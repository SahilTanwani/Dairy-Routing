package com.dairy.milkroute.simulation;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One scenario, loaded from {@code classpath:scenarios/{name}.yaml}.
 *
 * <p>A scenario is a dataset plus a story about the weather and the network. Keeping it in a
 * file rather than in code means a new one is a new file, and means the numbers a demo turns
 * on are visible next to the run rather than buried in a class.
 *
 * @param name                what to call it
 * @param dataset             which dairy to reseed before running
 * @param session             MORNING or EVENING
 * @param businessDate        the date the trips belong to
 * @param ambientC            the temperature the plan is built against
 * @param startAt             where the simulated clock starts
 * @param stepSeconds         how much time each tick advances
 * @param maxSteps            a cap, so a stuck run stops rather than spinning
 * @param offlineFromMinutes  when the signal drops, in minutes after the start
 * @param offlineUntilMinutes when it comes back
 * @param ambientRiseAtMinutes when the weather turns, or null for a steady day
 * @param ambientRiseToC      what it turns to
 */
public record ScenarioConfig(
        String name,
        String dataset,
        String session,
        LocalDate businessDate,
        double ambientC,
        Instant startAt,
        int stepSeconds,
        int maxSteps,
        int offlineFromMinutes,
        int offlineUntilMinutes,
        Integer ambientRiseAtMinutes,
        Double ambientRiseToC) {

    public boolean hasWeatherChange() {
        return ambientRiseAtMinutes != null && ambientRiseToC != null;
    }
}
