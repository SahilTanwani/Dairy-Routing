# MilkRoute

A dairy sends 22 tankers out twice a day to collect milk from about 1,400 farmers across 60
villages and bring it to one chilling plant. Raw milk left warm goes off, and how fast depends
on the day's heat, so every route races a deadline the weather sets. This backend plans the
routes, tracks the tankers, tells farmers when to expect one, and — when the fleet cannot
reach everybody — names the limit it hit and prices the cheapest way out.

Java 21 · Spring Boot 4.0.8 · PostgreSQL 16 · Flyway · Docker Compose.

---

## Prerequisites

- **Docker Desktop** — Java 21, Maven and PostgreSQL 16 all run inside the
  containers, so nothing else needs installing to run it
- **Git**, or download the ZIP from GitHub
- **Ports 8080 and 5432 free**

`./mvnw test` additionally needs JDK 21 on the host.

# 1. Run it

```bash
git clone https://github.com/SahilTanwani/Dairy-Routing.git
cd Dairy-Routing
docker compose up
./demo.sh          # in a second terminal
```

| Step | First run | Afterwards |
|---|---|---|
| `docker compose up` | ~2 min while Maven pulls every dependency into the image, plus a minute or two the very first time for the base images | ~15 s |
| App becomes usable | ~18 s after the container starts | same |
| `./demo.sh` | 22 s — four acts: three dairies, four plans, one advisory | 22 s |

**What "ready" looks like.** `Started MilkrouteApplication` is *not* it — the app listens a
few seconds before it has loaded the dairy, and until it has, health answers **503 with
`"STARTING"`** on purpose. Wait for the seeder line, or poll health:

```
Seeded 'baseline' (seed 88213): 60 villages, 1250 points, 1408 farmers, 22 tankers, ...
$ curl localhost:8080/api/v1/health   →   {"status":"UP","service":"milkroute",...}
```

`demo.sh` waits for the 200 itself. It needs `bash` and `curl` only, uses `jq` if present, and
takes `PAUSE=1` (stop between acts), `VERBOSE=1` (full bodies) and `BASE_URL=...`.

## Tests, development mode, and the docs

```bash
docker compose up -d db                                  # both of these need a database
./mvnw test
# Tests run: 187, Failures: 0, Errors: 0, Skipped: 0     Total time: 39.7 s

SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run        # app on the host, ready in ~9 s
# PowerShell:  $env:SPRING_PROFILES_ACTIVE="dev"; .\mvnw.cmd spring-boot:run
```

**Three test classes — 14 tests in all — boot a Spring context and expect PostgreSQL on
localhost:5432.** Without it those three fail and the other 173 still pass. Everything in
`domain/` is plain Java and needs no Spring context, which is why the suite finishes in well
under a minute.

The `dev` profile is what makes `POST /admin/reseed` legal: that endpoint empties the
database, so it is refused unless the profile is `dev` or `sim`. Interactive docs at
<http://localhost:8080/swagger-ui.html>, OpenAPI at `/v3/api-docs`.

---

## Trying a different dairy

Three dairies, each a YAML file in `src/main/resources/datasets/`. Switching is one HTTP call
— no restart, no rebuild — and `dataset-check` reports the arithmetic before any planning
happens: minutes of work needed against minutes the fleet has.

```bash
curl -X POST 'localhost:8080/api/v1/admin/reseed?dataset=baseline'
curl -X POST 'localhost:8080/api/v1/admin/reseed?dataset=heat-crisis'
curl -X POST 'localhost:8080/api/v1/admin/reseed?dataset=sparse-district'
curl 'localhost:8080/api/v1/admin/dataset-check?dataset=baseline'
```

| Dataset | Villages · points · farmers · tankers | Session | Needs / has (min) | Ratio | What it shows |
|---|---|---|---|---|---|
| `baseline` | 60 · 1,250 · 1,408 · 22 | Morning, 22 °C | 7,021 / 6,600 | **1.06** | Just short. The driver roster runs out first. |
| `heat-crisis` | 60 · 1,250 · 1,408 · 22 | Evening, 35 °C | 8,133 / 3,190 | **2.55** | Badly short. The milk runs out first. |
| `sparse-district` | 45 · 574 · 611 · 16 | Evening, 30 °C | 9,425 / 3,252 | **2.90** | 10 villages no tanker can reach and return from in time. |

