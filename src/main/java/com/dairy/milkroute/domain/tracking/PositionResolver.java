package com.dairy.milkroute.domain.tracking;

import com.dairy.milkroute.domain.geo.GeoPoint;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.entity.TripStop;
import java.util.List;

/**
 * Where a tanker is right now.
 *
 * <p><strong>Events decide which leg it is on; pings only say how far along that leg.</strong>
 * That division is the whole design. A tanker that drives past a collection point has not
 * collected from it — the road goes past the point, the driver did not stop, the farmer is
 * still holding his can. Only an ARRIVED_AT_STOP moves the sequence forward.
 *
 * <p>Letting GPS advance progress would be easy and wrong in a way that is very hard to
 * notice: the board would show stop nine done, the farmer endpoint would say collected, and
 * the milk would still be at the roadside. Position is a presentation detail; progress is a
 * fact about milk.
 *
 * <p>So a ping can only ever place the tanker between the stop it last left and the next one
 * it is heading for, and the fraction is clamped to that leg however far off the ping is.
 */
public final class PositionResolver {

    /** A leg shorter than this is not worth interpolating along. */
    private static final double NEGLIGIBLE_LEG_KM = 0.01;

    /**
     * @param trip  the trip, whose {@code currentSeq} was set by arrival events
     * @param stops its stops in sequence order
     * @param plant where the run starts and ends
     * @return the tanker's position on its current leg
     */
    public ResolvedPosition resolve(Trip trip, List<TripStop> stops, GeoPoint plant) {
        int fromSeq = trip.getCurrentSeq();
        GeoPoint legStart = fromSeq == 0 ? plant : locationOf(stops, fromSeq, plant);
        GeoPoint legEnd = locationOf(stops, fromSeq + 1, plant);

        GeoPoint ping = lastPingOf(trip);
        if (ping == null) {
            return ResolvedPosition.atStop(fromSeq, legStart);
        }

        double legKm = legStart.haversineKm(legEnd);
        if (legKm < NEGLIGIBLE_LEG_KM) {
            return new ResolvedPosition(fromSeq, 1.0, ping, trip.getLastPingAt());
        }

        // Clamped, because a ping is only trusted to place the tanker on the leg the events
        // say it is on. A wandering fix cannot push it past a stop nobody has arrived at.
        double progress = Math.clamp(legStart.haversineKm(ping) / legKm, 0.0, 1.0);

        return new ResolvedPosition(fromSeq, progress, ping, trip.getLastPingAt());
    }

    /**
     * How much of the current leg is still ahead, as a fraction.
     *
     * <p>The ETA starts from here rather than from the last stop, which is the difference
     * between "twelve minutes to the next stop" and "twelve minutes from a village he left
     * eight minutes ago".
     */
    public double remainingFractionOfLeg(ResolvedPosition position) {
        return 1.0 - position.progress();
    }

    private static GeoPoint lastPingOf(Trip trip) {
        if (trip.getLastLat() == null || trip.getLastLng() == null) {
            return null;
        }
        return new GeoPoint(trip.getLastLat().doubleValue(), trip.getLastLng().doubleValue());
    }

    /**
     * The location of a stop by sequence number, falling back to the plant.
     *
     * <p>Past the last stop the tanker is on its way home, so the plant is the right
     * destination rather than an error.
     */
    private static GeoPoint locationOf(List<TripStop> stops, int seq, GeoPoint plant) {
        return stops.stream()
                .filter(stop -> stop.getSeq() == seq)
                .findFirst()
                .map(stop -> new GeoPoint(
                        stop.getCollectionPoint().getLat().doubleValue(),
                        stop.getCollectionPoint().getLng().doubleValue()))
                .orElse(plant);
    }
}
