package com.dairy.milkroute.controller;

import com.dairy.milkroute.simulation.SimulationEngine;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drives the simulation.
 *
 * <p>Only present under the {@code sim} profile, alongside {@link
 * com.dairy.milkroute.simulation.VirtualClock}. A production deployment has no endpoint that
 * can wind the clock forward or reseed the dairy mid-morning, and the profile is what makes
 * that structural rather than a matter of remembering.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/sim")
@Profile("sim")
public class SimulationController {

    private final SimulationEngine engine;

    public SimulationController(SimulationEngine engine) {
        this.engine = engine;
    }

    /**
     * Starts a scenario. Returns immediately; poll {@code /status} to watch it.
     *
     * <p>Reseeds the dataset the scenario names, so this destroys whatever is in the
     * database. That is what {@code sim} is for.
     */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam(defaultValue = "happy-morning") String scenario) {
        String started = engine.start(scenario);
        return Map.of("started", started, "status", engine.status());
    }

    @PostMapping("/pause")
    public SimulationEngine.Status pause() {
        engine.pause();
        return engine.status();
    }

    @PostMapping("/resume")
    public SimulationEngine.Status resume() {
        engine.resume();
        return engine.status();
    }

    /** Skips the clock ahead. Forward only. */
    @PostMapping("/jump-to")
    public SimulationEngine.Status jumpTo(@RequestParam String at) {
        engine.jumpTo(Instant.parse(at));
        return engine.status();
    }

    @GetMapping("/status")
    public SimulationEngine.Status status() {
        return engine.status();
    }
}
