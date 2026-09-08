# TASKS

Work these **in order**, one at a time. Mark `[x]` when done and commit.

**Time budget: ~24 working hours.** Scoped accordingly. See the cut list at the bottom.

---

## Phase 0 — Foundation (~1.5 h)

- [x] **T0.1** · Write `ASSUMPTIONS.md`
- [x] **T0.2** · Generate Spring Boot project (Boot 4.0.8, Java 21, springdoc 3.0.3)
- [x] **T0.3** · `git init`, `.gitignore` (`target/ .idea/ *.iml *.log`), first commit
- [x] **T0.4** · **`ClockProvider` interface + `SystemClock`** ← do not skip or defer

      config/ClockProvider.java   — interface, one method: Instant now()
      config/SystemClock.java     — @Component @Profile("!sim")

      RULE FROM HERE ON: Instant.now() appears exactly once, inside SystemClock.

- [x] **T0.5** · `docker-compose.yml` (postgres:16-alpine + healthcheck) and `Dockerfile`
      (two-stage, `dependency:go-offline` in its own layer)
- [x] **T0.6** · `application.yml` — datasource from env with local defaults,
      `ddl-auto: validate`, `open-in-view: false`
- [x] **T0.7** · `V1__baseline.sql` (trivial table) + `GET /api/v1/health` using
      `ClockProvider`
- [x] **T0.8** · **Clean-machine check #1** — clone your own repo elsewhere,
      `docker compose up --build`, curl health
- [x] **T0.9** · Package skeleton, including all `domain/` subpackages

      RULE: nothing in domain/ imports org.springframework

**Done when:** fresh clone → `docker compose up` → `GET /api/v1/health` returns 200.

---

## Phase 1 — Schema (~2 h)

- [x] **T1.1** · `V2__master_data.sql` — village, collection_point, farmer, plant,
      tanker, driver, temperature_profile, solver_parameter. All indexes and CHECKs
      from `docs/02-schema.md`.
- [x] **T1.2** · `V3__planning.sql` — route_plan, route, route_stop.
      **Include `uq_one_published_per_session`.**
- [x] **T1.3** · `V4__coverage.sql` — point_coverage_state, plan_exclusion
- [x] **T1.4** · `V5__operations.sql` — trip, trip_stop, collection, driver_event,
      tanker_ping, alert, intake_record.
      **Include `client_event_id UUID UNIQUE` and `uq_alert_open`.**
- [x] **T1.5** · ~~`V6__advisory.sql` — merge_proposal, merge_proposal_point~~
      **Withdrawn.** Built, then removed with the consolidation advisory: nothing
      in scope writes those two tables. The schema is V1-V5.
- [x] **T1.6** · JPA entities, one per table. `@Version` on Trip.
- [x] **T1.7** · Enums — Session, TripStatus, TripStopStatus, EventType, RiskLevel,
      SkipReason, PlanStatus, PlanMode, EtaConfidence, AlertType, AlertSeverity,
      ExclusionReason
- [x] **T1.8** · Repositories — query methods for available tankers, active points by
      village, published plan by session, active trips

**Done when:** `docker compose down -v && docker compose up` runs all migrations cleanly
and Hibernate validation passes with no schema mismatch.

---

## Phase 2 — Seed data (~3 h)

- [x] **T2.1** · `DatasetConfig` + `Range` records
- [x] **T2.2** · `DatasetLoader` — reads `classpath:datasets/{name}.yaml` via SnakeYAML
- [x] **T2.3** · `GeoPoint` record + Haversine + `project(from, bearing, km)`, with tests
      against known distances
- [x] **T2.4** · Seed villages — corridor placement, uneven bearings, growing gaps,
      fixed `Random(seed)`
- [x] **T2.5** · Seed collection points — scatter within village radius
- [x] **T2.6** · Seed farmers — `twoFarmerPointRatio` chance of 2; compute
      `service_minutes = 2.0 + 0.35 × count` and morning/evening litres
