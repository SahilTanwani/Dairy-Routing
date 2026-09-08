package com.dairy.milkroute.domain.mitigation;

import java.time.Instant;

/**
 * Do nothing. Finish the route as planned.
 *
 * <p>Shown deliberately, and shown with its risk spelled out. A dispatcher offered only the
 * cautious options is being steered rather than informed, and sometimes finishing the route
 * genuinely is right — the deadline is a model with a safety buffer in it, and a driver
 * eight minutes over on a 313 minute budget is not a crisis.
 *
 * <p>When it does not meet the deadline, the rationale says how much milk is at risk instead
 * of implying the option is unavailable. Making it look forbidden would hide the trade-off
 * that the whole board exists to surface.
 *
 * @param litresAtRisk        milk that would arrive past the budget
 * @param projectedArrival    when the tanker reaches the plant on the current course
 * @param projectedMilkAgeMin how old the oldest milk is then
 * @param meetsDeadline       whether that is inside the budget
 * @param minutesOver         how far past, zero when it fits
 */
public record ContinueAsPlanned(
        double litresAtRisk,
        Instant projectedArrival,
        int projectedMilkAgeMin,
        boolean meetsDeadline,
        long minutesOver) implements Mitigation {

    /** Doing nothing saves nothing; it is the baseline everything else is measured against. */
    @Override
    public double litresSaved() {
        return 0;
    }

    @Override
    public String action() {
        return "Continue the route as planned";
    }

    @Override
    public String rationale() {
        if (meetsDeadline) {
            return "Arrives at %d minutes, inside the budget. Nothing needs to change."
                    .formatted(projectedMilkAgeMin);
        }
        return ("Arrives %d minutes past the budget with %.0f L aboard. "
                + "The whole load is at risk of rejection.")
                .formatted(minutesOver, litresAtRisk);
    }
}
