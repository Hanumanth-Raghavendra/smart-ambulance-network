package com.smartambulance.service;

import com.smartambulance.domain.ClearanceLog;
import com.smartambulance.domain.EmergencyRequest;
import com.smartambulance.domain.Enums.ClearanceStatus;
import com.smartambulance.dto.Views.ClearanceView;
import com.smartambulance.geo.RoutePlan;
import com.smartambulance.repo.Repos.AmbulanceRepo;
import com.smartambulance.repo.Repos.ClearanceRepo;
import com.smartambulance.repo.Repos.RequestRepo;
import com.smartambulance.security.AuthUser;
import com.smartambulance.sim.EtaEngine;
import com.smartambulance.sim.SignalService;
import com.smartambulance.sim.SimClock;
import com.smartambulance.ws.Broadcaster;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Green-corridor requests: created per junction when a route is planned, decided by traffic police. */
@Service
public class ClearanceService {
    private final ClearanceRepo repo;
    private final RequestRepo requests;
    private final AmbulanceRepo ambulances;
    private final SettingsService settings;
    private final SignalService signals;
    private final EtaEngine eta;
    private final SimClock clock;
    private final Broadcaster broadcaster;
    private final StateLock lock;

    public ClearanceService(ClearanceRepo repo, RequestRepo requests, AmbulanceRepo ambulances, SettingsService settings,
                            SignalService signals, EtaEngine eta, SimClock clock, Broadcaster broadcaster, StateLock lock) {
        this.repo = repo;
        this.requests = requests;
        this.ambulances = ambulances;
        this.settings = settings;
        this.signals = signals;
        this.eta = eta;
        this.clock = clock;
        this.broadcaster = broadcaster;
        this.lock = lock;
    }

    /** Creates one clearance request per junction of the plan (auto-approved if the admin enabled that). */
    public List<ClearanceLog> createForLeg(EmergencyRequest r, RoutePlan plan) {
        double now = clock.now();
        var prediction = eta.predict(plan, 0, null, now, Set.of());
        boolean auto = settings.autoApprove();
        List<ClearanceLog> out = new ArrayList<>();
        int order = 0;
        for (RoutePlan.Stop s : plan.stops) {
            ClearanceLog l = new ClearanceLog();
            l.setRequestId(r.getId());
            l.setLeg(r.getLeg());
            l.setSignalId(s.signalId);
            l.setSignalName(s.name);
            l.setStopOrder(order++);
            l.setRequestedAtSim(now);
            l.setEtaAtRequestSec(prediction.stops().stream().filter(p -> p.signalId() == s.signalId)
                    .mapToDouble(p -> p.etaSec()).findFirst().orElse(0));
            if (auto) {
                l.setStatus(ClearanceStatus.APPROVED);
                l.setApproved(true);
                l.setDecidedAtSim(now);
            }
            out.add(repo.save(l));
        }
        broadcaster.police("CLEARANCES", Set.of());
        return out;
    }

    public ClearanceView view(ClearanceLog l, EmergencyRequest r) {
        String vehicle = r.getAmbulanceId() == null ? null
                : ambulances.findById(r.getAmbulanceId()).map(a -> a.getVehicleNo()).orElse(null);
        return new ClearanceView(l.getId(), l.getRequestId(), l.getLeg().name(), l.getSignalId(), l.getSignalName(),
                l.getStopOrder(), l.getPassedAtSim() != null && l.getStatus() == ClearanceStatus.PENDING
                ? "PASSED_UNDECIDED" : l.getStatus().name(),
                l.getEtaAtRequestSec(), l.getDelaySavedSec(), l.getWaitedSec(), r.getPatientName(), vehicle,
                r.getSeverity().name());
    }

