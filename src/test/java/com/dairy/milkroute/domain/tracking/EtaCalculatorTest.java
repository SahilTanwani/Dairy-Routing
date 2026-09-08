package com.dairy.milkroute.domain.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.dairy.milkroute.domain.geo.GeoPoint;
import com.dairy.milkroute.domain.geo.HaversineTravelTime;
import com.dairy.milkroute.domain.geo.TravelParameters;
import com.dairy.milkroute.domain.geo.TravelTimeProvider;
import com.dairy.milkroute.entity.CollectionPoint;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.entity.TripStop;
import com.dairy.milkroute.entity.Village;
import com.dairy.milkroute.enums.EtaConfidence;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.enums.TripStopStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit, no context: the calculator takes entities and a clock instant and returns a
 * number.
 *
 * <p>The fixture is a straight run of ten stops two kilometres apart heading away from the
 * plant, so travel time between consecutive stops is the same for every leg and the
 * arithmetic in each test can be reasoned about rather than merely observed.
 */
class EtaCalculatorTest {

    private static final GeoPoint PLANT = new GeoPoint(16.7050, 74.2433);
    private static final Instant DEPART = Instant.parse("2026-10-15T05:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-15T06:00:00Z");
    private static final int STOPS = 10;

    /** Planned ten minutes per stop, which is what the delay factor measures against. */
    private static final Duration PLANNED_GAP = Duration.ofMinutes(10);

    private final TravelTimeProvider travel = new HaversineTravelTime(
            new TravelParameters(1.35, 15.0, 26.0, 34.0, 1.15, 0.90));
    private final PositionResolver positions = new PositionResolver();
    private final EtaCalculator calculator = new EtaCalculator(travel, positions);

    @Nested
    @DisplayName("the happy path")
    class HappyPath {

        @Test
        void estimatesAnArrivalAheadOfNow() {
            Trip trip = tripAt(3);
            List<TripStop> stops = stops(trip);
            collectFirst(stops, 3, PLANNED_GAP);

            EtaEstimate eta = calculator.toPlant(trip, stops, PLANT, NOW);

            assertThat(eta.at()).isAfter(NOW);
            assertThat(eta.remainingMinutes()).isPositive();
        }

        @Test
        void aDriverOnTimeIsNotAdjusted() {
            Trip trip = tripAt(4);
            List<TripStop> stops = stops(trip);
            // Arrived exactly when planned at each of the first four stops.
            collectFirst(stops, 4, PLANNED_GAP);

            assertThat(calculator.observedDelayFactor(trip, stops)).isEqualTo(1.0);
        }

        @Test
        void aLaterStopIsAlwaysAfterAnEarlierOne() {
            Trip trip = tripAt(2);
            List<TripStop> stops = stops(trip);
            collectFirst(stops, 2, PLANNED_GAP);

            Instant third = calculator.toStop(trip, stops, PLANT, 3, NOW).at();
            Instant eighth = calculator.toStop(trip, stops, PLANT, 8, NOW).at();

            assertThat(third).isBefore(eighth);
        }

        @Test
        void theRunHomeIsCountedAfterTheLastStop() {
            Trip trip = tripAt(2);
            List<TripStop> stops = stops(trip);
            collectFirst(stops, 2, PLANNED_GAP);

            Instant lastStop = calculator.toStop(trip, stops, PLANT, STOPS, NOW).at();
            Instant plant = calculator.toPlant(trip, stops, PLANT, NOW).at();

            // Twenty kilometres back to the plant is not free.
            assertThat(plant).isAfter(lastStop);
        }
    }

    @Nested
    @DisplayName("with a delay")
    class WithDelay {

