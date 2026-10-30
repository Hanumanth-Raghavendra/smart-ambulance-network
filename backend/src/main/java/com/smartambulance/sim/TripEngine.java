package com.smartambulance.sim;

import com.smartambulance.config.AppProperties;
import com.smartambulance.domain.*;
import com.smartambulance.domain.Enums.ClearanceStatus;
import com.smartambulance.domain.Enums.GpsMode;
import com.smartambulance.domain.Enums.RequestStatus;
import com.smartambulance.dto.Commands.GpsPing;
import com.smartambulance.dto.Views.FleetItem;
import com.smartambulance.geo.RoutePlan;
import com.smartambulance.geo.RoutePlan.Stop;
import com.smartambulance.repo.Repos.*;
import com.smartambulance.security.AuthUser;
import com.smartambulance.service.ApiException;
import com.smartambulance.service.RequestService;
import com.smartambulance.service.StateLock;
import com.smartambulance.sim.EtaEngine.Prediction;
import com.smartambulance.sim.EtaEngine.StopEta;
import com.smartambulance.ws.Broadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The real-time heart of the platform. On every tick (and on every real GPS update) it
 * <ol>
 *   <li>moves simulated ambulances along their planned route, stopping at red lights,</li>
 *   <li>recomputes junction-wise ETAs,</li>
 *   <li>sends GREEN requests to signals whose approved corridor is due (ETA &lt;= lead time),</li>
 *   <li>raises give-way alerts for opted-in drivers ahead of the ambulance,</li>
 *   <li>streams everything to the dashboards over WebSocket.</li>
 * </ol>
 */
@Component
public class TripEngine {
    private static final Logger log = LoggerFactory.getLogger(TripEngine.class);
    private static final List<RequestStatus> LIVE = List.of(RequestStatus.PENDING_HOSPITAL, RequestStatus.WAITING_AMBULANCE,
            RequestStatus.DISPATCHED, RequestStatus.EN_ROUTE_TO_PATIENT, RequestStatus.AT_PATIENT, RequestStatus.EN_ROUTE_TO_HOSPITAL);

    private final RequestRepo requests;
    private final AmbulanceRepo ambulances;
    private final ClearanceRepo clearances;
    private final PingRepo pings;
    private final PlanCodec codec;
    private final SignalService signals;
    private final EtaEngine eta;
    private final SimClock clock;
    private final AppProperties props;
    private final RequestService service;
    private final DriverAlertService driverAlerts;
    private final RequestViewFactory views;
    private final Broadcaster broadcaster;
    private final StateLock lock;

    private long lastTickNanos = System.nanoTime();
    private final Map<Long, Integer> tickCounter = new HashMap<>();

    public TripEngine(RequestRepo requests, AmbulanceRepo ambulances, ClearanceRepo clearances, PingRepo pings,
                      PlanCodec codec, SignalService signals, EtaEngine eta, SimClock clock, AppProperties props,
                      RequestService service, DriverAlertService driverAlerts, RequestViewFactory views,
                      Broadcaster broadcaster, StateLock lock) {
        this.requests = requests;
        this.ambulances = ambulances;
        this.clearances = clearances;
        this.pings = pings;
        this.codec = codec;
        this.signals = signals;
        this.eta = eta;
        this.clock = clock;
        this.props = props;
        this.service = service;
        this.driverAlerts = driverAlerts;
        this.views = views;
        this.broadcaster = broadcaster;
        this.lock = lock;
    }

    // ------------------------------------------------------------------ scheduler

    @Scheduled(fixedDelayString = "${san.sim.tick-millis:500}", initialDelayString = "3000")
    public void tick() {
        try {
            lock.run(this::doTick);
        } catch (Exception e) {
            log.error("Simulation tick failed", e);
        }
    }

    private void doTick() {
        long n = System.nanoTime();
        double dtReal = Math.min(2.0, (n - lastTickNanos) / 1e9);
        lastTickNanos = n;
        double dtSim = dtReal * clock.speed();
        double now = clock.now();

        for (EmergencyRequest r : requests.findByStatusIn(LIVE)) {
            try {
                step(r, dtSim, now);
            } catch (Exception e) {
                log.error("Step failed for request {}", r.getId(), e);
            }
        }
        broadcaster.signals(signals.snapshot());
        broadcaster.fleet(ambulances.findAll().stream()
                .map(a -> new FleetItem(a.getId(), a.getVehicleNo(), a.getLat(), a.getLng(), a.getStatus().name(), a.getActiveRequestId()))
                .collect(Collectors.toList()));
    }

