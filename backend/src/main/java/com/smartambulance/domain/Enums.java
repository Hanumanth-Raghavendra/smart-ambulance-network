package com.smartambulance.domain;

public final class Enums {
    private Enums() {}

    public enum Role { PATIENT, HOSPITAL, AMBULANCE, POLICE, DRIVER, ADMIN }

    public enum Severity { MINOR, SERIOUS, CRITICAL }

    public enum AmbulanceStatus { AVAILABLE, ASSIGNED, OFFLINE }

    public enum GpsMode { SIMULATED, GPS }

    public enum Leg { TO_PATIENT, TO_HOSPITAL }

    public enum RequestStatus {
        PENDING_HOSPITAL, WAITING_AMBULANCE, DISPATCHED, EN_ROUTE_TO_PATIENT, AT_PATIENT,
        EN_ROUTE_TO_HOSPITAL, ARRIVED_AT_HOSPITAL, COMPLETED, CANCELLED, NO_HOSPITAL;

        public boolean isActive() {
            return this != COMPLETED && this != CANCELLED && this != NO_HOSPITAL;
        }

        public boolean isMoving() {
            return this == EN_ROUTE_TO_PATIENT || this == EN_ROUTE_TO_HOSPITAL;
        }
    }

    public enum ClearanceStatus { PENDING, APPROVED, GREEN_SENT, PASSED, REJECTED, CANCELLED, EXPIRED }
}
