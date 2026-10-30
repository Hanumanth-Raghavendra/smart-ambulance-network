package com.smartambulance.domain;

import com.smartambulance.domain.Enums.ClearanceStatus;
import com.smartambulance.domain.Enums.Leg;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One green-corridor request for one signal on one leg of one trip. Doubles as the audit trail. */
@Entity
@Table(name = "clearance_logs", indexes = @Index(columnList = "requestId"))
@Getter @Setter @NoArgsConstructor
public class ClearanceLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long requestId;
    @Enumerated(EnumType.STRING) private Leg leg;
    private Long signalId;
    private String signalName;
    private int stopOrder;
    private double etaAtRequestSec;
    @Enumerated(EnumType.STRING) private ClearanceStatus status = ClearanceStatus.PENDING;
    private Long decidedBy;
    /** true once police approved this request (kept even after the ambulance has passed). */
    private boolean approved;
    private Double requestedAtSim, decidedAtSim, greenSentAtSim, passedAtSim;
    private double delaySavedSec;
    private double waitedSec;
    private Instant createdAt = Instant.now();
}