A ratio above 1.0 means the fleet cannot serve everyone; above the feasibility margin of 0.92
the planner switches to coverage mode and writes down who it left out and why.

`./demo.sh` runs all of this — three reseeds, four plans, the advisory, a publish
and a rejected second publish — with commentary between each step.
`PAUSE=1 ./demo.sh` stops between acts for a live walkthrough.

**Why baseline and heat-crisis are a fair comparison.** They share a random seed (88213), so
they build the identical dairy — same villages, coordinates, 1,250 collection points, 1,408
farmers and 22 tankers. Ignoring comments, three lines differ:

```bash
cd src/main/resources/datasets
diff <(grep -v '^ *#' baseline.yaml) <(grep -v '^ *#' heat-crisis.yaml)
# < name: baseline             >  name: heat-crisis
# < defaultSession: MORNING    >  defaultSession: EVENING
# < defaultAmbientC: 22        >  defaultAmbientC: 35
```

The only real difference is the weather, which makes the morning-versus-evening result an
experiment rather than an anecdote.

> **Reseeding empties the database.** Every plan, trip and collection goes. Reseed *before*
> you start a simulation run, never during one.

---

## Watching a session run

**Why a simulation exists.** A session happens twice a day and takes three hours; you cannot
test that by waiting for it. So nothing here calls the system clock — every class asks a
`ClockProvider` what time it is, and under the `sim` profile that clock is driven by the test.

**The simulated drivers are not a shortcut.** They post to the same HTTP endpoints a real
driver's phone would use and never touch the database, so the demo exercises the real
controllers, services and duplicate handling rather than a parallel test path.

```bash
SPRING_PROFILES_ACTIVE=sim docker compose up -d app     # sim profile replaces the real clock
# PowerShell:  $env:SPRING_PROFILES_ACTIVE="sim"; docker compose up -d app
curl localhost:8080/api/v1/sim/status
# {"state":"IDLE","simulatedTime":"2026-10-15T04:30:00Z","step":0,...}

# The scenario reseeds, plans, publishes and creates the trips itself.
curl -X POST 'localhost:8080/api/v1/sim/run?scenario=happy-morning'
curl localhost:8080/api/v1/sim/status
# {"state":"RUNNING","simulatedTime":"2026-10-15T06:03:30Z","step":157,"drivers":22,...}

# Skip the dull stretch. The parameter is "at", and it only moves forward.
curl -X POST 'localhost:8080/api/v1/sim/jump-to?at=2026-10-15T10:00:00Z'
curl localhost:8080/api/v1/sim/status
# {"state":"FINISHED","step":946,"drivers":22,"driversDone":22}
```

Jumping backwards is refused with a 400 — `cannot jump backwards: simulated time is already
2026-10-15T07:31:00Z` — because events already recorded cannot be un-happened by rewinding a
clock. **Measured run:** `happy-morning` replayed 621 simulated minutes in 946 steps in
**3 min 20 s** of real time, with one jump forward, and all 22 trips finished `COMPLETED`.

**What to look for.** Every simulated driver loses signal for thirty minutes and reconnects,
sending its buffered events *plus three the server already had* — twenty-two log lines, one
per driver, and SQL proving the duplicates changed nothing:

```bash
docker logs milkroute-app | grep reconnected
# PowerShell:  docker logs milkroute-app | Select-String reconnected
# Driver D-0001 reconnected on trip 1: sent 15 events (12 buffered + 3 already
# acknowledged) -> 12 applied, 3 duplicates

docker exec milkroute-db psql -U milkroute -d milkroute -c \
 "select count(*) rows, count(distinct client_event_id) ids from driver_event;
  select status, count(*) from trip group by status;"
#  rows | ids       ← every row a distinct event: the three resent were dropped
#  2670 | 2670
#  COMPLETED | 22
```

A second scenario, `spoilage-crisis`, heats the air from 30 °C to 38 °C forty minutes in, so
deadlines tighten mid-trip and alerts escalate.

---

# 2. What I found

**Hold budget** is how many minutes the milk in a tanker stays good — hotter air, fewer
minutes. **Driver shift** is the most hours a driver may work in one session. A tanker is
limited by whichever runs out first, and which one that is depends entirely on the weather.

