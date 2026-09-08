package com.dairy.milkroute.enums;

/**
 * Lifecycle of a consolidation proposal. Mirrors chk_mp_status.
 *
 * <p>Proposals are generated and ranked, never executed. Merging collection points changes
 * how far people carry cans every morning, so the decision belongs to the cooperative;
 * only IMPLEMENTED writes back to collection_point.merged_into_id.
 */
public enum MergeProposalStatus {
    PROPOSED,
    ACCEPTED,
    REJECTED,
    IMPLEMENTED
}
