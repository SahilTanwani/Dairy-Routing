package com.dairy.milkroute.domain.geo;

import com.dairy.milkroute.enums.Session;
import java.time.Duration;
import java.util.List;

/**
 * How long it takes to drive between two places.
 *
 * <p>This is the seam. Every travel estimate in the system goes through this interface, so
 * replacing the Haversine model with a real routing engine is a matter of supplying a
 * different implementation rather than editing the planner, the ETA calculator and the
 * monitor. The estimates would get better; nothing above would change.
 *
 * <p>The session is a parameter rather than state because the same pair of points is a
 * different journey at 05:00 and at 17:00, and a provider that had to be rebuilt per
 * session would be easy to use with the wrong one.
 */
public interface TravelTimeProvider {

    /** Driving time from {@code a} to {@code b}. Symmetric: this model has no one-way streets. */
    Duration between(GeoPoint a, GeoPoint b, Session session);

    /**
     * Every pairwise time across a set of points, computed once.
     *
     * <p>The planner asks for the same distances thousands of times while comparing merges,
     * so paying for them once up front is the difference between a plan in seconds and a
     * plan in minutes.
     */
    TravelMatrix matrix(List<GeoPoint> points, Session session);
}
