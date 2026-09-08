package com.dairy.milkroute.domain.trip;

import com.dairy.milkroute.enums.EventType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * One thing a driver's phone reported, in the shape the replayer needs.
 *
 * <p>Deliberately not the web request DTO and not the JPA entity. The replayer is plain
 * domain code that should not change when the API grows a field or the column type changes,
 * and keeping a small record between the two is what buys that.
 *
 * @param type           what happened
 * @param clientTs       when the phone says it happened; replay order comes from this
 * @param seq            which stop, by its sequence on the trip; null for whole-trip events
 * @param litresByFarmer farmer code to litres, for a COLLECTED
 * @param skipReasonName the reason on a STOP_SKIPPED, if the driver gave one
 */
public record ReplayableEvent(
        EventType type,
        Instant clientTs,
        Integer seq,
        Map<String, BigDecimal> litresByFarmer,
        String skipReasonName) {

    public ReplayableEvent {
        litresByFarmer = litresByFarmer == null ? Map.of() : Map.copyOf(litresByFarmer);
    }
}