- [x] **T2.7** · Seed fleet, drivers, plant — round-robin `capacityMix`, first N insulated
- [x] **T2.8** · Seed temperature profiles (24 rows) + the 19 solver parameters
- [x] **T2.9** · `SeedRunner` — `ApplicationRunner`, skips if data exists
- [x] **T2.10** · Reseed endpoint — `POST /admin/reseed?dataset=X`.
      **Load config before truncating. Clear the matrix cache. Guard to dev/sim.**
- [x] **T2.11** · Write `baseline.yaml`
- [x] **T2.12** · `GET /admin/dataset-check` — counts, required vs available hot minutes
      and ratio, volume vs capacity, unreachable count, farthest village km

**Done when:** 60 villages, ~1,250 points, ~1,400 farmers; dataset-check shows time
ratio < 0.8.

---

## Phase 3 — The two calculations (~2 h)

- [x] **T3.1** · `TravelTimeProvider` interface (plain Java, `domain/geo/`)
- [x] **T3.2** · `HaversineTravelTime` — circuity 1.35, speeds 15/26/34, morning ×1.15 /
      evening ×0.90, all params injected from `solver_parameter`
- [x] **T3.3** · Travel time tests — the worked example: 10.4 km straight → 14.0 km road
      → 21.5 min morning, 27.4 min evening
- [x] **T3.4** · `TravelMatrix` + cache keyed by SHA-256 of the sorted point set
- [ ] **T3.5** · `SpoilageCalculator` — Q10 formula with min/max clamps
- [ ] **T3.6** · **`SpoilageCalculator` tests** ← test hardest

      18→414, 22→313, 27→222, 30→180, 35→127, 39→96
      (35, insulated) → 193; (22, insulated) → 475, just under the ceiling
      (10, false) → 480 ceiling; (55, false) → 60 floor

- [ ] **T3.7** · `AmbientTemperatureProvider` interface + impl reading
      `temperature_profile`

**Done when:** both calculators pass their full test suites. Everything downstream
depends on these being right.

---

## Phase 4 — The planner (~5 h) ← this will overrun; protect it

- [ ] **T4.1** · `PlanningContext` record
- [ ] **T4.2** · Four `RouteConstraint` implementations — Spoilage, Capacity,
      ShiftLength, PlantWindow
- [ ] **T4.3** · **`ConstraintChecker`** ← the class they will read most closely.
      Hot time **excludes** the plant → first-village leg.
- [ ] **T4.4** · `ConstraintChecker` tests — one per constraint, plus boundary:
      exactly at `budget − buffer` passes, one minute over fails
- [ ] **T4.5** · `VillageBlock` record
- [x] **T4.6** · `VillageSolver` — nearest-neighbour + 2-opt; split oversized blocks
- [x] **T4.7** · `FeasibilityAssessor` — required vs available → mode selection
- [x] **T4.8** · `PlanningStrategy` interface
- [x] **T4.9** · `RoutePlanner` (full-service) — savings over blocks, greedy merge
- [ ] **T4.10** · `SequenceOptimiser` — farthest-first seed + 2-opt on **hot time**
      (Or-opt is optional, cut if short)
- [x] **T4.11** · `TankerAssigner` — riskiest route gets the largest hold budget
- [x] **T4.12** · `PlanResult` + `FeasibilityReport`

**Done when:** `POST /plans` at 22 °C serves all points in under 5 s using fewer than 22
tankers.

---

## Phase 5 — Coverage mode (~2 h)

- [ ] **T5.1** · `PointScorer` — efficiency × equity × urgency
- [ ] **T5.2** · `PointScorer` tests — a 3-day-skipped point outscores a marginally more
      efficient fresh one
- [ ] **T5.3** · `CoveragePlanner` — mandatory-first seeding, greedy village insertion,
      partial fill
- [ ] **T5.4** · `CoverageStateService` — update skip counters, enforce the three-strike
      rule
