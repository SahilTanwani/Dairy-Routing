package com.dairy.milkroute.domain.advisory;

import java.time.LocalTime;

/**
 * The cheapest recommendation this system can make: leave later.
 *
 * <p>On a hot evening the binding constraint is not the fleet, the routes or the driver
 * roster — it is the air temperature, and the air temperature falls on its own. Departing at
 * 18:30 instead of 16:30 buys an hour of extra hold budget per tanker, which buys coverage,
 * and it costs the dairy nothing: no tankers, no drivers, no capital. The farmers milk two
 * hours later.
 *
 * <p>Every other lever in the system costs money. More tankers cost money. Insulation costs
 * money. A local chilling unit costs a great deal of money. This one is a decision, and the
 * only reason a dairy would not already have made it is that nobody had put the numbers side
 * by side.
 *
 * <p>Both sides come from real planning runs against the same dairy, not from arithmetic on
 * a coverage percentage. The second run re-plans the identical villages, fleet and points at
 * the cooler temperature, so the comparison is what the system would actually produce rather
 * than an estimate of it.
 *
 * @param currentDepartAt       when the fleet leaves today
 * @param currentAmbientC       ambient at that hour
 * @param currentBudgetMinutes  hold budget a plain tanker gets at that temperature
 * @param currentCoveragePct    points served by the plan as it stands
 * @param shiftedDepartAt       the proposed departure
 * @param shiftedAmbientC       ambient at the proposed hour
 * @param shiftedBudgetMinutes  hold budget at that temperature
 * @param shiftedCoveragePct    points served by the re-planned session
 * @param litresRecoveredPerSession milk collected in the shifted plan that the current plan
 *                              leaves in farmers' cans
 * @param litresRecoveredPerYear the same figure annualised, because a daily number sounds
 *                              small and an annual one is what gets a decision made
 * @param costToTheDairy        what the change costs, which is the point of the advisory
 */
public record TimingAdvisory(
        LocalTime currentDepartAt,
        double currentAmbientC,
        int currentBudgetMinutes,
        double currentCoveragePct,
        LocalTime shiftedDepartAt,
        double shiftedAmbientC,
        int shiftedBudgetMinutes,
        double shiftedCoveragePct,
        double litresRecoveredPerSession,
        double litresRecoveredPerYear,
        String costToTheDairy) {

    /** Sessions in a year, for annualising a per-session gain. One session per day. */
    public static final int SESSIONS_PER_YEAR = 365;

    public double coverageGainPct() {
        return shiftedCoveragePct - currentCoveragePct;
    }

    public int budgetGainMinutes() {
        return shiftedBudgetMinutes - currentBudgetMinutes;
    }

    /**
     * Whether this is worth putting in front of anyone.
     *
     * <p>A shift that gains nothing is not a finding, and reporting it as one trains ops to
     * ignore the advisory. On a mild morning the honest answer is that the current departure
     * is already fine.
     */
    public boolean worthDoing() {
        return coverageGainPct() > 0.5;
    }

    /** One sentence for the ops board and the demo. */
    public String summary() {
        if (!worthDoing()) {
            return ("Leaving at %s instead of %s would not change coverage (%.0f%%). "
                    + "The current departure is already the right one.")
                    .formatted(shiftedDepartAt, currentDepartAt, currentCoveragePct);
        }
        return ("Depart %s instead of %s: %.1f C instead of %.1f C, %d minutes of hold "
                + "budget instead of %d, and coverage rises from %.0f%% to %.0f%%. "
                + "About %,.0f litres a year. Cost: %s")
                .formatted(shiftedDepartAt, currentDepartAt,
                        shiftedAmbientC, currentAmbientC,
                        shiftedBudgetMinutes, currentBudgetMinutes,
                        currentCoveragePct, shiftedCoveragePct,
                        litresRecoveredPerYear, costToTheDairy);
    }
}
