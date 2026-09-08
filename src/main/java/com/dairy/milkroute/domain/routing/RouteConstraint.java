package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.entity.Tanker;

/**
 * One rule a route must satisfy.
 *
 * <p>Four small implementations rather than one method with four branches, for three
 * reasons. Each is testable without the others, so a spoilage test needs no plant hours
 * and no driver. A rejection names the rule that caused it, which is what lets the
 * feasibility report explain itself. And a fifth rule is a new class rather than an edit
 * to the solver.
 */
public interface RouteConstraint {

    ConstraintResult check(PartialRoute route, Tanker tanker, PlanningContext context);

    String name();
}