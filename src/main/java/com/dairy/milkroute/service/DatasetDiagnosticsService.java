package com.dairy.milkroute.service;

import com.dairy.milkroute.config.SolverParameters;
import com.dairy.milkroute.domain.geo.GeoPoint;
import com.dairy.milkroute.dto.DatasetConfig;
import com.dairy.milkroute.dto.response.DatasetDiagnostics;
import com.dairy.milkroute.entity.CollectionPoint;
import com.dairy.milkroute.entity.Plant;
import com.dairy.milkroute.entity.Tanker;
import com.dairy.milkroute.entity.Village;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.enums.TankerStatus;
import com.dairy.milkroute.repository.CollectionPointRepository;
import com.dairy.milkroute.repository.FarmerRepository;
import com.dairy.milkroute.repository.PlantRepository;
import com.dairy.milkroute.repository.TankerRepository;
import com.dairy.milkroute.repository.VillageRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers the question a dataset config cannot: does this dairy actually bind where it was
 * meant to?
 *
 * <p>The number that matters is the time ratio, required over available hot minutes. Under
 * about 0.8 the dairy is comfortable and every point gets served; over 1 no fleet of this
 * size can serve everyone and planning has to choose. A config written to be comfortable
 * that quietly lands at 1.4 is the kind of thing best discovered here rather than in front
 * of an audience.
 *
 * <h2>Provisional arithmetic</h2>
 *
 * <p>The hold budget and the road travel times below are computed here, from parameters
 * read out of {@code solver_parameter}. That is temporary. {@code SpoilageCalculator}
 * (T3.5) and {@code HaversineTravelTime} (T3.2) are the classes that own these two
 * formulas, and this service should delegate to them once they exist; the numbers already
 * come from the same table, so the values will not move when it does. Until then, treat
 * this endpoint as a dataset-tuning aid rather than as the planner's view of the world.
 */
@Service
public class DatasetDiagnosticsService {

    /** Estimated points a tanker serves per village visit is not assumed; see below. */
    private static final double MINUTES_PER_HOUR = 60.0;

    private final VillageRepository villageRepo;
    private final CollectionPointRepository pointRepo;
    private final FarmerRepository farmerRepo;
    private final TankerRepository tankerRepo;
    private final PlantRepository plantRepo;
    private final SolverParameters parameters;

    public DatasetDiagnosticsService(VillageRepository villageRepo,
                                     CollectionPointRepository pointRepo,
                                     FarmerRepository farmerRepo,
                                     TankerRepository tankerRepo,
                                     PlantRepository plantRepo,
                                     SolverParameters parameters) {
        this.villageRepo = villageRepo;
        this.pointRepo = pointRepo;
        this.farmerRepo = farmerRepo;
        this.tankerRepo = tankerRepo;
        this.plantRepo = plantRepo;
        this.parameters = parameters;
    }