        @Test
        void aSlowDriverPushesTheEtaLater() {
            Trip onTime = tripAt(4);
            List<TripStop> onTimeStops = stops(onTime);
            collectFirst(onTimeStops, 4, PLANNED_GAP);

            Trip slow = tripAt(4);
            List<TripStop> slowStops = stops(slow);
            // Fifteen minutes between stops against a ten minute plan: half again as slow.
            collectFirst(slowStops, 4, Duration.ofMinutes(15));

            assertThat(calculator.observedDelayFactor(slow, slowStops))
                    .isCloseTo(1.5, within(0.01));
            assertThat(calculator.toPlant(slow, slowStops, PLANT, NOW).at())
                    .isAfter(calculator.toPlant(onTime, onTimeStops, PLANT, NOW).at());
        }

        @Test
        void aFastDriverPullsItEarlier() {
            Trip trip = tripAt(4);
            List<TripStop> stops = stops(trip);
            collectFirst(stops, 4, Duration.ofMinutes(8));

            assertThat(calculator.observedDelayFactor(trip, stops)).isCloseTo(0.8, within(0.01));
        }

        @Test
        void oneCatastrophicStopCannotDoubleTheWholeAfternoon() {
            Trip trip = tripAt(4);
            List<TripStop> stops = stops(trip);
            // Two hours stuck at a gate, against a ten minute plan: a factor of twelve raw.
            collectFirst(stops, 4, Duration.ofMinutes(120));

            assertThat(calculator.observedDelayFactor(trip, stops))
                    .as("clamped, so a single bad leg stays local")
                    .isEqualTo(2.0);
        }

        @Test
        void impossiblyFastIsClampedToo() {
            Trip trip = tripAt(4);
            List<TripStop> stops = stops(trip);
            collectFirst(stops, 4, Duration.ofMinutes(1));

            assertThat(calculator.observedDelayFactor(trip, stops)).isEqualTo(0.7);
        }

