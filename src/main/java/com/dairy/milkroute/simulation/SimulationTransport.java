package com.dairy.milkroute.simulation;

import com.dairy.milkroute.dto.request.DriverEventBatchRequest;
import com.dairy.milkroute.dto.request.DriverEventRequest;
import com.dairy.milkroute.dto.request.TankerPingRequest;
import com.dairy.milkroute.dto.response.IngestResult;
import com.dairy.milkroute.dto.response.TripResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The simulation's only way of talking to the system: HTTP, over the loopback interface, to
 * the same endpoints a driver's phone calls.
 *
 * <p>It would be faster and much easier to call the services directly. It would also prove
 * far less. Going over the wire means the simulation exercises request validation, JSON
 * binding, the clock-drift guard, transaction boundaries and the unique index — every layer
 * between a phone and a row. A simulation that bypassed those would pass happily while the
 * real API was broken.
 *
 * <p>Failures are logged and swallowed rather than thrown. A driver whose request fails is a
 * driver with a bad connection, which is a thing that happens; it must not stop the other
 * twenty-one from finishing their routes.
 */
@Component
@Profile("sim")
public class SimulationTransport {

    private static final Logger log = LoggerFactory.getLogger(SimulationTransport.class);

    /** A plausible position. The simulation does not model roads, only progress. */
    private static final BigDecimal PING_LAT = BigDecimal.valueOf(16.75);
    private static final BigDecimal PING_LNG = BigDecimal.valueOf(74.28);
    private static final short PING_ACCURACY_M = 8;

    private final RestClient http;

    /**
     * Built directly rather than from an injected builder: this talks to the application it
     * is running inside, so it wants none of the interceptors, tracing or load balancing an
     * auto-configured client would carry. A plain client to loopback is exactly the intent.
     */
    public SimulationTransport(
            @Value("${milkroute.sim.base-url:http://localhost:8080/api/v1}") String baseUrl) {
        this.http = RestClient.create(baseUrl);
    }

    /** What the server made of a batch. Null when the call itself failed. */
    public record IngestCounts(int applied, int duplicates) {
    }

    public IngestCounts sendEvents(long tripId, List<DriverEventRequest> events) {
        if (events.isEmpty()) {
            return new IngestCounts(0, 0);
        }
        try {
            IngestResult result = http.post()
                    .uri("/drivers/trips/{tripId}/events", tripId)
                    .body(new DriverEventBatchRequest(events))
                    .retrieve()
                    .body(IngestResult.class);

            return result == null
                    ? null
                    : new IngestCounts(result.applied(), result.duplicates());

        } catch (RestClientException e) {
            log.warn("Event batch for trip {} failed: {}", tripId, e.getMessage());
            return null;
        }
    }

    public void sendPing(long tripId, Instant at) {
        try {
            http.post()
                    .uri("/drivers/trips/{tripId}/pings", tripId)
                    .body(new TankerPingRequest.Batch(List.of(
                            new TankerPingRequest(PING_LAT, PING_LNG, PING_ACCURACY_M, at))))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.debug("Ping for trip {} failed: {}", tripId, e.getMessage());
        }
    }

    public TripResponse trip(long tripId) {
        return http.get()
                .uri("/drivers/trips/{tripId}", tripId)
                .retrieve()
                .body(TripResponse.class);
    }

    public List<TripResponse> createTrips(String session, String businessDate) {
        return http.post()
                .uri(uri -> uri.path("/trips")
                        .queryParam("session", session)
                        .queryParam("businessDate", businessDate)
                        .build())
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<TripResponse>>() { });
    }

    public void reseed(String dataset) {
        http.post()
                .uri(uri -> uri.path("/admin/reseed").queryParam("dataset", dataset).build())
                .retrieve()
                .toBodilessEntity();
    }

    /** Generates a plan and returns its id. */
    public Long generatePlan(String session, String businessDate, double ambientC) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("session", session);
        body.put("businessDate", businessDate);
        body.put("ambientTempC", ambientC);

        var response = http.post()
                .uri("/plans")
                .body(body)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<
                        java.util.Map<String, Object>>() { });

        return response == null ? null : ((Number) response.get("id")).longValue();
    }

    public void publishPlan(long planId) {
        http.post()
                .uri("/plans/{planId}/publish", planId)
                .retrieve()
                .toBodilessEntity();
    }
}
