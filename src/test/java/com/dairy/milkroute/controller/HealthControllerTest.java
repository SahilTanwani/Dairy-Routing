package com.dairy.milkroute.controller;

import java.time.Instant;

import com.dairy.milkroute.config.ClockProvider;

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
 * A web slice — no datasource, so this runs without a database.
 *
 * <p>The fixed clock is the point: because the controller takes a {@link ClockProvider}
 * rather than reading the wall clock, the timestamp in the response is an exact
 * assertion instead of a range check.
 */
@WebMvcTest(HealthController.class)
class HealthControllerTest {

    private static final Instant FIXED = Instant.parse("2026-09-08T04:30:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        ClockProvider clockProvider() {
            return () -> FIXED;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthReportsUpWithTheInjectedClock() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("milkroute"))
                .andExpect(jsonPath("$.timestamp").value("2026-09-08T04:30:00Z"));
    }
}
