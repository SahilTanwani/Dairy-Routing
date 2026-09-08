package com.dairy.milkroute.service;

import com.dairy.milkroute.entity.CollectionPoint;
import com.dairy.milkroute.entity.Driver;
import com.dairy.milkroute.entity.Farmer;
import com.dairy.milkroute.entity.Plant;
import com.dairy.milkroute.entity.Route;
import com.dairy.milkroute.entity.RoutePlan;
import com.dairy.milkroute.entity.Tanker;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.entity.TripStop;
import com.dairy.milkroute.entity.Village;
import com.dairy.milkroute.enums.PlanMode;
import com.dairy.milkroute.enums.PlanSource;
import com.dairy.milkroute.enums.PlanStatus;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.enums.TankerStatus;
import com.dairy.milkroute.enums.TripStatus;
import com.dairy.milkroute.enums.TripStopStatus;
import com.dairy.milkroute.repository.CollectionPointRepository;
import com.dairy.milkroute.repository.DriverRepository;
import com.dairy.milkroute.repository.FarmerRepository;
import com.dairy.milkroute.repository.PlantRepository;
import com.dairy.milkroute.repository.RoutePlanRepository;
import com.dairy.milkroute.repository.RouteRepository;
import com.dairy.milkroute.repository.TankerRepository;
import com.dairy.milkroute.repository.TripRepository;
import com.dairy.milkroute.repository.TripStopRepository;
import com.dairy.milkroute.repository.VillageRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.stereotype.Component;

/**
 * The smallest dairy that can have a trip driven down it.
 *
 * <p>Built row by row rather than by running the seeder, because the ingestion tests need to
 * know exactly which farmer is at which stop, and a randomly generated dairy would make every
 * assertion indirect. Codes are made unique per fixture so repeated runs against a database
 * that already holds a seeded dairy do not collide.
 */
@Component
public class VillageFixture {

    private final VillageRepository villageRepo;
    private final CollectionPointRepository pointRepo;
    private final FarmerRepository farmerRepo;
    private final PlantRepository plantRepo;
    private final TankerRepository tankerRepo;
    private final DriverRepository driverRepo;
    private final RoutePlanRepository planRepo;
    private final RouteRepository routeRepo;
    private final TripRepository tripRepo;
    private final TripStopRepository tripStopRepo;

    public VillageFixture(VillageRepository villageRepo,
                          CollectionPointRepository pointRepo,
                          FarmerRepository farmerRepo,
                          PlantRepository plantRepo,
                          TankerRepository tankerRepo,
                          DriverRepository driverRepo,
                          RoutePlanRepository planRepo,
                          RouteRepository routeRepo,
                          TripRepository tripRepo,
                          TripStopRepository tripStopRepo) {
        this.villageRepo = villageRepo;
        this.pointRepo = pointRepo;
        this.farmerRepo = farmerRepo;
        this.plantRepo = plantRepo;
        this.tankerRepo = tankerRepo;
        this.driverRepo = driverRepo;
        this.planRepo = planRepo;
        this.routeRepo = routeRepo;
        this.tripRepo = tripRepo;
        this.tripStopRepo = tripStopRepo;
    }

    /** What the fixture produced, in the shape the tests want to talk about. */
    public record Built(Trip trip, List<TripStop> stops, List<String> farmerCodes) {
    }

