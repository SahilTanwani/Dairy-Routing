package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.PointCoverageState;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointCoverageStateRepository extends JpaRepository<PointCoverageState, Long> {

    /**
     * Points that have hit the three-strike rule. These are seeded into the next plan
     * before scoring begins: the equity term raises priority, but this overrides it.
     */
    List<PointCoverageState> findByConsecutiveSkipsGreaterThanEqual(int skips);

    List<PointCoverageState> findByCollectionPointIdIn(List<Long> collectionPointIds);
}