| | Morning | Evening |
|---|---|---|
| Temperature | 22 °C | 35 °C |
| Milk stays good for | 313 min | **127 min** |
| Driver works | 300 min | 300 min |
| **So each tanker is worth** | **300 min — the driver is the limit** | **127 min — the milk is the limit** |
| Dairy needs | 7,021 tanker-minutes | 8,133 tanker-minutes |
| Fleet supplies | 6,600 tanker-minutes | 3,190 tanker-minutes |
| Plan reaches | **868 of 1,250 points**, 12,898 L | **241 of 1,250 points**, 2,419 L |

**Morning: the limit is how long a driver can work, not how fast the milk spoils.** The gap
is only 6%, and closing it costs nothing to buy. Raise the shift to 600 minutes and the milk
becomes the limit again instead of the driver — same 22 tankers, same villages, same weather:

| Shift | Mode | Points served |
|---|---|---|
| 300 minutes | Coverage optimisation | 868 |
| 600 minutes | **Full service** | **1,216** |

**348 more collection points every single morning, from a rostering conversation.** Every
other fix for the morning — more tankers, more insulation, a second chilling plant — costs money.

**Evening: the limit is spoilage.** The dairy needs more than twice what the fleet can give.
No rostering change touches a shortfall that size; serving everyone at 35 °C would take about
61 tankers. But the air cools roughly 3 °C an hour after the afternoon peak, so leaving at
**18:30 instead of 16:30** means collecting at 29 °C instead of 35 °C:

```bash
curl 'localhost:8080/api/v1/advisory/session-timing?session=EVENING&ambientTempC=35&shiftHours=2'
```

| | Leave 16:30 | Leave 18:30 |
|---|---|---|
| Temperature | 35 °C | 29 °C |
| Milk stays good for | 127 min | **193 min** |
| Points reached | 19% | **37%** |

**About 800,000 litres a year, and it costs nothing** — farmers milk two hours later. The
endpoint gets that number by planning the same dairy twice, once at each departure time, and
reporting both sides, so the claim can be checked rather than believed.

---

# 3. How it works

```
dataset YAML ─► seed the dairy ─► today's temperature ─► hold budget per tanker
     │
     ├─► solve each village internally      →  60 blocks, not 1,250 loose stops
     ├─► enough fleet-time?                 →  full service | coverage mode
     ├─► build routes: merge village blocks, each merge checked against 4 constraints
     ├─► re-order each route for least time carrying milk, farthest village first
     ├─► assign tankers (riskiest route gets the longest budget) ─► publish plan
     └─► create trips (a frozen copy) ─► driver events ─► ETAs, alerts
```

## The drive out is free

The tanker is empty on the way out, so nothing is spoiling yet: the clock starts at the
**first collection**, not at departure. The goal is therefore "least time carrying milk", not
"least distance" — and the two disagree. Three villages 8, 25 and 40 km from the plant:

| Order | Legs carrying milk | Straight-line km | Road km (×1.35) | At 39 km/h |
|---|---|---|---|---|
| Nearest first: 8 → 25 → 40 → plant | 17 + 15 + 40 | 72 | 97 | **149 min** |
| Farthest first: 40 → 25 → 8 → plant | 15 + 17 + 8 | 40 | 54 | **83 min** |

Both drive exactly 80 km, so going out empty to the far end and collecting on the way home
removes **66 minutes of spoilage risk for no extra fuel**. A solver minimising distance cannot
see this: on distance the two routes are the same route.

## How long milk lasts depends on the weather

Bacteria in raw milk grow about twice as fast per 10 °C rise, so the budget roughly halves for
every 10 °C hotter the day is.

| Outside air | 18 °C | 22 °C | 27 °C | 30 °C | 35 °C | 39 °C |
|---|---|---|---|---|---|---|
| Plain tanker | 414 min | 313 min | 222 min | 180 min | **127 min** | 96 min |
| Insulated | 480 (ceiling) | 475 min | 336 min | 273 min | **193 min** | 146 min |

## Solving villages first

Collection points inside a village are 200–600 m apart — a minute or two of driving. Villages
are 8–15 minutes apart. So the problem is not "arrange 1,250 stops"; it is "arrange about 20
stops inside each village, then assign 60 village blocks to 22 tankers". Those are different
problems, and it is why this scales: the expensive part sees 60 things, not 1,250.

## Trips copy their plan