    public Built build(int stopCount) {
        String tag = UUID.randomUUID().toString().substring(0, 6);

        Plant plant = new Plant();
        plant.setCode("PL-" + tag);
        plant.setName("Test Plant " + tag);
        plant.setLat(BigDecimal.valueOf(16.705000));
        plant.setLng(BigDecimal.valueOf(74.243300));
        plant.setUnloadMinutes(20);
        plant.setOpensAt(LocalTime.of(4, 0));
        plant.setClosesAt(LocalTime.of(22, 0));
        plantRepo.save(plant);

        Village village = new Village();
        village.setCode("V-" + tag);
        village.setName("Testpur " + tag);
        village.setLat(BigDecimal.valueOf(16.800000));
        village.setLng(BigDecimal.valueOf(74.300000));
        villageRepo.save(village);

        Tanker tanker = new Tanker();
        tanker.setRegNo("MH-" + tag);
        tanker.setCapacityLitres(4000);
        tanker.setInsulated(false);
        tanker.setStatus(TankerStatus.AVAILABLE);
        tanker.setHomePlant(plant);
        tankerRepo.save(tanker);

        Driver driver = new Driver();
        driver.setCode("D-" + tag);
        driver.setName("Test Driver");
        driver.setPhone("+919000000000");
        driverRepo.save(driver);

        RoutePlan plan = new RoutePlan();
        plan.setVersion(nextVersion());
        plan.setSession(Session.MORNING);
        plan.setSource(PlanSource.GENERATED);
        plan.setMode(PlanMode.FULL_SERVICE);
        plan.setStatus(PlanStatus.DRAFT);
        plan.setPlannedTempC(BigDecimal.valueOf(22.0));
        plan.setEffectiveFrom(LocalDate.of(2026, 10, 15));
        plan.setGeneratedAt(Instant.parse("2026-10-14T20:00:00Z"));
        planRepo.save(plan);

        Route route = new Route();
        route.setPlan(plan);
        route.setLabel("R-" + tag.substring(0, 2));
        route.setTanker(tanker);
        route.setDriver(driver);
        route.setPlant(plant);
        route.setPlannedDepartAt(LocalTime.of(5, 0));
        route.setEstHotMinutes(120);
        route.setHoldBudgetMinutes(313);
        route.setSlackMinutes(193);
        route.setEstVolumeLitres(BigDecimal.valueOf(500));
        route.setEstDistanceKm(BigDecimal.valueOf(40));
        route.setStopCount(stopCount);
        routeRepo.save(route);

        Trip trip = new Trip();
        trip.setRoute(route);
        trip.setPlan(plan);
        trip.setBusinessDate(LocalDate.of(2026, 10, 15));
        trip.setSession(Session.MORNING);
        trip.setStatus(TripStatus.SCHEDULED);
        trip.setTanker(tanker);
        trip.setDriver(driver);
        trip.setDestinationPlant(plant);
        trip.setAmbientTempC(BigDecimal.valueOf(22.0));
        trip.setHoldBudgetMinutes(313);
        trip.setCapacityLitres(4000);
        trip.setLitresOnBoard(BigDecimal.ZERO);
        tripRepo.save(trip);

        List<TripStop> stops = new ArrayList<>();
        List<String> farmerCodes = new ArrayList<>();

        for (int seq = 1; seq <= stopCount; seq++) {
            CollectionPoint point = new CollectionPoint();
            point.setCode("CP-%s-%02d".formatted(tag, seq));
            point.setVillage(village);
            point.setLat(BigDecimal.valueOf(16.800000 + seq * 0.001));
            point.setLng(BigDecimal.valueOf(74.300000 + seq * 0.001));
            point.setServiceMinutes(BigDecimal.valueOf(2.4));
            point.setAvgMorningLitres(BigDecimal.valueOf(12.50));
            point.setAvgEveningLitres(BigDecimal.valueOf(8.00));
            pointRepo.save(point);

            Farmer farmer = new Farmer();
            farmer.setCode("F-%s-%02d".formatted(tag, seq));
            farmer.setName("Farmer " + seq);
            farmer.setPhone("+919100000000");
            farmer.setCollectionPoint(point);
            farmer.setAnimalCount((short) 3);
            farmerRepo.save(farmer);
            farmerCodes.add(farmer.getCode());

            TripStop stop = new TripStop();
            stop.setTrip(trip);
            stop.setSeq(seq);
            stop.setCollectionPoint(point);
            stop.setPlannedArrivalAt(
                    Instant.parse("2026-10-15T05:00:00Z").plusSeconds(seq * 600L));
            stop.setPlannedLitres(BigDecimal.valueOf(12.50));
            stop.setStatus(TripStopStatus.PENDING);
            stops.add(tripStopRepo.save(stop));
        }

        return new Built(trip, stops, farmerCodes);
    }

    /** Plan versions are unique per session, and the seeded dairy may already hold some. */
    private int nextVersion() {
        return planRepo.findFirstBySessionOrderByVersionDesc(Session.MORNING)
                .map(existing -> existing.getVersion() + 1)
                .orElse(1);
    }
}
