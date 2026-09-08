package com.dairy.milkroute.service;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.domain.geo.GeoPoint;
import com.dairy.milkroute.dto.DatasetConfig;
import com.dairy.milkroute.dto.ReferenceData;
import com.dairy.milkroute.dto.response.SeedResult;
import com.dairy.milkroute.entity.CollectionPoint;
import com.dairy.milkroute.entity.Driver;
import com.dairy.milkroute.entity.Farmer;
import com.dairy.milkroute.entity.Plant;
import com.dairy.milkroute.entity.SolverParameter;
import com.dairy.milkroute.entity.Tanker;
import com.dairy.milkroute.entity.TemperatureProfile;
import com.dairy.milkroute.entity.Village;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.enums.TankerStatus;
import com.dairy.milkroute.repository.CollectionPointRepository;
import com.dairy.milkroute.repository.DriverRepository;
import com.dairy.milkroute.repository.FarmerRepository;
import com.dairy.milkroute.repository.PlantRepository;
import com.dairy.milkroute.repository.SolverParameterRepository;
import com.dairy.milkroute.repository.TankerRepository;
import com.dairy.milkroute.repository.TemperatureProfileRepository;
import com.dairy.milkroute.repository.VillageRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds a fake dairy from a {@link DatasetConfig}.
 *
 * <p>Two rules govern everything here.
 *
 * <p><strong>Every count comes from the config.</strong> There is no 60, no 1,400 and no
 * 22 anywhere in this class. Changing {@code villageCount} in the YAML from 60 to 200 is
 * the whole of scaling this dairy up, and that claim only survives if it is literally
 * true in the source.
 *
 * <p><strong>One seeded generator drives every draw.</strong> A single {@link Random}
 * created from {@code cfg.seed()} produces the bearings, the village gaps, the point
 * scatter, the herd sizes and the yields, in that order. The same config therefore
 * produces the same dairy every time, which is what lets tests assert exact numbers and
 * lets the demo behave identically twice. Reordering the draws in this class changes the
 * generated dairy even though the seed has not moved, so the sequence below is part of
 * the contract rather than an implementation detail.
 */
@Service
public class SeedService {

    /**
     * Rows per saveAll call. Large enough that the round trips stop mattering, small
     * enough that 1,400 farmers never sit in the persistence context at once.
     *
     * <p>Worth knowing: the identifiers are BIGSERIAL, so Hibernate uses IDENTITY
     * generation and cannot combine these into JDBC batches, because it has to read back
     * each generated key. Chunking still bounds memory and flush size, which is what
     * makes the run predictable, but the wire traffic is one insert per row.
     */
    private static final int BATCH_SIZE = 500;

    /** Fixed cost of stopping at all: park, open the valve, paperwork, close up. */
    private static final double SERVICE_BASE_MINUTES = 2.0;

    /** Extra service time each farmer at a point adds. */
    private static final double SERVICE_MINUTES_PER_FARMER = 0.35;

    /** How a day's yield splits across the two collections. */
    private static final double MORNING_SHARE = 0.60;
    private static final double EVENING_SHARE = 1.0 - MORNING_SHARE;

    /**
     * Plant intake hours, mirroring the column defaults in V2. They are not in the dataset
     * config because they describe the plant's working day rather than the shape of the
     * dairy, and no dataset has yet needed to vary them; the moment one does, they move
     * into {@link DatasetConfig} rather than growing a second value here.
     */
    private static final LocalTime PLANT_OPENS_AT = LocalTime.of(4, 0);
    private static final LocalTime PLANT_CLOSES_AT = LocalTime.of(22, 0);

    /** Degrees of wander applied to a corridor bearing, one standard deviation. */
    private static final double CORRIDOR_WANDER_DEGREES = 8.0;

    /** How unevenly corridors fan out, as a fraction of the even spacing. */
    private static final double BEARING_JITTER_FRACTION = 0.4;

    /** Village gaps vary between 70% and 130% of the nominal spacing. */
    private static final double GAP_VARIATION_MIN = 0.7;
    private static final double GAP_VARIATION_SPAN = 0.6;

    private final VillageRepository villageRepo;
    private final CollectionPointRepository pointRepo;
    private final FarmerRepository farmerRepo;
    private final PlantRepository plantRepo;
    private final TankerRepository tankerRepo;
    private final DriverRepository driverRepo;
    private final TemperatureProfileRepository temperatureRepo;
    private final SolverParameterRepository parameterRepo;
    private final DatasetLoader loader;
    private final ClockProvider clock;

