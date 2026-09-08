package com.dairy.milkroute.domain.trip;

import com.dairy.milkroute.entity.TripStop;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Where milk records go.
 *
 * <p>A port, so the replayer can handle a COLLECTED event without knowing that farmers live
 * in a table. Writing a collection row needs a farmer looked up by code and a repository to
 * put it in; neither belongs in {@code domain/}, and neither should force the replayer's
 * dispatch — the one place that knows what each event type means — out of the domain with it.
 */
@FunctionalInterface
public interface CollectionSink {

    /**
     * Records what each farmer delivered at this stop.
     *
     * @param stop            the stop being collected from
     * @param litresByFarmer  farmer code to litres; one row per farmer, which is the whole
     *                        reason a point and a farmer are different things
     * @param collectedAt     when the driver says it happened
     * @return total litres actually recorded, so the trip's load can be updated
     */
    BigDecimal record(TripStop stop, Map<String, BigDecimal> litresByFarmer, Instant collectedAt);
}
