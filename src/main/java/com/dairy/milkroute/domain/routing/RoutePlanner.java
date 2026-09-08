package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.domain.geo.GeoPoint;
import com.dairy.milkroute.domain.geo.TravelTimeProvider;
import com.dairy.milkroute.domain.spoilage.SpoilageCalculator;
import com.dairy.milkroute.entity.Tanker;
import com.dairy.milkroute.enums.PlanMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Phase two, full-service mode: combine village blocks into as few routes as the
 * constraints allow.
 *
 * <p>Clarke-Wright savings, over villages rather than points. Start with one route per
 * village — correct but absurd, sixty tankers for sixty villages — then repeatedly merge
 * the pair that saves the most, keeping any merge all four constraints accept. Savings is
 * the right family of algorithm here because it is greedy on exactly the quantity the
 * dairy cares about and it degrades gracefully: stop it early and you still have a valid
 * plan, just a more expensive one.
 *
 * <p><strong>The sequence is optimised before the constraints are checked.</strong> That
 * ordering is not an efficiency detail, it is a correctness one. Concatenating two routes
 * produces an arbitrary village order, and an arbitrary order can spend an hour of hot time
 * that a farthest-first order would not. Checking the concatenation as-built would reject
 * merges that are perfectly feasible once ordered properly, and the plan would come out
 * needing more tankers than the dairy actually needs.
 *
 * <p>This strategy assumes every point will be served. When the feasibility assessor says
 * that is impossible, the caller should be using the coverage strategy instead — this one
 * will simply fail to place some routes and report them, which is honest but not a plan.
 */
public final class RoutePlanner implements PlanningStrategy {

    private final TravelTimeProvider travel;
    private final SequenceOptimiser optimiser;
    private final ConstraintChecker checker;
    private final TankerAssigner assigner;
    private final SpoilageCalculator spoilage;
    private final FeasibilityAssessor feasibilityAssessor;

    public RoutePlanner(TravelTimeProvider travel,
                        SequenceOptimiser optimiser,
                        ConstraintChecker checker,
                        TankerAssigner assigner,
                        SpoilageCalculator spoilage,
                        FeasibilityAssessor feasibilityAssessor) {
        this.travel = travel;
        this.optimiser = optimiser;
        this.checker = checker;
        this.assigner = assigner;
        this.spoilage = spoilage;
        this.feasibilityAssessor = feasibilityAssessor;
    }

    @Override
    public PlanMode mode() {
        return PlanMode.FULL_SERVICE;
    }

    @Override
    public PlanResult plan(List<VillageBlock> blocks, PlanningContext ctx) {
        long startedAt = System.nanoTime();

        FeasibilityReport feasibility = feasibilityAssessor.assess(blocks, ctx);

        // One route per village: the trivially valid starting point every merge improves on.
        List<PartialRoute> routes = new ArrayList<>(blocks.stream()
                .map(PartialRoute::of)
                .toList());

        for (Saving saving : savings(blocks, ctx)) {
            PartialRoute endingWithI = routeEndingWith(routes, saving.from());
            PartialRoute startingWithJ = routeStartingWith(routes, saving.to());

            // Both ends must still be exposed, and they must belong to different routes:
            // merging a route into itself would close it into a loop.
            if (endingWithI == null || startingWithJ == null || endingWithI == startingWithJ) {
                continue;
            }

            PartialRoute merged = optimiser.optimise(
                    PartialRoute.concat(endingWithI, startingWithJ), ctx);

            if (leastCapableTankerThatCanRun(merged, ctx) == null) {
                continue;
            }

            routes.remove(endingWithI);
            routes.remove(startingWithJ);
            routes.add(merged);
        }

        TankerAssigner.Assignment assignment = assigner.assign(routes, ctx);

        return new PlanResult(
                mode(),
                assignment.assigned(),
                assignment.unassigned(),
                feasibility,
                (System.nanoTime() - startedAt) / 1_000_000);
    }

    /**
     * Every ordered pair of villages, scored by what merging them saves.
     *
     * <p>Merging drops one run home from {@code from} and one run out to {@code to},
     * replacing both with a single hop between them. Ordered rather than unordered pairs,
     * because the hop is measured exit-to-entry and a village is not symmetric: entering
     * at the near side and leaving at the far side is a different journey from the reverse.
     */
    private List<Saving> savings(List<VillageBlock> blocks, PlanningContext ctx) {
        GeoPoint plant = ctx.plantLocation();
        List<Saving> savings = new ArrayList<>(blocks.size() * (blocks.size() - 1));

        for (VillageBlock from : blocks) {
            for (VillageBlock to : blocks) {
                if (from.equals(to)) {
                    continue;
                }
                double value = legMinutes(plant, from.entryLocation(), ctx)
                        + legMinutes(plant, to.entryLocation(), ctx)
                        - legMinutes(from.exitLocation(), to.entryLocation(), ctx);

                savings.add(new Saving(from, to, value));
            }
        }

        savings.sort(Comparator.comparingDouble(Saving::minutes).reversed());
        return savings;
    }

    /**
     * The least capable tanker that can still run this route, or null if none can.
     *
     * <p>Scanning upward rather than downward is deliberate. Testing against the best
     * tanker in the yard would approve merges that only the insulated ones can run, and
     * there are six of those; the plan would then look feasible and fall apart at
     * assignment. Taking the weakest tanker that passes keeps the insulated capacity in
     * reserve for the routes that genuinely need it.
     */
    private Tanker leastCapableTankerThatCanRun(PartialRoute route, PlanningContext ctx) {
        return ctx.availableTankers().stream()
                .sorted(Comparator.comparingInt(
                                (Tanker tanker) -> spoilage.holdBudgetMinutes(
                                        ctx.ambientTempC(), tanker.isInsulated()))
                        .thenComparingInt(Tanker::getCapacityLitres))
                .filter(tanker -> checker.isFeasible(route, tanker, ctx))
                .findFirst()
                .orElse(null);
    }

    private PartialRoute routeEndingWith(List<PartialRoute> routes, VillageBlock block) {
        return routes.stream()
                .filter(route -> route.lastBlock().equals(block))
                .findFirst()
                .orElse(null);
    }

    private PartialRoute routeStartingWith(List<PartialRoute> routes, VillageBlock block) {
        return routes.stream()
                .filter(route -> route.firstBlock().equals(block))
                .findFirst()
                .orElse(null);
    }

    private double legMinutes(GeoPoint from, GeoPoint to, PlanningContext ctx) {
        return SpoilageConstraint.minutes(travel.between(from, to, ctx.session()));
    }

    /** What merging {@code from} into {@code to} would save, in minutes. */
    private record Saving(VillageBlock from, VillageBlock to, double minutes) {
    }
}