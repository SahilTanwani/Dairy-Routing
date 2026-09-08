package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.MergeProposal;
import com.dairy.milkroute.enums.MergeProposalStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MergeProposalRepository extends JpaRepository<MergeProposal, Long> {

    /** Ranked by what they save, which is how the advisory presents them. */
    List<MergeProposal> findByStatusOrderByMinutesSavedDesc(MergeProposalStatus status);

    List<MergeProposal> findByVillageId(Long villageId);
}
