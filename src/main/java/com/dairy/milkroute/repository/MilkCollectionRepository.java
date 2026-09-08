package com.dairy.milkroute.repository;

import com.dairy.milkroute.entity.MilkCollection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MilkCollectionRepository extends JpaRepository<MilkCollection, Long> {

    /** A farmer's own history, newest first. */
    List<MilkCollection> findByFarmerIdOrderByCollectedAtDesc(Long farmerId);

    List<MilkCollection> findByTripStopId(Long tripStopId);

    List<MilkCollection> findByTripId(Long tripId);

    /**
     * Backs UNIQUE (trip_stop_id, farmer_id). The database is the guarantee; this is how
     * ingestion checks before trying, so a double tap reads as a no-op rather than a 500.
     */
    Optional<MilkCollection> findByTripStopIdAndFarmerId(Long tripStopId, Long farmerId);
}
