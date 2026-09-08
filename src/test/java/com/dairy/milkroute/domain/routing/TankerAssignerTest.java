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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pairing routes with tankers, and the re-check that stops a plan booking milk to spoil.
 *
 * <p>At 30 C a plain tanker holds milk for 180 minutes and an insulated one for 273. The
 * routes here need about 200 minutes of hot time, which sits in the gap: fine on an
 * insulated tanker, impossible on a plain one. Two such routes and one insulated tanker is
 * therefore the smallest honest reproduction of the bug — the second route is genuinely
 * runnable by a tanker in the fleet, just not by the one left for it.
 */
class TankerAssignerTest {

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
    private final TankerAssigner assigner =
            new TankerAssigner(spoilageConstraint, spoilage, checker);

    @Nested
    @DisplayName("the re-check")
    class Recheck {

        @Test
        void refusesToPairARouteWithATankerThatCannotRunIt() {
            PartialRoute first = routeAt("NORTH", 0);
            PartialRoute second = routeAt("SOUTH", 180);
            PlanningContext ctx = context(insulatedAndPlain());

            Tanker insulated = ctx.availableTankers().getFirst();
            Tanker plain = ctx.availableTankers().get(1);

            // The setup, asserted rather than assumed: each route is runnable by the
            // insulated tanker and by neither plain one.
            assertThat(checker.isFeasible(second, insulated, ctx)).isTrue();
            assertThat(checker.isFeasible(second, plain, ctx)).isFalse();

            TankerAssigner.Assignment assignment =
                    assigner.assign(List.of(first, second), ctx);

            // One insulated tanker, two routes that need one. The second is left unassigned
            // rather than booked onto a tanker whose milk will not survive the journey.
            assertThat(assignment.assigned()).hasSize(1);
            assertThat(assignment.unassigned()).hasSize(1);
            assertThat(assignment.assigned().getFirst().tanker().isInsulated()).isTrue();
        }

        @Test
        void neverProducesANegativeSlackRoute() {
            // The bug this replaces produced a route with -44 minutes of slack on the full
            // dairy: booked to reach the plant three quarters of an hour after its own milk
            // had spoiled. No assigned route may ever have less than zero.
            PlanningContext ctx = context(insulatedAndPlain());

            TankerAssigner.Assignment assignment = assigner.assign(
                    List.of(routeAt("NORTH", 0), routeAt("SOUTH", 180)), ctx);

            assertThat(assignment.assigned())
                    .allSatisfy(route -> assertThat(route.slackMinutes()).isGreaterThanOrEqualTo(0));
        }

        @Test
        void aRejectedRouteDoesNotConsumeTheTankerItRefused() {
            // The riskiest route cannot run even on the best tanker, so it is dropped — but
            // the tanker is still the best one available and must go to the next route down
            // rather than being burned on the refusal.
            PartialRoute impossible = routeAt("IMPOSSIBLE", 0, 400);
            PartialRoute runnable = routeAt("RUNNABLE", 180, 20);
            PlanningContext ctx = context(insulatedAndPlain());

            TankerAssigner.Assignment assignment =
                    assigner.assign(List.of(impossible, runnable), ctx);

            assertThat(assignment.unassigned()).hasSize(1);
            assertThat(assignment.assigned()).hasSize(1);
            assertThat(villageOf(assignment.assigned().getFirst())).isEqualTo("RUNNABLE");
            assertThat(assignment.assigned().getFirst().tanker().isInsulated()).isTrue();
        }

        @Test
        void labelsRunConsecutivelyEvenWhenARouteIsSkipped() {
            // Labels number the routes that exist, not the attempts. A plan whose routes run
            // R-01, R-03 would have ops looking for an R-02 that was never made.
            PlanningContext ctx = context(insulatedAndPlain());

            TankerAssigner.Assignment assignment = assigner.assign(
                    List.of(routeAt("A", 0, 400), routeAt("B", 90, 20), routeAt("C", 180, 20)),
                    ctx);

            assertThat(assignment.assigned()).extracting(AssignedRoute::label)
                    .containsExactly("R-01", "R-02");
        }
    }

    @Nested
    @DisplayName("ordinary pairing")
    class Pairing {

        @Test
        void givesTheInsulatedTankerToTheRiskiestRoute() {
            PartialRoute risky = routeAt("FAR", 0, 20);
            PartialRoute easy = routeAt("NEAR", 180, 5, 12);
            PlanningContext ctx = context(insulatedAndPlain());

            TankerAssigner.Assignment assignment = assigner.assign(List.of(easy, risky), ctx);

            assertThat(assignment.assigned()).hasSize(2);
            assertThat(villageOf(assignment.assigned().getFirst())).isEqualTo("FAR");
            assertThat(assignment.assigned().getFirst().tanker().isInsulated()).isTrue();
        }

        @Test
        void reportsRoutesTheFleetRanOutOfTankersFor() {
            PlanningContext ctx = context(List.of(tanker("ONE", true)));

            TankerAssigner.Assignment assignment = assigner.assign(
                    List.of(routeAt("A", 0, 20), routeAt("B", 180, 20)), ctx);

            assertThat(assignment.assigned()).hasSize(1);
            assertThat(assignment.unassigned()).hasSize(1);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static String villageOf(AssignedRoute route) {
        return route.route().firstBlock().village().getCode();
    }

    /** A one-village route needing about 200 minutes of hot time: the insulated-only band. */
    private PartialRoute routeAt(String code, double bearing) {
        return routeAt(code, bearing, 97);
    }

    private PartialRoute routeAt(String code, double bearing, double internalMinutes) {
        return routeAt(code, bearing, internalMinutes, 40);
    }

    private PartialRoute routeAt(String code, double bearing, double internalMinutes, double km) {
        GeoPoint at = PLANT_AT.project(bearing, km);

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

        return PartialRoute.of(new VillageBlock(
                village, List.of(point), internalMinutes, 500.0, internalMinutes));
    }

    private List<Tanker> insulatedAndPlain() {
        return List.of(tanker("MH-12-AA-1000", true), tanker("MH-12-AB-1001", false));
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
