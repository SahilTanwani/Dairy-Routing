package com.dairy.milkroute.dto.response;

/**
 * What a seeding run produced. Every figure is a row count read back from the database,
 * not a number the seeder believed it was going to write.
 *
 * @param dataset      the dataset that was loaded
 * @param seed         the RNG seed it ran with, so a surprising dairy can be reproduced
 * @param villages     villages created
 * @param points       collection points created
 * @param farmers      farmers created
 * @param tankers      tankers created
 * @param drivers      drivers created
 * @param plants       plants created
 * @param elapsedMs    wall time for the run
 */
public record SeedResult(
        String dataset,
        long seed,
        long villages,
        long points,
        long farmers,
        long tankers,
        long drivers,
        long plants,
        long elapsedMs) {
}
