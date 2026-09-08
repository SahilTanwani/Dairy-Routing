# 02 — Database Schema

Twenty-two tables in five groups. Every table traces to a sentence in the brief or a
decision in `ASSUMPTIONS.md`.

## How it all connects

```
village ──< collection_point ──< farmer
                  │                  │
                  └──< route_stop    │
                          │          │
                       route         │
                          │          │
                     route_plan      │
                          │          │
                          ▼          │
                        trip ──< trip_stop ──< collection >──┘
                          │
                          ├──< driver_event
                          ├──< tanker_ping
                          ├──< alert
                          └──1 intake_record
```

Read it as: **plans flow down the left, reality flows down the right, and they meet at
`trip`.** `route_plan → route → route_stop` is what we intend.
`trip → trip_stop → collection` is what happened. The trip copies from the route at
creation and then goes its own way.

---

# Migration V2 — Master data

```sql
CREATE TABLE village (
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(16)  NOT NULL UNIQUE,
    name       VARCHAR(120) NOT NULL,
    lat        NUMERIC(9,6) NOT NULL,
    lng        NUMERIC(9,6) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
```

- **`NUMERIC(9,6)` not `DOUBLE`** — six decimals is ~11 cm, far beyond GPS accuracy, and
  `NUMERIC` avoids floating-point drift on equality comparisons.
- **No PostGIS** — an extra extension to install, and we do distance in Java anyway.
  Keeping it out protects the clean-machine requirement.
- **`active` not `DELETE`** — a departed village still has historical collection rows
  pointing at it.

```sql
CREATE TABLE collection_point (
    id                 BIGSERIAL PRIMARY KEY,
    code               VARCHAR(16)  NOT NULL UNIQUE,
    village_id         BIGINT       NOT NULL REFERENCES village(id),
    lat                NUMERIC(9,6) NOT NULL,
    lng                NUMERIC(9,6) NOT NULL,
    service_minutes    NUMERIC(4,1) NOT NULL DEFAULT 2.0,
    avg_morning_litres NUMERIC(7,2) NOT NULL DEFAULT 0,
    avg_evening_litres NUMERIC(7,2) NOT NULL DEFAULT 0,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    merged_into_id     BIGINT       REFERENCES collection_point(id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_cp_village ON collection_point(village_id);
CREATE INDEX idx_cp_active  ON collection_point(active) WHERE active;
```

~1,250 rows, roughly 21 per village. This is where tankers actually stop.

- **`service_minutes` per point** — stopping costs time regardless of volume: park, open
  the valve, paperwork, close up. Computed as `2.0 + 0.35 × farmerCount`.
- **Morning and evening litres stored separately**, not one figure with a multiplier,
  because the split genuinely differs by village and each is corrected independently by
  the variance loop.
- **`merged_into_id`** — the consolidation advisory needs somewhere for a retired point
  to point at. The old point is deactivated, not deleted, and its farmers repoint to the
  hub. Historical rows stay intact.

```sql
CREATE TABLE farmer (
    id                  BIGSERIAL PRIMARY KEY,
    code                VARCHAR(16)  NOT NULL UNIQUE,
    name                VARCHAR(120) NOT NULL,
    phone               VARCHAR(20),
    collection_point_id BIGINT       NOT NULL REFERENCES collection_point(id),
    animal_count        SMALLINT     NOT NULL DEFAULT 2,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    joined_on           DATE         NOT NULL DEFAULT CURRENT_DATE
);
CREATE INDEX idx_farmer_point ON farmer(collection_point_id) WHERE active;
CREATE INDEX idx_farmer_code  ON farmer(code);
```

**This table is the answer to "some collection points serve two farmers."**

Many farmers point at one collection point. The tanker routes over **points** and visits
each once. The milk is recorded against **farmers**, one row each. So a point with two
farmers gets one stop and two milk records.

Routing over farmers instead would give two stops at identical coordinates and a broken
model.

```sql
CREATE TABLE plant (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(16)  NOT NULL UNIQUE,
    name           VARCHAR(120) NOT NULL,
    lat            NUMERIC(9,6) NOT NULL,
    lng            NUMERIC(9,6) NOT NULL,
    unload_minutes INT          NOT NULL DEFAULT 20,
    opens_at       TIME         NOT NULL DEFAULT '04:00',
    closes_at      TIME         NOT NULL DEFAULT '22:00',
    is_primary     BOOLEAN      NOT NULL DEFAULT FALSE,
    active         BOOLEAN      NOT NULL DEFAULT TRUE
);
```

