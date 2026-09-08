package com.dairy.milkroute.entity;

import com.dairy.milkroute.enums.Session;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * How recently each collection point was actually served.
 *
 * <p>This is what stops the algorithm quietly destroying the cooperative. Without it the
 * same marginal points at the end of the corridor get skipped every hot day, because they
 * are always the least efficient choice; {@code consecutiveSkips} feeds the equity term in
 * the scoring function and the three-strike rule that overrides the score outright.
 *
 * <p>The primary key is the collection point's own id: one row per point, no surrogate.
 */
@Entity
@Table(name = "point_coverage_state")
public class PointCoverageState {

    @Id
    @Column(name = "collection_point_id", nullable = false)
    private Long collectionPointId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "collection_point_id", nullable = false)
    private CollectionPoint collectionPoint;

    @Column(name = "last_served_date")
    private LocalDate lastServedDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_served_session", length = 10)
    private Session lastServedSession;

    /** Reset to zero on service. Three of these forces inclusion in the next plan. */
    @Column(name = "consecutive_skips", nullable = false)
    private int consecutiveSkips;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getCollectionPointId() {
        return collectionPointId;
    }

    public void setCollectionPointId(Long collectionPointId) {
        this.collectionPointId = collectionPointId;
    }

    public CollectionPoint getCollectionPoint() {
        return collectionPoint;
    }

    public void setCollectionPoint(CollectionPoint collectionPoint) {
        this.collectionPoint = collectionPoint;
    }

    public LocalDate getLastServedDate() {
        return lastServedDate;
    }

    public void setLastServedDate(LocalDate lastServedDate) {
        this.lastServedDate = lastServedDate;
    }

    public Session getLastServedSession() {
        return lastServedSession;
    }

    public void setLastServedSession(Session lastServedSession) {
        this.lastServedSession = lastServedSession;
    }

    public int getConsecutiveSkips() {
        return consecutiveSkips;
    }

    public void setConsecutiveSkips(int consecutiveSkips) {
        this.consecutiveSkips = consecutiveSkips;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
