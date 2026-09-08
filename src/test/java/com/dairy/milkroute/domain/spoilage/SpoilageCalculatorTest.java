package com.dairy.milkroute.domain.spoilage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Most of these assert relationships rather than absolute numbers. If someone retunes
 * baseHoldMinutesAt30C from 180 to 165, only the table test should break — the doubling,
 * halving and insulation-offset tests still hold, which tells you the model is intact
 * and only its calibration moved.
 */
class SpoilageCalculatorTest {

    /** The values seeded into solver_parameter. */
    private final SpoilageCalculator calc =
            new SpoilageCalculator(180.0, 2.0, 6.0, 60.0, 480.0);

    // ---------------------------------------------------------------- the table

    @Test
    void reproducesTheDocumentedTemperatureTable() {
        // 180 x 2^((30 - T) / 10), rounded.
        assertThat(calc.holdBudgetMinutes(18.0, false)).isEqualTo(414);
        assertThat(calc.holdBudgetMinutes(22.0, false)).isEqualTo(313);
        assertThat(calc.holdBudgetMinutes(27.0, false)).isEqualTo(222);
        assertThat(calc.holdBudgetMinutes(30.0, false)).isEqualTo(180);
        assertThat(calc.holdBudgetMinutes(35.0, false)).isEqualTo(127);
        assertThat(calc.holdBudgetMinutes(39.0, false)).isEqualTo(96);
    }

    // ------------------------------------------------------------ the Q10 shape

    @Test
    void tenDegreesCoolerDoublesTheBudget() {
        assertThat(calc.holdBudgetMinutes(20.0, false))
                .isEqualTo(2 * calc.holdBudgetMinutes(30.0, false));
    }

    @Test
    void tenDegreesWarmerHalvesTheBudget() {
        assertThat(calc.holdBudgetMinutes(40.0, false))
                .isEqualTo(calc.holdBudgetMinutes(30.0, false) / 2);
    }

    @Test
    void theBudgetFallsMonotonicallyAsItGetsHotter() {
        int previous = Integer.MAX_VALUE;
        for (double t = 20.0; t <= 45.0; t += 1.0) {
            int budget = calc.holdBudgetMinutes(t, false);
            assertThat(budget).isLessThanOrEqualTo(previous);
            previous = budget;
        }
    }

    // ------------------------------------------------------------- insulation

    @Test
    void insulationActsAsASixDegreeTemperatureOffset() {
        // A jacket does not multiply the budget; it slows the milk warming toward
        // ambient, so the milk behaves as though the air were six degrees cooler.
        assertThat(calc.holdBudgetMinutes(35.0, true))
                .isEqualTo(calc.holdBudgetMinutes(29.0, false));
    }

    @Test
    void insulationBuysRoughlyHalfAgainOnAHotEvening() {
        assertThat(calc.holdBudgetMinutes(35.0, false)).isEqualTo(127);
        assertThat(calc.holdBudgetMinutes(35.0, true)).isEqualTo(193);
        // 2^(6/10) = 1.52, so the multiplier falls out of the exponential
        // rather than being an arbitrary constant.
    }

    // ----------------------------------------------------------------- clamps

    @Test
    void ceilingStopsACoolMorningProducingAnUnusableWindow() {
        // Bacteriology alone allows twelve hours at 10 C. Nobody leaves milk in a
        // tanker all day, so the model is bounded at eight.
        assertThat(calc.holdBudgetMinutes(10.0, false)).isEqualTo(480);
        assertThat(calc.holdBudgetMinutes(5.0, false)).isEqualTo(480);
    }

    @Test
    void onlyTheColdestMorningsReachTheCeilingWithInsulation() {
        assertThat(calc.holdBudgetMinutes(22.0, true)).isEqualTo(475);  // just under
        assertThat(calc.holdBudgetMinutes(18.0, true)).isEqualTo(480);  // clamped
    }

    @Test
    void floorStopsABadSensorReadingAlertingEveryTrip() {
        // A thermometer stuck at 55 C would otherwise give 45 minutes and put every
        // trip into alarm.
        assertThat(calc.holdBudgetMinutes(55.0, false)).isEqualTo(60);
    }

    // ------------------------------------------------------- the headline finding

    @Test
    void theMorningBudgetIsRoughlyTwoAndAHalfTimesTheEvening() {
        // This ratio is why the morning and evening sessions need separate plans,
        // and why the evening is infeasible in summer with this fleet.
        int morning = calc.holdBudgetMinutes(22.0, false);   // 313
        int evening = calc.holdBudgetMinutes(35.0, false);   // 127
        assertThat((double) morning / evening).isBetween(2.4, 2.5);
    }

    // ------------------------------------------------------------ configuration

    @Test
    void rejectsNonsenseConfiguration() {
        assertThatThrownBy(() -> new SpoilageCalculator(0, 2.0, 6, 60, 480))
                .isInstanceOf(IllegalArgumentException.class);

        // A Q10 factor of 1.0 would mean temperature has no effect at all.
        assertThatThrownBy(() -> new SpoilageCalculator(180, 1.0, 6, 60, 480))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new SpoilageCalculator(180, 2.0, 6, 500, 480))
                .isInstanceOf(IllegalArgumentException.class);
    }
}