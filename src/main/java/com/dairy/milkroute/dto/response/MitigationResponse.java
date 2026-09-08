package com.dairy.milkroute.dto.response;

import com.dairy.milkroute.domain.mitigation.ContinueAsPlanned;
import com.dairy.milkroute.domain.mitigation.Mitigation;
import com.dairy.milkroute.domain.mitigation.SkipRemaining;
import java.time.Instant;
import java.util.List;

/**
 * The options for a trip in trouble, ranked.
 *
 * <p>{@code tripVersion} is here because executing one requires sending it back. Two
 * dispatchers acting on the same trip in the same minute is a real evening, and the version
 * is what turns that into a clean 409 rather than half of each decision being applied.
 *
 * @param tripId      the trip
 * @param tripVersion send this back when executing; a stale value is refused
 * @param anySaves    false when nothing on the list gets the load in on time
 * @param summary     the honest headline, including "nothing saves this load"
 * @param options     ranked by litres saved
 */
public record MitigationResponse(
        Long tripId,
        int tripVersion,
        boolean anySaves,
        String summary,
        List<Option> options) {

    /**
     * @param action  the machine name to send back to execute this
     * @param label   what it is, in words
     * @param rationale why it is being offered and what it costs
     */
    public record Option(
            String action,
            String label,
            double litresSaved,
            Instant projectedArrival,
            int projectedMilkAgeMin,
            boolean meetsDeadline,
            String rationale) {
    }

    public static MitigationResponse of(Long tripId, int version, List<Mitigation> ranked) {
        boolean anySaves = ranked.stream().anyMatch(Mitigation::meetsDeadline);

        List<Option> options = ranked.stream()
                .map(m -> new Option(
                        actionNameOf(m),
                        m.action(),
                        m.litresSaved(),
                        m.projectedArrival(),
                        m.projectedMilkAgeMin(),
                        m.meetsDeadline(),
                        m.rationale()))
                .toList();

        // Saying so plainly is the point. A board that always offers a fix is lying, and the
        // first time a dispatcher catches it doing that they stop trusting the rest.
        String summary = anySaves
                ? "%d options; the best one gets the load in on time.".formatted(options.size())
                : "No option gets this load to the plant inside its budget. "
                        + "Choose which milk to save, and tell the plant what is coming.";

        return new MitigationResponse(tripId, version, anySaves, summary, options);
    }

    private static String actionNameOf(Mitigation mitigation) {
        return switch (mitigation) {
            case SkipRemaining ignored -> "SKIP_REMAINING";
            case ContinueAsPlanned ignored -> "CONTINUE_AS_PLANNED";
        };
    }
}
