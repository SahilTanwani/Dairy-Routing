# MilkRoute

Backend for a dairy that collects milk twice a day from about 1,400 farmers across 60
villages using 22 tankers. Milk spoils on a clock set by the weather, so every route has to
reach the chilling plant before its oldest litre goes bad. The system plans the routes,
tracks the tankers, tells farmers when to expect one, and — when the fleet cannot cover the
dairy — says so out loud, names which constraint bound, and offers the cheapest lever.

Java 21 · Spring Boot 4.0.8 · PostgreSQL 16 · Flyway · Docker Compose. Nine dependencies.

---

## The finding, first

The most useful thing this system produces is not a route list. It is the discovery that
**neither session actually fits**, that the two sessions fail for different reasons, and
that both fixes are operational rather than capital.

A tanker is bounded by two things at once: the milk spoils, and the driver goes home. It can
therefore contribute `min(holdBudget, driverShift)` minutes of work with milk on board, and
which of the two binds depends entirely on the weather.

| Session | Ambient | Hold budget (plain / insulated) | Driver shift | Usable per tanker | Fleet total | Required | Ratio |
|---|---|---|---|---|---|---|---|
| Morning | 22 °C | 313 / 475 min | 300 min | **300 / 300** | 6,600 min | 7,021 min | **1.06** |
| Evening | 35 °C | 127 / 193 min | 300 min | **127 / 193** | 3,190 min | 8,133 min | **2.55** |

**In the morning the roster binds. In the evening spoilage binds.** At 22 °C the milk would
last five hours and the driver goes home after five; at 35 °C the milk is finished in two
and the shift never gets a chance to matter.

What the planner actually returns on this dairy:

| Session | Routes | Points served | Coverage | Thinnest slack |
|---|---|---|---|---|
| Morning, 22 °C, 300-minute shift | 22 | **868 of 1,250** | 69% | +93 min |
| Morning, 22 °C, 600-minute shift | 22 | **1,216 of 1,250** | 97% (`FULL_SERVICE`) | +31 min |
| Evening, 35 °C, depart 16:30 | 14 | **241 of 1,250** | 19% | +22 min |
| Evening, 29 °C, depart 18:30 | — | — | **37%** | — |

**The morning lever is the roster.** Doubling the shift from 300 to 600 minutes makes each
tanker bounded by its hold budget again, drops the ratio from 1.06 to 0.89, and turns 868
served points into 1,216. Same tankers, same villages, same weather — one rostering
conversation is worth 348 collection points a morning. Every other fix for the morning (more
tankers, more insulation, a second chilling centre) costs money; this one does not.

**The evening lever is the clock.** At a ratio of 2.55 no rostering change touches it —
serving every point on a 35 °C evening would take about 61 tankers against a fleet of 22.
But ambient falls roughly 3 °C an hour after the afternoon peak, so departing at 18:30
instead of 16:30 plans against 29 °C instead of 35 °C, lifts the plain-tanker budget from
127 to 193 minutes, and takes coverage from 19% to 37%:

```
GET /api/v1/advisory/session-timing?session=EVENING&ambientTempC=35&shiftHours=2

"Depart 18:30 instead of 16:30: 29.0 C instead of 35.0 C, 193 minutes of hold budget
 instead of 127, and coverage rises from 19% to 37%. About 802,686 litres a year.
 Cost: None. Farmers milk 2 hours later."
```

The advisory plans the dairy twice and reports both sides — temperature, budget and coverage
at each departure — so the claim can be checked rather than believed.

Neither number is a projection. Both come from the running system on the seeded datasets, and
`./demo.sh` reproduces them end to end in about a minute.

---

## How to run

```bash
docker compose up --build            # postgres:16-alpine + the app, migrations run on boot
curl localhost:8080/api/v1/health    # {"status":"UP", ...}
./demo.sh                            # the guided tour, four acts, ~1 minute
```

`demo.sh` needs only `bash` and `curl`; it uses `jq` if you have it and does not need it.

```bash
PAUSE=1 ./demo.sh                    # stop between acts, for a live walkthrough
VERBOSE=1 ./demo.sh                  # print whole response bodies
BASE_URL=http://host:8080 ./demo.sh  # point it elsewhere
```

