package com.dairy.milkroute.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * A batch of events from one phone.
 *
 * <p>A batch rather than one call per event because that is how the road works: the driver
 * is out of signal for forty minutes and then everything arrives at once. Sending them
 * together also means the server can order them by {@code clientTs} before replaying, which
 * is what makes an out-of-order reconnect safe.
 */
public record DriverEventBatchRequest(
        @NotEmpty @Valid List<DriverEventRequest> events) {
}
