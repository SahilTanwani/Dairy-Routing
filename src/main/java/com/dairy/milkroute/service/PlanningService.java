package com.dairy.milkroute.service;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.config.SolverParameters;
import com.dairy.milkroute.config.TravelTimeFactory;
import com.dairy.milkroute.domain.geo.GeoPoint;
import com.dairy.milkroute.domain.geo.TravelTimeProvider;
import com.dairy.milkroute.domain.routing.AssignedRoute;
import com.dairy.milkroute.domain.routing.CapacityConstraint;
import com.dairy.milkroute.domain.routing.ConstraintChecker;
import com.dairy.milkroute.domain.routing.FeasibilityAssessor;
import com.dairy.milkroute.domain.routing.FeasibilityReport;
import com.dairy.milkroute.domain.routing.PlanResult;
import com.dairy.milkroute.domain.routing.PlanningContext;
import com.dairy.milkroute.domain.routing.PlanningStrategy;
import com.dairy.milkroute.domain.routing.PlantWindowConstraint;
import com.dairy.milkroute.domain.routing.PointCoverage;
import com.dairy.milkroute.domain.routing.RoutePlanner;
import com.dairy.milkroute.domain.routing.SequenceOptimiser;
import com.dairy.milkroute.domain.routing.ShiftLengthConstraint;
import com.dairy.milkroute.domain.routing.SpoilageConstraint;
import com.dairy.milkroute.domain.routing.TankerAssigner;
import com.dairy.milkroute.domain.routing.VillageBlock;
import com.dairy.milkroute.domain.routing.VillageSolver;
import com.dairy.milkroute.domain.spoilage.SpoilageCalculator;
import com.dairy.milkroute.entity.CollectionPoint;
import com.dairy.milkroute.entity.Driver;
import com.dairy.milkroute.entity.Plant;
import com.dairy.milkroute.entity.Route;
import com.dairy.milkroute.entity.RoutePlan;
import com.dairy.milkroute.entity.RouteStop;
import com.dairy.milkroute.entity.Tanker;
import com.dairy.milkroute.entity.Village;
import com.dairy.milkroute.enums.PlanSource;
import com.dairy.milkroute.enums.PlanStatus;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.enums.TankerStatus;
import com.dairy.milkroute.repository.CollectionPointRepository;
import com.dairy.milkroute.repository.DriverRepository;
import com.dairy.milkroute.repository.PlantRepository;
import com.dairy.milkroute.repository.RoutePlanRepository;
import com.dairy.milkroute.repository.RouteRepository;
import com.dairy.milkroute.repository.RouteStopRepository;
import com.dairy.milkroute.repository.TankerRepository;
import com.dairy.milkroute.repository.VillageRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a request for a plan into rows in the database.
 *
 * <p>Everything clever happens in {@code domain/}: this class assembles the world the solver
 * needs, runs it, and writes down what came back. That division is the point — the solver
 * can be tested in microseconds with no container because it never learns where its inputs
 * came from, and this class stays boring enough to read in one sitting.
 *
 * <p>The order matters and is worth following:
 *
 * <ol>
 *   <li>snapshot the world: fleet, drivers, plant, points, coverage history, temperature;
 *   <li>solve each village internally, which is where 1,250 points become 60 blocks;
 *   <li>ask the feasibility assessor whether everyone can be served;
 *   <li>plan;
 *   <li>persist the plan, its routes and their stops.
 * </ol>
 *
 * <p>Step 3 does not change which planner runs. There is one planner and it degrades
 * honestly, so the assessment decides what the plan is <em>called</em> and what the caller is
 * told, not which algorithm executes. That is deliberate: the hard days are the ones that
 * matter, and running a different, less-exercised code path on exactly those days is how a
 * system fails when it counts.
 */
@Service
public class PlanningService {

    /** Route labels are R-01 upward; two digits is plenty for a fleet of this size. */
    private static final String ROUTE_LABEL_FORMAT = "R-%02d";

    private final VillageRepository villageRepo;
    private final CollectionPointRepository pointRepo;
    private final TankerRepository tankerRepo;
    private final DriverRepository driverRepo;
    private final PlantRepository plantRepo;
    private final RoutePlanRepository planRepo;
    private final RouteRepository routeRepo;
    private final RouteStopRepository routeStopRepo;

