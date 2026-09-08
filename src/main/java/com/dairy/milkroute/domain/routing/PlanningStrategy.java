package com.dairy.milkroute.domain.routing;

import com.dairy.milkroute.enums.PlanMode;
import java.util.List;

/**
 * One way of turning village blocks into routes.
 *
 * <p>Two implementations, and the difference between them is not an optimisation setting.
 * Full service assumes every point will be served and arranges them efficiently. Coverage
 * mode assumes some points will not be, and its real work is deciding <em>which</em> —
 * a question about fairness to farmers, not about distance.
 *
 * <p>Putting both behind one interface means the feasibility assessor picks the strategy
 * from the arithmetic and nothing above has to branch on it. The caller asks for a plan
 * and is told, in the result, which kind it got.
 */
public interface PlanningStrategy {

    /**
     * @param blocks the villages to serve, already solved internally
     * @param ctx    the world this plan is fixed against
     */
    PlanResult plan(List<VillageBlock> blocks, PlanningContext ctx);

    /** Which mode this strategy produces, recorded on the plan. */
    PlanMode mode();
}