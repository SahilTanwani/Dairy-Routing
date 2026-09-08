package com.dairy.milkroute.enums;

/**
 * Spoilage risk for a trip in flight. Mirrors chk_trip_risk.
 *
 * <p>Set by the monitor from the fraction of the hold budget consumed, and it only ever
 * ratchets upward within a trip: bacterial damage is cumulative, so a cooling afternoon
 * does not undo a hot morning.
 */
public enum RiskLevel {
    OK,
    WARNING,
    CRITICAL,
    LOST
}
