package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.Alert;
import com.dairy.milkroute.enums.AlertSeverity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    /**
     * The dedupe lookup. uq_alert_open makes at most one open alert per key possible, so
     * this returns Optional: found means update the existing one rather than raise a
     * second, which is what keeps the board worth reading.
     */
    Optional<Alert> findByDedupeKeyAndResolvedAtIsNull(String dedupeKey);

    List<Alert> findByResolvedAtIsNullOrderByRaisedAtDesc();

    List<Alert> findByTripIdAndResolvedAtIsNull(Long tripId);

    long countBySeverityAndResolvedAtIsNull(AlertSeverity severity);
}
