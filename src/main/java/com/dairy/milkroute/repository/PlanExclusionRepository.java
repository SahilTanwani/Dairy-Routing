package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.PlanExclusion;
import com.dairy.milkroute.enums.ExclusionReason;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanExclusionRepository extends JpaRepository<PlanExclusion, Long> {

    /** Who a plan left out, worst score first: the answer ops gives a farmer who rings. */
    List<PlanExclusion> findByPlanIdOrderByScoreDesc(Long planId);

    List<PlanExclusion> findByPlanIdAndReason(Long planId, ExclusionReason reason);

    long countByPlanId(Long planId);
}
