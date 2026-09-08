package com.dairy.milkroute.service;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.config.SolverParameters;
import com.dairy.milkroute.domain.spoilage.SpoilageCalculator;
import com.dairy.milkroute.domain.tracking.EtaEstimate;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.enums.AlertSeverity;
import com.dairy.milkroute.enums.AlertType;
import com.dairy.milkroute.enums.RiskLevel;
import com.dairy.milkroute.enums.TripStatus;
import com.dairy.milkroute.repository.TripRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Watches the milk that is already on the road.
 *
 * <p>Everything else in this system decides what should happen. This decides whether what is
 * happening is still going to work, and it is the part that runs while nobody is looking.
 *
 * <h2>The one-way ratchet</h2>
 *
 * <p>If ambient rises mid-trip, the hold budget shrinks and the deadline moves earlier,
 * immediately. If ambient then falls again, <strong>the budget does not grow back</strong>.
 * Milk that has already spent an hour at 37 °C did not become fresher when a cloud went over
 * — bacterial damage is cumulative, and a budget that recovered would silently clear an
 * alert on a trip that is in real trouble. Shrink only, always.
 *
 * <p>The comparison is against the trip's <em>current</em> budget rather than the one it
 * started with, which is what makes the ratchet hold across repeated sweeps: once cut to
 * 127 minutes, a later reading of 22 °C computes 313 and is simply not smaller, so nothing
 * moves.
 *
 * <h2>Losing the tanker is not the same as losing the milk</h2>
 *
 * <p>When pings stop, the monitor keeps counting on the planned timeline. The tanker is
 * invisible; the milk is still ageing. A monitor that went quiet when tracking dropped would
 * go quiet at exactly the moment it was most needed, and the dispatcher would find out at the
 * weighbridge.
 *
 * <h2>Why 80 and 95</h2>
 *
 * <p>WARNING at 80% of the budget is about thirty-five minutes of notice on a 180 minute
 * one — enough to divert, to call ahead, to skip the last two stops. CRITICAL at 95% is
 * where the options have run out and the question becomes which load to save.
 */
@Service
public class SpoilageMonitorService {

    /** Sixty seconds: fast enough to be useful, slow enough not to matter. */
    private static final long SWEEP_MILLIS = 60_000;

    private static final Logger log = LoggerFactory.getLogger(SpoilageMonitorService.class);

    private final TripRepository tripRepo;
    private final TripTrackingService tracking;
    private final AlertService alerts;
    private final AmbientTemperatureService ambientTemperature;
    private final SolverParameters parameters;
    private final ClockProvider clock;

    public SpoilageMonitorService(TripRepository tripRepo,
                                  TripTrackingService tracking,
                                  AlertService alerts,
                                  AmbientTemperatureService ambientTemperature,
                                  SolverParameters parameters,
                                  ClockProvider clock) {
        this.tripRepo = tripRepo;
        this.tracking = tracking;
        this.alerts = alerts;
        this.ambientTemperature = ambientTemperature;
        this.parameters = parameters;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = SWEEP_MILLIS)
    public void sweep() {
        sweep(null);
    }

    /**
     * One pass over every trip on the road, optionally against a stated ambient temperature.
     *
     * <p>Transactional and loading its own trips, which matters more than it looks. Handing
     * this method a list of trips fetched elsewhere means handing it detached entities, and
     * the first {@code trip.getTanker()} then fails on a lazy proxy with no session — an
     * error that gets caught per trip and logged, so the sweep appears to run while doing
     * nothing at all. Owning the query keeps the entities managed.
     *
     * @param ambientOverrideC ambient right now, or null to read the temperature profile
     */
    @Transactional
    public void sweep(Double ambientOverrideC) {
        List<Trip> active = tripRepo.findByStatusIn(
                List.of(TripStatus.IN_PROGRESS, TripStatus.RETURNING));

        for (Trip trip : active) {
            try {
                check(trip, ambientOverrideC);
            } catch (RuntimeException e) {
                // One bad trip must not stop the sweep. The other twenty-one are still
                // carrying milk and still need watching.
                log.error("Monitor failed for trip {}: {}", trip.getId(), e.getMessage(), e);
            }
        }
    }

    /** Assesses one trip against the temperature profile for its date and session. */
    @Transactional
    public RiskLevel check(Trip trip) {
        return check(trip, null);
    }

    /**
     * Assesses one trip, optionally against a stated ambient temperature.
     *
     * <p>The override exists because {@code temperature_profile} holds one figure per month
     * per session. Read from it alone, ambient never changes during a trip, the budget can
     * therefore never shrink, and the one-way ratchet below — the thing that protects milk
     * on a day that turns hot — is unreachable code that no test could ever exercise.
     *
     * <p>So the simulation and the ratchet test can say what the air is doing. A real
     * deployment with a weather feed would pass the reading here too; the profile is the
     * fallback, not the design.
     *
     * @param ambientOverrideC ambient right now, or null to read the profile
     */
    @Transactional
    public RiskLevel check(Trip trip, Double ambientOverrideC) {
        // An empty tanker has no clock running. There is nothing aboard to spoil.
        if (trip.getFirstCollectionAt() == null) {
            return trip.getRiskLevel();
        }

        SolverParameters.Snapshot params = parameters.snapshot();
        SpoilageCalculator spoilage = spoilageCalculator(params);

        ratchetDown(trip, spoilage, ambientOverrideC);

        EtaEstimate eta = tracking.refresh(trip);
        long projectedAgeMin = Duration.between(trip.getFirstCollectionAt(), eta.at()).toMinutes();
        double utilisation = (double) projectedAgeMin / trip.getHoldBudgetMinutes();

        RiskLevel level = levelFor(utilisation, params);

        // Tracking loss is its own risk, and it outranks the arithmetic: the projection is
        // still being made, but nobody can see whether it is still true.
        if (trackingLost(trip, params)) {
            level = worseOf(level, RiskLevel.LOST);
        }

        if (level != trip.getRiskLevel()) {
            RiskLevel previous = trip.getRiskLevel();
            trip.setRiskLevel(level);
            tripRepo.save(trip);
            announce(trip, previous, level, projectedAgeMin, utilisation);
        }
        return level;
    }

