package com.dairy.milkroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.dairy.milkroute.domain.geo.GeoPoint;
import com.dairy.milkroute.domain.geo.HaversineTravelTime;
import com.dairy.milkroute.domain.geo.TravelParameters;
import com.dairy.milkroute.domain.geo.TravelTimeProvider;
import com.dairy.milkroute.domain.spoilage.SpoilageCalculator;
import com.dairy.milkroute.entity.CollectionPoint;
import com.dairy.milkroute.entity.Plant;
import com.dairy.milkroute.entity.Tanker;
import com.dairy.milkroute.entity.Village;
import com.dairy.milkroute.enums.PlanMode;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.enums.TankerStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The savings loop, tested through outcomes rather than internals.
 *
 * <p>Several of these assert the setup before asserting the result — that a particular
 * merge really is infeasible, or really is fine — so that a test cannot pass by accident if
 * the travel model or the spoilage constants move. If the arithmetic underneath shifts, the
 * setup assertion fails first and says so, instead of the outcome silently becoming
 * vacuous.
 *
 * <p>Ambient is 30 C in most of them. That gives a 180 minute budget, which is tight enough
 * that merges genuinely fail and loose enough that they genuinely succeed — at 22 C almost
 * everything merges and the tests would prove nothing.
 */
class RoutePlannerTest {

    private static final GeoPoint PLANT_AT = new GeoPoint(16.7050, 74.2433);

    /** The seeded solver parameters, written out so a retune does not silently move a test. */
    private static final double EQUITY_EXPONENT = 1.6;
    private static final int MAX_CONSECUTIVE_SKIPS = 3;

    private final TravelTimeProvider travel = haversine();
    private final SpoilageCalculator spoilage =
            new SpoilageCalculator(180.0, 2.0, 6.0, 60.0, 480.0);
    private final SpoilageConstraint spoilageConstraint =
            new SpoilageConstraint(travel, spoilage, 20.0);
    private final ConstraintChecker checker = new ConstraintChecker(List.of(
            new CapacityConstraint(0.95),
            spoilageConstraint,
            new ShiftLengthConstraint(travel, spoilageConstraint),
            new PlantWindowConstraint(travel, spoilageConstraint)));
    private final RoutePlanner planner = new RoutePlanner(
            travel,
            new SequenceOptimiser(travel, spoilageConstraint),
            checker,
            new TankerAssigner(spoilageConstraint, spoilage),
            spoilage,
            new FeasibilityAssessor(spoilage, 0.92),
            EQUITY_EXPONENT,
            MAX_CONSECUTIVE_SKIPS);

    @Nested
    @DisplayName("sequence before constraints")
    class OrderingFirst {

        /**
         * The one that matters. Concatenating two routes produces an arbitrary village
         * order, and an arbitrary order can spend an hour of hot time that a farthest-first
         * order does not. Checking the concatenation as built would reject merges that are
         * perfectly feasible, and the plan would come out needing tankers the dairy does
         * not need.
         */
        @Test
        void mergesAPairThatIsOnlyFeasibleOnceReordered() {
            VillageBlock near = blockAt("NEAR", 8, 0, 15);
            VillageBlock far = blockAt("FAR", 40, 0, 15);
            PlanningContext ctx = context(30.0, List.of(tanker("MH-12-AA-1000", 4000, false)));
            Tanker tanker = ctx.availableTankers().getFirst();

            // Near first means carrying that milk the whole way out to the far village and
            // all the way back: too long.
            assertThat(checker.isFeasible(concat(near, far), tanker, ctx)).isFalse();
            // Far first means the long leg is driven empty, and the same two villages fit.
            assertThat(checker.isFeasible(concat(far, near), tanker, ctx)).isTrue();

            PlanResult result = planner.plan(List.of(near, far), ctx);

            assertThat(result.routes()).hasSize(1);
            assertThat(villageCodesOf(result.routes().getFirst().route()))
                    .containsExactly("FAR", "NEAR");
        }

