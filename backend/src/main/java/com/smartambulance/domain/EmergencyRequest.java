package com.smartambulance.domain;

import com.smartambulance.domain.Enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "emergency_requests")
@Getter @Setter @NoArgsConstructor
public class EmergencyRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long patientId;
    private String patientName;
    private String patientPhone;
    private double lat;
    private double lng;
    private String address;
    @Enumerated(EnumType.STRING) private Severity severity = Severity.SERIOUS;
    private String requiredSpeciality = "GENERAL";
    @Column(length = 1000) private String description;

    @Enumerated(EnumType.STRING) @Column(nullable = false) private RequestStatus status = RequestStatus.PENDING_HOSPITAL;

    private Long hospitalId;
    private String rejectedHospitalIds = "";
    private Instant hospitalAssignedAt;
    @Column(columnDefinition = "text") private String rankingJson;
    private boolean bedReserved;

    private Long ambulanceId;
    @Enumerated(EnumType.STRING) private GpsMode gpsMode = GpsMode.SIMULATED;
    @Enumerated(EnumType.STRING) private Leg leg = Leg.TO_PATIENT;
    /** JSON array of two RoutePlans: [toPatient, toHospital]. */
    @Column(columnDefinition = "text") private String routeJson;
    private double progressMeters;
    private Long waitingSignalId;
    private Double waitSinceSim;
    private Long loadingUntilMs;

    // patient condition, updated by the paramedic
    @Column(length = 1000) private String conditionNote;
    private Integer heartRate;
    private Integer spo2;

    // timeline in simulated seconds (see SimClock)
    private Double simCreated, simAccepted, simDispatched, simArrivedPatient, simPickedUp, simArrivedHospital, simCompleted;

    private double delaySavedSec;
    private double waitedSec;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
