-- V2 — master data.
--
-- The eight tables that describe the world before any planning happens: where the milk is
-- (village, collection_point, farmer), what moves it (tanker, driver), where it goes
-- (plant), and the two lookup tables the solver reads (temperature_profile,
-- solver_parameter).
--
-- Two shape decisions run through the whole file:
--
--   * Coordinates are NUMERIC(9,6), not DOUBLE PRECISION. Six decimals is ~11 cm, far
--     finer than any GPS fix, and NUMERIC avoids floating-point drift on equality.
--     There is no PostGIS dependency: distance is Haversine in Java, which keeps the
--     clean-machine story to "postgres:16-alpine and nothing else".
--
--   * Rows are deactivated, never deleted. A departed village or a retired collection
--     point still has historical collection rows pointing at it, and those rows are money.

CREATE TABLE village (
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(16)  NOT NULL UNIQUE,
    name       VARCHAR(120) NOT NULL,
    lat        NUMERIC(9,6) NOT NULL,
    lng        NUMERIC(9,6) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- The routing entity. A tanker stops here; it does not stop at farmers.
CREATE TABLE collection_point (
    id                 BIGSERIAL PRIMARY KEY,
    code               VARCHAR(16)  NOT NULL UNIQUE,
    village_id         BIGINT       NOT NULL REFERENCES village(id),
    lat                NUMERIC(9,6) NOT NULL,
    lng                NUMERIC(9,6) NOT NULL,
    -- Stopping costs time regardless of volume: park, open the valve, paperwork, close up.
    -- Seeded as 2.0 + 0.35 x farmerCount.
    service_minutes    NUMERIC(4,1) NOT NULL DEFAULT 2.0,
    -- Morning and evening are stored separately rather than one figure with a multiplier:
    -- the split genuinely differs by village and each is corrected independently.
    avg_morning_litres NUMERIC(7,2) NOT NULL DEFAULT 0,
    avg_evening_litres NUMERIC(7,2) NOT NULL DEFAULT 0,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Where a point retired by the consolidation advisory sends its farmers. Self-
    -- referencing, so the old point is deactivated rather than deleted and history holds.
    merged_into_id     BIGINT       REFERENCES collection_point(id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cp_village ON collection_point(village_id);
CREATE INDEX idx_cp_active  ON collection_point(active) WHERE active;

-- The attribution entity. N farmers to one collection point: one stop, one visit,
-- N milk records. Routing over farmers instead would produce two stops at identical
-- coordinates and a broken model.
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

-- More than one plant is supported because the "divert to another chilling centre"
-- mitigation needs somewhere to divert to. Which plant a route serves is not optimised.
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

-- Note what is absent: no "how long can this tanker hold milk" column. That is computed at
-- runtime from ambient temperature and `insulated`. Storing it would freeze a number that
-- changes twice a day.
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

CREATE TABLE driver (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(16)  NOT NULL UNIQUE,
    name          VARCHAR(120) NOT NULL,
    phone         VARCHAR(20)  NOT NULL,
    max_shift_min INT          NOT NULL DEFAULT 300,
    active        BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Ambient temperature by month and session: 24 rows, seeded in phase 2. This replaces a
-- weather API, which would be an external dependency the clean-machine run cannot rely on.
CREATE TABLE temperature_profile (
    id        BIGSERIAL PRIMARY KEY,
    month_no  SMALLINT     NOT NULL CHECK (month_no BETWEEN 1 AND 12),
    session   VARCHAR(10)  NOT NULL,
    ambient_c NUMERIC(4,1) NOT NULL,
    UNIQUE (month_no, session)
);

-- Every tuning number in the system lives here rather than in code (hard rule 4): safety
-- buffer, circuity factor, speeds, spoilage constants, equity exponent, thresholds. They
-- can be retuned without a redeploy, and a parameter change can be demonstrated live.
CREATE TABLE solver_parameter (
    key         VARCHAR(48)   PRIMARY KEY,
    value       NUMERIC(10,4) NOT NULL,
    description TEXT
);
