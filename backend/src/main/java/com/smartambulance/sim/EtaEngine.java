package com.smartambulance.sim;

import com.smartambulance.geo.RoutePlan;
import com.smartambulance.geo.RoutePlan.Stop;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Junction-wise ETA prediction. For every junction still ahead it computes the arrival time as
 * (segment length / segment speed) summed along the route, plus the red-light wait predicted from the
 * signal's known cycle. Two timelines are kept: one assuming approved green corridors, one assuming none.
 * It is recomputed from the ambulance's current position on every update.
 */
@Component
public class EtaEngine {
    public record StopEta(long signalId, String name, double distanceAhead, double etaSec,
                          double etaNoClearanceSec, double predictedWaitSec) {}

    public record Prediction(double totalSec, double totalNoClearanceSec, List<StopEta> stops) {}

    private final SignalService signals;

    public EtaEngine(SignalService signals) {
        this.signals = signals;
    }

    /**
     * @param approved   signal ids whose green corridor is approved (their wait counts as zero)
     * @param waitingAt  signal the ambulance is currently stopped at, or null
     */
    public Prediction predict(RoutePlan plan, double progress, Long waitingAt, double simNow, Set<Long> approved) {
        double t1 = 0, t2 = 0;
        List<StopEta> out = new ArrayList<>();

        // ambulance is stopped at a red light right now
        if (waitingAt != null) {
            Stop s = plan.stops.stream().filter(x -> x.signalId == waitingAt).findFirst().orElse(null);
            if (s != null) {
                double w2 = signals.naturalWait(s.signalId, simNow);
                double w1 = approved.contains(s.signalId) || signals.isForced(s.signalId) ? 0 : w2;
                out.add(new StopEta(s.signalId, s.name, 0, 0, 0, w2));
                t1 += w1;
                t2 += w2;
            }
        }

        int first = Math.min(plan.segmentIndexAt(progress), plan.cum.length - 2);
        for (int i = first; i < plan.cum.length - 1; i++) {
            double from = Math.max(progress, plan.cum[i]);
            double dt = (plan.cum[i + 1] - from) / plan.segSpeedMps[i];
            t1 += dt;
            t2 += dt;
            Stop stop = plan.stopAtVertex(i + 1);
            if (stop != null && !(waitingAt != null && stop.signalId == waitingAt && plan.cum[i + 1] <= progress + 1e-6)) {
                double w2 = signals.naturalWait(stop.signalId, simNow + t2);
                double w1 = approved.contains(stop.signalId) ? 0 : signals.naturalWait(stop.signalId, simNow + t1);
                out.add(new StopEta(stop.signalId, stop.name, plan.cum[i + 1] - progress, t1, t2, w2));
                t1 += w1;
                t2 += w2;
            }
        }
        return new Prediction(t1, t2, out);
    }
}
