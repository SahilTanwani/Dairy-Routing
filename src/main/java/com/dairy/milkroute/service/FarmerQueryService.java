package com.dairy.milkroute.service;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.config.SolverParameters;
import com.dairy.milkroute.domain.tracking.EtaEstimate;
import com.dairy.milkroute.dto.response.CollectionRecordResponse;
import com.dairy.milkroute.dto.response.FarmerStatusResponse;
import com.dairy.milkroute.entity.Farmer;
import com.dairy.milkroute.entity.MilkCollection;
import com.dairy.milkroute.entity.PointCoverageState;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.entity.TripStop;
import com.dairy.milkroute.enums.EtaConfidence;
import com.dairy.milkroute.enums.FarmerStatus;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.enums.TripStatus;
import com.dairy.milkroute.enums.TripStopStatus;
import com.dairy.milkroute.error.FarmerNotFoundException;
import com.dairy.milkroute.repository.FarmerRepository;
import com.dairy.milkroute.repository.MilkCollectionRepository;
import com.dairy.milkroute.repository.PlanExclusionRepository;
import com.dairy.milkroute.repository.PointCoverageStateRepository;
import com.dairy.milkroute.repository.TripRepository;
import com.dairy.milkroute.repository.TripStopRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "where is my tanker?" in a sentence a person can read out.
 *
 * <p>This is the endpoint the whole system is judged by. Everything upstream — the spoilage
 * model, the savings loop, the equity term — exists so that this can say something true.
 *
 * <h2>Never quote a time you cannot stand behind</h2>
 *
 * <p>Quote a farmer 6:41 and turn up at 7:15 and he will not believe another number you give
 * him, ever. So the phrasing is governed by confidence, not by whether a number happens to be
 * available: HIGH gets a time, MEDIUM gets a twenty-minute window, LOW gets a last-known
 * position and no time at all, LOST admits contact is gone and gives him the office number.
 *
 * <p>The same rule handles the aborted trip. There is always an ETA in the database; there is
 * not always a tanker coming. A stale ETA for an abandoned trip is the worst possible answer
 * and it is the easiest one to give by accident.
 *
 * <h2>Say why, not just no</h2>
 *
 * <p>A farmer who is not being served today is told the reason and the date the three-strike
 * rule guarantees him by. "No tanker" is not an answer anybody can plan a morning around.
 *
 * <p>Eight of the nine documented cases are reachable. The ninth, a point merged into a hub,
 * cannot happen in this build: {@code collection_point.merged_into_id} was removed along with
 * the consolidation advisory, so there is no way for a point to be merged and therefore no
 * honest branch to write. Adding one would be scaffolding for a feature that does not exist.
 */
@Service
public class FarmerQueryService {

    /** How wide a window to quote when the estimate is only worth a window. */
    private static final Duration MEDIUM_WINDOW = Duration.ofMinutes(10);

