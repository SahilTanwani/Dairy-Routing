package com.dairy.milkroute.service;

import com.dairy.milkroute.config.SolverParameters;
import com.dairy.milkroute.domain.advisory.TimingAdvisory;
import com.dairy.milkroute.domain.routing.PlanResult;
import com.dairy.milkroute.domain.spoilage.SpoilageCalculator;
import com.dairy.milkroute.enums.Session;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the timing advisory by planning the same dairy twice.
 *
 * <p>Twice, rather than estimating the second from the first. Coverage does not scale
 * linearly with hold budget — an extra hour lets some routes absorb a whole extra village
 * and others none at all — so the only honest way to say what leaving later would achieve is
 * to work it out properly. Both runs are in memory and neither is persisted.
 */
@Service
public class TimingAdvisoryService {

    /** Default shift. Two hours is a real operational change a dairy can actually make. */
    public static final double DEFAULT_SHIFT_HOURS = 2.0;

    private final PlanningService planningService;
    private final AmbientTemperatureService ambientTemperature;
    private final SolverParameters parameters;

    public TimingAdvisoryService(PlanningService planningService,
                                 AmbientTemperatureService ambientTemperature,
                                 SolverParameters parameters) {
        this.planningService = planningService;
        this.ambientTemperature = ambientTemperature;
        this.parameters = parameters;
    }

    @Transactional(readOnly = true)
    public TimingAdvisory advise(Session session,
                                 LocalDate businessDate,
                                 LocalTime currentDepartAt,
                                 double shiftHours,
                                 Double ambientOverrideC) {
        // The override exists because the finding this advisory exists to make is about a
        // 35 C evening, and the temperature profile only knows about the month it is given.
        double currentAmbient = ambientOverrideC != null
                ? ambientOverrideC
                : ambientTemperature.ambientC(businessDate, session);
        double shiftedAmbient = ambientTemperature.shift(currentAmbient, session, shiftHours);
        LocalTime shiftedDepartAt = currentDepartAt.plusMinutes(Math.round(shiftHours * 60));

        PlanResult current = planningService.planWithoutPersisting(
                session, businessDate, currentDepartAt, currentAmbient);
        PlanResult shifted = planningService.planWithoutPersisting(
                session, businessDate, shiftedDepartAt, shiftedAmbient);

        SpoilageCalculator spoilage = spoilageCalculator();
        double recoveredPerSession =
                Math.max(0, shifted.litresCollected() - current.litresCollected());

        return new TimingAdvisory(
                currentDepartAt,
                currentAmbient,
                // The plain tanker's budget, because that is what most of the fleet is and
                // quoting the insulated figure would overstate the starting position.
                spoilage.holdBudgetMinutes(currentAmbient, false),
                current.coveragePct(),
                shiftedDepartAt,
                shiftedAmbient,
                spoilage.holdBudgetMinutes(shiftedAmbient, false),
                shifted.coveragePct(),
                recoveredPerSession,
                recoveredPerSession * TimingAdvisory.SESSIONS_PER_YEAR,
                costOf(session, shiftHours));
    }

    /**
     * What the dairy gives up. For an evening shift the answer is nothing it pays for, which
     * is the entire reason this advisory is worth having.
     */
    private String costOf(Session session, double shiftHours) {
        if (session == Session.EVENING) {
            return "None. Farmers milk %.0f hours later.".formatted(shiftHours);
        }
        return ("None in money, but a morning shift moves collection into the warming part "
                + "of the day. Check the coverage figures before acting on it.");
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
