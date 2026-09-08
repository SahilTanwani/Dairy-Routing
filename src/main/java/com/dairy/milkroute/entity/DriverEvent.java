package com.dairy.milkroute.entity;

import com.dairy.milkroute.enums.EventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Something a driver's phone reported. Events drive trip progress; pings only refine
 * position.
 *
 * <p>{@code clientEventId} is stamped by the phone before the record is ever sent, and the
 * unique index on it is the entire offline story: when signal returns, the phone replays
 * its whole backlog because it cannot know what got through, and the database rejects
 * whatever it already has. No acknowledgement protocol, no duplicate milk, no lost data.
 */
@Entity
@Table(name = "driver_event")
public class DriverEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_event_id", nullable = false, unique = true)
    private UUID clientEventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    /** Null for events about the trip as a whole: TRIP_STARTED, BREAKDOWN, UNLOADED. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_stop_id")
    private TripStop tripStop;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private EventType eventType;

    /** When it happened on the driver's phone. Replay order is by this, not by arrival. */
    @Column(name = "client_ts", nullable = false)
    private Instant clientTs;

    /** When we heard about it. A forty-minute gap from clientTs is a dead zone, not a bug. */
    @Column(name = "server_ts", nullable = false)
    private Instant serverTs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload")
    private String payload;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getClientEventId() {
        return clientEventId;
    }

    public void setClientEventId(UUID clientEventId) {
        this.clientEventId = clientEventId;
    }

    public Trip getTrip() {
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }

    public TripStop getTripStop() {
        return tripStop;
    }

    public void setTripStop(TripStop tripStop) {
        this.tripStop = tripStop;
    }

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public Instant getClientTs() {
        return clientTs;
    }

    public void setClientTs(Instant clientTs) {
        this.clientTs = clientTs;
    }

    public Instant getServerTs() {
        return serverTs;
    }

    public void setServerTs(Instant serverTs) {
        this.serverTs = serverTs;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}
