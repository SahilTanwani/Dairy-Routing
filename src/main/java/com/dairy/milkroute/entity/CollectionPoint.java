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
 * The routing entity: a place a tanker physically stops. Farmers attach to it N:1, so one
 * stop produces one visit and N milk records.
 *
 * <p>Routing over farmers instead would put two stops at identical coordinates whenever a
 * point serves two households, which is a broken model rather than a slow one.
 */
@Entity
@Table(name = "collection_point")
public class CollectionPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 16)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "village_id", nullable = false)
    private Village village;

    @Column(name = "lat", nullable = false, precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(name = "lng", nullable = false, precision = 9, scale = 6)
    private BigDecimal lng;

    /** Stopping costs time whatever the volume. Seeded as 2.0 + 0.35 x farmerCount. */
    @Column(name = "service_minutes", nullable = false, precision = 4, scale = 1)
    private BigDecimal serviceMinutes;

    @Column(name = "avg_morning_litres", nullable = false, precision = 7, scale = 2)
    private BigDecimal avgMorningLitres;

    @Column(name = "avg_evening_litres", nullable = false, precision = 7, scale = 2)
    private BigDecimal avgEveningLitres;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /**
     * Set when the consolidation advisory retires this point into a hub. The point is
     * deactivated rather than deleted so its history stays readable.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merged_into_id")
    private CollectionPoint mergedInto;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Village getVillage() {
        return village;
    }

    public void setVillage(Village village) {
        this.village = village;
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

    public BigDecimal getServiceMinutes() {
        return serviceMinutes;
    }

    public void setServiceMinutes(BigDecimal serviceMinutes) {
        this.serviceMinutes = serviceMinutes;
    }

    public BigDecimal getAvgMorningLitres() {
        return avgMorningLitres;
    }

    public void setAvgMorningLitres(BigDecimal avgMorningLitres) {
        this.avgMorningLitres = avgMorningLitres;
    }

    public BigDecimal getAvgEveningLitres() {
        return avgEveningLitres;
    }

    public void setAvgEveningLitres(BigDecimal avgEveningLitres) {
        this.avgEveningLitres = avgEveningLitres;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public CollectionPoint getMergedInto() {
        return mergedInto;
    }

    public void setMergedInto(CollectionPoint mergedInto) {
        this.mergedInto = mergedInto;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
