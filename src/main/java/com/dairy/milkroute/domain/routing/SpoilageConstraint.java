package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.domain.geo.TravelTimeProvider;
import com.dairy.milkroute.domain.spoilage.SpoilageCalculator;
import com.dairy.milkroute.entity.Tanker;
import java.time.Duration;

/**
 * Will the milk still be good when this route reaches the plant?
 *
 * <p>Hot time is how long the oldest milk has been aboard on arrival:
 *
 * <pre>
 *   time working inside each village
 * + the hops between villages
 * + the run from the last village back to the plant
 * + the wait to unload
 * </pre>
 *
 * <p>Note what is absent: the plant to first village leg. The tanker is empty on the way
 * out, so no milk is ageing and the clock has not started. That omission is the whole
 * reason a route should drive out to its farthest village and collect on the way home —
 * same distance driven, but the oldest milk spends far less time aboard. The sequence
 * optimiser exploits it; this class is where it is defined.
 */
public final class SpoilageConstraint implements RouteConstraint {

    private final TravelTimeProvider travel;
    private final SpoilageCalculator spoilage;
    private final double safetyBufferMinutes;

    public SpoilageConstraint(TravelTimeProvider travel,
                              SpoilageCalculator spoilage,
                              double safetyBufferMinutes) {
        this.travel = travel;
        this.spoilage = spoilage;
        this.safetyBufferMinutes = safetyBufferMinutes;
    }

    @Override
    public ConstraintResult check(PartialRoute route, Tanker tanker, PlanningContext ctx) {
        double hot = hotMinutes(route, ctx);
        int budget = spoilage.holdBudgetMinutes(ctx.ambientTempC(), tanker.isInsulated());
        double usable = budget - safetyBufferMinutes;

        if (hot > usable) {
            return ConstraintResult.fail(name(), hot, usable,
                    "%.0f min of hot time against a %d min budget at %.1f C (%.0f min usable after the safety buffer)"
                            .formatted(hot, budget, ctx.ambientTempC(), usable));
        }
        return ConstraintResult.pass(name(), hot, usable);
    }

    /**
     * Minutes the oldest milk spends aboard, from first collection to unload.
     *
     * <p>Public so the planner can report a route's hot time and slack without repeating
     * the calculation, and so a test can assert that the outbound leg is excluded.
     */
    public double hotMinutes(PartialRoute route, PlanningContext ctx) {
        var blocks = route.blocks();
        double hot = 0;

        for (int i = 0; i < blocks.size(); i++) {
            hot += blocks.get(i).internalMinutes();

            if (i < blocks.size() - 1) {
                hot += minutes(travel.between(
                        blocks.get(i).exitLocation(),
                        blocks.get(i + 1).entryLocation(),
                        ctx.session()));
            }
        }

        hot += minutes(travel.between(
                route.lastBlock().exitLocation(),
                ctx.plantLocation(),
                ctx.session()));

        hot += ctx.plant().getUnloadMinutes();

        return hot;
    }

    /**
     * A duration in fractional minutes.
     *
     * <p>Duration.toMinutes() truncates, and a route has a dozen legs. Losing half a
     * minute on each is enough to accept a merge that should have been rejected.
     */
    static double minutes(Duration duration) {
        return duration.toSeconds() / 60.0;
    }

    @Override
    public String name() {
        return "SPOILAGE";
    }
}