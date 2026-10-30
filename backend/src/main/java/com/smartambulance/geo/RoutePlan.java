package com.smartambulance.geo;

import java.util.ArrayList;
import java.util.List;

/**
 * A planned route for one leg: a polyline whose vertices are the start point, the junctions on the way
 * (each a "stop" with a traffic signal) and the destination. Distances are cumulative metres along the polyline.
 * Plain public fields so that Jackson can store it as JSON on the request.
 */
public class RoutePlan {
    public String type;
    /** vertices as [lat, lng] */
    public List<double[]> points = new ArrayList<>();
    /** cumulative distance at each vertex; strictly increasing */
    public double[] cum;
    /** speed in m/s of segment i (vertex i to i+1) */
    public double[] segSpeedMps;
    public List<Stop> stops = new ArrayList<>();
    public double totalMeters;
    /** travel time ignoring signals */
    public double baseSeconds;

    public static class Stop {
        public int vertex;
        public long signalId;
        public String name;
        public double distance;

        public Stop() {}

        public Stop(int vertex, long signalId, String name, double distance) {
            this.vertex = vertex;
            this.signalId = signalId;
            this.name = name;
            this.distance = distance;
        }
    }

    /** Index of the segment containing the given distance (largest i with cum[i] <= d, capped to last segment). */
    public int segmentIndexAt(double meters) {
        int lo = 0, hi = cum.length - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (cum[mid] <= meters) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    public double[] pointAt(double meters) {
        if (points.isEmpty()) return new double[]{0, 0};
        if (meters <= 0) return points.get(0);
        if (meters >= totalMeters) return points.get(points.size() - 1);
        int i = segmentIndexAt(meters);
        double f = (meters - cum[i]) / (cum[i + 1] - cum[i]);
        return Geo.lerp(points.get(i), points.get(i + 1), f);
    }

    public Stop stopAtVertex(int vertex) {
        for (Stop s : stops) if (s.vertex == vertex) return s;
        return null;
    }

    /** Nearest point of the route at or after {@code fromMeters}: {alongMeters, lateralMeters}. */
    public double[] nearest(double lat, double lng, double fromMeters) {
        double bestAlong = fromMeters, bestLat = Double.MAX_VALUE;
        for (int i = 0; i < points.size() - 1; i++) {
            if (cum[i + 1] < fromMeters) continue;
            double[] pr = Geo.projectOnSegment(lat, lng, points.get(i), points.get(i + 1));
            double along = cum[i] + pr[0] * (cum[i + 1] - cum[i]);
            if (along < fromMeters) {
                // the closest point is behind the ambulance; clamp to its current position
                double[] here = pointAt(fromMeters);
                double d = Geo.haversine(lat, lng, here[0], here[1]);
                if (d < bestLat) { bestLat = d; bestAlong = fromMeters; }
            } else if (pr[1] < bestLat) {
                bestLat = pr[1];
                bestAlong = along;
            }
        }
        return new double[]{bestAlong, bestLat};
    }

    /** Builds a plan from raw vertices, enforcing strictly increasing distances (min 1 m per segment). */
    public static RoutePlan build(String type, List<double[]> pts, double[] segSpeed, List<Stop> stopList) {
        RoutePlan p = new RoutePlan();
        p.type = type;
        p.points = pts;
        p.segSpeedMps = segSpeed;
        p.cum = new double[pts.size()];
        double base = 0;
        for (int i = 0; i < pts.size() - 1; i++) {
            double d = Math.max(1.0, Geo.haversine(pts.get(i)[0], pts.get(i)[1], pts.get(i + 1)[0], pts.get(i + 1)[1]));
            p.cum[i + 1] = p.cum[i] + d;
            base += d / segSpeed[i];
        }
        p.totalMeters = p.cum[pts.size() - 1];
        p.baseSeconds = base;
        for (Stop s : stopList) {
            s.distance = p.cum[s.vertex];
            p.stops.add(s);
        }
        return p;
    }
}
