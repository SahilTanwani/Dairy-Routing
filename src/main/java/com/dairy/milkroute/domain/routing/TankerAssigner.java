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
 * <p>Routes left over when the tankers run out are returned unassigned rather than dropped.
 * A village that cannot be run today is a fact the caller has to record, not one the
 * assigner should quietly absorb.
 */
public final class TankerAssigner {

    private final SpoilageConstraint spoilageConstraint;
    private final SpoilageCalculator spoilage;

    public TankerAssigner(SpoilageConstraint spoilageConstraint, SpoilageCalculator spoilage) {
        this.spoilageConstraint = spoilageConstraint;
        this.spoilage = spoilage;
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

        for (int i = 0; i < byRisk.size(); i++) {
            PartialRoute route = byRisk.get(i);

            if (i >= byBudget.size()) {
                unassigned.add(route);
                continue;
            }

            Tanker tanker = byBudget.get(i);
            double hot = spoilageConstraint.hotMinutes(route, ctx);
            int budget = budgetOf(tanker, ctx);

            assigned.add(new AssignedRoute(
                    // R-01 is the riskiest route, so the ops board reads top down.
                    "R-%02d".formatted(i + 1),
                    route,
                    tanker,
                    hot,
                    budget,
                    budget - hot,
                    route.totalLitres()));
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