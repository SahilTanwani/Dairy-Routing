package com.dairy.milkroute.enums;

/**
 * Where a trip is in its life. Mirrors chk_trip_status.
 *
 * <p>BLOCKED is the honest outcome when trip creation cannot find an available tanker or
 * driver: the trip exists and says so, rather than silently not being created.
 */
public enum TripStatus {
    SCHEDULED,
    IN_PROGRESS,
    RETURNING,
    AT_PLANT,
    COMPLETED,
    ABORTED,
    BREAKDOWN,
    BLOCKED
}
