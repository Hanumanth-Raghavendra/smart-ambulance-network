package com.smartambulance.web;

import com.smartambulance.domain.TrafficSignal;
import com.smartambulance.dto.Views.*;
import com.smartambulance.repo.Repos.*;
import com.smartambulance.sim.RequestViewFactory;
import com.smartambulance.sim.SimClock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Static map data (junctions, roads, hospitals) plus current ambulance positions, for the map views. */
@RestController
@RequestMapping("/api/network")
public class NetworkController {
    private final SignalRepo signals;
    private final RoadRepo roads;
    private final HospitalRepo hospitals;
    private final AmbulanceRepo ambulances;
    private final RequestViewFactory views;
    private final SimClock clock;

    public NetworkController(SignalRepo signals, RoadRepo roads, HospitalRepo hospitals, AmbulanceRepo ambulances,
                             RequestViewFactory views, SimClock clock) {
        this.signals = signals;
        this.roads = roads;
        this.hospitals = hospitals;
        this.ambulances = ambulances;
        this.views = views;
        this.clock = clock;
    }

    @GetMapping
    public NetworkView network() {
        Map<Long, TrafficSignal> byId = new HashMap<>();
        signals.findAll().forEach(s -> byId.put(s.getId(), s));
        List<SignalView> sv = byId.values().stream()
                .map(s -> new SignalView(s.getId(), s.getName(), s.getLat(), s.getLng(), s.getZone(), s.getCycleSeconds(),
                        s.getGreenSeconds(), s.getOffsetSeconds()))
                .sorted((a, b) -> Long.compare(a.id(), b.id())).toList();
        List<RoadView> rv = roads.findAll().stream()
                .filter(r -> byId.containsKey(r.getFromId()) && byId.containsKey(r.getToId()))
                .map(r -> new RoadView(new double[]{byId.get(r.getFromId()).getLat(), byId.get(r.getFromId()).getLng()},
                        new double[]{byId.get(r.getToId()).getLat(), byId.get(r.getToId()).getLng()}, r.getCongestion()))
                .toList();
        return new NetworkView(sv, rv, hospitals.findByActiveTrue().stream().map(RequestViewFactory::brief).toList(),
                ambulances.findAll().stream().map(views::brief).toList(), clock.speed());
    }
}
