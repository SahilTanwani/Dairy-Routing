package com.dairy.milkroute.domain.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dairy.milkroute.enums.Session;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit, no Spring context: the travel model is domain code built with {@code new}.
 *
 * <p>The parameters here are the seeded values from {@code reference.yaml}, written out
 * rather than loaded, so a test failure means the formula moved and not that somebody
 * retuned the dairy.
 */
class HaversineTravelTimeTest {

    private static final TravelParameters PARAMS = new TravelParameters(
            1.35,   // circuityFactor
            15,     // speedUnder2Km
            26,     // speed2To10Km
            34,     // speedOver10Km
            1.15,   // morningSpeedFactor
            0.90);  // eveningSpeedFactor

    private static final GeoPoint PLANT = new GeoPoint(16.7050, 74.2433);

    private final HaversineTravelTime travel = new HaversineTravelTime(PARAMS);

    /** A point the given straight-line distance from the plant, on an arbitrary bearing. */
    private static GeoPoint straightKmAway(double km) {
        return PLANT.project(37, km);
    }

    private static double minutes(Duration duration) {
        return duration.toSeconds() / 60.0;
    }

    @Nested
    @DisplayName("the worked example from the spec")
    class WorkedExample {

        // 10.4 km straight-line, which the circuity factor turns into 14.04 km of road.
        // Over 10 km, so the district-road band applies: 34 km/h before the session factor.
        private final GeoPoint far = straightKmAway(10.4);

        @Test
        void straightLineDistanceIsTheStatedTenPointFour() {
            assertEquals(10.4, PLANT.haversineKm(far), 1e-6);
        }

        @Test
        void roadDistanceIsFourteenKm() {
            assertEquals(14.04, travel.roadKm(PLANT, far), 0.01);
        }

        @Test
        void morningTakesAboutTwentyOnePointFiveMinutes() {
            // 14.04 km at 34 x 1.15 = 39.1 km/h, which is 21.54 min.
            assertEquals(21.5, minutes(travel.between(PLANT, far, Session.MORNING)), 0.5);
        }

        @Test
        void eveningTakesAboutTwentySevenPointFourMinutes() {
            // The same road at 34 x 0.90 = 30.6 km/h, which is 27.53 min.
            assertEquals(27.4, minutes(travel.between(PLANT, far, Session.EVENING)), 0.5);
        }

        @Test
        void theEveningRunIsTheSlowerOne() {
            assertTrue(travel.between(PLANT, far, Session.EVENING)
                    .compareTo(travel.between(PLANT, far, Session.MORNING)) > 0,
                    "evening traffic should cost time, not save it");
        }
    }

    @Nested
    @DisplayName("speed bands")
    class SpeedBands {

        // Bands are chosen on road distance, so the straight-line thresholds are the road
        // ones divided by the circuity factor: 2 / 1.35 = 1.481 km and 10 / 1.35 = 7.407 km.

        @Test
        void aShortHopBetweenPointsUsesTheVillageLaneSpeed() {
            assertEquals(15 * 1.15, travel.speedKmph(1.9, Session.MORNING), 1e-9);
        }

        @Test
        void aMediumHopUsesTheConnectingRoadSpeed() {
            assertEquals(26 * 1.15, travel.speedKmph(5.0, Session.MORNING), 1e-9);
        }

        @Test
        void aLongHopUsesTheDistrictRoadSpeed() {
            assertEquals(34 * 1.15, travel.speedKmph(20.0, Session.MORNING), 1e-9);
        }

        @Test
        void theBandsSwitchAtTwoAndTenRoadKm() {
            // Exactly on a threshold belongs to the faster band: the comparison is <.
            assertEquals(15 * 1.15, travel.speedKmph(1.999999, Session.MORNING), 1e-9);
            assertEquals(26 * 1.15, travel.speedKmph(2.0, Session.MORNING), 1e-9);
            assertEquals(26 * 1.15, travel.speedKmph(9.999999, Session.MORNING), 1e-9);
            assertEquals(34 * 1.15, travel.speedKmph(10.0, Session.MORNING), 1e-9);
        }

        @Test
        void theBandIsChosenOnRoadDistanceNotStraightLine() {
            // 8 km straight is 10.8 km of road, so it is a district road even though the
            // straight-line distance is under ten.
            GeoPoint eightKm = straightKmAway(8);
            double expected = 8 * 1.35 / (34 * 1.15) * 60;
            assertEquals(expected, minutes(travel.between(PLANT, eightKm, Session.MORNING)), 0.02);
        }

        @Test
        void aVillageLaneHopCostsOneOrTwoMinutes() {
            // This is the assumption the two-phase algorithm rests on: points inside a
            // village are minutes apart while villages are tens of minutes apart.
            double hop = minutes(travel.between(PLANT, straightKmAway(0.4), Session.MORNING));
            assertTrue(hop >= 1 && hop <= 2, "expected 1-2 min for a 400 m hop, was " + hop);
        }
    }

    @Nested
    @DisplayName("general properties")
    class Properties {

        @Test
        void aPointIsNoDistanceFromItself() {
            assertEquals(Duration.ZERO, travel.between(PLANT, PLANT, Session.MORNING));
        }

        @Test
        void isSymmetric() {
            GeoPoint village = straightKmAway(23);
            assertEquals(travel.between(PLANT, village, Session.EVENING),
                    travel.between(village, PLANT, Session.EVENING));
        }

        @Test
        void timeGrowsWithDistance() {
            Duration near = travel.between(PLANT, straightKmAway(5), Session.MORNING);
            Duration far = travel.between(PLANT, straightKmAway(25), Session.MORNING);
            assertTrue(far.compareTo(near) > 0);
        }

        @Test
        void minutesAgreeWithTheDuration() {
            GeoPoint village = straightKmAway(17.3);
            assertEquals(minutes(travel.between(PLANT, village, Session.EVENING)),
                    travel.minutesBetween(PLANT, village, Session.EVENING), 1e-12);
        }
    }

    @Nested
    @DisplayName("parameter validation")
    class Validation {

        @Test
        void rejectsAZeroSpeed() {
            // A zero speed divides the journey by nothing and reports every point in the
            // dairy as unreachable, which is worse than failing to start.
            assertThrows(IllegalArgumentException.class,
                    () -> new TravelParameters(1.35, 0, 26, 34, 1.15, 0.90));
        }

        @Test
        void rejectsANegativeCircuityFactor() {
            assertThrows(IllegalArgumentException.class,
                    () -> new TravelParameters(-1, 15, 26, 34, 1.15, 0.90));
        }
    }
}
