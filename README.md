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

`./mvnw test` additionally needs JDK 21 on the host. On Windows, run `demo.sh` from **Git
Bash** — PowerShell's `bash` resolves to WSL, which may not be installed.

---

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
| `./demo.sh` | 22 s | 22 s |

**What "ready" looks like.** `Started MilkrouteApplication` is *not* it — the app listens a few
seconds before it has loaded the dairy, and until it has, health answers **503 with
`"STARTING"`** on purpose. Wait for the seeder line:

```
Seeded 'baseline' (seed 88213): 60 villages, 1250 points, 1408 farmers, 22 tankers, ...
```

`demo.sh` waits for the 200 itself. It needs `bash` and `curl` only, and takes `PAUSE=1` to
stop between sections.

## What demo.sh shows

Four acts, about twenty seconds. Each loads a different dairy and asks the same question:
**can 22 tankers collect everyone's milk before it goes off?**

| Act | Dairy | Answer |
|---|--|---|
| 1 | A normal morning, 22 °C | Not quite. 868 of 1,250 collection points. **The driver's shift runs out before the milk does.** |
| 2 | The same dairy, 35 °C evening | Badly short. 241 of 1,250. **Now the milk runs out first.** |
| 3 | The same evening, but leaving two hours later | Leaving two hours later would raise that from 19% to 37%, and costs nothing. |
| 4 | A thinly spread district | Ten villages no tanker can reach and get back from in time. Not a shortage — physics. |

Act 2 uses the same random seed as Act 1, so it is the same villages, the same farmers and the
same 22 tankers. **Only the weather is different.** That is what makes the comparison an
experiment rather than an anecdote.

## Tests and development mode

```bash
## Tests

```bash
docker compose up -d db     # three test classes need a database
./mvnw test
# Tests run: 187, Failures: 0, Errors: 0, Skipped: 0
```

Fourteen of the 187 boot a Spring context and expect PostgreSQL on localhost:5432.
Without it those fail and the other 173 still pass.

Interactive API docs at <http://localhost:8080/swagger-ui.html>.

---


# 2. Watching a whole session run

`demo.sh` shows the planner. The other half is a collection session actually
happening — twenty-two tankers on the road, farmers waiting, one of them asking
where the tanker is.

A real session takes three hours and happens twice a day, so it cannot be tested
by waiting for one. Instead, nothing in this system calls the system clock: every
class asks a `ClockProvider` what time it is, and under the `sim` profile that
clock is driven by the test. **A whole morning replays in about four minutes.**

The twenty-two simulated drivers post to the same HTTP endpoints a real driver's
phone would use and never touch the database, so this exercises the real
controllers and the real duplicate handling rather than a test shortcut.

**→ [docs/SIMULATION.md](docs/SIMULATION.md)** has the commands and the full
walkthrough: how to ask a farmer when his milk will be collected and watch the
answer change thirty seconds later, how to read a driver's stop list mid-round,
and what happens when all twenty-two phones lose signal for half an hour and then
reconnect at once. It also covers loading the three dairies by hand.

---

# 3. What I found

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

**Morning: the limit is how long a driver can work, not how fast the milk spoils.** The gap is
only 6%, and closing it costs nothing to buy. Raise the shift to 600 minutes and the milk
becomes the limit again instead of the driver — same 22 tankers, same villages, same weather:

| Shift | Mode | Points served |
|---|---|---|
| 300 minutes | Coverage optimisation | 868 |
| 600 minutes | **Full service** | **1,216** |

**348 more collection points every single morning, from a conversation about shift lengths.**
Every other fix for the morning — more tankers, more insulation, a second chilling plant —
costs money.

**Evening: the limit is spoilage.** The dairy needs more than twice what the fleet can give. No
change to shift lengths touches a shortfall that size; serving everyone at 35 °C would take
about 61 tankers. But the air cools roughly 3 °C an hour after the afternoon peak, so leaving at
**18:30 instead of 16:30** means collecting at 29 °C instead of 35 °C:

| | Leave 16:30 | Leave 18:30 |
|---|---|---|
| Temperature | 35 °C | 29 °C |
| Milk stays good for | 127 min | **193 min** |
| Points reached | 19% | **37%** |

**About 800,000 litres a year, and it costs nothing** — farmers milk two hours later. The
endpoint gets that number by planning the same dairy twice, once at each departure time, and
reporting both sides, so the claim can be checked rather than believed.

---

# 4. How it works

```
dataset YAML ─► seed the dairy ─► today's temperature ─► hold budget per tanker
     │
     ├─► solve each village internally      →  60 blocks, not 1,250 loose stops
     ├─► enough fleet-time?                 →  full service | coverage mode
     ├─► build routes: merge village blocks, each merge checked against 4 rules
     ├─► re-order each route for least time carrying milk, farthest village first
     ├─► assign tankers (riskiest route gets the longest budget) ─► publish plan
     └─► create trips (a frozen copy) ─► driver events ─► arrival times, alerts
