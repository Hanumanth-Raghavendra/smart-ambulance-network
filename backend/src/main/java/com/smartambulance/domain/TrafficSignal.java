package com.smartambulance.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A signalised junction. Junctions are also the nodes of the road graph. */
@Entity
@Table(name = "traffic_signals")
@Getter @Setter @NoArgsConstructor
public class TrafficSignal {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private String name;
    private double lat;
    private double lng;
    private String zone;
    private int cycleSeconds = 80;
    private int greenSeconds = 40;
    private int offsetSeconds = 0;
}