    private void step(EmergencyRequest r, double dtSim, double now) {
        switch (r.getStatus()) {
            case PENDING_HOSPITAL -> {
                Instant since = r.getHospitalAssignedAt();
                if (since != null && Instant.now().isAfter(since.plusSeconds(props.sim().hospitalResponseTimeoutSeconds()))) {
                    service.hospitalTimeout(r);
                }
            }
            case WAITING_AMBULANCE -> service.tryDispatch(r);
            case DISPATCHED -> broadcaster.pushRequest(r);
            case AT_PATIENT -> {
                if (r.getGpsMode() == GpsMode.SIMULATED && r.getLoadingUntilMs() != null
                        && System.currentTimeMillis() >= r.getLoadingUntilMs()) {
                    service.pickupInternal(r);
                }
            }
            case EN_ROUTE_TO_PATIENT, EN_ROUTE_TO_HOSPITAL -> {
                RoutePlan plan = codec.current(r);
                if (plan == null) return;
                if (r.getGpsMode() == GpsMode.SIMULATED) move(r, plan, dtSim, now);
                afterMove(r, plan, now, false);
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ simulated movement

    private void move(EmergencyRequest r, RoutePlan plan, double dtSim, double now) {
        double progress = r.getProgressMeters();
        double remaining = dtSim;
        double t = now;

        if (r.getWaitingSignalId() != null) {
            long sid = r.getWaitingSignalId();
            if (!signals.isGreen(sid, t)) return;                 // still held at the red light
            Stop stop = plan.stops.stream().filter(s -> s.signalId == sid).findFirst().orElse(null);
            if (stop != null) finishPass(r, stop, t, t - (r.getWaitSinceSim() == null ? t : r.getWaitSinceSim()));
            r.setWaitingSignalId(null);
            r.setWaitSinceSim(null);
        }

        while (remaining > 1e-6 && progress < plan.totalMeters) {
            int seg = plan.segmentIndexAt(progress);
            double segEnd = plan.cum[seg + 1];
            double speed = plan.segSpeedMps[seg];
            double need = (segEnd - progress) / speed;
            if (need > remaining) {
                progress += speed * remaining;
                break;
            }
            progress = segEnd;
            remaining -= need;
            t += need;
            Stop stop = plan.stopAtVertex(seg + 1);
            if (stop != null) {
                if (signals.isGreen(stop.signalId, t)) {
                    finishPass(r, stop, t, 0);
                } else {
                    r.setWaitingSignalId(stop.signalId);
                    r.setWaitSinceSim(t);
                    break;
                }
            }
        }
        r.setProgressMeters(Math.min(progress, plan.totalMeters));
    }

    /** The ambulance has crossed a junction: book the delay avoided (or suffered) and release the signal. */
    private void finishPass(EmergencyRequest r, Stop stop, double t, double waited) {
        boolean forced = signals.isForcedBy(stop.signalId, r.getId());
        double saved = forced ? signals.naturalWait(stop.signalId, t) : 0;
        ClearanceLog l = clearances.findByRequestIdAndLegOrderByStopOrderAsc(r.getId(), r.getLeg()).stream()
                .filter(x -> x.getSignalId() == stop.signalId).findFirst().orElse(null);
        if (l != null) {
            l.setPassedAtSim(t);
            l.setDelaySavedSec(saved);
            l.setWaitedSec(waited);
            if (l.getStatus() == ClearanceStatus.APPROVED || l.getStatus() == ClearanceStatus.GREEN_SENT) {
                l.setStatus(ClearanceStatus.PASSED);
            }
            clearances.save(l);
        }
        r.setDelaySavedSec(r.getDelaySavedSec() + saved);
        r.setWaitedSec(r.getWaitedSec() + waited);
        signals.release(stop.signalId, r.getId());
    }

    // ------------------------------------------------------------------ real GPS input

    /** Real GPS update from the ambulance driver's device (used when the trip runs in GPS mode). */
    public void reportLocation(AuthUser me, long ambulanceId, GpsPing p) {
        lock.run(() -> {
            Ambulance a = ambulances.findById(ambulanceId).orElseThrow(() -> ApiException.notFound("Ambulance not found"));
            if (!me.is(Enums.Role.ADMIN) && (a.getDriverId() == null || a.getDriverId() != me.id())) {
                throw ApiException.forbidden("Not your ambulance");
            }
            a.setLat(p.lat());
            a.setLng(p.lng());
            a.setUpdatedAt(Instant.now());
            ambulances.save(a);
            if (a.getActiveRequestId() == null) return;
            EmergencyRequest r = requests.findById(a.getActiveRequestId()).orElse(null);
            if (r == null || !r.getStatus().isMoving() || r.getGpsMode() != GpsMode.GPS) return;
            RoutePlan plan = codec.current(r);
            if (plan == null) return;
            double[] nearest = plan.nearest(p.lat(), p.lng(), r.getProgressMeters());
            r.setProgressMeters(Math.max(r.getProgressMeters(), nearest[0]));
            afterMove(r, plan, clock.now(), true);
        });
    }

    // ------------------------------------------------------------------ shared per-update pipeline

    private void afterMove(EmergencyRequest r, RoutePlan plan, double now, boolean gps) {
        double progress = r.getProgressMeters();
        if (gps) passStopsUpTo(r, plan, progress, now);

        double[] pos = plan.pointAt(progress);
        Ambulance a = r.getAmbulanceId() == null ? null : ambulances.findById(r.getAmbulanceId()).orElse(null);
        if (a != null) {
            a.setLat(pos[0]);
            a.setLng(pos[1]);
            a.setUpdatedAt(Instant.now());
            ambulances.save(a);
        }
        int c = tickCounter.merge(r.getId(), 1, Integer::sum);
        if (gps || c % 4 == 0) {
            LocationPing ping = new LocationPing();
            ping.setRequestId(r.getId());
            ping.setAmbulanceId(r.getAmbulanceId());
            ping.setLat(pos[0]);
            ping.setLng(pos[1]);
            ping.setSimTime(now);
            ping.setSpeedKmph(r.getWaitingSignalId() != null ? 0 : plan.segSpeedMps[plan.segmentIndexAt(progress)] * 3.6);
            pings.save(ping);
        }

        boolean arrived = progress >= plan.totalMeters - (gps ? 40 : 0.5);
        if (arrived) {
            if (gps) {
                r.setProgressMeters(plan.totalMeters);
                passStopsUpTo(r, plan, plan.totalMeters, now);
            }
            tickCounter.remove(r.getId());
            service.onArrived(r);
            return;
        }
        coordinate(r, plan, progress, now);
        driverAlerts.process(r, plan, progress, now);
        requests.save(r);
        broadcaster.pushRequest(r);
    }

    private void passStopsUpTo(EmergencyRequest r, RoutePlan plan, double progress, double now) {
        for (Stop s : plan.stops) {
            if (s.distance <= progress + 1.0) {
                boolean done = clearances.findByRequestIdAndLegOrderByStopOrderAsc(r.getId(), r.getLeg()).stream()
                        .anyMatch(l -> l.getSignalId() == s.signalId && l.getPassedAtSim() != null);
                if (!done) finishPass(r, s, now, 0);
            }
        }
    }

    /** Signal Coordinator: forces GREEN at approved junctions once the ambulance is within the lead time. */
    private void coordinate(EmergencyRequest r, RoutePlan plan, double progress, double now) {
        List<ClearanceLog> logs = clearances.findByRequestIdAndLegOrderByStopOrderAsc(r.getId(), r.getLeg());
        Prediction pred = eta.predict(plan, progress, r.getWaitingSignalId(), now, views.approvedSignals(logs));
        Map<Long, StopEta> bySignal = pred.stops().stream().collect(Collectors.toMap(StopEta::signalId, s -> s, (x, y) -> x));
        double lead = props.clearance().leadTimeSeconds();
        double maxHold = props.clearance().maxHoldSeconds();

        for (ClearanceLog l : logs) {
            if (l.getPassedAtSim() != null) continue;
            switch (l.getStatus()) {
                case APPROVED -> {
                    StopEta p = bySignal.get(l.getSignalId());
                    if (p != null && p.etaSec() <= lead) {
                        signals.force(l.getSignalId(), r.getId());
                        l.setStatus(ClearanceStatus.GREEN_SENT);
                        l.setGreenSentAtSim(now);
                        clearances.save(l);
                    }
                }
                case GREEN_SENT -> {
                    if (l.getGreenSentAtSim() != null && now - l.getGreenSentAtSim() > maxHold) {
                        signals.release(l.getSignalId(), r.getId());   // safety: never hold a junction forever
                        l.setStatus(ClearanceStatus.EXPIRED);
                        clearances.save(l);
                    } else {
                        signals.force(l.getSignalId(), r.getId());
                    }
                }
                case REJECTED, PENDING, EXPIRED, CANCELLED -> signals.release(l.getSignalId(), r.getId());
                default -> { }
            }
        }
    }
}
