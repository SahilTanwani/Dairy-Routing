package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.DriverEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverEventRepository extends JpaRepository<DriverEvent, Long> {

    /** Replay order: what the driver did, not the order we happened to hear about it. */
    List<DriverEvent> findByTripIdOrderByClientTsAsc(Long tripId);

    /**
     * The cheap half of idempotency. The unique index on client_event_id is the actual
     * guarantee and ingestion still catches its violation, because between this check and
     * the insert a second copy of the same batch can arrive.
     */
    boolean existsByClientEventId(UUID clientEventId);

    List<DriverEvent> findByClientEventIdIn(List<UUID> clientEventIds);
}
