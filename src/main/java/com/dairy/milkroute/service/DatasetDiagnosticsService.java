package com.dairy.milkroute.service;

import com.dairy.milkroute.config.SolverParameters;
import com.dairy.milkroute.config.TravelTimeFactory;
import com.dairy.milkroute.domain.geo.GeoPoint;
import com.dairy.milkroute.domain.geo.TravelTimeProvider;
import com.dairy.milkroute.domain.routing.FeasibilityReport;
import com.dairy.milkroute.domain.routing.PlanResult;
import com.dairy.milkroute.domain.spoilage.SpoilageCalculator;
import com.dairy.milkroute.dto.DatasetConfig;
import com.dairy.milkroute.dto.response.DatasetDiagnostics;
import com.dairy.milkroute.entity.CollectionPoint;
import com.dairy.milkroute.entity.Plant;
import com.dairy.milkroute.entity.Village;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.repository.CollectionPointRepository;
import com.dairy.milkroute.repository.FarmerRepository;
import com.dairy.milkroute.repository.PlantRepository;
import com.dairy.milkroute.repository.TankerRepository;
import com.dairy.milkroute.repository.VillageRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers the question a dataset config cannot: does this dairy actually bind where it was
 * meant to?
 *
 * <p>The number that matters is the time ratio, required over available hot minutes. Under
 * the feasibility margin the dairy is servable and every point gets collected; over it,
 * planning has to choose who goes without. A config written to be comfortable that quietly
 * lands at 1.4 is the kind of thing best discovered here rather than in front of an audience.
 *
 * <h2>One source of that number</h2>
 *
 * <p>This used to compute the ratio itself, with its own copy of the Q10 formula and its own
 * road-speed bands. The two implementations then drifted, as duplicated arithmetic does:
 * dataset-check reported 0.78 for a morning that {@code FeasibilityAssessor} scored at 1.06,
 * because this side counted only the hold budget while the assessor had learned that a
 * tanker is also bounded by the driver's shift. Two honest-looking numbers for the same
 * question is worse than one wrong one, because there is no way to tell which to act on.
 *
 * <p>So it now runs a real planning pass and reports what the planner reports. Slower — a
 * couple of hundred milliseconds rather than a few — and it cannot disagree with the thing
 * it is meant to be checking.
 */
@Service
public class DatasetDiagnosticsService {

    /** When each session's fleet leaves, for the pass this endpoint runs. */
    private static final LocalTime MORNING_DEPARTURE = LocalTime.of(5, 0);
    private static final LocalTime EVENING_DEPARTURE = LocalTime.of(16, 30);

    private final VillageRepository villageRepo;
    private final CollectionPointRepository pointRepo;
    private final FarmerRepository farmerRepo;
    private final TankerRepository tankerRepo;
    private final PlantRepository plantRepo;
    private final PlanningService planningService;
    private final TravelTimeFactory travelTimeFactory;
    private final SolverParameters parameters;

    public DatasetDiagnosticsService(VillageRepository villageRepo,
                                     CollectionPointRepository pointRepo,
                                     FarmerRepository farmerRepo,
                                     TankerRepository tankerRepo,
                                     PlantRepository plantRepo,
                                     PlanningService planningService,
                                     TravelTimeFactory travelTimeFactory,
                                     SolverParameters parameters) {
        this.villageRepo = villageRepo;
        this.pointRepo = pointRepo;
        this.farmerRepo = farmerRepo;
        this.tankerRepo = tankerRepo;
        this.plantRepo = plantRepo;
        this.planningService = planningService;
        this.travelTimeFactory = travelTimeFactory;
        this.parameters = parameters;
    }

    @Transactional(readOnly = true)
    public DatasetDiagnostics check(DatasetConfig cfg) {
        Session session = Session.valueOf(cfg.defaultSession());
        double ambientC = cfg.defaultAmbientC();
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);

        // The planner's own view, not a second opinion about it.
        PlanResult result = planningService.planWithoutPersisting(
                session, today, departureFor(session), ambientC);
        FeasibilityReport feasibility = result.feasibility();

        Plant plant = plantRepo.findFirstByPrimaryTrueAndActiveTrue()
                .orElseThrow(() -> new IllegalStateException("no primary plant is seeded"));
        GeoPoint plantAt = at(plant.getLat().doubleValue(), plant.getLng().doubleValue());

