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
import com.dairy.milkroute.enums.TankerStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The merge loop must not build more insulated-only routes than there are insulated tankers.
 *
 * <p>Every merge it considers is individually feasible — some tanker in the fleet can run it
 * — and that was exactly the trap. Six insulated tankers and a dozen routes that only an
 * insulated tanker can run is a plan counting on tankers the dairy does not own; the
 * assigner then refuses what it cannot crew and the coverage the plan promised evaporates.
 *
 * <p>The fixture is that situation in miniature. At 30 C a plain tanker has 160 usable
 * minutes and an insulated one 253. Four villages, each cheap enough to run alone on a plain
 * tanker, but any pair of them lands between the two — runnable only on the insulated one,
 * of which there is exactly one.
 */
class RoutePlannerFleetFitTest {

    private static final GeoPoint PLANT_AT = new GeoPoint(16.7050, 74.2433);
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
            1.6,
            3);

    private final VillageBlock north = blockAt("NORTH", 0);
    private final VillageBlock east = blockAt("EAST", 90);
    private final VillageBlock south = blockAt("SOUTH", 180);
    private final VillageBlock west = blockAt("WEST", 270);

    @Test
    void doesNotBuildMoreInsulatedOnlyRoutesThanThereAreInsulatedTankers() {
        PlanningContext ctx = context(oneInsulatedAndThreePlain());
        Tanker insulated = ctx.availableTankers().getFirst();
        Tanker plain = ctx.availableTankers().get(1);

        // The setup, asserted rather than assumed. Alone, a village runs on a plain tanker;
        // paired, it needs the insulated one.
        assertThat(checker.isFeasible(PartialRoute.of(north), plain, ctx)).isTrue();
        assertThat(checker.isFeasible(pair(north, east), plain, ctx)).isFalse();
        assertThat(checker.isFeasible(pair(north, east), insulated, ctx)).isTrue();
        assertThat(checker.isFeasible(pair(south, west), plain, ctx)).isFalse();
        assertThat(checker.isFeasible(pair(south, west), insulated, ctx)).isTrue();

        PlanResult result = planner.plan(List.of(north, east, south, west), ctx);

        // At most one merged pair, because there is exactly one tanker that could run it.
        // The other two villages stay as single routes a plain tanker can take.
        assertThat(result.routes()).hasSize(3);
        assertThat(result.unassignedRoutes()).isEmpty();
        assertThat(result.pointsServed()).isEqualTo(4);
        assertThat(result.coveragePct()).isEqualTo(100.0);
    }

    @Test
    void everyRouteItBuildsCanActuallyBeCrewed() {
        PlanResult result = planner.plan(
                List.of(north, east, south, west), context(oneInsulatedAndThreePlain()));

        // The point of the gate: a plan that promises coverage it cannot crew is worse than
        // a smaller plan that can be.
        assertThat(result.routes())
                .allSatisfy(route -> assertThat(route.slackMinutes()).isGreaterThanOrEqualTo(0));
        assertThat(result.unassignedRoutes()).isEmpty();
    }

    @Test
    void mergesFreelyWhenTheFleetCanCrewTheResult() {
        // Same villages, but now four insulated tankers. Nothing is holding the loop back,
        // so it should pair them up — the gate must not be a blanket brake on merging.
        PlanResult result = planner.plan(
                List.of(north, east, south, west), context(fourInsulated()));

        assertThat(result.routes()).hasSize(2);
        assertThat(result.routes())
                .allSatisfy(route -> assertThat(route.route().villageCount()).isEqualTo(2));
        assertThat(result.unassignedRoutes()).isEmpty();
    }

    @Test
    void refusesToMergeBeyondTheNumberOfTankers() {
        // One tanker, four villages: no merge can make four routes crewable by one tanker,
        // and the loop should not pretend otherwise by building routes nobody can drive.
        PlanResult result = planner.plan(
                List.of(north, east, south, west), context(List.of(tanker("SOLO", true))));

        assertThat(result.routes()).hasSize(1);
        assertThat(result.routes().getFirst().slackMinutes()).isGreaterThanOrEqualTo(0);
    }

    // ------------------------------------------------------------------ fixtures

    private PartialRoute pair(VillageBlock first, VillageBlock second) {
        return PartialRoute.concat(PartialRoute.of(first), PartialRoute.of(second));
    }

    /** A one-village route: cheap alone, expensive in company. */
    private VillageBlock blockAt(String code, double bearing) {
        GeoPoint at = PLANT_AT.project(bearing, 20);

        Village village = new Village();
        village.setCode(code);
        village.setName(code);
        village.setLat(BigDecimal.valueOf(at.lat()));
        village.setLng(BigDecimal.valueOf(at.lng()));

        CollectionPoint point = new CollectionPoint();
        point.setCode(code + "-CP");
        point.setLat(BigDecimal.valueOf(at.lat()));
        point.setLng(BigDecimal.valueOf(at.lng()));
        point.setServiceMinutes(BigDecimal.valueOf(30));
        point.setAvgMorningLitres(BigDecimal.valueOf(500));
        point.setAvgEveningLitres(BigDecimal.valueOf(350));

        return new VillageBlock(village, List.of(point), 30.0, 500.0, 30.0);
    }

    private List<Tanker> oneInsulatedAndThreePlain() {
        return List.of(
                tanker("MH-12-AA-1000", true),
                tanker("MH-12-AB-1001", false),
                tanker("MH-12-AC-1002", false),
                tanker("MH-12-AD-1003", false));
    }

    private List<Tanker> fourInsulated() {
        return List.of(
                tanker("MH-12-AA-1000", true),
                tanker("MH-12-AB-1001", true),
                tanker("MH-12-AC-1002", true),
                tanker("MH-12-AD-1003", true));
    }

    private Tanker tanker(String regNo, boolean insulated) {
        Tanker tanker = new Tanker();
        tanker.setRegNo(regNo);
        tanker.setCapacityLitres(4000);
        tanker.setInsulated(insulated);
        tanker.setStatus(TankerStatus.AVAILABLE);
        return tanker;
    }

    private PlanningContext context(List<Tanker> tankers) {
        return new PlanningContext(
                Session.MORNING,
                LocalDate.of(2026, 10, 15),
                AMBIENT_C,
                plant(),
                LocalTime.of(5, 0),
                tankers,
                List.of(),
                600);
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
