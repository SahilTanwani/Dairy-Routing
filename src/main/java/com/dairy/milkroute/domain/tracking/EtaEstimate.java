package com.dairy.milkroute.domain.tracking;

import com.dairy.milkroute.enums.EtaConfidence;
import java.time.Instant;

/**
 * An arrival time and how much it is worth.
 *
 * <p>The confidence travels with the number rather than beside it, because the two are only
 * safe together. Anything that renders an ETA for a person has to decide, from this, whether
 * to say a time, a window, or where the tanker was last seen.
 *
 * @param at               when it is expected
 * @param confidence       how much to trust that
 * @param delayFactor      the driver's observed pace against plan; 1.0 is on time
 * @param remainingMinutes minutes of travel and service still ahead, already scaled
 * @param position         where the tanker was when this was worked out
 */
public record EtaEstimate(
        Instant at,
        EtaConfidence confidence,
        double delayFactor,
        long remainingMinutes,
        ResolvedPosition position) {

    /** Whether this is precise enough to read out as a clock time. */
    public boolean preciseEnoughToQuote() {
        return confidence == EtaConfidence.HIGH;
    }

    /** Whether the tanker is visible at all. */
    public boolean trackingLost() {
        return confidence == EtaConfidence.LOST;
    }
}
