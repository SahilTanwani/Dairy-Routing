package com.dairy.milkroute.dto.response;

import java.time.Instant;

/**
 * Liveness payload for {@code GET /api/v1/health}.
 *
 * @param status    always {@code "UP"} — the endpoint only answers if the context started
 * @param service   the configured application name
 * @param timestamp current time, from the injected clock rather than the wall clock
 */
public record HealthResponse(String status, String service, Instant timestamp) {
}
