package com.dairy.milkroute.dto;

import java.util.Random;

/**
 * An inclusive numeric range in a dataset config, and the two ways the seeder draws from
 * it.
 *
 * <p>The {@link Random} is always passed in rather than held. Every draw in a seeding run
 * comes from one generator created with the dataset's seed, which is what makes the same
 * config produce the same dairy every time.
 *
 * @param min lower bound, inclusive
 * @param max upper bound, inclusive
 */
public record Range(double min, double max) {

    public Range {
        if (min > max) {
            throw new IllegalArgumentException("range min %s is above max %s".formatted(min, max));
        }
    }

    /** A uniform draw from the range. */
    public double pick(Random rng) {
        return min + rng.nextDouble() * (max - min);
    }

    /** A uniform draw rounded to a whole number, for counts. */
    public int pickInt(Random rng) {
        return (int) Math.round(pick(rng));
    }
}
