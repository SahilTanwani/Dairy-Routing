package com.dairy.milkroute.domain.geo;

import com.dairy.milkroute.enums.Session;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Travel time from straight-line distance, a circuity factor and three speed bands.
 *
 * <p>Deliberately not a routing engine. There is no OSRM, no road graph and no traffic
 * feed, because the thing this system has to get right is <em>whether the milk survives the
 * journey</em>, and that question is decided by tens of minutes rather than by whether a
 * particular turn is left or right. A model that is honest about being an estimate, and
 * carries a safety buffer to match, beats a precise-looking number nobody has validated.
 *
 * <p>Three bands rather than one average speed, because the difference between a village
 * lane and a district road is the largest single effect in the model — larger than any
 * refinement within a band would be. A short hop between two collection points genuinely
 * happens at 15 km/h, and pricing it at 34 would make the planner believe it can serve a
 * village in a third of the time it takes.
 *
 * <p>The band is chosen on <em>road</em> distance, not straight-line, so the circuity
 * factor is applied before the comparison. Otherwise a 9.9 km straight hop, which is 13.4 km
 * of actual road, would be priced as a connecting road rather than a district one.
 *
 * <p>Plain Java, built with {@code new}: this is domain code and knows nothing about Spring.
 */
public final class HaversineTravelTime implements TravelTimeProvider {

    private static final int SECONDS_PER_HOUR = 3600;
    private static final double SECONDS_PER_MINUTE = 60.0;

    /** Road-kilometre thresholds between the three speed bands. */
    private static final double VILLAGE_LANE_LIMIT_KM = 2;
    private static final double CONNECTING_ROAD_LIMIT_KM = 10;

    private final TravelParameters parameters;
    private final TravelMatrixCache cache;

    /** Uses a private cache, which is what the tests and one-off calculations want. */
    public HaversineTravelTime(TravelParameters parameters) {
        this(parameters, new TravelMatrixCache());
    }

    public HaversineTravelTime(TravelParameters parameters, TravelMatrixCache cache) {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public Duration between(GeoPoint a, GeoPoint b, Session session) {
        double roadKm = roadKm(a, b);
        return Duration.ofSeconds((long) (roadKm / speedKmph(roadKm, session) * SECONDS_PER_HOUR));
    }

    @Override
    public TravelMatrix matrix(List<GeoPoint> points, Session session) {
        return cache.get(points, session, parameters,
                () -> new TravelMatrix(points, (from, to) -> minutesBetween(from, to, session)));
    }

    /** Straight-line distance inflated by the circuity factor: what a tanker actually drives. */
    public double roadKm(GeoPoint a, GeoPoint b) {
        return a.haversineKm(b) * parameters.circuityFactor();
    }

    /**
     * The band's speed, adjusted for the session. Empty pre-dawn roads run faster than the
     * same roads into evening traffic, and evenings are the hard session for exactly this
     * kind of compounding reason.
     */
    public double speedKmph(double roadKm, Session session) {
        double base;
        if (roadKm < VILLAGE_LANE_LIMIT_KM) {
            base = parameters.speedUnder2Km();
        } else if (roadKm < CONNECTING_ROAD_LIMIT_KM) {
            base = parameters.speed2To10Km();
        } else {
            base = parameters.speedOver10Km();
        }

        return base * (session == Session.MORNING
                ? parameters.morningSpeedFactor()
                : parameters.eveningSpeedFactor());
    }

    /**
     * The same journey as {@link #between}, in minutes.
     *
     * <p>Derived from the Duration rather than computed separately, so the matrix and a
     * direct call can never disagree by a rounding step.
     */
    public double minutesBetween(GeoPoint a, GeoPoint b, Session session) {
        return between(a, b, session).toSeconds() / SECONDS_PER_MINUTE;
    }

    public TravelParameters parameters() {
        return parameters;
    }
}
