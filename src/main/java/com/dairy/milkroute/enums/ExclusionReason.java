package com.dairy.milkroute.enums;

/**
 * Why a collection point was left out of a plan.
 *
 * <p>UNREACHABLE_WITHIN_HOLD means no tanker could get there and back before the milk
 * spoils, which is a fact about the geography. COVERAGE_LIMIT means it could have been
 * served but lost the scoring contest, which is a decision we made and must be able to
 * defend.
 */
public enum ExclusionReason {
    UNREACHABLE_WITHIN_HOLD,
    COVERAGE_LIMIT,
    EXCEEDS_ALL_CAPACITY,
    POINT_INACTIVE
}
