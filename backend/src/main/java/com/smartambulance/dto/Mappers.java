package com.smartambulance.dto;

import com.smartambulance.domain.AppUser;
import com.smartambulance.dto.Views.UserView;

public final class Mappers {
    private Mappers() {}

    public static UserView user(AppUser u) {
        return new UserView(u.getId(), u.getName(), u.getEmail(), u.getPhone(), u.getRole().name(), u.isActive(),
                u.getHospitalId(), u.isAlertsOptIn(), u.getLastLat(), u.getLastLng());
    }
}