Interactive API docs are at `/swagger-ui.html`, the OpenAPI document at `/v3/api-docs`.

Tests — 187 of them, and the domain ones need no Spring context:

```bash
./mvnw test
```

**Configuration.** Everything is env-overridable with working local defaults: `DB_URL`,
`DB_USERNAME`, `DB_PASSWORD`, `APP_PORT`, `MILKROUTE_DATASET`, `SPRING_PROFILES_ACTIVE`.
The compose stack runs the `dev` profile, which is what makes `POST /admin/reseed` legal —
it truncates the database, so it is refused outside `dev` and `sim`.

**Datasets.** Three, in `src/main/resources/datasets/`. `baseline` is the dairy from the
brief. `heat-crisis` is the same dairy on the same seed with two lines changed — the session
and the temperature — so the comparison is an experiment and not an anecdote.
`sparse-district` is a thinner, longer dairy where ten villages are physically unreachable
inside any hold window. Each file carries its expected `dataset-check` numbers as a comment
at the top.

```bash
curl -X POST 'localhost:8080/api/v1/admin/reseed?dataset=heat-crisis'
curl 'localhost:8080/api/v1/admin/dataset-check?dataset=heat-crisis'
```

### The API

| Actor | Endpoint | What it does |
|---|---|---|
| Ops | `POST /api/v1/plans` | Generate a draft plan for a session, optionally at a stated temperature |
| Ops | `GET /api/v1/plans/{id}` · `/feasibility` · `/exclusions` | The plan, the arithmetic behind it, and who it left out |
| Ops | `POST /api/v1/plans/{id}/publish` | Commit it. One published plan per session, enforced by a unique index |
| Ops | `GET /api/v1/advisory/session-timing` | What departing later would be worth |
| Ops | `GET /api/v1/ops/board` | Live trips, riskiest first, with alerts and ETAs |
| Ops | `GET /api/v1/ops/trips/{id}/mitigations` · `POST .../{action}` | Ranked options, executed only on a human's say-so |
| Ops | `POST /api/v1/trips` | Create the day's trips from the published plan |
| Driver | `GET /api/v1/drivers/{code}/trip` | Today's run |
| Driver | `POST /api/v1/drivers/trips/{id}/events` | A batch of events, idempotent by `clientEventId` |
| Driver | `POST /api/v1/drivers/trips/{id}/pings` | GPS positions |
| Farmer | `GET /api/v1/farmers/{code}/tanker-status` | One plain-English sentence |
| Farmer | `GET /api/v1/farmers/{code}/collections` | What was collected, and when |
| Admin | `POST /api/v1/admin/reseed` · `GET /admin/dataset-check` | Rebuild a dairy; check what binds |
| Sim | `POST /api/v1/sim/run` · `pause` · `resume` · `jump-to` · `GET /status` | Drive a whole session on a virtual clock |

The farmer endpoint returns a sentence rather than a payload to be assembled by a caller,
because the caller is eventually an IVR line:

```json
{"farmerCode":"F-00001","status":"NOT_SCHEDULED",
 "message":"No morning collection has been dispatched yet today.",
 "collectionPoint":"CP-00001","nextExpectedSession":"MORNING",
 "officeContact":"the dairy office"}
```

Eight statuses are implemented, from `NOT_SCHEDULED` through `EN_ROUTE` and `ARRIVING_NEXT`
to `COLLECTED`, `SKIPPED` and `DEFERRED`. Where a point is skipped, `guaranteedBy` comes
from the three-strike fairness rule, so the answer is "not today, but by Thursday morning"
rather than a shrug.

---

## Assumptions

The reasoning behind every judgement call is in [`ASSUMPTIONS.md`](ASSUMPTIONS.md). The ones
that shape the code most:

- **A collection point is the routing entity; a farmer is the attribution entity**, N:1. The
  tanker visits a point once; milk is recorded per farmer. This is the direct answer to
  "some points serve two farmers" — routing over farmers would produce duplicate stops at
  identical coordinates.
