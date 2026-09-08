package com.dairy.milkroute.enums;

/**
 * The eleven things a driver's phone can report. Mirrors chk_event_type.
 *
 * <p>Events drive trip progress; pings only refine position. The replayer applies these in
 * client_ts order, which is not necessarily the order they arrived in.
 */
public enum EventType {
    TRIP_STARTED,
    ARRIVED_AT_STOP,
    COLLECTED,
    DEPARTED_STOP,
    STOP_SKIPPED,
    TANKER_FULL,
    BREAKDOWN,
    ARRIVED_AT_PLANT,
    UNLOADED,
    TRIP_ABORTED,
    ROUTE_DIVERTED
}
