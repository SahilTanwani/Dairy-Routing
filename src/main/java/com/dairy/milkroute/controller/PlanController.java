package com.dairy.milkroute.controller;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.dto.request.GeneratePlanRequest;
import com.dairy.milkroute.dto.response.ExclusionResponse;
import com.dairy.milkroute.dto.response.PlanResponse;
import com.dairy.milkroute.entity.RoutePlan;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.service.PlanPublicationService;
import com.dairy.milkroute.service.PlanQueryService;
import com.dairy.milkroute.service.PlanningService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plans: generate, read, publish.
 *
 * <p>Thin, as controllers here are. Everything it does is one call into a service; the
 * interesting decisions — which strategy, what happens on a second publish, who gets an
 * exclusion row — all live behind those calls.
 */
@RestController
@RequestMapping("/plans")
public class PlanController {

    /** When the fleet leaves if the caller does not say. */
    private static final LocalTime DEFAULT_MORNING_DEPARTURE = LocalTime.of(5, 0);
    private static final LocalTime DEFAULT_EVENING_DEPARTURE = LocalTime.of(16, 30);

    private final PlanningService planningService;
    private final PlanPublicationService publicationService;
    private final PlanQueryService queryService;
    private final ClockProvider clock;

    public PlanController(PlanningService planningService,
                          PlanPublicationService publicationService,
                          PlanQueryService queryService,
                          ClockProvider clock) {
        this.planningService = planningService;
        this.publicationService = publicationService;
        this.queryService = queryService;
        this.clock = clock;
    }

    /**
     * Generates a plan as a DRAFT.
     *
     * <p>Ambient temperature is optional. Left out, the plan is built against the seeded
     * temperature profile for the month, which is the realistic case; supplied, it lets the
     * same dairy be planned at 22 C and at 35 C to show what the heat actually costs.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanResponse generate(@Valid @RequestBody GeneratePlanRequest request) {
        Session session = Session.valueOf(request.session().toUpperCase());
        LocalDate date = request.businessDate() != null
                ? request.businessDate()
                : clock.now().atZone(ZoneOffset.UTC).toLocalDate();

        RoutePlan plan = planningService.generate(
                session,
                date,
                request.departAt() != null ? request.departAt() : defaultDepartureFor(session),
                request.ambientTempC());

        return queryService.byId(plan.getId());
    }

    @GetMapping("/{planId}")
    public PlanResponse byId(@PathVariable long planId) {
        return queryService.byId(planId);
    }

    /** The feasibility arithmetic, returned as the JSON it was stored as. */
    @GetMapping(value = "/{planId}/feasibility", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> feasibility(@PathVariable long planId) {
        return ResponseEntity.ok(queryService.feasibility(planId));
    }

    /**
     * Publishes a draft.
     *
     * <p>A second publish for the same session is refused with 409 by
     * {@code uq_one_published_per_session}, not by a check in application code. Pass
     * {@code replace=true} to archive the live plan first, which is a decision worth making
     * explicitly when tankers may already be running against it.
     */
    @PostMapping("/{planId}/publish")
    public PlanResponse publish(@PathVariable long planId,
                                @RequestParam(defaultValue = "false") boolean replace) {
        return queryService.byId(publicationService.publish(planId, replace).getId());
    }

    /** The plan the fleet is running for this session. */
    @GetMapping("/published")
    public PlanResponse published(@RequestParam String session) {
        return queryService.published(Session.valueOf(session.toUpperCase()));
    }

    /** Who this plan left out, and what it cost. */
    @GetMapping("/{planId}/exclusions")
    public ExclusionResponse exclusions(@PathVariable long planId) {
        return queryService.exclusions(planId);
    }

    private static LocalTime defaultDepartureFor(Session session) {
        return session == Session.MORNING ? DEFAULT_MORNING_DEPARTURE : DEFAULT_EVENING_DEPARTURE;
    }
}
