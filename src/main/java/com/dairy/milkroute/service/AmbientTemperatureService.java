package com.dairy.milkroute.service;

import com.dairy.milkroute.config.SolverParameters;
import com.dairy.milkroute.entity.TemperatureProfile;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.repository.TemperatureProfileRepository;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the air temperature will be for a given session, and what it would be if the fleet
 * left at a different hour.
 *
 * <p>Reads {@code temperature_profile}, which stands in for a weather API: twenty-four rows,
 * one per month per session, no external dependency to fail on a clean machine.
 *
 * <h2>Why there is a shift model at all</h2>
 *
 * <p>The table is month by session, so it can say what an August evening is like but not
 * what 18:30 is like as against 16:30. The timing advisory turns entirely on that
 * difference — the whole finding is that leaving two hours later collects far more milk —
 * so the difference has to be expressible.
 *
 * <p>The model is deliberately one line: ambient moves by {@code ambientShiftCPerHour} for
 * every hour away from the session's usual departure. Evenings cool as the sun goes down;
 * mornings warm as it comes up. That is a crude curve, and it is stated as an assumption
 * rather than dressed up: it is right about the direction and the rough magnitude, which is
 * what an advisory needs to be useful, and the coefficient sits in {@code solver_parameter}
 * so a dairy with real data can replace the estimate without a redeploy.
 */
@Service
public class AmbientTemperatureService {

    private final TemperatureProfileRepository profiles;
    private final SolverParameters parameters;

    public AmbientTemperatureService(TemperatureProfileRepository profiles,
                                     SolverParameters parameters) {
        this.profiles = profiles;
        this.parameters = parameters;
    }

    /** Ambient for this session at its usual departure time. */
    @Transactional(readOnly = true)
    public double ambientC(LocalDate date, Session session) {
        return profiles
                .findByMonthNoAndSession((short) date.getMonthValue(), session)
                .map(TemperatureProfile::getAmbientC)
                .orElseThrow(() -> new IllegalStateException(
                        "no temperature profile for month %d, %s"
                                .formatted(date.getMonthValue(), session)))
                .doubleValue();
    }

    /**
     * Ambient if the fleet left {@code hoursLater} than usual.
     *
     * <p>Negative hours mean leaving earlier, which is the useful direction for a morning
     * session and the useless one for an evening.
     */
    @Transactional(readOnly = true)
    public double ambientCShiftedBy(LocalDate date, Session session, double hoursLater) {
        return shift(ambientC(date, session), session, hoursLater);
    }

    /**
     * The shift applied to a known base temperature.
     *
     * <p>Sign by session: an evening that starts later is cooler, a morning that starts
     * later is warmer. Getting that backwards would have the advisory recommending the one
     * change guaranteed to lose more milk, so it is worth stating out loud rather than
     * leaving in a minus sign.
     */
    public double shift(double baseAmbientC, Session session, double hoursLater) {
        double perHour = parameters.snapshot().get("ambientShiftCPerHour");
        return session == Session.EVENING
                ? baseAmbientC - perHour * hoursLater
                : baseAmbientC + perHour * hoursLater;
    }
}
