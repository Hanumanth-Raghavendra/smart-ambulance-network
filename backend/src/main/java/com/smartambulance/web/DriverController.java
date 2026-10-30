package com.smartambulance.web;

import com.smartambulance.domain.AppUser;
import com.smartambulance.dto.Commands.Location;
import com.smartambulance.dto.Commands.OptIn;
import com.smartambulance.dto.Mappers;
import com.smartambulance.dto.Views.AlertView;
import com.smartambulance.dto.Views.UserView;
import com.smartambulance.repo.Repos.AlertRepo;
import com.smartambulance.repo.Repos.UserRepo;
import com.smartambulance.security.AuthUser;
import com.smartambulance.service.ApiException;
import com.smartambulance.sim.DriverAlertService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/** Road users (the "Driver Alert App"). */
@RestController
@RequestMapping("/api/driver")
@PreAuthorize("hasRole('DRIVER')")
public class DriverController {
    private final UserRepo users;
    private final AlertRepo alerts;

    public DriverController(UserRepo users, AlertRepo alerts) {
        this.users = users;
        this.alerts = alerts;
    }

    private AppUser load(AuthUser me) {
        return users.findById(me.id()).orElseThrow(() -> ApiException.notFound("User not found"));
    }

    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal AuthUser me) {
        return Mappers.user(load(me));
    }

    @PutMapping("/location")
    public UserView location(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody Location body) {
        AppUser u = load(me);
        u.setLastLat(body.lat());
        u.setLastLng(body.lng());
        u.setLastSeen(Instant.now());
        return Mappers.user(users.save(u));
    }

    @PutMapping("/optin")
    public UserView optIn(@AuthenticationPrincipal AuthUser me, @RequestBody OptIn body) {
        AppUser u = load(me);
        u.setAlertsOptIn(body.enabled());
        return Mappers.user(users.save(u));
    }

    @GetMapping("/alerts")
    public List<AlertView> alerts(@AuthenticationPrincipal AuthUser me) {
        return alerts.findTop30ByUserIdOrderByIdDesc(me.id()).stream().map(DriverAlertService::toView).toList();
    }
}
