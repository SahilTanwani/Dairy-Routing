package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.domain.geo.TravelTimeProvider;
import com.dairy.milkroute.entity.Tanker;
import java.time.LocalTime;

/**
 * Will the tanker arrive while the plant is open to receive?
 *
 * <p>A route that is fast enough and fits the tanker is still worthless if it reaches a
 * closed gate. Arrival is measured from the planned departure, so this is the one
 * constraint that depends on when the session starts rather than only on how long the
 * route takes — which is also why shifting the evening departure changes what is feasible.
 */
public final class PlantWindowConstraint implements RouteConstraint {

    private final TravelTimeProvider travel;
    private final SpoilageConstraint spoilageConstraint;

    public PlantWindowConstraint(TravelTimeProvider travel,
                                 SpoilageConstraint spoilageConstraint) {
        this.travel = travel;
        this.spoilageConstraint = spoilageConstraint;
    }

    @Override
    public ConstraintResult check(PartialRoute route, Tanker tanker, PlanningContext ctx) {
        double outbound = SpoilageConstraint.minutes(travel.between(
                ctx.plantLocation(),
                route.firstBlock().entryLocation(),
                ctx.session()));

        double total = outbound + spoilageConstraint.hotMinutes(route, ctx);
        LocalTime arrival = ctx.plannedDepartAt().plusMinutes((long) Math.ceil(total));

        LocalTime opens = ctx.plant().getOpensAt();
        LocalTime closes = ctx.plant().getClosesAt();

        if (arrival.isBefore(opens) || arrival.isAfter(closes)) {
            return ConstraintResult.fail(name(), total, total,
                    "arrives %s, plant open %s to %s".formatted(arrival, opens, closes));
        }
        return ConstraintResult.pass(name(), total, total);
    }

    @Override
    public String name() {
        return "PLANT_WINDOW";
    }
}