    @Transactional(readOnly = true)
    public DatasetDiagnostics check(DatasetConfig cfg) {
        SolverParameters.Snapshot params = parameters.snapshot();
        Session session = Session.valueOf(cfg.defaultSession());
        double ambientC = cfg.defaultAmbientC();

        Plant plant = plantRepo.findFirstByPrimaryTrueAndActiveTrue()
                .orElseThrow(() -> new IllegalStateException("no primary plant is seeded"));
        GeoPoint plantAt = at(plant.getLat().doubleValue(), plant.getLng().doubleValue());

        List<Village> villages = villageRepo.findByActiveTrueOrderByCodeAsc();
        List<Tanker> tankers = tankerRepo.findByStatusOrderByCapacityLitresDesc(TankerStatus.AVAILABLE);

        double required = 0;
        double expectedLitres = 0;
        double farthestKm = 0;
        List<String> unreachable = new ArrayList<>();

        for (Village village : villages) {
            GeoPoint villageAt = at(village.getLat().doubleValue(), village.getLng().doubleValue());
            List<CollectionPoint> points =
                    pointRepo.findByVillageIdAndActiveTrueOrderByCodeAsc(village.getId());
            if (points.isEmpty()) {
                continue;
            }

            double straightKm = plantAt.haversineKm(villageAt);
            farthestKm = Math.max(farthestKm, straightKm);

            double serviceMinutes = 0;
            for (CollectionPoint point : points) {
                serviceMinutes += point.getServiceMinutes().doubleValue();
                expectedLitres += litresFor(point, session);
            }

            // Hot time for this village: serving it, then carrying its milk home. The
            // outbound leg is excluded because the tanker is empty on it, which is the
            // rule the whole spoilage model turns on.
            double returnMinutes = travelMinutes(straightKm, session, params);
            required += serviceMinutes + returnMinutes;

            // Unreachable means physically impossible, not merely expensive: even a
            // dedicated insulated tanker cannot serve this village and get back in time.
            double bestBudget = holdBudgetMinutes(ambientC, true, params);
            if (serviceMinutes + returnMinutes > bestBudget - params.get("safetyBufferMin")) {
                unreachable.add(village.getCode());
            }
        }

        double available = 0;
        long fleetCapacity = 0;
        for (Tanker tanker : tankers) {
            available += holdBudgetMinutes(ambientC, tanker.isInsulated(), params)
                    - params.get("safetyBufferMin");
            fleetCapacity += tanker.getCapacityLitres();
        }

        return new DatasetDiagnostics(
                cfg.name(),
                session.name(),
                ambientC,
                villages.size(),
                pointRepo.count(),
                pointRepo.countByActiveTrue(),
                farmerRepo.countByActiveTrue(),
                tankers.size(),
                round(required),
                round(available),
                available == 0 ? Double.NaN : round(required / available),
                round(expectedLitres),
                fleetCapacity,
                fleetCapacity == 0 ? Double.NaN : round(expectedLitres / fleetCapacity),
                unreachable.size(),
                unreachable,
                round(farthestKm));
    }

    /**
     * Hold budget from the Q10 rule: bacterial growth roughly doubles per ten degrees, so
     * the time milk survives halves. Insulation is modelled as the day being several
     * degrees cooler, and the result is clamped at both ends because a sensor reading of
     * 55 C should not produce a five-minute budget.
     *
     * <p>Provisional; {@code SpoilageCalculator} (T3.5) owns this formula.
     */
    private double holdBudgetMinutes(double ambientC, boolean insulated,
                                     SolverParameters.Snapshot params) {
        double effectiveC = ambientC - (insulated ? params.get("insulationOffsetC") : 0);
        double base = params.get("baseHoldMinutesAt30C");
        double q10 = params.get("q10Factor");

        double minutes = base * Math.pow(q10, (30.0 - effectiveC) / 10.0);
        return Math.clamp(minutes, params.get("minHoldMinutes"), params.get("maxHoldMinutes"));
    }

    /**
     * Road travel time for a straight-line distance: apply the circuity factor, pick a
     * speed band, then adjust for the session, because empty pre-dawn roads and evening
     * traffic are not the same road.
     *
     * <p>Provisional; {@code HaversineTravelTime} (T3.2) owns this formula.
     */
    private double travelMinutes(double straightKm, Session session,
                                 SolverParameters.Snapshot params) {
        double roadKm = straightKm * params.get("circuityFactor");

        double speed;
        if (roadKm < 2) {
            speed = params.get("speedUnder2Km");
        } else if (roadKm < 10) {
            speed = params.get("speed2To10Km");
        } else {
            speed = params.get("speedOver10Km");
        }

        double factor = session == Session.MORNING
                ? params.get("morningSpeedFactor")
                : params.get("eveningSpeedFactor");

        return roadKm / (speed * factor) * MINUTES_PER_HOUR;
    }

    private double litresFor(CollectionPoint point, Session session) {
        return session == Session.MORNING
                ? point.getAvgMorningLitres().doubleValue()
                : point.getAvgEveningLitres().doubleValue();
    }

    private GeoPoint at(double lat, double lng) {
        return new GeoPoint(lat, lng);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
