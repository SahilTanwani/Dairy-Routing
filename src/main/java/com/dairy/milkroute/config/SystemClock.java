package com.dairy.milkroute.config;

import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Wall-clock implementation of {@link ClockProvider}, active in every profile except
 * {@code sim}, where {@code simulation/VirtualClock} takes over.
 *
 * <p>This class holds the <strong>only</strong> call to {@link Instant#now()} in the
 * codebase. If you find yourself wanting a second one, inject a {@code ClockProvider}
 * instead.
 */
@Component
@Profile("!sim")
public class SystemClock implements ClockProvider {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
