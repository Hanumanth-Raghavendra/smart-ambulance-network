package com.smartambulance;

import com.smartambulance.config.AppProperties;
import com.smartambulance.domain.Enums.Severity;
import com.smartambulance.domain.Hospital;
import com.smartambulance.domain.TrafficSignal;
import com.smartambulance.geo.Geo;
import com.smartambulance.geo.RoutePlan;
import com.smartambulance.geo.RoutePlan.Stop;
import com.smartambulance.service.HospitalRanker;
import com.smartambulance.sim.EtaEngine;
import com.smartambulance.sim.SignalService;
import com.smartambulance.sim.SimClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests of the algorithms named in the report: ranking, per-junction ETA, signal timing, geometry. */
class CoreAlgorithmsTest {

    private AppProperties props;
    private SignalService signals;

    @BeforeEach
    void setUp() {
        props = new AppProperties(
                new AppProperties.Jwt("x".repeat(40), 60),
                new AppProperties.Cors(List.of("http://localhost")),
                new AppProperties.Sim(1.0, 500, 54, 3, 60),
                new AppProperties.Clearance(30, 150),
                new AppProperties.Alert(900, 250, 25),
                new AppProperties.Ranking(1.0, 15.0, 10.0, new AppProperties.SeverityMultiplier(0.7, 1.0, 1.5)),
                7000);
        SimClock clock = new SimClock(props);
        signals = new SignalService(Mockito.mock(com.smartambulance.repo.Repos.SignalRepo.class), clock) {
            // signal 1: 100 s cycle, green for the first 40 s; signal 2: always green
            @Override public boolean naturalGreen(long id, double t) { return id == 2 || (t % 100) < 40; }
            @Override public double naturalWait(long id, double t) { return naturalGreen(id, t) ? 0 : 100 - (t % 100); }
            @Override public boolean isForced(long id) { return false; }
        };
    }

    private RoutePlan straightPlan() {
        // 3 km straight east, one junction at 1000 m (signal 1) and one at 2000 m (signal 2), 15 m/s everywhere
        double lat = 17.4, lng0 = 78.4;
        double perMeter = 1.0 / (111_320 * Math.cos(Math.toRadians(lat)));
        List<double[]> pts = new ArrayList<>();
        for (int m : new int[]{0, 1000, 2000, 3000}) pts.add(new double[]{lat, lng0 + m * perMeter});
        List<Stop> stops = List.of(new Stop(1, 1, "J1", 0), new Stop(2, 2, "J2", 0));
        return RoutePlan.build("TEST", pts, new double[]{15, 15, 15}, new ArrayList<>(stops));
    }

    @Test
    void haversineAndBearing() {
        assertEquals(111_195, Geo.haversine(0, 0, 1, 0), 300);
        assertEquals("east", Geo.compass(Geo.bearing(17.4, 78.4, 17.4, 78.5)));
        assertEquals("north", Geo.compass(Geo.bearing(17.4, 78.4, 17.5, 78.4)));
    }

    @Test
    void routePlanDistancesAreStrictlyIncreasingAndPositionsInterpolate() {
        RoutePlan p = straightPlan();
        for (int i = 0; i < p.cum.length - 1; i++) assertTrue(p.cum[i + 1] > p.cum[i]);
        assertEquals(3000, p.totalMeters, 15);
        assertEquals(3000.0 / 15, p.baseSeconds, 1.0);
        double[] mid = p.pointAt(p.totalMeters / 2);
        assertEquals(17.4, mid[0], 1e-6);
        assertTrue(mid[1] > p.points.get(1)[1] && mid[1] < p.points.get(2)[1]);
        assertEquals(1000, p.stops.get(0).distance, 10);
    }

    @Test
    void nearestFindsAlongAndLateralDistance() {
        RoutePlan p = straightPlan();
        double perMeterLat = 1.0 / 110_540;
        double[] n = p.nearest(17.4 + 100 * perMeterLat, p.points.get(1)[1], 0);
        assertEquals(1000, n[0], 10);
        assertEquals(100, n[1], 5);
        // a point behind the ambulance is clamped to its current position
        double[] behind = p.nearest(17.4, p.points.get(0)[1], 500);
        assertEquals(500, behind[0], 1e-6);
    }

