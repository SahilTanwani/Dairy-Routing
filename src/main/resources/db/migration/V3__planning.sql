-- V3 — planning.
--
-- What we intend: route_plan -> route -> route_stop. The other half of the model
-- (trip -> trip_stop -> collection, in V5) is what actually happened. The two meet at
-- `trip`, which copies from a route once and then goes its own way.

CREATE TABLE route_plan (
    id             BIGSERIAL PRIMARY KEY,
    version        INT          NOT NULL,
    session        VARCHAR(10)  NOT NULL,
    source         VARCHAR(16)  NOT NULL,
    -- Whether this plan could serve everyone or had to choose. Coverage mode is a
    -- property of the plan, not a runtime flag, so a published plan is self-describing.
    mode           VARCHAR(24)  NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    planned_temp_c NUMERIC(4,1) NOT NULL,
    effective_from DATE         NOT NULL,
    generated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    generation_ms  INT,
    -- JSONB because the feasibility report's shape will keep evolving and it is read as a
    -- whole blob, never queried field by field.
    feasibility    JSONB,
    notes          TEXT,
    CONSTRAINT chk_plan_session CHECK (session IN ('MORNING','EVENING')),
    CONSTRAINT chk_plan_source  CHECK (source  IN ('GENERATED','LEGACY','MANUAL')),
    CONSTRAINT chk_plan_mode    CHECK (mode    IN ('FULL_SERVICE','COVERAGE_OPTIMISATION')),
    CONSTRAINT chk_plan_status  CHECK (status  IN ('DRAFT','PUBLISHED','ARCHIVED')),
    UNIQUE (session, version)
);

-- The most important constraint in the schema. At most one published morning plan and at
-- most one published evening plan, enforced by Postgres rather than by service code.
--
-- If two morning plans were live, trip creation would pick one arbitrarily and half the
-- fleet would run the wrong routes. Because this is a partial unique index, no bug in
-- application code can produce that state: not a race between two dispatchers, not a
-- double-clicked publish button. The second insert fails.
CREATE UNIQUE INDEX uq_one_published_per_session
    ON route_plan(session) WHERE status = 'PUBLISHED';

CREATE TABLE route (
    id                  BIGSERIAL PRIMARY KEY,
    plan_id             BIGINT       NOT NULL REFERENCES route_plan(id) ON DELETE CASCADE,
    label               VARCHAR(16)  NOT NULL,
    -- Nullable: a draft plan can exist before tankers and drivers are assigned to it.
    tanker_id           BIGINT       REFERENCES tanker(id),
    driver_id           BIGINT       REFERENCES driver(id),
    plant_id            BIGINT       NOT NULL REFERENCES plant(id),
    planned_depart_at   TIME         NOT NULL,
    -- How old the oldest milk will be when this tanker reaches the plant. Excludes the
    -- plant -> first-village leg, which carries no milk.
    est_hot_minutes     INT          NOT NULL,
    hold_budget_minutes INT          NOT NULL,
    -- Budget minus hot time. Stored rather than computed because the ops board sorts
    -- routes by risk, and the feasibility report flags a six-minute-slack route amber
    -- before anyone publishes it.
    slack_minutes       INT          NOT NULL,
    est_volume_litres   NUMERIC(9,2) NOT NULL,
    est_distance_km     NUMERIC(7,2) NOT NULL,
    stop_count          INT          NOT NULL,
    UNIQUE (plan_id, label)
);

CREATE INDEX idx_route_plan ON route(plan_id);

CREATE TABLE route_stop (
    id                  BIGSERIAL PRIMARY KEY,
    route_id            BIGINT       NOT NULL REFERENCES route(id) ON DELETE CASCADE,
    seq                 INT          NOT NULL,
    collection_point_id BIGINT       NOT NULL REFERENCES collection_point(id),
    planned_arrival_at  TIME         NOT NULL,
    planned_litres      NUMERIC(7,2) NOT NULL,
    -- Per-stop leg cost, so a route can be redrawn and ETAs recomputed without rebuilding
    -- the travel matrix.
    leg_minutes         INT          NOT NULL,
    leg_km              NUMERIC(6,2) NOT NULL,
    UNIQUE (route_id, seq)
);
