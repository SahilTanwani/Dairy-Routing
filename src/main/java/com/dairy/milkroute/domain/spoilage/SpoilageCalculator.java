package com.dairy.milkroute.domain.spoilage;

/**
 * How many minutes milk survives in a tanker before the plant rejects it.
 *
 * <p>Not a constant. Bacterial growth in raw milk roughly doubles for every 10 C rise,
 * so a hold window that is correct at 22 C is badly wrong at 35 C. On a cool morning a
 * tanker has around five and a half hours; on a hot evening it has about two. That
 * difference is why the morning and evening sessions need separate route plans, and it
 * is what makes the evening session infeasible in summer with this fleet.
 *
 * <p>Plain Java by design: no Spring, no repository, no clock. It takes two numbers and
 * returns a number, so it can be tested in microseconds without a context.
 */
public final class SpoilageCalculator {

    private static final double REFERENCE_TEMP_C = 30.0;
    private static final double Q10_INTERVAL_C   = 10.0;

    private final double baseMinutesAtReferenceTemp;
    private final double q10Factor;
    private final double insulationOffsetC;
    private final double minHoldMinutes;
    private final double maxHoldMinutes;

    public SpoilageCalculator(double baseMinutesAtReferenceTemp,
                              double q10Factor,
                              double insulationOffsetC,
                              double minHoldMinutes,
                              double maxHoldMinutes) {
        if (baseMinutesAtReferenceTemp <= 0) {
            throw new IllegalArgumentException("baseMinutesAtReferenceTemp must be positive");
        }
        if (q10Factor <= 1.0) {
            throw new IllegalArgumentException("q10Factor must exceed 1.0");
        }
        if (minHoldMinutes >= maxHoldMinutes) {
            throw new IllegalArgumentException("minHoldMinutes must be below maxHoldMinutes");
        }
        this.baseMinutesAtReferenceTemp = baseMinutesAtReferenceTemp;
        this.q10Factor                  = q10Factor;
        this.insulationOffsetC          = insulationOffsetC;
        this.minHoldMinutes             = minHoldMinutes;
        this.maxHoldMinutes             = maxHoldMinutes;
    }

    /**
     * Minutes of safe hold time from first collection to plant arrival.
     *
     * <p>Insulation is modelled as a temperature offset rather than a multiplier: a
     * jacket slows the milk warming toward ambient, so the milk behaves as though the
     * surrounding air were cooler. The roughly 1.5x gain falls out of the exponential
     * rather than being an arbitrary constant.
     *
     * @param ambientC  ambient temperature for the session
     * @param insulated whether the tanker has a thermal jacket
     */
    public int holdBudgetMinutes(double ambientC, boolean insulated) {
        double effectiveC = ambientC - (insulated ? insulationOffsetC : 0.0);

        double budget = baseMinutesAtReferenceTemp
                * Math.pow(q10Factor, (REFERENCE_TEMP_C - effectiveC) / Q10_INTERVAL_C);

        return (int) Math.round(clamp(budget));
    }

    /**
     * Bounds the model at both ends.
     *
     * <p>Without the ceiling, a 10 C morning yields twelve hours: bacteriologically true,
     * operationally useless, since nobody leaves milk in a tanker all day. Without the
     * floor, one bad sensor reading of 50 C would put every trip into alarm and the
     * dispatcher would stop trusting the board.
     */
    private double clamp(double minutes) {
        return Math.min(maxHoldMinutes, Math.max(minHoldMinutes, minutes));
    }
}