Multiple plants are supported because the "divert to another chilling centre" mitigation
needs a destination. Choosing which plant each route serves is **not** optimised — say
so in the README.

```sql
CREATE TABLE tanker (
    id              BIGSERIAL PRIMARY KEY,
    reg_no          VARCHAR(20) NOT NULL UNIQUE,
    capacity_litres INT         NOT NULL CHECK (capacity_litres > 0),
    insulated       BOOLEAN     NOT NULL DEFAULT FALSE,
    status          VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    home_plant_id   BIGINT      NOT NULL REFERENCES plant(id),
    CONSTRAINT chk_tanker_status CHECK (status IN
        ('AVAILABLE','ON_TRIP','MAINTENANCE','BREAKDOWN','RETIRED'))
);
CREATE INDEX idx_tanker_available ON tanker(status) WHERE status = 'AVAILABLE';
```

**Note what is missing: there is no "how long can this tanker hold milk" column.** That
is computed at runtime from ambient temperature and `insulated`. Storing it would freeze
a number that changes twice a day.

```sql
CREATE TABLE driver (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(16)  NOT NULL UNIQUE,
    name          VARCHAR(120) NOT NULL,
    phone         VARCHAR(20)  NOT NULL,
    max_shift_min INT          NOT NULL DEFAULT 300,
    active        BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE temperature_profile (
    id        BIGSERIAL PRIMARY KEY,
    month_no  SMALLINT     NOT NULL CHECK (month_no BETWEEN 1 AND 12),
    session   VARCHAR(10)  NOT NULL,
    ambient_c NUMERIC(4,1) NOT NULL,
    UNIQUE (month_no, session)
);

CREATE TABLE solver_parameter (
    key         VARCHAR(48)   PRIMARY KEY,
    value       NUMERIC(10,4) NOT NULL,
    description TEXT
);
```

Temperature by month and session, 24 rows. Replaces a weather API.

Solver parameters go **in the database, not in code**, so they can be tuned without a
redeploy and a parameter change can be demonstrated live:

| Key | Value | Purpose |
|---|---|---|
| `baseHoldMinutesAt30C` | 180 | How long milk lasts at 30 °C |
| `q10Factor` | 2.0 | Growth doubles per 10 °C |
| `insulationOffsetC` | 6.0 | Insulated tanker acts 6 °C cooler |
| `minHoldMinutes` | 60 | Floor, guards sensor errors |
| `maxHoldMinutes` | 480 | Ceiling |
| `safetyBufferMin` | 20 | Margin against wrong travel estimates |
| `circuityFactor` | 1.35 | Roads wander |
| `speedUnder2Km` | 15 | Village lanes |
| `speed2To10Km` | 26 | Connecting roads |
| `speedOver10Km` | 34 | District roads |
| `morningSpeedFactor` | 1.15 | Empty roads |
| `eveningSpeedFactor` | 0.90 | Traffic |
| `equityExponent` | 1.6 | How fast neglect raises priority |
| `maxConsecutiveSkips` | 3 | Hard fairness rule |
| `maxWalkMetres` | 500 | How far a farmer will carry cans |
| `feasibilityMargin` | 0.92 | When to switch to coverage mode |
| `capacityHeadroom` | 0.95 | Volume estimates are ±15% |
| `spoilageWarnPct` | 0.80 | WARNING threshold |
| `spoilageCriticalPct` | 0.95 | CRITICAL threshold |
| `trackingLostMinutes` | 15 | No ping for this long → LOST |

---

# Migration V3 — Planning