- **The spoilage clock starts at first collection, not departure.** The plant → first-village
  leg carries no milk. This one decision is the source of the main routing optimisation.
- **Travel time is modelled, not fetched.** `roadKm = haversineKm × 1.35`, then 15/26/34 km/h
  by distance band, then ×1.15 morning or ×0.90 evening. A maps API would need an account key
  and break the "runs on a clean machine" requirement. `TravelTimeProvider` is an interface;
  swapping in OSRM is a one-class change. **The five numbers in that model are educated
  estimates and are the first thing I would calibrate against real GPS traces.**
- **Service time per point is `2.0 + 0.35 × farmerCount` minutes**, a fixed cost of stopping
  plus per-farmer time.
- **Capacity is not the binding constraint.** 1,400 farmers produce ~16,800 L a morning
  against ~88,000 L of fleet. Five tankers would hold all the milk; the dairy owns 22 because
  the area is spread out and the window is short. The constraint is time, not volume — so
  "use fewer tankers" is a real objective, and the feasibility report reports it.
- **Business date is set explicitly at trip creation**, never derived from `now()`, because
  the evening session can cross midnight.

### Nothing is hardcoded

Three rules hold across the codebase, and they are worth checking rather than believing:

- **`Instant.now()` appears exactly once**, in `config/SystemClock.java`. Everything else
  takes a `ClockProvider`. That is what makes the simulation profile possible at all.
- **No count is written into code.** Not 22 tankers, not 60 villages, not 1,250 points, not
  1,400 farmers. Sweeping for them —
  `grep -rn "\b22\b\|\b60\b\|1250\|1400\|1\.35\|\b180\b" src/main/java` — returns prose in
  comments, longitude bounds of ±180, seconds-per-minute conversions, and the plant's
  04:00–22:00 intake hours, which mirror the column defaults in `V2__master_data.sql` and
  describe the plant's working day rather than the shape of the dairy. Every count is a query
  result or a value in `datasets/*.yaml`.
- **Tuning numbers live in the `solver_parameter` table**, twenty-one rows seeded from
  `datasets/reference.yaml`: spoilage constants, circuity, speed bands, safety buffer,
  equity exponent, alert thresholds. Retune a row and re-plan — nothing is cached across
  calls, which is exactly the demonstration the table exists for.
- **Nothing in `domain/` imports `org.springframework`.** The solver, the spoilage model and
  the ETA calculator are plain Java, built with `new`, tested in microseconds without a
  container.

Four constants deliberately stay in code, and they are model definitions rather than dairy
policy: the Q10 reference temperature (30 °C — the parameter is literally named
`baseHoldMinutesAt30C`) and its 10 °C interval; the 2 km and 10 km speed-band boundaries,
named by the parameter keys `speedUnder2Km` and `speed2To10Km`; the ETA delay-factor clamps
of 0.7 and 2.0, which bound a numerical estimator rather than expressing an operational
choice; and the 2-opt iteration caps. Moving any of those to the database would let a
retune produce a model that is internally inconsistent rather than merely differently tuned.

---

## The spoilage model

Milk does not have a hold window; it has a hold window *at a temperature*. Bacterial growth
in raw milk roughly doubles per 10 °C rise, so:

```
effectiveC = ambientC − (insulated ? insulationOffsetC : 0)
budget     = baseHoldMinutesAt30C × q10Factor ^ ((30 − effectiveC) / 10)
             clamped to [minHoldMinutes, maxHoldMinutes]
```

With the seeded parameters — 180 minutes at 30 °C, Q10 of 2.0, a 6 °C insulation offset, and
clamps at 60 and 480 minutes:

| Ambient | Plain tanker | Insulated |
|---|---|---|
| 18 °C | 414 min | 480 (ceiling) |
| 22 °C | 313 min | 475 min |
| 27 °C | 222 min | 336 min |
| 30 °C | 180 min | 273 min |
| 35 °C | **127 min** | **193 min** |
| 39 °C | 96 min | 146 min |

Three details that matter more than the formula:

**Insulation is a temperature offset, not a multiplier.** A jacket slows the milk warming
toward ambient, so the milk behaves as though the air were about 6 °C cooler. The resulting
~1.5× gain falls out of the exponential instead of being an arbitrary constant — and it
correctly gets *larger* on hotter days, which is when it matters.

**Both clamps exist for operational reasons.** Without the 480-minute ceiling, a 10 °C
morning yields twelve hours: bacteriologically true, operationally useless. Without the
60-minute floor, one sensor reading of 50 °C would put every trip into alarm and the
dispatcher would stop trusting the board.

**The budget ratchets down only.** If ambient rises mid-trip, the deadline tightens and an
alert fires. If it falls again, the deadline stays where it was: milk that has spent an hour
at 35 °C did not become fresher when a cloud went over. Bacterial damage is cumulative, and
letting the budget grow back would silently clear an alert on a trip that is genuinely in
trouble. `SpoilageRatchetTest` is eight tests about exactly this.

The monitor sweeps every 60 seconds, warns at 80% of the budget consumed and escalates at
95%, and keeps counting when tracking is lost rather than treating silence as safety.

---

## The algorithm

### Hot time, and why the order of a route matters more than its length

```
hotTime = Σ village internal traverse minutes
        + Σ inter-village hop minutes
        + travel(last village exit → plant)
        + plant unload minutes
```

**The plant → first-village leg is excluded.** The tanker is empty on the way out; no milk is
aging. Which means the objective is not distance — it is hot time, and the two disagree.

Three villages at 8 km, 25 km and 40 km from the plant:

```
Nearest-first:   plant → 8 km → 25 km → 40 km → plant     hot time = 319 min
Farthest-first:  plant → 40 km → 25 km → 8 km → plant     hot time = 265 min
```

**Identical distance driven. 54 minutes of spoilage risk removed by reversing the order.**
Drive out empty to the far end and collect on the way home: the milk that has been in the
tanker longest is the milk picked up nearest the plant. A distance-minimising solver cannot
see this, because on distance the two routes are the same route. `SequenceOptimiser` seeds
farthest-first for this reason and runs 2-opt with hot time as the objective function.

### The pipeline

1. **Snapshot the world** — fleet, drivers, plant, active points, coverage history, and the
   session's ambient temperature.
2. **Solve each village internally** (`VillageSolver`, nearest-neighbour + 2-opt). This is
   where 1,250 points become 60 blocks, each with an entry point, an exit point and an
   internal traverse cost. Arranging 20 points inside a village is a different order of
   problem from arranging 60 villages, and the decomposition is honest about that. Oversized
   villages are split.
3. **Assess feasibility** (`FeasibilityAssessor`) — required hot minutes against
   `Σ min(holdBudget, driverShift)`, compared with `feasibilityMargin`. This decides what the
   plan is *called*, not which code runs.
4. **Plan** (`RoutePlanner`) — savings-style merging of village blocks, every candidate merge
   validated by `ConstraintChecker` before it is accepted.
5. **Optimise each route's sequence** for hot time, farthest-first seeded.
6. **Assign tankers** (`TankerAssigner`) — the riskiest route gets the largest hold budget,
   and every pairing is re-checked against the constraints rather than assumed.
7. **Persist** the plan, its routes and their stops.

There is one planner, and on a hot day it degrades honestly rather than handing over to a
second, less-exercised algorithm. The hard days are the ones that matter; running different
code on exactly those days is how a system fails when it counts.

### ConstraintChecker

Four independent constraints, each testable alone, and a failure names which one blocked the
merge:

| Constraint | Question |
|---|---|
| `SpoilageConstraint` | Does hot time fit inside `holdBudget − safetyBuffer` for this tanker? |
| `CapacityConstraint` | Does the volume fit inside `capacity × capacityHeadroom`? |
| `ShiftLengthConstraint` | Does the whole trip, outbound leg included, fit the driver's shift? |
| `PlantWindowConstraint` | Does it arrive while the plant is open? |

The boundary is tested explicitly: exactly at `budget − buffer` passes, one minute over
fails.

