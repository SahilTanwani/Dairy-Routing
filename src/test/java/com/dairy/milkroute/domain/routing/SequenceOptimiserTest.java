package com.dairy.milkroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import org.junit.jupiter.api.Test;

class SequenceOptimiserTest {

    private static final GeoPoint PLANT_AT = new GeoPoint(16.7050, 74.2433);

    private final TravelTimeProvider travel = haversine();
    private final SpoilageCalculator spoilage =
            new SpoilageCalculator(180.0, 2.0, 6.0, 60.0, 480.0);
    private final SpoilageConstraint constraint =
            new SpoilageConstraint(travel, spoilage, 20.0);
    private final SequenceOptimiser optimiser =
            new SequenceOptimiser(travel, constraint);

    @Test
    void putsTheFarthestVillageFirst() {
        VillageBlock near   = blockAt("NEAR",   8,  0.0,   15);
        VillageBlock middle = blockAt("MIDDLE", 25, 0.0,   15);
        VillageBlock far    = blockAt("FAR",    40, 0.0,   15);

        // Handed the worst possible order.
        PartialRoute given = new PartialRoute(List.of(near, middle, far));

        PartialRoute optimised = optimiser.optimise(given, morning());

        assertThat(optimised.blocks())
                .extracting(block -> block.village().getCode())
                .containsExactly("FAR", "MIDDLE", "NEAR");
    }

    @Test
    void neverIncreasesHotTime() {
        PartialRoute given = new PartialRoute(List.of(
                blockAt("A", 12, 20.0,  15),
                blockAt("B", 33, 95.0,  15),
                blockAt("C", 21, 200.0, 15),
                blockAt("D", 40, 310.0, 15),
                blockAt("E", 8,  150.0, 15)));
        PlanningContext ctx = morning();

        double before = constraint.hotMinutes(given, ctx);
        double after  = constraint.hotMinutes(optimiser.optimise(given, ctx), ctx);

        assertThat(after).isLessThanOrEqualTo(before);
    }

    @Test
    void beatsTheNearestFirstOrderingOnScatteredGeography() {
        List<VillageBlock> scattered = List.of(
                blockAt("A", 10, 45.0,  15),
                blockAt("B", 38, 50.0,  15),
                blockAt("C", 35, 60.0,  15),
                blockAt("D", 15, 220.0, 15));
        PlanningContext ctx = morning();

        // What a distance-naive planner would produce: nearest first.
        List<VillageBlock> nearestFirst = scattered.stream()
                .sorted(java.util.Comparator.comparingDouble(block ->
                        SpoilageConstraint.minutes(travel.between(
                                PLANT_AT, block.entryLocation(), ctx.session()))))
                .toList();

        double naive = constraint.hotMinutes(new PartialRoute(nearestFirst), ctx);
        double optimised = constraint.hotMinutes(
                optimiser.optimise(new PartialRoute(scattered), ctx), ctx);

        assertThat(optimised).isLessThan(naive);
    }

    @Test
    void leavesASingleVillageAlone() {
        PartialRoute one = PartialRoute.of(blockAt("ONLY", 20, 0.0, 15));
        assertThat(optimiser.optimise(one, morning()).blocks()).hasSize(1);
    }

    @Test
    void doesNotMutateTheRouteItWasGiven() {
        VillageBlock near = blockAt("NEAR", 8,  0.0, 15);
        VillageBlock far  = blockAt("FAR",  40, 0.0, 15);
        PartialRoute given = new PartialRoute(List.of(near, far));

        optimiser.optimise(given, morning());

        // The savings loop may still discard this candidate; it must be undamaged.
        assertThat(given.blocks()).containsExactly(near, far);
    }

    @Test
    void keepsEveryVillageItWasGiven() {
        List<VillageBlock> blocks = List.of(
                blockAt("A", 10, 0.0,   15),
                blockAt("B", 20, 90.0,  15),
                blockAt("C", 30, 180.0, 15),
                blockAt("D", 40, 270.0, 15));

        PartialRoute optimised =
                optimiser.optimise(new PartialRoute(blocks), morning());

        assertThat(optimised.blocks()).containsExactlyInAnyOrderElementsOf(blocks);
    }

    // ------------------------------------------------------------------ fixtures

    private PlanningContext morning() {
        return new PlanningContext(
                Session.MORNING,
                LocalDate.of(2026, 10, 15),
                22.0,
                plant(),
                LocalTime.of(5, 0),
                List.of(),
                List.of(),
                300);
    }

    /** A village {@code km} from the plant on the given bearing. */
    private VillageBlock blockAt(String code, double km, double bearing, double internalMinutes) {
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

    private static TravelTimeProvider haversine() {
        return new HaversineTravelTime(
                new TravelParameters(1.35, 15.0, 26.0, 34.0, 1.15, 0.90));
    }
}