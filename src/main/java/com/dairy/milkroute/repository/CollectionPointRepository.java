package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.CollectionPoint;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionPointRepository extends JpaRepository<CollectionPoint, Long> {

    /** The planner's unit of work for one village. */
    List<CollectionPoint> findByVillageIdAndActiveTrueOrderByCodeAsc(Long villageId);

    /** The full routable point set for a planning run. */
    List<CollectionPoint> findByActiveTrueOrderByCodeAsc();

    Optional<CollectionPoint> findByCode(String code);

    long countByActiveTrue();
}
