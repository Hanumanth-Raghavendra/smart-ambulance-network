package com.smartambulance.dto;

import java.time.Instant;
import java.util.List;

/** JSON shapes sent to the web clients (REST and WebSocket). */
public final class Views {
    private Views() {}

    public record UserView(Long id, String name, String email, String phone, String role, boolean active,
                           Long hospitalId, boolean alertsOptIn, Double lastLat, Double lastLng) {}

    public record HospitalBrief(Long id, String name, double lat, double lng, int bedsAvailable, int totalBeds,
                                String specialities, String contact, boolean active) {}

    public record AmbulanceBrief(Long id, String vehicleNo, double lat, double lng, String status, Long driverId,
                                 String driverName, Long activeRequestId) {}

    public record RankedHospital(long hospitalId, String name, double travelMinutes, boolean specialityMatch,
                                 int bedsAvailable, int totalBeds, double score) {}

    public record StopView(long signalId, String name, double lat, double lng, double distanceAhead, Double etaSec,
                           Double etaNoClearanceSec, Double predictedWaitSec, Long clearanceId, String clearanceStatus,
                           boolean signalGreen, boolean signalForced, double delaySavedSec, double waitedSec,
                           boolean passed) {}

    public record Timeline(Double created, Double accepted, Double dispatched, Double arrivedPatient, Double pickedUp,
                           Double arrivedHospital, Double completed) {}

    public record RequestView(
            Long id, String status, String severity, String requiredSpeciality, String description,
            Long patientId, String patientName, String patientPhone, double lat, double lng, String address,
            HospitalBrief hospital, AmbulanceBrief ambulance, String gpsMode,
            String leg, double progressMeters, double totalMeters,
            Double etaPatientSec, Double etaHospitalSec, Double etaHospitalNoClearanceSec,
            List<StopView> stops, List<double[]> polyline, List<double[]> hospitalLeg,
            String waitingAt,
            String conditionNote, Integer heartRate, Integer spo2,
            List<RankedHospital> ranking,
            double delaySavedSec, double waitedSec, long alertsSent,
            Timeline timeline, Instant createdAt, double simSpeed) {}

    public record ClearanceView(Long id, Long requestId, String leg, long signalId, String signalName, int stopOrder,
                                String status, Double etaSec, double delaySavedSec, double waitedSec,
                                String patientName, String vehicleNo, String severity) {}

    public record FleetItem(long id, String vehicleNo, double lat, double lng, String status, Long requestId) {}

    public record RoadView(double[] from, double[] to, double congestion) {}

    public record SignalView(Long id, String name, double lat, double lng, String zone, int cycleSeconds,
                             int greenSeconds, int offsetSeconds) {}

    public record NetworkView(List<SignalView> signals, List<RoadView> roads, List<HospitalBrief> hospitals,
                              List<AmbulanceBrief> ambulances, double simSpeed) {}

    public record AlertView(Long id, Long requestId, double distanceMeters, String direction, String message,
                            Instant createdAt) {}

    public record Analytics(
            long totalRequests, long completed, long active, long cancelled,
            Double avgResponseSec, Double avgTripSec, Double avgHospitalHandoverSec,
            double totalDelaySavedSec, double avgDelaySavedPerTrip, double totalWaitedSec,
            long clearanceRequests, long clearancesApproved, long clearancesRejected, long greenSent, long alertsSent,
            List<TripPoint> trips, List<SignalStat> signals, List<HospitalStat> hospitals) {}

    public record TripPoint(Long requestId, String severity, double actualSec, double withoutClearanceSec, double savedSec) {}

    public record SignalStat(long signalId, String name, long cleared, double savedSec, double waitedSec) {}

    public record HospitalStat(long hospitalId, String name, long accepted) {}
}
