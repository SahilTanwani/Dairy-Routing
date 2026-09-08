package com.dairy.milkroute.enums;

/**
 * Fleet availability. Mirrors chk_tanker_status.
 *
 * <p>AVAILABLE is what the planner asks for; everything else takes a tanker out of the
 * pool for the session.
 */
public enum TankerStatus {
    AVAILABLE,
    ON_TRIP,
    MAINTENANCE,
    BREAKDOWN,
    RETIRED
}