When trips are created, each stop is **copied** from the plan rather than pointing at it, so a
plan republished at 05:30 changes nothing under a driver halfway through their run. Without
the copy, a mid-session republish silently rewrites the stop list of every truck already out.

## One database index handles drivers losing signal

**The problem.** A driver's phone goes dark for thirty minutes in a valley, still recording
arrivals and collections with their real times. When the signal returns it uploads everything
at once — including events that did get through before the signal dropped, because the phone
cannot know which ones landed.

**The solution.** Every event carries a phone-generated `client_event_id` with a unique index
on it. A batch is sorted by the phone's own timestamps, applied in that order, and inserted
with `ON CONFLICT (client_event_id) DO NOTHING`. Duplicates are counted and dropped.

The obvious alternative — catch the constraint violation and count it — does not work:
PostgreSQL aborts the whole transaction on a failed statement, so every event queued behind
the duplicate is lost with it.

---

# 4. Assumptions

The brief left a lot open. Every number below is a choice, labelled either **grounded** (there
is a real basis for it) or **guess** (I picked it and it needs measuring).

## The collection point model

**Most collection points serve one farmer; about 12% serve two.** That gives ~1,250 points for
1,408 farmers, roughly 21 per village. The brief says "some collection points serve two
farmers", so the two-farmer point is **grounded**; the 12% is a **guess**, common enough to
matter without inventing structure the brief does not describe.

Routing over farmers instead would produce two stops at identical coordinates, and 1,400 stops
across 22 tankers is 64 stops per route — not a hard optimisation problem, an impossible one.
Real dairies use village collection societies for exactly this reason.

## Spoilage

| Assumption | Value | Reasoning | Status |
|---|---|---|---|
| Milk holds this long at 30 °C | **180 min** | Common dairy practice is raw milk to chilling within 2–4 hours; 3 hours is the mid-point. | **Guess** — an industry rule of thumb, not this dairy's data. |
| Growth doubles per 10 °C | **Q10 = 2.0** | Standard food-science figure for bacterial growth. | **Grounded.** |
| Insulation | **−6 °C offset**, not a multiplier | See below. | Modelling choice **grounded**; the 6 °C a **guess**. |
| Floor on the budget | **60 min** | One bad reading of 50 °C would otherwise put the whole fleet into alarm, and a dispatcher who sees that once stops trusting the board. | Reasoning **grounded**, value a **guess**. |
| Ceiling on the budget | **480 min** | At 10 °C the bacteriology allows twelve hours — true, and operationally useless. Nobody leaves milk in a tanker all day. | Reasoning **grounded**, value a **guess**. |
| Clock starts at | **first collection**, not departure | The tanker is empty on the outbound leg, so no milk is ageing yet. | **Grounded** — it follows from the physical situation. |

**Why insulation is a temperature offset.** A jacket does not stop the milk warming; it slows
the milk warming toward the air around it, so insulated milk behaves as though the air were
cooler. Modelled that way, the ~1.5× gain falls out of the exponential rather than my picking
1.5 and asserting it — and it correctly gets *larger* on hotter days, when it matters most.

**The budget only ever tightens.** If the air heats up mid-trip the deadline moves earlier. If
it cools again the deadline stays put: milk that spent an hour at 35 °C did not become fresher
when a cloud went over. Bacterial damage adds up.

## Travel time

No maps API is used; a key would break "clone it and run it". `TravelTimeProvider` is an
interface, so swapping in a real routing service is a one-class change.

| Assumption | Value | Reasoning | Status |
|---|---|---|---|
| Roads are longer than the straight line by | **×1.35** | Published rural road studies put this at 1.2 (flat grid) to 1.5 (hilly, winding). | **Guess** for mixed terrain. |
| Speed under 2 km | **15 km/h** | Village lanes: cattle, people walking, no room to pass. | **Guess.** |
| Speed 2–10 km | **26 km/h** | Connecting roads between villages. | **Guess.** |
| Speed over 10 km | **34 km/h** | District road, loaded heavy vehicle. | **Guess.** |
| Morning speeds | **+15%** | The roads are empty at 5 AM. | **Guess.** |
| Evening speeds | **−10%** | Market traffic and school children at 5 PM. | **Guess.** |

These are loaded tankers on rural Indian roads, not cars on a motorway. All six are estimates
and they are the weakest part of the model. **Worked example**, two villages 10.4 km apart in
a straight line:

```
road distance   10.4 × 1.35 = 14.0 km
morning         34 × 1.15 = 39 km/h  →  21.5 min
evening         34 × 0.90 = 31 km/h  →  27.4 min
```

Six minutes longer per leg in the evening. Across eight village hops that is **48 extra
minutes — in the session that already has the least time to spare.**

## The fairness decision

This one is a business decision wearing a technical costume, so it is worth spelling out.

**The obvious approach is wrong.** When the fleet cannot reach everyone, rank villages by
litres per minute spent and take the best. That is mathematically optimal, and it would
destroy the cooperative: the far end of the district is *always* the least efficient choice,
so it would be dropped on *every* hot day, the same villages over and over, until they left.

**So a village that has been waiting climbs the priority list, and the longer it has waited
the faster it climbs:**

```
score = litres per extra minute              ← efficiency
      × (1 + days since last served) ^ 1.6   ← fairness
```

**And a hard rule above it.** No collection point is skipped more than three sessions in a
row; at three it is served before anything else is even considered. That guarantees every
farmer is collected from at least once every two days — a promise the dairy can make, and the
source of the guaranteed date in a farmer's "when is my tanker coming?" answer.

Two details. A village counts as neglected as its *most* neglected point, because averaging
lets one long-ignored point hide behind fresher neighbours. And a village with no history at
all outranks any amount of recorded neglect, so somewhere the dairy has never reached cannot
be beaten on arithmetic.

## Operational

| Assumption | Value | Reasoning | Status |
|---|---|---|---|
| Session start times | 05:00 and 16:30 | Typical Indian dairy practice. Both configurable. | **Guess**, configurable. |
| Morning / evening yield | 60 / 40 split | Common for buffalo herds. | **Guess.** |
| Daily volume varies | ±15% per farmer | Real herds vary, so plans are always slightly wrong; capacity is therefore planned at 95% of the tanker, not 100%. | **Guess**, and the reason for the headroom. |
| Time to work a stop | 2 min + 0.35 × farmers | Fixed cost of stopping — park, valve, paperwork — plus per-farmer time. | **Guess.** |
| Safety buffer | 20 min | Travel times here are estimates. A route booked to arrive with two minutes to spare fails the first wet morning. | Reasoning **grounded**, value a **guess**. |
| Warn the dispatcher at | 80% of budget used | Meant to give enough notice to act. | **Guess** — see limitations. |
| Escalate to critical at | 95% of budget used | Below this a dispatcher has options; above it, mostly not. | **Guess.** |
| Business date | set explicitly at trip creation | The evening session can cross midnight, so deriving it from "now" would be a bug. | **Grounded.** |

**Every tunable number above is a row in the `solver_parameter` table** — 21 of them — and can
be changed without a redeploy or a restart. Nothing is cached between planning calls, so a
retune takes effect on the next request. Full reasoning is in [`ASSUMPTIONS.md`](ASSUMPTIONS.md).

---

# 5. What I did not build, and why

| Not built | Decision |
|---|---|
| **Real authentication** | A half-built Spring Security config is worse than an honest placeholder, so the intended roles are written down as a matrix and nothing ships that pretends to enforce them. |
| **Real map / traffic API** | An account key would break "clone it and run it", so travel time is modelled behind an interface a real routing service can replace in one class. |
| **SMS / IVR delivery** | A delivery-channel concern with no bearing on whether the routing or the tracking is correct; notifications are logged instead. |
| **Consolidation advisory** (merging points a few hundred metres apart) | Designed in full and then cut: three of its four design decisions are about farmer consent rather than geometry, so the design is the valuable part and the code is not. |
| **Partial village fill** | A village that is silently half-served is worse than one honestly skipped — nobody gets an exclusion row and the missed farmers look served. |
| **Ejection chains** (undoing an accepted merge for a better one) | Worth a few points of coverage, at the cost of routes left corrupt by an abandoned ejection and a day-over-day plan diff too noisy for ops to read. |
| **Ops console and driver app** | This is a backend brief. |
| **Payments and milk pricing** | A separate bounded context with its own rules; nothing in it changes a route. |
| **Kafka, microservices** | 22 tankers pinging every 30 seconds is 0.7 writes a second — the difficulty here is the domain, so that is where the time went. |
| **Multi-plant optimisation** | The schema holds several plants because the divert option needs a destination, but choosing which plant each route serves is a whole extra dimension of optimisation. |
| **Volume forecasting** | It needs historical data that does not exist yet; the ±15% variance in the seed is the argument for building it later. |
| **Data retention policy** | Every ping and event is kept forever, which is fine for a take-home and wrong for production, where GPS traces of named drivers are personal data needing a defensible deletion schedule. |

