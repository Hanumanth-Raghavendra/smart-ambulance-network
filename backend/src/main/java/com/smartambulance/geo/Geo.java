package com.smartambulance.geo;

/** Small geometry helpers (haversine distance, bearings, projection onto a segment). */
public final class Geo {
    private static final double R = 6_371_000.0;
    private static final String[] COMPASS = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};

    private Geo() {}

    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Initial bearing from point 1 to point 2, degrees 0..360 (0 = north). */
    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    public static String compass(double bearingDeg) {
        return COMPASS[(int) Math.round(((bearingDeg % 360) + 360) % 360 / 45.0) % 8];
    }

    public static double[] lerp(double[] a, double[] b, double f) {
        return new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f};
    }

    /**
     * Projects point p onto segment a-b using a local flat-earth approximation (fine for city scale).
     *
     * @return {t in 0..1 along the segment, distance in metres from p to the projected point}
     */
    public static double[] projectOnSegment(double plat, double plng, double[] a, double[] b) {
        double cos = Math.cos(Math.toRadians(a[0]));
        double ax = 0, ay = 0;
        double bx = (b[1] - a[1]) * cos * 111_320.0, by = (b[0] - a[0]) * 110_540.0;
        double px = (plng - a[1]) * cos * 111_320.0, py = (plat - a[0]) * 110_540.0;
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 < 1e-9 ? 0 : ((px - ax) * dx + (py - ay) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        double qx = ax + t * dx, qy = ay + t * dy;
        return new double[]{t, Math.hypot(px - qx, py - qy)};
    }
}
