package com.dairy.milkroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dairy.milkroute.domain.geo.GeoPoint;
import com.dairy.milkroute.domain.geo.HaversineTravelTime;
import com.dairy.milkroute.domain.geo.TravelParameters;
import com.dairy.milkroute.domain.geo.TravelTimeProvider;
import com.dairy.milkroute.domain.spoilage.SpoilageCalculator;
import com.dairy.milkroute.entity.CollectionPoint;
import com.dairy.milkroute.entity.Plant;
import com.dairy.milkroute.entity.Village;
import com.dairy.milkroute.enums.Session;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The split rule is the interesting part of this class, so most of these exercise it.
 *
 * <p>Service minutes do the work in the fixtures. Points inside a village are a few hundred
 * metres apart, so driving between them is a minute or two and the internal time is almost
 * entirely time spent standing at a stop. Turning service minutes up is therefore the
 * honest way to build a village too big to serve in one go, rather than inventing a village
 * the size of a district.
 */
class VillageSolverTest {

    private static final GeoPoint PLANT_AT = new GeoPoint(16.7050, 74.2433);

    /** Far enough out that the return leg is substantial, which the hot-time tests need. */
    private static final GeoPoint VILLAGE_AT = PLANT_AT.project(0, 20);

    private final TravelTimeProvider travel = haversine();
    private final VillageSolver solver = new VillageSolver(travel);

    @Nested
    @DisplayName("when the village fits the budget")
    class WithinBudget {

        @Test
        void returnsASingleBlock() {
            List<CollectionPoint> points = lineOfPoints("P", 6, 10);

            List<VillageBlock> blocks = solver.solve(village(), points, morning(), 180);

            assertThat(blocks).hasSize(1);
            assertThat(blocks.getFirst().sequence()).hasSize(6);
        }

        @Test
        void internalTimeCoversDrivingAndStanding() {
            List<CollectionPoint> points = lineOfPoints("P", 5, 10);

            VillageBlock block = solver.solve(village(), points, morning(), 180).getFirst();

            // Five stops at ten minutes each, plus the driving between them.
            assertThat(block.internalMinutes()).isGreaterThan(50.0);
            assertThat(block.internalMinutes()).isLessThan(60.0);
        }

        @Test
        void entersAtThePointNearestThePlant() {
            // Points strung out directly away from the plant, handed over farthest-first.
            List<CollectionPoint> points = new ArrayList<>(lineOfPoints("P", 5, 10));
            java.util.Collections.reverse(points);

            VillageBlock block = solver.solve(village(), points, morning(), 180).getFirst();

            // The entry and exit are what the legs between villages are measured from, so
            // starting at the far side would quietly lengthen every route through here.
            assertThat(block.entry().getCode()).isEqualTo("P0");
        }

        @Test
        void takesLitresFromTheSessionBeingPlanned() {
            List<CollectionPoint> points = lineOfPoints("P", 4, 10);

            VillageBlock morning = solver.solve(village(), points, morning(), 180).getFirst();
            VillageBlock evening = solver.solve(village(), points, evening(), 180).getFirst();

            assertThat(morning.litres()).isEqualTo(4 * 60.0);
            assertThat(evening.litres()).isEqualTo(4 * 40.0);
        }

        @Test
        void requiredHotTimeExcludesTheUnloadWait() {
            // hotMinutesRequired is summed over every village by the feasibility assessor,
            // and a tanker unloads once per route rather than once per village. Charging
            // the unload here would add one per village and make a workable dairy look
            // impossible.
            List<CollectionPoint> points = lineOfPoints("P", 4, 10);
            PlanningContext ctx = morning();

            VillageBlock block = solver.solve(village(), points, ctx, 180).getFirst();
            double routeHotTime = new SpoilageConstraint(
                    travel, new SpoilageCalculator(180.0, 2.0, 6.0, 60.0, 480.0), 20.0)
                    .hotMinutes(PartialRoute.of(block), ctx);

            assertThat(routeHotTime - block.hotMinutesRequired())
                    .isCloseTo(ctx.plant().getUnloadMinutes(), org.assertj.core.data.Offset.offset(0.001));
        }
    }

    @Nested
    @DisplayName("the split rule")
    class Splitting {

        @Test
        void splitsAVillageThatCannotBeServedInOneVisit() {
            // Six stops at forty minutes is four hours of work; the milk does not last it.
            List<CollectionPoint> points = twoClusters(3, 40);

            List<VillageBlock> blocks = solver.solve(village(), points, morning(), 180);

            assertThat(blocks).hasSize(2);
            assertThat(blocks).allSatisfy(block ->
                    assertThat(block.internalMinutes()).isLessThanOrEqualTo(180.0));
        }

        @Test
        void splitsGeographicallyRatherThanArbitrarily() {
            // Two clusters a kilometre either side of the village centre. A split by count
            // could interleave them and hand each tanker points from both sides; splitting
            // on the wider axis keeps each half somewhere a tanker can actually work.
            List<CollectionPoint> points = twoClusters(3, 40);

            List<VillageBlock> blocks = solver.solve(village(), points, morning(), 180);

            assertThat(blocks).allSatisfy(block -> {
                var sides = block.sequence().stream()
                        .map(point -> point.getCode().substring(0, 1))
                        .collect(Collectors.toSet());
                assertThat(sides).hasSize(1);
            });
        }

