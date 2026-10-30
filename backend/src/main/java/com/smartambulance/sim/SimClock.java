package com.smartambulance.sim;

import com.smartambulance.config.AppProperties;
import org.springframework.stereotype.Component;

/**
 * Simulation clock: simulated seconds = real seconds x speed multiplier.
 * Ambulance movement, signal cycles, ETAs and delays are all expressed on this clock so a
 * 6-minute trip can be demonstrated in about a minute.
 */
@Component
public class SimClock {
    private final double speed;

    public SimClock(AppProperties props) {
        this.speed = props.sim().speedMultiplier();
    }

    public double now() {
        return System.currentTimeMillis() / 1000.0 * speed;
    }

    public double speed() {
        return speed;
    }
}
