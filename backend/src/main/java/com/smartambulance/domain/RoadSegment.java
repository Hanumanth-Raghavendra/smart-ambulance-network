package com.smartambulance.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Undirected road between two junctions. */
@Entity
@Table(name = "road_segments")
@Getter @Setter @NoArgsConstructor
public class RoadSegment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long fromId;
    private Long toId;
    private double lengthMeters;
    /** 1.0 = free flow; 1.5 = ambulance moves at two thirds of its normal speed. */
    private double congestion = 1.0;
}
