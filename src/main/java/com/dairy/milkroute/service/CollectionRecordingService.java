package com.dairy.milkroute.service;

import com.dairy.milkroute.domain.trip.CollectionSink;
import com.dairy.milkroute.entity.Farmer;
import com.dairy.milkroute.entity.MilkCollection;
import com.dairy.milkroute.entity.TripStop;
import com.dairy.milkroute.repository.FarmerRepository;
import com.dairy.milkroute.repository.MilkCollectionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes milk records: one row per farmer, per visit.
 *
 * <p>This is where the point-versus-farmer split in the schema finally earns its keep. The
 * tanker made one stop; two households put cans on it; two people are owed money. A single
 * row against the collection point would make the second farmer invisible to the payment
 * side.
 *
 * <p><strong>These rows are money.</strong> That governs everything here:
 *
 * <ul>
 *   <li>A second COLLECTED for a farmer already recorded at this stop is not written again —
 *       {@code UNIQUE (trip_stop_id, farmer_id)} would refuse it anyway, and a double-tapped
 *       submit must not pay twice.
 *   <li>Nor is it silently discarded when the litres differ. That is a real disagreement
 *       about how much milk exists, it is logged loudly, and the original stands until
 *       somebody voids it deliberately.
 *   <li>Corrections void and re-insert. Nothing here ever updates litres in place, because
 *       an audit that cannot show what was originally keyed is not an audit.
 * </ul>
 */
@Service
public class CollectionRecordingService implements CollectionSink {

    /** The CHECK constraint's bound, repeated here so the rejection names a number. */
    private static final BigDecimal MAX_LITRES_PER_FARMER = BigDecimal.valueOf(500);

    private static final Logger log = LoggerFactory.getLogger(CollectionRecordingService.class);

    private final MilkCollectionRepository collectionRepo;
    private final FarmerRepository farmerRepo;

    public CollectionRecordingService(MilkCollectionRepository collectionRepo,
                                      FarmerRepository farmerRepo) {
        this.collectionRepo = collectionRepo;
        this.farmerRepo = farmerRepo;
    }

    @Override
    public BigDecimal record(TripStop stop,
                             Map<String, BigDecimal> litresByFarmer,
                             Instant collectedAt) {
        BigDecimal total = BigDecimal.ZERO;

        for (Map.Entry<String, BigDecimal> entry : litresByFarmer.entrySet()) {
            Farmer farmer = farmerRepo.findByCode(entry.getKey()).orElse(null);
            if (farmer == null) {
                // Never invent a farmer to hang milk on: an unattributable record is worse
                // than a missing one, because it looks like it has been paid.
                log.warn("Collection for unknown farmer {} at stop {}; not recorded",
                        entry.getKey(), stop.getId());
                continue;
            }

            BigDecimal litres = validated(entry.getValue(), entry.getKey());

            var existing = collectionRepo.findByTripStopIdAndFarmerId(stop.getId(), farmer.getId());
            if (existing.isPresent()) {
                total = total.add(handleRepeat(existing.get(), litres, farmer));
                continue;
            }

            MilkCollection collection = new MilkCollection();
            collection.setTripStop(stop);
            collection.setTrip(stop.getTrip());
            collection.setFarmer(farmer);
            collection.setLitres(litres);
            collection.setCollectedAt(collectedAt);
            collectionRepo.save(collection);

            total = total.add(litres);
        }
        return total;
    }

    /**
     * A farmer already recorded at this stop.
     *
     * <p>Same figure: a retry, and the row already exists. Different figure: two sources
     * disagree about somebody's milk, which is a conflict a person has to settle. Either way
     * nothing is overwritten and nothing is silently dropped.
     */
    private BigDecimal handleRepeat(MilkCollection existing, BigDecimal litres, Farmer farmer) {
        if (existing.getLitres().compareTo(litres) != 0) {
            log.warn("Conflicting litres for farmer {} at stop {}: have {}, received {}. "
                            + "Keeping the recorded figure; a correction must void it explicitly.",
                    farmer.getCode(), existing.getTripStop().getId(),
                    existing.getLitres(), litres);
        }
        // Already counted against the trip when it was first recorded.
        return BigDecimal.ZERO;
    }

    private static BigDecimal validated(BigDecimal litres, String farmerCode) {
        if (litres == null || litres.signum() < 0) {
            throw new IllegalArgumentException(
                    "litres cannot be negative for farmer " + farmerCode);
        }
        if (litres.compareTo(MAX_LITRES_PER_FARMER) > 0) {
            throw new IllegalArgumentException(
                    "litres must be %s or less for one farmer; got %s for %s"
                            .formatted(MAX_LITRES_PER_FARMER, litres, farmerCode));
        }
        return litres;
    }

    /**
     * Voids a record and writes a correction, keeping both.
     *
     * <p>A driver keying 125 where they meant 12.5 is the case this exists for. The wrong
     * row stays, marked, with a reason; the right row is new. Editing the number in place
     * would leave nobody able to explain why a farmer's payment changed.
     */
    public MilkCollection correct(MilkCollection original, BigDecimal corrected, String reason) {
        original.setVoided(true);
        original.setVoidReason(reason);
        collectionRepo.save(original);

        MilkCollection replacement = new MilkCollection();
        replacement.setTripStop(original.getTripStop());
        replacement.setTrip(original.getTrip());
        replacement.setFarmer(original.getFarmer());
        replacement.setLitres(validated(corrected, original.getFarmer().getCode()));
        replacement.setCollectedAt(original.getCollectedAt());
        return collectionRepo.save(replacement);
    }

    /** Milk recorded at one stop, voided rows excluded. */
    public List<MilkCollection> at(long tripStopId) {
        return collectionRepo.findByTripStopId(tripStopId).stream()
                .filter(row -> !row.isVoided())
                .toList();
    }
}
