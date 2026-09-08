package com.dairy.milkroute.simulation;

import com.dairy.milkroute.dto.request.DriverEventRequest;
import com.dairy.milkroute.dto.response.TripResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One driver, working through one trip.
 *
 * <p><strong>Everything it does goes through the real HTTP API.</strong> It never touches a
 * repository, never opens a transaction, never writes a row. That constraint is the entire
 * value of the simulation: if the driver wrote directly to the database it would be testing a
 * fixture, whereas going through {@code POST /drivers/trips/{id}/events} exercises request
 * validation, the clock-drift guard, the ingestion transaction, the unique index on
 * {@code client_event_id}, the replayer and the state machine — the same path a phone in a
 * village uses.
 *
 * <p>Deliberately not modelled: driver personalities, the farmer who rings the office, a
 * separate plant operator. They would make the log more colourful and would exercise nothing
 * the code does not already handle.
 *
 * <h2>The signal-loss window</h2>
 *
 * <p>Every driver goes offline for one hardcoded window. While offline, events are buffered
 * rather than sent — exactly as a phone does. On reconnect it sends the backlog <em>plus the
 * last three events the server has already acknowledged</em>, because a real phone cannot
 * know which of its sends got through before the signal dropped and resending is the correct,
 * safe behaviour.
 *
 * <p>The counts that come back prove the point: those three arrive as duplicates and change
 * nothing. That is the whole offline story, demonstrated rather than asserted.
 */
public final class VirtualDriver {

    private static final Logger log = LoggerFactory.getLogger(VirtualDriver.class);

    /** How long a tanker spends between stops in simulated time. */
    private static final Duration LEG = Duration.ofMinutes(6);

    /** How long it stands at one, collecting. */
    private static final Duration SERVICE = Duration.ofMinutes(3);

    /** How often the phone reports position while moving. */
    private static final Duration PING_EVERY = Duration.ofMinutes(2);

    /** How many already-acknowledged events to resend on reconnect. */
    private static final int RESEND_ON_RECONNECT = 3;

    private final long tripId;
    private final String driverCode;
    private final List<Stop> stops;
    private final SimulationTransport transport;
    private final Random rng;

    private final Instant offlineFrom;
    private final Instant offlineUntil;

    private final List<DriverEventRequest> buffered = new ArrayList<>();
    private final List<DriverEventRequest> acknowledged = new ArrayList<>();

    private int index;
    private boolean started;
    private boolean finished;
    private boolean wasOffline;
    private Instant nextActionAt;
    private Instant nextPingAt;

    public VirtualDriver(TripResponse trip,
                         SimulationTransport transport,
                         Instant startAt,
                         Instant offlineFrom,
                         Instant offlineUntil,
                         long seed) {
        this.tripId = trip.id();
        this.driverCode = trip.driverCode();
        this.transport = transport;
        this.offlineFrom = offlineFrom;
        this.offlineUntil = offlineUntil;
        this.rng = new Random(seed);
        this.nextActionAt = startAt;
        this.nextPingAt = startAt;

        this.stops = trip.stops().stream()
                .map(stop -> new Stop(
                        stop.seq(),
                        stop.farmers().stream().map(TripResponse.Farmer::code).toList()))
                .toList();
    }

    public boolean finished() {
        return finished;
    }

    public long tripId() {
        return tripId;
    }

    /**
     * Advances this driver to {@code now}.
     *
     * <p>Called once per simulation step. Does nothing until the clock reaches whatever the
     * driver is waiting for, which is what lets a whole fleet be driven from one loop.
     */
    public void tick(Instant now) {
        if (finished) {
            return;
        }

        reconnectIfDue(now);

        if (now.isAfter(nextPingAt) || now.equals(nextPingAt)) {
            sendPing(now);
            nextPingAt = now.plus(PING_EVERY);
        }

        if (now.isBefore(nextActionAt)) {
            return;
        }

        if (!started) {
            emit(event("TRIP_STARTED", now, null, null), now);
            started = true;
            nextActionAt = now.plus(LEG);
            return;
        }

        if (index >= stops.size()) {
            emit(event("ARRIVED_AT_PLANT", now, null, null), now);
            emit(event("UNLOADED", now.plusSeconds(60), null, null), now);
            finished = true;
            return;
        }

        Stop stop = stops.get(index);
        emit(event("ARRIVED_AT_STOP", now, stop.seq(), null), now);
        emit(event("COLLECTED", now.plus(SERVICE), stop.seq(), litresFor(stop)), now);
        emit(event("DEPARTED_STOP", now.plus(SERVICE).plusSeconds(30), stop.seq(), null), now);

        index++;
        nextActionAt = now.plus(SERVICE).plus(LEG);
    }

    /**
     * Sends an event, or buffers it if the phone has no signal.
     *
     * <p>The driver keeps working either way. That is the point of the window: the work does
     * not stop when the network does.
     */
    private void emit(DriverEventRequest event, Instant now) {
        if (offline(now)) {
            buffered.add(event);
            wasOffline = true;
            return;
        }
        SimulationTransport.IngestCounts counts = transport.sendEvents(tripId, List.of(event));
        remember(event);
        if (counts == null) {
            log.warn("Driver {} could not send an event for trip {}", driverCode, tripId);
        }
    }

    /**
     * The reconnect.
     *
     * <p>Backlog plus the last three the server already has. A phone cannot know which of its
     * sends landed before the drop, so resending is correct rather than sloppy — and the
     * duplicate count coming back is the proof that the server is built for it.
     */
    private void reconnectIfDue(Instant now) {
        if (!wasOffline || offline(now)) {
            return;
        }

        List<DriverEventRequest> toSend = new ArrayList<>(buffered);
        List<DriverEventRequest> resent = acknowledged.stream()
                .skip(Math.max(0, acknowledged.size() - RESEND_ON_RECONNECT))
                .toList();
        toSend.addAll(resent);

        SimulationTransport.IngestCounts counts = transport.sendEvents(tripId, toSend);

        log.info("Driver {} reconnected on trip {}: sent {} events ({} buffered + {} already "
                        + "acknowledged) -> {} applied, {} duplicates",
                driverCode, tripId, toSend.size(), buffered.size(), resent.size(),
                counts == null ? "?" : counts.applied(),
                counts == null ? "?" : counts.duplicates());

        buffered.forEach(this::remember);
        buffered.clear();
        wasOffline = false;
    }

    private void remember(DriverEventRequest event) {
        acknowledged.add(event);
    }

    private boolean offline(Instant now) {
        return !now.isBefore(offlineFrom) && now.isBefore(offlineUntil);
    }

    private void sendPing(Instant now) {
        if (offline(now)) {
            // A phone with no signal sends no positions either. This is what makes the
            // monitor's tracking-lost path fire during the window.
            return;
        }
        transport.sendPing(tripId, now);
    }

    /** Roughly the planned volume, varied a little, because no day is exactly average. */
    private List<DriverEventRequest.FarmerLitres> litresFor(Stop stop) {
        return stop.farmerCodes().stream()
                .map(code -> new DriverEventRequest.FarmerLitres(
                        code,
                        BigDecimal.valueOf(10 + rng.nextInt(20) + rng.nextInt(10) / 10.0)))
                .toList();
    }

    private static DriverEventRequest event(String type,
                                            Instant at,
                                            Integer seq,
                                            List<DriverEventRequest.FarmerLitres> collections) {
        return new DriverEventRequest(UUID.randomUUID(), type, at, seq, collections, null);
    }

    private record Stop(int seq, List<String> farmerCodes) {
    }
}
