package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.DriverEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Inserts an event unless its client_event_id is already present, and says which
     * happened: 1 for a new event, 0 for one the server already holds.
     *
     * <p>ON CONFLICT rather than catching the constraint violation. Both let the unique
     * index be the arbiter, but Postgres aborts a transaction on a failed statement, so a
     * caught violation leaves the batch unable to continue — the twenty-nine good events
     * behind the duplicate would be lost with it. Letting the database absorb the conflict
     * keeps the guarantee, keeps the transaction usable, and stays correct if two copies of
     * the same batch arrive at once.
     */
    @Modifying
    @Query(value = """
            INSERT INTO driver_event
                (client_event_id, trip_id, trip_stop_id, event_type, client_ts, server_ts, payload)
            VALUES
                (:clientEventId, :tripId, :tripStopId, :eventType, :clientTs, :serverTs,
                 CAST(:payload AS jsonb))
            ON CONFLICT (client_event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("clientEventId") UUID clientEventId,
                       @Param("tripId") Long tripId,
                       @Param("tripStopId") Long tripStopId,
                       @Param("eventType") String eventType,
                       @Param("clientTs") Instant clientTs,
                       @Param("serverTs") Instant serverTs,
                       @Param("payload") String payload);
}