```

Seven ideas hold the whole thing up. Each is a paragraph here and a full section in
**[docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md)**, which walks through every feature in plain
language — the maths, the files that do it, and the database tables it touches.

## The drive out is free

The tanker is empty on the way out, so nothing is spoiling yet — the clock starts at the
**first collection**, not at departure. That means the goal is "least time carrying milk", not
"least distance", and the two disagree. Three villages 8, 25 and 40 km from the plant:

| Order | Straight-line km | Time carrying milk |
|---|---|---|
| Nearest first: 8 → 25 → 40 → plant | 80 | **149 min** |
| Farthest first: 40 → 25 → 8 → plant | 80 | **83 min** |

Both drive exactly the same distance. Going out empty to the far end and collecting on the way
home removes **66 minutes of spoilage risk for no extra fuel**. A planner minimising distance
could not see this: on distance the two are the same route.

→ [Planning the routes](docs/HOW-IT-WORKS.md#feature-4--planning-the-routes)

## How long milk lasts depends on the weather

Bacteria in raw milk grow about twice as fast for every 10 °C rise, so the budget roughly halves
for every 10 °C hotter the day is. That is the whole model:

| Outside air | 18 °C | 22 °C | 27 °C | 30 °C | 35 °C | 39 °C |
|---|---|---|---|---|---|---|
| Plain tanker | 414 min | 313 min | 222 min | 180 min | **127 min** | 96 min |
| Insulated | 480 (ceiling) | 475 min | 336 min | 273 min | **193 min** | 146 min |

Insulation is modelled as the air being **6 °C cooler**, not as a multiplier — a jacket slows
the milk warming towards the air around it, and because the formula is exponential that works
out at about 1.5× on its own. The multiplier falls out of the physics rather than being picked.

→ [How long milk lasts](docs/HOW-IT-WORKS.md#feature-3--how-long-milk-lasts)

## How distances and travel times are worked out

No maps API — that needs an account key, and the project has to run on a clean machine. Instead:
the **Haversine formula** for the straight-line distance between two coordinates, then
**× 1.35** because roads wander around hills and go via the bridge, then divided by a speed that
depends on the leg — 15 km/h in village lanes, 26 on connecting roads, 34 on the district road —
and adjusted for the time of day, because roads at 5 AM are empty and roads at 5 PM are not.

Haversine rather than the simpler spherical law of cosines, because that one loses precision on
**short** distances — and collection points inside a village are 200 to 600 metres apart, so
short distances are the normal case here.

A 10.4 km straight line becomes 14 km of road: 21.5 minutes in the morning, 27.4 in the evening.

→ [Working out distances](docs/HOW-IT-WORKS.md#feature-1--working-out-distances) ·
[Working out travel time](docs/HOW-IT-WORKS.md#feature-2--working-out-travel-time)

## Villages are solved first

Collection points inside a village are 200–600 m apart — a minute or two of driving. Villages
are 8–15 minutes apart. So the problem is not "arrange 1,250 stops"; it is "arrange about 20
stops inside each village, then assign 60 village blocks to 22 tankers". Those are different
problems, and it is why this scales: the expensive part sees 60 things, not 1,250.

→ [Planning the routes](docs/HOW-IT-WORKS.md#feature-4--planning-the-routes)

## Trips copy their plan

When today's trips are created, each stop is **copied** from the plan rather than pointing at
it. So a plan republished at 05:30 changes nothing under a driver halfway through their run.
Without the copy, a mid-session republish silently rewrites the stop list of every tanker
already out.

→ [Making today's trips](docs/HOW-IT-WORKS.md#feature-5--making-todays-trips)

## One database index handles drivers losing signal

**The problem.** A driver's phone goes dark for thirty minutes in a valley, still recording
arrivals and collections with their real times. When the signal returns it uploads everything at
once — including events that *did* get through before the signal dropped, because the phone
cannot know which ones landed.

**The solution.** Every event carries a phone-generated `client_event_id` with a unique index on
it. A batch is sorted by the phone's own timestamps, applied in that order, and inserted with
`ON CONFLICT (client_event_id) DO NOTHING`. Duplicates are counted and dropped.

The obvious alternative — catch the constraint violation and count it — does not work:
PostgreSQL aborts the whole transaction on a failed statement, so every event queued behind the
duplicate would be lost with it.

→ [Recording what the driver did](docs/HOW-IT-WORKS.md#feature-6--recording-what-the-driver-did)

## When the fleet cannot serve everyone

The obvious rule is to serve whoever gives the most milk per minute. That is optimal on paper
and it would destroy the cooperative: the villages at the far end of the district are always the
least efficient choice, so they would be dropped every single hot day, their milk would spoil in
their own cans, and they would leave within a month.

So a village that has been waiting climbs the priority list, and the longer it has waited the
faster it climbs. On top of that is a hard rule: **no collection point is skipped more than
three sessions in a row.** At three, it is served before anything else is even considered. That
is a promise the dairy can actually make — and it is where the guaranteed date in a farmer's
answer comes from.

→ [Answering the farmer](docs/HOW-IT-WORKS.md#feature-10--answering-the-farmer)

---

# 5. Assumptions

The brief left a lot open. Every number below is a choice. **[ASSUMPTIONS.md](ASSUMPTIONS.md)**
has the full reasoning for each; this is the short version, and it labels each one either
**grounded** (there is a published figure behind it) or **a guess** (there is not).

## What a collection point is

Most points serve one farmer, about 12% serve two — roughly 1,250 points for 1,408 farmers. The
brief says "some collection points serve two farmers", so points and farmers are separate
things: the tanker visits a **point**, the milk is recorded against a **farmer**. Routing over
farmers instead would mean two stops at the same coordinates.

## Spoilage

| | Value | |
|---|---|---|
| Milk at 30 °C | 180 min | Industry rule of thumb. **A guess.** |
| Growth doubles every | 10 °C | Standard food science. **Grounded.** |
| Insulation worth | 6 °C cooler | Produces the ~1.5× that insulated tankers are said to buy. **A guess.** |
| Floor / ceiling | 60 / 480 min | A bad thermometer reading of 50 °C should not put every trip into alarm; a cold morning should not suggest leaving milk out for twelve hours. |

The clock starts at the **first collection**, not at departure — the tanker is empty on the way
out.

## Travel time

| | Value | |
|---|---|---|
| Roads vs straight line | ×1.35 | Published rural road studies say 1.2–1.5. **A guess for mixed terrain.** |
| Under 2 km | 15 km/h | Village lanes, cattle, people walking. **A guess.** |
| 2–10 km | 26 km/h | Connecting roads. **A guess.** |
| Over 10 km | 34 km/h | District road. A loaded tanker, not a car. **A guess.** |
| Morning / evening | ×1.15 / ×0.90 | Empty roads at 5 AM, market traffic at 5 PM. **A guess.** |

Six extra minutes per leg in the evening, across eight legs, is 48 minutes — **in the session
that already has the least time.**

## Fairness — a business decision, not a technical one

When the fleet cannot reach everyone, serving whoever gives the most milk per minute is
mathematically optimal and would destroy the cooperative. So waiting raises a village's
priority, and nothing is skipped more than three sessions running. Every farmer is collected
from at least once every two days.

## Operational

| | |
|---|---|
| Sessions | 05:00 and 16:30, both configurable |
| Morning / evening milk | 60 / 40 split |
| Daily volume varies | ±15%, so capacity is only planned to 95% |
| Stopping costs | 2 minutes, plus 0.35 per farmer at that point |
| Safety buffer | 20 minutes — travel times are estimates, and a route booked to arrive with two minutes to spare fails on the first wet morning |
| Alerts | Warning at 80% of the budget, critical at 95% |

**Every tunable number is a row in the `solver_parameter` table** and can be changed without a
redeploy.

---

# 6. What I did not build, and why

| Not built | Why |
|---|---|
| Real authentication | A role-header stub is in place and the real design is written down. A half-built security layer is worse than an honest placeholder. |
| A real map or traffic API | Needs an account key, which breaks "runs on a clean machine". `TravelTimeProvider` is the swap point and an OSRM implementation sits behind a profile. |
| SMS or IVR to farmers | A delivery channel. It has no bearing on whether the routing or the tracking is correct. Notifications are logged instead. |
| The consolidation advisory | Designed and documented — merging collection points a short walk apart would free real fleet time — but cut for time. |
| Partial fill and ejection chains | Each worth a few points of coverage, and each adds a class of bug: a village silently half-served, a route left corrupt by an abandoned swap. |
| An ops screen or a driver app | This is a backend brief. |
| Payments and milk pricing | A separate problem entirely. |
| Message queues, microservices | 22 tankers reporting every 30 seconds is under one write per second. The difficulty here is the domain, so that is where the effort went. |
| Choosing between multiple plants | The schema supports more than one plant, because the "divert" option needs somewhere to divert to. Optimising which plant each route serves is a whole extra dimension. |
| Predicting tomorrow's volumes | Needs history the system does not have yet. The variance data is the foundation for it. |
| A data retention policy | Nothing is ever deleted. At real scale the GPS ping table would want monthly partitioning. |

---

# 7. Known limitations

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
`COMPLETED` and alerts fire at the right thresholds. It cannot test whether the travel model is
accurate, because I wrote both the model and the simulator, and they would agree with each other
whatever the truth was.

**The warning alert fires too early.** It triggers when a tanker is projected to use 80% of its
hold budget. But routes are planned close to their limit by design — a typical morning route is
booked at **220 minutes of a 313-minute budget, about 70%** — so a 14% overrun is enough to
cross the line. In the simulated morning the first warning fires six minutes after the fleet
leaves, and twelve trips raise one. An alert board that cries wolf is one nobody reads. The
threshold is a single row in `solver_parameter` and should be tuned against real rejection data
rather than left at my guess.

**The deadline can tighten if the weather gets hotter mid-trip, but nothing ever makes it.**
Temperature comes from a table holding one figure per month per session, so it cannot change
during a run. The logic is written and tested, but only the `spoilage-crisis` scenario drives it,
by passing the change in by hand. A weather feed or a thermometer on the tanker would fix that.

---

# 8. If I had two more weeks

- **Wire a real routing service and calibrate it against GPS traces.** Every other number is
  downstream of the travel model, so this comes first.
- **Run in shadow mode** alongside the existing paper routes for a fortnight, and compare
  predicted against actual arrivals before trusting a plan.
- **Calibrate the hold window against real rejection data.** Every intake record already stores
  how old the oldest milk was, which is exactly what is needed to replace my guessed 180 minutes
  with a measured figure.
- **Build the driver's Android app** with proper offline sync. The server side of it is done.
- **Finish the consolidation advisory** — find collection points a short walk apart and price
  what merging them would free up.
- **Move plan generation to a background job.** Above about 5,000 collection points a
  synchronous request stops being reasonable.

---

# 9. Where things are

```
src/main/java/com/dairy/milkroute/
├── config/       the clock, solver parameters, scheduling
├── controller/   HTTP endpoints only — thin, no business logic
├── domain/       plain Java, no Spring, tested with no container
│                 geo/ spoilage/ routing/ tracking/ trip/ mitigation/ advisory/
├── service/      Spring beans: orchestration and transactions
├── repository/ entity/ dto/ enums/ error/ security/
└── simulation/   virtual clock, virtual drivers, scenario runner

