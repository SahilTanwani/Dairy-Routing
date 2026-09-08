package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.entity.Tanker;

/**
 * A finished route: the villages, the tanker that will run them, and how close to the wire
 * it is.
 *
 * <p>{@code slackMinutes} is the field to look at. It is what the ops board sorts on and
 * what the feasibility report flags before publication, because a route with six minutes
 * to spare is not a route that works — it is a route that works until the first thing goes
 * wrong.
 *
 * @param label              this route's name within the plan, R-01 being the riskiest
 * @param route              the villages in visiting order
 * @param tanker             the tanker assigned to run it
 * @param hotMinutes         how old the oldest milk will be on arrival at the plant
 * @param holdBudgetMinutes  what this tanker's milk can survive at today's temperature
 * @param slackMinutes       budget minus hot time: the margin before milk is at risk
 * @param litres             expected volume
 */
public record AssignedRoute(
        String label,
        PartialRoute route,
        Tanker tanker,
        double hotMinutes,
        int holdBudgetMinutes,
        double slackMinutes,
        double litres) {

    public int stopCount() {
        return route.stopCount();
    }

    public int villageCount() {
        return route.villageCount();
    }

    /**
     * How much of the hold budget this route consumes.
     *
     * <p>The same quantity the spoilage monitor watches once the trip is running, so a
     * route that leaves the plant at 0.93 was already one bad junction from a CRITICAL.
     */
    public double budgetUsedFraction() {
        return holdBudgetMinutes == 0 ? 1.0 : hotMinutes / holdBudgetMinutes;
    }
}