```sql
CREATE TABLE route_plan (
    id             BIGSERIAL PRIMARY KEY,
    version        INT          NOT NULL,
    session        VARCHAR(10)  NOT NULL,
    source         VARCHAR(16)  NOT NULL,
    mode           VARCHAR(24)  NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    planned_temp_c NUMERIC(4,1) NOT NULL,
    effective_from DATE         NOT NULL,
    generated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    generation_ms  INT,
    feasibility    JSONB,
    notes          TEXT,
    CONSTRAINT chk_plan_session CHECK (session IN ('MORNING','EVENING')),
    CONSTRAINT chk_plan_source  CHECK (source  IN ('GENERATED','LEGACY','MANUAL')),
    CONSTRAINT chk_plan_mode    CHECK (mode    IN ('FULL_SERVICE','COVERAGE_OPTIMISATION')),
    CONSTRAINT chk_plan_status  CHECK (status  IN ('DRAFT','PUBLISHED','ARCHIVED')),
    UNIQUE (session, version)
);

CREATE UNIQUE INDEX uq_one_published_per_session
    ON route_plan(session) WHERE status = 'PUBLISHED';
```

**That partial unique index is the most important constraint in the schema.** At most one
published morning plan, at most one published evening plan.

If two morning plans were live, trip creation would pick one arbitrarily and half the
fleet would run the wrong routes. Enforcing it in Postgres means **no bug in service code
can ever produce it** — not a race, not a double-click.

- **`mode`** records whether this plan could serve everyone or had to choose.
- **`feasibility` as JSONB** because the report's shape will evolve and it is read as a
  whole blob, never queried by field.

```sql
CREATE TABLE route (
    id                  BIGSERIAL PRIMARY KEY,
    plan_id             BIGINT       NOT NULL REFERENCES route_plan(id) ON DELETE CASCADE,
    label               VARCHAR(16)  NOT NULL,
    tanker_id           BIGINT       REFERENCES tanker(id),
    driver_id           BIGINT       REFERENCES driver(id),
    plant_id            BIGINT       NOT NULL REFERENCES plant(id),
    planned_depart_at   TIME         NOT NULL,
    est_hot_minutes     INT          NOT NULL,
    hold_budget_minutes INT          NOT NULL,
    slack_minutes       INT          NOT NULL,
    est_volume_litres   NUMERIC(9,2) NOT NULL,
    est_distance_km     NUMERIC(7,2) NOT NULL,
    stop_count          INT          NOT NULL,
    UNIQUE (plan_id, label)
);
CREATE INDEX idx_route_plan ON route(plan_id);
```

`est_hot_minutes` is how long the oldest milk will have aged when the tanker reaches the
plant. `slack_minutes` is budget minus that.

Slack is stored rather than computed because the ops board sorts routes by risk. A route
with 6 minutes of slack fails on any bad day, and the feasibility report flags it amber
before publish.

```sql
CREATE TABLE route_stop (
    id                  BIGSERIAL PRIMARY KEY,
    route_id            BIGINT       NOT NULL REFERENCES route(id) ON DELETE CASCADE,
    seq                 INT          NOT NULL,
    collection_point_id BIGINT       NOT NULL REFERENCES collection_point(id),
    planned_arrival_at  TIME         NOT NULL,
    planned_litres      NUMERIC(7,2) NOT NULL,
    leg_minutes         INT          NOT NULL,
    leg_km              NUMERIC(6,2) NOT NULL,
    UNIQUE (route_id, seq)
);
```

Storing `leg_minutes` and `leg_km` per stop means the route can be redrawn and ETAs
recomputed without touching the travel matrix again.

---

# Migration V4 — Coverage

