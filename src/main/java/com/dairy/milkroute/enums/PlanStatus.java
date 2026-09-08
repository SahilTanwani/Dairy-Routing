package com.dairy.milkroute.enums;

/**
 * Lifecycle of a route plan. Mirrors chk_plan_status.
 *
 * <p>Only one plan per session may be PUBLISHED at a time; that is enforced by the
 * partial unique index uq_one_published_per_session, not by this enum.
 */
public enum PlanStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
