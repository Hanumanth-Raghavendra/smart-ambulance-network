package com.smartambulance.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "driver_alerts", indexes = @Index(columnList = "userId"))
@Getter @Setter @NoArgsConstructor
public class DriverAlert {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long requestId;
    private Long userId;
    private double distanceMeters;
    private String direction;
    @Column(length = 500) private String message;
    private Instant createdAt = Instant.now();
}
