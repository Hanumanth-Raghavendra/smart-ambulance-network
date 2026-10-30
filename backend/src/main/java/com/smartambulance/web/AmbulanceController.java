package com.smartambulance.web;

import com.smartambulance.domain.Ambulance;
import com.smartambulance.domain.EmergencyRequest;
import com.smartambulance.dto.Commands.GpsPing;
import com.smartambulance.dto.Views.AmbulanceBrief;
import com.smartambulance.dto.Views.RequestView;
import com.smartambulance.repo.Repos.AmbulanceRepo;
import com.smartambulance.repo.Repos.RequestRepo;
import com.smartambulance.security.AuthUser;
import com.smartambulance.service.ApiException;
import com.smartambulance.sim.RequestViewFactory;
import com.smartambulance.sim.TripEngine;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ambulance")
@PreAuthorize("hasRole('AMBULANCE')")
public class AmbulanceController {
    public record Me(AmbulanceBrief ambulance, RequestView request) {}

    private final AmbulanceRepo ambulances;
    private final RequestRepo requests;
    private final RequestViewFactory views;
    private final TripEngine engine;

    public AmbulanceController(AmbulanceRepo ambulances, RequestRepo requests, RequestViewFactory views, TripEngine engine) {
        this.ambulances = ambulances;
        this.requests = requests;
        this.views = views;
        this.engine = engine;
    }

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal AuthUser me) {
        Ambulance a = ambulances.findByDriverId(me.id()).orElseThrow(() -> ApiException.notFound("No ambulance is assigned to your account"));
        RequestView v = null;
        if (a.getActiveRequestId() != null) {
            v = requests.findById(a.getActiveRequestId()).filter(r -> r.getStatus().isActive()).map(views::build).orElse(null);
        }
        return new Me(views.brief(a), v);
    }

    /** Real GPS feed (trip started in GPS mode). Requires san.sim.speed-multiplier=1 for meaningful ETAs. */
    @PostMapping("/location")
    public void location(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody GpsPing body) {
        Ambulance a = ambulances.findByDriverId(me.id()).orElseThrow(() -> ApiException.notFound("No ambulance is assigned to your account"));
        engine.reportLocation(me, a.getId(), body);
    }
}
