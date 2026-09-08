package com.dairy.milkroute.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * One planned visit to one collection point, in sequence.
 *
 * <p>{@code legMinutes} and {@code legKm} are the cost of getting here from the previous
 * stop. Holding them per stop means a route can be redrawn and its ETAs recomputed without
 * rebuilding the travel matrix.
 */
@Entity
@Table(name = "route_stop")
public class RouteStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    @Column(name = "seq", nullable = false)
    private int seq;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "collection_point_id", nullable = false)
    private CollectionPoint collectionPoint;

    /** A clock time, not an instant: the plan is a daily shape, not a dated one. */
    @Column(name = "planned_arrival_at", nullable = false)
    private LocalTime plannedArrivalAt;

    @Column(name = "planned_litres", nullable = false, precision = 7, scale = 2)
    private BigDecimal plannedLitres;

    @Column(name = "leg_minutes", nullable = false)
    private int legMinutes;

    @Column(name = "leg_km", nullable = false, precision = 6, scale = 2)
    private BigDecimal legKm;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Route getRoute() {
        return route;
    }

    public void setRoute(Route route) {
        this.route = route;
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

    public LocalTime getPlannedArrivalAt() {
        return plannedArrivalAt;
    }

    public void setPlannedArrivalAt(LocalTime plannedArrivalAt) {
        this.plannedArrivalAt = plannedArrivalAt;
    }

    public BigDecimal getPlannedLitres() {
        return plannedLitres;
    }

    public void setPlannedLitres(BigDecimal plannedLitres) {
        this.plannedLitres = plannedLitres;
    }

    public int getLegMinutes() {
        return legMinutes;
    }

    public void setLegMinutes(int legMinutes) {
        this.legMinutes = legMinutes;
    }

    public BigDecimal getLegKm() {
        return legKm;
    }

    public void setLegKm(BigDecimal legKm) {
        this.legKm = legKm;
    }
}
