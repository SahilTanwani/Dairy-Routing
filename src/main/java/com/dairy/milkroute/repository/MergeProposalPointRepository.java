package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.MergeProposalPoint;
import com.dairy.milkroute.entity.MergeProposalPointId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MergeProposalPointRepository
        extends JpaRepository<MergeProposalPoint, MergeProposalPointId> {

    List<MergeProposalPoint> findByProposalId(Long proposalId);
}
