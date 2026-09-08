package com.dairy.milkroute.config;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Whether the application is ready to answer, as distinct from merely being up.
 *
 * <p>Tomcat starts accepting requests before {@code ApplicationRunner}s finish, so on a
 * fresh database there is a window of a couple of seconds where the process is listening and
 * the solver parameters do not exist yet. A request landing in that window fails with
 * "solver parameter 'circuityFactor' is not seeded", which reads like a broken build rather
 * than a race.
 *
 * <p>A couple of seconds sounds harmless. It is not: it is exactly when a demo script,
 * a container healthcheck and an impatient curl all arrive.
 *
 * <p>Atomic rather than volatile because the seeder writes it on the main thread while
 * request threads read it, and the intent — one transition, once — is worth stating in the
 * type.
 */
@Component
public class ReadinessState {

    private final AtomicBoolean ready = new AtomicBoolean(false);

    /** Called once the database is known to hold a usable dairy. */
    public void markReady() {
        ready.set(true);
    }

    public boolean isReady() {
        return ready.get();
    }
}
