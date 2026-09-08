-- V5 — operations.
--
-- What actually happened: trip -> trip_stop -> collection, plus the three streams that
-- feed them (driver_event, tanker_ping, alert) and the record that closes the loop
-- (intake_record).

CREATE TABLE trip (
    id                   BIGSERIAL PRIMARY KEY,
    route_id             BIGINT       NOT NULL REFERENCES route(id),
    plan_id              BIGINT       NOT NULL REFERENCES route_plan(id),
    business_date        DATE         NOT NULL,
    session              VARCHAR(10)  NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED',
    tanker_id            BIGINT       NOT NULL REFERENCES tanker(id),
    driver_id            BIGINT       NOT NULL REFERENCES driver(id),
    destination_plant_id BIGINT       NOT NULL REFERENCES plant(id),

    ambient_temp_c       NUMERIC(4,1) NOT NULL,
    -- Copied onto the trip, not read from the tanker. Editing a tanker record mid-morning
    -- must not change a deadline that is already running. The only permitted change is the
    -- one-way downward ratchet when ambient temperature rises.
    hold_budget_minutes  INT          NOT NULL,
    -- Written once at the first collection and then frozen. The spoilage clock starts when
    -- milk first enters the tanker, not at departure: the plant -> first-village leg
    -- carries nothing. If the deadline were recalculated later, a bug in the ETA engine
    -- could silently extend it and lose a full tanker without ever raising an alert.
    first_collection_at  TIMESTAMPTZ,
    spoilage_deadline_at TIMESTAMPTZ,

    started_at           TIMESTAMPTZ,
    plant_arrival_at     TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,

    litres_on_board      NUMERIC(9,2) NOT NULL DEFAULT 0,
    capacity_litres      INT          NOT NULL,
    -- current_seq, last_lat/last_lng, eta_plant_at and risk_level are denormalised
    -- projections. All are derivable from the event and ping streams, but the ops board
    -- polls every two seconds for every active trip and must not run an aggregate per row.
    current_seq          INT          NOT NULL DEFAULT 0,
    last_lat             NUMERIC(9,6),
    last_lng             NUMERIC(9,6),
    last_ping_at         TIMESTAMPTZ,
    eta_plant_at         TIMESTAMPTZ,
    eta_confidence       VARCHAR(10)  NOT NULL DEFAULT 'UNKNOWN',
    risk_level           VARCHAR(10)  NOT NULL DEFAULT 'OK',
    -- Optimistic locking. Two dispatchers executing mitigations at once produces a clean
    -- 409, not a half-applied trip.
    version              INT          NOT NULL DEFAULT 0,

    CONSTRAINT chk_trip_status CHECK (status IN
        ('SCHEDULED','IN_PROGRESS','RETURNING','AT_PLANT',
         'COMPLETED','ABORTED','BREAKDOWN','BLOCKED')),
    CONSTRAINT chk_trip_risk CHECK (risk_level IN ('OK','WARNING','CRITICAL','LOST')),
    CONSTRAINT chk_trip_session CHECK (session IN ('MORNING','EVENING')),
    -- Makes running the trip-creation job twice harmless.
    UNIQUE (route_id, business_date, session)
);

CREATE INDEX idx_trip_active ON trip(status)
    WHERE status IN ('IN_PROGRESS','RETURNING');
CREATE INDEX idx_trip_date ON trip(business_date, session);

-- The snapshot. At trip creation every planned stop is copied here, and from then on the
-- trip never reads the plan again.
--
-- That copy is the point: if ops publishes a new plan at 05:30 while the fleet is on the
-- road, nothing changes for a driver halfway through their stop list. Without it, a
-- mid-session republish silently rewrites the route under a moving tanker.
CREATE TABLE trip_stop (
    id                  BIGSERIAL PRIMARY KEY,
    trip_id             BIGINT      NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
    -- Nullable and deliberately weak: it records provenance only. A stop added to a trip
    -- by a mitigation has no route_stop behind it.
    route_stop_id       BIGINT      REFERENCES route_stop(id),
    seq                 INT         NOT NULL,
    collection_point_id BIGINT      NOT NULL REFERENCES collection_point(id),
    planned_arrival_at  TIMESTAMPTZ NOT NULL,
    planned_litres      NUMERIC(7,2) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    eta_at              TIMESTAMPTZ,
    arrived_at          TIMESTAMPTZ,
    departed_at         TIMESTAMPTZ,
    actual_litres       NUMERIC(7,2),
    skip_reason         VARCHAR(32),
    CONSTRAINT chk_ts_status CHECK (status IN
        ('PENDING','EN_ROUTE','ARRIVED','COLLECTED','SKIPPED','DEFERRED')),
    CONSTRAINT chk_ts_skip CHECK (skip_reason IS NULL OR skip_reason IN
        ('NO_MILK','FARMER_ABSENT','ROAD_BLOCKED','TANKER_FULL',
         'SPOILAGE_ABORT','SEQUENCE_SKIP','COVERAGE_LIMIT','POINT_INACTIVE')),
    UNIQUE (trip_id, seq)
);

CREATE INDEX idx_tripstop_trip ON trip_stop(trip_id, seq);