Wiring the trip layer on top of this planner found three real defects in it, all now fixed
and all with regression tests: a feasibility assessor that ignored the driver roster
entirely; a tanker assigner that paired a route with a tanker that could not run it, booking
a route to reach the plant 44 minutes after its own milk had spoiled; and a merge loop that
built more insulated-only routes than there are insulated tankers, so every merge was
individually feasible and the set was not.

### Coverage mode, and why maximising litres is the wrong objective

When the fleet cannot cover the dairy, something has to be dropped. The naive objective —
rank points by litres per minute and take the best — is mathematically optimal and destroys
the business. The far end of the corridor is *always* the least efficient choice, so it is
*always* the one dropped. The optimiser is not making a fresh judgement each hot day; it is
making the same judgement every hot day, about the same eight villages, until those farmers
stop supplying.

So the merge ranking is:

```
score = (litres / marginalHotMinutes)          ← efficiency
      × (1 + daysSinceLastServed) ^ 1.6        ← equity
```

with a hard rule above it: a village at `maxConsecutiveSkips` (3) is merged **before**
anything is ranked at all. That cap guarantees every farmer is served at least once every
two days — a promise the dairy can actually make, and the source of the `guaranteedBy` date
in the farmer-facing response.

Two details worth stating. A village is as neglected as its *most* neglected point;
averaging would let one long-ignored point hide behind fresher neighbours. And a point with
no history at all outranks any amount of recorded neglect, so a village the dairy has never
reached cannot be overtaken by arithmetic.

The counters move on **publish**, not on generate. Drafts get produced and discarded during a
normal morning, and treating a discarded draft as a served session would tell the system that
milk was collected when no tanker ever left.

Publishing also writes one `plan_exclusion` row per unserved point, with the litres forgone.
That is the part a dairy can use: not "coverage was 33%" but a list of who was left, in which
village, and what it cost.

```
GET /api/v1/plans/{id}/exclusions
→ 386 points excluded · 2,859.81 L left in farmers' cans
    CP-00154  village V-0012  COVERAGE_LIMIT  15.91 L
```

### Trips, events and idempotency

`trip_stop` **snapshots** `route_stop` at trip creation. If a new plan is published at 05:30
while tankers are on the road, nothing changes under a driver halfway through their run.

**Events drive trip progress; GPS pings only refine position.** Driving past a collection
point is not collecting from it, so only an `ARRIVED_AT_STOP` advances the sequence. Pings
place the tanker along the current leg for the ETA and nothing more.

Drivers lose signal constantly, so the phone buffers events with their real timestamps and
replays the backlog on reconnect — including events the server already has, because it
cannot know which got through. Every event carries a phone-generated `clientEventId` with a
unique index, the batch is sorted by client timestamp and applied in that order, and
duplicates are absorbed by `INSERT ... ON CONFLICT (client_event_id) DO NOTHING`.

That last detail is a deviation from the plan worth knowing about. The obvious implementation
catches `DataIntegrityViolationException` and counts a duplicate, and it does not work:
Postgres aborts the transaction on the failed statement, so every event queued behind the
duplicate is lost with it. `ON CONFLICT` keeps the same unique index as the arbiter, keeps
the transaction usable, and stays correct when two copies of a batch arrive at once. The
test sends 30 events, resends 5, and asserts 25 applied, 5 duplicates, and no double
collections.

### Mitigations

When a trip is going to miss its deadline, the system generates options, ranks them by litres
saved, and executes none of them. Auto-diverting a tanker because a travel estimate was
pessimistic would be worse than the problem.

And when nothing works, it says so. A trip three hours behind on a 35 °C morning comes back
with `anySaves: false` and the summary "No option gets this load to the plant inside its
budget" — SKIP_REMAINING saves 40 L and still lands at 337 minutes against a 193-minute
budget; CONTINUE_AS_PLANNED arrives 188 minutes over. A system that always offers a fix is
lying. Execution uses optimistic locking, so a stale version returns 409 rather than
double-diverting a tanker.

---

## Permission matrix

**Not implemented.** There is no authentication in this build and no role interceptor ships;
the matrix below is the intended design, and it is written down because a half-built Spring
Security config would be worse than an honest placeholder. Enforcing it is roughly a day's
work — an interceptor over these path groups plus a real token per actor.

