package com.dairy.milkroute.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * A plan as the ops board sees it.
 *
 * @param id            plan id
 * @param version       version within its session
 * @param session       MORNING or EVENING
 * @param mode          FULL_SERVICE, or COVERAGE_OPTIMISATION when the fleet cannot cover
 *                      the dairy at this temperature
 * @param status        DRAFT, PUBLISHED or ARCHIVED
 * @param plannedTempC  the temperature the plan was built against
 * @param effectiveFrom the date it is for
 * @param generatedAt   when it was produced
 * @param generationMs  how long the solver took
 * @param routeCount    routes in the plan
 * @param stopCount     collection points served across those routes
 * @param litres        expected volume
 * @param minSlackMin   the thinnest margin on any route: the number that says whether this
 *                      plan survives a bad morning
 * @param routes        the routes themselves, riskiest first
 */
public record PlanResponse(
        Long id,
        int version,
        String session,
        String mode,
        String status,
        BigDecimal plannedTempC,
        LocalDate effectiveFrom,
        Instant generatedAt,
        Integer generationMs,
        int routeCount,
        int stopCount,
        BigDecimal litres,
        Integer minSlackMin,
        List<RouteResponse> routes) {

    /**
     * One route.
     *
     * @param label         R-01 upward, riskiest first
     * @param tankerRegNo   the tanker assigned
     * @param insulated     whether it is insulated, which is most of why it got this route
     * @param driverCode    the driver, if one was rostered
     * @param departAt      when it leaves the plant
     * @param estHotMin     how old the oldest milk will be on arrival
     * @param holdBudgetMin what the milk can survive at this temperature
     * @param slackMin      budget minus hot time
     * @param litres        expected volume
     * @param distanceKm    road distance
     * @param stopCount     points on the route
     */
    public record RouteResponse(
            Long id,
            String label,
            String tankerRegNo,
            boolean insulated,
            String driverCode,
            LocalTime departAt,
            int estHotMin,
            int holdBudgetMin,
            int slackMin,
            BigDecimal litres,
            BigDecimal distanceKm,
            int stopCount) {
    }
}
