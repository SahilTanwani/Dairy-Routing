package com.dairy.milkroute.enums;

/**
 * Per-stop progress within a trip. Mirrors chk_ts_status.
 *
 * <p>Only a driver event moves a stop to ARRIVED or COLLECTED. A GPS ping that happens to
 * pass within a few metres of a point does not: driving past a point is not collecting
 * from it.
 */
public enum TripStopStatus {
    PENDING,
    EN_ROUTE,
    ARRIVED,
    COLLECTED,
    SKIPPED,
    DEFERRED
}
