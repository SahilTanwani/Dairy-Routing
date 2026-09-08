package com.dairy.milkroute.domain.tracking;

import com.dairy.milkroute.domain.geo.GeoPoint;
import com.dairy.milkroute.domain.geo.TravelTimeProvider;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.entity.TripStop;
import com.dairy.milkroute.enums.EtaConfidence;
import com.dairy.milkroute.enums.TripStopStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * When the tanker will get there.
 *
 * <p>Three ideas, and the middle one is what makes it useful.
 *
 * <p><strong>Start from where it actually is.</strong> The remainder of the current leg,
 * then every stop still ahead, then the run home. Skipped and deferred stops are not counted
 * — a skipped stop moves every later ETA <em>earlier</em>, which is the opposite of what a
 * naive "stops remaining times average" would say.
 *
 * <p><strong>Scale by how the driver is actually going.</strong> A driver fifteen percent
 * slow through eight stops will most likely be fifteen percent slow through the next eight.
 * Comparing elapsed time against planned time over the stops already done turns the plan
 * into a live estimate for ten lines of code, and it is the whole difference between a
 * number worth quoting and one that is quietly always wrong.
 *
 * <p><strong>Say how much to trust it.</strong> An ETA with no confidence attached invites
 * somebody to read it out. Quote a farmer 6:41 and turn up at 7:15 and he will never believe
 * another number you give him.
 */
public final class EtaCalculator {

    /**
     * Fewer completed stops than this and the observed pace is noise rather than a trend —
     * one slow village would otherwise rewrite the whole afternoon.
     */
    private static final int MINIMUM_STOPS_FOR_A_TREND = 3;

    /**
     * Bounds on the delay factor. Below 0.7 the estimate would claim a driver is making up
     * time faster than the roads allow; above 2.0 one pathological stop — a breakdown, a
     * long argument at a gate — would double every remaining estimate for the rest of the
     * run. Clamping keeps a single bad leg local.
     */
    private static final double MIN_DELAY_FACTOR = 0.7;
    private static final double MAX_DELAY_FACTOR = 2.0;

    /** Confidence bands, by how stale the last position is. */
    private static final Duration FRESH_PING = Duration.ofMinutes(5);
    private static final Duration STALE_PING = Duration.ofMinutes(15);

    private final TravelTimeProvider travel;
    private final PositionResolver positions;

    public EtaCalculator(TravelTimeProvider travel, PositionResolver positions) {
        this.travel = travel;
        this.positions = positions;
    }

    /** When this trip reaches the plant, and how much to trust the answer. */
    public EtaEstimate toPlant(Trip trip, List<TripStop> stops, GeoPoint plant, Instant now) {
        ResolvedPosition position = positions.resolve(trip, stops, plant);
        Duration remaining = remainingTo(trip, stops, plant, position, lastSeq(stops) + 1);

        double factor = observedDelayFactor(trip, stops);
        Duration scaled = scale(remaining, factor);

        return new EtaEstimate(
                now.plus(scaled),
                confidence(position.lastPingAt(), completed(stops).size(), now),
                factor,
                scaled.toMinutes(),
                position);
    }

    /** When this trip reaches one particular stop. */
    public EtaEstimate toStop(Trip trip,
                              List<TripStop> stops,
                              GeoPoint plant,
                              int targetSeq,
                              Instant now) {
        ResolvedPosition position = positions.resolve(trip, stops, plant);
        Duration remaining = remainingTo(trip, stops, plant, position, targetSeq);

        double factor = observedDelayFactor(trip, stops);
        Duration scaled = scale(remaining, factor);

        return new EtaEstimate(
                now.plus(scaled),
                confidence(position.lastPingAt(), completed(stops).size(), now),
                factor,
                scaled.toMinutes(),
                position);
    }

    /**
     * Travel and service time from the tanker's current position up to {@code targetSeq}.
     *
     * <p>A target beyond the last stop means the plant, which is how the run home is costed.
     */
    private Duration remainingTo(Trip trip,
                                 List<TripStop> stops,
                                 GeoPoint plant,
                                 ResolvedPosition position,
                                 int targetSeq) {
        int fromSeq = position.fromSeq();
        GeoPoint legEnd = locationOf(stops, fromSeq + 1, plant);

        // The part of the current leg still ahead, measured from where the tanker actually
        // is rather than from the stop it left.
        double legMinutes = minutesBetween(position.at(), legEnd, trip);
        Duration remaining = ofMinutes(legMinutes);

        for (int seq = fromSeq + 1; seq < targetSeq; seq++) {
            TripStop stop = stopAt(stops, seq);
            if (stop == null || isPast(stop)) {
                // A skipped or deferred stop costs nothing, so every later ETA moves earlier.
                continue;
            }
            remaining = remaining
                    .plus(serviceTimeOf(stop))
                    .plus(ofMinutes(minutesBetween(
                            locationOf(stops, seq, plant),
                            locationOf(stops, seq + 1, plant),
                            trip)));
        }
        return remaining;
    }

