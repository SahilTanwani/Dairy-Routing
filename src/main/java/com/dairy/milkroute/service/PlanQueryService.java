package com.dairy.milkroute.service;

import com.dairy.milkroute.dto.response.ExclusionResponse;
import com.dairy.milkroute.dto.response.PlanResponse;
import com.dairy.milkroute.entity.PlanExclusion;
import com.dairy.milkroute.entity.Route;
import com.dairy.milkroute.entity.RoutePlan;
import com.dairy.milkroute.enums.PlanStatus;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.error.PlanNotFoundException;
import com.dairy.milkroute.repository.PlanExclusionRepository;
import com.dairy.milkroute.repository.RoutePlanRepository;
import com.dairy.milkroute.repository.RouteRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads plans back out for the API.
 *
 * <p>Separate from {@link PlanningService} because the two have nothing in common but a
 * table. One assembles a solver and writes rows; this one loads rows and shapes them. Wiring
 * both into a single class would mean every read carried the solver's dependencies with it.
 */
@Service
public class PlanQueryService {

    private final RoutePlanRepository planRepo;
    private final RouteRepository routeRepo;
    private final PlanExclusionRepository exclusionRepo;

    public PlanQueryService(RoutePlanRepository planRepo,
                            RouteRepository routeRepo,
                            PlanExclusionRepository exclusionRepo) {
        this.planRepo = planRepo;
        this.routeRepo = routeRepo;
        this.exclusionRepo = exclusionRepo;
    }

    @Transactional(readOnly = true)
    public PlanResponse byId(long planId) {
        return toResponse(planRepo.findById(planId)
                .orElseThrow(() -> new PlanNotFoundException("no plan with id " + planId)));
    }

    @Transactional(readOnly = true)
    public PlanResponse published(Session session) {
        return toResponse(planRepo.findBySessionAndStatus(session, PlanStatus.PUBLISHED)
                .orElseThrow(() -> new PlanNotFoundException(
                        "no published plan for the %s session".formatted(session))));
    }

    @Transactional(readOnly = true)
    public String feasibility(long planId) {
        RoutePlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new PlanNotFoundException("no plan with id " + planId));
        // Stored as JSON and returned as JSON. The report's shape will keep changing and it
        // is read as a whole; re-modelling it here would mean editing three places every time
        // it grows a field.
        return plan.getFeasibility();
    }

    @Transactional(readOnly = true)
    public ExclusionResponse exclusions(long planId) {
        if (!planRepo.existsById(planId)) {
            throw new PlanNotFoundException("no plan with id " + planId);
        }

        List<PlanExclusion> rows = exclusionRepo.findByPlanIdOrderByScoreDesc(planId);
        BigDecimal forgone = rows.stream()
                .map(row -> row.getLitresForgone() == null ? BigDecimal.ZERO : row.getLitresForgone())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ExclusionResponse(
                planId,
                rows.size(),
                forgone,
                rows.stream().map(row -> new ExclusionResponse.Exclusion(
                        row.getCollectionPoint().getCode(),
                        row.getCollectionPoint().getVillage().getCode(),
                        row.getReason().name(),
                        row.getDetail(),
                        row.getLitresForgone())).toList());
    }

    private PlanResponse toResponse(RoutePlan plan) {
        List<Route> routes = routeRepo.findByPlanIdOrderBySlackMinutesAsc(plan.getId());

        return new PlanResponse(
                plan.getId(),
                plan.getVersion(),
                plan.getSession().name(),
                plan.getMode().name(),
                plan.getStatus().name(),
                plan.getPlannedTempC(),
                plan.getEffectiveFrom(),
                plan.getGeneratedAt(),
                plan.getGenerationMs(),
                routes.size(),
                routes.stream().mapToInt(Route::getStopCount).sum(),
                routes.stream().map(Route::getEstVolumeLitres)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                routes.stream().map(Route::getSlackMinutes).min(Comparator.naturalOrder())
                        .orElse(null),
                routes.stream().map(PlanQueryService::toRouteResponse).toList());
    }

    private static PlanResponse.RouteResponse toRouteResponse(Route route) {
        return new PlanResponse.RouteResponse(
                route.getId(),
                route.getLabel(),
                route.getTanker() == null ? null : route.getTanker().getRegNo(),
                route.getTanker() != null && route.getTanker().isInsulated(),
                route.getDriver() == null ? null : route.getDriver().getCode(),
                route.getPlannedDepartAt(),
                route.getEstHotMinutes(),
                route.getHoldBudgetMinutes(),
                route.getSlackMinutes(),
                route.getEstVolumeLitres(),
                route.getEstDistanceKm(),
                route.getStopCount());
    }
}
