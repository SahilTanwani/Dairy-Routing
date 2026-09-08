package com.dairy.milkroute.controller;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.config.ReadinessState;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The other half of the health contract: what it says before the dairy is loaded.
 *
 * <p>A separate class rather than a nested one because each needs its own
 * {@link ReadinessState} bean, and a shared one would make the two tests depend on the order
 * they happen to run in.
 *
 * <p>Worth a test of its own because the failure it prevents is a confusing one. Tomcat
 * accepts requests before the seeder finishes; a caller that trusts a 200 in that window gets
 * an error about a missing solver parameter and goes looking for a bug in the solver.
 */
@WebMvcTest(HealthController.class)
class HealthReadinessTest {

    private static final Instant FIXED = Instant.parse("2026-09-08T04:30:00Z");

    @TestConfiguration
    static class NotReadyConfig {

        @Bean
        ClockProvider clockProvider() {
            return () -> FIXED;
        }

        /** Never marked ready: the state during startup, before any dairy exists. */
        @Bean
        ReadinessState readinessState() {
            return new ReadinessState();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthIsUnavailableUntilTheDairyIsLoaded() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("STARTING"))
                .andExpect(jsonPath("$.service").value("milkroute"));
    }
}
