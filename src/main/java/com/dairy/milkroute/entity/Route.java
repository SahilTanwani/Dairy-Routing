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
 * One tanker's planned round trip: plant, a sequence of villages, plant.
 *
 * <p>There is no {@code @OneToMany} to RouteStop here. Stops are fetched through
 * RouteStopRepository in one ordered query when they are needed, which keeps the planner
 * out of N+1 territory and keeps this entity safe to load with open-in-view disabled.
 */
@Entity
@Table(name = "route")
public class Route {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private RoutePlan plan;

    @Column(name = "label", nullable = false, length = 16)
    private String label;

    /** Null until the assigner runs: a draft plan can exist before the fleet is allocated. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tanker_id")
    private Tanker tanker;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private Driver driver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plant_id", nullable = false)
    private Plant plant;

    @Column(name = "planned_depart_at", nullable = false)
    private LocalTime plannedDepartAt;

    /**
     * How old the oldest milk will be on arrival at the plant. Excludes the plant to
     * first-village leg, which carries no milk and therefore no spoilage.
     */
    @Column(name = "est_hot_minutes", nullable = false)
    private int estHotMinutes;

    @Column(name = "hold_budget_minutes", nullable = false)
    private int holdBudgetMinutes;

    /**
     * Budget minus hot time. Stored rather than derived because the ops board sorts by
     * risk and the feasibility report flags a thin-slack route before anyone publishes it.
     */
    @Column(name = "slack_minutes", nullable = false)
    private int slackMinutes;

    @Column(name = "est_volume_litres", nullable = false, precision = 9, scale = 2)
    private BigDecimal estVolumeLitres;

    @Column(name = "est_distance_km", nullable = false, precision = 7, scale = 2)
    private BigDecimal estDistanceKm;

    @Column(name = "stop_count", nullable = false)
    private int stopCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public RoutePlan getPlan() {
        return plan;
    }

    public void setPlan(RoutePlan plan) {
        this.plan = plan;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Tanker getTanker() {
        return tanker;
    }

    public void setTanker(Tanker tanker) {
        this.tanker = tanker;
    }

    public Driver getDriver() {
        return driver;
    }

    public void setDriver(Driver driver) {
        this.driver = driver;
    }

    public Plant getPlant() {
        return plant;
    }

    public void setPlant(Plant plant) {
        this.plant = plant;
    }

    public LocalTime getPlannedDepartAt() {
        return plannedDepartAt;
    }

    public void setPlannedDepartAt(LocalTime plannedDepartAt) {
        this.plannedDepartAt = plannedDepartAt;
    }

    public int getEstHotMinutes() {
        return estHotMinutes;
    }

    public void setEstHotMinutes(int estHotMinutes) {
        this.estHotMinutes = estHotMinutes;
    }

    public int getHoldBudgetMinutes() {
        return holdBudgetMinutes;
    }

    public void setHoldBudgetMinutes(int holdBudgetMinutes) {
        this.holdBudgetMinutes = holdBudgetMinutes;
    }

    public int getSlackMinutes() {
        return slackMinutes;
    }

    public void setSlackMinutes(int slackMinutes) {
        this.slackMinutes = slackMinutes;
    }

    public BigDecimal getEstVolumeLitres() {
        return estVolumeLitres;
    }

    public void setEstVolumeLitres(BigDecimal estVolumeLitres) {
        this.estVolumeLitres = estVolumeLitres;
    }

    public BigDecimal getEstDistanceKm() {
        return estDistanceKm;
    }

    public void setEstDistanceKm(BigDecimal estDistanceKm) {
        this.estDistanceKm = estDistanceKm;
    }

    public int getStopCount() {
        return stopCount;
    }

    public void setStopCount(int stopCount) {
        this.stopCount = stopCount;
    }
}
