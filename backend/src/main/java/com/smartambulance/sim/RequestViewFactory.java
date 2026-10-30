package com.smartambulance.sim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartambulance.config.AppProperties;
import com.smartambulance.domain.*;
import com.smartambulance.domain.Enums.ClearanceStatus;
import com.smartambulance.domain.Enums.Leg;
import com.smartambulance.domain.Enums.RequestStatus;
import com.smartambulance.dto.Views.*;
import com.smartambulance.geo.RoutePlan;
import com.smartambulance.repo.Repos.*;
import com.smartambulance.sim.EtaEngine.Prediction;
import com.smartambulance.sim.EtaEngine.StopEta;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/** Builds the JSON snapshot of a request that every dashboard renders. */
@Component
public class RequestViewFactory {
    private final HospitalRepo hospitals;
    private final AmbulanceRepo ambulances;
    private final UserRepo users;
    private final ClearanceRepo clearances;
    private final AlertRepo alerts;
    private final PlanCodec codec;
    private final EtaEngine eta;
    private final SignalService signals;
    private final SimClock clock;
    private final AppProperties props;
    private final ObjectMapper mapper;

    public RequestViewFactory(HospitalRepo hospitals, AmbulanceRepo ambulances, UserRepo users, ClearanceRepo clearances,
                              AlertRepo alerts, PlanCodec codec, EtaEngine eta, SignalService signals, SimClock clock,
                              AppProperties props, ObjectMapper mapper) {
        this.hospitals = hospitals;
        this.ambulances = ambulances;
        this.users = users;
        this.clearances = clearances;
        this.alerts = alerts;
        this.codec = codec;
        this.eta = eta;
        this.signals = signals;
        this.clock = clock;
        this.props = props;
        this.mapper = mapper;
    }

    public static HospitalBrief brief(Hospital h) {
        return new HospitalBrief(h.getId(), h.getName(), h.getLat(), h.getLng(), h.getBedsAvailable(), h.getTotalBeds(),
                h.getSpecialities(), h.getContact(), h.isActive());
    }

    public AmbulanceBrief brief(Ambulance a) {
        String driver = a.getDriverId() == null ? null : users.findById(a.getDriverId()).map(AppUser::getName).orElse(null);
        return new AmbulanceBrief(a.getId(), a.getVehicleNo(), a.getLat(), a.getLng(), a.getStatus().name(),
                a.getDriverId(), driver, a.getActiveRequestId());
    }

