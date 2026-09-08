package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.enums.TripStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripRepository extends JpaRepository<Trip, Long> {

    /** What the spoilage monitor sweeps and the ops board renders. */
    List<Trip> findByStatusIn(List<TripStatus> statuses);

    List<Trip> findByBusinessDateAndSession(LocalDate businessDate, Session session);

    /**
     * Backs UNIQUE (route_id, business_date, session), which is what makes running the
     * trip-creation job twice harmless.
     */
    Optional<Trip> findByRouteIdAndBusinessDateAndSession(
            Long routeId, LocalDate businessDate, Session session);

    /** A driver's trip for the session: the driver app's first call of the day. */
    Optional<Trip> findByDriverIdAndBusinessDateAndSession(
            Long driverId, LocalDate businessDate, Session session);

    long countByBusinessDateAndSession(LocalDate businessDate, Session session);
}
