package com.dairy.milkroute.domain.geo;

/**
 * The six numbers that turn a straight-line distance into a road journey time.
 *
 * <p>All of them come from {@code solver_parameter}. They are passed in as a plain record
 * rather than read from a repository because everything in {@code domain/} is built with
 * {@code new} and knows nothing about Spring or the database.
 *
 * <p>They are also part of the travel matrix cache key. Retuning the circuity factor and
 * re-planning is a demonstration the parameter table exists for, and it would be worthless
 * if a matrix computed under the old factor were handed back afterwards.
 *
 * @param circuityFactor     road distance as a multiple of straight-line distance; roads
 *                           wander around fields, rivers and property lines
 * @param speedUnder2Km      km/h on village lanes
 * @param speed2To10Km       km/h on connecting roads
 * @param speedOver10Km      km/h on district roads
 * @param morningSpeedFactor multiplier for a pre-dawn run on empty roads
 * @param eveningSpeedFactor multiplier for the evening run, into traffic
 */
public record TravelParameters(
        double circuityFactor,
        double speedUnder2Km,
        double speed2To10Km,
        double speedOver10Km,
        double morningSpeedFactor,
        double eveningSpeedFactor) {

    public TravelParameters {
        requirePositive(circuityFactor, "circuityFactor");
        requirePositive(speedUnder2Km, "speedUnder2Km");
        requirePositive(speed2To10Km, "speed2To10Km");
        requirePositive(speedOver10Km, "speedOver10Km");
        requirePositive(morningSpeedFactor, "morningSpeedFactor");
        requirePositive(eveningSpeedFactor, "eveningSpeedFactor");
    }

    /**
     * A zero or negative speed would divide a journey by nothing and produce an infinite
     * travel time, which the planner would happily accept as "unreachable" for every point
     * in the dairy. Failing here instead makes a mis-seeded parameter obvious.
     */
    private static void requirePositive(double value, String name) {
        if (!(value > 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "%s must be a positive finite number, was %s".formatted(name, value));
        }
    }
}
