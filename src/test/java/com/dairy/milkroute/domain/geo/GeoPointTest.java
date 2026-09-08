package com.dairy.milkroute.domain.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit, no Spring context: {@link GeoPoint} is domain code and has no container to
 * need.
 *
 * <p>The distances asserted here are analytic rather than looked up. One degree along the
 * equator is exactly one 360th of the circumference for the sphere the class uses, so the
 * expected value can be derived rather than trusted, and a wrong Earth radius or a
 * degrees/radians slip fails immediately instead of being absorbed into a tolerance.
 */
class GeoPointTest {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    /** One degree of arc on a great circle, in km. */
    private static final double DEGREE_KM = 2 * Math.PI * EARTH_RADIUS_KM / 360.0;

    /** The seeded plant: Kolhapur district, Maharashtra. */
    private static final GeoPoint PLANT = new GeoPoint(16.7050, 74.2433);

    @Nested
    @DisplayName("haversineKm")
    class Distance {

        @Test
        void isZeroForThePointItself() {
            assertEquals(0.0, PLANT.haversineKm(PLANT), 1e-9);
        }

        @Test
        void oneDegreeOfLatitudeIsOneDegreeOfArc() {
            double km = new GeoPoint(0, 0).haversineKm(new GeoPoint(1, 0));
            assertEquals(DEGREE_KM, km, 1e-6);
            assertEquals(111.19, km, 0.01);
        }

        @Test
        void oneDegreeOfLongitudeAtTheEquatorIsTheSame() {
            double km = new GeoPoint(0, 0).haversineKm(new GeoPoint(0, 1));
            assertEquals(DEGREE_KM, km, 1e-6);
        }

        @Test
        void longitudeShrinksWithLatitude() {
            // cos(60 degrees) is exactly 0.5, so a degree of longitude there is half as far.
            double atEquator = new GeoPoint(0, 0).haversineKm(new GeoPoint(0, 1));
            double atSixty = new GeoPoint(60, 0).haversineKm(new GeoPoint(60, 1));
            assertEquals(atEquator / 2, atSixty, 0.01);
        }

        @Test
        void antipodesAreHalfTheCircumference() {
            double km = new GeoPoint(0, 0).haversineKm(new GeoPoint(0, 180));
            assertEquals(Math.PI * EARTH_RADIUS_KM, km, 1e-6);
        }

        @Test
        void isSymmetric() {
            GeoPoint village = PLANT.project(37, 18.4);
            assertEquals(PLANT.haversineKm(village), village.haversineKm(PLANT), 1e-9);
        }
    }

    @Nested
    @DisplayName("project")
    class Projection {

        @Test
        void travellingNorthRaisesLatitudeByTheRightArc() {
            GeoPoint north = new GeoPoint(0, 0).project(0, DEGREE_KM);
            assertEquals(1.0, north.lat(), 1e-9);
            assertEquals(0.0, north.lng(), 1e-9);
        }

        @Test
        void travellingEastAtTheEquatorRaisesLongitude() {
            GeoPoint east = new GeoPoint(0, 0).project(90, DEGREE_KM);
            assertEquals(0.0, east.lat(), 1e-9);
            assertEquals(1.0, east.lng(), 1e-9);
        }

        @Test
        void roundTripsThroughHaversineAtEveryBearing() {
            // The seeder projects villages along wandering bearings, so this has to hold
            // in every direction rather than just the cardinal ones.
            for (int bearing = 0; bearing < 360; bearing += 15) {
                GeoPoint village = PLANT.project(bearing, 42.5);
                assertEquals(42.5, PLANT.haversineKm(village), 1e-6,
                        "bearing " + bearing);
            }
        }

        @Test
        void oppositeBearingsReturnToTheOrigin() {
            GeoPoint out = PLANT.project(115, 30);
            GeoPoint back = out.project(115 + 180 + bearingConvergence(PLANT, out), 30);
            assertTrue(PLANT.haversineKm(back) < 0.5,
                    "returned to within 500 m, was " + PLANT.haversineKm(back));
        }

        @Test
        void wrapsAcrossTheAntimeridian() {
            GeoPoint past = new GeoPoint(0, 179).project(90, DEGREE_KM * 2);
            assertEquals(-179.0, past.lng(), 1e-6);
            assertTrue(past.lng() >= -180 && past.lng() <= 180);
        }

        /**
         * On a sphere the reverse bearing is not simply plus 180: meridians converge. This
         * is the correction, and it is small at these distances.
         */
        private double bearingConvergence(GeoPoint from, GeoPoint to) {
            return -(to.lng() - from.lng()) * Math.sin(Math.toRadians(to.lat()));
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void rejectsImpossibleLatitude() {
            assertThrows(IllegalArgumentException.class, () -> new GeoPoint(91, 0));
        }

        @Test
        void rejectsImpossibleLongitude() {
            assertThrows(IllegalArgumentException.class, () -> new GeoPoint(0, 181));
        }
    }
}
