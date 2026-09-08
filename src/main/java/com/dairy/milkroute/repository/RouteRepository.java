package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.Route;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteRepository extends JpaRepository<Route, Long> {

    List<Route> findByPlanIdOrderByLabelAsc(Long planId);

    /** Ops board ordering: the thinnest slack is the route most likely to lose milk. */
    List<Route> findByPlanIdOrderBySlackMinutesAsc(Long planId);

    long countByPlanId(Long planId);
}
