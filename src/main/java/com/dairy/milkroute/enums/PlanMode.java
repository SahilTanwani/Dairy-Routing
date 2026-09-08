package com.dairy.milkroute.enums;

/**
 * Whether a plan could serve every active point or had to choose between them.
 * Mirrors chk_plan_mode.
 *
 * <p>COVERAGE_OPTIMISATION is selected by the feasibility assessor when required hot
 * minutes exceed what the fleet can supply, not by the caller.
 */
public enum PlanMode {
    FULL_SERVICE,
    COVERAGE_OPTIMISATION
}