    /**
     * How the driver's real pace compares with the plan.
     *
     * <p>Measured between the first and last completed stops, so it needs no departure time
     * and cannot be thrown by a late start that the driver has already made up.
     */
    public double observedDelayFactor(Trip trip, List<TripStop> stops) {
        List<TripStop> done = completed(stops);
        if (done.size() < MINIMUM_STOPS_FOR_A_TREND) {
            return 1.0;
        }

        TripStop first = done.getFirst();
        TripStop last = done.getLast();

        long actual = Duration.between(first.getArrivedAt(), last.getArrivedAt()).toSeconds();
        long planned = Duration.between(
                first.getPlannedArrivalAt(), last.getPlannedArrivalAt()).toSeconds();

        if (planned <= 0) {
            return 1.0;
        }
        return Math.clamp((double) actual / planned, MIN_DELAY_FACTOR, MAX_DELAY_FACTOR);
    }

    /**
     * How much to trust the estimate, from the age of the last position and how much of the
     * run has been observed.
     *
     * <p>Both matter. A fresh ping early in a trip still has no pace to extrapolate from, so
     * it is worth a window rather than a time; a stale ping means the tanker is somewhere
     * other than where the map says, whatever the pace was.
     */
    public EtaConfidence confidence(Instant lastPingAt, int stopsCompleted, Instant now) {
        if (lastPingAt == null) {
            return EtaConfidence.LOST;
        }

        Duration age = Duration.between(lastPingAt, now);
        if (age.compareTo(STALE_PING) > 0) {
            return EtaConfidence.LOST;
        }
        if (age.compareTo(FRESH_PING) > 0) {
            return EtaConfidence.LOW;
        }
        return stopsCompleted >= MINIMUM_STOPS_FOR_A_TREND
                ? EtaConfidence.HIGH
                : EtaConfidence.MEDIUM;
    }

    /** Stops already collected from, oldest arrival first. */
    private static List<TripStop> completed(List<TripStop> stops) {
        List<TripStop> done = new ArrayList<>();
        for (TripStop stop : stops) {
            if (stop.getStatus() == TripStopStatus.COLLECTED && stop.getArrivedAt() != null) {
                done.add(stop);
            }
        }
        done.sort(Comparator.comparing(TripStop::getArrivedAt));
        return done;
    }

    private static boolean isPast(TripStop stop) {
        return stop.getStatus() == TripStopStatus.COLLECTED
                || stop.getStatus() == TripStopStatus.SKIPPED
                || stop.getStatus() == TripStopStatus.DEFERRED;
    }

    private double minutesBetween(GeoPoint from, GeoPoint to, Trip trip) {
        return travel.between(from, to, trip.getSession()).toSeconds() / 60.0;
    }

    private static Duration serviceTimeOf(TripStop stop) {
        return ofMinutes(stop.getCollectionPoint().getServiceMinutes().doubleValue());
    }

    private static Duration ofMinutes(double minutes) {
        return Duration.ofSeconds(Math.round(minutes * 60));
    }

    private static Duration scale(Duration duration, double factor) {
        return Duration.ofSeconds(Math.round(duration.toSeconds() * factor));
    }

    private static TripStop stopAt(List<TripStop> stops, int seq) {
        return stops.stream().filter(s -> s.getSeq() == seq).findFirst().orElse(null);
    }

    private static int lastSeq(List<TripStop> stops) {
        return stops.stream().mapToInt(TripStop::getSeq).max().orElse(0);
    }

    private static GeoPoint locationOf(List<TripStop> stops, int seq, GeoPoint plant) {
        TripStop stop = stopAt(stops, seq);
        return stop == null
                ? plant
                : new GeoPoint(stop.getCollectionPoint().getLat().doubleValue(),
                        stop.getCollectionPoint().getLng().doubleValue());
    }
}
