package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.PlanExclusion;
import com.dairy.milkroute.enums.ExclusionReason;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlanExclusionRepository extends JpaRepository<PlanExclusion, Long> {

    /** Who a plan left out, worst score first: the answer ops gives a farmer who rings. */
    List<PlanExclusion> findByPlanIdOrderByScoreDesc(Long planId);

    List<PlanExclusion> findByPlanIdAndReason(Long planId, ExclusionReason reason);

    long countByPlanId(Long planId);

    /**
     * Why this point was left out of the plans for a given date, newest plan first.
     *
     * <p>Returns the reasons rather than the rows. The farmer endpoint needs one word, and
     * loading exclusion entities to get it drags a plan and a collection point along with
     * each one — a farmer asking a question should not cost a join per excluded point in the
     * dairy.
     */
    @Query("""
            SELECT e.reason FROM PlanExclusion e
            WHERE e.collectionPoint.id = :collectionPointId
              AND e.plan.effectiveFrom = :effectiveFrom
            ORDER BY e.plan.id DESC
            """)
    List<ExclusionReason> findReasonsFor(@Param("collectionPointId") Long collectionPointId,
                                         @Param("effectiveFrom") LocalDate effectiveFrom);
}
