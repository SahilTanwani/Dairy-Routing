package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.entity.Tanker;
import java.util.List;

/**
 * Runs every constraint against a candidate route and reports the first that fails.
 *
 * <p>Short-circuits deliberately. The savings loop calls this tens of thousands of times
 * and only ever asks "can this merge happen"; evaluating three more constraints after one
 * has already said no would buy nothing. When the feasibility report needs the full
 * picture, {@link #checkAll} gives it.
 *
 * <p>Order is cheapest first: capacity is arithmetic over blocks the route already holds,
 * while the other three walk the route and consult the travel model. Spoilage goes next
 * because it is the constraint that actually binds here.
 */
public final class ConstraintChecker {

    private final List<RouteConstraint> constraints;

    public ConstraintChecker(List<RouteConstraint> constraints) {
        if (constraints.isEmpty()) {
            throw new IllegalArgumentException(
                    "a checker with no constraints accepts everything");
        }
        this.constraints = List.copyOf(constraints);
    }

    /** The first failure, or a pass if every constraint is satisfied. */
    public ConstraintResult check(PartialRoute route, Tanker tanker, PlanningContext ctx) {
        for (RouteConstraint constraint : constraints) {
            ConstraintResult result = constraint.check(route, tanker, ctx);
            if (!result.passed()) {
                return result;
            }
        }
        return ConstraintResult.pass("ALL", 0, 0);
    }

    public boolean isFeasible(PartialRoute route, Tanker tanker, PlanningContext ctx) {
        return check(route, tanker, ctx).passed();
    }

    /**
     * Every constraint evaluated, pass or fail.
     *
     * <p>For the feasibility report, where "fails spoilage by 14 minutes but has 40
     * minutes of shift to spare" is more useful than the first no.
     */
    public List<ConstraintResult> checkAll(
            PartialRoute route, Tanker tanker, PlanningContext ctx) {
        return constraints.stream()
                .map(constraint -> constraint.check(route, tanker, ctx))
                .toList();
    }
}