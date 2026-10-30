package com.smartambulance.domain;

import com.smartambulance.domain.Enums.Role;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "app_users")
@Getter @Setter @NoArgsConstructor
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private String name;
    @Column(nullable = false, unique = true) private String email;
    @Column(nullable = false) private String passwordHash;
    private String phone;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Role role;
    private boolean active = true;
    /** Set for HOSPITAL staff. */
    private Long hospitalId;
    /** Road users (DRIVER role): opted in to receive give-way alerts. */
    private boolean alertsOptIn = true;
    private Double lastLat;
    private Double lastLng;
    private Instant lastSeen;
    private Instant createdAt = Instant.now();
}
