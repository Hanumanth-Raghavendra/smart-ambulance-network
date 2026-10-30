package com.smartambulance.web;

import com.smartambulance.domain.*;
import com.smartambulance.domain.Enums.Role;
import com.smartambulance.dto.Commands.*;
import com.smartambulance.dto.Mappers;
import com.smartambulance.dto.Views.*;
import com.smartambulance.geo.Geo;
import com.smartambulance.repo.Repos.*;
import com.smartambulance.security.AuthUser;
import com.smartambulance.service.AnalyticsService;
import com.smartambulance.service.ApiException;
import com.smartambulance.service.SettingsService;
import com.smartambulance.sim.RequestViewFactory;
import com.smartambulance.sim.RoadNetwork;
import com.smartambulance.sim.SignalService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    public record AdminRoad(Long id, Long fromId, Long toId, String fromName, String toName, double lengthMeters, double congestion) {}

    private final UserRepo users;
    private final HospitalRepo hospitals;
    private final AmbulanceRepo ambulances;
    private final SignalRepo signalRepo;
    private final RoadRepo roadRepo;
    private final RequestRepo requests;
    private final PasswordEncoder encoder;
    private final SettingsService settings;
    private final AnalyticsService analytics;
    private final RequestViewFactory views;
    private final RoadNetwork network;
    private final SignalService signalService;

    public AdminController(UserRepo users, HospitalRepo hospitals, AmbulanceRepo ambulances, SignalRepo signalRepo,
                           RoadRepo roadRepo, RequestRepo requests, PasswordEncoder encoder, SettingsService settings,
                           AnalyticsService analytics, RequestViewFactory views, RoadNetwork network, SignalService signalService) {
        this.users = users;
        this.hospitals = hospitals;
        this.ambulances = ambulances;
        this.signalRepo = signalRepo;
        this.roadRepo = roadRepo;
        this.requests = requests;
        this.encoder = encoder;
        this.settings = settings;
        this.analytics = analytics;
        this.views = views;
        this.network = network;
        this.signalService = signalService;
    }

    // ---- analytics & settings
    @GetMapping("/analytics")
    public Analytics analytics() {
        return analytics.compute();
    }

    @GetMapping("/settings")
    public Settings settings() {
        return new Settings(settings.autoApprove());
    }

    @PutMapping("/settings")
    public Settings updateSettings(@RequestBody Settings body) {
        settings.setAutoApprove(body.autoApprove());
        return new Settings(settings.autoApprove());
    }

    @GetMapping("/requests")
    public List<RequestView> allRequests() {
        return requests.findAllByOrderByIdDesc().stream().limit(100).map(views::build).toList();
    }

    // ---- users
    @GetMapping("/users")
    public List<UserView> users() {
        return users.findAll().stream().sorted((a, b) -> Long.compare(a.getId(), b.getId())).map(Mappers::user).toList();
    }

    @PostMapping("/users")
    public UserView createUser(@Valid @RequestBody CreateUser b) {
        if (users.existsByEmailIgnoreCase(b.email())) throw ApiException.conflict("That email is already registered");
        if (b.role() == Role.HOSPITAL && (b.hospitalId() == null || !hospitals.existsById(b.hospitalId()))) {
            throw ApiException.badRequest("Hospital staff need a valid hospital");
        }
        AppUser u = new AppUser();
        u.setName(b.name().trim());
        u.setEmail(b.email().trim().toLowerCase());
        u.setPasswordHash(encoder.encode(b.password()));
        u.setPhone(b.phone());
        u.setRole(b.role());
        u.setHospitalId(b.role() == Role.HOSPITAL ? b.hospitalId() : null);
        return Mappers.user(users.save(u));
    }

    @PutMapping("/users/{id}/active")
    public UserView setActive(@AuthenticationPrincipal AuthUser me, @PathVariable long id, @RequestBody OptIn body) {
        if (id == me.id() && !body.enabled()) throw ApiException.badRequest("You cannot disable your own account");
        AppUser u = users.findById(id).orElseThrow(() -> ApiException.notFound("User not found"));
        u.setActive(body.enabled());
        return Mappers.user(users.save(u));
    }

    // ---- hospitals
    @GetMapping("/hospitals")
    public List<HospitalBrief> hospitals() {
        return hospitals.findAll().stream().sorted((a, b) -> Long.compare(a.getId(), b.getId())).map(RequestViewFactory::brief).toList();
    }

    @PostMapping("/hospitals")
    public HospitalBrief createHospital(@Valid @RequestBody HospitalForm f) {
        return RequestViewFactory.brief(hospitals.save(apply(new Hospital(), f)));
    }

    @PutMapping("/hospitals/{id}")
    public HospitalBrief updateHospital(@PathVariable long id, @Valid @RequestBody HospitalForm f) {
        Hospital h = hospitals.findById(id).orElseThrow(() -> ApiException.notFound("Hospital not found"));
        return RequestViewFactory.brief(hospitals.save(apply(h, f)));
    }

    private Hospital apply(Hospital h, HospitalForm f) {
        if (f.bedsAvailable() > f.totalBeds()) throw ApiException.badRequest("Available beds cannot exceed total beds");
        h.setName(f.name().trim());
        h.setLat(f.lat());
        h.setLng(f.lng());
        h.setTotalBeds(f.totalBeds());
        h.setBedsAvailable(f.bedsAvailable());
        h.setSpecialities(f.specialities() == null || f.specialities().isBlank() ? "GENERAL"
                : f.specialities().toUpperCase().replaceAll("\\s+", ""));
        h.setContact(f.contact());
        h.setActive(f.active() == null || f.active());
        return h;
    }

    // ---- ambulances
    @GetMapping("/ambulances")
    public List<AmbulanceBrief> ambulances() {
        return ambulances.findAll().stream().sorted((a, b) -> Long.compare(a.getId(), b.getId())).map(views::brief).toList();
    }

    @PostMapping("/ambulances")
    public AmbulanceBrief createAmbulance(@Valid @RequestBody AmbulanceForm f) {
        Ambulance a = new Ambulance();
        a.setVehicleNo(f.vehicleNo().trim().toUpperCase());
        a.setDriverId(checkDriver(f.driverId(), null));
        a.setLat(f.lat());
        a.setLng(f.lng());
        return views.brief(ambulances.save(a));
    }

    @PutMapping("/ambulances/{id}")
    public AmbulanceBrief updateAmbulance(@PathVariable long id, @Valid @RequestBody AmbulanceForm f) {
        Ambulance a = ambulances.findById(id).orElseThrow(() -> ApiException.notFound("Ambulance not found"));
        if (a.getActiveRequestId() != null) throw ApiException.conflict("This ambulance is on a trip");
        a.setVehicleNo(f.vehicleNo().trim().toUpperCase());
        a.setDriverId(checkDriver(f.driverId(), id));
        a.setLat(f.lat());
        a.setLng(f.lng());
        a.setUpdatedAt(Instant.now());
        return views.brief(ambulances.save(a));
    }

    private Long checkDriver(Long driverId, Long ambulanceId) {
        if (driverId == null) return null;
        AppUser u = users.findById(driverId).orElseThrow(() -> ApiException.badRequest("Driver account not found"));
        if (u.getRole() != Role.AMBULANCE) throw ApiException.badRequest("That account does not have the AMBULANCE role");
        ambulances.findByDriverId(driverId).ifPresent(other -> {
            if (ambulanceId == null || !other.getId().equals(ambulanceId)) throw ApiException.conflict("That driver already has an ambulance");
        });
        return driverId;
    }

    // ---- signals and roads
    @GetMapping("/signals")
    public List<SignalView> signals() {
        return signalRepo.findAll().stream().sorted((a, b) -> Long.compare(a.getId(), b.getId())).map(AdminController::sv).toList();
    }

    @PostMapping("/signals")
    public SignalView createSignal(@Valid @RequestBody SignalForm f) {
        SignalView v = sv(signalRepo.save(apply(new TrafficSignal(), f)));
        reloadNetwork();
        return v;
    }

    @PutMapping("/signals/{id}")
    public SignalView updateSignal(@PathVariable long id, @Valid @RequestBody SignalForm f) {
        TrafficSignal s = signalRepo.findById(id).orElseThrow(() -> ApiException.notFound("Signal not found"));
        SignalView v = sv(signalRepo.save(apply(s, f)));
        reloadNetwork();
        return v;
    }

    private static TrafficSignal apply(TrafficSignal s, SignalForm f) {
        if (f.greenSeconds() >= f.cycleSeconds()) throw ApiException.badRequest("Green time must be shorter than the cycle");
        s.setName(f.name().trim());
        s.setLat(f.lat());
        s.setLng(f.lng());
        s.setZone(f.zone());
        s.setCycleSeconds(f.cycleSeconds());
        s.setGreenSeconds(f.greenSeconds());
        s.setOffsetSeconds(f.offsetSeconds());
        return s;
    }

    private static SignalView sv(TrafficSignal s) {
        return new SignalView(s.getId(), s.getName(), s.getLat(), s.getLng(), s.getZone(), s.getCycleSeconds(),
                s.getGreenSeconds(), s.getOffsetSeconds());
    }

    @GetMapping("/roads")
    public List<AdminRoad> roads() {
        Map<Long, TrafficSignal> byId = signalRepo.findAll().stream().collect(Collectors.toMap(TrafficSignal::getId, Function.identity()));
        return roadRepo.findAll().stream().map(r -> new AdminRoad(r.getId(), r.getFromId(), r.getToId(),
                byId.containsKey(r.getFromId()) ? byId.get(r.getFromId()).getName() : "?",
                byId.containsKey(r.getToId()) ? byId.get(r.getToId()).getName() : "?",
                r.getLengthMeters(), r.getCongestion())).toList();
    }

    @PostMapping("/roads")
    public AdminRoad createRoad(@Valid @RequestBody RoadForm f) {
        TrafficSignal a = signalRepo.findById(f.fromId()).orElseThrow(() -> ApiException.badRequest("Unknown junction"));
        TrafficSignal b = signalRepo.findById(f.toId()).orElseThrow(() -> ApiException.badRequest("Unknown junction"));
        if (a.getId().equals(b.getId())) throw ApiException.badRequest("A road needs two different junctions");
        RoadSegment r = new RoadSegment();
        r.setFromId(a.getId());
        r.setToId(b.getId());
        r.setLengthMeters(Geo.haversine(a.getLat(), a.getLng(), b.getLat(), b.getLng()) * 1.25);
        r.setCongestion(f.congestion());
        r = roadRepo.save(r);
        reloadNetwork();
        return new AdminRoad(r.getId(), a.getId(), b.getId(), a.getName(), b.getName(), r.getLengthMeters(), r.getCongestion());
    }

    @PutMapping("/roads/{id}")
    public AdminRoad updateRoad(@PathVariable long id, @Valid @RequestBody RoadForm f) {
        RoadSegment r = roadRepo.findById(id).orElseThrow(() -> ApiException.notFound("Road not found"));
        r.setCongestion(f.congestion());
        roadRepo.save(r);
        reloadNetwork();
        return roads().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    private void reloadNetwork() {
        signalService.refresh();
        network.reload();
    }
}
