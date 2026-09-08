package com.dairy.milkroute.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Everything on the dispatcher's screen, in one call.
 *
 * <p>A facade because the alternative is four requests every two seconds for twenty-two
 * trips, and because a board assembled from four separately-timed responses can show a trip
 * as OK in one panel and CRITICAL in another. One call, one moment, one consistent picture.
 *
 * <p>Trips come back worst first. A board sorted by route label makes the dispatcher hunt
 * for the emergency.
 *
 * @param asOf         when this snapshot was taken
 * @param activeTrips  trips on the road, riskiest first
 * @param criticalCount open CRITICAL alerts
 * @param warningCount  open WARNING alerts
 * @param alerts        the open alerts themselves, newest first
 */
public record OpsBoardResponse(
        Instant asOf,
        List<TripCard> activeTrips,
        long criticalCount,
        long warningCount,
        List<AlertCard> alerts) {

    /**
     * @param riskLevel      OK, WARNING, CRITICAL or LOST
     * @param minutesToSpoil budget left; negative once the deadline has passed
     * @param etaConfidence  whether the ETA is worth reading
     */
    public record TripCard(
            Long tripId,
            String routeLabel,
            String tankerRegNo,
            boolean insulated,
            String driverCode,
            String status,
            String riskLevel,
            int currentSeq,
            int stopCount,
            int stopsCollected,
            BigDecimal litresOnBoard,
            Instant spoilageDeadlineAt,
            Instant etaPlantAt,
            String etaConfidence,
            Long minutesToSpoil,
            Instant lastPingAt) {
    }

    public record AlertCard(
            Long id,
            Long tripId,
            String type,
            String severity,
            String message,
            Instant raisedAt) {
    }
}