        @Test
        void splitsRepeatedlyUntilEveryBlockFits() {
            // Eight stops at forty minutes against a hundred-minute budget: one split is
            // not enough, so the solver has to recurse.
            List<CollectionPoint> points = lineOfPoints("P", 8, 40);

            List<VillageBlock> blocks = solver.solve(village(), points, morning(), 100);

            assertThat(blocks).hasSize(4);
            assertThat(blocks).allSatisfy(block -> {
                assertThat(block.sequence()).hasSize(2);
                assertThat(block.internalMinutes()).isLessThanOrEqualTo(100.0);
            });
        }

        @Test
        void losesNoPointAndDuplicatesNone() {
            List<CollectionPoint> points = lineOfPoints("P", 9, 40);

            List<CollectionPoint> served = solver.solve(village(), points, morning(), 100)
                    .stream()
                    .flatMap(block -> block.sequence().stream())
                    .toList();

            // A split that drops a point is a farmer nobody visits and nobody notices.
            assertThat(served).containsExactlyInAnyOrderElementsOf(points);
        }

        @Test
        void everyBlockKeepsItsOwnVillage() {
            Village village = village();
            List<VillageBlock> blocks =
                    solver.solve(village, twoClusters(3, 40), morning(), 180);

            assertThat(blocks).allSatisfy(block ->
                    assertThat(block.village()).isSameAs(village));
        }

        @Test
        void asinglePointOverBudgetIsReturnedWhole() {
            // Nothing left to split. The point is genuinely unreachable within the hold
            // window, and the constraint checker rejecting it is the correct outcome —
            // coverage mode records it as UNREACHABLE_WITHIN_HOLD rather than the solver
            // pretending it solved something.
            List<CollectionPoint> points = lineOfPoints("P", 1, 500);

            List<VillageBlock> blocks = solver.solve(village(), points, morning(), 180);

            assertThat(blocks).hasSize(1);
            assertThat(blocks.getFirst().internalMinutes()).isGreaterThan(180.0);
        }

        @Test
        void doesNotSplitAVillageThatOnlyJustFits() {
            List<CollectionPoint> points = lineOfPoints("P", 4, 40);

            // Four stops at forty is 160 minutes of standing plus a little driving.
            assertThat(solver.solve(village(), points, morning(), 400)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("edges")
    class Edges {

        @Test
        void aVillageWithNoActivePointsProducesNoBlocks() {
            assertThat(solver.solve(village(), List.of(), morning(), 180)).isEmpty();
        }

        @Test
        void aBlockAlwaysHoldsAtLeastOnePoint() {
            // The record refuses an empty sequence, so a split that produced an empty half
            // would fail loudly here rather than travelling on as a route with no stops.
            assertThatThrownBy(() -> new VillageBlock(village(), List.of(), 0, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private PlanningContext morning() {
        return context(Session.MORNING);
    }

    private PlanningContext evening() {
        return context(Session.EVENING);
    }

    private PlanningContext context(Session session) {
        return new PlanningContext(
                session,
                LocalDate.of(2026, 10, 15),
                22.0,
                plant(),
                LocalTime.of(5, 0),
                List.of(),
                List.of(),
                300);
    }

    /** Points strung out eastward, 400 m apart, the nearest to the plant first. */
    private List<CollectionPoint> lineOfPoints(String prefix, int count, double serviceMinutes) {
        List<CollectionPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(point(prefix + i, VILLAGE_AT.project(90, 0.4 * i), serviceMinutes));
        }
        return points;
    }

    /** Two clusters, west and east of the village centre, with a clear gap between them. */
    private List<CollectionPoint> twoClusters(int perCluster, double serviceMinutes) {
        List<CollectionPoint> points = new ArrayList<>(perCluster * 2);
        for (int i = 0; i < perCluster; i++) {
            points.add(point("W" + i, VILLAGE_AT.project(270, 0.4 + 0.4 * i), serviceMinutes));
            points.add(point("E" + i, VILLAGE_AT.project(90, 0.4 + 0.4 * i), serviceMinutes));
        }
        return points;
    }

    private CollectionPoint point(String code, GeoPoint at, double serviceMinutes) {
        CollectionPoint point = new CollectionPoint();
        point.setCode(code);
        point.setLat(BigDecimal.valueOf(at.lat()));
        point.setLng(BigDecimal.valueOf(at.lng()));
        point.setServiceMinutes(BigDecimal.valueOf(serviceMinutes));
        point.setAvgMorningLitres(BigDecimal.valueOf(60));
        point.setAvgEveningLitres(BigDecimal.valueOf(40));
        return point;
    }

    private Village village() {
        Village village = new Village();
        village.setCode("V-0001");
        village.setName("Rampur");
        village.setLat(BigDecimal.valueOf(VILLAGE_AT.lat()));
        village.setLng(BigDecimal.valueOf(VILLAGE_AT.lng()));
        return village;
    }

    private Plant plant() {
        Plant plant = new Plant();
        plant.setCode("PLANT-1");
        plant.setName("Kolhapur Chilling Centre");
        plant.setLat(BigDecimal.valueOf(PLANT_AT.lat()));
        plant.setLng(BigDecimal.valueOf(PLANT_AT.lng()));
        plant.setUnloadMinutes(20);
        plant.setOpensAt(LocalTime.of(4, 0));
        plant.setClosesAt(LocalTime.of(22, 0));
        return plant;
    }

    private static TravelTimeProvider haversine() {
        return new HaversineTravelTime(
                new TravelParameters(1.35, 15.0, 26.0, 34.0, 1.15, 0.90));
    }
}