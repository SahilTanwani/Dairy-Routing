package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.domain.geo.GeoPoint;
import com.dairy.milkroute.domain.geo.TravelTimeProvider;
import com.dairy.milkroute.entity.CollectionPoint;
import com.dairy.milkroute.entity.Village;
import com.dairy.milkroute.enums.Session;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Phase one: order the points inside a single village.
 *
 * <p>This is where the problem stops being intractable. Arranging 1,250 points at once is a
 * different kind of problem from arranging 60 blocks of twenty, and the split is legitimate
 * because the geography supports it — points inside a village are a minute or two apart
 * while villages are tens of minutes apart. Sixty small problems, milliseconds each, and
 * complexity then grows with village count rather than point count.
 *
 * <p>Nearest-neighbour for a starting order, then 2-opt to clean up the crossings it always
 * leaves. On twenty points that is exact enough that a better solver would buy nothing
 * measurable against a travel model carrying a circuity factor.
 *
 * <p>The objective here is plain path time, not hot time. Inside a village every minute is
 * spent on milk, so there is no free deadhead to exploit and the two objectives coincide.
 * The distinction only appears between villages, which is {@link SequenceOptimiser}'s job.
 */
public final class VillageSolver {

    /** 2-opt on twenty points converges in a handful of passes; this is a guard. */
    private static final int MAX_PASSES = 50;

    /** Improvements below this are floating-point noise, not better orderings. */
    private static final double MIN_GAIN_MINUTES = 0.01;

    private final TravelTimeProvider travel;

    public VillageSolver(TravelTimeProvider travel) {
        this.travel = travel;
    }

    /**
     * The village as one or more blocks.
     *
     * <p>Normally one. A village is split when working it alone would take longer than the
     * milk survives — at which point no route can serve it whole, and splitting it
     * geographically at least lets two tankers each take a half. Returning a list rather
     * than a block is what makes that expressible instead of a special case downstream.
     *
     * @param village          the village being solved
     * @param points           its active collection points
     * @param ctx              the run this is being solved for
     * @param holdBudgetMinutes the budget a block's internal time must fit inside
     */
    public List<VillageBlock> solve(Village village,
                                    List<CollectionPoint> points,
                                    PlanningContext ctx,
                                    int holdBudgetMinutes) {
        if (points.isEmpty()) {
            return List.of();
        }

        List<VillageBlock> blocks = new ArrayList<>();
        solveInto(village, points, ctx, holdBudgetMinutes, blocks);
        return List.copyOf(blocks);
    }

    private void solveInto(Village village,
                           List<CollectionPoint> points,
                           PlanningContext ctx,
                           int holdBudgetMinutes,
                           List<VillageBlock> into) {
        List<CollectionPoint> sequence = twoOpt(nearestNeighbour(points, ctx), ctx);
        double internalMinutes = pathMinutes(sequence, ctx) + serviceMinutes(sequence);

        // A single point that cannot be served in time cannot be split any further. It is
        // genuinely unreachable, and the constraint checker rejecting it is the right
        // outcome: coverage mode will record it as UNREACHABLE_WITHIN_HOLD rather than the
        // solver pretending otherwise.
        if (internalMinutes > holdBudgetMinutes && sequence.size() > 1) {
            List<List<CollectionPoint>> halves = splitGeographically(sequence);
            solveInto(village, halves.get(0), ctx, holdBudgetMinutes, into);
            solveInto(village, halves.get(1), ctx, holdBudgetMinutes, into);
            return;
        }

        into.add(new VillageBlock(
                village,
                sequence,
                internalMinutes,
                litres(sequence, ctx.session()),
                internalMinutes + returnLegMinutes(sequence.getLast(), ctx)));
    }

    /**
     * A starting order: begin at the point nearest the plant, then always take the closest
     * unvisited one.
     *
     * <p>Starting from the plant side rather than an arbitrary point matters because the
     * first and last points become the block's entry and exit, and those are what the legs
     * between villages are measured from.
     */
    private List<CollectionPoint> nearestNeighbour(List<CollectionPoint> points,
                                                   PlanningContext ctx) {
        List<CollectionPoint> remaining = new ArrayList<>(points);
        GeoPoint plant = ctx.plantLocation();

        CollectionPoint current = remaining.stream()
                .min(Comparator.comparingDouble(p -> legMinutes(plant, locationOf(p), ctx)))
                .orElseThrow();
        remaining.remove(current);

        List<CollectionPoint> sequence = new ArrayList<>(points.size());
        sequence.add(current);

        while (!remaining.isEmpty()) {
            GeoPoint from = locationOf(current);
            CollectionPoint next = remaining.stream()
                    .min(Comparator.comparingDouble(p -> legMinutes(from, locationOf(p), ctx)))
                    .orElseThrow();
            remaining.remove(next);
            sequence.add(next);
            current = next;
        }
        return sequence;
    }