src/main/resources/
├── db/migration/  V1..V5 — Flyway owns the schema, Hibernate only validates it
├── datasets/      baseline, heat-crisis, sparse-district, reference (solver parameters)
└── scenarios/     happy-morning, spoilage-crisis
```

| Document | What it is |
|---|---|
| **[docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md)** | Every feature in plain language: the maths, the files, the tables |
| **[docs/SIMULATION.md](docs/SIMULATION.md)** | Replaying a whole session, and what to look at while it runs |
| [ASSUMPTIONS.md](ASSUMPTIONS.md) | Every number I chose, and why |
| [docs/02-schema.md](docs/02-schema.md) | The 20 tables and the reasoning behind each |
| [docs/03-algorithms.md](docs/03-algorithms.md) | The routing and spoilage code in detail |
| [docs/05-edge-cases.md](docs/05-edge-cases.md) | What happens when things go wrong |

**The system clock is called in exactly one place**, which is what makes the simulation possible
at all; retrofitting it later would mean touching every class. **And nothing in `domain/` knows
Spring exists** — the solver, spoilage model and ETA calculator are plain Java, built with
`new`. Both are worth checking rather than believing:

```bash
grep -rn "Instant\.now()" src/main/java
# config/SystemClock.java:22:        return Instant.now();
# simulation/VirtualClock.java:13:   * ... {@code Instant.now()} ...   ← a comment, not a call

grep -rn "org.springframework" src/main/java/com/dairy/milkroute/domain | wc -l
# 0
```