```sql
CREATE TABLE point_coverage_state (
    collection_point_id BIGINT PRIMARY KEY REFERENCES collection_point(id),
    last_served_date    DATE,
    last_served_session VARCHAR(10),
    consecutive_skips   INT NOT NULL DEFAULT 0,
    skips_last_7_days   INT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

**This table is what stops the algorithm destroying the cooperative.**
`consecutive_skips` feeds the equity term in the scoring function and the hard
three-strike rule.

```sql
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
```

Records who was left out of each plan and why, with the score.

**Why bother:** when a farmer calls asking why the tanker did not come, ops has a real
answer instead of a shrug. Same class of problem as "where is my tanker," one layer up.

Reasons: `UNREACHABLE_WITHIN_HOLD`, `COVERAGE_LIMIT`, `EXCEEDS_ALL_CAPACITY`,
`POINT_INACTIVE`.

---

# Migration V5 — Operations

```sql
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
    hold_budget_minutes  INT          NOT NULL,
    first_collection_at  TIMESTAMPTZ,
    spoilage_deadline_at TIMESTAMPTZ,

    started_at           TIMESTAMPTZ,
    plant_arrival_at     TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,

    litres_on_board      NUMERIC(9,2) NOT NULL DEFAULT 0,
    capacity_litres      INT          NOT NULL,
    current_seq          INT          NOT NULL DEFAULT 0,
    last_lat             NUMERIC(9,6),
    last_lng             NUMERIC(9,6),
    last_ping_at         TIMESTAMPTZ,
    eta_plant_at         TIMESTAMPTZ,
    eta_confidence       VARCHAR(10)  NOT NULL DEFAULT 'UNKNOWN',
    risk_level           VARCHAR(10)  NOT NULL DEFAULT 'OK',
    version              INT          NOT NULL DEFAULT 0,

    CONSTRAINT chk_trip_status CHECK (status IN
        ('SCHEDULED','IN_PROGRESS','RETURNING','AT_PLANT',
         'COMPLETED','ABORTED','BREAKDOWN','BLOCKED')),
    CONSTRAINT chk_trip_risk CHECK (risk_level IN ('OK','WARNING','CRITICAL','LOST')),
    CONSTRAINT chk_trip_session CHECK (session IN ('MORNING','EVENING')),
    UNIQUE (route_id, business_date, session)
);
CREATE INDEX idx_trip_active ON trip(status)
    WHERE status IN ('IN_PROGRESS','RETURNING');
