package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.domain.spoilage.SpoilageCalculator;
import com.dairy.milkroute.entity.Tanker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pairs routes with tankers: the route closest to the wire gets the tanker that holds milk
 * longest.
 *
 * <p>Sort routes by hot time descending, tankers by hold budget descending, and zip. The
 * whole idea fits in one line, and it is worth more than it looks. Insulation is worth
 * about six degrees, which on a hot evening is sixty-odd minutes of budget; spending that
 * on the route that already has the most slack, while the three-hour route runs on a plain
 * tanker, is how a plan that looked fine on paper loses a load.
 *
 * <p>With a uniform fleet the only axis that varies is insulation, so this reduces to
 * "the insulated tankers go to the longest routes" — which is exactly the decision worth
 * making.
 *
 * <p>Every pairing is re-checked against the constraints before it is accepted. That is not
 * belt and braces: the merge loop only ever established that <em>some</em> tanker in the
 * fleet could run a route, and the one it is finally given may be a different and weaker
 * one. Assigning without re-checking silently produces routes with negative slack.
 *
 * <p>Routes left over — because the tankers ran out, or because no tanker that remained
 * could run them — are returned unassigned rather than dropped. A village that cannot be run
 * today is a fact the caller has to record, not one the assigner should quietly absorb.
 */
public final class TankerAssigner {

    private final SpoilageConstraint spoilageConstraint;
    private final SpoilageCalculator spoilage;
    private final ConstraintChecker checker;

    public TankerAssigner(SpoilageConstraint spoilageConstraint,
                          SpoilageCalculator spoilage,
                          ConstraintChecker checker) {
        this.spoilageConstraint = spoilageConstraint;
        this.spoilage = spoilage;
        this.checker = checker;
    }

    /**
     * @param routes candidate routes, in any order
     * @param ctx    the run being planned
     */
    public Assignment assign(List<PartialRoute> routes, PlanningContext ctx) {
        List<PartialRoute> byRisk = routes.stream()
                .sorted(Comparator.comparingDouble(
                        (PartialRoute route) -> spoilageConstraint.hotMinutes(route, ctx))
                        .reversed())
                .toList();

        List<Tanker> byBudget = ctx.availableTankers().stream()
                .sorted(Comparator.comparingInt(
                                (Tanker tanker) -> budgetOf(tanker, ctx))
                        .reversed()
                        .thenComparing(Comparator.comparingInt(Tanker::getCapacityLitres).reversed()))
                .toList();

        List<AssignedRoute> assigned = new ArrayList<>();
        List<PartialRoute> unassigned = new ArrayList<>();

        // Walked with two independent cursors. A route that cannot use the tanker it is
        // offered does not consume it: the biggest budget on the yard is offered to the
        // riskiest route first, and if even that will not do, no smaller tanker will either,
        // so the route is dropped and the tanker moves on to the next one down the list.
        int nextTanker = 0;

        for (PartialRoute route : byRisk) {
            if (nextTanker >= byBudget.size()) {
                unassigned.add(route);
                continue;
            }

            Tanker tanker = byBudget.get(nextTanker);

            // The merge loop only established that *some* tanker could run this route. The
            // one it actually gets is decided here, and it may not be that one — there are
            // six insulated tankers and there can be more than six risky routes. Pairing
            // without re-checking produces a plan whose slack is negative: a route booked to
            // arrive after its own milk has spoiled, which is the exact failure this system
            // exists to prevent.
            if (!checker.isFeasible(route, tanker, ctx)) {
                unassigned.add(route);
                continue;
            }

            double hot = spoilageConstraint.hotMinutes(route, ctx);
            int budget = budgetOf(tanker, ctx);

            assigned.add(new AssignedRoute(
                    // R-01 is the riskiest route, so the ops board reads top down.
                    "R-%02d".formatted(assigned.size() + 1),
                    route,
                    tanker,
                    hot,
                    budget,
                    budget - hot,
                    route.totalLitres()));
            nextTanker++;
        }

        return new Assignment(List.copyOf(assigned), List.copyOf(unassigned));
    }

    private int budgetOf(Tanker tanker, PlanningContext ctx) {
        return spoilage.holdBudgetMinutes(ctx.ambientTempC(), tanker.isInsulated());
    }

    /**
     * What the assigner produced.
     *
     * @param assigned   routes with a tanker, riskiest first
     * @param unassigned routes the fleet could not cover
     */
    public record Assignment(List<AssignedRoute> assigned, List<PartialRoute> unassigned) {

        public Assignment {
            assigned = List.copyOf(assigned);
            unassigned = List.copyOf(unassigned);
        }
    }
}