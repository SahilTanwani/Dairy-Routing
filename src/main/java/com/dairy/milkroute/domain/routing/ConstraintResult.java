package com.dairy.milkroute.domain.routing;

/**
 * Whether a route passed one constraint, and by how much it failed if it did not.
 *
 * <p>The numbers matter as much as the verdict. "Route seven is infeasible" is not
 * actionable; "route seven needs 141 minutes of hot time against a 127 minute budget"
 * tells ops that a cooler start time or an insulated tanker would fix it.
 *
 * @param passed     whether the route satisfies this constraint
 * @param constraint which constraint produced this result
 * @param actual     what the route needs
 * @param limit      what it is allowed
 * @param detail     a sentence for the feasibility report, null when passed
 */
public record ConstraintResult(
        boolean passed,
        String constraint,
        double actual,
        double limit,
        String detail) {

    public static ConstraintResult pass(String constraint, double actual, double limit) {
        return new ConstraintResult(true, constraint, actual, limit, null);
    }

    public static ConstraintResult fail(
            String constraint, double actual, double limit, String detail) {
        return new ConstraintResult(false, constraint, actual, limit, detail);
    }

    /** How much room is left. Negative when the constraint failed. */
    public double slack() {
        return limit - actual;
    }
}