    public List<RankedHospital> ranking(EmergencyRequest r) {
        if (r.getRankingJson() == null) return List.of();
        try {
            return mapper.readValue(r.getRankingJson(), new TypeReference<List<RankedHospital>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    public Set<Long> approvedSignals(List<ClearanceLog> logs) {
        return logs.stream()
                .filter(l -> l.getStatus() == ClearanceStatus.APPROVED || l.getStatus() == ClearanceStatus.GREEN_SENT)
                .map(ClearanceLog::getSignalId).collect(Collectors.toSet());
    }

    public RequestView build(EmergencyRequest r) {
        double now = clock.now();
        Hospital h = r.getHospitalId() == null ? null : hospitals.findById(r.getHospitalId()).orElse(null);
        Ambulance a = r.getAmbulanceId() == null ? null : ambulances.findById(r.getAmbulanceId()).orElse(null);
        List<RoutePlan> legs = codec.read(r);
        RoutePlan plan = legs.size() > r.getLeg().ordinal() ? legs.get(r.getLeg().ordinal()) : null;
        List<ClearanceLog> logs = plan == null ? List.of()
                : clearances.findByRequestIdAndLegOrderByStopOrderAsc(r.getId(), r.getLeg());

        double loading = props.sim().loadingRealSeconds() * clock.speed();
        Double etaPatient = null, etaHospital = null, etaHospitalNoClear = null;
        List<StopView> stops = new ArrayList<>();
        double total = 0, progress = r.getProgressMeters();
        List<double[]> poly = List.of();
        List<double[]> hospLeg = legs.size() > 1 ? legs.get(1).points : List.of();

        if (plan != null) {
            total = plan.totalMeters;
            poly = plan.points;
            Prediction pred = eta.predict(plan, progress, r.getWaitingSignalId(), now, approvedSignals(logs));
            Map<Long, StopEta> predBySignal = pred.stops().stream().collect(Collectors.toMap(StopEta::signalId, s -> s, (x, y) -> x));
            Map<Long, ClearanceLog> logBySignal = logs.stream().collect(Collectors.toMap(ClearanceLog::getSignalId, l -> l, (x, y) -> x));
            for (RoutePlan.Stop s : plan.stops) {
                ClearanceLog l = logBySignal.get(s.signalId);
                StopEta p = predBySignal.get(s.signalId);
                boolean passed = l != null && l.getPassedAtSim() != null;
                double[] pos = plan.points.get(s.vertex);
                stops.add(new StopView(s.signalId, s.name, pos[0], pos[1], Math.max(0, s.distance - progress),
                        passed || p == null ? null : p.etaSec(), passed || p == null ? null : p.etaNoClearanceSec(),
                        passed || p == null ? null : p.predictedWaitSec(),
                        l == null ? null : l.getId(), l == null ? null : l.getStatus().name(),
                        signals.isGreen(s.signalId, now), signals.isForced(s.signalId),
                        l == null ? 0 : l.getDelaySavedSec(), l == null ? 0 : l.getWaitedSec(), passed));
            }
            RequestStatus st = r.getStatus();
            double leg2 = legs.size() > 1 ? legs.get(1).baseSeconds : 0;
            if (st == RequestStatus.DISPATCHED || st == RequestStatus.EN_ROUTE_TO_PATIENT) {
                etaPatient = pred.totalSec();
                etaHospital = pred.totalSec() + loading + leg2;
                etaHospitalNoClear = pred.totalNoClearanceSec() + loading + leg2;
            } else if (st == RequestStatus.AT_PATIENT) {
                etaPatient = 0.0;
                etaHospital = loading + leg2;
                etaHospitalNoClear = etaHospital;
            } else if (st == RequestStatus.EN_ROUTE_TO_HOSPITAL) {
                etaPatient = 0.0;
                etaHospital = pred.totalSec();
                etaHospitalNoClear = pred.totalNoClearanceSec();
            } else if (st == RequestStatus.ARRIVED_AT_HOSPITAL || st == RequestStatus.COMPLETED) {
                etaPatient = 0.0;
                etaHospital = 0.0;
                etaHospitalNoClear = 0.0;
            }
        }

        AmbulanceBrief ab = null;
        if (a != null) {
            ab = brief(a);
            if (plan != null && r.getStatus().isMoving()) {
                double[] p = plan.pointAt(progress);
                ab = new AmbulanceBrief(ab.id(), ab.vehicleNo(), p[0], p[1], ab.status(), ab.driverId(), ab.driverName(), ab.activeRequestId());
            }
        }

        String waitingAt = null;
        if (r.getWaitingSignalId() != null && plan != null) {
            waitingAt = plan.stops.stream().filter(s -> s.signalId == r.getWaitingSignalId()).map(s -> s.name).findFirst().orElse(null);
        }

        Timeline tl = new Timeline(r.getSimCreated(), r.getSimAccepted(), r.getSimDispatched(), r.getSimArrivedPatient(),
                r.getSimPickedUp(), r.getSimArrivedHospital(), r.getSimCompleted());

        return new RequestView(r.getId(), r.getStatus().name(), r.getSeverity().name(), r.getRequiredSpeciality(),
                r.getDescription(), r.getPatientId(), r.getPatientName(), r.getPatientPhone(), r.getLat(), r.getLng(),
                r.getAddress(), h == null ? null : brief(h), ab, r.getGpsMode().name(), r.getLeg().name(), progress, total,
                etaPatient, etaHospital, etaHospitalNoClear, stops, poly, hospLeg, waitingAt, r.getConditionNote(),
                r.getHeartRate(), r.getSpo2(), ranking(r), r.getDelaySavedSec(), r.getWaitedSec(),
                alerts.countByRequestId(r.getId()), tl, r.getCreatedAt(), clock.speed());
    }
}
