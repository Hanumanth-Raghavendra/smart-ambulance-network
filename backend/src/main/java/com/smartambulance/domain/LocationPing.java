package com.smartambulance.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "location_pings", indexes = @Index(columnList = "requestId"))
@Getter @Setter @NoArgsConstructor
public class LocationPing {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long requestId;
    private Long ambulanceId;
    private double lat;
    private double lng;
    private double speedKmph;
    private double simTime;
    private Instant createdAt = Instant.now();
}
