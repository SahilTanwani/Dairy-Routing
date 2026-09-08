package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.RouteStop;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteStopRepository extends JpaRepository<RouteStop, Long> {

    /** In sequence: this is what trip creation snapshots into trip_stop. */
    List<RouteStop> findByRouteIdOrderBySeqAsc(Long routeId);

    List<RouteStop> findByRouteIdInOrderByRouteIdAscSeqAsc(List<Long> routeIds);
}
