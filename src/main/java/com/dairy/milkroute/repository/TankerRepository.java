package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.Tanker;
import com.dairy.milkroute.enums.TankerStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TankerRepository extends JpaRepository<Tanker, Long> {

    /**
     * The fleet the planner may allocate, largest hold first so the riskiest route can be
     * given the biggest tanker. Never a hardcoded count: how many there are is this
     * query's answer.
     */
    List<Tanker> findByStatusOrderByCapacityLitresDesc(TankerStatus status);

    Optional<Tanker> findByRegNo(String regNo);

    long countByStatus(TankerStatus status);
}
