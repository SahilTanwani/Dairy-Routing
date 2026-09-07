package com.dairy.milkroute.controller;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.dto.response.HealthResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness probe.
 *
 * <p>A 200 here means more than "the process is running". The context only starts once
 * Flyway has applied every migration and Hibernate has validated its mappings against the
 * resulting schema, so a successful response also says the database was reachable and the
 * schema was correct at boot.
 *
 * <p>The timestamp comes from {@link ClockProvider}, not the wall clock. Under the
 * {@code sim} profile this endpoint reports simulated time, which makes it a cheap way to
 * see where the virtual clock currently sits.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final ClockProvider clock;
    private final String serviceName;

    public HealthController(ClockProvider clock,
                            @Value("${spring.application.name}") String serviceName) {
        this.clock = clock;
        this.serviceName = serviceName;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", serviceName, clock.now());
    }
}
