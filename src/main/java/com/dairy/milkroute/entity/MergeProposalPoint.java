package com.dairy.milkroute.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/**
 * One collection point folding into a proposal's hub, with the walk it would impose on
 * the farmers who use it.
 */
@Entity
@Table(name = "merge_proposal_point")
public class MergeProposalPoint {

    @EmbeddedId
    private MergeProposalPointId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("proposalId")
    @JoinColumn(name = "proposal_id", nullable = false)
    private MergeProposal proposal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("collectionPointId")
    @JoinColumn(name = "collection_point_id", nullable = false)
    private CollectionPoint collectionPoint;

    /** Straight-line, which is why every proposal needs field validation before action. */
    @Column(name = "walk_metres", nullable = false)
    private int walkMetres;

    public MergeProposalPointId getId() {
        return id;
    }

    public void setId(MergeProposalPointId id) {
        this.id = id;
    }

    public MergeProposal getProposal() {
        return proposal;
    }

    public void setProposal(MergeProposal proposal) {
        this.proposal = proposal;
    }

    public CollectionPoint getCollectionPoint() {
        return collectionPoint;
    }

    public void setCollectionPoint(CollectionPoint collectionPoint) {
        this.collectionPoint = collectionPoint;
    }

    public int getWalkMetres() {
        return walkMetres;
    }

    public void setWalkMetres(int walkMetres) {
        this.walkMetres = walkMetres;
    }
}
