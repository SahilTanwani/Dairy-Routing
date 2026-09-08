package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.RoutePlan;
import com.dairy.milkroute.enums.PlanStatus;
import com.dairy.milkroute.enums.Session;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutePlanRepository extends JpaRepository<RoutePlan, Long> {

    /**
     * The plan trip creation runs from. Optional, not List, because uq_one_published_per_session
     * makes more than one structurally impossible: the database guarantees what this
     * signature claims.
     */
    Optional<RoutePlan> findBySessionAndStatus(Session session, PlanStatus status);

    List<RoutePlan> findBySessionOrderByVersionDesc(Session session);

    /** Next version number for a session; the planner increments from here. */
    Optional<RoutePlan> findFirstBySessionOrderByVersionDesc(Session session);
}