    /** Clearance rows of every active request on its current leg (what the police console lists). */
    public List<ClearanceView> openForPolice() {
        List<ClearanceView> out = new ArrayList<>();
        for (EmergencyRequest r : requests.findByStatusIn(List.of(
                com.smartambulance.domain.Enums.RequestStatus.DISPATCHED,
                com.smartambulance.domain.Enums.RequestStatus.EN_ROUTE_TO_PATIENT,
                com.smartambulance.domain.Enums.RequestStatus.AT_PATIENT,
                com.smartambulance.domain.Enums.RequestStatus.EN_ROUTE_TO_HOSPITAL))) {
            for (ClearanceLog l : repo.findByRequestIdAndLegOrderByStopOrderAsc(r.getId(), r.getLeg())) out.add(view(l, r));
        }
        return out;
    }

    public ClearanceView approve(long id, AuthUser police) {
        return lock.run(() -> decide(id, police, true));
    }

    public ClearanceView reject(long id, AuthUser police) {
        return lock.run(() -> decide(id, police, false));
    }

    private ClearanceView decide(long id, AuthUser police, boolean approve) {
        ClearanceLog l = repo.findById(id).orElseThrow(() -> ApiException.notFound("Clearance request not found"));
        EmergencyRequest r = requests.findById(l.getRequestId()).orElseThrow();
        if (!r.getStatus().isActive()) throw ApiException.conflict("Trip is no longer active");
        if (l.getPassedAtSim() != null || l.getStatus() == ClearanceStatus.CANCELLED) {
            throw new ApiException(HttpStatus.CONFLICT, "The ambulance has already passed this junction");
        }
        applyDecision(l, police.id(), approve);
        repo.save(l);
        broadcaster.pushRequest(r);
        return view(l, r);
    }

    /** Approves or rejects every undecided junction of the request's current leg ("approve the whole corridor"). */
    public List<ClearanceView> decideAll(long requestId, AuthUser police, boolean approve) {
        return lock.run(() -> {
            EmergencyRequest r = requests.findById(requestId).orElseThrow(() -> ApiException.notFound("Request not found"));
            if (!r.getStatus().isActive()) throw ApiException.conflict("Trip is no longer active");
            List<ClearanceView> out = new ArrayList<>();
            for (ClearanceLog l : repo.findByRequestIdAndLegOrderByStopOrderAsc(requestId, r.getLeg())) {
                if (l.getPassedAtSim() != null || l.getStatus() == ClearanceStatus.CANCELLED) continue;
                ClearanceStatus s = l.getStatus();
                boolean change = approve
                        ? (s == ClearanceStatus.PENDING || s == ClearanceStatus.REJECTED || s == ClearanceStatus.EXPIRED)
                        : s != ClearanceStatus.REJECTED;
                if (change) {
                    applyDecision(l, police.id(), approve);
                    repo.save(l);
                }
                out.add(view(l, r));
            }
            broadcaster.pushRequest(r);
            return out;
        });
    }

    private void applyDecision(ClearanceLog l, long userId, boolean approve) {
        double now = clock.now();
        l.setDecidedBy(userId);
        l.setDecidedAtSim(now);
        if (approve) {
            if (l.getStatus() == ClearanceStatus.PENDING || l.getStatus() == ClearanceStatus.REJECTED
                    || l.getStatus() == ClearanceStatus.EXPIRED) {
                l.setStatus(ClearanceStatus.APPROVED);
            }
            l.setApproved(true);
        } else {
            l.setStatus(ClearanceStatus.REJECTED);
            l.setApproved(false);
            signals.release(l.getSignalId(), l.getRequestId());
        }
    }

    /** Closes all undecided/unpassed rows of a leg when the leg ends or the trip is cancelled. */
    public void closeLeg(long requestId, com.smartambulance.domain.Enums.Leg leg, boolean cancelled) {
        for (ClearanceLog l : repo.findByRequestIdAndLegOrderByStopOrderAsc(requestId, leg)) {
            signals.release(l.getSignalId(), requestId);
            if (l.getPassedAtSim() == null && (cancelled || l.getStatus() == ClearanceStatus.PENDING
                    || l.getStatus() == ClearanceStatus.APPROVED || l.getStatus() == ClearanceStatus.GREEN_SENT)) {
                l.setStatus(ClearanceStatus.CANCELLED);
                repo.save(l);
            }
        }
    }
}
