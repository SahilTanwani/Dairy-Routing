package com.dairy.milkroute.controller;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.dto.response.TimingAdvisoryResponse;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.service.TimingAdvisoryService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Advisories: things the dairy could do differently.
 *
 * <p>One endpoint so far, and it is the one worth having. Every other lever costs money;
 * this one costs a conversation about when farmers milk.
 */
@RestController
@RequestMapping("/advisory")
public class AdvisoryController {

    private static final LocalTime DEFAULT_MORNING_DEPARTURE = LocalTime.of(5, 0);
    private static final LocalTime DEFAULT_EVENING_DEPARTURE = LocalTime.of(16, 30);

    private final TimingAdvisoryService advisoryService;
    private final ClockProvider clock;

    public AdvisoryController(TimingAdvisoryService advisoryService, ClockProvider clock) {
        this.advisoryService = advisoryService;
        this.clock = clock;
    }

    /**
     * What leaving later would be worth.
     *
     * <p>Plans the same dairy twice, at the current departure and at a shifted one, and
     * reports both sides so the answer can be checked rather than taken on trust.
     */
    @GetMapping("/session-timing")
    public TimingAdvisoryResponse sessionTiming(
            @RequestParam String session,
            @RequestParam(required = false) LocalDate businessDate,
            @RequestParam(required = false) LocalTime departAt,
            @RequestParam(required = false) Double shiftHours,
            @RequestParam(required = false) Double ambientTempC) {

        Session parsed = Session.valueOf(session.toUpperCase());
        LocalDate date = businessDate != null
                ? businessDate
                : clock.now().atZone(ZoneOffset.UTC).toLocalDate();

        return TimingAdvisoryResponse.from(advisoryService.advise(
                parsed,
                date,
                departAt != null ? departAt : defaultDepartureFor(parsed),
                shiftHours != null ? shiftHours : TimingAdvisoryService.DEFAULT_SHIFT_HOURS,
                ambientTempC));
    }

    private static LocalTime defaultDepartureFor(Session session) {
        return session == Session.MORNING ? DEFAULT_MORNING_DEPARTURE : DEFAULT_EVENING_DEPARTURE;
    }
}
