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
import java.time.Instant;

/**
 * One farmer's milk, at one stop, on one trip. Maps the {@code collection} table.
 *
 * <p>Named MilkCollection rather than Collection on purpose: an entity called Collection
 * shadows {@link java.util.Collection} in every repository signature and query that
 * mentions it, which is a trap for no benefit. The table keeps its own name.
 *
 * <p>One row per farmer per visit is how the shared-point problem resolves: a point with
 * two farmers gets one tanker stop and two of these.
 *
 * <p>Rows are voided, never deleted. This is money: a driver keying 125 where they meant
 * 12.5 gets a void plus a correction, and both survive for the audit.
 */
@Entity
@Table(name = "collection")
public class MilkCollection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_stop_id", nullable = false)
    private TripStop tripStop;

    /**
     * Denormalised: reachable through the trip stop, but the farmer-history query filters
     * by trip directly and should not have to join through it.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "farmer_id", nullable = false)
    private Farmer farmer;

    /** Constrained to 0-500 in the database, which catches a misplaced decimal point. */
    @Column(name = "litres", nullable = false, precision = 6, scale = 2)
    private BigDecimal litres;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Column(name = "voided", nullable = false)
    private boolean voided = false;

    @Column(name = "void_reason", length = 120)
    private String voidReason;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public TripStop getTripStop() {
        return tripStop;
    }

    public void setTripStop(TripStop tripStop) {
        this.tripStop = tripStop;
    }

    public Trip getTrip() {
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }

    public Farmer getFarmer() {
        return farmer;
    }

    public void setFarmer(Farmer farmer) {
        this.farmer = farmer;
    }

    public BigDecimal getLitres() {
        return litres;
    }

    public void setLitres(BigDecimal litres) {
        this.litres = litres;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public void setCollectedAt(Instant collectedAt) {
        this.collectedAt = collectedAt;
    }

    public boolean isVoided() {
        return voided;
    }

    public void setVoided(boolean voided) {
        this.voided = voided;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public void setVoidReason(String voidReason) {
        this.voidReason = voidReason;
    }
}
