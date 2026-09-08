package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.Driver;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    List<Driver> findByActiveTrueOrderByCodeAsc();

    Optional<Driver> findByCode(String code);

    long countByActiveTrue();
}
