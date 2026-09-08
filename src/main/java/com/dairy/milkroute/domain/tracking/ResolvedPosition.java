package com.dairy.milkroute.domain.tracking;

import com.dairy.milkroute.domain.geo.GeoPoint;
import java.time.Instant;

/**
 * Where a tanker is, expressed as how far along which leg.
 *
 * @param fromSeq     the stop it last left; 0 means it is still on the run out from the plant
 * @param progress    0 to 1 along the leg from {@code fromSeq} to the next stop
 * @param at          the interpolated position, or the raw ping when there is one
 * @param lastPingAt  when that position was observed; null if nothing has ever been heard
 */
public record ResolvedPosition(int fromSeq, double progress, GeoPoint at, Instant lastPingAt) {

    /** No ping yet: assume it is exactly where the events say, and no further. */
    public static ResolvedPosition atStop(int seq, GeoPoint at) {
        return new ResolvedPosition(seq, 0.0, at, null);
    }

    public boolean everSeen() {
        return lastPingAt != null;
    }
}
