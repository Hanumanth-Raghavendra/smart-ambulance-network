package com.smartambulance.sim;

import com.smartambulance.domain.TrafficSignal;
import com.smartambulance.repo.Repos.SignalRepo;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signal controller interface (simulated). Every signal runs a fixed cycle; the platform can force a signal
 * GREEN for an approaching ambulance (green corridor). In a real deployment {@link #force} / {@link #release}
 * would call the city's traffic-controller gateway.
 */
@Component
public class SignalService {
    public record SignalState(long id, String name, double lat, double lng, boolean green, boolean forced, double secondsToChange) {}

    private final SignalRepo repo;
    private final SimClock clock;
    private volatile Map<Long, TrafficSignal> signals = new HashMap<>();
    /** signalId -> request ids currently holding it green */
    private final Map<Long, Set<Long>> forced = new ConcurrentHashMap<>();

    public SignalService(SignalRepo repo, SimClock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public synchronized void refresh() {
        Map<Long, TrafficSignal> m = new HashMap<>();
        for (TrafficSignal s : repo.findAll()) m.put(s.getId(), s);
        this.signals = m;
    }

    private TrafficSignal get(long id) {
        if (signals.isEmpty()) refresh();
        return signals.get(id);
    }

    public Collection<TrafficSignal> all() {
        if (signals.isEmpty()) refresh();
        return signals.values();
    }

    private static double phase(TrafficSignal s, double simT) {
        double cycle = Math.max(10, s.getCycleSeconds());
        double p = (simT + s.getOffsetSeconds()) % cycle;
        return p < 0 ? p + cycle : p;
    }

    /** Natural (uncontrolled) state: true if the signal is green at the given simulated time. */
    public boolean naturalGreen(long id, double simT) {
        TrafficSignal s = get(id);
        return s == null || phase(s, simT) < s.getGreenSeconds();
    }

    /** Seconds an arriving vehicle would wait at the red light if nobody intervenes. 0 when green. */
    public double naturalWait(long id, double simT) {
        TrafficSignal s = get(id);
        if (s == null) return 0;
        double p = phase(s, simT);
        return p < s.getGreenSeconds() ? 0 : Math.max(10, s.getCycleSeconds()) - p;
    }

    public boolean isForced(long id) {
        Set<Long> r = forced.get(id);
        return r != null && !r.isEmpty();
    }

    public boolean isForcedBy(long id, long requestId) {
        Set<Long> r = forced.get(id);
        return r != null && r.contains(requestId);
    }

    public boolean isGreen(long id, double simT) {
        return isForced(id) || naturalGreen(id, simT);
    }

    public void force(long signalId, long requestId) {
        forced.computeIfAbsent(signalId, k -> ConcurrentHashMap.newKeySet()).add(requestId);
    }

    public void release(long signalId, long requestId) {
        Set<Long> r = forced.get(signalId);
        if (r != null) {
            r.remove(requestId);
            if (r.isEmpty()) forced.remove(signalId);
        }
    }

    public void releaseAll(long requestId) {
        for (Long id : new ArrayList<>(forced.keySet())) release(id, requestId);
    }

    public List<SignalState> snapshot() {
        double now = clock.now();
        List<SignalState> out = new ArrayList<>();
        for (TrafficSignal s : all()) {
            boolean f = isForced(s.getId());
            boolean g = f || naturalGreen(s.getId(), now);
            double p = phase(s, now);
            double toChange = p < s.getGreenSeconds() ? s.getGreenSeconds() - p : Math.max(10, s.getCycleSeconds()) - p;
            out.add(new SignalState(s.getId(), s.getName(), s.getLat(), s.getLng(), g, f, f ? 0 : toChange));
        }
        out.sort(Comparator.comparingLong(SignalState::id));
        return out;
    }
}
