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
 * A GPS observation. Pings refine where a tanker is between stops; they never advance a
 * trip's progress, because driving past a collection point is not collecting from it.
 *
 * <p>There is deliberately no idempotency key. A duplicate reading is harmless, and adding
 * a UUID would double the write volume of the busiest table in the schema for nothing.
 */
@Entity
@Table(name = "tanker_ping")
public class TankerPing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(name = "lat", nullable = false, precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(name = "lng", nullable = false, precision = 9, scale = 6)
    private BigDecimal lng;

    @Column(name = "speed_kmph", precision = 5, scale = 1)
    private BigDecimal speedKmph;

    @Column(name = "accuracy_m")
    private Short accuracyM;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

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

    public BigDecimal getLat() {
        return lat;
    }

    public void setLat(BigDecimal lat) {
        this.lat = lat;
    }

    public BigDecimal getLng() {
        return lng;
    }

    public void setLng(BigDecimal lng) {
        this.lng = lng;
    }

    public BigDecimal getSpeedKmph() {
        return speedKmph;
    }

    public void setSpeedKmph(BigDecimal speedKmph) {
        this.speedKmph = speedKmph;
    }

    public Short getAccuracyM() {
        return accuracyM;
    }

    public void setAccuracyM(Short accuracyM) {
        this.accuracyM = accuracyM;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(Instant recordedAt) {
        this.recordedAt = recordedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }
}
