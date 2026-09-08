package com.dairy.milkroute.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dairy.milkroute.dto.request.DriverEventRequest;
import com.dairy.milkroute.dto.response.IngestResult;
import com.dairy.milkroute.entity.CollectionPoint;
import com.dairy.milkroute.entity.Driver;
import com.dairy.milkroute.entity.Farmer;
import com.dairy.milkroute.entity.Plant;
import com.dairy.milkroute.entity.Route;
import com.dairy.milkroute.entity.RoutePlan;
import com.dairy.milkroute.entity.Tanker;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.entity.TripStop;
import com.dairy.milkroute.entity.Village;
import com.dairy.milkroute.enums.PlanMode;
import com.dairy.milkroute.enums.PlanSource;
import com.dairy.milkroute.enums.PlanStatus;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.enums.TankerStatus;
import com.dairy.milkroute.enums.TripStatus;
import com.dairy.milkroute.enums.TripStopStatus;
import com.dairy.milkroute.repository.CollectionPointRepository;
import com.dairy.milkroute.repository.DriverEventRepository;
import com.dairy.milkroute.repository.DriverRepository;
import com.dairy.milkroute.repository.FarmerRepository;
import com.dairy.milkroute.repository.MilkCollectionRepository;
import com.dairy.milkroute.repository.PlantRepository;
import com.dairy.milkroute.repository.RoutePlanRepository;
import com.dairy.milkroute.repository.RouteRepository;
import com.dairy.milkroute.repository.TankerRepository;
import com.dairy.milkroute.repository.TripRepository;
import com.dairy.milkroute.repository.TripStopRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

/**
 * The offline story, end to end against a real database.
 *
 * <p>This one has to be an integration test. The guarantee being tested is a partial unique
 * index in Postgres, and a mock would only prove that the mock behaves the way I imagined the
 * index does.
 *
 * <p>The scenario is the real one: a driver works through his stops, loses signal, and the
 * phone flushes its buffer on reconnect — including five events the server already took,
 * because the phone cannot know which of them got through before the drop.
 */
@SpringBootTest
@Transactional
class EventIngestionIdempotencyTest {

