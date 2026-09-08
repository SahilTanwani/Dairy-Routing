package com.dairy.milkroute.domain.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dairy.milkroute.enums.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TravelMatrixTest {

    private static final TravelParameters PARAMS = new TravelParameters(
            1.35, 15, 26, 34, 1.15, 0.90);

    private static final GeoPoint PLANT = new GeoPoint(16.7050, 74.2433);

    private static List<GeoPoint> villages(int count) {
        List<GeoPoint> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            points.add(PLANT.project(i * 360.0 / count, 5 + i * 3));
        }
        return points;
    }

    @Nested
    @DisplayName("the matrix itself")
    class Matrix {

        private final HaversineTravelTime travel = new HaversineTravelTime(PARAMS);

        @Test
        void coversEveryPointItWasGiven() {
            List<GeoPoint> points = villages(6);
            TravelMatrix matrix = travel.matrix(points, Session.MORNING);

            assertEquals(6, matrix.size());
            points.forEach(point -> assertTrue(matrix.covers(point)));
        }

        @Test
        void agreesWithTheDirectCalculation() {
            List<GeoPoint> points = villages(5);
            TravelMatrix matrix = travel.matrix(points, Session.EVENING);

            for (GeoPoint from : points) {
                for (GeoPoint to : points) {
                    assertEquals(travel.minutesBetween(from, to, Session.EVENING),
                            matrix.minutesBetween(from, to), 1e-12);
                }
            }
        }

        @Test
        void isSymmetricWithAZeroDiagonal() {
            TravelMatrix matrix = travel.matrix(villages(7), Session.MORNING);

            for (int i = 0; i < matrix.size(); i++) {
                assertEquals(0.0, matrix.minutesBetween(i, i), 1e-12);
                for (int j = 0; j < matrix.size(); j++) {
                    assertEquals(matrix.minutesBetween(i, j), matrix.minutesBetween(j, i), 1e-12);
                }
            }
        }

        @Test
        void sortsAndDeduplicatesSoTheSetIsWhatMatters() {
            List<GeoPoint> points = villages(4);
            List<GeoPoint> shuffledWithDuplicates = new ArrayList<>(points.reversed());
            shuffledWithDuplicates.addAll(points);

            TravelMatrix fromClean = travel.matrix(points, Session.MORNING);
            TravelMatrix fromMessy = travel.matrix(shuffledWithDuplicates, Session.MORNING);

            assertEquals(4, fromMessy.size());
            assertEquals(fromClean.points(), fromMessy.points());
        }

        @Test
        void indexSpaceIsItsOwnPointList() {
            TravelMatrix matrix = travel.matrix(villages(5), Session.MORNING);
            GeoPoint third = matrix.points().get(2);

            assertEquals(2, matrix.indexOf(third));
        }

        @Test
        void refusesAPointItDoesNotCover() {
            TravelMatrix matrix = travel.matrix(villages(3), Session.MORNING);
            GeoPoint stranger = PLANT.project(200, 90);

            // Silently computing the leg would hide a plan being built against a different
            // point set than the matrix was made for.
            assertEquals(-1, matrix.indexOf(stranger));
            assertThrows(IllegalArgumentException.class,
                    () -> matrix.minutesBetween(PLANT.project(200, 90), matrix.points().getFirst()));
        }

        @Test
        void buildsOnlyTheUpperTriangle() {
            AtomicInteger legs = new AtomicInteger();
            List<GeoPoint> points = villages(10);
            new TravelMatrix(points, (from, to) -> {
                legs.incrementAndGet();
                return 1.0;
            });

            // n(n-1)/2 rather than n^2: the mirror is free.
            assertEquals(45, legs.get());
        }
    }

    @Nested
    @DisplayName("the cache")
    class Cache {

        @Test
        void returnsTheSameMatrixForTheSameInputs() {
            TravelMatrixCache cache = new TravelMatrixCache();
            HaversineTravelTime travel = new HaversineTravelTime(PARAMS, cache);
            List<GeoPoint> points = villages(5);

            TravelMatrix first = travel.matrix(points, Session.MORNING);
            TravelMatrix second = travel.matrix(points, Session.MORNING);

            assertSame(first, second);
            assertEquals(1, cache.size());
        }

        @Test
        void theSameSetInADifferentOrderHitsTheSameEntry() {
            TravelMatrixCache cache = new TravelMatrixCache();
            HaversineTravelTime travel = new HaversineTravelTime(PARAMS, cache);
            List<GeoPoint> points = villages(5);

            TravelMatrix first = travel.matrix(points, Session.MORNING);
            TravelMatrix reversed = travel.matrix(points.reversed(), Session.MORNING);

            assertSame(first, reversed);
            assertEquals(1, cache.size());
        }

        @Test
        void theSessionIsPartOfTheKey() {
            TravelMatrixCache cache = new TravelMatrixCache();
            HaversineTravelTime travel = new HaversineTravelTime(PARAMS, cache);
            List<GeoPoint> points = villages(4);

            TravelMatrix morning = travel.matrix(points, Session.MORNING);
            TravelMatrix evening = travel.matrix(points, Session.EVENING);

            assertNotEquals(morning.minutesBetween(0, 1), evening.minutesBetween(0, 1));
            assertEquals(2, cache.size());
        }

        @Test
        void movingAPointInvalidatesWithoutAnybodyRememberingTo() {
            List<GeoPoint> before = villages(4);
            List<GeoPoint> after = new ArrayList<>(before);
            after.set(2, after.get(2).project(90, 3));

            assertNotEquals(TravelMatrixCache.key(before, Session.MORNING, PARAMS),
                    TravelMatrixCache.key(after, Session.MORNING, PARAMS));
        }

        @Test
        void addingAPointInvalidates() {
            List<GeoPoint> before = villages(4);
            List<GeoPoint> after = new ArrayList<>(before);
            after.add(PLANT.project(15, 31));

            assertNotEquals(TravelMatrixCache.key(before, Session.MORNING, PARAMS),
                    TravelMatrixCache.key(after, Session.MORNING, PARAMS));
        }

        @Test
        void retuningAParameterInvalidates() {
            // Changing circuityFactor and re-planning is a demonstration the parameter
            // table exists for, and it would prove nothing if the old matrix came back.
            TravelParameters retuned = new TravelParameters(1.50, 15, 26, 34, 1.15, 0.90);
            List<GeoPoint> points = villages(4);

            assertNotEquals(TravelMatrixCache.key(points, Session.MORNING, PARAMS),
                    TravelMatrixCache.key(points, Session.MORNING, retuned));
        }

        @Test
        void aReorderedSetProducesTheSameKey() {
            List<GeoPoint> points = villages(6);

            assertEquals(TravelMatrixCache.key(points, Session.MORNING, PARAMS),
                    TravelMatrixCache.key(points.reversed(), Session.MORNING, PARAMS));
        }

        @Test
        void theKeyIsASha256Digest() {
            String key = TravelMatrixCache.key(villages(3), Session.MORNING, PARAMS);

            assertEquals(64, key.length());
            assertTrue(key.matches("[0-9a-f]{64}"));
        }

        @Test
        void clearingEmptiesIt() {
            TravelMatrixCache cache = new TravelMatrixCache();
            HaversineTravelTime travel = new HaversineTravelTime(PARAMS, cache);
            travel.matrix(villages(3), Session.MORNING);
            assertEquals(1, cache.size());

            cache.clear();

            assertEquals(0, cache.size());
        }

        @Test
        void buildsOncePerDistinctInput() {
            AtomicInteger builds = new AtomicInteger();
            TravelMatrixCache cache = new TravelMatrixCache();
            List<GeoPoint> points = villages(3);

            for (int i = 0; i < 5; i++) {
                cache.get(points, Session.MORNING, PARAMS, () -> {
                    builds.incrementAndGet();
                    return new TravelMatrix(points, (from, to) -> 1.0);
                });
            }

            assertEquals(1, builds.get());
        }
    }
}
