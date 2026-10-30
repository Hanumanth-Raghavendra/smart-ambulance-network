package com.smartambulance.config;

import com.smartambulance.domain.*;
import com.smartambulance.domain.Enums.Role;
import com.smartambulance.geo.Geo;
import com.smartambulance.repo.Repos.*;
import com.smartambulance.service.SettingsService;
import com.smartambulance.sim.RoadNetwork;
import com.smartambulance.sim.SignalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Loads a demo city on first start: a simplified road network of 19 Hyderabad junctions, five fictional hospitals,
 * five ambulances and ready-to-use accounts for every role (password: Demo@123).
 * Junction coordinates are approximate and the roads are straight links, which is enough for the simulation.
 */
@Component
@Order(1)
public class DataSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    public static final String DEMO_PASSWORD = "Demo@123";

    private final UserRepo users;
    private final HospitalRepo hospitals;
    private final AmbulanceRepo ambulances;
    private final SignalRepo signals;
    private final RoadRepo roads;
    private final PasswordEncoder encoder;
    private final SettingsService settings;
    private final RoadNetwork network;
    private final SignalService signalService;

    public DataSeeder(UserRepo users, HospitalRepo hospitals, AmbulanceRepo ambulances, SignalRepo signals, RoadRepo roads,
                      PasswordEncoder encoder, SettingsService settings, RoadNetwork network, SignalService signalService) {
        this.users = users;
        this.hospitals = hospitals;
        this.ambulances = ambulances;
        this.signals = signals;
        this.roads = roads;
        this.encoder = encoder;
        this.settings = settings;
        this.network = network;
        this.signalService = signalService;
    }

    private static final Object[][] JUNCTIONS = {
            {"Secunderabad", 17.4344, 78.5013, "North-East"},
            {"Paradise Circle", 17.4413, 78.4866, "North"},
            {"Begumpet", 17.4440, 78.4670, "North"},
            {"Ameerpet", 17.4375, 78.4483, "West"},
            {"Panjagutta", 17.4259, 78.4517, "Central"},
            {"Khairatabad", 17.4122, 78.4620, "Central"},
            {"Lakdikapul", 17.4046, 78.4612, "Central"},
            {"Nampally", 17.3900, 78.4690, "Old City"},
            {"Abids", 17.3917, 78.4737, "Old City"},
            {"Koti", 17.3849, 78.4844, "Old City"},
            {"Charminar", 17.3616, 78.4747, "South"},
            {"Tarnaka", 17.4287, 78.5330, "East"},
            {"Mehdipatnam", 17.3950, 78.4340, "West"},
            {"Jubilee Hills Check Post", 17.4326, 78.4071, "West"},
            {"Tank Bund", 17.4239, 78.4738, "Central"},
            {"Musheerabad", 17.4083, 78.4947, "East"},
            {"Chikkadpally", 17.4040, 78.4930, "East"},
            {"Banjara Hills", 17.4150, 78.4480, "West"},
            {"Malakpet", 17.3745, 78.5063, "South"},
    };

    // 1-based indexes into JUNCTIONS
    private static final int[][] LINKS = {
            {1, 2}, {1, 16}, {1, 12}, {2, 3}, {2, 15}, {3, 4}, {4, 5}, {4, 18}, {5, 6}, {5, 18}, {18, 14}, {6, 7},
            {6, 15}, {6, 18}, {7, 8}, {8, 9}, {9, 10}, {8, 13}, {13, 18}, {9, 17}, {17, 16}, {16, 15}, {10, 11},
            {10, 19}, {11, 19}, {9, 11},
    };

    private static final Object[][] HOSPITALS = {
            {"CityCare Multispecialty Hospital", 17.4358, 78.4520, 40, 14, "GENERAL,CARDIAC,TRAUMA", "+91 40 5550 0101"},
            {"Metro Heart Institute", 17.4105, 78.4635, 30, 9, "CARDIAC,GENERAL", "+91 40 5550 0102"},
            {"Lifeline Trauma Centre", 17.3925, 78.4710, 50, 18, "TRAUMA,NEURO,GENERAL", "+91 40 5550 0103"},
            {"Sunrise Neuro and Stroke Institute", 17.4360, 78.5040, 25, 6, "NEURO,CARDIAC,GENERAL", "+91 40 5550 0104"},
            {"Green Cross Mother and Child Hospital", 17.3700, 78.4800, 35, 12, "PEDIATRIC,MATERNITY,GENERAL", "+91 40 5550 0105"},
    };

    private static final Object[][] AMBULANCES = {
            {"TS09 UA 1001", 1}, {"TS09 UA 1002", 4}, {"TS09 UA 1003", 9}, {"TS09 UA 1004", 11}, {"TS09 UA 1005", 13},
    };

    @Override
    public void run(String... args) {
        if (signals.count() == 0) seedNetwork();
        if (users.count() == 0) seedPeople();
        settings.autoApprove();           // touch so the key is readable
        signalService.refresh();
        network.reload();
    }

    private void seedNetwork() {
        Random rnd = new Random(42);
        List<TrafficSignal> saved = new ArrayList<>();
        for (Object[] j : JUNCTIONS) {
            TrafficSignal s = new TrafficSignal();
            s.setName((String) j[0]);
            s.setLat((Double) j[1]);
            s.setLng((Double) j[2]);
            s.setZone((String) j[3]);
            int cycle = 70 + rnd.nextInt(5) * 10;                      // 70..110 s
            s.setCycleSeconds(cycle);
            s.setGreenSeconds((int) (cycle * (0.42 + rnd.nextDouble() * 0.12)));
            s.setOffsetSeconds(rnd.nextInt(cycle));
            saved.add(signals.save(s));
        }
        for (int[] l : LINKS) {
            TrafficSignal a = saved.get(l[0] - 1), b = saved.get(l[1] - 1);
            RoadSegment r = new RoadSegment();
            r.setFromId(a.getId());
            r.setToId(b.getId());
            r.setLengthMeters(Geo.haversine(a.getLat(), a.getLng(), b.getLat(), b.getLng()) * 1.25);
            r.setCongestion(Math.round((1.0 + rnd.nextDouble() * 0.6) * 100.0) / 100.0);
            roads.save(r);
        }
        log.info("Seeded {} junctions and {} roads", saved.size(), LINKS.length);
    }

    private AppUser user(String name, String email, Role role, Long hospitalId, String phone) {
        AppUser u = new AppUser();
        u.setName(name);
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(DEMO_PASSWORD));
        u.setRole(role);
        u.setHospitalId(hospitalId);
        u.setPhone(phone);
        return users.save(u);
    }

    private void seedPeople() {
        user("System Admin", "admin@sanet.demo", Role.ADMIN, null, null);
        user("Traffic Control Officer", "police@sanet.demo", Role.POLICE, null, "+91 90000 00001");
        user("Demo Patient", "patient@sanet.demo", Role.PATIENT, null, "+91 90000 10001");
        user("Second Patient", "patient2@sanet.demo", Role.PATIENT, null, "+91 90000 10002");

        List<Hospital> hs = new ArrayList<>();
        for (Object[] h : HOSPITALS) {
            Hospital x = new Hospital();
            x.setName((String) h[0]);
            x.setLat((Double) h[1]);
            x.setLng((Double) h[2]);
            x.setTotalBeds((Integer) h[3]);
            x.setBedsAvailable((Integer) h[4]);
            x.setSpecialities((String) h[5]);
            x.setContact((String) h[6]);
            hs.add(hospitals.save(x));
        }
        for (int i = 0; i < hs.size(); i++) {
            user("Staff - " + hs.get(i).getName(), "hospital" + (i + 1) + "@sanet.demo", Role.HOSPITAL, hs.get(i).getId(), null);
        }

        List<TrafficSignal> sig = signals.findAll();
        sig.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        for (int i = 0; i < AMBULANCES.length; i++) {
            TrafficSignal at = sig.get((Integer) AMBULANCES[i][1] - 1);
            AppUser d = user("Paramedic " + (i + 1), "ambulance" + (i + 1) + "@sanet.demo", Role.AMBULANCE, null, null);
            Ambulance a = new Ambulance();
            a.setVehicleNo((String) AMBULANCES[i][0]);
            a.setDriverId(d.getId());
            a.setLat(at.getLat());
            a.setLng(at.getLng());
            ambulances.save(a);
        }

        // eight road users parked at the midpoints of different roads, so alerts can be demonstrated immediately
        List<RoadSegment> rs = roads.findAll();
        rs.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        for (int i = 0; i < 8; i++) {
            RoadSegment r = rs.get((i * 3) % rs.size());
            TrafficSignal a = sig.stream().filter(s -> s.getId().equals(r.getFromId())).findFirst().orElseThrow();
            TrafficSignal b = sig.stream().filter(s -> s.getId().equals(r.getToId())).findFirst().orElseThrow();
            AppUser u = user("Road User " + (i + 1), "driver" + (i + 1) + "@sanet.demo", Role.DRIVER, null, null);
            u.setLastLat((a.getLat() + b.getLat()) / 2);
            u.setLastLng((a.getLng() + b.getLng()) / 2);
            users.save(u);
        }
        log.info("Seeded demo accounts. Password for all: {}", DEMO_PASSWORD);
    }
}
