package com.dairy.milkroute.domain.routing;

/**
 * How recently one collection point was actually served, as the planner sees it.
 *
 * <p>A plain snapshot of {@code point_coverage_state}, passed into the planning context so
 * that {@code domain/} never touches a repository. The planner reads it when ordering
 * merges: a point nobody has visited for three days is worth pulling onto a route even
 * when a closer one would collect more litres per minute.
 *
 * @param consecutiveSkips   sessions in a row this point has gone unserved
 * @param daysSinceLastServed days since the last visit; meaningless when never served
 * @param everServed         false for a point with no history at all, which the planner
 *                           treats as the most neglected case there is
 */
public record PointCoverage(int consecutiveSkips, long daysSinceLastServed, boolean everServed) {

    /**
     * A point with no state row. A new point, or one the dairy has never reached, has a
     * stronger claim than anything with a history, so it is kept as its own case rather
     * than being folded in as a very large number of days.
     */
    public static PointCoverage neverServed(int consecutiveSkips) {
        return new PointCoverage(consecutiveSkips, 0, false);
    }

    public static PointCoverage servedDaysAgo(int consecutiveSkips, long days) {
        return new PointCoverage(consecutiveSkips, Math.max(0, days), true);
    }
}