| | Farmer | Driver | Dispatcher | Plant operator | Admin |
|---|---|---|---|---|---|
| Own tanker status / own collections | ✅ | — | ✅ | — | ✅ |
| Another farmer's data | ❌ | ❌ | ✅ | — | ✅ |
| Own trip, own events and pings | — | ✅ | ✅ | — | ✅ |
| Another driver's trip | — | ❌ | ✅ | — | ✅ |
| Generate / read plans | ❌ | ❌ | ✅ | read | ✅ |
| Publish a plan | ❌ | ❌ | ✅ | ❌ | ✅ |
| Ops board, alerts, ETAs | ❌ | ❌ | ✅ | ✅ | ✅ |
| Execute a mitigation | ❌ | ❌ | ✅ | ❌ | ✅ |
| Record intake at the plant | ❌ | ❌ | ❌ | ✅ | ✅ |
| Reseed / change solver parameters | ❌ | ❌ | ❌ | ❌ | ✅ |

The design decisions behind it, which are the part worth keeping:

- **Farmers authenticate by phone, not password.** The realistic channel is IVR or SMS OTP
  against the number already on the farmer record. A farmer can see their own status and
  their own collection history, and nothing else — milk volumes are income, and income is
  private between neighbours.
- **Drivers get a token bound to a device**, because the driver app is the write path for
  every event in the system and a shared login makes the audit trail meaningless.
- **A driver can only write events for their own trip.** The trip id in the path is checked
  against the trip assigned to that driver for that business date.
- **Publishing a plan and executing a mitigation are dispatcher-only and audited.** Both
  change what tankers do while they are moving.
- **Reseeding is admin-only and, today, profile-gated** — it truncates the database, so it is
  refused outside `dev` and `sim` regardless of who calls it. That gate is real and does
  ship.

---

## What I did not build, and why

| Not built | Why |
|---|---|
| **Real authentication** | Documented above as a matrix rather than half-implemented. The design is the valuable part; the interceptor is a day. |
| **Real map / traffic API** | Needs an account key, which breaks "runs on a clean machine". `TravelTimeProvider` is an interface so the seam is real and testable. |
| **Consolidation advisory** (merging collection points a few hundred metres apart) | Designed in full, then cut — see `ASSUMPTIONS.md` §8. It answers a different question from the brief's, and would have meant two tables, an entity graph and a DBSCAN pass nothing else reads. Three of its four design decisions are about farmer consent rather than geometry, which is why the design is recorded and the code is not. Its removal is also why `collection_point.merged_into_id` does not exist, and why farmer status Q6 is unreachable. |
| **Partial village fill** (taking some points of a village, leaving the rest) | Worth a few points of coverage. A village that is silently half-served is worse than one honestly skipped: nobody gets an exclusion row, and the missed farmers look served. |
| **Ejection chains** (undoing an accepted merge to make room for a better one) | Worth a few more points. The failure mode is a route left corrupt by an abandoned ejection, and day-over-day plan thrash makes the diff unreadable — and the diff is what ops actually reads. |
| **A separate `CoveragePlanner`** | Coverage mode is the same loop with the same ranking, degrading honestly. A second algorithm for the hard days is a second thing to get wrong on exactly the days that matter. |
| **SMS / IVR delivery** | A delivery-channel concern with no bearing on routing or tracking correctness. Notifications are logged. |
| **Payments, fat/SNF pricing** | A separate bounded context. |
| **Driver app, ops console** | This is a backend brief. |
| **Multi-plant optimisation** | The schema supports several plants because the divert mitigation needs a destination, but choosing which plant each route serves is a whole extra optimisation dimension. |
| **Volume forecasting** | Needs historical data the system does not have yet. The ±15% variance in the seed is what makes the case for it. |
| **Kafka, microservices** | 22 tankers pinging every 30 s is 0.7 writes a second. The difficulty here is the domain, so that is where the time went. |
| **A comparison against a hand-entered "old" plan** | I would be inventing the bad plan myself, which makes the finding circular. The feasibility arithmetic above is a real finding instead. |