    /**
     * 2-opt over the path: repeatedly reverse a run of points where doing so shortens it.
     *
     * <p>Nearest-neighbour reliably strands a point or two and doubles back for them at the
     * end; reversing a segment is exactly the move that undoes that.
     */
    private List<CollectionPoint> twoOpt(List<CollectionPoint> sequence, PlanningContext ctx) {
        if (sequence.size() < 4) {
            return sequence;
        }

        List<CollectionPoint> best = new ArrayList<>(sequence);
        double bestCost = pathMinutes(best, ctx);

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean improved = false;

            for (int i = 0; i < best.size() - 1; i++) {
                for (int k = i + 1; k < best.size(); k++) {
                    List<CollectionPoint> candidate = reverseSegment(best, i, k);
                    double cost = pathMinutes(candidate, ctx);

                    if (cost < bestCost - MIN_GAIN_MINUTES) {
                        best = candidate;
                        bestCost = cost;
                        improved = true;
                    }
                }
            }

            if (!improved) {
                break;
            }
        }
        return best;
    }

    /**
     * Cut the village in two along whichever axis it is more spread out on.
     *
     * <p>Geographic rather than by count, so each half is a place a tanker can work rather
     * than a scattering of points across the whole village. The wider axis is the one worth
     * cutting: splitting a long thin village across its short axis would leave two halves
     * that overlap each other end to end.
     */
    private List<List<CollectionPoint>> splitGeographically(List<CollectionPoint> points) {
        double latSpread = spread(points, p -> p.getLat().doubleValue());
        double lngSpread = spread(points, p -> p.getLng().doubleValue());

        Comparator<CollectionPoint> alongWiderAxis = latSpread >= lngSpread
                ? Comparator.comparingDouble(p -> p.getLat().doubleValue())
                : Comparator.comparingDouble(p -> p.getLng().doubleValue());

        List<CollectionPoint> ordered = points.stream().sorted(alongWiderAxis).toList();
        int half = ordered.size() / 2;

        return List.of(
                List.copyOf(ordered.subList(0, half)),
                List.copyOf(ordered.subList(half, ordered.size())));
    }

    private static double spread(List<CollectionPoint> points,
                                 java.util.function.ToDoubleFunction<CollectionPoint> axis) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (CollectionPoint point : points) {
            double value = axis.applyAsDouble(point);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return max - min;
    }

    private static List<CollectionPoint> reverseSegment(List<CollectionPoint> points,
                                                        int from, int to) {
        List<CollectionPoint> out = new ArrayList<>(points);
        for (int left = from, right = to; left < right; left++, right--) {
            CollectionPoint swap = out.get(left);
            out.set(left, out.get(right));
            out.set(right, swap);
        }
        return out;
    }

    /** Driving time along the sequence, excluding the service stops themselves. */
    private double pathMinutes(List<CollectionPoint> sequence, PlanningContext ctx) {
        double minutes = 0;
        for (int i = 0; i < sequence.size() - 1; i++) {
            minutes += legMinutes(locationOf(sequence.get(i)),
                    locationOf(sequence.get(i + 1)), ctx);
        }
        return minutes;
    }

    private static double serviceMinutes(List<CollectionPoint> sequence) {
        return sequence.stream()
                .mapToDouble(point -> point.getServiceMinutes().doubleValue())
                .sum();
    }

    private static double litres(List<CollectionPoint> sequence, Session session) {
        return sequence.stream()
                .mapToDouble(point -> session == Session.MORNING
                        ? point.getAvgMorningLitres().doubleValue()
                        : point.getAvgEveningLitres().doubleValue())
                .sum();
    }

    /**
     * The run home from this block's exit.
     *
     * <p>Deliberately excludes the plant's unload time. This figure is summed across every
     * village by the feasibility assessor, and unloading happens once per route rather than
     * once per village — charging it here would inflate the requirement by an unload per
     * village and make a feasible dairy look impossible.
     */
    private double returnLegMinutes(CollectionPoint exit, PlanningContext ctx) {
        return legMinutes(locationOf(exit), ctx.plantLocation(), ctx);
    }

    private double legMinutes(GeoPoint from, GeoPoint to, PlanningContext ctx) {
        return SpoilageConstraint.minutes(travel.between(from, to, ctx.session()));
    }

    private static GeoPoint locationOf(CollectionPoint point) {
        return new GeoPoint(point.getLat().doubleValue(), point.getLng().doubleValue());
    }
}