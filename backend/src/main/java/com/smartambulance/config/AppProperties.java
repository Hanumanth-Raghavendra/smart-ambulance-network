package com.smartambulance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** All tunables of the platform, bound from the "san." prefix in application.properties. */
@ConfigurationProperties(prefix = "san")
public record AppProperties(Jwt jwt, Cors cors, Sim sim, Clearance clearance, Alert alert, Ranking ranking,
                            double serviceAreaMaxMeters) {

    public record Jwt(String secret, long expiryMinutes) {}

    public record Cors(List<String> allowedOrigins) {}

    public record Sim(double speedMultiplier, long tickMillis, double ambulanceKmph, double loadingRealSeconds,
                      long hospitalResponseTimeoutSeconds) {}

    public record Clearance(double leadTimeSeconds, double maxHoldSeconds) {}

    public record Alert(double aheadMeters, double lateralMeters, double cooldownSeconds) {}

    public record Ranking(double wTravel, double wSpeciality, double wBeds, SeverityMultiplier severityMultiplier) {}

    public record SeverityMultiplier(double minor, double serious, double critical) {}
}