- [ ] **T5.5** · Coverage report + `GET /plans/{id}/exclusions`

**Done when:** 35 °C switches to `COVERAGE_OPTIMISATION` automatically, ~67% coverage,
exclusions carry reasons.

---

## Phase 6 — Plans and the timing advisory (~1.5 h)

- [ ] **T6.1** · `PlanningService` — build context, pick strategy, persist plan + routes
      + stops
- [ ] **T6.2** · Publish endpoint — verify `uq_one_published_per_session` rejects a
      second publish
- [ ] **T6.3** · `PlanController` — POST /plans, GET /plans/{id}, GET feasibility,
      POST publish, GET published
- [ ] **T6.4** · **`TimingAdvisory`** ← 20 minutes, best line in the demo
- [ ] **T6.5** · `AdvisoryController`

**Done when:** timing advisory on `heat-crisis` shows 67% → 91% at zero cost.

---

## ═══ SLEEP HERE — 8 hours, non-negotiable ═══

The quality drop from working through is visible in a diff and it is the first thing a
reviewer notices.

---

## Phase 7 — Trips and events (~3 h)

- [ ] **T7.1** · Trip creation — read published plan, **snapshot route_stops into
      trip_stops**, check tanker/driver availability, substitute or BLOCK
- [ ] **T7.2** · `TripStateMachine` — allowed-transitions map
- [ ] **T7.3** · `TripStateMachine` tests — every valid transition, sample invalid ones
- [ ] **T7.4** · Event DTOs — batch request with `clientEventId`, type, `clientTs`,
      payload
- [ ] **T7.5** · **`EventIngestionService`** ← the idempotency piece.
      `@Transactional`, sort by `client_ts`, catch `DataIntegrityViolationException`
- [ ] **T7.6** · `EventReplayer` — apply each event type; synthesise a missing
      `ARRIVED_AT_STOP` before a `COLLECTED`
- [ ] **T7.7** · **Idempotency test** — 30 events, resend 5, assert 25 applied /
      5 duplicates / no double collections
- [ ] **T7.8** · Collection recording — one row per farmer, validated 0–500
- [ ] **T7.9** · `DriverController` — today's trip, events, pings, own clock

**Done when:** you can curl a sequence of events and watch a trip progress.

---

## Phase 8 — Tracking and monitoring (~2 h)

- [ ] **T8.1** · `PingService` — batch ingest, update trip's last position
- [ ] **T8.2** · `PositionResolver` — events drive progress, pings refine
- [ ] **T8.3** · `EtaCalculator` — with the clamped delay factor
- [ ] **T8.4** · `EtaCalculator` tests — happy path, with delay, with skipped stops
- [ ] **T8.5** · Confidence rules — HIGH / MEDIUM / LOW / LOST
- [ ] **T8.6** · `FarmerQueryService` — all nine statuses with message templates,
      `guaranteedBy` from the three-strike rule
- [ ] **T8.7** · `FarmerController` — tanker-status, collections
- [ ] **T8.8** · `SpoilageMonitorService` — `@Scheduled` 60 s, 80%/95% thresholds,
      **one-way ratchet**, tracking-lost keeps counting
- [ ] **T8.9** · `AlertService` — raise with `dedupe_key`, update if already open
- [ ] **T8.10** · `Mitigation` sealed interface + SkipRemaining, DivertToPlant,
      ContinueAsPlanned
- [ ] **T8.11** · `MitigationService` — generate, rank by litres saved, execute with
      optimistic locking. **Never auto-execute.**
- [ ] **T8.12** · `OpsController` + `GET /ops/board` facade

**Done when:** a delayed trip escalates WARNING → CRITICAL and offers ranked mitigations
with real numbers.

---

## Phase 9 — Minimal simulation (~2 h)

Deliberately reduced. Skip driver personalities, the farmer caller, the separate plant
operator class, and the random connectivity model.

