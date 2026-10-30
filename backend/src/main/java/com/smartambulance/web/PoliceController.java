package com.smartambulance.web;

import com.smartambulance.domain.Enums.RequestStatus;
import com.smartambulance.dto.Views.ClearanceView;
import com.smartambulance.dto.Views.RequestView;
import com.smartambulance.repo.Repos.RequestRepo;
import com.smartambulance.security.AuthUser;
import com.smartambulance.service.ClearanceService;
import com.smartambulance.sim.RequestViewFactory;
import com.smartambulance.sim.SignalService;
import com.smartambulance.sim.SignalService.SignalState;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/police")
@PreAuthorize("hasAnyRole('POLICE','ADMIN')")
public class PoliceController {
    private final RequestRepo requests;
    private final RequestViewFactory views;
    private final ClearanceService clearance;
    private final SignalService signals;

    public PoliceController(RequestRepo requests, RequestViewFactory views, ClearanceService clearance, SignalService signals) {
        this.requests = requests;
        this.views = views;
        this.clearance = clearance;
        this.signals = signals;
    }

    /** Trips that have an ambulance assigned and are not finished. */
    @GetMapping("/requests")
    public List<RequestView> active() {
        return requests.findByStatusIn(List.of(RequestStatus.DISPATCHED, RequestStatus.EN_ROUTE_TO_PATIENT,
                RequestStatus.AT_PATIENT, RequestStatus.EN_ROUTE_TO_HOSPITAL)).stream().map(views::build).toList();
    }

    @GetMapping("/clearances")
    public List<ClearanceView> clearances() {
        return clearance.openForPolice();
    }

    @GetMapping("/signals")
    public List<SignalState> signals() {
        return signals.snapshot();
    }

    @PostMapping("/clearances/{id}/approve")
    public ClearanceView approve(@AuthenticationPrincipal AuthUser me, @PathVariable long id) {
        return clearance.approve(id, me);
    }

    @PostMapping("/clearances/{id}/reject")
    public ClearanceView reject(@AuthenticationPrincipal AuthUser me, @PathVariable long id) {
        return clearance.reject(id, me);
    }

    @PostMapping("/requests/{id}/clearances/approve-all")
    public List<ClearanceView> approveAll(@AuthenticationPrincipal AuthUser me, @PathVariable long id) {
        return clearance.decideAll(id, me, true);
    }

    @PostMapping("/requests/{id}/clearances/reject-all")
    public List<ClearanceView> rejectAll(@AuthenticationPrincipal AuthUser me, @PathVariable long id) {
        return clearance.decideAll(id, me, false);
    }
}
