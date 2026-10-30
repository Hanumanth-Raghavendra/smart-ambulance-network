package com.smartambulance.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartambulance.config.AppProperties;
import com.smartambulance.domain.*;
import com.smartambulance.domain.Enums.*;
import com.smartambulance.dto.Commands.Condition;
import com.smartambulance.dto.Commands.CreateRequest;
import com.smartambulance.dto.Views.RankedHospital;
import com.smartambulance.geo.RoutePlan;
import com.smartambulance.repo.Repos.*;
import com.smartambulance.security.AuthUser;
import com.smartambulance.sim.*;
import com.smartambulance.ws.Broadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The emergency workflow:
 * patient request -> hospital selection/acceptance -> ambulance dispatch -> (route, ETA, clearance, alerts: see TripEngine)
 * -> pickup -> hospital handover.
 */
@Service
public class RequestService {
    private static final Logger log = LoggerFactory.getLogger(RequestService.class);

    private final RequestRepo requests;
    private final HospitalRepo hospitals;
    private final AmbulanceRepo ambulances;
    private final UserRepo users;
    private final HospitalRanker ranker;
    private final RoadNetwork network;
    private final PlanCodec codec;
    private final ClearanceService clearance;
    private final SignalService signals;
    private final DriverAlertService driverAlerts;
    private final Broadcaster broadcaster;
    private final SimClock clock;
    private final AppProperties props;
    private final ObjectMapper mapper;
    private final StateLock lock;

    public RequestService(RequestRepo requests, HospitalRepo hospitals, AmbulanceRepo ambulances, UserRepo users,
                          HospitalRanker ranker, RoadNetwork network, PlanCodec codec, ClearanceService clearance,
                          SignalService signals, DriverAlertService driverAlerts, Broadcaster broadcaster,
                          SimClock clock, AppProperties props, ObjectMapper mapper, StateLock lock) {
        this.requests = requests;
        this.hospitals = hospitals;
        this.ambulances = ambulances;
        this.users = users;
        this.ranker = ranker;
        this.network = network;
        this.codec = codec;
        this.clearance = clearance;
        this.signals = signals;
        this.driverAlerts = driverAlerts;
        this.broadcaster = broadcaster;
        this.clock = clock;
        this.props = props;
        this.mapper = mapper;
        this.lock = lock;
    }

    public EmergencyRequest get(long id) {
        return requests.findById(id).orElseThrow(() -> ApiException.notFound("Emergency request " + id + " not found"));
    }

    // ------------------------------------------------------------------ 1. patient request

    public EmergencyRequest create(AuthUser me, CreateRequest dto) {
        return lock.run(() -> {
            if (requests.findByPatientIdOrderByIdDesc(me.id()).stream().anyMatch(x -> x.getStatus().isActive())) {
                throw ApiException.conflict("You already have an active emergency request");
            }
            if (network.isEmpty() || network.distanceToNetwork(dto.lat(), dto.lng()) > props.serviceAreaMaxMeters()) {
                throw ApiException.badRequest("This location is outside the service area");
            }
            AppUser patient = users.findById(me.id()).orElseThrow();
            EmergencyRequest r = new EmergencyRequest();
            r.setPatientId(me.id());
            r.setPatientName(patient.getName());
            r.setPatientPhone(dto.phone() != null && !dto.phone().isBlank() ? dto.phone() : patient.getPhone());
            r.setLat(dto.lat());
            r.setLng(dto.lng());
            r.setAddress(dto.address());
            r.setSeverity(dto.severity());
            r.setRequiredSpeciality(dto.requiredSpeciality() == null || dto.requiredSpeciality().isBlank()
                    ? "GENERAL" : dto.requiredSpeciality().toUpperCase(Locale.ROOT));
            r.setDescription(dto.description());
            r.setSimCreated(clock.now());
            requests.save(r);
            assignHospital(r);
            return r;
        });
    }

    // ------------------------------------------------------------------ 2. hospital selection

