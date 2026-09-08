package com.dairy.milkroute.domain.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.enums.TripStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Every legal transition, and a sample of the illegal ones that out-of-order offline events
 * would otherwise produce.
 */
class TripStateMachineTest {

    private final TripStateMachine machine = new TripStateMachine();

    @Nested
    @DisplayName("the legal moves")
    class Legal {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "SCHEDULED,   IN_PROGRESS",
                "SCHEDULED,   ABORTED",
                "SCHEDULED,   BLOCKED",
                "IN_PROGRESS, RETURNING",
                "IN_PROGRESS, BREAKDOWN",
                "IN_PROGRESS, ABORTED",
                "RETURNING,   AT_PLANT",
                "RETURNING,   BREAKDOWN",
                "RETURNING,   ABORTED",
                "AT_PLANT,    COMPLETED",
                "BREAKDOWN,   RETURNING",
                "BREAKDOWN,   ABORTED",
        })
        void areAllowed(TripStatus from, TripStatus to) {
            Trip trip = tripAt(from);

            machine.transition(trip, to);

            assertThat(trip.getStatus()).isEqualTo(to);
        }

        @Test
        void coverEveryEdgeInTheTable() {
            // If a transition is added to the machine without a row above, this fails and
            // says so, rather than the new edge going untested.
            int edges = 0;
            for (TripStatus from : TripStatus.values()) {
                edges += machine.allowedFrom(from).size();
            }
            assertThat(edges).isEqualTo(12);
        }

        @Test
        void aWholeOrdinaryTripRunsEndToEnd() {
            Trip trip = tripAt(TripStatus.SCHEDULED);

            for (TripStatus next : List.of(TripStatus.IN_PROGRESS, TripStatus.RETURNING,
                    TripStatus.AT_PLANT, TripStatus.COMPLETED)) {
                machine.transition(trip, next);
            }

            assertThat(trip.getStatus()).isEqualTo(TripStatus.COMPLETED);
        }

        @Test
        void aBreakdownCanBeRecoveredFrom() {
            Trip trip = tripAt(TripStatus.IN_PROGRESS);

            machine.transition(trip, TripStatus.BREAKDOWN);
            machine.transition(trip, TripStatus.RETURNING);
            machine.transition(trip, TripStatus.AT_PLANT);

            // A recovered breakdown still delivers its milk, which is the point of allowing
            // BREAKDOWN back to RETURNING rather than treating it as terminal.
            assertThat(trip.getStatus()).isEqualTo(TripStatus.AT_PLANT);
        }
    }

    @Nested
    @DisplayName("the illegal ones")
    class Illegal {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                // A replayed TRIP_STARTED arriving after the tanker is already home.
                "COMPLETED,   IN_PROGRESS",
                // Skipping the road entirely.
                "SCHEDULED,   COMPLETED",
                "SCHEDULED,   AT_PLANT",
                "SCHEDULED,   RETURNING",
                // Going backwards, which a late-arriving event would otherwise cause.
                "AT_PLANT,    IN_PROGRESS",
                "RETURNING,   IN_PROGRESS",
                // Reviving something that is over.
                "ABORTED,     IN_PROGRESS",
                "COMPLETED,   ABORTED",
                // A blocked trip is not started, it is replaced.
                "BLOCKED,     IN_PROGRESS",
                // Unloading without arriving.
                "IN_PROGRESS, COMPLETED",
                "IN_PROGRESS, AT_PLANT",
        })
        void areRefused(TripStatus from, TripStatus to) {
            Trip trip = tripAt(from);

            assertThatThrownBy(() -> machine.transition(trip, to))
                    .isInstanceOf(IllegalTransitionException.class)
                    .hasMessageContaining(from.name())
                    .hasMessageContaining(to.name());

            assertThat(trip.getStatus())
                    .as("a refused transition must leave the trip where it was")
                    .isEqualTo(from);
        }

        @ParameterizedTest
        @EnumSource(TripStatus.class)
        void aTripCannotTransitionToItself(TripStatus status) {
            // Not a no-op: a duplicate ARRIVED_AT_PLANT should be ignored by the replayer,
            // not quietly re-applied as a state change.
            assertThat(machine.canTransition(status, status)).isFalse();
        }

        @Test
        void carriesBothEndsOfTheRefusal() {
            IllegalTransitionException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalTransitionException.class,
                    () -> machine.transition(tripAt(TripStatus.COMPLETED), TripStatus.IN_PROGRESS));

            assertThat(thrown.from()).isEqualTo(TripStatus.COMPLETED);
            assertThat(thrown.to()).isEqualTo(TripStatus.IN_PROGRESS);
        }
    }

    @Nested
    @DisplayName("terminal states")
    class Terminal {

        @Test
        void nothingLeavesCompletedAbortedOrBlocked() {
            assertThat(machine.isTerminal(TripStatus.COMPLETED)).isTrue();
            assertThat(machine.isTerminal(TripStatus.ABORTED)).isTrue();
            assertThat(machine.isTerminal(TripStatus.BLOCKED)).isTrue();
        }

        @Test
        void everythingElseHasSomewhereToGo() {
            assertThat(machine.isTerminal(TripStatus.SCHEDULED)).isFalse();
            assertThat(machine.isTerminal(TripStatus.IN_PROGRESS)).isFalse();
            assertThat(machine.isTerminal(TripStatus.RETURNING)).isFalse();
            assertThat(machine.isTerminal(TripStatus.AT_PLANT)).isFalse();
            assertThat(machine.isTerminal(TripStatus.BREAKDOWN)).isFalse();
        }
    }

    private static Trip tripAt(TripStatus status) {
        Trip trip = new Trip();
        trip.setStatus(status);
        return trip;
    }
}