    /**
     * Shrinks the budget if the day got hotter. Never grows it.
     *
     * <p>The deadline is recomputed from {@code first_collection_at}, which was frozen at the
     * first collection, so a tightened deadline is still anchored to when the milk actually
     * went aboard.
     */
    private void ratchetDown(Trip trip, SpoilageCalculator spoilage, Double ambientOverrideC) {
        double currentAmbient = ambientOverrideC != null
                ? ambientOverrideC
                : ambientTemperature.ambientC(trip.getBusinessDate(), trip.getSession());

        int current = spoilage.holdBudgetMinutes(currentAmbient, trip.getTanker().isInsulated());

        if (current < trip.getHoldBudgetMinutes()) {
            int was = trip.getHoldBudgetMinutes();
            trip.setHoldBudgetMinutes(current);
            trip.setSpoilageDeadlineAt(
                    trip.getFirstCollectionAt().plus(Duration.ofMinutes(current)));
            trip.setAmbientTempC(BigDecimal.valueOf(currentAmbient)
                    .setScale(1, RoundingMode.HALF_UP));
            tripRepo.save(trip);

            alerts.raise(AlertType.DEADLINE_TIGHTENED, AlertSeverity.WARNING, trip,
                    AlertService.keyFor(AlertType.DEADLINE_TIGHTENED, trip.getId(),
                            AlertSeverity.WARNING),
                    ("Ambient reached %.1f C; hold budget cut from %d to %d minutes and the "
                            + "deadline moved to %s.")
                            .formatted(currentAmbient, was, current, trip.getSpoilageDeadlineAt()),
                    null);
        }
    }

    private RiskLevel levelFor(double utilisation, SolverParameters.Snapshot params) {
        if (utilisation >= params.get("spoilageCriticalPct")) {
            return RiskLevel.CRITICAL;
        }
        if (utilisation >= params.get("spoilageWarnPct")) {
            return RiskLevel.WARNING;
        }
        return RiskLevel.OK;
    }

    private boolean trackingLost(Trip trip, SolverParameters.Snapshot params) {
        if (trip.getLastPingAt() == null) {
            return true;
        }
        long silentMinutes = Duration.between(trip.getLastPingAt(), clock.now()).toMinutes();
        return silentMinutes > params.getInt("trackingLostMinutes");
    }

    /**
     * Raises or clears an alert on the change.
     *
     * <p>Only on the change. The sweep runs sixty times an hour and the alert is for the
     * transition, not the condition.
     */
    private void announce(Trip trip,
                          RiskLevel previous,
                          RiskLevel level,
                          long projectedAgeMin,
                          double utilisation) {

        log.info("Trip {} risk {} -> {} (projected {} min against a {} min budget, {}%)",
                trip.getId(), previous, level, projectedAgeMin,
                trip.getHoldBudgetMinutes(), Math.round(utilisation * 100));

        if (level == RiskLevel.OK) {
            alerts.resolveFor(AlertType.SPOILAGE_RISK, trip.getId());
            return;
        }

        AlertSeverity severity = level == RiskLevel.CRITICAL
                ? AlertSeverity.CRITICAL
                : AlertSeverity.WARNING;

        AlertType type = level == RiskLevel.LOST
                ? AlertType.TRACKING_LOST
                : AlertType.SPOILAGE_RISK;

        String message = level == RiskLevel.LOST
                ? ("No position for over %d minutes. Still counting on the planned timeline: "
                        + "projected %d minutes against a %d minute budget.")
                        .formatted(parameters.snapshot().getInt("trackingLostMinutes"),
                                projectedAgeMin, trip.getHoldBudgetMinutes())
                : ("Projected %d minutes of milk age against a %d minute budget (%d%%). "
                        + "Deadline %s.")
                        .formatted(projectedAgeMin, trip.getHoldBudgetMinutes(),
                                Math.round(utilisation * 100), trip.getSpoilageDeadlineAt());

        alerts.raise(type, severity, trip,
                AlertService.keyFor(type, trip.getId(), severity), message, null);
    }

    /** Ordinal order runs OK, WARNING, CRITICAL, LOST, so the later one is the louder one. */
    private static RiskLevel worseOf(RiskLevel a, RiskLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private SpoilageCalculator spoilageCalculator(SolverParameters.Snapshot params) {
        return new SpoilageCalculator(
                params.get("baseHoldMinutesAt30C"),
                params.get("q10Factor"),
                params.get("insulationOffsetC"),
                params.get("minHoldMinutes"),
                params.get("maxHoldMinutes"));
    }
}
