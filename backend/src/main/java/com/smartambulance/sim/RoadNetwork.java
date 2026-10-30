package com.smartambulance.sim;

import com.smartambulance.config.AppProperties;
import com.smartambulance.domain.RoadSegment;
import com.smartambulance.domain.TrafficSignal;
import com.smartambulance.geo.Geo;
import com.smartambulance.geo.RoutePlan;
import com.smartambulance.geo.RoutePlan.Stop;
import com.smartambulance.repo.Repos.RoadRepo;
import com.smartambulance.repo.Repos.SignalRepo;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * In-memory road graph (junctions + roads) and the route engine. Routing is Dijkstra on estimated
 * ambulance travel time, where each road's speed is the ambulance base speed divided by its congestion factor.
 * This is the extension point for a live traffic provider such as the Google Maps Directions / Distance Matrix API.
 */
@Component
public class RoadNetwork {
    private record Edge(long to, double meters, double speedMps) {}

    private static final class Graph {
        final Map<Long, TrafficSignal> nodes = new HashMap<>();
        final Map<Long, List<Edge>> adj = new HashMap<>();
    }

    private final SignalRepo signalRepo;
    private final RoadRepo roadRepo;
    private final AppProperties props;
    private volatile Graph graph;

    public RoadNetwork(SignalRepo signalRepo, RoadRepo roadRepo, AppProperties props) {
        this.signalRepo = signalRepo;
        this.roadRepo = roadRepo;
        this.props = props;
    }

    public synchronized void reload() {
        Graph g = new Graph();
        for (TrafficSignal s : signalRepo.findAll()) {
            g.nodes.put(s.getId(), s);
            g.adj.put(s.getId(), new ArrayList<>());
        }
        double base = props.sim().ambulanceKmph() / 3.6;
        for (RoadSegment r : roadRepo.findAll()) {
            if (!g.nodes.containsKey(r.getFromId()) || !g.nodes.containsKey(r.getToId())) continue;
            double speed = base / Math.max(1.0, r.getCongestion());
            g.adj.get(r.getFromId()).add(new Edge(r.getToId(), r.getLengthMeters(), speed));
            g.adj.get(r.getToId()).add(new Edge(r.getFromId(), r.getLengthMeters(), speed));
        }
        this.graph = g;
    }

    private Graph graph() {
        Graph g = graph;
        if (g == null) {
            reload();
            g = graph;
        }
        return g;
    }

    public boolean isEmpty() {
        return graph().nodes.isEmpty();
    }

    public Optional<TrafficSignal> nearestSignal(double lat, double lng) {
        TrafficSignal best = null;
        double bd = Double.MAX_VALUE;
        for (TrafficSignal s : graph().nodes.values()) {
            double d = Geo.haversine(lat, lng, s.getLat(), s.getLng());
            if (d < bd) { bd = d; best = s; }
        }
        return Optional.ofNullable(best);
    }

    public double distanceToNetwork(double lat, double lng) {
        return nearestSignal(lat, lng).map(s -> Geo.haversine(lat, lng, s.getLat(), s.getLng())).orElse(Double.MAX_VALUE);
    }

    /** Junction ids on the cheapest path from a to b (inclusive), or empty if unreachable. */
    private List<Long> shortestPath(long a, long b) {
        Graph g = graph();
        Map<Long, Double> dist = new HashMap<>();
        Map<Long, Long> prev = new HashMap<>();
        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(x -> x[0]));
        dist.put(a, 0.0);
        pq.add(new double[]{0, a});
        while (!pq.isEmpty()) {
            double[] cur = pq.poll();
            long u = (long) cur[1];
            if (cur[0] > dist.getOrDefault(u, Double.MAX_VALUE)) continue;
            if (u == b) break;
            for (Edge e : g.adj.getOrDefault(u, List.of())) {
                double nd = cur[0] + e.meters() / e.speedMps();
                if (nd < dist.getOrDefault(e.to(), Double.MAX_VALUE)) {
                    dist.put(e.to(), nd);
                    prev.put(e.to(), u);
                    pq.add(new double[]{nd, e.to()});
                }
            }
        }
        if (!dist.containsKey(b)) return List.of();
        LinkedList<Long> path = new LinkedList<>();
        for (Long at = b; at != null; at = prev.get(at)) path.addFirst(at);
        return path;
    }

    private double edgeSpeed(long from, long to) {
        for (Edge e : graph().adj.getOrDefault(from, List.of())) if (e.to() == to) return e.speedMps();
        return props.sim().ambulanceKmph() / 3.6;
    }

    /** Fastest route between two arbitrary coordinates, snapped to the junction graph at both ends. */
    public RoutePlan route(String type, double sLat, double sLng, double eLat, double eLng) {
        Graph g = graph();
        TrafficSignal a = nearestSignal(sLat, sLng).orElseThrow(() -> new IllegalStateException("Road network is empty"));
        TrafficSignal b = nearestSignal(eLat, eLng).orElseThrow();
        List<Long> path = shortestPath(a.getId(), b.getId());
        if (path.isEmpty()) throw new IllegalStateException("No route between the two points");

        double local = props.sim().ambulanceKmph() * 0.7 / 3.6;   // connector roads off the main network
        List<double[]> pts = new ArrayList<>();
        List<Double> speeds = new ArrayList<>();
        List<Stop> stops = new ArrayList<>();

        pts.add(new double[]{sLat, sLng});
        for (int i = 0; i < path.size(); i++) {
            TrafficSignal n = g.nodes.get(path.get(i));
            speeds.add(i == 0 ? local : edgeSpeed(path.get(i - 1), path.get(i)));
            pts.add(new double[]{n.getLat(), n.getLng()});
            stops.add(new Stop(pts.size() - 1, n.getId(), n.getName(), 0));
        }
        speeds.add(local);
        pts.add(new double[]{eLat, eLng});

        double[] sp = speeds.stream().mapToDouble(Double::doubleValue).toArray();
        return RoutePlan.build(type, pts, sp, stops);
    }
}