    public SeedService(VillageRepository villageRepo,
                       CollectionPointRepository pointRepo,
                       FarmerRepository farmerRepo,
                       PlantRepository plantRepo,
                       TankerRepository tankerRepo,
                       DriverRepository driverRepo,
                       TemperatureProfileRepository temperatureRepo,
                       SolverParameterRepository parameterRepo,
                       DatasetLoader loader,
                       ClockProvider clock) {
        this.villageRepo = villageRepo;
        this.pointRepo = pointRepo;
        this.farmerRepo = farmerRepo;
        this.plantRepo = plantRepo;
        this.tankerRepo = tankerRepo;
        this.driverRepo = driverRepo;
        this.temperatureRepo = temperatureRepo;
        this.parameterRepo = parameterRepo;
        this.loader = loader;
        this.clock = clock;
    }

    @Transactional
    public SeedResult seed(DatasetConfig cfg) {
        long startedAt = clock.now().toEpochMilli();
        Random rng = new Random(cfg.seed());

        seedReferenceData();

        List<Plant> plants = seedPlants(cfg);
        Plant primary = plants.getFirst();

        List<Village> villages = seedVillages(cfg, primary, rng);
        List<CollectionPoint> points = seedCollectionPoints(cfg, villages, rng);
        int farmers = seedFarmers(cfg, points, rng);

        seedFleet(cfg, primary);
        seedDrivers(cfg);

        return new SeedResult(
                cfg.name(),
                cfg.seed(),
                villageRepo.count(),
                pointRepo.count(),
                farmers,
                tankerRepo.count(),
                driverRepo.count(),
                plantRepo.count(),
                clock.now().toEpochMilli() - startedAt);
    }

    /**
     * Temperature profiles and solver parameters, from {@code datasets/reference.yaml}.
     *
     * <p>These are the same for every dataset but are seeded rather than migrated,
     * because the reseed endpoint truncates both tables.
     */
    private void seedReferenceData() {
        ReferenceData reference = loader.loadReference();

        List<TemperatureProfile> profiles = new ArrayList<>();
        for (ReferenceData.TemperatureRow row : reference.temperatureProfiles()) {
            profiles.add(temperature(row.month(), Session.MORNING, row.morning()));
            profiles.add(temperature(row.month(), Session.EVENING, row.evening()));
        }
        saveInChunks(temperatureRepo, profiles);

        List<SolverParameter> parameters = new ArrayList<>();
        for (ReferenceData.ParameterRow row : reference.solverParameters()) {
            SolverParameter parameter = new SolverParameter();
            parameter.setKey(row.key());
            parameter.setValue(row.value());
            parameter.setDescription(row.description());
            parameters.add(parameter);
        }
        saveInChunks(parameterRepo, parameters);
    }

    private TemperatureProfile temperature(int month, Session session, BigDecimal ambientC) {
        TemperatureProfile profile = new TemperatureProfile();
        profile.setMonthNo((short) month);
        profile.setSession(session);
        profile.setAmbientC(ambientC);
        return profile;
    }

    /**
     * The primary plant sits where the config puts it. Any further plants are spread
     * around it at half the corridor length, far enough to be a genuine alternative
     * destination for a diversion without landing on top of the first.
     */
    private List<Plant> seedPlants(DatasetConfig cfg) {
        GeoPoint origin = new GeoPoint(cfg.plantLat(), cfg.plantLng());
        double spreadKm = cfg.villageDistanceKm().max() / 2;

        List<Plant> plants = new ArrayList<>();
        for (int i = 0; i < cfg.plantCount(); i++) {
            GeoPoint at = i == 0
                    ? origin
                    : origin.project(i * (360.0 / cfg.plantCount()), spreadKm);

            Plant plant = new Plant();
            plant.setCode("PL-%02d".formatted(i + 1));
            plant.setName(i == 0 ? "Central Chilling Plant" : "Chilling Centre %d".formatted(i + 1));
            plant.setLat(coordinate(at.lat()));
            plant.setLng(coordinate(at.lng()));
            plant.setOpensAt(PLANT_OPENS_AT);
            plant.setClosesAt(PLANT_CLOSES_AT);
            plant.setPrimary(i == 0);
            plants.add(plant);
        }
        return saveInChunks(plantRepo, plants);
    }

