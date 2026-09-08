package com.dairy.milkroute.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A trip as the driver's phone sees it: where to go, in what order, and what has been done.
 *
 * <p>The stop list here comes from {@code trip_stop}, which was snapshotted at creation — so
 * a plan republished mid-session cannot change what the phone is showing a driver who is
 * already halfway down the list.
 */
public record TripResponse(
        Long id,
        String status,
        LocalDate businessDate,
        String session,
        String tankerRegNo,
        String driverCode,
        int capacityLitres,
        BigDecimal litresOnBoard,
        int holdBudgetMinutes,
        Instant firstCollectionAt,
        Instant spoilageDeadlineAt,
        int currentSeq,
        int stopCount,
        List<Stop> stops) {

    /**
     * @param seq        visiting order
     * @param status     PENDING through COLLECTED, or SKIPPED / DEFERRED
     * @param skipReason why, when it was not collected from
     * @param farmers    who delivers here, because milk is recorded per farmer
     */
    public record Stop(
            int seq,
            String collectionPointCode,
            String villageName,
            String status,
            Instant plannedArrivalAt,
            Instant arrivedAt,
            BigDecimal plannedLitres,
            BigDecimal actualLitres,
            String skipReason,
            List<Farmer> farmers) {
    }

    public record Farmer(String code, String name) {
    }
}
