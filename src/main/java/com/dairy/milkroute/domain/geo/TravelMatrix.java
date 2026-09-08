package com.dairy.milkroute.domain.geo;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every pairwise travel time across a set of points, computed once and held in memory.
 *
 * <p>The planner compares thousands of candidate merges and asks for the same leg over and
 * over. Recomputing a Haversine and a speed band each time is not expensive individually;
 * doing it a million times inside the savings loop is what turns a five-second plan into a
 * five-minute one.
 *
 * <h2>Points are canonicalised</h2>
 *
 * <p>The constructor sorts the points and drops duplicates, so a matrix is defined by the
 * <em>set</em> it covers rather than by the order it was handed. That is what makes the
 * cache key honest: two callers holding the same points in different orders get the same
 * matrix, and index-based access is well defined because indices are positions in
 * {@link #points()}, not in whatever list the caller passed.
 *
 * <p>Only the upper triangle is computed and then mirrored. The model has no one-way
 * streets, so half the work would be duplicated.
 */
public final class TravelMatrix {

    private final List<GeoPoint> points;
    private final Map<GeoPoint, Integer> indexByPoint;
    private final double[][] minutes;

    /**
     * @param requested the points to cover, in any order and with duplicates allowed
     * @param leg       the pairwise time function, normally a {@link TravelTimeProvider}
     *                  bound to one session
     */
    public TravelMatrix(List<GeoPoint> requested, LegTime leg) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(leg, "leg");

        this.points = canonicalise(requested);
        this.indexByPoint = new HashMap<>(this.points.size() * 2);
        for (int i = 0; i < this.points.size(); i++) {
            indexByPoint.put(this.points.get(i), i);
        }

        int n = this.points.size();
        this.minutes = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double value = leg.minutesBetween(this.points.get(i), this.points.get(j));
                minutes[i][j] = value;
                minutes[j][i] = value;
            }
        }
    }

    /** The points this matrix covers: sorted, deduplicated, and the index space for lookups. */
    public List<GeoPoint> points() {
        return points;
    }

    public int size() {
        return points.size();
    }

    /** Position of a point in {@link #points()}, or -1 if the matrix does not cover it. */
    public int indexOf(GeoPoint point) {
        return indexByPoint.getOrDefault(point, -1);
    }

    public boolean covers(GeoPoint point) {
        return indexByPoint.containsKey(point);
    }

    public double minutesBetween(int from, int to) {
        return minutes[from][to];
    }

    /**
     * Travel time between two points the matrix covers.
     *
     * <p>Throws rather than falling back to computing the leg on the spot. A point that
     * should be in the matrix and is not means the caller built the matrix from a different
     * set than it is now routing over, and quietly returning the right number would hide a
     * plan that is being built against stale geography.
     */
    public double minutesBetween(GeoPoint from, GeoPoint to) {
        return minutes[require(from)][require(to)];
    }

    private int require(GeoPoint point) {
        Integer index = indexByPoint.get(point);
        if (index == null) {
            throw new IllegalArgumentException("matrix does not cover " + point);
        }
        return index;
    }

    private static List<GeoPoint> canonicalise(List<GeoPoint> requested) {
        return requested.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparingDouble(GeoPoint::lat)
                        .thenComparingDouble(GeoPoint::lng))
                .toList();
    }

    /**
     * One leg of the matrix, in minutes. Kept separate from {@link TravelTimeProvider} so a
     * matrix can be built from any pairwise function, which is what lets the tests fill one
     * with known values instead of geography.
     */
    @FunctionalInterface
    public interface LegTime {
        double minutesBetween(GeoPoint from, GeoPoint to);
    }
}