    /**
     * Villages strung along corridors radiating from the plant, dense near town and
     * thinning with distance.
     *
     * <p>Uniform random dots look obviously fake and, worse, they make the routing problem
     * easier than it is: real villages line up along roads, which is exactly why grouping
     * points by village pays off. Three details do the work — bearings that are not evenly
     * spaced, a per-village wander so a corridor is not a straight line, and gaps that
     * widen as settlement thins.
     *
     * <p>The farthest village is the one that matters. It is what the planner struggles
     * with and what goes critical in the demo.
     */
    private List<Village> seedVillages(DatasetConfig cfg, Plant plant, Random rng) {
        GeoPoint plantAt = new GeoPoint(plant.getLat().doubleValue(), plant.getLng().doubleValue());

        double evenStep = 360.0 / cfg.corridorCount();
        double[] bearings = new double[cfg.corridorCount()];
        for (int i = 0; i < bearings.length; i++) {
            bearings[i] = i * evenStep + (rng.nextDouble() - 0.5) * evenStep * BEARING_JITTER_FRACTION;
        }

        int perCorridor = (int) Math.ceil((double) cfg.villageCount() / cfg.corridorCount());

        List<Village> villages = new ArrayList<>(cfg.villageCount());
        for (double bearing : bearings) {
            double distance = cfg.villageDistanceKm().min();
            double span = cfg.villageDistanceKm().max() - distance;
            double nominalGap = span / perCorridor;

            for (int i = 0; i < perCorridor && villages.size() < cfg.villageCount(); i++) {
                double wandered = bearing + rng.nextGaussian() * CORRIDOR_WANDER_DEGREES;
                GeoPoint at = plantAt.project(wandered, distance);

                Village village = new Village();
                village.setCode("V-%04d".formatted(villages.size() + 1));
                village.setName("Village %d".formatted(villages.size() + 1));
                village.setLat(coordinate(at.lat()));
                village.setLng(coordinate(at.lng()));
                villages.add(village);

                distance += nominalGap * (GAP_VARIATION_MIN + rng.nextDouble() * GAP_VARIATION_SPAN);
            }
        }
        return saveInChunks(villageRepo, villages);
    }

    /**
     * Points scattered inside each village's radius.
     *
     * <p>At a few hundred metres apart, travel between two points in the same village is
     * one or two minutes while travel between villages is tens of minutes. That gap is
     * what makes the two-phase algorithm work: solve within a village, then order the
     * villages.
     */
    private List<CollectionPoint> seedCollectionPoints(DatasetConfig cfg,
                                                       List<Village> villages,
                                                       Random rng) {
        List<CollectionPoint> points = new ArrayList<>();
        for (Village village : villages) {
            GeoPoint centre =
                    new GeoPoint(village.getLat().doubleValue(), village.getLng().doubleValue());
            int count = cfg.pointsPerVillage().pickInt(rng);

            for (int i = 0; i < count; i++) {
                double offsetKm = rng.nextDouble() * cfg.pointScatterMetres() / 1000.0;
                GeoPoint at = centre.project(rng.nextDouble() * 360, offsetKm);

                CollectionPoint point = new CollectionPoint();
                point.setCode("CP-%05d".formatted(points.size() + 1));
                point.setVillage(village);
                point.setLat(coordinate(at.lat()));
                point.setLng(coordinate(at.lng()));
                // Overwritten once the point's farmers are known.
                point.setServiceMinutes(minutes(SERVICE_BASE_MINUTES));
                point.setAvgMorningLitres(BigDecimal.ZERO);
                point.setAvgEveningLitres(BigDecimal.ZERO);
                points.add(point);
            }
        }
        return saveInChunks(pointRepo, points);
    }

