package com.dairy.milkroute.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * What the weighbridge actually received: one row per trip, the ground truth.
 *
 * <p>{@code oldestMilkMin} is the payoff. Plot rejection rate against milk age over a few
 * hundred sessions and you find out whether 180 minutes at 30 C should really be 165 or
 * 195. That is the loop that lets the spoilage model improve instead of staying a guess.
 */
@Entity
@Table(name = "intake_record")
public class IntakeRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false, unique = true)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plant_id", nullable = false)
    private Plant plant;

    @Column(name = "arrived_at", nullable = false)
    private Instant arrivedAt;

    @Column(name = "unloaded_at", nullable = false)
    private Instant unloadedAt;

    @Column(name = "received_litres", nullable = false, precision = 9, scale = 2)
    private BigDecimal receivedLitres;

    @Column(name = "accepted_litres", nullable = false, precision = 9, scale = 2)
    private BigDecimal acceptedLitres;

    @Column(name = "rejected_litres", nullable = false, precision = 9, scale = 2)
    private BigDecimal rejectedLitres;

    @Column(name = "milk_temp_c", precision = 4, scale = 1)
    private BigDecimal milkTempC;

    /** Age of the oldest milk on board at arrival: what the spoilage model predicted. */
    @Column(name = "oldest_milk_min", nullable = false)
    private int oldestMilkMin;

    @Column(name = "rejection_reason", length = 64)
    private String rejectionReason;

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

    public Plant getPlant() {
        return plant;
    }

    public void setPlant(Plant plant) {
        this.plant = plant;
    }

    public Instant getArrivedAt() {
        return arrivedAt;
    }

    public void setArrivedAt(Instant arrivedAt) {
        this.arrivedAt = arrivedAt;
    }

    public Instant getUnloadedAt() {
        return unloadedAt;
    }

    public void setUnloadedAt(Instant unloadedAt) {
        this.unloadedAt = unloadedAt;
    }

    public BigDecimal getReceivedLitres() {
        return receivedLitres;
    }

    public void setReceivedLitres(BigDecimal receivedLitres) {
        this.receivedLitres = receivedLitres;
    }

    public BigDecimal getAcceptedLitres() {
        return acceptedLitres;
    }

    public void setAcceptedLitres(BigDecimal acceptedLitres) {
        this.acceptedLitres = acceptedLitres;
    }

    public BigDecimal getRejectedLitres() {
        return rejectedLitres;
    }

    public void setRejectedLitres(BigDecimal rejectedLitres) {
        this.rejectedLitres = rejectedLitres;
    }

    public BigDecimal getMilkTempC() {
        return milkTempC;
    }

    public void setMilkTempC(BigDecimal milkTempC) {
        this.milkTempC = milkTempC;
    }

    public int getOldestMilkMin() {
        return oldestMilkMin;
    }

    public void setOldestMilkMin(int oldestMilkMin) {
        this.oldestMilkMin = oldestMilkMin;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }
}