    /** Two collections a day, which is what a skip counter counts in. */
    private static final int SESSIONS_PER_DAY = 2;

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneOffset.UTC);

    private final FarmerRepository farmerRepo;
    private final TripRepository tripRepo;
    private final TripStopRepository tripStopRepo;
    private final MilkCollectionRepository collectionRepo;
    private final PlanExclusionRepository exclusionRepo;
    private final PointCoverageStateRepository coverageRepo;
    private final TripTrackingService tracking;
    private final SolverParameters parameters;
    private final ClockProvider clock;
    private final String officeContact;

    public FarmerQueryService(FarmerRepository farmerRepo,
                              TripRepository tripRepo,
                              TripStopRepository tripStopRepo,
                              MilkCollectionRepository collectionRepo,
                              PlanExclusionRepository exclusionRepo,
                              PointCoverageStateRepository coverageRepo,
                              TripTrackingService tracking,
                              SolverParameters parameters,
                              ClockProvider clock,
                              @Value("${milkroute.office-contact:the dairy office}")
                              String officeContact) {
        this.farmerRepo = farmerRepo;
        this.tripRepo = tripRepo;
        this.tripStopRepo = tripStopRepo;
        this.collectionRepo = collectionRepo;
        this.exclusionRepo = exclusionRepo;
        this.coverageRepo = coverageRepo;
        this.tracking = tracking;
        this.parameters = parameters;
        this.clock = clock;
        this.officeContact = officeContact;
    }

    @Transactional(readOnly = true)
    public FarmerStatusResponse tankerStatus(String farmerCode, Session session) {
        Farmer farmer = farmerRepo.findByCode(farmerCode)
                .orElseThrow(FarmerNotFoundException::new);

        LocalDate today = clock.now().atZone(ZoneOffset.UTC).toLocalDate();
        var point = farmer.getCollectionPoint();

        List<Trip> todaysTrips = tripRepo.findByBusinessDateAndSession(today, session);
        if (todaysTrips.isEmpty()) {
            return notScheduled(farmer, session);
        }

        Optional<TripStop> mine = tripStopRepo
                .findByCollectionPointIdAndTripIdIn(point.getId(),
                        todaysTrips.stream().map(Trip::getId).toList())
                .stream()
                .findFirst();

        if (mine.isEmpty()) {
            return notServedToday(farmer, today, session);
        }

        TripStop stop = mine.get();
        Trip trip = stop.getTrip();

        if (trip.getStatus() == TripStatus.ABORTED) {
            return aborted(farmer, stop);
        }

        return switch (stop.getStatus()) {
            case COLLECTED -> collected(farmer, stop);
            case SKIPPED -> skipped(farmer, stop);
            case DEFERRED -> deferred(farmer, stop, session);
            default -> onTheWay(farmer, trip, stop);
        };
    }

    /**
     * His own milk, newest first. Two farmers at one point each see only their own.
     *
     * <p>Mapped here, inside the transaction. Handing entities to a controller and mapping
     * them there reaches through a lazy association after the session has closed, which with
     * open-in-view disabled is a 500 rather than a slow query.
     */
    @Transactional(readOnly = true)
    public List<CollectionRecordResponse> collections(String farmerCode) {
        Farmer farmer = farmerRepo.findByCode(farmerCode)
                .orElseThrow(FarmerNotFoundException::new);

        return collectionRepo.findByFarmerIdOrderByCollectedAtDesc(farmer.getId()).stream()
                .map(row -> new CollectionRecordResponse(
                        row.getCollectedAt(),
                        row.getLitres(),
                        row.getTripStop().getCollectionPoint().getCode(),
                        row.isVoided(),
                        row.getVoidReason()))
                .toList();
    }

    // ------------------------------------------------------------------ the answers

    private FarmerStatusResponse onTheWay(Farmer farmer, Trip trip, TripStop stop) {
        if (trip.getStatus() == TripStatus.SCHEDULED) {
            return response(farmer, FarmerStatus.SCHEDULED,
                    "The tanker has not left the plant yet. Your collection point is stop %d today."
                            .formatted(stop.getSeq()),
                    stop, null, null, null, null, null, null, null);
        }

        EtaEstimate eta = tracking.etaToStop(trip, stop.getSeq());
        int stopsAway = Math.max(0, stop.getSeq() - trip.getCurrentSeq());
        boolean next = stopsAway <= 1;

        FarmerStatus status = next ? FarmerStatus.ARRIVING_NEXT : FarmerStatus.EN_ROUTE;
        String message = phraseFor(eta, stopsAway, next);

        // The ETA is only handed out when the confidence justifies quoting one. LOW and LOST
        // get the sentence and no number, so nothing downstream can render a precise time
        // from a position that is a quarter of an hour old.
        Instant quotable = eta.confidence() == EtaConfidence.HIGH
                || eta.confidence() == EtaConfidence.MEDIUM ? eta.at() : null;

        return response(farmer, status, message, stop,
                quotable, eta.confidence().name(), stopsAway, null, null, null, null);
    }

    /**
     * The sentence, chosen by confidence.
     *
     * <p>Each branch says as much as is honestly known and stops there.
     */
    private String phraseFor(EtaEstimate eta, int stopsAway, boolean next) {
        return switch (eta.confidence()) {
            case HIGH -> next
                    ? "The tanker is on its way to you now, expected around %s."
                            .formatted(CLOCK.format(eta.at()))
                    : "The tanker is %d stops away, expected around %s."
                            .formatted(stopsAway, CLOCK.format(eta.at()));

            case MEDIUM -> "The tanker is %d stops away, expected between %s and %s."
                    .formatted(stopsAway,
                            CLOCK.format(eta.at().minus(MEDIUM_WINDOW)),
                            CLOCK.format(eta.at().plus(MEDIUM_WINDOW)));

            // No time at all: the position is minutes old and a number would be a guess
            // dressed up as a promise.
            case LOW -> ("The tanker is on its way and was last seen a few minutes ago. "
                    + "We do not have a reliable time for you yet — please check again shortly.");

            case LOST, UNKNOWN -> ("We have lost contact with this tanker and cannot give you a "
                    + "time. Please call %s.").formatted(officeContact);
        };
    }

    private FarmerStatusResponse collected(Farmer farmer, TripStop stop) {
        Optional<MilkCollection> mine =
                collectionRepo.findByTripStopIdAndFarmerId(stop.getId(), farmer.getId());

        BigDecimal litres = mine.map(MilkCollection::getLitres).orElse(null);
        Instant at = mine.map(MilkCollection::getCollectedAt).orElse(stop.getArrivedAt());

        String message = litres == null
                ? "The tanker collected here at %s.".formatted(CLOCK.format(at))
                : "Collected at %s, %s litres.".formatted(CLOCK.format(at), litres.stripTrailingZeros().toPlainString());

        return response(farmer, FarmerStatus.COLLECTED, message, stop,
                null, null, null, at, litres, null, null);
    }

    private FarmerStatusResponse skipped(Farmer farmer, TripStop stop) {
        String reason = stop.getSkipReason() == null ? null : stop.getSkipReason().name();
        return response(farmer, FarmerStatus.SKIPPED,
                ("The tanker came but did not collect here today (%s). Please call %s if that "
                        + "is wrong.").formatted(readable(reason), officeContact),
                stop, null, null, null, null, null, reason, null);
    }

    private FarmerStatusResponse deferred(Farmer farmer, TripStop stop, Session session) {
        String reason = stop.getSkipReason() == null ? null : stop.getSkipReason().name();
        return responseWithGuarantee(farmer, FarmerStatus.DEFERRED,
                ("Your collection was postponed today (%s). It is scheduled for the next run; "
                        + "call %s if you need it sooner.").formatted(readable(reason), officeContact),
                stop, reason, session);
    }

    private FarmerStatusResponse aborted(Farmer farmer, TripStop stop) {
        // Never leave a stale ETA against a tanker that is not coming.
        return response(farmer, FarmerStatus.TRIP_ABORTED,
                ("Today's collection round was stopped and no tanker is coming to you. "
                        + "Please call %s.").formatted(officeContact),
                stop, null, null, null, null, null, null, null);
    }

    private FarmerStatusResponse notScheduled(Farmer farmer, Session session) {
        return new FarmerStatusResponse(
                farmer.getCode(), FarmerStatus.NOT_SCHEDULED.name(),
                "No %s collection has been dispatched yet today.".formatted(session.name().toLowerCase()),
                farmer.getCollectionPoint().getCode(),
                null, null, null, null, null, null, null, session.name(), officeContact);
    }

    private FarmerStatusResponse notServedToday(Farmer farmer, LocalDate today, Session session) {
        String reason = latestExclusionReason(farmer, today);
        LocalDate guaranteed = guaranteedBy(farmer, today);

        String message = guaranteed == null
                ? ("Your collection point is not on today's %s round (%s). Please call %s.")
                        .formatted(session.name().toLowerCase(), readable(reason), officeContact)
                : ("Your collection point is not on today's %s round (%s). You are guaranteed "
                        + "a collection by %s.")
                        .formatted(session.name().toLowerCase(), readable(reason), guaranteed);

        return new FarmerStatusResponse(
                farmer.getCode(), FarmerStatus.NOT_SERVED_TODAY.name(), message,
                farmer.getCollectionPoint().getCode(),
                null, null, null, null, null, reason, guaranteed,
                session == Session.MORNING ? Session.EVENING.name() : Session.MORNING.name(),
                officeContact);
    }

    /**
     * The date the three-strike rule promises service by.
     *
     * <p>A point at the skip limit is inserted into the next plan before anything is ranked,
     * so the promise is real rather than aspirational. Two sessions a day, so the remaining
     * skips convert to days at two per day, rounded up.
     */
    private LocalDate guaranteedBy(Farmer farmer, LocalDate today) {
        int limit = parameters.snapshot().getInt("maxConsecutiveSkips");

        return coverageRepo.findById(farmer.getCollectionPoint().getId())
                .map(PointCoverageState::getConsecutiveSkips)
                .map(skips -> {
                    int sessionsLeft = Math.max(0, limit - skips);
                    return today.plusDays((long) Math.ceil(sessionsLeft / (double) SESSIONS_PER_DAY));
                })
                .orElse(null);
    }

    /**
     * Why this point was excluded today, from the most recent plan for the date.
     *
     * <p>One indexed query returning one column. Reading every exclusion in the dairy and
     * filtering in memory also works, and it lazy-loads a plan per row to do it — which on a
     * hot evening with a thousand exclusions is a thousand queries to answer one farmer.
     */
    private String latestExclusionReason(Farmer farmer, LocalDate today) {
        return exclusionRepo
                .findReasonsFor(farmer.getCollectionPoint().getId(), today)
                .stream()
                .findFirst()
                .map(Enum::name)
                .orElse(null);
    }

    private FarmerStatusResponse response(Farmer farmer,
                                          FarmerStatus status,
                                          String message,
                                          TripStop stop,
                                          Instant etaAt,
                                          String confidence,
                                          Integer stopsAway,
                                          Instant collectedAt,
                                          BigDecimal litres,
                                          String reason,
                                          LocalDate guaranteedBy) {
        return new FarmerStatusResponse(
                farmer.getCode(), status.name(), message,
                stop.getCollectionPoint().getCode(),
                etaAt, confidence, stopsAway, collectedAt, litres, reason, guaranteedBy,
                null, officeContact);
    }

    private FarmerStatusResponse responseWithGuarantee(Farmer farmer,
                                                       FarmerStatus status,
                                                       String message,
                                                       TripStop stop,
                                                       String reason,
                                                       Session session) {
        LocalDate today = clock.now().atZone(ZoneOffset.UTC).toLocalDate();
        return new FarmerStatusResponse(
                farmer.getCode(), status.name(), message,
                stop.getCollectionPoint().getCode(),
                null, null, null, null, null, reason, guaranteedBy(farmer, today),
                session == Session.MORNING ? Session.EVENING.name() : Session.MORNING.name(),
                officeContact);
    }

    /** Turns SEQUENCE_SKIP into "sequence skip", which is what a person would say. */
    private static String readable(String reason) {
        return reason == null ? "no reason recorded" : reason.toLowerCase().replace('_', ' ');
    }
}
