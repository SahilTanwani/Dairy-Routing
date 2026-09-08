package com.dairy.milkroute.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A request to plan one session.
 *
 * <p>Only the session is required. The rest defaults to today, the session's usual departure
 * and the seeded temperature profile, which is the realistic call; supplying
 * {@code ambientTempC} explicitly is what lets the same dairy be planned at 22 C and at
 * 35 C to show what the heat costs.
 *
 * @param session      MORNING or EVENING
 * @param businessDate the date to plan for; defaults to today
 * @param departAt     when the fleet leaves; defaults to 05:00 morning, 16:30 evening
 * @param ambientTempC ambient to plan against; defaults to the temperature profile
 */
public record GeneratePlanRequest(
        @NotBlank String session,
        LocalDate businessDate,
        LocalTime departAt,
        Double ambientTempC) {
}
