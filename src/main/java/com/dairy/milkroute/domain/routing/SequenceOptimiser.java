package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.domain.geo.GeoPoint;
import com.dairy.milkroute.domain.geo.TravelTimeProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reorders the villages on a route to reduce hot time.
 *
 * <p>{@link SpoilageConstraint} measures how long the oldest milk is aboard. This class
 * reduces it, and the two together are where the free deadhead pays off: because the run
 * out to the first village carries no milk, a tanker should drive out empty to its
 * farthest village and collect on the way home. Same distance driven, materially less
 * time on milk.
 *
 * <p>Two steps. The sequence is seeded farthest-first, which is near-optimal for this
 * objective whenever the villages sit roughly radially around the plant. Then 2-opt
 * cleans up the cases where they do not — two villages off to one side that are closer to
 * each other than either is to the plant.
 *
 * <p>The objective is hot time, not distance. That single substitution is what makes this
 * a spoilage optimiser rather than a travelling-salesman solver: distance charges every
 * leg equally, while hot time charges nothing for the empty run out, so an ordering that
 * drives slightly further can still deliver fresher milk.
 */
public final class SequenceOptimiser {

    /** 2-opt on a dozen villages converges long before this; the cap is a guard, not a budget. */
    private static final int MAX_PASSES = 50;

    /** Improvements smaller than this are floating-point noise, not better routes. */
    private static final double MIN_GAIN_MINUTES = 0.01;

    private final TravelTimeProvider travel;
    private final SpoilageConstraint spoilage;

    public SequenceOptimiser(TravelTimeProvider travel, SpoilageConstraint spoilage) {
        this.travel = travel;
        this.spoilage = spoilage;
    }

    /**
     * The given route's villages in the order that minimises hot time.
     *
     * <p>Returns a new route; the original is untouched, because the savings loop holds
     * candidates it may still discard.
     */
    public PartialRoute optimise(PartialRoute route, PlanningContext ctx) {
        if (route.villageCount() < 2) {
            return route;
        }

        List<VillageBlock> best = farthestFirst(route.blocks(), ctx);
        double bestHot = hotMinutes(best, ctx);

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean improved = false;

            for (int i = 0; i < best.size() - 1; i++) {
                for (int k = i + 1; k < best.size(); k++) {
                    List<VillageBlock> candidate = reverseSegment(best, i, k);
                    double hot = hotMinutes(candidate, ctx);

                    if (hot < bestHot - MIN_GAIN_MINUTES) {
                        best = candidate;
                        bestHot = hot;
                        improved = true;
                    }
                }
            }

            if (!improved) {
                break;
            }
        }

        return route.resequenced(best);
    }

    /**
     * Villages ordered by distance from the plant, farthest first.
     *
     * <p>The seed, not the answer. It is near-optimal when villages sit radially around
     * the plant, which is the usual case, and it gives 2-opt a good starting point in the
     * cases where they do not.
     */
    private List<VillageBlock> farthestFirst(List<VillageBlock> blocks, PlanningContext ctx) {
        GeoPoint plant = ctx.plantLocation();

        return blocks.stream()
                .sorted(Comparator.comparingDouble(
                                (VillageBlock block) -> SpoilageConstraint.minutes(
                                        travel.between(plant, block.entryLocation(), ctx.session())))
                        .reversed())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /**
     * The 2-opt move: reverse the run of villages between {@code i} and {@code k}.
     *
     * <p>Reversing a segment is the one rearrangement that keeps the rest of the sequence
     * intact while changing which villages are adjacent, which is why it explores useful
     * orderings rather than arbitrary ones.
     */
    private static List<VillageBlock> reverseSegment(List<VillageBlock> blocks, int i, int k) {
        List<VillageBlock> out = new ArrayList<>(blocks);
        for (int left = i, right = k; left < right; left++, right--) {
            VillageBlock swap = out.get(left);
            out.set(left, out.get(right));
            out.set(right, swap);
        }
        return out;
    }

    private double hotMinutes(List<VillageBlock> blocks, PlanningContext ctx) {
        return spoilage.hotMinutes(new PartialRoute(blocks), ctx);
    }
}