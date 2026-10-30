package com.smartambulance.repo;

import com.smartambulance.domain.*;
import com.smartambulance.domain.Enums.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class Repos {
    private Repos() {}

    public interface UserRepo extends JpaRepository<AppUser, Long> {
        Optional<AppUser> findByEmailIgnoreCase(String email);
        boolean existsByEmailIgnoreCase(String email);
        List<AppUser> findByRoleAndActiveTrueAndAlertsOptInTrue(Role role);
        List<AppUser> findByHospitalId(Long hospitalId);
    }

    public interface HospitalRepo extends JpaRepository<Hospital, Long> {
        List<Hospital> findByActiveTrue();
    }

    public interface AmbulanceRepo extends JpaRepository<Ambulance, Long> {
        List<Ambulance> findByStatus(AmbulanceStatus status);
        Optional<Ambulance> findByDriverId(Long driverId);
    }

    public interface SignalRepo extends JpaRepository<TrafficSignal, Long> {}

    public interface RoadRepo extends JpaRepository<RoadSegment, Long> {}

    public interface RequestRepo extends JpaRepository<EmergencyRequest, Long> {
        List<EmergencyRequest> findByStatusIn(Collection<RequestStatus> statuses);
        List<EmergencyRequest> findByPatientIdOrderByIdDesc(Long patientId);
        List<EmergencyRequest> findByHospitalIdOrderByIdDesc(Long hospitalId);
        List<EmergencyRequest> findAllByOrderByIdDesc();
    }

    public interface ClearanceRepo extends JpaRepository<ClearanceLog, Long> {
        List<ClearanceLog> findByRequestIdOrderByStopOrderAsc(Long requestId);
        List<ClearanceLog> findByRequestIdAndLegOrderByStopOrderAsc(Long requestId, Leg leg);
        List<ClearanceLog> findByRequestIdIn(Collection<Long> requestIds);
    }

    public interface AlertRepo extends JpaRepository<DriverAlert, Long> {
        List<DriverAlert> findTop30ByUserIdOrderByIdDesc(Long userId);
        long countByRequestId(Long requestId);
    }

    public interface PingRepo extends JpaRepository<LocationPing, Long> {
        List<LocationPing> findByRequestIdOrderByIdAsc(Long requestId);
    }

    public interface SettingRepo extends JpaRepository<AppSetting, String> {}
}
