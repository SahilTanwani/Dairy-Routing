package com.dairy.milkroute.entity;

import com.dairy.milkroute.enums.SkipReason;
import com.dairy.milkroute.enums.TripStopStatus;
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
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A stop as the trip sees it: a snapshot of the route stop, taken at trip creation.
 *
 * <p>The copying is the whole point. If ops publishes a new plan at 05:30 while the fleet
 * is on the road, nothing changes for a driver halfway through their list. Without the
 * snapshot, a mid-session republish rewrites the route under a moving tanker.
 *
 * <p>Times are Instants here, not the plan's clock times: a trip happens on a date.
 */
@Entity
@Table(name = "trip_stop")
public class TripStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    /** Provenance only, and nullable: a stop added by a mitigation has no plan behind it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_stop_id")
    private RouteStop routeStop;

    @Column(name = "seq", nullable = false)
    private int seq;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "collection_point_id", nullable = false)
    private CollectionPoint collectionPoint;

    @Column(name = "planned_arrival_at", nullable = false)
    private Instant plannedArrivalAt;

    @Column(name = "planned_litres", nullable = false, precision = 7, scale = 2)
    private BigDecimal plannedLitres;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TripStopStatus status = TripStopStatus.PENDING;

    @Column(name = "eta_at")
    private Instant etaAt;

    /** Set by a driver event, never by a ping. Driving past a point is not arriving at it. */
    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "departed_at")
    private Instant departedAt;

    @Column(name = "actual_litres", precision = 7, scale = 2)
    private BigDecimal actualLitres;

    @Enumerated(EnumType.STRING)
    @Column(name = "skip_reason", length = 32)
    private SkipReason skipReason;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Trip getTrip() {
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }

    public RouteStop getRouteStop() {
        return routeStop;
    }

    public void setRouteStop(RouteStop routeStop) {
        this.routeStop = routeStop;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public CollectionPoint getCollectionPoint() {
        return collectionPoint;
    }

    public void setCollectionPoint(CollectionPoint collectionPoint) {
        this.collectionPoint = collectionPoint;
    }

    public Instant getPlannedArrivalAt() {
        return plannedArrivalAt;
    }

    public void setPlannedArrivalAt(Instant plannedArrivalAt) {
        this.plannedArrivalAt = plannedArrivalAt;
    }

    public BigDecimal getPlannedLitres() {
        return plannedLitres;
    }

    public void setPlannedLitres(BigDecimal plannedLitres) {
        this.plannedLitres = plannedLitres;
    }

    public TripStopStatus getStatus() {
        return status;
    }

    public void setStatus(TripStopStatus status) {
        this.status = status;
    }

    public Instant getEtaAt() {
        return etaAt;
    }

    public void setEtaAt(Instant etaAt) {
        this.etaAt = etaAt;
    }

    public Instant getArrivedAt() {
        return arrivedAt;
    }

    public void setArrivedAt(Instant arrivedAt) {
        this.arrivedAt = arrivedAt;
    }

    public Instant getDepartedAt() {
        return departedAt;
    }

    public void setDepartedAt(Instant departedAt) {
        this.departedAt = departedAt;
    }

    public BigDecimal getActualLitres() {
        return actualLitres;
    }

    public void setActualLitres(BigDecimal actualLitres) {
        this.actualLitres = actualLitres;
    }

    public SkipReason getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(SkipReason skipReason) {
        this.skipReason = skipReason;
    }
}
