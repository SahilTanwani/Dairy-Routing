package com.dairy.milkroute.service;

import com.dairy.milkroute.domain.routing.PlanResult;
import com.dairy.milkroute.entity.RoutePlan;
import com.dairy.milkroute.enums.PlanStatus;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.error.PlanConflictException;
import com.dairy.milkroute.error.PlanNotFoundException;
import com.dairy.milkroute.repository.RoutePlanRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes a draft plan the one the fleet actually runs.
 *
 * <p>At most one plan per session may be published, and that is enforced by
 * {@code uq_one_published_per_session} in Postgres rather than by a check in this class.
 * The distinction matters: a check here would be a race between two dispatchers clicking
 * publish at the same moment, and the losing write would still land. The partial unique
 * index means the second insert simply cannot exist, and the job of this class is to turn
 * that database refusal into a 409 a caller can understand.
 *
 * <p>Publishing is also where the coverage counters move and the exclusion rows get written.
 * On publish rather than on generate, because drafts are produced, compared and thrown away
 * during a normal morning — treating a discarded draft as a served session would tell the
 * system a village had its milk collected when no tanker ever left the yard.
 */
@Service
public class PlanPublicationService {

    @PersistenceContext
    private EntityManager entityManager;

    private final RoutePlanRepository planRepo;
    private final CoverageStateService coverageState;
    private final PlanningService planningService;

    public PlanPublicationService(RoutePlanRepository planRepo,
                                  CoverageStateService coverageState,
                                  PlanningService planningService) {
        this.planRepo = planRepo;
        this.coverageState = coverageState;
        this.planningService = planningService;
    }

    /**
     * Publishes a draft.
     *
     * @param planId  the draft to publish
     * @param replace archive whatever is currently published for this session first. Off by
     *                default: the constraint refusing a second publish is the safe outcome,
     *                and replacing a live plan while tankers may already be running against
     *                it should take an explicit decision rather than happening quietly.
     */
    @Transactional
    public RoutePlan publish(long planId, boolean replace) {
        RoutePlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new PlanNotFoundException("no plan with id " + planId));

        // Publishing something already published is a no-op, not an error. A double-clicked
        // button and a retried request should both leave the same one plan live.
        if (plan.getStatus() == PlanStatus.PUBLISHED) {
            return plan;
        }
        if (plan.getStatus() == PlanStatus.ARCHIVED) {
            throw new PlanConflictException(
                    "plan %d is archived and cannot be published again".formatted(planId));
        }

        if (replace) {
            archiveCurrent(plan.getSession());
        }

        plan.setStatus(PlanStatus.PUBLISHED);
        try {
            // Forced now rather than at commit, so the constraint violation surfaces here
            // where it can be translated instead of as an opaque failure on the way out.
            entityManager.flush();
        } catch (DataIntegrityViolationException | jakarta.persistence.PersistenceException e) {
            throw new PlanConflictException(
                    ("a plan is already published for the %s session. Archive it first, or "
                            + "publish with replace=true.").formatted(plan.getSession()), e);
        }

        recordCoverage(plan);
        return plan;
    }

    private void archiveCurrent(Session session) {
        planRepo.findBySessionAndStatus(session, PlanStatus.PUBLISHED)
                .ifPresent(live -> {
                    live.setStatus(PlanStatus.ARCHIVED);
                    entityManager.flush();
                });
    }

    /**
     * Re-runs the plan in memory to recover which points it serves, then writes the coverage
     * counters and the exclusion rows.
     *
     * <p>Re-planning rather than reading the stops back is deliberate: the exclusion rows
     * need the points that were <em>left out</em>, and those are precisely the ones the
     * database has no row for. The run is deterministic against the same inputs, so it
     * reproduces the same plan.
     */
    private void recordCoverage(RoutePlan plan) {
        PlanResult result = planningService.planWithoutPersisting(
                plan.getSession(),
                plan.getEffectiveFrom(),
                firstDepartureOf(plan),
                plan.getPlannedTempC().doubleValue());

        coverageState.recordPublishedPlan(plan.getEffectiveFrom(), plan.getSession(), result);
        coverageState.recordExclusions(plan, result, plan.getSession());
    }

    /**
     * The departure time the plan was built against, read back from its own first route.
     *
     * <p>Every route in a plan departs together, so any of them answers. A plan with no
     * routes at all could not serve anyone, and re-planning it changes nothing.
     */
    private java.time.LocalTime firstDepartureOf(RoutePlan plan) {
        return planRepo.findById(plan.getId())
                .flatMap(p -> entityManager
                        .createQuery("select r.plannedDepartAt from Route r "
                                + "where r.plan.id = :planId order by r.label",
                                java.time.LocalTime.class)
                        .setParameter("planId", plan.getId())
                        .setMaxResults(1)
                        .getResultStream()
                        .findFirst())
                .orElse(java.time.LocalTime.of(5, 0));
    }
}
