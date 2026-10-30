package com.smartambulance.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartambulance.domain.*;
import com.smartambulance.domain.Enums.*;
import com.smartambulance.geo.Geo;
import com.smartambulance.dto.Views.RankedHospital;
import com.smartambulance.geo.RoutePlan;
import com.smartambulance.repo.Repos.*;
import com.smartambulance.service.HospitalRanker;
import com.smartambulance.sim.PlanCodec;
import com.smartambulance.sim.RoadNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Fills an empty database with a few days of completed sample emergencies, so the analytics charts, trip lists,
 * hospital histories and driver alert logs are not empty on first launch. Runs only when there are no requests yet.
 * Disable with the environment variable SEED_SAMPLE_HISTORY=false. All times are simulated seconds, like live trips.
 */
@Component
@Order(2)
public class SampleHistorySeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(SampleHistorySeeder.class);
    private static final String[] SPECIALITIES = {"GENERAL", "CARDIAC", "TRAUMA", "NEURO", "PEDIATRIC", "MATERNITY"};
    private static final Map<String, String[]> DESCRIPTIONS = Map.of(
            "CARDIAC", new String[]{"Chest pain spreading to the left arm", "Sudden breathlessness and sweating"},
            "TRAUMA", new String[]{"Road accident, leg injury and bleeding", "Fall from height, suspected fracture"},
            "NEURO", new String[]{"Sudden weakness on one side, slurred speech", "Severe headache and confusion"},
            "PEDIATRIC", new String[]{"Child with high fever and seizure", "Infant struggling to breathe"},
            "MATERNITY", new String[]{"Labour pains, contractions every 4 minutes", "Pregnant woman with heavy bleeding"},
            "GENERAL", new String[]{"High fever and vomiting since morning", "Fainted at home, now drowsy"});
    private static final String[] NOTES = {"Stable, oxygen on", "Conscious, IV line started", "Bleeding controlled",
            "Responding to voice, monitoring", "Pain relief given, vitals improving"};
    private static final String[] DIRECTIONS = {"north", "south", "east", "west", "north-east", "south-west"};

    private final RequestRepo requests;
    private final ClearanceRepo clearances;
    private final AlertRepo alerts;
    private final UserRepo users;
    private final AmbulanceRepo ambulances;
    private final SignalRepo signals;
    private final HospitalRepo hospitals;
    private final HospitalRanker ranker;
    private final RoadNetwork network;
    private final PlanCodec codec;
    private final ObjectMapper mapper;
    private final boolean enabled;

    public SampleHistorySeeder(RequestRepo requests, ClearanceRepo clearances, AlertRepo alerts, UserRepo users,
                               AmbulanceRepo ambulances, SignalRepo signals, HospitalRepo hospitals, HospitalRanker ranker, RoadNetwork network,
                               PlanCodec codec, ObjectMapper mapper, @Value("${SEED_SAMPLE_HISTORY:true}") boolean enabled) {
        this.requests = requests;
        this.clearances = clearances;
        this.alerts = alerts;
        this.users = users;
        this.ambulances = ambulances;
        this.signals = signals;
        this.hospitals = hospitals;
        this.ranker = ranker;
        this.network = network;
        this.codec = codec;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    @Override
    public void run(String... args) {
        if (!enabled || requests.count() > 0) return;
        List<AppUser> patients = new ArrayList<>();
        users.findByEmailIgnoreCase("patient@sanet.demo").ifPresent(patients::add);
        users.findByEmailIgnoreCase("patient2@sanet.demo").ifPresent(patients::add);
        AppUser police = users.findByEmailIgnoreCase("police@sanet.demo").orElse(null);
        List<AppUser> roadUsers = users.findAll().stream().filter(u -> u.getRole() == Role.DRIVER).toList();
        List<Ambulance> fleet = ambulances.findAll();
        List<TrafficSignal> junctions = signals.findAll();
        if (patients.isEmpty() || police == null || fleet.isEmpty() || junctions.isEmpty()) return;

        Random rnd = new Random(2026);
        int trips = 14;
        for (int i = 0; i < trips; i++) {
            try {
                seedTrip(i, trips, rnd, patients.get(i % patients.size()), police, roadUsers, fleet, junctions);
            } catch (RuntimeException e) {
                log.warn("Skipped sample trip {}: {}", i, e.getMessage());
            }
        }
        seedCancelled(trips, patients.get(patients.size() - 1), junctions.get(3));
        log.info("Seeded sample emergency history ({} trips)", trips);
    }

    private void seedTrip(int i, int total, Random rnd, AppUser patient, AppUser police, List<AppUser> roadUsers,
                          List<Ambulance> fleet, List<TrafficSignal> junctions) {
        // spread the trips over all hospitals: pick a hospital, then a pickup 2-5 km away that needs one of its specialities
        List<Hospital> hs = hospitals.findAll().stream().sorted(Comparator.comparingLong(Hospital::getId)).toList();
        Hospital pref = hs.get(i % hs.size());
        List<TrafficSignal> farEnough = junctions.stream().filter(j -> {
            double d = Geo.haversine(j.getLat(), j.getLng(), pref.getLat(), pref.getLng());
            return d >= 2000 && d <= 5500;
        }).toList();
        TrafficSignal at = farEnough.isEmpty() ? junctions.get(rnd.nextInt(junctions.size()))
                : farEnough.get(rnd.nextInt(farEnough.size()));
        double lat = at.getLat() + (rnd.nextDouble() - 0.5) * 0.004, lng = at.getLng() + (rnd.nextDouble() - 0.5) * 0.004;
        Severity sev = rnd.nextDouble() < 0.35 ? Severity.CRITICAL : rnd.nextDouble() < 0.62 ? Severity.SERIOUS : Severity.MINOR;
        List<String> offered = new ArrayList<>(Arrays.asList(pref.getSpecialities().split(",")));
        offered.remove("GENERAL");
        String spec = offered.isEmpty() ? "GENERAL" : offered.get(rnd.nextInt(offered.size()));

        List<RankedHospital> ranking = ranker.rank(lat, lng, sev, spec, Set.of());
        if (ranking.isEmpty()) throw new IllegalStateException("no hospital available");
        long hospitalId = ranking.get(0).hospitalId();

        Ambulance amb = fleet.stream().min(Comparator.comparingDouble(
                a -> network.route("ESTIMATE", a.getLat(), a.getLng(), lat, lng).baseSeconds)).orElseThrow();
        Hospital h = hospitals.findById(hospitalId).orElseThrow();
        double[] hosPos = {h.getLat(), h.getLng()};
        RoutePlan toPatient = network.route("TO_PATIENT", amb.getLat(), amb.getLng(), lat, lng);
        RoutePlan toHospital = network.route("TO_HOSPITAL", lat, lng, hosPos[0], hosPos[1]);

        // every 5th trip is one where the traffic police did not clear the road, to show the contrast
        boolean cleared = i % 5 != 3;
        boolean rejected = !cleared && i % 2 == 1;

        double t0 = -400_000 + i * 25_000.0;
        double accepted = t0 + 20 + rnd.nextInt(40);
        double dispatched = accepted + 5;

        EmergencyRequest r = new EmergencyRequest();
        r.setPatientId(patient.getId());
        r.setPatientName(patient.getName());
        r.setPatientPhone(patient.getPhone());
        r.setLat(lat);
        r.setLng(lng);
        r.setAddress(at.getName() + " area");
        r.setSeverity(sev);
        r.setRequiredSpeciality(spec);
        String[] d = DESCRIPTIONS.get(spec);
        r.setDescription(d[rnd.nextInt(d.length)]);
        r.setHospitalId(hospitalId);
        r.setAmbulanceId(amb.getId());
        r.setRankingJson(json(ranking));
        r.setStatus(RequestStatus.COMPLETED);
        r.setLeg(Leg.TO_HOSPITAL);
        r.setConditionNote(NOTES[rnd.nextInt(NOTES.length)]);
        r.setHeartRate(80 + rnd.nextInt(65));
        r.setSpo2(88 + rnd.nextInt(11));
        Instant when = Instant.now().minus((long) (total - i) * 6 + 1, ChronoUnit.HOURS).minus(rnd.nextInt(50), ChronoUnit.MINUTES);
        r.setCreatedAt(when);
        r.setUpdatedAt(when);
        r.setSimCreated(t0);
        r.setSimAccepted(accepted);
        r.setSimDispatched(dispatched);
        codec.write(r, List.of(toPatient, toHospital));
        r.setProgressMeters(toHospital.totalMeters);
        r = requests.save(r);

        double[] leg1 = logLeg(r, Leg.TO_PATIENT, toPatient, dispatched, cleared, rejected, rnd, police, when);
        double arrivedPatient = dispatched + toPatient.baseSeconds + leg1[1];
        double pickedUp = arrivedPatient + 18;
        double[] leg2 = logLeg(r, Leg.TO_HOSPITAL, toHospital, pickedUp, cleared, rejected, rnd, police, when);
        double arrivedHospital = pickedUp + toHospital.baseSeconds + leg2[1];

        r.setSimArrivedPatient(arrivedPatient);
        r.setSimPickedUp(pickedUp);
        r.setSimArrivedHospital(arrivedHospital);
        r.setSimCompleted(arrivedHospital + 40 + rnd.nextInt(80));
        r.setDelaySavedSec(leg1[0] + leg2[0]);
        r.setWaitedSec(leg1[1] + leg2[1]);
        requests.save(r);

        // warnings sent to nearby road users while the ambulance was driving
        int nAlerts = 2 + rnd.nextInt(4);
        for (int k = 0; k < nAlerts && !roadUsers.isEmpty(); k++) {
            AppUser u = roadUsers.get(rnd.nextInt(roadUsers.size()));
            double dist = 120 + rnd.nextInt(760);
            String dir = DIRECTIONS[rnd.nextInt(DIRECTIONS.length)];
            DriverAlert a = new DriverAlert();
            a.setRequestId(r.getId());
            a.setUserId(u.getId());
            a.setDistanceMeters(dist);
            a.setDirection(dir);
            a.setMessage(String.format("Ambulance approaching from the %s, about %d m away. Slow down, move to the left and give way.", dir, (long) dist));
            a.setCreatedAt(when.plus(3 + k * 2L, ChronoUnit.MINUTES));
            alerts.save(a);
        }
    }

    /** Writes one clearance row per junction of the leg. Returns {delaySaved, waited} in simulated seconds. */
    private double[] logLeg(EmergencyRequest r, Leg leg, RoutePlan plan, double start, boolean cleared, boolean rejected,
                            Random rnd, AppUser police, Instant when) {
        double saved = 0, waited = 0;
        int order = 0;
        double speed = plan.totalMeters / Math.max(1, plan.baseSeconds);
        for (RoutePlan.Stop s : plan.stops) {
            ClearanceLog l = new ClearanceLog();
            l.setRequestId(r.getId());
            l.setLeg(leg);
            l.setSignalId(s.signalId);
            l.setSignalName(s.name);
            l.setStopOrder(order++);
            double eta = start + s.distance / speed;
            l.setEtaAtRequestSec(s.distance / speed);
            l.setRequestedAtSim(start);
            if (cleared) {
                double sv = rnd.nextDouble() < 0.5 ? Math.round((12 + rnd.nextDouble() * 70) * 10) / 10.0 : 0;
                l.setStatus(ClearanceStatus.PASSED);
                l.setApproved(true);
                l.setDecidedBy(police.getId());
                l.setDecidedAtSim(start + 2);
                l.setGreenSentAtSim(eta - 30);
                l.setDelaySavedSec(sv);
                saved += sv;
            } else {
                double w = rnd.nextDouble() < 0.6 ? Math.round((18 + rnd.nextDouble() * 75) * 10) / 10.0 : 0;
                l.setStatus(rejected ? ClearanceStatus.REJECTED : ClearanceStatus.PENDING);
                if (rejected) {
                    l.setDecidedBy(police.getId());
                    l.setDecidedAtSim(start + 4);
                }
                l.setWaitedSec(w);
                waited += w;
            }
            l.setPassedAtSim(eta + waited);
            l.setCreatedAt(when);
            clearances.save(l);
        }
        return new double[]{saved, waited};
    }

    private void seedCancelled(int idx, AppUser patient, TrafficSignal at) {
        EmergencyRequest r = new EmergencyRequest();
        r.setPatientId(patient.getId());
        r.setPatientName(patient.getName());
        r.setPatientPhone(patient.getPhone());
        r.setLat(at.getLat());
        r.setLng(at.getLng());
        r.setAddress(at.getName() + " area");
        r.setSeverity(Severity.MINOR);
        r.setDescription("Request cancelled by the patient (help arrived by other means)");
        r.setStatus(RequestStatus.CANCELLED);
        r.setSimCreated(-400_000 + idx * 25_000.0);
        Instant when = Instant.now().minus(3, ChronoUnit.HOURS);
        r.setCreatedAt(when);
        r.setUpdatedAt(when);
        requests.save(r);
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
