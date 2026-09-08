package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.IntakeRecord;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntakeRecordRepository extends JpaRepository<IntakeRecord, Long> {

    Optional<IntakeRecord> findByTripId(Long tripId);
}
