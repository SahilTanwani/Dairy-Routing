package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.Village;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VillageRepository extends JpaRepository<Village, Long> {

    List<Village> findByActiveTrueOrderByCodeAsc();

    Optional<Village> findByCode(String code);

    long countByActiveTrue();
}
