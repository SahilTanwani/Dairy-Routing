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
 * <p>Clarke-Wright in shape, over villages rather than points. Start with one route per
 * village — correct but absurd, sixty tankers for sixty villages — then work down a ranked
 * list of merges, keeping any that all four constraints accept. Greedy merging is the right
 * family here because it degrades gracefully: stop it early, or run out of budget partway,
 * and what you have is still a valid plan rather than a broken one.
 *
 * <p>The ranking is not plain savings. See {@link #candidates}: it is efficiency weighted by
 * how long each village has gone unserved, with villages at the skip limit jumping the queue
 * outright. That is what stops a hot day quietly serving the same efficient villages and
 * abandoning the far end of the corridor week after week.
 *
 * <p><strong>The sequence is optimised before the constraints are checked.</strong> That
 * ordering is not an efficiency detail, it is a correctness one. Concatenating two routes
 * produces an arbitrary village order, and an arbitrary order can spend an hour of hot time
 * that a farthest-first order would not. Checking the concatenation as-built would reject
 * merges that are perfectly feasible once ordered properly, and the plan would come out
 * needing more tankers than the dairy actually needs.
 *
 * <p>There is no separate coverage planner. On a day the fleet cannot serve everyone this
 * same loop runs, the ranking decides who gets served first, and whatever the fleet cannot
 * crew is returned in {@code unassignedRoutes} with the plan marked
 * COVERAGE_OPTIMISATION. Degrading honestly is the behaviour; a second algorithm for it
 * would have been a second thing to get wrong.
 *
 * <p>Deliberately not built: partial fill, where a route takes some points of a village and
 * leaves the rest, and ejection chains, where an accepted merge is undone to make room for a
 * better one. Both raise coverage a few points and both add a class of bug — a village
 * silently half-served, a route left corrupt by an abandoned ejection — that is worse than
 * the coverage they buy.
 */
public final class RoutePlanner implements PlanningStrategy {

    /**
     * Floor on marginal hot time when computing efficiency. A village that costs almost
     * nothing extra would otherwise divide by nearly zero and dominate the ranking on a
     * rounding artefact.
     */
    private static final double MIN_MARGINAL_MINUTES = 0.5;

    private final TravelTimeProvider travel;
    private final SequenceOptimiser optimiser;
    private final ConstraintChecker checker;
    private final TankerAssigner assigner;
    private final SpoilageCalculator spoilage;
    private final FeasibilityAssessor feasibilityAssessor;
    private final double equityExponent;
    private final int maxConsecutiveSkips;

    /**
     * @param equityExponent      how sharply neglect raises a village's priority; from
     *                            solver_parameter, 1.6 as seeded
     * @param maxConsecutiveSkips skips after which a village stops competing and is merged
     *                            first regardless of its score
     */
    public RoutePlanner(TravelTimeProvider travel,
                        SequenceOptimiser optimiser,
                        ConstraintChecker checker,
                        TankerAssigner assigner,
                        SpoilageCalculator spoilage,
                        FeasibilityAssessor feasibilityAssessor,
                        double equityExponent,
                        int maxConsecutiveSkips) {
        this.travel = travel;
        this.optimiser = optimiser;
        this.checker = checker;
        this.assigner = assigner;
        this.spoilage = spoilage;
        this.feasibilityAssessor = feasibilityAssessor;
        this.equityExponent = equityExponent;
        this.maxConsecutiveSkips = maxConsecutiveSkips;
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

        for (Candidate candidate : candidates(blocks, ctx)) {
            PartialRoute endingWithI = routeEndingWith(routes, candidate.from());
            PartialRoute startingWithJ = routeStartingWith(routes, candidate.to());

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
                // The mode on the plan is what the arithmetic said, not what this strategy
                // is called. On a day it cannot serve everyone, this planner still runs and
                // degrades honestly, and the plan has to say so.
                feasibility.mode(),
                assignment.assigned(),
                assignment.unassigned(),
                feasibility,
                (System.nanoTime() - startedAt) / 1_000_000);
    }

    /**
     * Every ordered pair of villages, ranked by which village most deserves to go next.
     *
     * <p>Plain Clarke-Wright would rank these by minutes saved, which maximises litres per
     * tanker-minute and, on a day the fleet cannot serve everyone, quietly serves the same
     * efficient villages every time. The far end of the corridor is always the least
     * efficient choice, so it is always the one dropped, and a cooperative that stops
     * collecting from the same eight villages every hot week does not stay a cooperative.
     *
     * <p>So the ranking carries an equity term:
     *
     * <pre>
     *   litres / marginalHotMinutes  x  (1 + daysSinceLastServed) ^ equityExponent
     * </pre>
     *
     * <p>The first factor is still efficiency, and on a normal day it decides almost
     * everything, because every village has been served recently and the second factor is
     * near 1. The second factor is what makes a village that has been missed for three days
     * outrank a marginally better one served yesterday. At an exponent of 1.6, three days of
     * neglect multiplies a village's claim by about 7.5, which is enough to overcome a real
     * efficiency gap rather than merely nudging the order.
     *
     * <p>Two cases sit outside the score. A village at the skip limit is merged before
     * anything is ranked at all, because fairness there is a rule rather than a preference.
     * A village with no history is treated as never served and ordered ahead of everything
     * that has one, with efficiency deciding between them — which on a freshly seeded dairy
     * is every village, so the first plan is ordered purely on efficiency.
     */
    private List<Candidate> candidates(List<VillageBlock> blocks, PlanningContext ctx) {
        List<Candidate> candidates = new ArrayList<>(blocks.size() * (blocks.size() - 1));

        for (VillageBlock from : blocks) {
            for (VillageBlock to : blocks) {
                if (from.equals(to)) {
                    continue;
                }
                candidates.add(candidate(from, to, ctx));
            }
        }

        candidates.sort(Comparator
                .comparing(Candidate::mandatory).reversed()
                .thenComparing(Comparator.comparing(Candidate::neverServed).reversed())
                .thenComparing(Comparator.comparingDouble(Candidate::score).reversed()));

        return candidates;
    }

    private Candidate candidate(VillageBlock from, VillageBlock to, PlanningContext ctx) {
        double efficiency = to.litres() / Math.max(marginalHotMinutes(from, to, ctx), MIN_MARGINAL_MINUTES);

        int worstSkips = 0;
        long worstDays = 0;
        boolean neverServed = false;

        // A village is as neglected as its most neglected point. Averaging would let a
        // village with one long-ignored point look fine because its neighbours are fresh.
        for (var point : to.sequence()) {
            PointCoverage coverage = ctx.coverageFor(point.getId());
            if (coverage == null || !coverage.everServed()) {
                neverServed = true;
                worstSkips = Math.max(worstSkips, coverage == null ? 0 : coverage.consecutiveSkips());
                continue;
            }
            worstSkips = Math.max(worstSkips, coverage.consecutiveSkips());
            worstDays = Math.max(worstDays, coverage.daysSinceLastServed());
        }

        double equity = Math.pow(1.0 + worstDays, equityExponent);

        return new Candidate(
                from,
                to,
                worstSkips >= maxConsecutiveSkips,
                neverServed,
                efficiency * equity);
    }

    /**
     * The extra on-milk time appending {@code to} costs a route that currently ends at
     * {@code from}: its own internal work, the hop to reach it, and the longer or shorter
     * run home that results.
     *
     * <p>Hot minutes rather than distance, because hot time is the budget that actually runs
     * out. A village that is further away but on the way home can be cheaper than a nearer
     * one that adds a detour.
     */
    private double marginalHotMinutes(VillageBlock from, VillageBlock to, PlanningContext ctx) {
        GeoPoint plant = ctx.plantLocation();

        return to.internalMinutes()
                + legMinutes(from.exitLocation(), to.entryLocation(), ctx)
                + legMinutes(to.exitLocation(), plant, ctx)
                - legMinutes(from.exitLocation(), plant, ctx);
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

    /**
     * One possible next merge: append {@code to} to the route ending at {@code from}.
     *
     * @param mandatory    the village has hit the skip limit and jumps the queue entirely
     * @param neverServed  no service history, which outranks any amount of recorded neglect
     * @param score        efficiency times equity, the ranking within a tier
     */
    private record Candidate(VillageBlock from, VillageBlock to,
                             boolean mandatory, boolean neverServed, double score) {
    }
}