package com.dairy.milkroute.dto;

import java.util.List;

/**
 * Everything the seeder needs to build one fake dairy, loaded from
 * {@code classpath:datasets/{name}.yaml}.
 *
 * <p>This record is the whole of rule 1: no count is written into the seeder. If a
 * reviewer opens {@code SeedService} looking for a hardcoded 60 and finds one, the claim
 * that the system is dynamic collapses, and the "change one file to go from 60 villages to
 * 200" demo stops being true.
 *
 * @param name                  dataset name, matching its file name
 * @param seed                  fixed RNG seed; the same config must always produce the
 *                              same dairy, because tests assert exact numbers and the demo
 *                              has to behave identically twice
 * @param villageCount          how many villages to place
 * @param corridorCount         how many roads out of town they are strung along
 * @param villageDistanceKm     nearest and farthest village, straight-line from the plant
 * @param pointsPerVillage      collection points per village
 * @param pointScatterMetres    how far a point may sit from its village centre
 * @param targetFarmerCount     a target, not an exact count: chasing an exact figure buys
 *                              nothing and costs a pile of special cases
 * @param twoFarmerPointRatio   fraction of points shared by two farmers, which is what the
 *                              point-versus-farmer split in the schema exists to handle
 * @param animalsPerFarmer      herd size range
 * @param litresPerAnimalPerDay yield range, per animal per day
 * @param tankerCount           fleet size
 * @param capacityMix           capacities dealt round-robin across the fleet; a single
 *                              entry gives every tanker the same hold
 * @param insulatedCount        the first N tankers are insulated, which is what makes
 *                              tanker assignment a real decision
 * @param driverCount           drivers available
 * @param plantCount            chilling plants
 * @param plantLat              primary plant latitude
 * @param plantLng              primary plant longitude
 * @param defaultSession        session the diagnostics endpoint reports against
 * @param defaultAmbientC       ambient temperature the diagnostics endpoint reports against
 */
public record DatasetConfig(
        String name,
        long seed,

        // geography
        int villageCount,
        int corridorCount,
        Range villageDistanceKm,
        Range pointsPerVillage,
        double pointScatterMetres,

        // people
        int targetFarmerCount,
        double twoFarmerPointRatio,
        Range animalsPerFarmer,
        Range litresPerAnimalPerDay,

        // fleet
        int tankerCount,
        List<Integer> capacityMix,
        int insulatedCount,
        int driverCount,

        // plant
        int plantCount,
        double plantLat,
        double plantLng,

        // session defaults
        String defaultSession,
        double defaultAmbientC) {

    public DatasetConfig {
        capacityMix = List.copyOf(capacityMix);
        if (capacityMix.isEmpty()) {
            throw new IllegalArgumentException("capacityMix must hold at least one capacity");
        }
        if (insulatedCount > tankerCount) {
            throw new IllegalArgumentException(
                    "insulatedCount %d exceeds tankerCount %d".formatted(insulatedCount, tankerCount));
        }
    }
}
