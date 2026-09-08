package com.dairy.milkroute.enums;

/**
 * What a farmer is told when he asks where the tanker is.
 *
 * <p>Nine of them, because the honest answers to that question genuinely differ. Collapsing
 * "not started yet" and "we are not coming today" into one "no tanker" would save a few
 * lines here and cost a farmer a morning standing next to his cans.
 */
public enum FarmerStatus {

    /** No trip covers this point today, and none was planned. */
    NOT_SCHEDULED,

    /** A trip exists and has not left the plant. */
    SCHEDULED,

    /** On its way, with stops still between here and there. */
    EN_ROUTE,

    /** The next stop. Worth its own status: this is the one people wait outside for. */
    ARRIVING_NEXT,

    /** Done, with the litres recorded against this farmer. */
    COLLECTED,

    /** The tanker came and did not collect — with the reason. */
    SKIPPED,

    /** Pushed to a later run, usually because the tanker filled up. */
    DEFERRED,

    /** Left out of the plan itself, with the reason and the date it is guaranteed by. */
    NOT_SERVED_TODAY,

    /** The trip was abandoned. Never leave a stale ETA for a tanker that is not coming. */
    TRIP_ABORTED
}
