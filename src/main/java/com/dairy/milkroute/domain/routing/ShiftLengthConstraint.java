package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.domain.geo.TravelTimeProvider;
import com.dairy.milkroute.entity.Tanker;

/**
 * Is this a reasonable working day?
 *
 * <p>Unlike spoilage, this one counts the outbound leg. The tanker being empty means no
 * milk is ageing; it does not mean the driver is not driving. Shift length is the only
 * place the plant to first village leg is charged, and reading these two constraints side
 * by side is the clearest statement of why hot time is shaped the way it is.
 */
public final class ShiftLengthConstraint implements RouteConstraint {

    private final TravelTimeProvider travel;
    private final SpoilageConstraint spoilageConstraint;

    public ShiftLengthConstraint(TravelTimeProvider travel,
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

        double shift = outbound + spoilageConstraint.hotMinutes(route, ctx);
        double limit = ctx.driverMaxShiftMin();

        if (shift > limit) {
            return ConstraintResult.fail(name(), shift, limit,
                    "%.0f min shift against a %.0f min limit (%.0f min of that is the empty run out)"
                            .formatted(shift, limit, outbound));
        }
        return ConstraintResult.pass(name(), shift, limit);
    }

    @Override
    public String name() {
        return "SHIFT_LENGTH";
    }
}