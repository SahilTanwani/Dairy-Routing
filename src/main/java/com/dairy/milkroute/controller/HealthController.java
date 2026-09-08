package com.dairy.milkroute.controller;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.config.ReadinessState;
import com.dairy.milkroute.dto.response.HealthResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 *
 * <p><strong>Up is not the same as ready.</strong> Tomcat accepts requests before the seeder
 * has finished, so on a fresh database there is a window where the process is listening and
 * the solver parameters do not yet exist. Answering 200 during that window is a lie that
 * costs somebody a confused minute every time: a demo script, a container healthcheck or a
 * curl arrives, gets a plausible-looking failure about a missing parameter, and looks for a
 * bug that is not there. So this returns 503 until the dairy is actually loaded.
 */
@RestController
@RequestMapping(ApiPaths.V1)
public class HealthController {

    private final ClockProvider clock;
    private final ReadinessState readiness;
    private final String serviceName;

    public HealthController(ClockProvider clock,
                            ReadinessState readiness,
                            @Value("${spring.application.name}") String serviceName) {
        this.clock = clock;
        this.readiness = readiness;
        this.serviceName = serviceName;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        if (!readiness.isReady()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new HealthResponse("STARTING", serviceName, clock.now()));
        }
        return ResponseEntity.ok(new HealthResponse("UP", serviceName, clock.now()));
    }
}
