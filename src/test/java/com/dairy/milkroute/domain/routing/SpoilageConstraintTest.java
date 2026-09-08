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
import com.dairy.milkroute.enums.Session;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * The test that matters here is {@link #farthestFirstBeatsNearestFirstOnHotTime()}.
 * Everything else exists to make its result unambiguous.
 *
 * <p>Villages are placed due north of the plant at chosen distances so the arithmetic is
 * checkable by hand: a route's hot time is the villages it works, the hops between them,
 * the loaded run home, and the unload — and never the empty run out.
 */
class SpoilageConstraintTest {

    private static final GeoPoint PLANT_AT = new GeoPoint(16.7050, 74.2433);

    private final TravelTimeProvider travel = haversine();
    private final SpoilageCalculator spoilage =
            new SpoilageCalculator(180.0, 2.0, 6.0, 60.0, 480.0);
    private final SpoilageConstraint constraint =
            new SpoilageConstraint(travel, spoilage, 20.0);

    // ------------------------------------------------------- the free deadhead

    @Test
    void farthestFirstBeatsNearestFirstOnHotTime() {
        VillageBlock near   = blockAt("NEAR",   8,  15);
        VillageBlock middle = blockAt("MIDDLE", 25, 15);
        VillageBlock far    = blockAt("FAR",    40, 15);

        PartialRoute nearestFirst  = new PartialRoute(List.of(near, middle, far));
        PartialRoute farthestFirst = new PartialRoute(List.of(far, middle, near));

        double nearestHot  = constraint.hotMinutes(nearestFirst,  morning());
        double farthestHot = constraint.hotMinutes(farthestFirst, morning());

        // Same three villages, same service time, same total distance driven. The only
        // difference is which leg the tanker runs empty.
        assertThat(farthestHot).isLessThan(nearestHot);

        // The gain is the run home: nearest-first drives 40 km back loaded,
        // farthest-first drives 8 km back loaded.
        assertThat(nearestHot - farthestHot).isGreaterThan(30.0);
    }

    @Test
    void theOutboundLegIsNotChargedToHotTime() {
        VillageBlock far = blockAt("FAR", 40, 15);
        PlanningContext ctx = morning();

        double outbound = SpoilageConstraint.minutes(
                travel.between(PLANT_AT, far.entryLocation(), ctx.session()));

        // Out and back are the same distance, so hot time is 15 service + one leg home
        // + 20 unload. If the outbound leg were counted it would be one leg higher.
        assertThat(constraint.hotMinutes(PartialRoute.of(far), ctx))
                .isCloseTo(15 + outbound + 20, Offset.offset(0.5));
    }

    @Test
    void hotTimeIsServicePlusTheRunHomePlusUnload() {
        VillageBlock only = blockAt("ONE", 10, 30);
        PlanningContext ctx = morning();

        double homeLeg = SpoilageConstraint.minutes(
                travel.between(only.exitLocation(), PLANT_AT, ctx.session()));

        assertThat(constraint.hotMinutes(PartialRoute.of(only), ctx))
                .isCloseTo(30 + homeLeg + 20, Offset.offset(0.01));
    }

    // ------------------------------------------------------------ pass and fail

    @Test
    void acceptsARouteComfortablyInsideItsBudget() {
        PartialRoute route = PartialRoute.of(blockAt("NEAR", 8, 20));

        ConstraintResult result = constraint.check(route, standardTanker(), morning());

        assertThat(result.passed()).isTrue();
        assertThat(result.constraint()).isEqualTo("SPOILAGE");
        assertThat(result.slack()).isPositive();
    }

    @Test
    void rejectsARouteThatWouldArriveWithSpoiledMilk() {
        PartialRoute route = new PartialRoute(List.of(
                blockAt("V1", 40, 60), blockAt("V2", 35, 60), blockAt("V3", 30, 60),
                blockAt("V4", 25, 60), blockAt("V5", 20, 60), blockAt("V6", 15, 60)));

        ConstraintResult result = constraint.check(route, standardTanker(), evening());

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).contains("hot time");
        assertThat(result.slack()).isNegative();
    }

    @Test
    void theSameRouteCanPassInTheMorningAndFailInTheEvening() {
        // 313 minutes of budget at 22 C against 127 at 35 C: the same work, judged twice.
        PartialRoute route = new PartialRoute(List.of(
                blockAt("V1", 30, 10), blockAt("V2", 20, 10)));

        assertThat(constraint.check(route, standardTanker(), morning()).passed()).isTrue();
        assertThat(constraint.check(route, standardTanker(), evening()).passed()).isFalse();
    }

    @Test
    void insulationIsCheckedAgainstTheSameRoute() {
        // A route whose hot time sits between the bare budget (127) and the insulated
        // one (193) on a hot evening, allowing for the twenty-minute buffer either side.
        PartialRoute route = new PartialRoute(List.of(
                blockAt("V1", 35, 25), blockAt("V2", 25, 25)));

        double hot = constraint.hotMinutes(route, evening());
        assertThat(hot).isBetween(107.0, 173.0);

        assertThat(constraint.check(route, standardTanker(),  evening()).passed()).isFalse();
        assertThat(constraint.check(route, insulatedTanker(), evening()).passed()).isTrue();
    }

    @Test
    void theSafetyBufferRejectsRoutesThatWouldOnlyJustMakeIt() {
        SpoilageConstraint noBuffer   = new SpoilageConstraint(travel, spoilage, 0.0);
        SpoilageConstraint withBuffer = new SpoilageConstraint(travel, spoilage, 20.0);

        PlanningContext evening = evening();
        int budget = spoilage.holdBudgetMinutes(35.0, false);   // 127

        // Grow a village's service time until hot time lands inside the raw budget but
        // inside the buffer zone. Derived rather than hand-tuned, so the test survives a
        // change to the travel model or the spoilage constants.
        VillageBlock probe = null;
        for (int service = 1; service <= 200; service++) {
            VillageBlock candidate = blockAt("V1", 30, service);
            double hot = noBuffer.hotMinutes(PartialRoute.of(candidate), evening);
            if (hot > budget - 20 && hot <= budget) {
                probe = candidate;
                break;
            }
        }
        assertThat(probe).as("no service time lands in the buffer window").isNotNull();

        PartialRoute route = PartialRoute.of(probe);

        // Inside the budget on paper, rejected once model uncertainty is accounted for.
        assertThat(noBuffer.check(route, standardTanker(), evening).passed()).isTrue();
        assertThat(withBuffer.check(route, standardTanker(), evening).passed()).isFalse();
    }

    // ------------------------------------------------------------------ fixtures

    private PlanningContext morning() {
        return context(Session.MORNING, 22.0, LocalTime.of(5, 0));
    }

    private PlanningContext evening() {
        return context(Session.EVENING, 35.0, LocalTime.of(16, 30));
    }

    private PlanningContext context(Session session, double ambientC, LocalTime departAt) {
        return new PlanningContext(
                session,
                LocalDate.of(2026, 10, 15),
                ambientC,
                plant(),
                departAt,
                List.of(standardTanker()),
                List.of(),
                300);
    }

    /** A village {@code km} due north of the plant, costing {@code internalMinutes} to work. */
    private VillageBlock blockAt(String code, double km, double internalMinutes) {
        GeoPoint at = PLANT_AT.project(0.0, km);

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

        return new VillageBlock(village, List.of(point), internalMinutes, 500.0, 0.0);
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

    private Tanker standardTanker() {
        return tanker("MH-12-AA-0001", false);
    }

    private Tanker insulatedTanker() {
        return tanker("MH-12-AA-0002", true);
    }

    private Tanker tanker(String regNo, boolean insulated) {
        Tanker tanker = new Tanker();
        tanker.setRegNo(regNo);
        tanker.setCapacityLitres(4000);
        tanker.setInsulated(insulated);
        return tanker;
    }

    /** The seeded production travel parameters. */
    private static TravelTimeProvider haversine() {
        return new HaversineTravelTime(
                new TravelParameters(1.35, 15.0, 26.0, 34.0, 1.15, 0.90));
    }
}