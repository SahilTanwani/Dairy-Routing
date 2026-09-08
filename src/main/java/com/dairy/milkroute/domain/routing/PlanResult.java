package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.enums.PlanMode;
import java.util.List;

/**
 * The output of a planning run: the routes, what was left out, and why.
 *
 * <p>{@code unassignedRoutes} is the honest half. A planner that returns only what it
 * managed to route lets a village disappear silently, and the first anyone hears about it
 * is a farmer with a full can and no tanker. Anything the run could not place stays in the
 * result so the caller has to deal with it.
 *
 * @param mode              which strategy produced this
 * @param routes            routes with a tanker, ordered riskiest first
 * @param unassignedRoutes  routes that no available tanker could run; their villages go
 *                          unserved unless the caller does something about it
 * @param feasibility       the required-versus-available arithmetic the mode came from
 * @param generationMs      wall time for the run, which the five-second target is measured
 *                          against
 */
public record PlanResult(
        PlanMode mode,
        List<AssignedRoute> routes,
        List<PartialRoute> unassignedRoutes,
        FeasibilityReport feasibility,
        long generationMs) {

    public PlanResult {
        routes = List.copyOf(routes);
        unassignedRoutes = List.copyOf(unassignedRoutes);
    }

    public int tankersUsed() {
        return routes.size();
    }

    public int stopsServed() {
        return routes.stream().mapToInt(AssignedRoute::stopCount).sum();
    }

    public int stopsUnserved() {
        return unassignedRoutes.stream().mapToInt(PartialRoute::stopCount).sum();
    }

    public double litresServed() {
        return routes.stream().mapToDouble(AssignedRoute::litres).sum();
    }

    public double litresForgone() {
        return unassignedRoutes.stream().mapToDouble(PartialRoute::totalLitres).sum();
    }

    /** Every village the run failed to place, for the exclusion records. */
    public List<VillageBlock> unservedBlocks() {
        return unassignedRoutes.stream().flatMap(route -> route.blocks().stream()).toList();
    }

    public double coverageFraction() {
        int total = stopsServed() + stopsUnserved();
        return total == 0 ? 1.0 : (double) stopsServed() / total;
    }

    /**
     * The thinnest margin on any route.
     *
     * <p>This is the plan's real quality measure, and it is why the objective is to raise
     * the floor rather than the average. Nineteen comfortable routes plus one at four
     * minutes is a worse plan than twenty at thirty minutes each, because the dairy loses
     * loads one route at a time, not on average.
     */
    public double minimumSlackMinutes() {
        return routes.stream()
                .mapToDouble(AssignedRoute::slackMinutes)
                .min()
                .orElse(Double.NaN);
    }

    public boolean servedEveryone() {
        return unassignedRoutes.isEmpty();
    }
}