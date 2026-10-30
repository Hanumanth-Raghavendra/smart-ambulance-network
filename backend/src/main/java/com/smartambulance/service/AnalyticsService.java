package com.smartambulance.service;

import com.smartambulance.domain.ClearanceLog;
import com.smartambulance.domain.EmergencyRequest;
import com.smartambulance.domain.Enums.ClearanceStatus;
import com.smartambulance.domain.Enums.RequestStatus;
import com.smartambulance.dto.Views.*;
import com.smartambulance.repo.Repos.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** Response-time and green-corridor analytics for the admin dashboard. All durations are simulated seconds. */
@Service
public class AnalyticsService {
    private final RequestRepo requests;
    private final ClearanceRepo clearances;
    private final AlertRepo alerts;
    private final HospitalRepo hospitals;

    public AnalyticsService(RequestRepo requests, ClearanceRepo clearances, AlertRepo alerts, HospitalRepo hospitals) {
        this.requests = requests;
        this.clearances = clearances;
        this.alerts = alerts;
        this.hospitals = hospitals;
    }

    private static Double avg(List<Double> xs) {
        return xs.isEmpty() ? null : xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    public Analytics compute() {
        List<EmergencyRequest> all = requests.findAll();
        List<ClearanceLog> logs = clearances.findAll();

        long completed = all.stream().filter(r -> r.getStatus() == RequestStatus.COMPLETED).count();
        long active = all.stream().filter(r -> r.getStatus().isActive()).count();
        long cancelled = all.stream().filter(r -> r.getStatus() == RequestStatus.CANCELLED).count();

        List<Double> response = new ArrayList<>(), trip = new ArrayList<>(), handover = new ArrayList<>();
        List<TripPoint> points = new ArrayList<>();
        double saved = 0, waited = 0;
        for (EmergencyRequest r : all) {
            if (r.getSimArrivedPatient() != null && r.getSimCreated() != null) response.add(r.getSimArrivedPatient() - r.getSimCreated());
            if (r.getSimArrivedHospital() != null && r.getSimDispatched() != null) {
                double actual = r.getSimArrivedHospital() - r.getSimDispatched();
                trip.add(actual);
                points.add(new TripPoint(r.getId(), r.getSeverity().name(), round(actual),
                        round(actual + r.getDelaySavedSec()), round(r.getDelaySavedSec())));
            }
            if (r.getSimCompleted() != null && r.getSimArrivedHospital() != null) handover.add(r.getSimCompleted() - r.getSimArrivedHospital());
            saved += r.getDelaySavedSec();
            waited += r.getWaitedSec();
        }
        points.sort(Comparator.comparingLong(TripPoint::requestId));
        double avgSaved = points.isEmpty() ? 0 : saved / points.size();

        long approved = logs.stream().filter(ClearanceLog::isApproved).count();
        long rejected = logs.stream().filter(l -> l.getStatus() == ClearanceStatus.REJECTED).count();
        long green = logs.stream().filter(l -> l.getGreenSentAtSim() != null).count();

        Map<Long, List<ClearanceLog>> bySignal = logs.stream().filter(l -> l.getPassedAtSim() != null)
                .collect(Collectors.groupingBy(ClearanceLog::getSignalId));
        List<SignalStat> signalStats = bySignal.entrySet().stream().map(e -> new SignalStat(e.getKey(),
                        e.getValue().get(0).getSignalName(),
                        e.getValue().stream().filter(ClearanceLog::isApproved).count(),
                        round(e.getValue().stream().mapToDouble(ClearanceLog::getDelaySavedSec).sum()),
                        round(e.getValue().stream().mapToDouble(ClearanceLog::getWaitedSec).sum())))
                .sorted(Comparator.comparingDouble((SignalStat s) -> s.savedSec() + s.waitedSec()).reversed())
                .limit(10).toList();

        Map<Long, Long> acceptedBy = all.stream().filter(r -> r.getSimAccepted() != null && r.getHospitalId() != null)
                .collect(Collectors.groupingBy(EmergencyRequest::getHospitalId, Collectors.counting()));
        List<HospitalStat> hospitalStats = hospitals.findAll().stream()
                .map(h -> new HospitalStat(h.getId(), h.getName(), acceptedBy.getOrDefault(h.getId(), 0L))).toList();

        return new Analytics(all.size(), completed, active, cancelled, avg(response), avg(trip), avg(handover),
                round(saved), round(avgSaved), round(waited), logs.size(), approved, rejected, green, alerts.count(),
                points, signalStats, hospitalStats);
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
