package com.dairy.milkroute.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Who a plan left out, and why.
 *
 * <p>The endpoint exists so that "why did no tanker come?" has an answer other than a
 * shrug. A dairy that cannot serve everyone on a hot evening is a fact; a dairy that cannot
 * say who it failed to serve is a different and worse problem.
 *
 * @param planId          the plan
 * @param excludedPoints  how many points were left out
 * @param litresForgone   milk left in farmers' cans
 * @param exclusions      one row per point
 */
public record ExclusionResponse(
        Long planId,
        int excludedPoints,
        BigDecimal litresForgone,
        List<Exclusion> exclusions) {

    /**
     * @param collectionPointCode the point
     * @param villageCode         its village
     * @param reason              COVERAGE_LIMIT when it lost the ranking,
     *                            UNREACHABLE_WITHIN_HOLD when no tanker could ever reach it
     * @param detail              a sentence ops can read to a farmer
     * @param litresForgone       what it cost
     */
    public record Exclusion(
            String collectionPointCode,
            String villageCode,
            String reason,
            String detail,
            BigDecimal litresForgone) {
    }
}