        @Test
        void twoStopsAreNotEnoughToCallItATrend() {
            Trip trip = tripAt(2);
            List<TripStop> stops = stops(trip);
            collectFirst(stops, 2, Duration.ofMinutes(25));

            // Genuinely slow so far, but two stops is one interval. Extrapolating from it
            // would let a single slow village rewrite the rest of the run.
            assertThat(calculator.observedDelayFactor(trip, stops)).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("with skipped stops")
    class WithSkips {

        @Test
        void aSkippedStopMovesEveryLaterEtaEarlier() {
            Trip withAll = tripAt(2);
            List<TripStop> allStops = stops(withAll);
            collectFirst(allStops, 2, PLANNED_GAP);

            Trip withSkips = tripAt(2);
            List<TripStop> skipped = stops(withSkips);
            collectFirst(skipped, 2, PLANNED_GAP);
            skipped.get(4).setStatus(TripStopStatus.SKIPPED);
            skipped.get(5).setStatus(TripStopStatus.SKIPPED);

            Instant withoutSkips = calculator.toPlant(withAll, allStops, PLANT, NOW).at();
            Instant withSkipsAt = calculator.toPlant(withSkips, skipped, PLANT, NOW).at();

            // The opposite of what "stops remaining times an average" would produce.
            assertThat(withSkipsAt).isBefore(withoutSkips);
        }

        @Test
        void aDeferredStopCostsNothingEither() {
            Trip trip = tripAt(2);
            List<TripStop> stops = stops(trip);
            collectFirst(stops, 2, PLANNED_GAP);
            long full = calculator.toPlant(trip, stops, PLANT, NOW).remainingMinutes();

            stops.get(6).setStatus(TripStopStatus.DEFERRED);
            long deferred = calculator.toPlant(trip, stops, PLANT, NOW).remainingMinutes();

            assertThat(deferred).isLessThan(full);
        }

        @Test
        void skippingEverythingAheadLeavesOnlyTheRunHome() {
            Trip trip = tripAt(2);
            List<TripStop> stops = stops(trip);
            collectFirst(stops, 2, PLANNED_GAP);
            for (int i = 2; i < STOPS; i++) {
                stops.get(i).setStatus(TripStopStatus.SKIPPED);
            }

            long remaining = calculator.toPlant(trip, stops, PLANT, NOW).remainingMinutes();

            // Only travel is left, no service time at all: well under the full run.
            assertThat(remaining).isLessThan(60);
            assertThat(remaining).isPositive();
        }
    }

    @Nested
    @DisplayName("confidence")
    class Confidence {

        @Test
        void freshPingAndThreeStopsIsHigh() {
            assertThat(calculator.confidence(NOW.minus(Duration.ofMinutes(2)), 3, NOW))
                    .isEqualTo(EtaConfidence.HIGH);
        }

        @Test
        void freshPingButTooEarlyIsMedium() {
            // Nothing wrong with the position; there is simply no pace to extrapolate yet,
            // so the honest answer is a window rather than a time.
            assertThat(calculator.confidence(NOW.minus(Duration.ofMinutes(2)), 2, NOW))
                    .isEqualTo(EtaConfidence.MEDIUM);
        }

        @Test
        void aPingBetweenFiveAndFifteenMinutesIsLow() {
            assertThat(calculator.confidence(NOW.minus(Duration.ofMinutes(9)), 8, NOW))
                    .isEqualTo(EtaConfidence.LOW);
        }

        @Test
        void nothingHeardForOverFifteenMinutesIsLost() {
            assertThat(calculator.confidence(NOW.minus(Duration.ofMinutes(16)), 8, NOW))
                    .isEqualTo(EtaConfidence.LOST);
        }

        @Test
        void neverHeardFromAtAllIsLost() {
            assertThat(calculator.confidence(null, 8, NOW)).isEqualTo(EtaConfidence.LOST);
        }

        @Test
        void stalenessOutranksProgress() {
            // Eight stops of good data do not make an hour-old position trustworthy.
            assertThat(calculator.confidence(NOW.minus(Duration.ofHours(1)), 8, NOW))
                    .isEqualTo(EtaConfidence.LOST);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private Trip tripAt(int currentSeq) {
        Trip trip = new Trip();
        trip.setSession(Session.MORNING);
        trip.setCurrentSeq(currentSeq);
        trip.setLastLat(BigDecimal.valueOf(PLANT.project(0, 2.0 * currentSeq).lat()));
        trip.setLastLng(BigDecimal.valueOf(PLANT.project(0, 2.0 * currentSeq).lng()));
        trip.setLastPingAt(NOW.minus(Duration.ofMinutes(1)));
        return trip;
    }

    /** Ten stops, two kilometres apart, straight out from the plant. */
    private List<TripStop> stops(Trip trip) {
        Village village = new Village();
        village.setCode("V-0001");
        village.setName("Testpur");

        List<TripStop> stops = new ArrayList<>();
        for (int seq = 1; seq <= STOPS; seq++) {
            GeoPoint at = PLANT.project(0, 2.0 * seq);

            CollectionPoint point = new CollectionPoint();
            point.setCode("CP-%05d".formatted(seq));
            point.setVillage(village);
            point.setLat(BigDecimal.valueOf(at.lat()));
            point.setLng(BigDecimal.valueOf(at.lng()));
            point.setServiceMinutes(BigDecimal.valueOf(3.0));

            TripStop stop = new TripStop();
            stop.setTrip(trip);
            stop.setSeq(seq);
            stop.setCollectionPoint(point);
            stop.setPlannedArrivalAt(DEPART.plus(PLANNED_GAP.multipliedBy(seq)));
            stop.setPlannedLitres(BigDecimal.valueOf(12.5));
            stop.setStatus(TripStopStatus.PENDING);
            stops.add(stop);
        }
        return stops;
    }

    /** Marks the first {@code count} stops collected, {@code gap} apart in real time. */
    private void collectFirst(List<TripStop> stops, int count, Duration gap) {
        for (int i = 0; i < count; i++) {
            TripStop stop = stops.get(i);
            stop.setStatus(TripStopStatus.COLLECTED);
            stop.setArrivedAt(DEPART.plus(gap.multipliedBy(i + 1)));
            stop.setActualLitres(BigDecimal.valueOf(12.5));
        }
    }
}
