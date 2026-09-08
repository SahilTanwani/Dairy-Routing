package com.dairy.milkroute.controller;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.dto.response.TripResponse;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.service.DriverTripService;
import com.dairy.milkroute.service.TripCreationService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Dispatch: turning the published plan into trips. */
@RestController
@RequestMapping(ApiPaths.V1 + "/trips")
public class TripController {

    private final TripCreationService tripCreationService;
    private final DriverTripService driverTripService;
    private final ClockProvider clock;

    public TripController(TripCreationService tripCreationService,
                          DriverTripService driverTripService,
                          ClockProvider clock) {
        this.tripCreationService = tripCreationService;
        this.driverTripService = driverTripService;
        this.clock = clock;
    }

    /**
     * Creates the day's trips from the published plan.
     *
     * <p>Safe to run twice: {@code UNIQUE (route_id, business_date, session)} makes the
     * second run a no-op rather than a duplicate fleet.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<TripResponse> create(
            @RequestParam(defaultValue = "MORNING") String session,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        LocalDate date = businessDate != null
                ? businessDate
                : clock.now().atZone(ZoneOffset.UTC).toLocalDate();

        return tripCreationService.createForSession(Session.valueOf(session.toUpperCase()), date)
                .stream()
                .map(Trip::getId)
                .map(driverTripService::byId)
                .toList();
    }
}
