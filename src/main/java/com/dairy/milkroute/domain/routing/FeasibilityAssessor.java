package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.domain.spoilage.SpoilageCalculator;
import com.dairy.milkroute.entity.Tanker;
import com.dairy.milkroute.enums.PlanMode;
import java.util.List;

/**
 * Decides, before any routing happens, whether serving everybody is even possible.
 *
 * <p>Two sums and a comparison. What the dairy needs is the on-milk time of every village;
 * what it has is the hold budget of every tanker at today's temperature. If the first
 * exceeds the second, no arrangement of routes fixes it, and the honest move is to say so
 * and switch to deciding who gets left out rather than producing a plan that quietly
 * cannot work.
 *
 * <p>Formally this is the Team Orienteering Problem: a fleet, a time budget per vehicle, a
 * prize at each node, and no way to visit them all. Worth naming, because it says the
 * shortfall is structural rather than a solver that needs more time.
 *
 * <p>The margin exists because both sides of the comparison are estimates. Committing the
 * fleet to the last available minute would mean any route running slightly long turns into
 * rejected milk, so planning stops short of the theoretical limit.
 */
public final class FeasibilityAssessor {

    private final SpoilageCalculator spoilage;
    private final double feasibilityMargin;

    /**
     * @param feasibilityMargin fraction of the fleet's hold budget planning will commit to;
     *                          above it, the run switches to coverage mode
     */
    public FeasibilityAssessor(SpoilageCalculator spoilage, double feasibilityMargin) {
        if (feasibilityMargin <= 0 || feasibilityMargin > 1) {
            throw new IllegalArgumentException(
                    "feasibilityMargin must be in (0, 1], was " + feasibilityMargin);
        }
        this.spoilage = spoilage;
        this.feasibilityMargin = feasibilityMargin;
    }

    public FeasibilityReport assess(List<VillageBlock> blocks, PlanningContext ctx) {
        double required = blocks.stream()
                .mapToDouble(VillageBlock::hotMinutesRequired)
                .sum();

        double available = 0;
        for (Tanker tanker : ctx.availableTankers()) {
            available += spoilage.holdBudgetMinutes(ctx.ambientTempC(), tanker.isInsulated());
        }

        PlanMode mode = required <= available * feasibilityMargin
                ? PlanMode.FULL_SERVICE
                : PlanMode.COVERAGE_OPTIMISATION;

        return new FeasibilityReport(
                mode,
                required,
                available,
                available == 0 ? Double.POSITIVE_INFINITY : required / available,
                feasibilityMargin,
                blocks.size(),
                ctx.fleetSize(),
                tankersRequired(required, available, ctx.fleetSize()));
    }

    /**
     * How many tankers this session would actually need.
     *
     * <p>Computed from the fleet's average budget rather than the best tanker's, because
     * the answer is meant to describe buying or hiring more of the fleet the dairy has, not
     * an imaginary fleet of the most capable tanker on the yard.
     */
    private int tankersRequired(double required, double available, int fleetSize) {
        if (fleetSize == 0 || available <= 0) {
            return 0;
        }
        double averageBudget = available / fleetSize;
        return (int) Math.ceil(required / (averageBudget * feasibilityMargin));
    }
}