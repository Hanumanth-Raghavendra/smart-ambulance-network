package com.smartambulance.ws;

import com.smartambulance.domain.Ambulance;
import com.smartambulance.domain.EmergencyRequest;
import com.smartambulance.domain.Enums.Role;
import com.smartambulance.repo.Repos.AmbulanceRepo;
import com.smartambulance.security.AuthUser;
import org.springframework.stereotype.Component;

/** Who may see which request. */
@Component
public class RequestAccess {
    private final AmbulanceRepo ambulances;

    public RequestAccess(AmbulanceRepo ambulances) {
        this.ambulances = ambulances;
    }

    public boolean canView(AuthUser me, EmergencyRequest r) {
        if (me.is(Role.ADMIN, Role.POLICE)) return true;
        if (me.is(Role.PATIENT)) return r.getPatientId() != null && r.getPatientId() == me.id();
        if (me.is(Role.HOSPITAL)) return me.hospitalId() != null && me.hospitalId().equals(r.getHospitalId());
        if (me.is(Role.AMBULANCE) && r.getAmbulanceId() != null) {
            return ambulances.findById(r.getAmbulanceId()).map(Ambulance::getDriverId).map(d -> d == me.id()).orElse(false);
        }
        return false;
    }
}
