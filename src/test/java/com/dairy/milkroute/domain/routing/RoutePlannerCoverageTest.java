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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The equity term and the three-strike rule, tested through the merge ordering they live in.
 *
 * <p>The geometry is deliberately symmetric: ANCHOR sits between NORTH and SOUTH, which are
 * mirror images of each other at the same distance with the same volume. Efficiency
 * therefore cannot separate them, and whichever one gets served is decided purely by the
 * coverage history the test sets up. With one tanker and a budget that fits two villages but
 * not three, the village that loses the ranking is the village that goes unserved — which is
 * exactly the decision the equity term exists to make.
 */
class RoutePlannerCoverageTest {

    private static final GeoPoint PLANT_AT = new GeoPoint(16.7050, 74.2433);

    private static final double EQUITY_EXPONENT = 1.6;
    private static final int MAX_CONSECUTIVE_SKIPS = 3;

    /**
     * The skip count in the three-strike fixture, written as its own literal rather than
     * reusing the parameter above. Deriving the test data from the threshold would make the
     * test agree with itself no matter what the threshold was set to.
     */
    private static final int SKIPS_AT_THE_LIMIT = 3;

    /** Enough budget for two of these villages, not three. */
    private static final double AMBIENT_C = 30.0;

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
            new TankerAssigner(spoilageConstraint, spoilage, checker),
            spoilage,
            spoilageConstraint,
            new FeasibilityAssessor(spoilage, 0.92),
            EQUITY_EXPONENT,
            MAX_CONSECUTIVE_SKIPS);

    private final VillageBlock anchor = blockAt("ANCHOR", 1L, 30, 0);
    private final VillageBlock north = blockAt("NORTH", 2L, 32, 10);
    private final VillageBlock south = blockAt("SOUTH", 3L, 32, 350);

    @Test
    void aNeglectedVillageOutranksAnEquallyEfficientFreshOne() {
        // NORTH and SOUTH are mirror images, so efficiency is a wash and only the history
        // separates them: five days unserved against served yesterday.
        Map<Long, PointCoverage> coverage = coverage(
                served(1L, 1), served(2L, 5), served(3L, 1));

        PlanResult result = planner.plan(
                List.of(anchor, north, south), context(coverage, oneTanker()));

        assertThat(servedVillages(result)).contains("NORTH").doesNotContain("SOUTH");
    }

    @Test
    void theSameTwoVillagesSwapWhenTheHistorySwaps() {
        // The mirror of the previous test. If this and the one above both pass, the outcome
        // is being decided by the coverage data rather than by an accident of geometry.
        Map<Long, PointCoverage> coverage = coverage(
                served(1L, 1), served(2L, 1), served(3L, 5));

        PlanResult result = planner.plan(
                List.of(anchor, north, south), context(coverage, oneTanker()));

        assertThat(servedVillages(result)).contains("SOUTH").doesNotContain("NORTH");
    }

    @Test
    void aVillageAtTheSkipLimitJumpsTheRankingEntirely() {
        // SOUTH was served yesterday and would lose on every term of the score, but it has
        // been skipped three sessions running. Three strikes is a rule, not a preference:
        // it stops competing and goes first.
        Map<Long, PointCoverage> coverage = coverage(
                served(1L, 1),
                new Entry(2L, PointCoverage.servedDaysAgo(0, 6)),
                new Entry(3L, PointCoverage.servedDaysAgo(SKIPS_AT_THE_LIMIT, 1)));

        PlanResult result = planner.plan(
                List.of(anchor, north, south), context(coverage, oneTanker()));

        assertThat(servedVillages(result)).contains("SOUTH").doesNotContain("NORTH");
    }

    @Test
    void aVillageWithNoHistoryOutranksOneWithRecordedNeglect() {
        // NORTH has been waiting ten days, which is a long time. SOUTH has never been
        // served at all, which is worse, and no number of days should be able to overtake
        // it — so it is ranked as its own case rather than as a very large day count.
        Map<Long, PointCoverage> coverage = coverage(served(1L, 1), served(2L, 10));

        PlanResult result = planner.plan(
                List.of(anchor, north, south), context(coverage, oneTanker()));

        assertThat(servedVillages(result)).contains("SOUTH").doesNotContain("NORTH");
    }

    @Test
    void aDairyWithNoHistoryAtAllStillPlans() {
        // The first ever plan: every village is unserved, the equity term separates nothing,
        // and the ranking falls back to efficiency. It must not divide by zero or refuse.
        PlanResult result = planner.plan(
                List.of(anchor, north, south), context(Map.of(), oneTanker()));

        assertThat(result.routes()).hasSize(1);
        assertThat(result.pointsServed()).isEqualTo(2);
    }

    @Test
    void aPlanThatCannotServeEveryoneSaysSo() {
        PlanResult result = planner.plan(
                List.of(anchor, north, south), context(Map.of(), oneTanker()));

        // The mode on the plan is the arithmetic's verdict, not the strategy's name. One
        // tanker cannot cover three villages at 30 C and the plan has to admit it.
        assertThat(result.mode()).isEqualTo(PlanMode.COVERAGE_OPTIMISATION);
        assertThat(result.feasibility().fullServicePossible()).isFalse();
        assertThat(result.servedEveryone()).isFalse();
    }

    @Test
    void reportsCoverageAsPointsAndLitres() {
        PlanResult result = planner.plan(
                List.of(anchor, north, south), context(Map.of(), oneTanker()));

        assertThat(result.pointsTotal()).isEqualTo(3);
        assertThat(result.pointsServed()).isEqualTo(2);
        assertThat(result.pointsUnserved()).isEqualTo(1);
        assertThat(result.coveragePct()).isCloseTo(66.67, org.assertj.core.data.Offset.offset(0.01));
        assertThat(result.litresCollected()).isLessThan(result.litresTotal());
        assertThat(result.litresTotal())
                .isEqualTo(result.litresCollected() + result.litresForgone());
    }

    @Test
    void everyUnservedVillageIsAvailableForAnExclusionRow() {
        PlanResult result = planner.plan(
                List.of(anchor, north, south), context(Map.of(), oneTanker()));

        // T5.5 writes one plan_exclusion per point from exactly this list.
        assertThat(result.unservedBlocks()).hasSize(1);
        assertThat(result.unservedBlocks().getFirst().sequence()).hasSize(1);
    }

    // ------------------------------------------------------------------ fixtures

    private List<String> servedVillages(PlanResult result) {
        return result.routes().stream()
                .flatMap(route -> route.route().blocks().stream())
                .map(block -> block.village().getCode())
                .toList();
    }

    private record Entry(Long pointId, PointCoverage coverage) { }

    private static Entry served(Long pointId, int daysAgo) {
        return new Entry(pointId, PointCoverage.servedDaysAgo(0, daysAgo));
    }

    private static Map<Long, PointCoverage> coverage(Entry... entries) {
        Map<Long, PointCoverage> map = new HashMap<>();
        for (Entry entry : entries) {
            map.put(entry.pointId(), entry.coverage());
        }
        return map;
    }

    private PlanningContext context(Map<Long, PointCoverage> coverage, List<Tanker> tankers) {
        return new PlanningContext(
                Session.MORNING,
                LocalDate.of(2026, 10, 15),
                AMBIENT_C,
                plant(),
                LocalTime.of(5, 0),
                tankers,
                List.of(),
                300,
                coverage);
    }

    private List<Tanker> oneTanker() {
        Tanker tanker = new Tanker();
        tanker.setRegNo("MH-12-AA-1000");
        tanker.setCapacityLitres(4000);
        tanker.setInsulated(false);
        tanker.setStatus(TankerStatus.AVAILABLE);
        return List.of(tanker);
    }

    /** A one-point village with a stable id, so coverage history can be attached to it. */
    private VillageBlock blockAt(String code, Long pointId, double km, double bearing) {
        GeoPoint at = PLANT_AT.project(bearing, km);

        Village village = new Village();
        village.setCode(code);
        village.setName(code);
        village.setLat(BigDecimal.valueOf(at.lat()));
        village.setLng(BigDecimal.valueOf(at.lng()));

        CollectionPoint point = new CollectionPoint();
        point.setId(pointId);
        point.setCode(code + "-CP");
        point.setLat(BigDecimal.valueOf(at.lat()));
        point.setLng(BigDecimal.valueOf(at.lng()));
        point.setServiceMinutes(BigDecimal.valueOf(15));
        point.setAvgMorningLitres(BigDecimal.valueOf(500));
        point.setAvgEveningLitres(BigDecimal.valueOf(350));

        double returnLeg = SpoilageConstraint.minutes(
                travel.between(at, PLANT_AT, Session.MORNING));

        return new VillageBlock(village, List.of(point), 15.0, 500.0, 15.0 + returnLeg);
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
