package com.dairy.milkroute.enums;

/**
 * How much to trust an ETA, which decides the words a farmer hears.
 *
 * <p>HIGH quotes a time, MEDIUM quotes a window, LOW quotes a last-known position, and
 * LOST admits we have lost contact. Quote a farmer 6:41 and arrive at 7:15 and he will
 * never believe another number, so the confidence level is part of the answer.
 *
 * <p>UNKNOWN is the state before the first ping of a trip.
 */
public enum EtaConfidence {
    HIGH,
    MEDIUM,
    LOW,
    LOST,
    UNKNOWN
}
