package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.domain.geo.GeoPoint;
import com.dairy.milkroute.entity.CollectionPoint;
import com.dairy.milkroute.entity.Village;
import java.util.List;
import java.util.Objects;

/**
 * One village, solved: its collection points in the order a tanker should visit them, with
 * the cost of doing so.
 *
 * <p>This is the unit that makes the problem tractable. Arranging 1,250 points is a
 * different order of difficulty from arranging 60 blocks, and the decomposition is honest
 * because the geography supports it — points inside a village are a minute or two apart
 * while villages are tens of minutes apart. Complexity then grows with village count
 * rather than point count, which is what lets the dairy triple in size without the planner
 * falling over.
 *
 * <p>Immutable. The planner builds many candidate routes over the same blocks, and a block
 * that could be mutated by one candidate would corrupt every other candidate holding it.
 *
 * @param village          the village this block covers
 * @param sequence         its points in visiting order, as solved by the village solver
 * @param internalMinutes  driving between the points plus the service time at each; the
 *                         cost of working the village once a tanker is inside it
 * @param litres           expected volume for the session this block was solved for
 * @param hotMinutesRequired
 *        internal minutes plus the return leg to the plant: the minimum on-milk time this
 *        village costs any route that serves it. Supplied by whoever builds the block
 *        rather than computed here, because it needs a plant and a travel model, and a
 *        data holder should not be carrying either.
 */
public record VillageBlock(
        Village village,
        List<CollectionPoint> sequence,
        double internalMinutes,
        double litres,
        double hotMinutesRequired) {

    public VillageBlock {
        Objects.requireNonNull(village, "village");
        sequence = List.copyOf(sequence);

        if (sequence.isEmpty()) {
            throw new IllegalArgumentException(
                    "a block must hold at least one point; village " + village.getCode());
        }
    }

    /**
     * The first point visited, and therefore where a tanker enters this village.
     *
     * <p>Derived from the sequence rather than stored alongside it. Holding entry and exit
     * as separate fields would let them disagree with the sequence after a resequencing,
     * and a route whose legs are measured from the wrong end of a village is a plan that
     * looks right and is not.
     */
    public CollectionPoint entry() {
        return sequence.getFirst();
    }

    /** The last point visited, and where the leg to the next village starts. */
    public CollectionPoint exit() {
        return sequence.getLast();
    }

    public GeoPoint entryLocation() {
        return locationOf(entry());
    }

    public GeoPoint exitLocation() {
        return locationOf(exit());
    }

    public int stopCount() {
        return sequence.size();
    }

    private static GeoPoint locationOf(CollectionPoint point) {
        return new GeoPoint(point.getLat().doubleValue(), point.getLng().doubleValue());
    }
}