    /**
     * Farmers, and the volumes their points are expected to yield.
     *
     * <p>{@code twoFarmerPointRatio} of points get two farmers. That is the case the
     * point-versus-farmer split in the schema exists for: one stop, one visit, two milk
     * records.
     *
     * <p>{@code targetFarmerCount} is a target, not a quota. Once it is met the remaining
     * points get a single farmer each, because chasing an exact total buys nothing and
     * costs a pile of special cases.
     *
     * <p>Note what is not here: day-to-day variance. Volumes are seeded as averages and
     * varied at trip creation instead, so the plan's estimate is always slightly wrong in
     * the way a real plan is.
     */
    private int seedFarmers(DatasetConfig cfg, List<CollectionPoint> points, Random rng) {
        List<Farmer> farmers = new ArrayList<>();
        List<CollectionPoint> touched = new ArrayList<>(points.size());
        int created = 0;

        for (CollectionPoint point : points) {
            int wanted = rng.nextDouble() < cfg.twoFarmerPointRatio() ? 2 : 1;
            double morning = 0;
            double evening = 0;
            int atThisPoint = 0;

            for (int i = 0; i < wanted && created < cfg.targetFarmerCount(); i++) {
                int animals = cfg.animalsPerFarmer().pickInt(rng);
                double daily = animals * cfg.litresPerAnimalPerDay().pick(rng);

                Farmer farmer = new Farmer();
                farmer.setCode("F-%05d".formatted(++created));
                farmer.setName("Farmer %d".formatted(created));
                farmer.setPhone("+9190%08d".formatted(created));
                farmer.setCollectionPoint(point);
                farmer.setAnimalCount((short) animals);
                farmers.add(farmer);

                atThisPoint++;
                morning += daily * MORNING_SHARE;
                evening += daily * EVENING_SHARE;
            }

            // A point past the farmer target has nobody on it and no milk to collect.
            point.setServiceMinutes(minutes(
                    SERVICE_BASE_MINUTES + SERVICE_MINUTES_PER_FARMER * atThisPoint));
            point.setAvgMorningLitres(litres(morning));
            point.setAvgEveningLitres(litres(evening));
            point.setActive(atThisPoint > 0);
            touched.add(point);
        }

        saveInChunks(pointRepo, touched);
        saveInChunks(farmerRepo, farmers);
        return created;
    }

    /**
     * The fleet. Capacities are dealt round-robin from {@code capacityMix}, so
     * {@code [2000, 3000, 5000]} gives an even three-way split and a single entry gives a
     * uniform fleet, with no special case for either.
     *
     * <p>The first {@code insulatedCount} tankers are insulated. That is deliberately the
     * only axis the fleet varies on in this build: insulation is worth several degrees of
     * hold budget, which is what gives tanker assignment a decision to make.
     */
    private void seedFleet(DatasetConfig cfg, Plant homePlant) {
        List<Integer> mix = cfg.capacityMix();

        List<Tanker> tankers = new ArrayList<>(cfg.tankerCount());
        for (int i = 0; i < cfg.tankerCount(); i++) {
            Tanker tanker = new Tanker();
            tanker.setRegNo("MH-12-%s-%04d".formatted(plateLetters(i), 1000 + i));
            tanker.setCapacityLitres(mix.get(i % mix.size()));
            tanker.setInsulated(i < cfg.insulatedCount());
            tanker.setStatus(TankerStatus.AVAILABLE);
            tanker.setHomePlant(homePlant);
            tankers.add(tanker);
        }
        saveInChunks(tankerRepo, tankers);
    }

    private void seedDrivers(DatasetConfig cfg) {
        List<Driver> drivers = new ArrayList<>(cfg.driverCount());
        for (int i = 0; i < cfg.driverCount(); i++) {
            Driver driver = new Driver();
            driver.setCode("D-%04d".formatted(i + 1));
            driver.setName("Driver %d".formatted(i + 1));
            driver.setPhone("+9198%08d".formatted(i + 1));
            drivers.add(driver);
        }
        saveInChunks(driverRepo, drivers);
    }

    /** Two-letter registration series, rolling over after 26 tankers. */
    private String plateLetters(int index) {
        char first = (char) ('A' + (index / 26) % 26);
        char second = (char) ('A' + index % 26);
        return "%c%c".formatted(first, second);
    }

    private BigDecimal coordinate(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    /** service_minutes is NUMERIC(4,1), so one decimal place is the column's own scale. */
    private BigDecimal minutes(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    private BigDecimal litres(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private <T> List<T> saveInChunks(CrudRepository<T, ?> repo, List<T> all) {
        List<T> saved = new ArrayList<>(all.size());
        for (int from = 0; from < all.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, all.size());
            repo.saveAll(all.subList(from, to)).forEach(saved::add);
        }
        return saved;
    }
}