---

# 6. Known limitations

**The travel times are my own model, not measurements.** I estimated how much roads wander
compared with a straight line, and how fast a loaded tanker goes on each kind of road. Those
numbers could simply be wrong, and every deadline in the system is built on them.

That is why every trip records **both the predicted and the actual arrival time**. In real use
you would run this quietly alongside the existing paper routes for two weeks, compare the two
columns, and only switch over once the predictions were close.

**The simulation proves the plumbing works, not that the timing is right.** The simulated
drivers move at a fixed six minutes per leg rather than the times the planner worked out, so a
40 km hop and a 300-metre one take the same simulated time.

That is enough to prove events are recorded correctly, duplicates are rejected, trips reach
`COMPLETED` and alerts fire at the right thresholds. It cannot test whether the travel model
is accurate, because I wrote both the model and the simulator, and they would agree with each
other whatever the truth was.

**The warning alert fires too early.** It triggers when a tanker is projected to use 80% of
its hold budget. But routes are planned close to their limit by design — a typical morning
route is booked at **220 minutes of a 313-minute budget, about 70%** — so a 14% overrun is
enough to cross the line.

In the simulated morning the first warning fires six minutes after the fleet leaves, and
twelve trips raise one. An alert board that cries wolf is one nobody reads. The threshold is a
single row in `solver_parameter` and should be tuned against real rejection data rather than
left at my guess.

**The deadline can tighten if the weather gets hotter mid-trip, but nothing ever makes it.**
Temperature comes from a table holding one figure per month per session, so it cannot change
during a run. The logic is written and tested, but only the `spoilage-crisis` scenario drives
it, by passing the change in by hand. A weather feed or a tanker thermometer would fix that.

---

# 7. If I had two more weeks

- **Wire a real routing service and calibrate it against GPS traces.** Every other number is
  downstream of the travel model, so this comes first.
- **Take the roster finding to the dairy.** 348 collection points a morning for a conversation
  about shift patterns is the best-value item in the system.
- **Trial the 18:30 evening departure** with two or three villages, and measure actual
  rejection rates against what the model predicted.
- **Separate "we chose not to" from "no tanker could".** Excluded points all come back with
  one reason today; splitting them is what tells a dairy where to put a local chilling unit.
- **Calibrate the hold window** against real rejection data, replacing the 180-minute industry
  figure with a measured one.
- **Build the driver Android app** with proper offline sync. The server side of that contract
  is already built and tested.

---

# 8. Where things are

```
src/main/java/com/dairy/milkroute/
├── config/       the clock, solver parameters, scheduling
├── controller/   HTTP endpoints only — thin, no business logic
├── domain/       plain Java, no Spring, tested with no container
│                 geo/ spoilage/ routing/ tracking/ trip/ mitigation/ advisory/
├── service/      Spring beans: orchestration and transactions
├── repository/ entity/ dto/ mapper/ enums/ error/ security/
└── simulation/   virtual clock, virtual drivers, scenario runner

src/main/resources/
├── db/migration/  V1..V5 — Flyway owns the schema, Hibernate only validates it
├── datasets/      baseline, heat-crisis, sparse-district, reference (solver parameters)
└── scenarios/     happy-morning, spoilage-crisis

docs/  problem, schema, algorithms, datasets, edge cases, task list
```

**The system clock is called in exactly one place**, which is what makes the simulation
possible at all; retrofitting it later would mean touching every class. **And nothing in
`domain/` knows Spring exists** — the solver, spoilage model and ETA calculator are plain
Java, built with `new`. Both are worth checking rather than believing:

```bash
grep -rn "Instant\.now()" src/main/java
# config/SystemClock.java:22:        return Instant.now();
# simulation/VirtualClock.java:13:   * ... {@code Instant.now()} ...   ← a comment, not a call

grep -rn "org.springframework" src/main/java/com/dairy/milkroute/domain | wc -l
# 0
```
