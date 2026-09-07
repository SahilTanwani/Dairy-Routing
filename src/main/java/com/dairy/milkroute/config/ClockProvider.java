package com.dairy.milkroute.config;

import java.time.Instant;

/**
 * The single source of "now" for the whole application.
 *
 * <p>Every class that needs the current time takes a {@code ClockProvider} through its
 * constructor instead of calling {@link Instant#now()}. That is what makes the
 * simulation possible: swapping one bean for a virtual clock moves time for every
 * planner, spoilage check, ETA calculation and scheduled sweep at once.
 *
 * <p>This interface is deliberately plain Java with no Spring annotations, so classes in
 * {@code domain/} can depend on it without pulling the framework into the domain model.
 */
public interface ClockProvider {

    /** The current instant, in UTC. */
    Instant now();
}
