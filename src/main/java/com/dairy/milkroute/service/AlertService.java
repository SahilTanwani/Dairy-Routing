package com.dairy.milkroute.service;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.entity.Alert;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.enums.AlertSeverity;
import com.dairy.milkroute.enums.AlertType;
import com.dairy.milkroute.repository.AlertRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raises alerts without drowning the board.
 *
 * <p>The monitor sweeps every sixty seconds. A trip that is in trouble for forty minutes is
 * in trouble on forty consecutive sweeps, and a system that raised forty identical alerts
 * would have trained the dispatcher to ignore the board long before anyone acted on the
 * first one. An alert board nobody reads is worse than no alert board, because it looks like
 * coverage.
 *
 * <p>So every alert carries a {@code dedupe_key}, and {@code uq_alert_open} — unique on that
 * key among unresolved rows — makes a second open copy impossible in the database rather
 * than merely unlikely in the service. Repeated WARNINGs update the one open row; an
 * escalation from WARNING to CRITICAL is a different key and therefore a genuinely new
 * alert, which is exactly the moment somebody should look up.
 *
 * <p>Because the index is scoped to unresolved rows, the same situation can raise a fresh
 * alert tomorrow once today's has been closed.
 */
@Service
public class AlertService {

    private final AlertRepository alertRepo;
    private final ClockProvider clock;

    public AlertService(AlertRepository alertRepo, ClockProvider clock) {
        this.alertRepo = alertRepo;
        this.clock = clock;
    }

    /**
     * Raises an alert, or refreshes the open one with the same key.
     *
     * @param dedupeKey what makes two alerts "the same alert"; severity belongs in it, so an
     *                  escalation is not swallowed as a repeat
     */
    @Transactional
    public Alert raise(AlertType type,
                       AlertSeverity severity,
                       Trip trip,
                       String dedupeKey,
                       String message,
                       String payloadJson) {

        Optional<Alert> open = alertRepo.findByDedupeKeyAndResolvedAtIsNull(dedupeKey);
        if (open.isPresent()) {
            // Same situation, newer numbers. The board should show the current shortfall, not
            // the one from forty minutes ago, but this is not a new thing to react to.
            Alert existing = open.get();
            existing.setMessage(message);
            existing.setPayload(payloadJson);
            return alertRepo.save(existing);
        }

        Alert alert = new Alert();
        alert.setTrip(trip);
        alert.setAlertType(type);
        alert.setSeverity(severity);
        alert.setDedupeKey(dedupeKey);
        alert.setMessage(message);
        alert.setPayload(payloadJson);
        alert.setRaisedAt(clock.now());
        return alertRepo.save(alert);
    }

    /** The standard key: one open alert per trip per severity per kind of problem. */
    public static String keyFor(AlertType type, long tripId, AlertSeverity severity) {
        return "%s:%d:%s".formatted(type.name(), tripId, severity.name());
    }

    @Transactional
    public void resolve(String dedupeKey, String resolution) {
        alertRepo.findByDedupeKeyAndResolvedAtIsNull(dedupeKey).ifPresent(alert -> {
            alert.setResolvedAt(clock.now());
            alertRepo.save(alert);
        });
    }

    /**
     * Closes any open alert of this kind for a trip whose risk has passed.
     *
     * <p>Without this, a trip that recovers leaves its WARNING open for ever and the board
     * slowly fills with problems that stopped being problems hours ago.
     */
    @Transactional
    public void resolveFor(AlertType type, long tripId) {
        for (AlertSeverity severity : AlertSeverity.values()) {
            resolve(keyFor(type, tripId, severity), "risk cleared");
        }
    }

    @Transactional(readOnly = true)
    public List<Alert> open() {
        return alertRepo.findByResolvedAtIsNullOrderByRaisedAtDesc();
    }

    @Transactional(readOnly = true)
    public long openCount(AlertSeverity severity) {
        return alertRepo.countBySeverityAndResolvedAtIsNull(severity);
    }
}
