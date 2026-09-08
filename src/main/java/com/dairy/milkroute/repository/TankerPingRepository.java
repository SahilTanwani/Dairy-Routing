package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.TankerPing;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TankerPingRepository extends JpaRepository<TankerPing, Long> {

    /** Last known position, which decides ETA confidence and the words a farmer hears. */
    Optional<TankerPing> findFirstByTripIdOrderByRecordedAtDesc(Long tripId);

    List<TankerPing> findByTripIdOrderByRecordedAtDesc(Long tripId);
}
