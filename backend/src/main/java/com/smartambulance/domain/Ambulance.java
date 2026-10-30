package com.smartambulance.domain;

import com.smartambulance.domain.Enums.AmbulanceStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "ambulances")
@Getter @Setter @NoArgsConstructor
public class Ambulance {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true) private String vehicleNo;
    private Long driverId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AmbulanceStatus status = AmbulanceStatus.AVAILABLE;
    private double lat;
    private double lng;
    private Long activeRequestId;
    private Instant updatedAt = Instant.now();
}
