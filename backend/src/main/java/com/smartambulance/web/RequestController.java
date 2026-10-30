package com.smartambulance.web;

import com.smartambulance.domain.EmergencyRequest;
import com.smartambulance.dto.Commands.*;
import com.smartambulance.dto.Views.RequestView;
import com.smartambulance.repo.Repos.RequestRepo;
import com.smartambulance.security.AuthUser;
import com.smartambulance.service.ApiException;
import com.smartambulance.service.RequestService;
import com.smartambulance.sim.RequestViewFactory;
import com.smartambulance.ws.RequestAccess;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/requests")
public class RequestController {
    private final RequestService service;
    private final RequestViewFactory views;
    private final RequestRepo repo;
    private final RequestAccess access;

    public RequestController(RequestService service, RequestViewFactory views, RequestRepo repo, RequestAccess access) {
        this.service = service;
        this.views = views;
        this.repo = repo;
        this.access = access;
    }

    @PostMapping
    @PreAuthorize("hasRole('PATIENT')")
    public RequestView create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody CreateRequest body) {
        return views.build(service.create(me, body));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('PATIENT')")
    public List<RequestView> mine(@AuthenticationPrincipal AuthUser me) {
        return repo.findByPatientIdOrderByIdDesc(me.id()).stream().map(views::build).toList();
    }

    @GetMapping("/{id}")
    public RequestView one(@AuthenticationPrincipal AuthUser me, @PathVariable long id) {
        EmergencyRequest r = service.get(id);
        if (!access.canView(me, r)) throw ApiException.forbidden("You cannot view this request");
        return views.build(r);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('PATIENT','ADMIN')")
    public RequestView cancel(@AuthenticationPrincipal AuthUser me, @PathVariable long id) {
        return views.build(service.cancel(me, id));
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize("hasAnyRole('HOSPITAL','ADMIN')")
    public RequestView accept(@AuthenticationPrincipal AuthUser me, @PathVariable long id) {
        return views.build(service.hospitalAccept(me, id));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('HOSPITAL','ADMIN')")
    public RequestView reject(@AuthenticationPrincipal AuthUser me, @PathVariable long id,
                              @RequestBody(required = false) Reason body) {
        return views.build(service.hospitalReject(me, id, body == null ? null : body.reason()));
    }

    @PostMapping("/{id}/handover")
    @PreAuthorize("hasAnyRole('HOSPITAL','ADMIN')")
    public RequestView handover(@AuthenticationPrincipal AuthUser me, @PathVariable long id) {
        return views.build(service.handover(me, id));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAnyRole('AMBULANCE','ADMIN')")
    public RequestView start(@AuthenticationPrincipal AuthUser me, @PathVariable long id,
                             @RequestBody(required = false) StartTrip body) {
        return views.build(service.driverAccept(me, id, body == null ? null : body.mode()));
    }

    @PostMapping("/{id}/pickup")
    @PreAuthorize("hasAnyRole('AMBULANCE','ADMIN')")
    public RequestView pickup(@AuthenticationPrincipal AuthUser me, @PathVariable long id) {
        return views.build(service.pickup(me, id));
    }

    @PostMapping("/{id}/arrived")
    @PreAuthorize("hasAnyRole('AMBULANCE','ADMIN')")
    public RequestView arrived(@AuthenticationPrincipal AuthUser me, @PathVariable long id) {
        return views.build(service.markArrived(me, id));
    }

    @PutMapping("/{id}/condition")
    @PreAuthorize("hasAnyRole('AMBULANCE','ADMIN')")
    public RequestView condition(@AuthenticationPrincipal AuthUser me, @PathVariable long id, @Valid @RequestBody Condition body) {
        return views.build(service.updateCondition(me, id, body));
    }
}