    private final TravelTimeFactory travelTimeFactory;
    private final SolverParameters parameters;
    private final CoverageStateService coverageState;
    private final AmbientTemperatureService ambientTemperature;
    private final ClockProvider clock;
    private final ObjectMapper objectMapper;

    public PlanningService(VillageRepository villageRepo,
                           CollectionPointRepository pointRepo,
                           TankerRepository tankerRepo,
                           DriverRepository driverRepo,
                           PlantRepository plantRepo,
                           RoutePlanRepository planRepo,
                           RouteRepository routeRepo,
                           RouteStopRepository routeStopRepo,
                           TravelTimeFactory travelTimeFactory,
                           SolverParameters parameters,
                           CoverageStateService coverageState,
                           AmbientTemperatureService ambientTemperature,
                           ClockProvider clock,
                           ObjectMapper objectMapper) {
        this.villageRepo = villageRepo;
        this.pointRepo = pointRepo;
        this.tankerRepo = tankerRepo;
        this.driverRepo = driverRepo;
        this.plantRepo = plantRepo;
        this.planRepo = planRepo;
        this.routeRepo = routeRepo;
        this.routeStopRepo = routeStopRepo;
        this.travelTimeFactory = travelTimeFactory;
        this.parameters = parameters;
        this.coverageState = coverageState;
        this.ambientTemperature = ambientTemperature;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * Plans a session and writes it down as a DRAFT.
     *
     * @param session      which collection
     * @param businessDate the date being planned for
     * @param departAt     when the fleet leaves the plant
     * @param ambientC     ambient to plan against, or null to read the temperature profile
     */
    @Transactional
    public RoutePlan generate(Session session,
                              LocalDate businessDate,
                              LocalTime departAt,
                              Double ambientC) {
        Solved solved = solve(session, businessDate, departAt, ambientC);
        return persist(solved);
    }

    /**
     * Plans a session without writing anything.
     *
     * <p>The timing advisory needs to plan the same dairy twice at two temperatures and keep
     * neither. Persisting both and deleting one would leave version numbers with holes in
     * them for no reason.
     */
    @Transactional(readOnly = true)
    public PlanResult planWithoutPersisting(Session session,
                                            LocalDate businessDate,
                                            LocalTime departAt,
                                            Double ambientC) {
        return solve(session, businessDate, departAt, ambientC).result();
    }

    // ------------------------------------------------------------------ solving

    private Solved solve(Session session,
                         LocalDate businessDate,
                         LocalTime departAt,
                         Double ambientC) {
        SolverParameters.Snapshot params = parameters.snapshot();
        TravelTimeProvider travel = travelTimeFactory.create();

        Plant plant = plantRepo.findFirstByPrimaryTrueAndActiveTrue()
                .orElseThrow(() -> new IllegalStateException("no primary plant is seeded"));

        double temperature = ambientC != null
                ? ambientC
                : ambientTemperature.ambientC(businessDate, session);

        List<Tanker> fleet = tankerRepo.findByStatusOrderByCapacityLitresDesc(TankerStatus.AVAILABLE);
        List<Driver> drivers = driverRepo.findByActiveTrueOrderByCodeAsc();

        PlanningContext ctx = new PlanningContext(
                session,
                businessDate,
                temperature,
                plant,
                departAt,
                fleet,
                drivers,
                shiftLimitOf(drivers),
                coverageState.coverageAsOf(businessDate));

        SpoilageCalculator spoilage = spoilageCalculator(params);
        List<VillageBlock> blocks = solveVillages(travel, ctx, spoilage, params);

        PlanningStrategy planner = planner(travel, spoilage, params);
        PlanResult result = planner.plan(blocks, ctx);

        return new Solved(ctx, result, travel, blocks.size());
    }

    /**
     * Phase one, over every active village.
     *
     * <p>The hold budget passed in is the best one any tanker could offer today, because a
     * village is only worth splitting if it will not fit in the roomiest tanker on the yard.
     * Splitting against the worst tanker would fragment villages that the insulated ones
     * could have taken whole.
     */
    private List<VillageBlock> solveVillages(TravelTimeProvider travel,
                                             PlanningContext ctx,
                                             SpoilageCalculator spoilage,
                                             SolverParameters.Snapshot params) {
        VillageSolver solver = new VillageSolver(travel);
        int bestBudget = bestHoldBudget(ctx, spoilage);

        List<VillageBlock> blocks = new ArrayList<>();
        for (Village village : villageRepo.findByActiveTrueOrderByCodeAsc()) {
            List<CollectionPoint> points =
                    pointRepo.findByVillageIdAndActiveTrueOrderByCodeAsc(village.getId());
            blocks.addAll(solver.solve(village, points, ctx, bestBudget));
        }
        return blocks;
    }

    private PlanningStrategy planner(TravelTimeProvider travel,
                                     SpoilageCalculator spoilage,
                                     SolverParameters.Snapshot params) {
        SpoilageConstraint spoilageConstraint = new SpoilageConstraint(
                travel, spoilage, params.get("safetyBufferMin"));

        ConstraintChecker checker = new ConstraintChecker(List.of(
                new CapacityConstraint(params.get("capacityHeadroom")),
                spoilageConstraint,
                new ShiftLengthConstraint(travel, spoilageConstraint),
                new PlantWindowConstraint(travel, spoilageConstraint)));

        return new RoutePlanner(
                travel,
                new SequenceOptimiser(travel, spoilageConstraint),
                checker,
                new TankerAssigner(spoilageConstraint, spoilage, checker),
                spoilage,
                spoilageConstraint,
                new FeasibilityAssessor(spoilage, params.get("feasibilityMargin")),
                params.get("equityExponent"),
                params.getInt("maxConsecutiveSkips"));
    }

    private SpoilageCalculator spoilageCalculator(SolverParameters.Snapshot params) {
        return new SpoilageCalculator(
                params.get("baseHoldMinutesAt30C"),
                params.get("q10Factor"),
                params.get("insulationOffsetC"),
                params.get("minHoldMinutes"),
                params.get("maxHoldMinutes"));
    }

    private int bestHoldBudget(PlanningContext ctx, SpoilageCalculator spoilage) {
        return ctx.availableTankers().stream()
                .mapToInt(t -> spoilage.holdBudgetMinutes(ctx.ambientTempC(), t.isInsulated()))
                .max()
                .orElseGet(() -> spoilage.holdBudgetMinutes(ctx.ambientTempC(), false));
    }

    /**
     * The shortest shift any available driver works.
     *
     * <p>The shortest rather than the average: a route is planned before anyone knows which
     * driver takes it, so planning to the average would produce routes that only some of the
     * roster could legally run.
     */
    private int shiftLimitOf(List<Driver> drivers) {
        return drivers.stream()
                .mapToInt(Driver::getMaxShiftMin)
                .min()
                .orElseThrow(() -> new IllegalStateException("no active drivers are seeded"));
    }

    // --------------------------------------------------------------- persistence

    private RoutePlan persist(Solved solved) {
        PlanningContext ctx = solved.ctx();
        PlanResult result = solved.result();

        RoutePlan plan = new RoutePlan();
        plan.setVersion(nextVersionFor(ctx.session()));
        plan.setSession(ctx.session());
        plan.setSource(PlanSource.GENERATED);
        plan.setMode(result.mode());
        plan.setStatus(PlanStatus.DRAFT);
        plan.setPlannedTempC(BigDecimal.valueOf(ctx.ambientTempC())
                .setScale(1, RoundingMode.HALF_UP));
        plan.setEffectiveFrom(ctx.businessDate());
        plan.setGeneratedAt(clock.now());
        plan.setGenerationMs((int) result.generationMs());
        plan.setFeasibility(asJson(result.feasibility()));
        planRepo.save(plan);

        List<Driver> drivers = ctx.availableDrivers();
        int index = 0;

        for (AssignedRoute assigned : result.routes()) {
            Route route = new Route();
            route.setPlan(plan);
            route.setLabel(ROUTE_LABEL_FORMAT.formatted(index + 1));
            route.setTanker(assigned.tanker());
            // Round-robin rather than optimised. Which driver takes which route is a
            // rostering decision the dairy makes on the yard, not one this system is
            // qualified to make, so it assigns and gets out of the way.
            route.setDriver(index < drivers.size() ? drivers.get(index) : null);
            route.setPlant(ctx.plant());
            route.setPlannedDepartAt(ctx.plannedDepartAt());
            route.setEstHotMinutes((int) Math.round(assigned.hotMinutes()));
            route.setHoldBudgetMinutes(assigned.holdBudgetMinutes());
            route.setSlackMinutes((int) Math.round(assigned.slackMinutes()));
            route.setEstVolumeLitres(scaled(assigned.litres(), 2));
            route.setStopCount(assigned.stopCount());

            List<RouteStop> stops = buildStops(route, assigned, ctx, solved.travel());
            route.setEstDistanceKm(scaled(
                    stops.stream().mapToDouble(s -> s.getLegKm().doubleValue()).sum(), 2));

            routeRepo.save(route);
            routeStopRepo.saveAll(stops);
            index++;
        }

        return plan;
    }

    /**
     * Walks the route from the plant, recording each leg and the clock time it arrives.
     *
     * <p>Arrival times are stored so that a driver's phone and the farmer endpoint can both
     * answer "when" without either recomputing the route. They are clock times rather than
     * instants because a plan is a daily shape; the trip is what turns them into a date.
     */
    private List<RouteStop> buildStops(Route route,
                                       AssignedRoute assigned,
                                       PlanningContext ctx,
                                       TravelTimeProvider travel) {
        List<CollectionPoint> points = assigned.route().stops();
        List<RouteStop> stops = new ArrayList<>(points.size());

        GeoPoint from = ctx.plantLocation();
        double minutesFromDeparture = 0;
        int seq = 1;

        for (CollectionPoint point : points) {
            GeoPoint at = locationOf(point);

            double legMinutes = minutesOf(travel.between(from, at, ctx.session()));
            double legKm = travel.roadKm(from, at);

            // Service time at the previous stop is part of getting to this one.
            minutesFromDeparture += legMinutes;

            RouteStop stop = new RouteStop();
            stop.setRoute(route);
            stop.setSeq(seq++);
            stop.setCollectionPoint(point);
            stop.setPlannedArrivalAt(
                    ctx.plannedDepartAt().plusMinutes(Math.round(minutesFromDeparture)));
            stop.setPlannedLitres(litresFor(point, ctx.session()));
            stop.setLegMinutes((int) Math.round(legMinutes));
            stop.setLegKm(scaled(legKm, 2));
            stops.add(stop);

            minutesFromDeparture += point.getServiceMinutes().doubleValue();
            from = at;
        }
        return stops;
    }

    private int nextVersionFor(Session session) {
        return planRepo.findFirstBySessionOrderByVersionDesc(session)
                .map(existing -> existing.getVersion() + 1)
                .orElse(1);
    }

    /**
     * The feasibility report as JSON, for the jsonb column.
     *
     * <p>Uses the application's own mapper rather than a private one, so the shape stored in
     * the column is the same shape the API returns. The report is a record of primitives, so
     * there is nothing here that can fail in a way worth catching.
     */
    private String asJson(FeasibilityReport report) {
        return objectMapper.writeValueAsString(report);
    }

    private static BigDecimal litresFor(CollectionPoint point, Session session) {
        return session == Session.MORNING
                ? point.getAvgMorningLitres()
                : point.getAvgEveningLitres();
    }

    /**
     * A duration in fractional minutes. Duration.toMinutes() truncates, and a route has a
     * dozen legs; losing half a minute on each would put every arrival time out by several.
     */
    private static double minutesOf(java.time.Duration duration) {
        return duration.toSeconds() / 60.0;
    }

    private static GeoPoint locationOf(CollectionPoint point) {
        return new GeoPoint(point.getLat().doubleValue(), point.getLng().doubleValue());
    }

    private static BigDecimal scaled(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    /** One planning run, held together long enough to be written down. */
    private record Solved(PlanningContext ctx,
                          PlanResult result,
                          TravelTimeProvider travel,
                          int villageCount) {
    }

    /** Exposed so the advisory can report what the coverage history looked like. */
    Map<Long, PointCoverage> coverageFor(LocalDate businessDate) {
        return coverageState.coverageAsOf(businessDate);
    }
}