        /**
         * The case that actually distinguishes the two orderings.
         *
         * <p>With two single-village routes it makes no difference, because the loop
         * generates both ordered pairs and tries {@code concat(a, b)} and
         * {@code concat(b, a)} separately — one of them is already the good order. The
         * difference only appears once a route has two or more villages, where
         * concatenation can reach {@code [X,Y,Z]} and {@code [Z,X,Y]} but not
         * {@code [X,Z,Y]}, and the village that belongs in the middle can only get there by
         * reordering.
         *
         * <p>These four villages were found by searching random geometries for exactly that
         * situation. Reordering first serves them with one tanker; checking first leaves two
         * routes and uses two.
         */
        @Test
        void acceptsAMergeThatCheckingFirstWouldReject() {
            List<VillageBlock> blocks = List.of(
                    blockAtCoords("V0", 16.582887420, 74.324104689, 9.412181),
                    blockAtCoords("V1", 16.766590701, 74.386454883, 15.397024),
                    blockAtCoords("V2", 16.690234639, 74.500310243, 11.258071),
                    blockAtCoords("V3", 16.748595888, 74.320057469, 10.359959));
            PlanningContext ctx = context(27.5252, fourTankers(), 600);

            PlanResult result = planner.plan(blocks, ctx);

            assertThat(result.routes()).hasSize(1);
            assertThat(result.routes().getFirst().route().villageCount()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("merging")
    class Merging {

        @Test
        void collapsesVillagesThatComfortablyShareATanker() {
            List<VillageBlock> blocks = List.of(
                    blockAt("A", 10, 0, 10),
                    blockAt("B", 12, 5, 10),
                    blockAt("C", 14, 10, 10));
            PlanningContext ctx = context(22.0, threeTankers());

            PlanResult result = planner.plan(blocks, ctx);

            // One tanker, not three. Minimising tankers used is the point of the loop.
            assertThat(result.routes()).hasSize(1);
            assertThat(result.routes().getFirst().route().villageCount()).isEqualTo(3);
            assertThat(result.tankersUsed()).isEqualTo(1);
        }

        @Test
        void refusesAMergeNoTankerCouldRun() {
            // Forty kilometres apart in opposite directions: the hop between them alone
            // costs more than the milk has.
            VillageBlock north = blockAt("NORTH", 40, 0, 15);
            VillageBlock south = blockAt("SOUTH", 40, 180, 15);
            PlanningContext ctx = context(30.0, List.of(
                    tanker("MH-12-AA-1000", 4000, false),
                    tanker("MH-12-AB-1001", 4000, false)));
            Tanker tanker = ctx.availableTankers().getFirst();

            // Each is fine alone; together they are not, in either order.
            assertThat(checker.isFeasible(PartialRoute.of(north), tanker, ctx)).isTrue();
            assertThat(checker.isFeasible(PartialRoute.of(south), tanker, ctx)).isTrue();
            assertThat(checker.isFeasible(concat(north, south), tanker, ctx)).isFalse();
            assertThat(checker.isFeasible(concat(south, north), tanker, ctx)).isFalse();

            PlanResult result = planner.plan(List.of(north, south), ctx);

            assertThat(result.routes()).hasSize(2);
            assertThat(result.servedEveryone()).isTrue();
        }

        @Test
        void takesTheBiggestSavingFirst() {
            // Two neighbours far out, and one village on the other side of the plant.
            // Pairing the neighbours saves a whole run home; pairing either with the far
            // side saves almost nothing and does not fit anyway.
            VillageBlock a = blockAt("A", 38, 0, 15);
            VillageBlock b = blockAt("B", 40, 0, 15);
            VillageBlock opposite = blockAt("OPPOSITE", 35, 180, 15);
            PlanningContext ctx = context(30.0, threeTankers());

            PlanResult result = planner.plan(List.of(a, b, opposite), ctx);

            assertThat(result.routes()).hasSize(2);

            List<String> paired = result.routes().stream()
                    .map(route -> villageCodesOf(route.route()))
                    .filter(codes -> codes.size() == 2)
                    .findFirst()
                    .orElseThrow();
            assertThat(paired).containsExactlyInAnyOrder("A", "B");
        }

        @Test
        void neverLosesAVillage() {
            List<VillageBlock> blocks = List.of(
                    blockAt("A", 40, 0, 15),
                    blockAt("B", 38, 10, 15),
                    blockAt("C", 35, 180, 15),
                    blockAt("D", 12, 90, 15));
            PlanningContext ctx = context(30.0, threeTankers());

            PlanResult result = planner.plan(blocks, ctx);

            List<String> placed = java.util.stream.Stream.concat(
                            result.routes().stream().map(AssignedRoute::route),
                            result.unassignedRoutes().stream())
                    .flatMap(route -> villageCodesOf(route).stream())
                    .toList();

            // A village that vanishes between the solver and the plan is a farmer with a
            // full can and nobody coming.
            assertThat(placed).containsExactlyInAnyOrder("A", "B", "C", "D");
        }
    }

    @Nested
    @DisplayName("what comes out")
    class Result {

        @Test
        void reportsRoutesTheFleetCannotCrew() {
            VillageBlock north = blockAt("NORTH", 40, 0, 15);
            VillageBlock south = blockAt("SOUTH", 40, 180, 15);
            // Two routes are needed and there is one tanker.
            PlanningContext ctx = context(30.0, List.of(tanker("MH-12-AA-1000", 4000, false)));

            PlanResult result = planner.plan(List.of(north, south), ctx);

            assertThat(result.routes()).hasSize(1);
            assertThat(result.unassignedRoutes()).hasSize(1);
            assertThat(result.servedEveryone()).isFalse();
            assertThat(result.pointsUnserved()).isEqualTo(1);
            assertThat(result.litresForgone()).isGreaterThan(0);
        }

        @Test
        void givesTheInsulatedTankerToTheRiskiestRoute() {
            VillageBlock far = blockAt("FAR", 40, 0, 15);
            VillageBlock nearer = blockAt("NEARER", 35, 180, 15);
            PlanningContext ctx = context(30.0, List.of(
                    tanker("MH-12-AA-1000", 4000, false),
                    tanker("MH-12-AB-1001", 4000, true)));

            PlanResult result = planner.plan(List.of(far, nearer), ctx);

            assertThat(result.routes()).hasSize(2);
            // Insulation is worth about sixty minutes of budget here. Spending it on the
            // route that already had slack is how a plan loses a load.
            AssignedRoute riskiest = result.routes().getFirst();
            assertThat(villageCodesOf(riskiest.route())).containsExactly("FAR");
            assertThat(riskiest.tanker().isInsulated()).isTrue();
        }

        @Test
        void ordersRoutesRiskiestFirstAndLabelsThemInThatOrder() {
            PlanResult result = planner.plan(
                    List.of(blockAt("FAR", 40, 0, 15), blockAt("NEARER", 35, 180, 15)),
                    context(30.0, threeTankers()));

            assertThat(result.routes()).hasSize(2);
            assertThat(result.routes().get(0).hotMinutes())
                    .isGreaterThan(result.routes().get(1).hotMinutes());
            assertThat(result.routes()).extracting(AssignedRoute::label)
                    .containsExactly("R-01", "R-02");
        }

        @Test
        void slackIsTheMarginLeftOnTheTightestRoute() {
            PlanResult result = planner.plan(
                    List.of(blockAt("FAR", 40, 0, 15), blockAt("NEARER", 35, 180, 15)),
                    context(30.0, threeTankers()));

            AssignedRoute riskiest = result.routes().getFirst();
            assertThat(riskiest.slackMinutes())
                    .isEqualTo(riskiest.holdBudgetMinutes() - riskiest.hotMinutes());
            assertThat(result.minimumSlackMinutes()).isEqualTo(riskiest.slackMinutes());
        }

        @Test
        void carriesTheFeasibilityArithmeticThatChoseTheMode() {
            PlanResult result = planner.plan(
                    List.of(blockAt("A", 10, 0, 10), blockAt("B", 12, 90, 10)),
                    context(22.0, threeTankers()));

            assertThat(result.mode()).isEqualTo(PlanMode.FULL_SERVICE);
            assertThat(result.feasibility().mode()).isEqualTo(PlanMode.FULL_SERVICE);
            assertThat(result.feasibility().requiredHotMinutes()).isGreaterThan(0);
            assertThat(result.feasibility().ratio()).isLessThan(0.92);
            assertThat(result.coveragePct()).isEqualTo(100.0);
        }

        @Test
        void aSingleVillageNeedsNoMerging() {
            PlanResult result = planner.plan(
                    List.of(blockAt("ONLY", 15, 0, 15)),
                    context(22.0, threeTankers()));

            assertThat(result.routes()).hasSize(1);
            assertThat(result.routes().getFirst().route().villageCount()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static PartialRoute concat(VillageBlock first, VillageBlock second) {
        return PartialRoute.concat(PartialRoute.of(first), PartialRoute.of(second));
    }

    private static List<String> villageCodesOf(PartialRoute route) {
        return route.blocks().stream().map(block -> block.village().getCode()).toList();
    }

    private PlanningContext context(double ambientC, List<Tanker> tankers) {
        return context(ambientC, tankers, 300);
    }

    private PlanningContext context(double ambientC, List<Tanker> tankers, int driverMaxShiftMin) {
        return new PlanningContext(
                Session.MORNING,
                LocalDate.of(2026, 10, 15),
                ambientC,
                plant(),
                LocalTime.of(5, 0),
                tankers,
                List.of(),
                driverMaxShiftMin);
    }

    private List<Tanker> fourTankers() {
        List<Tanker> fleet = new java.util.ArrayList<>(threeTankers());
        fleet.add(tanker("MH-12-AD-1003", 4000, false));
        return List.copyOf(fleet);
    }

    /** A one-point village at explicit coordinates, for geometries found by search. */
    private VillageBlock blockAtCoords(String code, double lat, double lng, double internalMinutes) {
        return blockAt(code, new GeoPoint(lat, lng), internalMinutes);
    }

    private List<Tanker> threeTankers() {
        return List.of(
                tanker("MH-12-AA-1000", 4000, false),
                tanker("MH-12-AB-1001", 4000, false),
                tanker("MH-12-AC-1002", 4000, false));
    }

    private Tanker tanker(String regNo, int capacityLitres, boolean insulated) {
        Tanker tanker = new Tanker();
        tanker.setRegNo(regNo);
        tanker.setCapacityLitres(capacityLitres);
        tanker.setInsulated(insulated);
        tanker.setStatus(TankerStatus.AVAILABLE);
        return tanker;
    }

    /** A one-point village {@code km} from the plant on the given bearing. */
    private VillageBlock blockAt(String code, double km, double bearing, double internalMinutes) {
        return blockAt(code, PLANT_AT.project(bearing, km), internalMinutes);
    }

    private VillageBlock blockAt(String code, GeoPoint at, double internalMinutes) {
        Village village = new Village();
        village.setCode(code);
        village.setName(code);
        village.setLat(BigDecimal.valueOf(at.lat()));
        village.setLng(BigDecimal.valueOf(at.lng()));

        CollectionPoint point = new CollectionPoint();
        point.setCode(code + "-CP");
        point.setLat(BigDecimal.valueOf(at.lat()));
        point.setLng(BigDecimal.valueOf(at.lng()));
        point.setServiceMinutes(BigDecimal.valueOf(internalMinutes));
        point.setAvgMorningLitres(BigDecimal.valueOf(500));
        point.setAvgEveningLitres(BigDecimal.valueOf(350));

        double returnLeg = SpoilageConstraint.minutes(
                travel.between(at, PLANT_AT, Session.MORNING));

        return new VillageBlock(
                village, List.of(point), internalMinutes, 500.0, internalMinutes + returnLeg);
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