        Unreachable unreachable = unreachableVillages(plantAt, session, ambientC);

        return new DatasetDiagnostics(
                cfg.name(),
                session.name(),
                ambientC,
                villageRepo.countByActiveTrue(),
                pointRepo.count(),
                pointRepo.countByActiveTrue(),
                farmerRepo.countByActiveTrue(),
                feasibility.tankerCount(),
                round(feasibility.requiredHotMinutes()),
                round(feasibility.availableHotMinutes()),
                round(feasibility.ratio()),
                round(expectedLitres(session)),
                fleetCapacityLitres(),
                round(volumeRatio(session)),
                unreachable.codes().size(),
                unreachable.codes(),
                round(unreachable.farthestKm()));
    }

    /**
     * Villages no single tanker could serve and return from in time, whatever the fleet size.
     *
     * <p>A different question from "was it left out of the plan". A point excluded at the
     * coverage limit could have been served; one flagged here is beyond the reach of the
     * physics, and the honest answer for it is a local chilling unit rather than a better
     * route.
     *
     * <p>Measured against the roomiest tanker on the yard and the real travel model, so it
     * uses the same numbers the constraint checker would.
     */
    private Unreachable unreachableVillages(GeoPoint plant, Session session, double ambientC) {
        SolverParameters.Snapshot params = parameters.snapshot();
        TravelTimeProvider travel = travelTimeFactory.create();
        SpoilageCalculator spoilage = spoilageCalculator(params);

        boolean anyInsulated = tankerRepo.findAll().stream().anyMatch(t -> t.isInsulated());
        double usable = spoilage.holdBudgetMinutes(ambientC, anyInsulated)
                - params.get("safetyBufferMin");

        List<String> codes = new ArrayList<>();
        double farthestKm = 0;

        for (Village village : villageRepo.findByActiveTrueOrderByCodeAsc()) {
            List<CollectionPoint> points =
                    pointRepo.findByVillageIdAndActiveTrueOrderByCodeAsc(village.getId());
            if (points.isEmpty()) {
                continue;
            }

            GeoPoint villageAt = at(village.getLat().doubleValue(), village.getLng().doubleValue());
            farthestKm = Math.max(farthestKm, plant.haversineKm(villageAt));

            double serviceMinutes = points.stream()
                    .mapToDouble(p -> p.getServiceMinutes().doubleValue())
                    .sum();

            // Hot time only: the run out carries no milk, so it is not charged here.
            double returnMinutes =
                    travel.between(villageAt, plant, session).toSeconds() / 60.0;

            if (serviceMinutes + returnMinutes > usable) {
                codes.add(village.getCode());
            }
        }
        return new Unreachable(codes, farthestKm);
    }

    private double expectedLitres(Session session) {
        return pointRepo.findByActiveTrueOrderByCodeAsc().stream()
                .mapToDouble(point -> session == Session.MORNING
                        ? point.getAvgMorningLitres().doubleValue()
                        : point.getAvgEveningLitres().doubleValue())
                .sum();
    }

    private long fleetCapacityLitres() {
        return tankerRepo.findAll().stream().mapToLong(t -> t.getCapacityLitres()).sum();
    }

    private double volumeRatio(Session session) {
        long capacity = fleetCapacityLitres();
        return capacity == 0 ? Double.NaN : expectedLitres(session) / capacity;
    }

    private SpoilageCalculator spoilageCalculator(SolverParameters.Snapshot params) {
        return new SpoilageCalculator(
                params.get("baseHoldMinutesAt30C"),
                params.get("q10Factor"),
                params.get("insulationOffsetC"),
                params.get("minHoldMinutes"),
                params.get("maxHoldMinutes"));
    }

    private static LocalTime departureFor(Session session) {
        return session == Session.MORNING ? MORNING_DEPARTURE : EVENING_DEPARTURE;
    }

    private static GeoPoint at(double lat, double lng) {
        return new GeoPoint(lat, lng);
    }

    private static double round(double value) {
        return Double.isFinite(value) ? Math.round(value * 100.0) / 100.0 : value;
    }

    private record Unreachable(List<String> codes, double farthestKm) {
    }
}
