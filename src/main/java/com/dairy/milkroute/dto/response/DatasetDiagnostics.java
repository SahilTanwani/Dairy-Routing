package com.dairy.milkroute.dto.response;

import java.util.List;

/**
 * What {@code GET /api/v1/admin/dataset-check} reports: whether a dataset actually lands
 * on the pressure point it was written to hit.
 *
 * <p>Writing a config does not guarantee it binds where you intended. A dataset meant to
 * be comfortable can turn out impossible, and one meant to force coverage mode can turn
 * out easy. This is the ten minutes per dataset that stops that being discovered live.
 *
 * @param dataset               dataset the figures were computed against
 * @param session               session assumed, since evening is the harder one
 * @param ambientC              ambient temperature assumed
 * @param villages              villages seeded
 * @param collectionPoints      points seeded
 * @param activePoints          points with at least one farmer, which is what gets routed
 * @param farmers               farmers seeded
 * @param tankers               tankers available
 * @param requiredHotMinutes    minutes of on-milk time the dairy needs
 * @param availableHotMinutes   minutes the fleet can supply before milk spoils
 * @param timeRatio             required over available; under 1 the dairy is servable,
 *                              and this is the single number that decides the mode
 * @param expectedLitres        volume the session should yield
 * @param fleetCapacityLitres   what the fleet can hold in one round
 * @param volumeRatio           volume over capacity; well under 1 means capacity never
 *                              binds and spoilage decides every route
 * @param unreachableVillages   villages no single tanker can serve and return from in
 *                              time, whatever the fleet size
 * @param unreachableVillageCodes which ones, so the finding can be checked by hand
 * @param farthestVillageKm     straight-line distance to the hardest village
 */
public record DatasetDiagnostics(
        String dataset,
        String session,
        double ambientC,
        long villages,
        long collectionPoints,
        long activePoints,
        long farmers,
        long tankers,
        double requiredHotMinutes,
        double availableHotMinutes,
        double timeRatio,
        double expectedLitres,
        long fleetCapacityLitres,
        double volumeRatio,
        int unreachableVillages,
        List<String> unreachableVillageCodes,
        double farthestVillageKm) {

    public DatasetDiagnostics {
        unreachableVillageCodes = List.copyOf(unreachableVillageCodes);
    }
}
