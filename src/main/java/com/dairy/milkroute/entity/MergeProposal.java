package com.dairy.milkroute.entity;

import com.dairy.milkroute.enums.MergeConfidence;
import com.dairy.milkroute.enums.MergeProposalStatus;
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
 * A suggestion that several nearby collection points in one village be served as one.
 *
 * <p>Proposals are generated and ranked, never executed. Merging points changes how far
 * people carry cans every morning, so the decision belongs to the cooperative; only a
 * proposal moved to IMPLEMENTED writes back to collection_point.merged_into_id.
 *
 * <p>Never generated across villages: two villages can be 400 m apart and have a century
 * of reasons not to share a collection point.
 */
@Entity
@Table(name = "merge_proposal")
public class MergeProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "village_id", nullable = false)
    private Village village;

    /** The surviving point, chosen to minimise the worst walk rather than the average. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hub_point_id", nullable = false)
    private CollectionPoint hubPoint;

    @Column(name = "minutes_saved", nullable = false, precision = 6, scale = 2)
    private BigDecimal minutesSaved;

    @Column(name = "farmers_affected", nullable = false)
    private int farmersAffected;

    /**
     * The number that decides whether a proposal is humane. The farmer with the longest
     * walk is the one who will refuse, so this is checked against maxWalkMetres.
     */
    @Column(name = "max_walk_metres", nullable = false)
    private int maxWalkMetres;

    @Column(name = "litres_affected", nullable = false, precision = 7, scale = 2)
    private BigDecimal litresAffected;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence", nullable = false, length = 10)
    private MergeConfidence confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MergeProposalStatus status = MergeProposalStatus.PROPOSED;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Village getVillage() {
        return village;
    }

    public void setVillage(Village village) {
        this.village = village;
    }

    public CollectionPoint getHubPoint() {
        return hubPoint;
    }

    public void setHubPoint(CollectionPoint hubPoint) {
        this.hubPoint = hubPoint;
    }

    public BigDecimal getMinutesSaved() {
        return minutesSaved;
    }

    public void setMinutesSaved(BigDecimal minutesSaved) {
        this.minutesSaved = minutesSaved;
    }

    public int getFarmersAffected() {
        return farmersAffected;
    }

    public void setFarmersAffected(int farmersAffected) {
        this.farmersAffected = farmersAffected;
    }

    public int getMaxWalkMetres() {
        return maxWalkMetres;
    }

    public void setMaxWalkMetres(int maxWalkMetres) {
        this.maxWalkMetres = maxWalkMetres;
    }

    public BigDecimal getLitresAffected() {
        return litresAffected;
    }

    public void setLitresAffected(BigDecimal litresAffected) {
        this.litresAffected = litresAffected;
    }

    public MergeConfidence getConfidence() {
        return confidence;
    }

    public void setConfidence(MergeConfidence confidence) {
        this.confidence = confidence;
    }

    public MergeProposalStatus getStatus() {
        return status;
    }

    public void setStatus(MergeProposalStatus status) {
        this.status = status;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }
}
