package com.dairy.milkroute.simulation;

import com.dairy.milkroute.config.ClockProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Time, under the {@code sim} profile.
 *
 * <p>This is the payoff for the rule that {@code Instant.now()} appears exactly once in the
 * codebase. Because every class takes a {@link ClockProvider} rather than reading the wall
 * clock, replacing that one implementation makes the entire system — planner, monitor, ETA
 * calculator, event ingestion, alerting — run a whole session in a few seconds without a
 * single line of any of them knowing it.
 *
 * <p>Retrofitting this later would have meant touching every class that ever asked what time
 * it was, which is most of them. It is the cheapest rule in the project and the one with the
 * largest payoff.
 *
 * <p>{@code @Profile("sim")} pairs with {@code SystemClock}'s {@code @Profile("!sim")}: one
 * of the two is always present, never both.
 */
@Component
@Profile("sim")
public class VirtualClock implements ClockProvider {

    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-10-15T04:30:00Z"));

    @Override
    public Instant now() {
        return now.get();
    }

    /** Moves time forward. Refuses to go backwards: nothing downstream expects that. */
    public Instant advance(Duration step) {
        if (step.isNegative()) {
            throw new IllegalArgumentException("the simulated clock only moves forward");
        }
        return now.updateAndGet(current -> current.plus(step));
    }

    /**
     * Jumps straight to a moment.
     *
     * <p>For skipping the boring hour between departure and the first village. Still forward
     * only — a trip whose events are already in the past cannot be un-happened by rewinding
     * the clock, and letting it try would produce a state no real session could reach.
     */
    public Instant jumpTo(Instant target) {
        return now.updateAndGet(current -> {
            if (target.isBefore(current)) {
                throw new IllegalArgumentException(
                        "cannot jump backwards: simulated time is already %s".formatted(current));
            }
            return target;
        });
    }

    /** Puts the clock back to a known starting point, for a fresh scenario. */
    public void resetTo(Instant start) {
        now.set(start);
    }
}
