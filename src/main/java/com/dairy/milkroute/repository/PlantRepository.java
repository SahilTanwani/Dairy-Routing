package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.Plant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlantRepository extends JpaRepository<Plant, Long> {

    Optional<Plant> findByCode(String code);

    Optional<Plant> findFirstByPrimaryTrueAndActiveTrue();

    List<Plant> findByActiveTrueOrderByCodeAsc();
}
