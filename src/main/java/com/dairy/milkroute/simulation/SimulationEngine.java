package com.dairy.milkroute.simulation;

import com.dairy.milkroute.dto.response.TripResponse;
import com.dairy.milkroute.enums.TripStatus;
import com.dairy.milkroute.service.SpoilageMonitorService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Runs a whole collection session in a few seconds.
 *
 * <p>The loop is deliberately dull: advance the clock thirty seconds, let every driver do
 * whatever that makes due, sweep the spoilage monitor, repeat. All the interesting behaviour
 * belongs to the code being simulated rather than to the simulator, which is the point — a
 * clever simulator would be a second implementation of the system, and passing it would prove
 * only that the two agreed.
 *
 * <p>Thirty-second steps because that is fine enough for a stop to take a plausible number of
 * ticks and coarse enough that a three-hour session is a few hundred iterations.
 *
 * <p>The monitor is swept every step rather than on its schedule. Under {@code sim} the
 * scheduled sweep still runs on wall-clock time, which has nothing to do with simulated time;
 * driving it from the loop is what makes the ratchet and the alerts land where the scenario
 * says they should.
 */
@Service
@Profile("sim")
public class SimulationEngine {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    private final VirtualClock clock;
    private final ScenarioLoader scenarios;
    private final SimulationTransport transport;
    private final SpoilageMonitorService monitor;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "simulation");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicInteger step = new AtomicInteger();
    private final AtomicReference<String> scenarioName = new AtomicReference<>();
    private final List<VirtualDriver> drivers = new ArrayList<>();

    public enum State { IDLE, RUNNING, PAUSED, FINISHED, FAILED }

    public SimulationEngine(VirtualClock clock,
                            ScenarioLoader scenarios,
                            SimulationTransport transport,
                            SpoilageMonitorService monitor) {
        this.clock = clock;
        this.scenarios = scenarios;
        this.transport = transport;
        this.monitor = monitor;
    }

    /**
     * Starts a scenario in the background.
     *
     * <p>Background so that {@code status}, {@code pause} and {@code jumpTo} mean something
     * while it runs. A synchronous run would be simpler and would make the other three
     * endpoints decorative.
     */
    public synchronized String start(String scenario) {
        if (state.get() == State.RUNNING) {
            throw new IllegalStateException(
                    "a simulation is already running (%s); pause or wait for it to finish"
                            .formatted(scenarioName.get()));
        }

        ScenarioConfig config = scenarios.load(scenario);
        scenarioName.set(config.name());
        state.set(State.RUNNING);
        paused.set(false);
        step.set(0);

        worker.submit(() -> {
            try {
                run(config);
                state.set(State.FINISHED);
            } catch (RuntimeException e) {
                log.error("Simulation '{}' failed: {}", config.name(), e.getMessage(), e);
                state.set(State.FAILED);
            }
        });

        return config.name();
    }

    private void run(ScenarioConfig config) {
        clock.resetTo(config.startAt());

        // Set the world up through the same API a person would use.
        transport.reseed(config.dataset());
        Long planId = transport.generatePlan(
                config.session(), config.businessDate().toString(), config.ambientC());
        transport.publishPlan(planId);

        List<TripResponse> trips = transport.createTrips(
                config.session(), config.businessDate().toString());

        Instant offlineFrom = config.startAt().plus(Duration.ofMinutes(config.offlineFromMinutes()));
        Instant offlineUntil = config.startAt().plus(Duration.ofMinutes(config.offlineUntilMinutes()));

        drivers.clear();
        long seed = 0;
        for (TripResponse trip : trips) {
            if (TripStatus.BLOCKED.name().equals(trip.status())) {
                // Nobody to drive it. Simulating one anyway would hide the fact that the
                // dairy could not crew this route.
                continue;
            }
            drivers.add(new VirtualDriver(trip, transport, config.startAt(),
                    offlineFrom, offlineUntil, seed++));
        }

        log.info("Simulation '{}' starting: plan {}, {} trips, {} drivers, offline {} to {}",
                config.name(), planId, trips.size(), drivers.size(),
                config.offlineFromMinutes(), config.offlineUntilMinutes());

        Duration stepSize = Duration.ofSeconds(config.stepSeconds());
        Instant weatherTurnsAt = config.hasWeatherChange()
                ? config.startAt().plus(Duration.ofMinutes(config.ambientRiseAtMinutes()))
                : null;

        for (int i = 0; i < config.maxSteps(); i++) {
            while (paused.get() && state.get() == State.RUNNING) {
                sleepBriefly();
            }
            if (state.get() != State.RUNNING) {
                return;
            }

            Instant now = clock.advance(stepSize);
            step.incrementAndGet();

            drivers.forEach(driver -> driver.tick(now));

            Double ambientNow = weatherTurnsAt != null && !now.isBefore(weatherTurnsAt)
                    ? config.ambientRiseToC()
                    : config.ambientC();
            monitor.sweep(ambientNow);

            if (drivers.stream().allMatch(VirtualDriver::finished)) {
                log.info("Simulation '{}' complete after {} steps ({} simulated minutes)",
                        config.name(), i + 1,
                        Duration.between(config.startAt(), now).toMinutes());
                return;
            }
        }

        log.warn("Simulation '{}' hit its step cap of {} with {} drivers still going",
                config.name(), config.maxSteps(),
                drivers.stream().filter(driver -> !driver.finished()).count());
    }

    public void pause() {
        paused.set(true);
        state.compareAndSet(State.RUNNING, State.PAUSED);
    }

    public void resume() {
        state.compareAndSet(State.PAUSED, State.RUNNING);
        paused.set(false);
    }

    /** Skips ahead. Forward only, like the clock itself. */
    public Instant jumpTo(Instant target) {
        return clock.jumpTo(target);
    }

    public Status status() {
        long finished = drivers.stream().filter(VirtualDriver::finished).count();
        return new Status(
                state.get().name(),
                scenarioName.get(),
                clock.now(),
                step.get(),
                drivers.size(),
                (int) finished);
    }

    /**
     * @param simulatedTime where the clock has reached
     * @param driversDone   how many have finished their routes
     */
    public record Status(String state,
                         String scenario,
                         Instant simulatedTime,
                         int step,
                         int drivers,
                         int driversDone) {
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
