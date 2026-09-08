-- V4 — coverage.
--
-- Two tables that exist so the system can be honest about who it did not serve. When the
-- day is too hot to reach everybody, the question stops being "which routes" and becomes
-- "who gets left out, and can we look them in the eye afterwards".

-- One row per collection point, carrying its service history. This is what stops the
-- algorithm quietly destroying the cooperative: without it, the same marginal points at
-- the end of the corridor are skipped every hot day, because they are always the least
-- efficient. `consecutive_skips` feeds the equity term in the scoring function and the
-- hard three-strike rule that overrides the score entirely.
CREATE TABLE point_coverage_state (
    collection_point_id BIGINT PRIMARY KEY REFERENCES collection_point(id),
    last_served_date    DATE,
    last_served_session VARCHAR(10),
    consecutive_skips   INT NOT NULL DEFAULT 0,
    skips_last_7_days   INT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Who was left out of each plan, why, and what it cost. When a farmer rings to ask why the
-- tanker did not come, ops has a real answer with a number attached instead of a shrug.
--
-- Reasons: UNREACHABLE_WITHIN_HOLD, COVERAGE_LIMIT, EXCEEDS_ALL_CAPACITY, POINT_INACTIVE.
CREATE TABLE plan_exclusion (
    id                  BIGSERIAL PRIMARY KEY,
    plan_id             BIGINT NOT NULL REFERENCES route_plan(id) ON DELETE CASCADE,
    collection_point_id BIGINT NOT NULL REFERENCES collection_point(id),
    reason              VARCHAR(32) NOT NULL,
    detail              TEXT,
    score               NUMERIC(9,4),
    litres_forgone      NUMERIC(7,2),
    UNIQUE (plan_id, collection_point_id)
);
