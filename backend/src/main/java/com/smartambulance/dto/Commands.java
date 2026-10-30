package com.smartambulance.dto;

import com.smartambulance.domain.Enums.GpsMode;
import com.smartambulance.domain.Enums.Role;
import com.smartambulance.domain.Enums.Severity;
import jakarta.validation.constraints.*;

/** Request bodies. */
public final class Commands {
    private Commands() {}

    public record Register(@NotBlank @Size(max = 100) String name, @NotBlank @Email String email,
                           @NotBlank @Size(min = 6, max = 100) String password, @Size(max = 20) String phone,
                           Role role) {}

    public record Login(@NotBlank String email, @NotBlank String password) {}

    public record CreateRequest(@NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
                                @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
                                @Size(max = 200) String address, @NotNull Severity severity,
                                @Size(max = 30) String requiredSpeciality, @Size(max = 1000) String description,
                                @Size(max = 20) String phone) {}

    public record Reason(@Size(max = 300) String reason) {}

    public record StartTrip(GpsMode mode) {}

    public record Condition(@Size(max = 1000) String note, @Min(0) @Max(300) Integer heartRate,
                            @Min(0) @Max(100) Integer spo2) {}

    public record GpsPing(@NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
                          @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng, Double speedKmph) {}

    public record Beds(@NotNull @Min(0) Integer bedsAvailable, @Size(max = 200) String specialities) {}

    public record Location(@NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
                           @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {}

    public record OptIn(boolean enabled) {}

    public record CreateUser(@NotBlank String name, @NotBlank @Email String email, @NotBlank @Size(min = 6) String password,
                             String phone, @NotNull Role role, Long hospitalId) {}

    public record HospitalForm(@NotBlank String name, @NotNull Double lat, @NotNull Double lng,
                               @NotNull @Min(0) Integer totalBeds, @NotNull @Min(0) Integer bedsAvailable,
                               String specialities, String contact, Boolean active) {}

    public record AmbulanceForm(@NotBlank String vehicleNo, Long driverId, @NotNull Double lat, @NotNull Double lng) {}

    public record SignalForm(@NotBlank String name, @NotNull Double lat, @NotNull Double lng, String zone,
                             @NotNull @Min(20) Integer cycleSeconds, @NotNull @Min(5) Integer greenSeconds,
                             @NotNull @Min(0) Integer offsetSeconds) {}

    public record RoadForm(@NotNull Long fromId, @NotNull Long toId, @NotNull @DecimalMin("1.0") Double congestion) {}

    public record Settings(boolean autoApprove) {}
}
