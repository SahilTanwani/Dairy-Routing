package com.dairy.milkroute.controller;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.dto.request.DriverEventBatchRequest;
import com.dairy.milkroute.dto.request.TankerPingRequest;
import com.dairy.milkroute.dto.response.IngestResult;
import com.dairy.milkroute.dto.response.TripResponse;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.service.DriverTripService;
import com.dairy.milkroute.service.EventIngestionService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The driver's phone.
 *
 * <p>Three things: what am I doing today, here is what I did, here is where I am. The middle
 * one is the interesting one — it is a batch, because the phone spends part of every session
 * out of signal and flushes everything at once when it comes back.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/drivers")
public class DriverController {

    private final DriverTripService driverTripService;
    private final EventIngestionService ingestionService;
    private final ClockProvider clock;

    public DriverController(DriverTripService driverTripService,
                            EventIngestionService ingestionService,
                            ClockProvider clock) {
        this.driverTripService = driverTripService;
        this.ingestionService = ingestionService;
        this.clock = clock;
    }

    /** What this driver is running. Defaults to today and the morning session. */
    @GetMapping("/{driverCode}/trip")
    public TripResponse todaysTrip(
            @PathVariable String driverCode,
            @RequestParam(defaultValue = "MORNING") String session,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {

        return driverTripService.todaysTrip(
                driverCode,
                businessDate != null ? businessDate : today(),
                Session.valueOf(session.toUpperCase()));
    }

    @GetMapping("/trips/{tripId}")
    public TripResponse trip(@PathVariable long tripId) {
        return driverTripService.byId(tripId);
    }

    /**
     * A batch of events.
     *
     * <p>Always 200, even when every event in it was already known. A phone that resends
     * after a reconnect is behaving correctly and must not be told it did something wrong;
     * the counts in the response say what actually landed.
     */
    @PostMapping("/trips/{tripId}/events")
    public IngestResult events(@PathVariable long tripId,
                               @Valid @RequestBody DriverEventBatchRequest batch) {
        return ingestionService.ingest(tripId, batch.events());
    }

    /** A batch of GPS readings. */
    @PostMapping("/trips/{tripId}/pings")
    public Map<String, Integer> pings(@PathVariable long tripId,
                                      @Valid @RequestBody TankerPingRequest.Batch batch) {
        return Map.of("recorded", driverTripService.recordPings(tripId, batch.pings()));
    }

    /**
     * The server's clock.
     *
     * <p>So a phone can tell how far its own has drifted before it starts stamping events
     * with it. A device an hour out has its batch refused; this is how it finds out first.
     */
    @GetMapping("/clock")
    public Map<String, String> serverClock() {
        return Map.of("now", clock.now().toString());
    }

    private LocalDate today() {
        return clock.now().atZone(ZoneOffset.UTC).toLocalDate();
    }
}
