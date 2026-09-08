package com.dairy.milkroute.entity;

import com.dairy.milkroute.enums.ExclusionReason;
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

/**
 * A collection point that a plan left out, with the reason and the score it lost on.
 *
 * <p>The point of recording this is accountability. When a farmer rings to ask why the
 * tanker did not come, ops can give a real answer with a number behind it instead of a
 * shrug: the same class of problem as "where is my tanker", one layer up.
 */
@Entity
@Table(name = "plan_exclusion")
public class PlanExclusion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private RoutePlan plan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "collection_point_id", nullable = false)
    private CollectionPoint collectionPoint;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32)
    private ExclusionReason reason;

    @Column(name = "detail")
    private String detail;

    /** The scoring value that lost, kept so a coverage decision can be re-examined. */
    @Column(name = "score", precision = 9, scale = 4)
    private BigDecimal score;

    @Column(name = "litres_forgone", precision = 7, scale = 2)
    private BigDecimal litresForgone;

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

    public CollectionPoint getCollectionPoint() {
        return collectionPoint;
    }

    public void setCollectionPoint(CollectionPoint collectionPoint) {
        this.collectionPoint = collectionPoint;
    }

    public ExclusionReason getReason() {
        return reason;
    }

    public void setReason(ExclusionReason reason) {
        this.reason = reason;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public BigDecimal getLitresForgone() {
        return litresForgone;
    }

    public void setLitresForgone(BigDecimal litresForgone) {
        this.litresForgone = litresForgone;
    }
}
