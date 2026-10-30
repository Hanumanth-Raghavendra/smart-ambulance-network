package com.smartambulance.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Arrays;
import java.util.Locale;

@Entity
@Table(name = "hospitals")
@Getter @Setter @NoArgsConstructor
public class Hospital {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private String name;
    private double lat;
    private double lng;
    private int totalBeds;
    private int bedsAvailable;
    /** Comma separated, e.g. "CARDIAC,TRAUMA,GENERAL". */
    private String specialities = "GENERAL";
    private String contact;
    private boolean active = true;

    public boolean hasSpeciality(String s) {
        if (s == null || s.isBlank() || s.equalsIgnoreCase("GENERAL")) return true;
        return Arrays.stream(specialities.split(","))
                .anyMatch(x -> x.trim().toUpperCase(Locale.ROOT).equals(s.trim().toUpperCase(Locale.ROOT)));
    }
}
