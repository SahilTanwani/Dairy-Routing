package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.Farmer;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FarmerRepository extends JpaRepository<Farmer, Long> {

    /** One stop, N farmers: this is the list a driver keys milk against at a point. */
    List<Farmer> findByCollectionPointIdAndActiveTrueOrderByCodeAsc(Long collectionPointId);

    Optional<Farmer> findByCode(String code);

    long countByActiveTrue();
}