-- One row per farmer per visit. This is where the shared-point problem resolves: one stop,
-- two farmers, two rows, one tanker visit.
CREATE TABLE collection (
    id           BIGSERIAL PRIMARY KEY,
    trip_stop_id BIGINT       NOT NULL REFERENCES trip_stop(id),
    trip_id      BIGINT       NOT NULL REFERENCES trip(id),
    farmer_id    BIGINT       NOT NULL REFERENCES farmer(id),
    -- The CHECK catches keying errors. No farmer here delivers 500 litres in one session.
    litres       NUMERIC(6,2) NOT NULL CHECK (litres >= 0 AND litres <= 500),
    collected_at TIMESTAMPTZ  NOT NULL,
    -- Voided, never deleted: this is money. A driver keying 125 where they meant 12.5 gets
    -- a void plus a correction, and both rows survive.
    voided       BOOLEAN      NOT NULL DEFAULT FALSE,
    void_reason  VARCHAR(120),
    -- Stops a double-tapped submit creating two milk records for the same farmer.
    UNIQUE (trip_stop_id, farmer_id)
);

CREATE INDEX idx_collection_farmer ON collection(farmer_id, collected_at DESC);

-- The offline story, in one table.
--
-- Drivers lose signal constantly. The phone keeps recording locally and, when signal
-- returns, sends the whole backlog — including records the server may already have,
-- because the phone cannot know which ones got through before the drop. The phone stamps
-- a UUID on each record before it is ever sent; the unique index below rejects anything
-- already present. No duplicate milk, no acknowledgement protocol, no lost data.
--
-- Both timestamps are kept. client_ts is when it happened, server_ts is when we heard
-- about it; a forty-minute gap between them is a dead zone, not an error.
CREATE TABLE driver_event (
    id              BIGSERIAL PRIMARY KEY,
    client_event_id UUID        NOT NULL UNIQUE,
    trip_id         BIGINT      NOT NULL REFERENCES trip(id),
    trip_stop_id    BIGINT      REFERENCES trip_stop(id),
    event_type      VARCHAR(32) NOT NULL,
    client_ts       TIMESTAMPTZ NOT NULL,
    server_ts       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    payload         JSONB,
    CONSTRAINT chk_event_type CHECK (event_type IN (
        'TRIP_STARTED','ARRIVED_AT_STOP','COLLECTED','DEPARTED_STOP',
        'STOP_SKIPPED','TANKER_FULL','BREAKDOWN','ARRIVED_AT_PLANT',
        'UNLOADED','TRIP_ABORTED','ROUTE_DIVERTED'))
);

-- Replay order is by client_ts, which is what this index serves.
CREATE INDEX idx_event_trip ON driver_event(trip_id, client_ts);

-- Deliberately no idempotency key here. A duplicate GPS reading is harmless — events
-- change state, pings only observe it — and adding UUIDs would double the write volume of
-- the highest-volume table in the schema for no benefit.
CREATE TABLE tanker_ping (
    id          BIGSERIAL PRIMARY KEY,
    trip_id     BIGINT       NOT NULL REFERENCES trip(id),
    lat         NUMERIC(9,6) NOT NULL,
    lng         NUMERIC(9,6) NOT NULL,
    speed_kmph  NUMERIC(5,1),
    accuracy_m  SMALLINT,
    recorded_at TIMESTAMPTZ  NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ping_trip ON tanker_ping(trip_id, recorded_at DESC);

-- Types: SPOILAGE_RISK, DEADLINE_TIGHTENED, TRACKING_LOST, TANKER_FULL,
-- SECOND_TRIP_NEEDED, BREAKDOWN, TRIP_NOT_STARTED, DATA_CONFLICT, INTAKE_MISSING,
-- SPOILAGE_EXCEEDED. trip_id is nullable: some alerts are about the plan, not a trip.
CREATE TABLE alert (
    id              BIGSERIAL PRIMARY KEY,
    trip_id         BIGINT       REFERENCES trip(id),
    alert_type      VARCHAR(32)  NOT NULL,
    severity        VARCHAR(10)  NOT NULL,
    dedupe_key      VARCHAR(120) NOT NULL,
    message         TEXT         NOT NULL,
    payload         JSONB,
    raised_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    acknowledged_at TIMESTAMPTZ,
    resolved_at     TIMESTAMPTZ,
    resolution      VARCHAR(32),
    CONSTRAINT chk_alert_severity CHECK (severity IN ('INFO','WARNING','CRITICAL'))
);

-- Prevents alert spam, which is the failure mode that makes an alert board useless. The
-- monitor sweeps every sixty seconds; without this, a trip in trouble for forty minutes
-- raises forty identical alerts and the dispatcher stops reading the board.
--
-- The key is "SPOILAGE:" + tripId + ":" + severity, so a WARNING -> CRITICAL escalation
-- correctly raises a new alert while repeated WARNINGs collapse into the open one. Scoping
-- the index to unresolved rows means the same key can legitimately recur tomorrow.
CREATE UNIQUE INDEX uq_alert_open ON alert(dedupe_key) WHERE resolved_at IS NULL;

-- Ground truth at the weighbridge, one row per trip.
--
-- oldest_milk_min is the payoff: after a few hundred sessions you can plot rejection rate
-- against milk age and find out whether 180 minutes at 30 C should really be 165 or 195.
-- That is the loop that lets the spoilage model improve instead of staying a guess.
CREATE TABLE intake_record (
    id               BIGSERIAL PRIMARY KEY,
    trip_id          BIGINT       NOT NULL UNIQUE REFERENCES trip(id),
    plant_id         BIGINT       NOT NULL REFERENCES plant(id),
    arrived_at       TIMESTAMPTZ  NOT NULL,
    unloaded_at      TIMESTAMPTZ  NOT NULL,
    received_litres  NUMERIC(9,2) NOT NULL,
    accepted_litres  NUMERIC(9,2) NOT NULL,
    rejected_litres  NUMERIC(9,2) NOT NULL,
    milk_temp_c      NUMERIC(4,1),
    oldest_milk_min  INT          NOT NULL,
    rejection_reason VARCHAR(64)
);