    private Set<Long> rejected(EmergencyRequest r) {
        String s = r.getRejectedHospitalIds();
        if (s == null || s.isBlank()) return new HashSet<>();
        return Arrays.stream(s.split(",")).filter(x -> !x.isBlank()).map(Long::parseLong).collect(Collectors.toSet());
    }

    /** Ranks hospitals and offers the request to the best one that has not refused it yet. */
    private void assignHospital(EmergencyRequest r) {
        List<RankedHospital> ranking = ranker.rank(r.getLat(), r.getLng(), r.getSeverity(), r.getRequiredSpeciality(), Set.of());
        try {
            r.setRankingJson(mapper.writeValueAsString(ranking));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        Set<Long> refused = rejected(r);
        Optional<RankedHospital> top = ranking.stream().filter(x -> !refused.contains(x.hospitalId())).findFirst();
        if (top.isEmpty()) {
            r.setHospitalId(null);
            r.setStatus(RequestStatus.NO_HOSPITAL);
            requests.save(r);
            broadcaster.notifyUser(r.getPatientId(), "error", "No hospital available",
                    "No hospital with a free bed could take your request. Please call 108 / 112.");
            broadcaster.pushRequest(r);
            return;
        }
        Hospital h = hospitals.findById(top.get().hospitalId()).orElseThrow();
        r.setHospitalId(h.getId());
        r.setHospitalAssignedAt(Instant.now());
        r.setStatus(RequestStatus.PENDING_HOSPITAL);
        requests.save(r);

        for (AppUser staff : users.findByHospitalId(h.getId())) {
            broadcaster.notifyUser(staff.getId(), "warning", "Incoming " + r.getSeverity() + " emergency",
                    r.getPatientName() + " needs " + r.getRequiredSpeciality() + " care. Accept or reject.");
        }
        broadcaster.sms(h.getContact(), "New " + r.getSeverity() + " emergency request #" + r.getId());
        broadcaster.notifyUser(r.getPatientId(), "info", "Request sent", "Waiting for " + h.getName() + " to accept.");
        broadcaster.pushRequest(r);
    }

    public EmergencyRequest hospitalAccept(AuthUser me, long id) {
        return lock.run(() -> {
            EmergencyRequest r = get(id);
            requireHospital(me, r);
            if (r.getStatus() != RequestStatus.PENDING_HOSPITAL) throw ApiException.conflict("Request is not waiting for a decision");
            Hospital h = hospitals.findById(r.getHospitalId()).orElseThrow();
            if (h.getBedsAvailable() <= 0) throw ApiException.conflict("No free beds. Update the bed count or reject the request.");
            h.setBedsAvailable(h.getBedsAvailable() - 1);
            hospitals.save(h);
            r.setBedReserved(true);
            r.setSimAccepted(clock.now());
            r.setStatus(RequestStatus.WAITING_AMBULANCE);
            requests.save(r);
            broadcaster.notifyUser(r.getPatientId(), "success", "Hospital ready", h.getName() + " accepted your request.");
            broadcaster.sms(r.getPatientPhone(), h.getName() + " has accepted your emergency request.");
            broadcaster.pushRequest(r);
            tryDispatch(r);
            return get(id);
        });
    }

    public EmergencyRequest hospitalReject(AuthUser me, long id, String reason) {
        return lock.run(() -> {
            EmergencyRequest r = get(id);
            requireHospital(me, r);
            if (r.getStatus() != RequestStatus.PENDING_HOSPITAL) throw ApiException.conflict("Request is not waiting for a decision");
            rejectInternal(r, reason);
            return get(id);
        });
    }

    /** Hospital did not answer in time: treated as a rejection. */
    public void hospitalTimeout(EmergencyRequest r) {
        lock.run(() -> {
            if (r.getStatus() == RequestStatus.PENDING_HOSPITAL) rejectInternal(r, "No response in time");
        });
    }

    private void rejectInternal(EmergencyRequest r, String reason) {
        Set<Long> refused = rejected(r);
        if (r.getHospitalId() != null) refused.add(r.getHospitalId());
        r.setRejectedHospitalIds(refused.stream().map(String::valueOf).collect(Collectors.joining(",")));
        log.info("Request {} refused by hospital {} ({})", r.getId(), r.getHospitalId(), reason);
        broadcaster.notifyUser(r.getPatientId(), "warning", "Hospital unavailable", "Finding the next best hospital...");
        assignHospital(r);
    }

    private void requireHospital(AuthUser me, EmergencyRequest r) {
        if (!me.is(Role.ADMIN) && (me.hospitalId() == null || !me.hospitalId().equals(r.getHospitalId()))) {
            throw ApiException.forbidden("This request belongs to another hospital");
        }
    }

    // ------------------------------------------------------------------ 3. ambulance dispatch

    /** Assigns the ambulance with the shortest estimated travel time to the patient. */
    public boolean tryDispatch(EmergencyRequest r) {
        return lock.run(() -> {
            if (r.getStatus() != RequestStatus.WAITING_AMBULANCE) return false;
            Ambulance best = null;
            double bestT = Double.MAX_VALUE;
            for (Ambulance a : ambulances.findByStatus(AmbulanceStatus.AVAILABLE)) {
                double t;
                try {
                    t = network.route("ESTIMATE", a.getLat(), a.getLng(), r.getLat(), r.getLng()).baseSeconds;
                } catch (RuntimeException e) {
                    continue;
                }
                if (t < bestT) { bestT = t; best = a; }
            }
            if (best == null) return false;

            Hospital h = hospitals.findById(r.getHospitalId()).orElseThrow();
            RoutePlan toPatient = network.route("TO_PATIENT", best.getLat(), best.getLng(), r.getLat(), r.getLng());
            RoutePlan toHospital = network.route("TO_HOSPITAL", r.getLat(), r.getLng(), h.getLat(), h.getLng());

            best.setStatus(AmbulanceStatus.ASSIGNED);
            best.setActiveRequestId(r.getId());
            best.setUpdatedAt(Instant.now());
            ambulances.save(best);

            r.setAmbulanceId(best.getId());
            codec.write(r, List.of(toPatient, toHospital));
            r.setLeg(Leg.TO_PATIENT);
            r.setProgressMeters(0);
            r.setWaitingSignalId(null);
            r.setWaitSinceSim(null);
            r.setStatus(RequestStatus.DISPATCHED);
            r.setSimDispatched(clock.now());
            requests.save(r);

            clearance.createForLeg(r, toPatient);
            broadcaster.notifyUser(best.getDriverId(), "warning", "New emergency dispatch",
                    "Pick up " + r.getPatientName() + " and take them to " + h.getName() + ".");
            broadcaster.ambulance(best.getId(), "ASSIGNED", r.getId());
            broadcaster.notifyUser(r.getPatientId(), "success", "Ambulance dispatched", best.getVehicleNo() + " is assigned to you.");
            broadcaster.sms(r.getPatientPhone(), "Ambulance " + best.getVehicleNo() + " dispatched to your location.");
            broadcaster.pushRequest(r);
            return true;
        });
    }

    public EmergencyRequest driverAccept(AuthUser me, long id, GpsMode mode) {
        return lock.run(() -> {
            EmergencyRequest r = get(id);
            requireDriverOf(me, r);
            if (r.getStatus() != RequestStatus.DISPATCHED) throw ApiException.conflict("Dispatch already accepted or no longer valid");
            r.setGpsMode(mode == null ? GpsMode.SIMULATED : mode);
            r.setStatus(RequestStatus.EN_ROUTE_TO_PATIENT);
            requests.save(r);
            broadcaster.notifyUser(r.getPatientId(), "success", "Ambulance on the way", "Stay where you are and keep your phone close.");
            broadcaster.pushRequest(r);
            return r;
        });
    }

    public void requireDriverOf(AuthUser me, EmergencyRequest r) {
        if (me.is(Role.ADMIN)) return;
        Ambulance a = r.getAmbulanceId() == null ? null : ambulances.findById(r.getAmbulanceId()).orElse(null);
        if (a == null || a.getDriverId() == null || a.getDriverId() != me.id()) {
            throw ApiException.forbidden("This trip is not assigned to your ambulance");
        }
    }

    // ------------------------------------------------------------------ 4. arrival, pickup, handover

    /** Ambulance reached the end of the current leg. */
    public void onArrived(EmergencyRequest r) {
        lock.run(() -> {
            Leg leg = r.getLeg();
            clearance.closeLeg(r.getId(), leg, false);
            signals.releaseAll(r.getId());
            driverAlerts.clear(r.getId());
            r.setWaitingSignalId(null);
            r.setWaitSinceSim(null);
            Hospital h = r.getHospitalId() == null ? null : hospitals.findById(r.getHospitalId()).orElse(null);
            if (leg == Leg.TO_PATIENT) {
                r.setStatus(RequestStatus.AT_PATIENT);
                r.setSimArrivedPatient(clock.now());
                if (r.getGpsMode() == GpsMode.SIMULATED) {
                    r.setLoadingUntilMs(System.currentTimeMillis() + (long) (props.sim().loadingRealSeconds() * 1000));
                }
                broadcaster.notifyUser(r.getPatientId(), "success", "Ambulance has arrived", "Paramedics are with you.");
            } else {
                r.setStatus(RequestStatus.ARRIVED_AT_HOSPITAL);
                r.setSimArrivedHospital(clock.now());
                if (h != null) {
                    for (AppUser staff : users.findByHospitalId(h.getId())) {
                        broadcaster.notifyUser(staff.getId(), "warning", "Ambulance at the gate",
                                "Patient " + r.getPatientName() + " has arrived. Confirm handover when done.");
                    }
                }
                broadcaster.notifyUser(r.getPatientId(), "success", "Arrived at hospital", "Handover in progress.");
            }
            requests.save(r);
            broadcaster.pushRequest(r);
        });
    }

    public EmergencyRequest pickup(AuthUser me, long id) {
        return lock.run(() -> {
            EmergencyRequest r = get(id);
            requireDriverOf(me, r);
            pickupInternal(r);
            return r;
        });
    }

    /** Patient on board: plan the second leg from the pickup point to the hospital and request its corridor. */
    public void pickupInternal(EmergencyRequest r) {
        lock.run(() -> {
            if (r.getStatus() != RequestStatus.AT_PATIENT) throw ApiException.conflict("The ambulance has not reached the patient yet");
            Hospital h = hospitals.findById(r.getHospitalId()).orElseThrow();
            RoutePlan toHospital = network.route("TO_HOSPITAL", r.getLat(), r.getLng(), h.getLat(), h.getLng());
            List<RoutePlan> legs = new ArrayList<>(codec.read(r));
            while (legs.size() < 2) legs.add(toHospital);
            legs.set(1, toHospital);
            codec.write(r, legs);
            r.setLeg(Leg.TO_HOSPITAL);
            r.setProgressMeters(0);
            r.setLoadingUntilMs(null);
            r.setStatus(RequestStatus.EN_ROUTE_TO_HOSPITAL);
            r.setSimPickedUp(clock.now());
            requests.save(r);
            clearance.createForLeg(r, toHospital);
            for (AppUser staff : users.findByHospitalId(h.getId())) {
                broadcaster.notifyUser(staff.getId(), "info", "Patient on board", "Ambulance is heading to your hospital.");
            }
            broadcaster.notifyUser(r.getPatientId(), "info", "On the way to " + h.getName(), "Green corridor requested.");
            broadcaster.pushRequest(r);
        });
    }

    /** Manual "I have arrived" for GPS mode when automatic geofence detection does not trigger. */
    public EmergencyRequest markArrived(AuthUser me, long id) {
        return lock.run(() -> {
            EmergencyRequest r = get(id);
            requireDriverOf(me, r);
            if (!r.getStatus().isMoving()) throw ApiException.conflict("The ambulance is not travelling");
            RoutePlan plan = codec.current(r);
            if (plan != null) r.setProgressMeters(plan.totalMeters);
            onArrived(r);
            return get(id);
        });
    }

    public EmergencyRequest handover(AuthUser me, long id) {
        return lock.run(() -> {
            EmergencyRequest r = get(id);
            requireHospital(me, r);
            if (r.getStatus() != RequestStatus.ARRIVED_AT_HOSPITAL) throw ApiException.conflict("The ambulance has not arrived yet");
            r.setStatus(RequestStatus.COMPLETED);
            r.setSimCompleted(clock.now());
            requests.save(r);
            freeAmbulance(r);
            broadcaster.notifyUser(r.getPatientId(), "success", "Handover complete", "You are now in the hospital's care.");
            broadcaster.pushRequest(r);
            return r;
        });
    }

    private void freeAmbulance(EmergencyRequest r) {
        if (r.getAmbulanceId() == null) return;
        ambulances.findById(r.getAmbulanceId()).ifPresent(a -> {
            a.setStatus(AmbulanceStatus.AVAILABLE);
            a.setActiveRequestId(null);
            a.setUpdatedAt(Instant.now());
            ambulances.save(a);
        });
    }

    // ------------------------------------------------------------------ cancel & condition

    public EmergencyRequest cancel(AuthUser me, long id) {
        return lock.run(() -> {
            EmergencyRequest r = get(id);
            if (!me.is(Role.ADMIN) && (r.getPatientId() == null || r.getPatientId() != me.id())) {
                throw ApiException.forbidden("Not your request");
            }
            switch (r.getStatus()) {
                case PENDING_HOSPITAL, WAITING_AMBULANCE, DISPATCHED, EN_ROUTE_TO_PATIENT, AT_PATIENT -> {}
                default -> throw ApiException.conflict("This request can no longer be cancelled");
            }
            if (r.isBedReserved() && r.getHospitalId() != null) {
                hospitals.findById(r.getHospitalId()).ifPresent(h -> {
                    h.setBedsAvailable(Math.min(h.getTotalBeds(), h.getBedsAvailable() + 1));
                    hospitals.save(h);
                });
                r.setBedReserved(false);
            }
            if (r.getAmbulanceId() != null) {
                clearance.closeLeg(r.getId(), r.getLeg(), true);
                signals.releaseAll(r.getId());
                driverAlerts.clear(r.getId());
                freeAmbulance(r);
                broadcaster.ambulance(r.getAmbulanceId(), "CANCELLED", r.getId());
            }
            r.setStatus(RequestStatus.CANCELLED);
            r.setWaitingSignalId(null);
            requests.save(r);
            if (r.getHospitalId() != null) {
                for (AppUser staff : users.findByHospitalId(r.getHospitalId())) {
                    broadcaster.notifyUser(staff.getId(), "info", "Request cancelled", "Request #" + r.getId() + " was cancelled.");
                }
            }
            broadcaster.pushRequest(r);
            return r;
        });
    }

    public EmergencyRequest updateCondition(AuthUser me, long id, Condition c) {
        return lock.run(() -> {
            EmergencyRequest r = get(id);
            requireDriverOf(me, r);
            if (!r.getStatus().isActive()) throw ApiException.conflict("Trip is closed");
            r.setConditionNote(c.note());
            r.setHeartRate(c.heartRate());
            r.setSpo2(c.spo2());
            requests.save(r);
            if (r.getHospitalId() != null) {
                broadcaster.hospital(r.getHospitalId(), "CONDITION", Map.of("requestId", r.getId()));
            }
            broadcaster.pushRequest(r);
            return r;
        });
    }
}
