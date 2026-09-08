package com.dairy.milkroute.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * What a farmer is told when he asks where the tanker is.
 *
 * <p>The {@code message} field is the point. An operator on a phone, or an IVR reading this
 * aloud, must not have to assemble a sentence out of six fields — so the sentence is built
 * here, once, where the rules about what may be said are known.
 *
 * <p>The structured fields are still present for anything that wants to render it
 * differently, but they are the supporting cast.
 *
 * @param farmerCode        who asked
 * @param status            one of the nine
 * @param message           one plain sentence, safe to read out
 * @param collectionPoint   where his milk goes
 * @param etaAt             expected arrival; null whenever it is not safe to quote one
 * @param confidence        how much the ETA is worth
 * @param stopsAway         how many stops before his, when the tanker is on the way
 * @param collectedAt       when his milk was taken
 * @param litres            how much, his own only
 * @param reason            why not, when it was not
 * @param guaranteedBy      the date the three-strike rule promises him service by
 * @param nextExpectedSession the next run that should reach him
 * @param officeContact     for the cases where a person needs to be involved
 */
public record FarmerStatusResponse(
        String farmerCode,
        String status,
        String message,
        String collectionPoint,
        Instant etaAt,
        String confidence,
        Integer stopsAway,
        Instant collectedAt,
        BigDecimal litres,
        String reason,
        LocalDate guaranteedBy,
        String nextExpectedSession,
        String officeContact) {
}