    /**
     * The trip runs on 15 October 2026, so the server's clock has to be there too. Without
     * this the clock-drift guard refuses the batch — correctly, since a device weeks ahead of
     * the server would write a spoilage deadline weeks wrong. Fixing the clock is also what
     * makes the timestamps in these tests exact assertions rather than ranges.
     */
    private static final Instant SERVER_NOW = Instant.parse("2026-10-15T09:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        com.dairy.milkroute.config.ClockProvider testClock() {
            return () -> SERVER_NOW;
        }
    }

    private static final int STOPS = 10;
    private static final int RESENT = 5;
    private static final BigDecimal LITRES_PER_FARMER = BigDecimal.valueOf(12.50);

    @Autowired private EventIngestionService ingestion;
    @Autowired private TripRepository tripRepo;
    @Autowired private TripStopRepository tripStopRepo;
    @Autowired private DriverEventRepository eventRepo;
    @Autowired private MilkCollectionRepository collectionRepo;
    @Autowired private VillageFixture fixture;

    private Trip trip;
    private List<TripStop> stops;
    private List<String> farmerCodes;

    @BeforeEach
    void createATripToDriveDown() {
        VillageFixture.Built built = fixture.build(STOPS);
        trip = built.trip();
        stops = built.stops();
        farmerCodes = built.farmerCodes();
    }

    @Test
    @DisplayName("30 events, 5 resent: 25 applied, 5 duplicates, no double milk")
    void resendingAfterAReconnectChangesNothing() {
        List<DriverEventRequest> firstRun = aDriveDownTheRoute();
        assertThat(firstRun).hasSize(30);

        IngestResult first = ingestion.ingest(trip.getId(), firstRun);

        assertThat(first.applied()).isEqualTo(30);
        assertThat(first.duplicates()).isZero();

        long collectionsAfterFirst = collectionRepo.findByTripId(trip.getId()).size();
        assertThat(collectionsAfterFirst).isEqualTo(STOPS);

        // The reconnect. The phone resends the last five it holds — the same events, the same
        // client-generated ids — plus five it never managed to send.
        List<DriverEventRequest> replay = new ArrayList<>(
                firstRun.subList(firstRun.size() - RESENT, firstRun.size()));
        List<DriverEventRequest> fresh = departuresFor(RESENT);
        replay.addAll(fresh);
        Collections.shuffle(replay, new java.util.Random(7));

        IngestResult second = ingestion.ingest(trip.getId(), replay);

        assertThat(second.received()).isEqualTo(RESENT * 2);
        assertThat(second.applied()).isEqualTo(RESENT);
        assertThat(second.duplicates()).isEqualTo(RESENT);

        // The assertion that matters. A duplicate COLLECTED that produced a second row would
        // pay a farmer twice for one can of milk.
        assertThat(collectionRepo.findByTripId(trip.getId()))
                .as("no farmer may be paid twice for one visit")
                .hasSize(STOPS);

        assertThat(eventRepo.findByTripIdOrderByClientTsAsc(trip.getId()))
                .as("35 distinct events, not 40")
                .hasSize(30 + RESENT);
    }

    @Test
    void theWholeBatchResentAgainAppliesNothing() {
        List<DriverEventRequest> events = aDriveDownTheRoute();
        ingestion.ingest(trip.getId(), events);

        IngestResult again = ingestion.ingest(trip.getId(), events);

        assertThat(again.applied()).isZero();
        assertThat(again.duplicates()).isEqualTo(events.size());
        assertThat(collectionRepo.findByTripId(trip.getId())).hasSize(STOPS);
    }

    @Test
    void anOutOfOrderBatchIsReplayedInTheOrderThingsHappened() {
        List<DriverEventRequest> events = aDriveDownTheRoute();
        Collections.shuffle(events, new java.util.Random(42));

        IngestResult result = ingestion.ingest(trip.getId(), events);

        // Sorted by clientTs before replay, so a shuffled reconnect produces the same trip
        // as an orderly one: every stop collected, and the trip left where it should be.
        assertThat(result.applied()).isEqualTo(events.size());
        assertThat(tripStopRepo.findByTripIdOrderBySeqAsc(trip.getId()))
                .allSatisfy(stop -> assertThat(stop.getStatus()).isEqualTo(TripStopStatus.COLLECTED));
        assertThat(collectionRepo.findByTripId(trip.getId())).hasSize(STOPS);
    }

    @Test
    void aCollectedWithNoArrivalStillRecordsTheMilk() {
        // The dropped-event case. Losing an arrival time is a nuisance; losing a milk record
        // is money a farmer is owed and will not be paid.
        Instant at = Instant.parse("2026-10-15T05:40:00Z");
        DriverEventRequest started = event("TRIP_STARTED", at.minusSeconds(600), null, null);
        DriverEventRequest collected = collectedAt(1, at);

        IngestResult result = ingestion.ingest(trip.getId(), List.of(started, collected));

        assertThat(result.applied()).isEqualTo(2);

        TripStop first = tripStopRepo.findByTripIdAndSeq(trip.getId(), 1).orElseThrow();
        assertThat(first.getStatus()).isEqualTo(TripStopStatus.COLLECTED);
        assertThat(first.getArrivedAt())
                .as("the arrival is inferred, not left null")
                .isNotNull()
                .isBefore(at);
        assertThat(collectionRepo.findByTripStopId(first.getId())).hasSize(1);
    }

    @Test
    void theSpoilageClockStartsAtTheFirstCollectionAndNeverMoves() {
        Instant firstCollection = Instant.parse("2026-10-15T05:40:00Z");
        ingestion.ingest(trip.getId(), List.of(
                event("TRIP_STARTED", firstCollection.minusSeconds(1200), null, null),
                collectedAt(1, firstCollection),
                collectedAt(2, firstCollection.plusSeconds(900))));

        Trip reloaded = tripRepo.findById(trip.getId()).orElseThrow();

        assertThat(reloaded.getFirstCollectionAt()).isEqualTo(firstCollection);
        assertThat(reloaded.getSpoilageDeadlineAt())
                .isEqualTo(firstCollection.plusSeconds(reloaded.getHoldBudgetMinutes() * 60L));
    }

    // ------------------------------------------------------------------ the drive

    /** Start, then arrive-collect-depart down ten stops: thirty events. */
    private List<DriverEventRequest> aDriveDownTheRoute() {
        List<DriverEventRequest> events = new ArrayList<>();
        Instant at = Instant.parse("2026-10-15T05:00:00Z");

        events.add(event("TRIP_STARTED", at, null, null));

        for (int seq = 1; seq <= STOPS; seq++) {
            at = at.plusSeconds(600);
            events.add(event("ARRIVED_AT_STOP", at, seq, null));
            at = at.plusSeconds(180);
            events.add(collectedAt(seq, at));
            at = at.plusSeconds(60);
            events.add(event("DEPARTED_STOP", at, seq, null));
        }
        // 1 + 10 x 3 = 31; drop the last departure to land on exactly thirty.
        events.removeLast();
        return events;
    }

    /** Events the phone never managed to send before it lost signal. */
    private List<DriverEventRequest> departuresFor(int count) {
        List<DriverEventRequest> events = new ArrayList<>();
        Instant at = Instant.parse("2026-10-15T07:30:00Z");
        for (int i = 0; i < count; i++) {
            at = at.plusSeconds(120);
            events.add(event("DEPARTED_STOP", at, STOPS, null));
        }
        return events;
    }

    private DriverEventRequest collectedAt(int seq, Instant at) {
        return event("COLLECTED", at, seq,
                List.of(new DriverEventRequest.FarmerLitres(
                        farmerCodes.get(seq - 1), LITRES_PER_FARMER)));
    }

    private DriverEventRequest event(String type,
                                     Instant at,
                                     Integer seq,
                                     List<DriverEventRequest.FarmerLitres> collections) {
        return new DriverEventRequest(UUID.randomUUID(), type, at, seq, collections, null);
    }
}
