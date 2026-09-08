package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.TripStop;
import com.dairy.milkroute.enums.TripStopStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripStopRepository extends JpaRepository<TripStop, Long> {

    List<TripStop> findByTripIdOrderBySeqAsc(Long tripId);

    Optional<TripStop> findByTripIdAndSeq(Long tripId, int seq);

    /** Where a tanker still has to go, which is what remaining-litres estimates use. */
    List<TripStop> findByTripIdAndStatusInOrderBySeqAsc(Long tripId, List<TripStopStatus> statuses);

    /** The farmer-facing lookup: is my point on anyone's list today, and where are they. */
    List<TripStop> findByCollectionPointIdAndTripIdIn(Long collectionPointId, List<Long> tripIds);
}