    @Test
    void etaWithoutClearanceIncludesRedLightWait() {
        EtaEngine eta = new EtaEngine(signals);
        RoutePlan p = straightPlan();
        // start at t=50 -> reaches J1 at t=50+66.7=116.7 -> (116.7 % 100)=16.7 < 40 => green, no wait.
        EtaEngine.Prediction green = eta.predict(p, 0, null, 50, Set.of());
        assertEquals(p.baseSeconds, green.totalNoClearanceSec(), 1.0);
        // start at t=-10 (== 90): arrives at J1 at 56.7 -> red, waits 43.3 s
        EtaEngine.Prediction red = eta.predict(p, 0, null, -10 + 100, Set.of());
        assertTrue(red.totalNoClearanceSec() > p.baseSeconds + 40, "red light adds roughly 43 s");
        assertEquals(2, red.stops().size());
        assertTrue(red.stops().get(0).predictedWaitSec() > 40);
    }

    @Test
    void approvedClearanceRemovesTheWaitFromTheClearanceTimeline() {
        EtaEngine eta = new EtaEngine(signals);
        RoutePlan p = straightPlan();
        EtaEngine.Prediction pr = eta.predict(p, 0, null, 90, Set.of(1L));
        assertEquals(p.baseSeconds, pr.totalSec(), 1.0);
        assertTrue(pr.totalNoClearanceSec() > pr.totalSec() + 40);
    }

    @Test
    void etaShrinksAsTheAmbulanceProgresses() {
        EtaEngine eta = new EtaEngine(signals);
        RoutePlan p = straightPlan();
        double a = eta.predict(p, 0, null, 50, Set.of()).totalSec();
        double b = eta.predict(p, 1500, null, 50, Set.of()).totalSec();
        assertTrue(b < a);
        assertEquals(1, eta.predict(p, 1500, null, 50, Set.of()).stops().size(), "only J2 is still ahead");
    }

    @Test
    void waitingAtASignalIsReportedAsFirstStop() {
        EtaEngine eta = new EtaEngine(signals);
        RoutePlan p = straightPlan();
        EtaEngine.Prediction pr = eta.predict(p, p.stops.get(0).distance, 1L, 90, Set.of());
        assertEquals(1L, pr.stops().get(0).signalId());
        assertEquals(0, pr.stops().get(0).distanceAhead(), 1e-9);
    }

    @Test
    void hospitalScoreRewardsSpecialityBedsAndShortTravel() {
        HospitalRanker r = new HospitalRanker(null, null, props);
        double near = r.score(5, true, 10, 40, Severity.SERIOUS);
        double far = r.score(15, true, 10, 40, Severity.SERIOUS);
        double noSpec = r.score(5, false, 10, 40, Severity.SERIOUS);
        double fullBeds = r.score(5, true, 40, 40, Severity.SERIOUS);
        double almostFull = r.score(5, true, 1, 40, Severity.SERIOUS);
        assertTrue(near < far);
        assertTrue(near < noSpec);
        assertTrue(fullBeds < near);
        assertTrue(almostFull > near);
        // critical patients weigh travel time more heavily
        assertTrue(r.score(15, true, 10, 40, Severity.CRITICAL) - r.score(5, true, 10, 40, Severity.CRITICAL)
                > far - near);
    }

    @Test
    void hospitalSpecialityMatching() {
        Hospital h = new Hospital();
        h.setSpecialities("CARDIAC,TRAUMA");
        assertTrue(h.hasSpeciality("cardiac"));
        assertTrue(h.hasSpeciality("GENERAL"));
        assertTrue(h.hasSpeciality(null));
        assertFalse(h.hasSpeciality("NEURO"));
    }

    @Test
    void naturalSignalCycleIsPeriodic() {
        TrafficSignal s = new TrafficSignal();
        s.setCycleSeconds(100);
        s.setGreenSeconds(40);
        s.setOffsetSeconds(10);
        var repo = Mockito.mock(com.smartambulance.repo.Repos.SignalRepo.class);
        s.setName("T");
        try {
            var f = TrafficSignal.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(s, 7L);
        } catch (ReflectiveOperationException e) { fail(e); }
        Mockito.when(repo.findAll()).thenReturn(List.of(s));
        SignalService real = new SignalService(repo, new SimClock(props));
        assertTrue(real.naturalGreen(7, 0));            // phase 10  -> green
        assertFalse(real.naturalGreen(7, 40));          // phase 50  -> red
        assertEquals(50, real.naturalWait(7, 40), 1e-9);// red until phase 100
        assertTrue(real.naturalGreen(7, 90));           // phase 0   -> green again
        real.force(7, 99);
        assertTrue(real.isGreen(7, 40), "a forced signal is green regardless of its cycle");
        real.release(7, 99);
        assertFalse(real.isGreen(7, 40));
    }
}
