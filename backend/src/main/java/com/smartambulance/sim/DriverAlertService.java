package com.smartambulance.sim;

import com.smartambulance.config.AppProperties;
import com.smartambulance.domain.AppUser;
import com.smartambulance.domain.DriverAlert;
import com.smartambulance.domain.EmergencyRequest;
import com.smartambulance.domain.Enums.Role;
import com.smartambulance.dto.Views.AlertView;
import com.smartambulance.geo.Geo;
import com.smartambulance.geo.RoutePlan;
import com.smartambulance.repo.Repos.AlertRepo;
import com.smartambulance.repo.Repos.UserRepo;
import com.smartambulance.ws.Broadcaster;
import com.smartambulance.ws.Broadcaster.Event;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geo-fenced give-way alerts. A road user who opted in and shared a location gets an alert when they are within
 * {@code lateralMeters} of the ambulance's remaining route and within {@code aheadMeters} ahead of it.
 */
@Component
public class DriverAlertService {
    private final UserRepo users;
    private final AlertRepo alerts;
    private final Broadcaster broadcaster;
    private final AppProperties props;
    private final Map<Long, Set<Long>> inZone = new ConcurrentHashMap<>();
    private final Map<String, Double> lastAlertSim = new ConcurrentHashMap<>();

    public DriverAlertService(UserRepo users, AlertRepo alerts, Broadcaster broadcaster, AppProperties props) {
        this.users = users;
        this.alerts = alerts;
        this.broadcaster = broadcaster;
        this.props = props;
    }

    public void process(EmergencyRequest r, RoutePlan plan, double progress, double simNow) {
        double[] amb = plan.pointAt(progress);
        Set<Long> zone = inZone.computeIfAbsent(r.getId(), k -> ConcurrentHashMap.newKeySet());
        Set<Long> seen = new HashSet<>();
        var cfg = props.alert();

        for (AppUser u : users.findByRoleAndActiveTrueAndAlertsOptInTrue(Role.DRIVER)) {
            if (u.getLastLat() == null || u.getLastLng() == null) continue;
            double[] n = plan.nearest(u.getLastLat(), u.getLastLng(), progress);
            double ahead = n[0] - progress, lateral = n[1];
            if (lateral > cfg.lateralMeters() || ahead > cfg.aheadMeters()) continue;

            seen.add(u.getId());
            zone.add(u.getId());
            double dist = Math.round(Geo.haversine(u.getLastLat(), u.getLastLng(), amb[0], amb[1]));
            String dir = Geo.compass(Geo.bearing(u.getLastLat(), u.getLastLng(), amb[0], amb[1]));
            String msg = String.format("Ambulance approaching from the %s, about %d m away. Slow down, move to the left and give way.",
                    dir, (long) dist);

            String key = r.getId() + ":" + u.getId();
            Double last = lastAlertSim.get(key);
            if (last == null || simNow - last >= cfg.cooldownSeconds()) {
                DriverAlert a = new DriverAlert();
                a.setRequestId(r.getId());
                a.setUserId(u.getId());
                a.setDistanceMeters(dist);
                a.setDirection(dir);
                a.setMessage(msg);
                a = alerts.save(a);
                lastAlertSim.put(key, simNow);
                broadcaster.alertUser(u.getId(), new Event("ALERT", toView(a)));
            } else {
                broadcaster.alertUser(u.getId(), new Event("UPDATE",
                        Map.of("requestId", r.getId(), "distanceMeters", dist, "direction", dir, "message", msg)));
            }
        }

        for (Long uid : new ArrayList<>(zone)) {
            if (!seen.contains(uid)) {
                zone.remove(uid);
                broadcaster.alertUser(uid, new Event("CLEAR", Map.of("requestId", r.getId())));
            }
        }
    }

    /** Called when a leg ends or a trip is cancelled. */
    public void clear(long requestId) {
        Set<Long> zone = inZone.remove(requestId);
        if (zone != null) for (Long uid : zone) broadcaster.alertUser(uid, new Event("CLEAR", Map.of("requestId", requestId)));
        lastAlertSim.keySet().removeIf(k -> k.startsWith(requestId + ":"));
    }

    public static AlertView toView(DriverAlert a) {
        return new AlertView(a.getId(), a.getRequestId(), a.getDistanceMeters(), a.getDirection(), a.getMessage(), a.getCreatedAt());
    }
}