CREATE INDEX idx_trip_date ON trip(business_date, session);
```

- **`first_collection_at` and `spoilage_deadline_at` are written once and frozen.** If
  the deadline were recalculated later, a bug in the ETA engine could silently extend it
  and lose a tanker of milk without warning.
- **`hold_budget_minutes` is copied onto the trip**, not read from the tanker. Editing a
  tanker record mid-morning must not change a running deadline. The only permitted
  change is the one-way downward ratchet.
- **`current_seq`, `last_lat`, `eta_plant_at`, `risk_level` are denormalised
  projections.** They are derivable, but the ops board polls every 2 s for 22 trips and
  must not run 22 aggregates.
- **`version` gives optimistic locking** — two dispatchers acting at once produces a
  clean 409, not a corrupt trip.
- **`UNIQUE (route_id, business_date, session)`** makes running the creation job twice
  harmless.

```sql
CREATE TABLE trip_stop (
    id                  BIGSERIAL PRIMARY KEY,
    trip_id             BIGINT      NOT NULL REFERENCES trip(id) ON DELETE CASCADE,
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
```

**The copying is the point.** At trip creation, every planned stop is copied here. From
then on the trip never looks at the plan again.

If ops publishes a new plan at 05:30 while 22 tankers are on the road, nothing changes
for them. Without the copy, a mid-session republish silently rewrites the stop list under
a driver halfway through it.

```sql
CREATE TABLE collection (
    id           BIGSERIAL PRIMARY KEY,
    trip_stop_id BIGINT       NOT NULL REFERENCES trip_stop(id),
    trip_id      BIGINT       NOT NULL REFERENCES trip(id),
    farmer_id    BIGINT       NOT NULL REFERENCES farmer(id),
    litres       NUMERIC(6,2) NOT NULL CHECK (litres >= 0 AND litres <= 500),
    collected_at TIMESTAMPTZ  NOT NULL,
    voided       BOOLEAN      NOT NULL DEFAULT FALSE,
    void_reason  VARCHAR(120),
    UNIQUE (trip_stop_id, farmer_id)
);
CREATE INDEX idx_collection_farmer ON collection(farmer_id, collected_at DESC);
```

**One row per farmer per visit.** This is where the shared-point problem resolves: one
stop, two rows.

- **`voided` instead of `DELETE`** — this is money. A driver keying 125 L instead of 12.5
  gets a void plus a correction, both rows kept. Never destroy a financial record.
- **`UNIQUE (trip_stop_id, farmer_id)`** stops a double-tap creating two records.
- **The CHECK on litres** catches typos. No farmer produces 500 litres.

```sql
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
CREATE INDEX idx_event_trip ON driver_event(trip_id, client_ts);
```

**`client_event_id UUID UNIQUE` is the best single line of engineering in this project.**

Drivers lose signal constantly. The phone keeps recording and stores everything locally.
When signal returns it sends the whole backlog — possibly including records the server
already has, because it cannot know which went through before the drop.

The phone generates a UUID per record before sending. The unique index rejects anything
already present. No duplicate milk records, no protocol, no lost data.

**Both timestamps stored.** `client_ts` is when it happened; `server_ts` is when we heard
about it. A 40-minute gap is a dead zone, not an error.

```sql
CREATE TABLE tanker_ping (
    id          BIGSERIAL PRIMARY KEY,
    trip_id     BIGINT       NOT NULL REFERENCES trip(id),
    lat         NUMERIC(9,6) NOT NULL,
    lng         NUMERIC(9,6) NOT NULL,
    accuracy_m  SMALLINT,
    recorded_at TIMESTAMPTZ  NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ping_trip ON tanker_ping(trip_id, recorded_at DESC);
```

**No idempotency here, deliberately.** A duplicate GPS reading is harmless. Events change
state; pings are observations. Adding UUIDs would double write volume for nothing.

Volume: 22 trips × 2 pings/min × 180 min ≈ 8,000 rows per session. Trivial. Monthly
partitioning is the 100× answer.

```sql
CREATE TABLE alert (
    id              BIGSERIAL PRIMARY KEY,
    trip_id         BIGINT       REFERENCES trip(id),
    alert_type      VARCHAR(32)  NOT NULL,
    severity        VARCHAR(10)  NOT NULL,
    dedupe_key      VARCHAR(120) NOT NULL,
    message         TEXT         NOT NULL,
    payload         JSONB,
    raised_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ,
    CONSTRAINT chk_alert_severity CHECK (severity IN ('INFO','WARNING','CRITICAL'))
);
CREATE UNIQUE INDEX uq_alert_open ON alert(dedupe_key) WHERE resolved_at IS NULL;
```

**That index prevents alert spam.** The monitor sweeps every 60 s. Without dedupe, a trip
in trouble for 40 minutes raises 40 identical alerts and the dispatcher stops looking at
the board.

Key format: `"SPOILAGE:" + tripId + ":" + severity`, so a WARNING → CRITICAL escalation
correctly creates a new alert while repeated WARNINGs do not.

Types: `SPOILAGE_RISK`, `DEADLINE_TIGHTENED`, `TRACKING_LOST`, `TANKER_FULL`,
`SECOND_TRIP_NEEDED`, `BREAKDOWN`, `TRIP_NOT_STARTED`, `DATA_CONFLICT`,
`INTAKE_MISSING`, `SPOILAGE_EXCEEDED`.

```sql
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
```

**`oldest_milk_min` at intake is the ground truth.** After a few hundred sessions you can
plot rejection rate against milk age and discover whether 180 min at 30 °C should really
be 165 or 195. That is the loop that makes the model improve rather than stay a guess.

---

# Migration V6 — Advisory

```sql
CREATE TABLE merge_proposal (
    id               BIGSERIAL PRIMARY KEY,
    village_id       BIGINT NOT NULL REFERENCES village(id),
    hub_point_id     BIGINT NOT NULL REFERENCES collection_point(id),
    minutes_saved    NUMERIC(6,2) NOT NULL,
    farmers_affected INT NOT NULL,
    max_walk_metres  INT NOT NULL,
    litres_affected  NUMERIC(7,2) NOT NULL,
    confidence       VARCHAR(10) NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'PROPOSED',
    generated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_mp_status CHECK (status IN
        ('PROPOSED','ACCEPTED','REJECTED','IMPLEMENTED'))
);

CREATE TABLE merge_proposal_point (
    proposal_id         BIGINT NOT NULL REFERENCES merge_proposal(id) ON DELETE CASCADE,
    collection_point_id BIGINT NOT NULL REFERENCES collection_point(id),
    walk_metres         INT    NOT NULL,
    PRIMARY KEY (proposal_id, collection_point_id)
);
```

---

# The five constraints worth naming out loud

If asked "what would you point to in this schema," these five:

1. `uq_one_published_per_session` — makes two live plans structurally impossible
2. `client_event_id UUID UNIQUE` — the entire offline story in one index
3. `trip_stop` snapshotting `route_stop` — mid-session republish cannot corrupt a trip
4. `UNIQUE (trip_stop_id, farmer_id)` on `collection` — one stop, N farmers, no doubles
5. `uq_alert_open` — prevents the dispatcher tuning out an alert board that spams
