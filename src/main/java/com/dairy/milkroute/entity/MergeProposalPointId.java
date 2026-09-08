package com.dairy.milkroute.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key for {@link MergeProposalPoint}. A point appears at most once in a given
 * proposal, so the pair is the natural key and a surrogate id would buy nothing.
 */
@Embeddable
public class MergeProposalPointId implements Serializable {

    @Column(name = "proposal_id", nullable = false)
    private Long proposalId;

    @Column(name = "collection_point_id", nullable = false)
    private Long collectionPointId;

    protected MergeProposalPointId() {
        // for JPA
    }

    public MergeProposalPointId(Long proposalId, Long collectionPointId) {
        this.proposalId = proposalId;
        this.collectionPointId = collectionPointId;
    }

    public Long getProposalId() {
        return proposalId;
    }

    public void setProposalId(Long proposalId) {
        this.proposalId = proposalId;
    }

    public Long getCollectionPointId() {
        return collectionPointId;
    }

    public void setCollectionPointId(Long collectionPointId) {
        this.collectionPointId = collectionPointId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MergeProposalPointId that)) {
            return false;
        }
        return Objects.equals(proposalId, that.proposalId)
                && Objects.equals(collectionPointId, that.collectionPointId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(proposalId, collectionPointId);
    }
}
