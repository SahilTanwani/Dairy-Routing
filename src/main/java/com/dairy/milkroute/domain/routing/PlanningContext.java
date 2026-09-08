package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.domain.geo.GeoPoint;
import com.dairy.milkroute.entity.Driver;
import com.dairy.milkroute.entity.Plant;
import com.dairy.milkroute.entity.Tanker;
import com.dairy.milkroute.enums.Session;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything a planning run is fixed against: which session, how hot it is, where the milk
 * is going, and what there is to move it with.
 *
 * <p>Snapshotted at the start of a run and never reread. A plan that consulted the fleet
 * table halfway through could have a tanker go into maintenance between deciding route
 * seven and route eight, and would produce a plan that was never valid as a whole. Passing
 * the context by value means every constraint decision in a run is made against the same
 * world.
 *
 * <p>The ambient temperature is a field rather than a lookup for the same reason, and for
 * one more: the timing advisory replans the identical dairy at a different temperature by
 * building a second context. That only works if temperature is an input here rather than
 * something the planner fetches for itself.
 *
 * <p>Note what is deliberately absent: the travel model, the spoilage calculator and the
 * solver parameters. Those are collaborators the constraint checker and the planner are
 * given directly, not state the context carries around.
 *
 * @param session            which of the two daily collections this plan is for
 * @param businessDate       the date being planned, which the equity term scores against
 * @param ambientTempC       the temperature the whole plan is built against
 * @param plant              where the milk is delivered and the spoilage clock stops
 * @param plannedDepartAt    when tankers leave the plant, and therefore the origin of every
 *                           arrival time the plant window is checked against
 * @param availableTankers   the fleet as it stood when the run began
 * @param availableDrivers   the drivers available, which caps how many routes are useful
 * @param driverMaxShiftMin  the shift length a route must fit inside
 * @param coverageByPointId  service history per collection point, which the merge ordering
 *                           uses to favour neglected villages. Empty is legitimate: a dairy
 *                           with no history treats every point as never served.
 */
public record PlanningContext(
        Session session,
        LocalDate businessDate,
        double ambientTempC,
        Plant plant,
        LocalTime plannedDepartAt,
        List<Tanker> availableTankers,
        List<Driver> availableDrivers,
        int driverMaxShiftMin,
        Map<Long, PointCoverage> coverageByPointId) {

    /**
     * A run with no coverage history, which is what a freshly seeded dairy has and what
     * most tests want.
     */
    public PlanningContext(Session session,
                           LocalDate businessDate,
                           double ambientTempC,
                           Plant plant,
                           LocalTime plannedDepartAt,
                           List<Tanker> availableTankers,
                           List<Driver> availableDrivers,
                           int driverMaxShiftMin) {
        this(session, businessDate, ambientTempC, plant, plannedDepartAt,
                availableTankers, availableDrivers, driverMaxShiftMin, Map.of());
    }

    public PlanningContext {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(plant, "plant");
        Objects.requireNonNull(plannedDepartAt, "plannedDepartAt");
        availableTankers = List.copyOf(availableTankers);
        availableDrivers = List.copyOf(availableDrivers);
        coverageByPointId = Map.copyOf(coverageByPointId);

        if (driverMaxShiftMin <= 0) {
            throw new IllegalArgumentException(
                    "driverMaxShiftMin must be positive, was " + driverMaxShiftMin);
        }
    }

    /**
     * The plant as a {@link GeoPoint}.
     *
     * <p>The entity stores coordinates as BigDecimal, which is right for a column and wrong
     * for arithmetic that runs a million times inside a savings loop. Converting once here
     * keeps that conversion out of the loop and out of every caller.
     */
    public GeoPoint plantLocation() {
        return new GeoPoint(plant.getLat().doubleValue(), plant.getLng().doubleValue());
    }

    /**
     * Service history for one point, or null when there is none.
     *
     * <p>Guards the null id deliberately: an unsaved point has no id, and the immutable map
     * this reads from throws on a null key rather than returning nothing.
     */
    public PointCoverage coverageFor(Long collectionPointId) {
        return collectionPointId == null ? null : coverageByPointId.get(collectionPointId);
    }

    /** How many tankers there are to plan with. Never a hardcoded fleet size. */
    public int fleetSize() {
        return availableTankers.size();
    }
}