package com.dairy.milkroute.entity;

import com.dairy.milkroute.enums.EtaConfidence;
import com.dairy.milkroute.enums.RiskLevel;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.enums.TripStatus;
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
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One tanker actually running one route on one date: where the planned world and the real
 * world meet.
 *
 * <p>A trip copies what it needs from the route at creation and then stops looking at the
 * plan. {@code holdBudgetMinutes} is copied rather than read from the tanker, because
 * editing a tanker record mid-morning must not move a deadline that is already running;
 * the only permitted change is the one-way downward ratchet when the day gets hotter.
 */
@Entity
@Table(name = "trip")
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private RoutePlan plan;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "session", nullable = false, length = 10)
    private Session session;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TripStatus status = TripStatus.SCHEDULED;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tanker_id", nullable = false)
    private Tanker tanker;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false)
    private Driver driver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_plant_id", nullable = false)
    private Plant destinationPlant;

    @Column(name = "ambient_temp_c", nullable = false, precision = 4, scale = 1)
    private BigDecimal ambientTempC;

    @Column(name = "hold_budget_minutes", nullable = false)
    private int holdBudgetMinutes;

    /**
     * When milk first entered this tanker. Written once and then frozen: the spoilage
     * clock starts at first collection, not at departure, because the plant to
     * first-village leg carries nothing.
     */
    @Column(name = "first_collection_at")
    private Instant firstCollectionAt;

    /**
     * Frozen alongside {@code firstCollectionAt}. If this were recomputed as the trip ran,
     * a bug in the ETA engine could silently extend it and lose a full tanker of milk
     * without ever raising an alert.
     */
    @Column(name = "spoilage_deadline_at")
    private Instant spoilageDeadlineAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "plant_arrival_at")
    private Instant plantArrivalAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "litres_on_board", nullable = false, precision = 9, scale = 2)
    private BigDecimal litresOnBoard = BigDecimal.ZERO;

    @Column(name = "capacity_litres", nullable = false)
    private int capacityLitres;

    /**
     * The next five fields are denormalised projections of the event and ping streams. All
     * are derivable, but the ops board polls every two seconds for every active trip and
     * must not run an aggregate per row to do it.
     */
    @Column(name = "current_seq", nullable = false)
    private int currentSeq;

    @Column(name = "last_lat", precision = 9, scale = 6)
    private BigDecimal lastLat;

    @Column(name = "last_lng", precision = 9, scale = 6)
    private BigDecimal lastLng;

    @Column(name = "last_ping_at")
    private Instant lastPingAt;

    @Column(name = "eta_plant_at")
    private Instant etaPlantAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "eta_confidence", nullable = false, length = 10)
    private EtaConfidence etaConfidence = EtaConfidence.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 10)
    private RiskLevel riskLevel = RiskLevel.OK;

    /**
     * Optimistic locking. Two dispatchers executing mitigations on the same trip at the
     * same moment produce a clean 409 rather than a half-applied trip.
     */
    @Version
    @Column(name = "version", nullable = false)
    private int version;

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

    public RoutePlan getPlan() {
        return plan;
    }

    public void setPlan(RoutePlan plan) {
        this.plan = plan;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public void setBusinessDate(LocalDate businessDate) {
        this.businessDate = businessDate;
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public TripStatus getStatus() {
        return status;
    }

    public void setStatus(TripStatus status) {
        this.status = status;
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

    public Plant getDestinationPlant() {
        return destinationPlant;
    }

    public void setDestinationPlant(Plant destinationPlant) {
        this.destinationPlant = destinationPlant;
    }

    public BigDecimal getAmbientTempC() {
        return ambientTempC;
    }

    public void setAmbientTempC(BigDecimal ambientTempC) {
        this.ambientTempC = ambientTempC;
    }

    public int getHoldBudgetMinutes() {
        return holdBudgetMinutes;
    }

    public void setHoldBudgetMinutes(int holdBudgetMinutes) {
        this.holdBudgetMinutes = holdBudgetMinutes;
    }

    public Instant getFirstCollectionAt() {
        return firstCollectionAt;
    }

    public void setFirstCollectionAt(Instant firstCollectionAt) {
        this.firstCollectionAt = firstCollectionAt;
    }

    public Instant getSpoilageDeadlineAt() {
        return spoilageDeadlineAt;
    }

    public void setSpoilageDeadlineAt(Instant spoilageDeadlineAt) {
        this.spoilageDeadlineAt = spoilageDeadlineAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getPlantArrivalAt() {
        return plantArrivalAt;
    }

    public void setPlantArrivalAt(Instant plantArrivalAt) {
        this.plantArrivalAt = plantArrivalAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public BigDecimal getLitresOnBoard() {
        return litresOnBoard;
    }

    public void setLitresOnBoard(BigDecimal litresOnBoard) {
        this.litresOnBoard = litresOnBoard;
    }

    public int getCapacityLitres() {
        return capacityLitres;
    }

    public void setCapacityLitres(int capacityLitres) {
        this.capacityLitres = capacityLitres;
    }

    public int getCurrentSeq() {
        return currentSeq;
    }

    public void setCurrentSeq(int currentSeq) {
        this.currentSeq = currentSeq;
    }

    public BigDecimal getLastLat() {
        return lastLat;
    }

    public void setLastLat(BigDecimal lastLat) {
        this.lastLat = lastLat;
    }

    public BigDecimal getLastLng() {
        return lastLng;
    }

    public void setLastLng(BigDecimal lastLng) {
        this.lastLng = lastLng;
    }

    public Instant getLastPingAt() {
        return lastPingAt;
    }

    public void setLastPingAt(Instant lastPingAt) {
        this.lastPingAt = lastPingAt;
    }

    public Instant getEtaPlantAt() {
        return etaPlantAt;
    }

    public void setEtaPlantAt(Instant etaPlantAt) {
        this.etaPlantAt = etaPlantAt;
    }

    public EtaConfidence getEtaConfidence() {
        return etaConfidence;
    }

    public void setEtaConfidence(EtaConfidence etaConfidence) {
        this.etaConfidence = etaConfidence;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }
}
