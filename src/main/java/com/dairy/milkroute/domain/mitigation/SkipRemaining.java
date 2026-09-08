package com.dairy.milkroute.domain.mitigation;

import java.time.Instant;
import java.util.List;

/**
 * Abandon the stops still ahead and drive the milk already aboard straight to the plant.
 *
 * <p>The trade is explicit: the load on board survives, the farmers ahead are not collected
 * from today. That is a real cost to real people, so the response names how many points and
 * how many litres are being given up rather than reporting only what is rescued.
 *
 * @param stopsSkipped        how many points would go uncollected
 * @param litresForgone       their milk, left behind
 * @param litresSaved         what reaches the plant that otherwise would not
 * @param projectedArrival    when the tanker would get there
 * @param projectedMilkAgeMin how old the oldest milk would be then
 * @param meetsDeadline       whether that is inside the budget
 * @param skippedPointCodes   which points, so ops can ring them
 */
public record SkipRemaining(
        int stopsSkipped,
        double litresForgone,
        double litresSaved,
        Instant projectedArrival,
        int projectedMilkAgeMin,
        boolean meetsDeadline,
        List<String> skippedPointCodes) implements Mitigation {

    public SkipRemaining {
        skippedPointCodes = List.copyOf(skippedPointCodes);
    }

    @Override
    public String action() {
        return "Skip the remaining %d stops and return to the plant".formatted(stopsSkipped);
    }

    @Override
    public String rationale() {
        if (!meetsDeadline) {
            return ("Saves %.0f L of the milk already aboard, but even returning now the oldest "
                    + "arrives at %d minutes — past the budget. %d points go uncollected.")
                    .formatted(litresSaved, projectedMilkAgeMin, stopsSkipped);
        }
        return ("Gets %.0f L to the plant inside the budget, at %d minutes. "
                + "Costs %d points and %.0f L left uncollected today.")
                .formatted(litresSaved, projectedMilkAgeMin, stopsSkipped, litresForgone);
    }
}
