package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.entity.CollectionPoint;
import java.util.ArrayList;
import java.util.List;

/**
 * A candidate route: an ordered run of village blocks, from the plant and back to it.
 *
 * <p>Partial because it is what the savings loop works on rather than what it produces.
 * The planner seeds one of these per village and then merges pairs while the constraints
 * allow, so a route spends most of its life as a hypothesis that may be discarded.
 *
 * <p>Immutable, and merging returns a new instance. The savings loop evaluates a merge,
 * checks it against four constraints, and abandons it if any fails; if merging mutated the
 * operands, a rejected merge would leave the two routes it was built from already damaged
 * and the loop would carry on against corrupted state.
 *
 * <p>Everything here is arithmetic over blocks the route already holds. Anything needing a
 * travel model or a tanker — hot time, plant arrival, slack, whether it fits — belongs to
 * the constraint checker, which has those collaborators. This type deliberately cannot
 * answer whether it is feasible.
 *
 * @param blocks the villages this route serves, in visiting order
 */
public record PartialRoute(List<VillageBlock> blocks) {

    public PartialRoute {
        blocks = List.copyOf(blocks);

        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("a route must serve at least one village");
        }
    }

    /** The seed the savings loop starts from: one route per village block. */
    public static PartialRoute of(VillageBlock block) {
        return new PartialRoute(List.of(block));
    }

    /**
     * The two routes run end to end, {@code first} then {@code second}.
     *
     * <p>Concatenation only. The resulting order is what the savings pair implies, not a
     * good order — the sequence optimiser reorders it and the constraints then judge it,
     * in that order, because a bad ordering would reject a merge that is actually feasible.
     */
    public static PartialRoute concat(PartialRoute first, PartialRoute second) {
        List<VillageBlock> merged = new ArrayList<>(first.blocks.size() + second.blocks.size());
        merged.addAll(first.blocks);
        merged.addAll(second.blocks);
        return new PartialRoute(merged);
    }

    /** A copy of this route with its villages in the given order. */
    public PartialRoute resequenced(List<VillageBlock> ordered) {
        return new PartialRoute(ordered);
    }

    /** Where the tanker leaves the plant for: the outbound leg's destination. */
    public VillageBlock firstBlock() {
        return blocks.getFirst();
    }

    /** The last village served, and therefore where the run home to the plant begins. */
    public VillageBlock lastBlock() {
        return blocks.getLast();
    }

    public double totalLitres() {
        return blocks.stream().mapToDouble(VillageBlock::litres).sum();
    }

    /**
     * Time spent working inside villages, summed.
     *
     * <p>Not the route's hot time: the legs between villages and the run back to the plant
     * are missing, and both need a travel model this type does not have.
     */
    public double internalMinutes() {
        return blocks.stream().mapToDouble(VillageBlock::internalMinutes).sum();
    }

    public int villageCount() {
        return blocks.size();
    }

    public int stopCount() {
        return blocks.stream().mapToInt(VillageBlock::stopCount).sum();
    }

    /** Every collection point on the route, in visiting order. */
    public List<CollectionPoint> stops() {
        return blocks.stream().flatMap(block -> block.sequence().stream()).toList();
    }

    /** Whether this route already serves the given village, which a merge must not repeat. */
    public boolean serves(VillageBlock block) {
        return blocks.contains(block);
    }
}