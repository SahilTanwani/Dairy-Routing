package com.dairy.milkroute.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.entity.Alert;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.enums.AlertType;
import com.dairy.milkroute.enums.TripStatus;
import com.dairy.milkroute.repository.AlertRepository;
import com.dairy.milkroute.repository.TripRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * The one-way ratchet: the budget shrinks when the day turns hot and never grows back.
 *
 * <p>This is the rule that protects milk on the afternoon the weather turns, and until the
 * monitor could be told what the air is doing it was unreachable — {@code
 * temperature_profile} returns one figure per month per session, so ambient never moved
 * mid-trip and the shrink branch never ran.
 *
 * <p>The numbers come from the Q10 model: at 22 °C a plain tanker holds milk for 313
 * minutes, at 35 °C for 127. The interesting assertion is the third one, where ambient falls
 * back to 22 and the budget stays at 127. Milk that has spent an hour at 35 °C did not become
 * fresher when a cloud went over.
 */
@SpringBootTest
@Transactional
class SpoilageRatchetTest {

    private static final Instant SERVER_NOW = Instant.parse("2026-10-15T09:00:00Z");

    private static final double COOL = 22.0;
    private static final double HOT = 35.0;

    /** Budgets the Q10 model gives a plain tanker at those temperatures. */
    private static final int BUDGET_AT_COOL = 313;
    private static final int BUDGET_AT_HOT = 127;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        ClockProvider testClock() {
            return () -> SERVER_NOW;
        }
    }

    @Autowired private SpoilageMonitorService monitor;
    @Autowired private TripRepository tripRepo;
    @Autowired private AlertRepository alertRepo;
    @Autowired private VillageFixture fixture;

    private Trip trip;

    @BeforeEach
    void aTripWithMilkAboard() {
        trip = fixture.build(6).trip();

        // A trip carrying milk: the clock only runs once something is aboard.
        trip.setStatus(TripStatus.IN_PROGRESS);
        trip.setFirstCollectionAt(SERVER_NOW.minus(Duration.ofMinutes(30)));
        trip.setSpoilageDeadlineAt(
                trip.getFirstCollectionAt().plus(Duration.ofMinutes(BUDGET_AT_COOL)));
        trip.setHoldBudgetMinutes(BUDGET_AT_COOL);
        trip.setLitresOnBoard(BigDecimal.valueOf(120));
        trip.setCurrentSeq(2);
        // Freshly seen, so tracking loss does not colour the result.
        trip.setLastPingAt(SERVER_NOW.minus(Duration.ofMinutes(1)));
        tripRepo.save(trip);
    }

    @Test
    @DisplayName("hot: the budget shrinks and the deadline moves earlier")
    void aRiseCutsTheBudget() {
        Instant deadlineBefore = trip.getSpoilageDeadlineAt();

        monitor.check(trip, HOT);

        assertThat(trip.getHoldBudgetMinutes()).isEqualTo(BUDGET_AT_HOT);
        assertThat(trip.getSpoilageDeadlineAt())
                .isEqualTo(trip.getFirstCollectionAt().plus(Duration.ofMinutes(BUDGET_AT_HOT)))
                .isBefore(deadlineBefore);
    }

    @Test
    @DisplayName("then cool again: the budget does NOT grow back")
    void aFallLeavesItWhereItWas() {
        monitor.check(trip, HOT);
        Instant tightened = trip.getSpoilageDeadlineAt();

        // The cloud goes over. The milk is not less spoiled for it.
        monitor.check(trip, COOL);

        assertThat(trip.getHoldBudgetMinutes())
                .as("bacterial damage is cumulative; the budget only ratchets down")
                .isEqualTo(BUDGET_AT_HOT);
        assertThat(trip.getSpoilageDeadlineAt()).isEqualTo(tightened);
    }

    @Test
    void repeatedSweepsAtTheSameTemperatureChangeNothing() {
        monitor.check(trip, HOT);
        Instant afterFirst = trip.getSpoilageDeadlineAt();

        monitor.check(trip, HOT);
        monitor.check(trip, HOT);

        assertThat(trip.getHoldBudgetMinutes()).isEqualTo(BUDGET_AT_HOT);
        assertThat(trip.getSpoilageDeadlineAt()).isEqualTo(afterFirst);
    }

    @Test
    void aFurtherRiseCutsItAgain() {
        monitor.check(trip, HOT);
        int afterFirstCut = trip.getHoldBudgetMinutes();

        monitor.check(trip, 39.0);

        assertThat(trip.getHoldBudgetMinutes()).isLessThan(afterFirstCut);
    }

    @Test
    void cuttingTheBudgetRaisesADeadlineTightenedAlert() {
        monitor.check(trip, HOT);

        List<Alert> raised = alertRepo.findByTripIdAndResolvedAtIsNull(trip.getId());

        assertThat(raised)
                .as("the dispatcher has to be told the deadline moved under them")
                .anySatisfy(alert -> {
                    assertThat(alert.getAlertType()).isEqualTo(AlertType.DEADLINE_TIGHTENED);
                    assertThat(alert.getMessage()).contains("35.0").contains("127");
                });
    }

    @Test
    void aFallOnItsOwnRaisesNothing() {
        // Nothing has changed for the worse, so there is nothing to tell anyone.
        monitor.check(trip, 18.0);

        assertThat(alertRepo.findByTripIdAndResolvedAtIsNull(trip.getId()))
                .noneSatisfy(alert ->
                        assertThat(alert.getAlertType()).isEqualTo(AlertType.DEADLINE_TIGHTENED));
        assertThat(trip.getHoldBudgetMinutes()).isEqualTo(BUDGET_AT_COOL);
    }

    @Test
    void anEmptyTankerHasNoClockToRatchet() {
        trip.setFirstCollectionAt(null);
        trip.setHoldBudgetMinutes(BUDGET_AT_COOL);
        tripRepo.save(trip);

        monitor.check(trip, HOT);

        assertThat(trip.getHoldBudgetMinutes())
                .as("nothing aboard, nothing ageing")
                .isEqualTo(BUDGET_AT_COOL);
    }

    @Test
    void withNoOverrideItReadsTheTemperatureProfile() {
        // The default path still works: October morning is 21 C in the seeded profile, which
        // is cooler than the trip's 22 C budget was built against, so nothing shrinks.
        monitor.check(trip);

        assertThat(trip.getHoldBudgetMinutes()).isEqualTo(BUDGET_AT_COOL);
    }
}
