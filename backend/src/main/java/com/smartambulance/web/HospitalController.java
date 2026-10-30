package com.smartambulance.web;

import com.smartambulance.domain.EmergencyRequest;
import com.smartambulance.domain.Hospital;
import com.smartambulance.dto.Commands.Beds;
import com.smartambulance.dto.Views.HospitalBrief;
import com.smartambulance.dto.Views.RequestView;
import com.smartambulance.repo.Repos.HospitalRepo;
import com.smartambulance.repo.Repos.RequestRepo;
import com.smartambulance.security.AuthUser;
import com.smartambulance.service.ApiException;
import com.smartambulance.sim.RequestViewFactory;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hospital")
@PreAuthorize("hasRole('HOSPITAL')")
public class HospitalController {
    private final HospitalRepo hospitals;
    private final RequestRepo requests;
    private final RequestViewFactory views;

    public HospitalController(HospitalRepo hospitals, RequestRepo requests, RequestViewFactory views) {
        this.hospitals = hospitals;
        this.requests = requests;
        this.views = views;
    }

    private Hospital mine(AuthUser me) {
        if (me.hospitalId() == null) throw ApiException.badRequest("Your account is not linked to a hospital");
        return hospitals.findById(me.hospitalId()).orElseThrow(() -> ApiException.notFound("Hospital not found"));
    }

    @GetMapping("/me")
    public HospitalBrief me(@AuthenticationPrincipal AuthUser me) {
        return RequestViewFactory.brief(mine(me));
    }

    /** Live bed count and speciality list: used by the ranking algorithm. */
    @PutMapping("/beds")
    public HospitalBrief beds(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody Beds body) {
        Hospital h = mine(me);
        if (body.bedsAvailable() > h.getTotalBeds()) throw ApiException.badRequest("Cannot exceed total beds (" + h.getTotalBeds() + ")");
        h.setBedsAvailable(body.bedsAvailable());
        if (body.specialities() != null && !body.specialities().isBlank()) {
            h.setSpecialities(body.specialities().toUpperCase().replaceAll("\\s+", ""));
        }
        return RequestViewFactory.brief(hospitals.save(h));
    }

    @GetMapping("/requests")
    public List<RequestView> requests(@AuthenticationPrincipal AuthUser me) {
        Hospital h = mine(me);
        return requests.findByHospitalIdOrderByIdDesc(h.getId()).stream().limit(40).map(views::build).toList();
    }
}
