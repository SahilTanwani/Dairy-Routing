package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.entity.Tanker;

/**
 * Does the milk fit?
 *
 * <p>Rarely binds here. Five tankers would hold the whole morning's volume; the dairy runs
 * twenty-two because the district is spread out and the spoilage window is short. The
 * constraint is time, not capacity — which is why the optimiser is built around hot time
 * rather than load or distance. It is still checked on every merge, because a plan that
 * silently overfills a tanker is worse than one that admits it cannot.
 */
public final class CapacityConstraint implements RouteConstraint {

    private final double headroom;

    /**
     * @param headroom fraction of nominal capacity a plan may use. Daily volume varies by
     *                 roughly a sixth either way, so a route planned to fill a tanker
     *                 exactly overflows on a good milking day.
     */
    public CapacityConstraint(double headroom) {
        this.headroom = headroom;
    }

    @Override
    public ConstraintResult check(PartialRoute route, Tanker tanker, PlanningContext ctx) {
        double litres = route.totalLitres();
        double usable = tanker.getCapacityLitres() * headroom;

        if (litres > usable) {
            return ConstraintResult.fail(name(), litres, usable,
                    "%.0f L against %d L capacity (%.0f L usable at %.0f%% headroom)"
                            .formatted(litres, tanker.getCapacityLitres(), usable, headroom * 100));
        }
        return ConstraintResult.pass(name(), litres, usable);
    }

    @Override
    public String name() {
        return "CAPACITY";
    }
}