- [ ] **T9.1** · `VirtualClock` — `@Profile("sim")`, advance and jumpTo
- [ ] **T9.2** · `VirtualDriver` — walks its route, POSTs events and pings through the
      **real HTTP API**. Never writes to the database directly.
- [ ] **T9.3** · Offline buffering — one hardcoded signal-loss window. On reconnect send
      the backlog **plus 3 already-acked events** to prove dedupe. Log the counts.
- [ ] **T9.4** · `SimulationEngine` — advance clock in 30 s steps, tick drivers, sweep
      the monitor, recompute ETAs
- [ ] **T9.5** · `SimulationController` — run, pause, jumpTo, status
- [ ] **T9.6** · Two scenarios — `happy-morning` and `spoilage-crisis`
- [ ] **T9.7** · Write `heat-crisis.yaml` (**same seed as baseline**) and
      `sparse-district.yaml`
- [ ] **T9.8** · **Tune the datasets** — run dataset-check on each. `sparse-district`
      must produce ≥ 5 unreachable villages. Write expected numbers as a comment at the
      top of each YAML.

**Done when:** `POST /sim/run` completes a session and `spoilage-crisis` produces exactly
one CRITICAL alert with zero rejected litres.

---

## Phase 10 — Ship it (~2 h)

- [ ] **T10.1** · `demo.sh` — the three-dataset sequence with curl and commentary
- [ ] **T10.2** · **Magic number sweep** — grep for hardcoded 22, 60, 1400, 1250, 180,
      1.35. Everything from config or `solver_parameter`.
- [ ] **T10.3** · **Clean-machine check #2** — `docker system prune -a`, fresh clone,
      full run, `./demo.sh`
- [ ] **T10.4** · `README.md`

      Order: what it is · how to run · THE FEASIBILITY FINDING (lead with it) ·
      assumptions · domain model · spoilage model · algorithm with the farthest-first
      worked example · coverage and why litre-maximisation is wrong · user permission
      matrix · edge cases · WHAT I DID NOT BUILD AND WHY · known limitations ·
      next two weeks

- [ ] **T10.5** · Final commit and push

---

## Cut list — in this order if running behind

1. Or-opt in `SequenceOptimiser` (T4.10) — the farthest-first seed does most of the work
2. `sparse-district` dataset (T9.7) — keep baseline and heat-crisis
3. `spoilage-crisis` scenario (T9.6) — keep happy-morning
4. `DivertToPlant` mitigation — keep SkipRemaining and ContinueAsPlanned
5. The whole of Phase 9 — demo with curl and logs instead

## Never cut

- `ConstraintChecker` and its tests (T4.3, T4.4)
- `SpoilageCalculator` and its tests (T3.5, T3.6)
- Farmer status endpoint (T8.6, T8.7)
- Event idempotency and its test (T7.5, T7.7)
- The equity term and three-strike rule (T5.1, T5.4)
- Clean-machine verification (T0.8, T10.3)
- `README.md` (T10.4)

---

## The five things that must exist at the end

1. `docker compose up` works from a fresh clone
2. `POST /plans` produces routes at 22 °C and switches to coverage mode at 35 °C
3. `GET /farmers/{code}/tanker-status` returns a plain-English sentence
4. Event idempotency demonstrably works
5. README explains the evening-is-impossible finding

Everything else is bonus.

---

## Working rules

- Commit every 45 minutes with a descriptive message. Git history gets read.
- Read every file before committing it. If a method does not make sense to you, ask for
  a simpler version or write it yourself.
- Modify something yourself in each of: `ConstraintChecker`, `SpoilageCalculator`,
  `CoveragePlanner`, `EventIngestionService`, `EtaCalculator`. Nothing cements
  understanding like editing code.
- Keep a scratch file of README sentences as you make decisions. At hour 22 you will be
  too tired to reconstruct your reasoning.
- If a task overruns by more than 50%, cut something from the list above. Do not cut
  sleep and do not cut the README.
