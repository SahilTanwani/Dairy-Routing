package com.dairy.milkroute.service;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.domain.routing.PlanResult;
import com.dairy.milkroute.domain.routing.PointCoverage;
import com.dairy.milkroute.entity.CollectionPoint;
import com.dairy.milkroute.entity.PlanExclusion;
import com.dairy.milkroute.entity.PointCoverageState;
import com.dairy.milkroute.entity.RoutePlan;
import com.dairy.milkroute.enums.ExclusionReason;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.repository.CollectionPointRepository;
import com.dairy.milkroute.repository.PlanExclusionRepository;
import com.dairy.milkroute.repository.PointCoverageStateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The memory that makes the equity term mean anything.
 *
 * <p>The planner's ranking asks how long each point has gone unserved. Without somebody
 * writing that down after every published plan, the answer is always "no idea", the equity
 * term is always 1, and the ranking collapses back to pure efficiency — which is the exact
 * behaviour it exists to prevent. This class is the other half of the fairness rule, and it
 * is the unglamorous half.
 *
 * <p>Counters move on <strong>publish</strong>, not on generate. Draft plans are produced,
 * compared and discarded during a normal morning; treating a discarded draft as a served
 * session would tell the system a village had its milk collected when no tanker ever left.
 */
@Service
public class CoverageStateService {

    private final PointCoverageStateRepository coverageRepo;
    private final CollectionPointRepository pointRepo;
    private final PlanExclusionRepository exclusionRepo;
    private final ClockProvider clock;

    public CoverageStateService(PointCoverageStateRepository coverageRepo,
                                CollectionPointRepository pointRepo,
                                PlanExclusionRepository exclusionRepo,
                                ClockProvider clock) {
        this.coverageRepo = coverageRepo;
        this.pointRepo = pointRepo;
        this.exclusionRepo = exclusionRepo;
        this.clock = clock;
    }

    /**
     * Service history for every active point, shaped for {@code PlanningContext}.
     *
     * <p>A point with no row is simply absent from the map. The planner reads that as never
     * served and ranks it ahead of anything with a history, which is right: a point the
     * dairy has never reached has a stronger claim than one missed for three days.
     */
    @Transactional(readOnly = true)
    public Map<Long, PointCoverage> coverageAsOf(LocalDate businessDate) {
        Map<Long, PointCoverage> byPointId = new HashMap<>();

        for (PointCoverageState state : coverageRepo.findAll()) {
            LocalDate lastServed = state.getLastServedDate();
            byPointId.put(state.getCollectionPointId(),
                    lastServed == null
                            ? PointCoverage.neverServed(state.getConsecutiveSkips())
                            : PointCoverage.servedDaysAgo(
                                    state.getConsecutiveSkips(),
                                    ChronoUnit.DAYS.between(lastServed, businessDate)));
        }
        return byPointId;
    }

    /**
     * Records what a published plan did to every active point: served points have their
     * skip counter cleared, everything else has it incremented.
     *
     * <p>Incrementing the ones that were left out is the half that matters. A point skipped
     * three sessions running becomes mandatory in the next plan and stops competing on
     * efficiency at all, which is the guarantee that no village can be neglected
     * indefinitely just for being far away.
     */
    @Transactional
    public void recordPublishedPlan(LocalDate businessDate, Session session, PlanResult result) {
        Set<Long> servedPointIds = servedPointIds(result);

        List<CollectionPoint> activePoints = pointRepo.findByActiveTrueOrderByCodeAsc();
        Map<Long, PointCoverageState> existing = new HashMap<>();
        for (PointCoverageState state : coverageRepo.findByCollectionPointIdIn(
                activePoints.stream().map(CollectionPoint::getId).toList())) {
            existing.put(state.getCollectionPointId(), state);
        }

        List<PointCoverageState> toSave = new ArrayList<>(activePoints.size());
        for (CollectionPoint point : activePoints) {
            PointCoverageState state = existing.get(point.getId());
            if (state == null) {
                state = new PointCoverageState();
                state.setCollectionPoint(point);
                state.setConsecutiveSkips(0);
            }

            if (servedPointIds.contains(point.getId())) {
                state.setConsecutiveSkips(0);
                state.setLastServedDate(businessDate);
                state.setLastServedSession(session);
            } else {
                state.setConsecutiveSkips(state.getConsecutiveSkips() + 1);
            }

            state.setUpdatedAt(clock.now());
            toSave.add(state);
        }

        coverageRepo.saveAll(toSave);
    }

    /**
     * One exclusion row per point the plan could not serve, with the litres it forgoes.
     *
     * <p>This is what ops reads back when a farmer rings to ask why nobody came. "The plan
     * could not reach you" is not an answer; "you were excluded at the coverage limit and it
     * cost 48 litres" is one, and it is also the record that makes the three-strike rule
     * auditable rather than a claim.
     *
     * <p>Every row here is COVERAGE_LIMIT: the point could have been served and lost the
     * ranking. Points that no tanker could ever reach are a different finding
     * ({@code UNREACHABLE_WITHIN_HOLD}) and are not what this loop produces.
     */
    @Transactional
    public int recordExclusions(RoutePlan plan, PlanResult result, Session session) {
        List<PlanExclusion> exclusions = new ArrayList<>();

        for (var block : result.unservedBlocks()) {
            for (CollectionPoint point : block.sequence()) {
                PlanExclusion exclusion = new PlanExclusion();
                exclusion.setPlan(plan);
                exclusion.setCollectionPoint(point);
                exclusion.setReason(ExclusionReason.COVERAGE_LIMIT);
                exclusion.setDetail(
                        "Ranked below the fleet's capacity for this session; village %s"
                                .formatted(block.village().getCode()));
                exclusion.setLitresForgone(litresFor(point, session));
                exclusions.add(exclusion);
            }
        }

        exclusionRepo.saveAll(exclusions);
        return exclusions.size();
    }

    private static Set<Long> servedPointIds(PlanResult result) {
        Set<Long> served = new HashSet<>();
        result.routes().forEach(route ->
                route.route().stops().forEach(stop -> served.add(stop.getId())));
        return served;
    }

    private static BigDecimal litresFor(CollectionPoint point, Session session) {
        return session == Session.MORNING
                ? point.getAvgMorningLitres()
                : point.getAvgEveningLitres();
    }

    /** Unused today, kept because the monitor will want a wall-clock date in T8.8. */
    LocalDate today() {
        return clock.now().atZone(ZoneOffset.UTC).toLocalDate();
    }
}
