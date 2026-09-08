package com.dairy.milkroute.service;

import com.dairy.milkroute.config.SolverParameters;
import com.dairy.milkroute.domain.spoilage.SpoilageCalculator;
import com.dairy.milkroute.entity.Driver;
import com.dairy.milkroute.entity.Route;
import com.dairy.milkroute.entity.RoutePlan;
import com.dairy.milkroute.entity.RouteStop;
import com.dairy.milkroute.entity.Tanker;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.entity.TripStop;
import com.dairy.milkroute.enums.PlanStatus;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.enums.SkipReason;
import com.dairy.milkroute.enums.TankerStatus;
import com.dairy.milkroute.enums.TripStatus;
import com.dairy.milkroute.enums.TripStopStatus;
import com.dairy.milkroute.error.PlanNotFoundException;
import com.dairy.milkroute.repository.DriverRepository;
import com.dairy.milkroute.repository.RoutePlanRepository;
import com.dairy.milkroute.repository.RouteRepository;
import com.dairy.milkroute.repository.RouteStopRepository;
import com.dairy.milkroute.repository.TankerRepository;
import com.dairy.milkroute.repository.TripRepository;
import com.dairy.milkroute.repository.TripStopRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the published plan into trips the fleet will actually run.
 *
 * <p>The important thing this does is <strong>copy</strong>. Every {@code route_stop} becomes
 * a {@code trip_stop}, and from that moment the trip never reads the plan again.
 *
 * <p>That is not defensive duplication, it is the whole point. Ops publishes a new plan at
 * 05:30 while twenty-two tankers are on the road; without the copy, the stop list changes
 * under a driver who is halfway through it, and the farmer endpoint starts promising visits
 * from a plan nobody is running. With the copy, a republish is invisible to everything
 * already moving — which is the only behaviour a dispatcher can reason about.
 *
 * <p>The other job is reconciling the plan with reality. A plan generated last night assigned
 * tankers and drivers that may since have gone into maintenance or called in sick. Where a
 * substitute exists it is used; where none does, the trip is created anyway as BLOCKED, with
 * its stops, so that the work is visible and someone can act on it. Creating nothing would be
 * the quiet failure: a village simply not collected from, and no record of why.
 */
@Service
public class TripCreationService {

    private static final Logger log = LoggerFactory.getLogger(TripCreationService.class);

    private final RoutePlanRepository planRepo;
    private final RouteRepository routeRepo;
    private final RouteStopRepository routeStopRepo;
    private final TripRepository tripRepo;
    private final TripStopRepository tripStopRepo;
    private final TankerRepository tankerRepo;
    private final DriverRepository driverRepo;
    private final SolverParameters parameters;
    private final AmbientTemperatureService ambientTemperature;

    public TripCreationService(RoutePlanRepository planRepo,
                               RouteRepository routeRepo,
                               RouteStopRepository routeStopRepo,
                               TripRepository tripRepo,
                               TripStopRepository tripStopRepo,
                               TankerRepository tankerRepo,
                               DriverRepository driverRepo,
                               SolverParameters parameters,
                               AmbientTemperatureService ambientTemperature) {
        this.planRepo = planRepo;
        this.routeRepo = routeRepo;
        this.routeStopRepo = routeStopRepo;
        this.tripRepo = tripRepo;
        this.tripStopRepo = tripStopRepo;
        this.tankerRepo = tankerRepo;
        this.driverRepo = driverRepo;
        this.parameters = parameters;
        this.ambientTemperature = ambientTemperature;
    }

