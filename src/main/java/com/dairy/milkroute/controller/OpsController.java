package com.dairy.milkroute.controller;

import com.dairy.milkroute.dto.response.MitigationResponse;
import com.dairy.milkroute.dto.response.OpsBoardResponse;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.service.MitigationService;
import com.dairy.milkroute.service.OpsBoardService;
import com.dairy.milkroute.service.SpoilageMonitorService;
import com.dairy.milkroute.repository.TripRepository;
import com.dairy.milkroute.error.TripNotFoundException;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The dispatcher's screen, and the decisions available from it. */
@RestController
@RequestMapping(ApiPaths.V1 + "/ops")
public class OpsController {

    private final OpsBoardService board;
    private final MitigationService mitigations;
    private final SpoilageMonitorService monitor;
    private final TripRepository tripRepo;

    public OpsController(OpsBoardService board,
                         MitigationService mitigations,
                         SpoilageMonitorService monitor,
                         TripRepository tripRepo) {
        this.board = board;
        this.mitigations = mitigations;
        this.monitor = monitor;
        this.tripRepo = tripRepo;
    }

    /** Every live trip and every open alert, one consistent snapshot. */
    @GetMapping("/board")
    public OpsBoardResponse board() {
        return board.board();
    }

    /**
     * What could be done about a trip, ranked.
     *
     * <p>Generated, never executed. The response carries the trip's version, which has to
     * come back with the choice.
     */
    @GetMapping("/trips/{tripId}/mitigations")
    public MitigationResponse mitigations(@PathVariable long tripId) {
        Trip trip = tripRepo.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException("no trip with id " + tripId));
        return MitigationResponse.of(tripId, trip.getVersion(), mitigations.generate(tripId));
    }

    /**
     * Applies a chosen option.
     *
     * <p>{@code version} is required, not optional. Without it two dispatchers acting in the
     * same minute would each half-apply a different plan; with it, the second one gets a 409
     * telling them the trip has moved.
     */
    @PostMapping("/trips/{tripId}/mitigations/{action}")
    public Map<String, Object> execute(@PathVariable long tripId,
                                       @PathVariable String action,
                                       @RequestParam int version) {
        Trip trip = mitigations.execute(tripId, action, version);
        return Map.of(
                "tripId", trip.getId(),
                "status", trip.getStatus().name(),
                "version", trip.getVersion(),
                "applied", action.toUpperCase());
    }

    /**
     * Runs the spoilage sweep now instead of waiting for the next minute.
     *
     * <p>For the demo and for tests. The scheduled sweep is the real path.
     */
    @PostMapping("/monitor/sweep")
    public Map<String, String> sweep() {
        monitor.sweep();
        return Map.of("status", "swept");
    }
}
