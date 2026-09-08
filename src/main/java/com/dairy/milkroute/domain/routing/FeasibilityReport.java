package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.enums.PlanMode;

/**
 * Whether this dairy can be served at all today, and the arithmetic behind the answer.
 *
 * <p>This is the finding the whole system exists to surface. A dairy that cannot serve
 * every farmer on a 35 C evening is not a bug in the planner — it is what "milk that sits
 * too long is rejected" means once you put numbers on it. Saying so, with the numbers,
 * beats quietly producing routes that were never going to work.
 *
 * <p>The ratio is the single number to read. Below the margin the fleet has room and every
 * point gets served; above it, planning has to choose who goes without, and it should say
 * that before it starts rather than after.
 *
 * @param mode                 what the ratio implies: serve everyone, or choose
 * @param requiredHotMinutes   on-milk minutes the dairy needs, summed over villages
 * @param availableHotMinutes  hold budget the fleet supplies at today's temperature
 * @param ratio                required over available; the number that decides the mode
 * @param feasibilityMargin    the fraction of available capacity planning will commit to
 * @param villageCount         villages in scope
 * @param tankerCount          tankers available
 * @param tankersRequired      how many tankers this session would actually need, which is
 *                             the sentence ops can act on: "you need 38 and you have 22"
 */
public record FeasibilityReport(
        PlanMode mode,
        double requiredHotMinutes,
        double availableHotMinutes,
        double ratio,
        double feasibilityMargin,
        int villageCount,
        int tankerCount,
        int tankersRequired) {

    public boolean fullServicePossible() {
        return mode == PlanMode.FULL_SERVICE;
    }

    /** Tankers the dairy is short of, or zero when the fleet is sufficient. */
    public int tankerShortfall() {
        return Math.max(0, tankersRequired - tankerCount);
    }

    /**
     * A sentence for the ops board.
     *
     * <p>Deliberately concrete. "Infeasible" tells a dispatcher nothing; "needs 38 tankers,
     * you have 22" tells them the size of the problem and rules out solving it by shuffling
     * routes.
     */
    public String summary() {
        if (fullServicePossible()) {
            return "Every point can be served: %.0f of %.0f available hot minutes needed (ratio %.2f)."
                    .formatted(requiredHotMinutes, availableHotMinutes, ratio);
        }
        return ("Cannot serve every point: %.0f hot minutes needed against %.0f available "
                + "(ratio %.2f). This session needs about %d tankers and the fleet has %d.")
                .formatted(requiredHotMinutes, availableHotMinutes, ratio,
                        tankersRequired, tankerCount);
    }
}