    /**
     * Creates one trip per route on the published plan.
     *
     * <p>The business date is passed in rather than derived from the clock. An evening
     * session that runs past midnight still belongs to the day it started, and a trip filed
     * under tomorrow because a tanker was late is a trip nobody can find.
     */
    @Transactional
    public List<Trip> createForSession(Session session, LocalDate businessDate) {
        RoutePlan plan = planRepo.findBySessionAndStatus(session, PlanStatus.PUBLISHED)
                .orElseThrow(() -> new PlanNotFoundException(
                        ("no published plan for the %s session; nothing can be dispatched. "
                                + "Publish a plan first.").formatted(session)));

        double ambientC = plan.getPlannedTempC().doubleValue();
        SpoilageCalculator spoilage = spoilageCalculator();

        Set<Long> takenTankers = new HashSet<>();
        Set<Long> takenDrivers = new HashSet<>();
        tripRepo.findByBusinessDateAndSession(businessDate, session).forEach(existing -> {
            takenTankers.add(existing.getTanker().getId());
            takenDrivers.add(existing.getDriver().getId());
        });

        List<Trip> created = new ArrayList<>();
        for (Route route : routeRepo.findByPlanIdOrderByLabelAsc(plan.getId())) {

            // Running the job twice is a no-op, guaranteed by UNIQUE (route_id,
            // business_date, session) and checked here so the second run is quiet.
            if (tripRepo.findByRouteIdAndBusinessDateAndSession(
                    route.getId(), businessDate, session).isPresent()) {
                continue;
            }

            Tanker tanker = availableTanker(route, takenTankers);
            Driver driver = availableDriver(route, takenDrivers);

            Trip trip = new Trip();
            trip.setRoute(route);
            trip.setPlan(plan);
            trip.setBusinessDate(businessDate);
            trip.setSession(session);
            trip.setDestinationPlant(route.getPlant());
            trip.setAmbientTempC(plan.getPlannedTempC());

            if (tanker == null || driver == null) {
                // Blocked, not absent. A route nobody can crew is a fact ops has to see.
                log.error("Route {} on plan {} cannot be crewed for {} {}: {}. Trip is BLOCKED.",
                        route.getLabel(), plan.getId(), businessDate, session,
                        tanker == null ? "no available tanker" : "no available driver");
                trip.setStatus(TripStatus.BLOCKED);
                trip.setTanker(tanker != null ? tanker : route.getTanker());
                trip.setDriver(driver != null ? driver : route.getDriver());
            } else {
                trip.setStatus(TripStatus.SCHEDULED);
                trip.setTanker(tanker);
                trip.setDriver(driver);
            }

            if (trip.getTanker() == null || trip.getDriver() == null) {
                log.error("Route {} has neither an assigned nor a substitute {}; skipping",
                        route.getLabel(), trip.getTanker() == null ? "tanker" : "driver");
                continue;
            }

            // Copied onto the trip, never read back from the tanker. Editing a tanker
            // mid-morning must not move a deadline that is already running.
            trip.setCapacityLitres(trip.getTanker().getCapacityLitres());
            trip.setHoldBudgetMinutes(
                    spoilage.holdBudgetMinutes(ambientC, trip.getTanker().isInsulated()));
            trip.setLitresOnBoard(BigDecimal.ZERO);

            tripRepo.save(trip);
            tripStopRepo.saveAll(snapshotStops(trip, route, businessDate));

            takenTankers.add(trip.getTanker().getId());
            takenDrivers.add(trip.getDriver().getId());
            created.add(trip);
        }

        log.info("Created {} trips for {} {} from plan {}",
                created.size(), businessDate, session, plan.getId());
        return created;
    }

    /**
     * The snapshot itself: one trip stop per route stop, with the plan's clock times resolved
     * against the trip's date.
     *
     * <p>A point deactivated since planning still gets a stop, immediately marked skipped.
     * Leaving it out would make the trip's stop list disagree with the plan it came from, and
     * a farmer asking why nobody came deserves a row that says so rather than a silence.
     */
    private List<TripStop> snapshotStops(Trip trip, Route route, LocalDate businessDate) {
        List<TripStop> stops = new ArrayList<>();

        for (RouteStop planned : routeStopRepo.findByRouteIdOrderBySeqAsc(route.getId())) {
            TripStop stop = new TripStop();
            stop.setTrip(trip);
            stop.setRouteStop(planned);
            stop.setSeq(planned.getSeq());
            stop.setCollectionPoint(planned.getCollectionPoint());
            stop.setPlannedArrivalAt(at(businessDate, planned));
            stop.setPlannedLitres(planned.getPlannedLitres());

            if (!planned.getCollectionPoint().isActive()) {
                stop.setStatus(TripStopStatus.SKIPPED);
                stop.setSkipReason(SkipReason.POINT_INACTIVE);
            } else {
                stop.setStatus(TripStopStatus.PENDING);
            }
            stops.add(stop);
        }
        return stops;
    }

    /**
     * The planned tanker if it is still available and unclaimed, otherwise a substitute of at
     * least the same capacity and insulation, otherwise nothing.
     *
     * <p>"At least" matters: swapping an insulated tanker for a plain one on the riskiest
     * route would silently undo the assignment decision and cost an hour of hold budget on
     * exactly the route that could least afford it.
     */
    private Tanker availableTanker(Route route, Set<Long> taken) {
        Tanker planned = route.getTanker();
        if (planned != null
                && planned.getStatus() == TankerStatus.AVAILABLE
                && !taken.contains(planned.getId())) {
            return planned;
        }

        int neededCapacity = planned == null ? 0 : planned.getCapacityLitres();
        boolean neededInsulation = planned != null && planned.isInsulated();

        return tankerRepo.findByStatusOrderByCapacityLitresDesc(TankerStatus.AVAILABLE).stream()
                .filter(candidate -> !taken.contains(candidate.getId()))
                .filter(candidate -> candidate.getCapacityLitres() >= neededCapacity)
                .filter(candidate -> !neededInsulation || candidate.isInsulated())
                .findFirst()
                .orElse(null);
    }

    private Driver availableDriver(Route route, Set<Long> taken) {
        Driver planned = route.getDriver();
        if (planned != null && planned.isActive() && !taken.contains(planned.getId())) {
            return planned;
        }
        return driverRepo.findByActiveTrueOrderByCodeAsc().stream()
                .filter(candidate -> !taken.contains(candidate.getId()))
                .findFirst()
                .orElse(null);
    }

    private static Instant at(LocalDate businessDate, RouteStop planned) {
        return planned.getPlannedArrivalAt().atDate(businessDate).toInstant(ZoneOffset.UTC);
    }

    private SpoilageCalculator spoilageCalculator() {
        SolverParameters.Snapshot params = parameters.snapshot();
        return new SpoilageCalculator(
                params.get("baseHoldMinutesAt30C"),
                params.get("q10Factor"),
                params.get("insulationOffsetC"),
                params.get("minHoldMinutes"),
                params.get("maxHoldMinutes"));
    }
}