---

## Known limitations

1. **Travel times are synthetic.** Circuity 1.35 and the 15/26/34 km/h bands are educated
   estimates. Every downstream number inherits their error. I would run the system in shadow
   mode for two weeks — generating plans, comparing predicted against actual arrivals —
   before publishing an algorithmic plan to drivers.

2. **The spoilage constant is industry practice, not this dairy's data.** 180 minutes at
   30 °C is reasonable but not measured here. `intake_record.oldest_milk_min` is written on
   every trip precisely so rejection rate can eventually be plotted against milk age and the
   constant replaced with a fitted one.

3. **`temperature_profile` holds one figure per month per session.** Ambient cannot move
   mid-trip unless a caller passes an override into `SpoilageMonitorService.check`. The
   simulation and the ratchet test do; a real deployment would pass a weather reading.
   Without one, the one-way ratchet never fires in production even though it is implemented
   and tested.

4. **The ETA delay factor can read a driver as faster than they are.** Pace is measured from
   the first to the last completed stop. If stops were skipped in between, the *planned* span
   still includes their service time and legs while the *actual* span does not, so the ratio
   comes out low. Bounded — the clamp at 0.7 caps it at 30% optimistic — but optimistic is
   the wrong direction to be wrong in when the number is being read to a farmer. The fix is
   per-stop planned legs rather than arrival times.

5. **Exclusion rows do not distinguish geography from choice.** `ExclusionReason` has an
   `UNREACHABLE_WITHIN_HOLD` value and nothing writes it: every excluded point comes back
   `COVERAGE_LIMIT`, including the ten `sparse-district` villages that no tanker could ever
   reach. The information exists — `GET /admin/dataset-check` reports unreachable villages by
   code — but the plan's own exclusion list will not tell you that ten of them are a physics
   problem rather than a ranking outcome. That distinction is exactly the one a dairy needs
   to decide where to put a local chilling unit, so it is the first thing I would add.

6. **Pre-collection milk age is not modelled.** The farmer milked before the tanker arrived.
   That is real, roughly constant across points, and would shift every deadline by a fixed
   offset — so every deadline here is slightly optimistic.

7. **Diversion is one-way.** Reverting a diverted trip mid-route is an operations phone call,
   not a system feature.

8. **Farmer status Q6 is unreachable.** A point merged into a hub has no representation, since
   the consolidation advisory was cut. Eight of the nine statuses are implemented.

9. **The simulation validates logic, not the model.** It proves constraints hold, events apply
   idempotently and alerts fire at the right thresholds. It cannot prove the travel-time model
   is right, because I wrote both the model and the simulator.

10. **`SeedResult.elapsedMs` reads 0.** It is measured inside the transaction, and Hibernate
    flushes the inserts at commit, after the second reading is taken. Cosmetic — the seeding
    itself is fine — but the number it reports is not the number it claims.

---

## The next two weeks

1. **Wire OSRM and calibrate.** Circuity and the three speed bands against real GPS traces,
   in shadow mode alongside the existing paper routes. Everything else is downstream of this.
2. **Take the roster finding to the dairy.** The morning shortfall is a 300-minute shift, and
   the arithmetic that says so is one endpoint. That is a conversation, not a project.
3. **Trial the 18:30 evening departure** with two or three villages and measure rejection
   rates against the model's prediction. It is the cheapest 18 points of coverage available.
4. **Classify exclusions properly** — `UNREACHABLE_WITHIN_HOLD` versus `COVERAGE_LIMIT` — and
   turn the unreachable set into a chilling-unit siting recommendation with litres attached.
5. **Calibrate the hold window** against real rejection data from `intake_record`, replacing
   the 180-minute industry figure with a measured one.
6. **Per-point volume forecasting**, so plan estimates stop being ±15% wrong and the capacity
   constraint starts meaning something.
7. **The driver Android app** with proper offline sync — the server side of that contract is
   already built and tested.
8. **Move plan generation to a background job** with a status endpoint. At 1,250 points it
   takes between a third of a second and about a second, and a synchronous POST is fine;
   above roughly 5,000 points it will not be.
