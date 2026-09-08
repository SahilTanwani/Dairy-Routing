package com.dairy.milkroute.enums;

/**
 * Why a stop was not collected from. Mirrors chk_ts_skip.
 *
 * <p>The reason is what lets ops answer a farmer who rings to ask why the tanker did not
 * come, so an unexplained skip is not an acceptable state.
 */
public enum SkipReason {
    NO_MILK,
    FARMER_ABSENT,
    ROAD_BLOCKED,
    TANKER_FULL,
    SPOILAGE_ABORT,
    SEQUENCE_SKIP,
    COVERAGE_LIMIT,
    POINT_INACTIVE
}
