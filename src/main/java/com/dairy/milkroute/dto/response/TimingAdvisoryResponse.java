package com.dairy.milkroute.dto.response;

import com.dairy.milkroute.domain.advisory.TimingAdvisory;
import java.time.LocalTime;

/**
 * The timing advisory, flattened for the wire.
 *
 * <p>Both sides of the comparison are reported rather than only the gain, because the whole
 * argument is in the pairing: this temperature against that one, this budget against that
 * one, this coverage against that one. A single "you would gain 24%" is a claim; the pair is
 * an argument somebody can check.
 */
public record TimingAdvisoryResponse(
        Current current,
        Proposed proposed,
        double coverageGainPct,
        int budgetGainMinutes,
        double litresRecoveredPerSession,
        double litresRecoveredPerYear,
        String costToTheDairy,
        boolean worthDoing,
        String summary) {

    public record Current(LocalTime departAt, double ambientC, int holdBudgetMin, double coveragePct) { }

    public record Proposed(LocalTime departAt, double ambientC, int holdBudgetMin, double coveragePct) { }

    public static TimingAdvisoryResponse from(TimingAdvisory advisory) {
        return new TimingAdvisoryResponse(
                new Current(advisory.currentDepartAt(), advisory.currentAmbientC(),
                        advisory.currentBudgetMinutes(), advisory.currentCoveragePct()),
                new Proposed(advisory.shiftedDepartAt(), advisory.shiftedAmbientC(),
                        advisory.shiftedBudgetMinutes(), advisory.shiftedCoveragePct()),
                advisory.coverageGainPct(),
                advisory.budgetGainMinutes(),
                advisory.litresRecoveredPerSession(),
                advisory.litresRecoveredPerYear(),
                advisory.costToTheDairy(),
                advisory.worthDoing(),
                advisory.summary());
    }
}
