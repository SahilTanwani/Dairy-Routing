package com.dairy.milkroute.dto.request;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A GPS reading.
 *
 * <p>No idempotency key, deliberately. A duplicate position is harmless — pings observe,
 * events change things — and stamping a UUID on the highest-volume write in the system to
 * guard against a harmless duplicate would be paying a lot for nothing.
 */
public record TankerPingRequest(
        @NotNull BigDecimal lat,
        @NotNull BigDecimal lng,
        Short accuracyM,
        @NotNull Instant recordedAt) {

    /** Pings are buffered and flushed in batches, exactly like events. */
    public record Batch(List<TankerPingRequest> pings) {
    }
}
