package com.dairy.milkroute.domain.geo;

/**
 * A latitude/longitude pair, and the two pieces of spherical geometry the whole system
 * rests on: how far apart two places are, and where you end up travelling a bearing from
 * one of them.
 *
 * <p>Plain Java with no Spring and no PostGIS. Distance is computed here rather than in
 * the database, which is what lets the clean-machine story stay "postgres:16-alpine and
 * nothing else".
 *
 * <p>Everything here is straight-line. Roads wander, and the circuity factor that accounts
 * for that lives in the travel-time model, not in this class.
 *
 * @param lat degrees north, negative for south
 * @param lng degrees east, negative for west
 */
public record GeoPoint(double lat, double lng) {

    /**
     * IUGG mean Earth radius. The choice matters less than using one value everywhere:
     * distances are compared against each other far more often than against a map.
     */
    private static final double EARTH_RADIUS_KM = 6371.0088;

    public GeoPoint {
        if (lat < -90 || lat > 90) {
            throw new IllegalArgumentException("latitude out of range: " + lat);
        }
        if (lng < -180 || lng > 180) {
            throw new IllegalArgumentException("longitude out of range: " + lng);
        }
    }

    /**
     * Great-circle distance in kilometres.
     *
     * <p>Haversine rather than the simpler spherical law of cosines, which loses precision
     * on short distances. Collection points sit 200 to 600 m apart, so short distances are
     * the common case here, not the edge case.
     */
    public double haversineKm(GeoPoint other) {
        double lat1 = Math.toRadians(lat);
        double lat2 = Math.toRadians(other.lat);
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(other.lng - lng);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);

        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /**
     * The point reached by travelling {@code km} along a great circle on the given
     * compass bearing.
     *
     * <p>This is what strings villages out along a corridor instead of scattering them at
     * random, and it is why the seeded map looks like a road network rather than confetti.
     *
     * @param bearingDegrees compass bearing, 0 is north and 90 is east
     * @param km             distance to travel
     */
    public GeoPoint project(double bearingDegrees, double km) {
        double angular = km / EARTH_RADIUS_KM;
        double bearing = Math.toRadians(bearingDegrees);
        double lat1 = Math.toRadians(lat);
        double lng1 = Math.toRadians(lng);

        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(angular)
                + Math.cos(lat1) * Math.sin(angular) * Math.cos(bearing));

        double lng2 = lng1 + Math.atan2(
                Math.sin(bearing) * Math.sin(angular) * Math.cos(lat1),
                Math.cos(angular) - Math.sin(lat1) * Math.sin(lat2));

        // Wrap into [-180, 180] so a corridor crossing the antimeridian stays valid.
        double normalisedLng = (Math.toDegrees(lng2) + 540) % 360 - 180;

        return new GeoPoint(Math.toDegrees(lat2), normalisedLng);
    }
}
