package com.dairy.milkroute.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One milk record, as its farmer sees it.
 *
 * <p>Built inside the service transaction rather than from an entity handed to a controller.
 * With {@code open-in-view: false} a lazy association touched after the transaction closes
 * throws, and the right answer is to finish the mapping while the session is still open —
 * not to widen the session until the problem goes quiet.
 *
 * <p>Voided rows are returned and marked rather than hidden. A farmer whose mis-keyed 125
 * litres became 12.5 should be able to see that it happened; a record that silently
 * disappears is how people stop trusting the numbers.
 */
public record CollectionRecordResponse(
        Instant collectedAt,
        BigDecimal litres,
        String collectionPointCode,
        boolean voided,
        String voidReason) {
}
