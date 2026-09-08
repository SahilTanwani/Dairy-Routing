package com.dairy.milkroute.domain.mitigation;

import java.time.Instant;

/**
 * Something a dispatcher could do about a trip in trouble.
 *
 * <p><strong>Generated and ranked, never executed.</strong> Every option here means a
 * decision about somebody's milk — whose gets collected, whose is left in a can at the
 * roadside — and a system that made those choices on its own would be making them without
 * any of the context that actually decides them. The dispatcher knows the driver is new, or
 * that the far village complained last week, or that the plant manager will wait ten minutes.
 *
 * <p>Sealed to two implementations. {@code DivertToPlant} and {@code TransferLoad} are on the
 * cut list and are not built; the interface names only what exists, so it cannot promise an
 * option the system will not produce.
 *
 * <p>{@link ContinueAsPlanned} is always offered, with its cost stated. A list of only the
 * cautious options is a recommendation disguised as a menu.
 */
public sealed interface Mitigation permits SkipRemaining, ContinueAsPlanned {

    /** What the dispatcher would be choosing, in one line. */
    String action();

    /** Litres this saves against doing nothing. Zero for the do-nothing option itself. */
    double litresSaved();

    /** When the tanker would reach the plant. */
    Instant projectedArrival();

    /** How old the oldest milk would be on arrival. */
    int projectedMilkAgeMin();

    /** Whether that lands inside the hold budget. */
    boolean meetsDeadline();

    /**
     * Why this is being offered, and what it costs.
     *
     * <p>Both halves matter. An option with no stated downside reads as free, and none of
     * these are.
     */
    String rationale();
}
