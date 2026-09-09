# MilkRoute — every feature, explained simply

For each feature: what it does, which files do it, exactly how the maths
works, and which database tables it touches.

---

# Contents

1. Feature 0 — Making up a dairy (the seeder)
2. Feature 1 — Working out distances (Haversine)
3. Feature 2 — Working out travel time
4. Feature 3 — Working out how long milk lasts
5. Feature 4 — Planning the routes
6. Feature 5 — Making today's trips
7. Feature 6 — Recording what the driver did
8. Feature 7 — Working out where the tanker is
9. Feature 8 — Working out when it will arrive (the ETA)
10. Feature 9 — Watching the milk clock
11. Feature 10 — Answering the farmer
12. Feature 11 — The advice ("leave two hours later")
13. Feature 12 — The simulation
14. The whole database in one table

---

# Feature 0 — Making up a dairy

## What it does

The database starts empty. Somebody has to invent 60 villages, 1,250
collection points, 1,408 farmers and 22 tankers. That's the seeder.

## Files

| File | Job |
|---|---|
| `dto/DatasetConfig.java` | The ~20 numbers that describe a dairy |
| `dto/Range.java` | A min/max pair, e.g. "14 to 30 points per village" |
| `service/DatasetLoader.java` | Reads the YAML file |
| `service/SeedService.java` | Actually creates the rows |
| `service/SeedRunner.java` | Runs it at startup if the database is empty |
| `service/ReseedService.java` | Wipes and rebuilds on demand |
| `resources/datasets/baseline.yaml` | The normal dairy |
| `resources/datasets/heat-crisis.yaml` | Same dairy, hot evening |
| `resources/datasets/sparse-district.yaml` | Villages spread much further |
| `resources/datasets/reference.yaml` | 24 temperature rows + 20 solver parameters |

## How villages get placed — the interesting part

The lazy way is random dots:

```java
lat = plantLat + random();
lng = plantLng + random();
```

That looks like confetti. Real villages sit **along roads**, radiating out
from the town, dense near it and thin at the edges.

So the seeder picks 7 bearings (compass directions) and walks outward along
each one, dropping villages:

```
                ● ●   ●
           ●        \   |
    ● ● ●    \       \  |      ●
 ● ─────────── PLANT ──────────  ●  ●
    ●  ●    /        |  \
          ●          ●    ●  ●
```

Two touches make it look real:

**Uneven angles.** Real roads don't leave a town at perfect 51° intervals, so
the bearings get nudged: 15, 62, 105, 158 rather than 0, 51, 103, 154.

**Wobble.** Each village gets a few degrees of random offset, so it's a loose
line rather than a ruler.

**Gaps grow with distance.** Villages cluster near the town and spread out
further away — because that's how settlement works.

Nearest village 4 km, farthest 43 km.

## Farmers, and the shared-point rule

```java
int farmersHere = (random() < 0.12) ? 2 : 1;
```

12% of points get two farmers. 1,250 × 1.12 ≈ 1,408.

**That's the brief's "some collection points serve two farmers", turned into
data.**

Then for each point:

```
service_minutes = 2.0 + 0.35 × farmerCount
```

Stopping costs about 2 minutes regardless (park, open the valve, paperwork),
plus a bit per farmer.

## Everything comes from the YAML

```yaml
villageCount: 60
corridorCount: 7
villageDistanceKm: { min: 4, max: 45 }
pointsPerVillage: { min: 14, max: 30 }
targetFarmerCount: 1400
twoFarmerPointRatio: 0.12
tankerCount: 22
capacityMix: [4000]
insulatedCount: 6
```

**No number is written into the Java.** That's why you can swap in a
200-village config with zero code changes.

## The fixed seed

```java
Random rng = new Random(config.seed());   // 88213
```

Same config always produces the identical dairy. Non-negotiable — tests assert
specific numbers, and a demo must behave the same in the interview as at home.

## Tables it fills

`village` · `collection_point` · `farmer` · `plant` · `tanker` · `driver` ·
`temperature_profile` · `solver_parameter`

---

# Feature 1 — Working out distances

## What it does

Answers "how far apart are these two places?" from their latitude and
longitude.

## Files

`domain/geo/GeoPoint.java` — a record with two methods.

## The maths

```java
public double haversineKm(GeoPoint other) {
    double lat1 = Math.toRadians(lat);
    double lat2 = Math.toRadians(other.lat);
    double dLat = lat2 - lat1;
    double dLng = Math.toRadians(other.lng - lng);

    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
             + Math.cos(lat1) * Math.cos(lat2)
             * Math.sin(dLng / 2) * Math.sin(dLng / 2);

    return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
}
```

### Why you can't just use Pythagoras

Earth is round. "Walk 16 km east" doesn't mean "add a fixed amount to
longitude".

At the equator, one degree of longitude is about 111 km. Near the North Pole
it's a few metres — the lines converge. So how much longitude changes depends
on your latitude.

That's what `Math.cos(lat1) * Math.cos(lat2)` is doing: shrinking the
east-west part as you move away from the equator.

### Why Haversine specifically

There's a simpler formula — the **spherical law of cosines**. It gives the
same answer for long distances but **loses precision on short ones**, because
it takes the arccosine of a number very close to 1, and floating-point
arithmetic can't tell 0.9999999 from 1.0000000 reliably.

Your collection points sit **200 to 600 metres apart**. So short distances are
the common case here, not the edge case. Haversine uses a half-angle sine
formulation that stays accurate down to metres.

**The sentence:** *"Haversine rather than the law of cosines, which loses
precision on short distances — and my points are 200 to 600 metres apart, so
short distances are the normal case."*

### Earth radius

```java
EARTH_RADIUS_KM = 6371.0088
```

The IUGG mean radius. *"The choice matters less than using one value
everywhere — distances get compared against each other far more often than
against a map."*

## The other method — `project`

The **opposite** question: *"start here, go 62° for 16 km — where do I end
up?"*

```java
public GeoPoint project(double bearingDegrees, double km) {
    double angular = km / EARTH_RADIUS_KM;     // distance as an angle
    ...
    double lat2 = Math.asin(Math.sin(lat1) * Math.cos(angular)
            + Math.cos(lat1) * Math.sin(angular) * Math.cos(bearing));

    double lng2 = lng1 + Math.atan2(...);

    double normalisedLng = (Math.toDegrees(lng2) + 540) % 360 - 180;
}
```

**Bearing** is just a compass direction: 0 = north, 90 = east, 180 = south,
270 = west.

**`km / EARTH_RADIUS_KM`** converts a distance into an angle. Travelling
6,371 km along the surface is exactly 1 radian of arc.

**The `+ 540 % 360 - 180`** wraps longitude back into the −180…180 range, so a
corridor crossing the antimeridian stays valid.

**Used only by the seeder**, to place villages along corridors.

## Why no PostGIS

PostGIS is a Postgres extension that does this in the database. It would work,
but it's another thing to install — and the brief says the project must run on
a clean machine.

*"Distance is computed in Java rather than the database, which is what lets
the clean-machine story stay `postgres:16-alpine` and nothing else."*

## Tables

None. Pure computation.

---

# Feature 2 — Working out travel time

## What it does

Answers "how long does it take a tanker to drive from A to B?"

## Files

| File | Job |
|---|---|
| `domain/geo/TravelTimeProvider.java` | The interface — the swap point |
| `domain/geo/HaversineTravelTime.java` | The real implementation |
| `domain/geo/TravelParameters.java` | The six numbers |
| `domain/geo/TravelMatrix.java` | All pairs, precomputed |
| `domain/geo/TravelMatrixCache.java` | So it's computed once |
| `config/TravelTimeFactory.java` | Builds one per planning run |

## The four steps

### Step 1 — straight-line distance

Haversine, from Feature 1.

### Step 2 — roads wander

```java
double roadKm = straightLineKm * 1.35;
```

A tanker can't fly. Roads curve around hills, follow field boundaries, go via
the bridge rather than across the river.

**Where 1.35 comes from:** published studies of rural road networks put the
road-to-straight-line ratio between 1.2 and 1.5. Flat grid areas near 1.2,
hilly winding areas near 1.5. Rural Maharashtra is a mix.

**Be honest that it's a guess.** It lives in `solver_parameter`, and every
trip records predicted-versus-actual so it can be calibrated with real data.

### Step 3 — speed depends on the road

```java
double speed = roadKm < 2  ? 15      // village lanes
             : roadKm < 10 ? 26      // connecting roads
             :               34;     // district road
```

These are **loaded tankers on rural Indian roads**, not cars on a motorway.
A short hop means narrow lanes, cattle, people walking. A long one means an
actual road.

### Step 4 — time of day

```java
speed *= (session == MORNING) ? 1.15 : 0.90;
```

At 5 AM the roads are empty. At 5 PM there's market traffic and school
children.

## Worked example

Two villages 10.4 km apart in a straight line:

```
Road distance:  10.4 × 1.35 = 14.0 km

Morning:  34 × 1.15 = 39 km/h  →  14.0 / 39 × 60 = 21.5 minutes
Evening:  34 × 0.90 = 31 km/h  →  14.0 / 31 × 60 = 27.4 minutes
```

**Six minutes longer per leg in the evening.** Over eight village hops that's
48 extra minutes — **in the session that already has the least spoilage
budget.** The evening is squeezed from both ends.

## Why the interface exists

```java
public interface TravelTimeProvider {
    Duration between(GeoPoint a, GeoPoint b, Session session);
    TravelMatrix matrix(List<GeoPoint> points, Session session);
}
```

Two implementations: the Haversine one (default, no API key, runs anywhere)
and an OSRM one behind a config flag.

**Why no Google Maps:** it needs an account key, and the moment your project
needs a key it stops running on a clean machine.

**Why write the second implementation at all:** anyone can say "you could plug
in real maps later". Writing it proves the design supports it.

**Session is a parameter, not state**, because the same pair of points is a
different journey at 05:00 and 17:00 — and a provider you had to rebuild per
session would be easy to use with the wrong one.

## The matrix and its cache

The planner asks for the same distances **thousands** of times per run.
Computing each on demand would be far too slow. So all pairs are computed once
and held in an array.

**The cache key is a hash of the point set AND the travel parameters AND the
session.** Not just the points:

> *"Retuning the circuity factor and re-planning is a demonstration the
> parameter table exists for, and it would be worthless if a matrix computed
> under the old factor were handed back afterwards."*

`TravelMatrix` also **sorts and deduplicates its points in the constructor** —
otherwise two callers with the same points in different orders would share a
cache entry whose indices mean different things.

## Tables

Reads `solver_parameter` for the six numbers.

---

# Feature 3 — How long milk lasts

## What it does

Answers "how many minutes before the plant rejects this milk?"

**Not a fixed number.** It depends on temperature.

## Files

`domain/spoilage/SpoilageCalculator.java` — about 30 lines, and everything in
the system depends on it.

`service/AmbientTemperatureService.java` — where the temperature comes from.

## The science, simply

Bacteria in milk multiply. **Their growth rate roughly doubles for every 10 °C
rise.** That's the Q10 rule, standard food science.

So milk at 20 °C lasts about twice as long as milk at 30 °C.

## The formula

```java
double effectiveC = ambientC - (insulated ? 6.0 : 0.0);

double budget = 180 * Math.pow(2.0, (30.0 - effectiveC) / 10.0);

return (int) Math.round(clamp(budget));
```

### Reading the exponent

`(30 − T) / 10` means **"how many 10-degree steps below 30 °C are we?"**

```
At 30 °C:  (30 − 30)/10 = 0    →  2^0 = 1      →  180 × 1    = 180 min
At 20 °C:  (30 − 20)/10 = 1    →  2^1 = 2      →  180 × 2    = 360 min
At 40 °C:  (30 − 40)/10 = −1   →  2^-1 = 0.5   →  180 × 0.5  =  90 min
```

Ten degrees cooler, twice the time. Ten warmer, half.

**The `/10` is what makes it per-ten-degrees rather than per-degree.** Without
it, one degree would double the budget, which is wildly wrong.

**Why 30 °C as the reference:** it's the middle of the range you actually
operate in (18–39 °C), and 180 minutes at 30 °C is the industry rule of thumb.

### Insulation as a temperature offset

The naive version would be:

```java
if (insulated) budget *= 1.5;   // where does 1.5 come from?
```

Arbitrary, and dimensionally meaningless.

What insulation physically does is **slow the rate at which milk warms toward
the air around it**. So the milk behaves as though the air were cooler:

```java
double effectiveC = ambientC - 6.0;
```

And because the formula is exponential, a 6 °C offset is worth about 1.5× on
its own:

```
2^(6/10) = 1.52
```

**So you get the multiplier as a consequence of the physics, not as a magic
number.** Same result, much better reason.

### The two clamps

```java
return Math.min(480, Math.max(60, minutes));
```

**Ceiling 480 (8 hours).** Without it, 10 °C gives 720 minutes — twelve hours.
Bacteriologically true, operationally useless. Nobody leaves milk in a tanker
all day: there's fat separation, the plant's intake window, the driver's
shift.

**Floor 60.** Without it, one bad sensor reading of 50 °C gives 45 minutes and
every trip goes into alarm.

> *"An unbounded model is an unfinished model."*

## The table

| Ambient | Plain tanker | Insulated |
|---|---|---|
| 18 °C | 414 min | 480 (capped) |
| 22 °C | 313 min | 475 min |
| 27 °C | 222 min | 336 min |
| 30 °C | 180 min | 273 min |
| 35 °C | 127 min | 193 min |
| 39 °C | 96 min | 146 min |

**The morning budget is about 2.5× the evening budget.** That single fact is
why the two sessions need separate plans, and why the evening is the hard one.

## Where the temperature comes from

`temperature_profile` — 24 rows, one per month per session.

Plus a shift model, because the table can say what an August evening is like
but not what 18:30 is like as against 16:30:

```java
public double shift(double base, Session session, double hoursLater) {
    double perHour = params.get("ambientShiftCPerHour");   // 3.0
    return session == EVENING
            ? base - perHour * hoursLater      // evenings cool
            : base + perHour * hoursLater;     // mornings warm
}
```

**One line, and the sign matters.** Getting it backwards would have the
advisory recommending the one change guaranteed to lose more milk.

## Tables

`temperature_profile` · `solver_parameter`

---

# Feature 4 — Planning the routes

## What it does

Decides which tanker visits which villages, in what order.

## The pipeline

```
POST /api/v1/plans
      │
PlanController                       parse, delegate (5 lines)
      │
PlanningService                      snapshot the world
      │
      ├─ VillageSolver × 60          1,250 points → 60 blocks
      ├─ FeasibilityAssessor         can 22 tankers cover this?
      ├─ RoutePlanner                merge villages into routes
      │     ├─ SequenceOptimiser        order them
      │     └─ ConstraintChecker        are they legal?
      ├─ TankerAssigner              riskiest route ← best tanker
      │
      └─ persist
```

## Files

| File | Job |
|---|---|
| `controller/PlanController.java` | 6 endpoints |
| `service/PlanningService.java` | Orchestration |
| `domain/routing/PlanningContext.java` | The snapshot of the world |
| `domain/routing/VillageSolver.java` | Phase 1 |
| `domain/routing/VillageBlock.java` | One solved village |
| `domain/routing/FeasibilityAssessor.java` | Possible or not? |
| `domain/routing/RoutePlanner.java` | Phase 2 — the merge loop |
| `domain/routing/PartialRoute.java` | A candidate route |
| `domain/routing/SequenceOptimiser.java` | Farthest-first + 2-opt |
| `domain/routing/ConstraintChecker.java` | Runs the four rules |
| `domain/routing/SpoilageConstraint.java` | Rule 1 — the important one |
| `domain/routing/CapacityConstraint.java` | Rule 2 |
| `domain/routing/ShiftLengthConstraint.java` | Rule 3 |
| `domain/routing/PlantWindowConstraint.java` | Rule 4 |
| `domain/routing/TankerAssigner.java` | Pair routes with tankers |
| `domain/routing/PlanResult.java` | The output |

---

## Step 1 — Solve each village on its own

**File:** `VillageSolver.java`

Points inside a village are 200–600 m apart, so driving between them takes
1–2 minutes. Order them into a short loop.

**Nearest-neighbour** for a starting order — begin at the point closest to the
plant, then always take the nearest unvisited one.

*Why start from the plant side:* the first and last points become the block's
**entry** and **exit**, and the legs between villages are measured from those.

**Then 2-opt** to clean up. Nearest-neighbour reliably strands a point or two
and doubles back for them; reversing a run of points is exactly the move that
undoes that.

Each village comes out as a `VillageBlock`:

```
village · ordered points · internal minutes · litres · entry · exit
```

**Why this matters so much:** the problem shrinks from *"arrange 1,250
points"* to *"arrange 60 blocks"*. That's why the system scales — the work
grows with the number of villages, not the number of points.

**Splitting.** If a village takes longer to work than the milk survives, it's
cut in two **along its wider axis** — so each half is a place a tanker can
work, not a scattering across the whole village.

A single point that still exceeds budget is returned unsplit. It's genuinely
unreachable, and the system records `UNREACHABLE_WITHIN_HOLD` rather than
pretending otherwise.

**One detail:** `hotMinutesRequired` **excludes unload time**, because
unloading happens once per *route*, not once per village. Including it would
inflate the requirement by an unload per village and make a feasible dairy
look impossible.

---

## Step 2 — Is this even possible?

**File:** `FeasibilityAssessor.java`

Two sums and a comparison:

```java
required  = Σ block.hotMinutesRequired()
available = Σ min( holdBudget(tanker), driverShift )
mode = required <= available × 0.92 ? FULL_SERVICE : COVERAGE_OPTIMISATION
```

### The `min()` is the important bit

> *"A tanker is bounded by two things, not one. Milk spoils, and drivers go
> home. Whichever runs out first is the real limit."*

Counting spoilage alone reported a comfortable morning at 22 °C — 313 minutes
of budget against a 300-minute shift — while the roster made full service
impossible. The plan then quietly served 868 of 1,250 points under a
FULL_SERVICE heading.

**That's your morning finding.**

### The 0.92 margin

Both sides are estimates. Committing the fleet to its last available minute
means any route running slightly long turns into rejected milk.

---

## Step 3 — Merge villages into routes

**File:** `RoutePlanner.java`

Start with **one route per village** — 60 routes, obviously absurd, but
trivially valid. Then work down a ranked list of merges, keeping any the
constraints accept.

### The ranking is not plain savings

Textbook Clarke-Wright ranks merges by minutes saved. That maximises litres
per tanker-minute and, on a short day, **quietly serves the same efficient
villages every time**.

The far end of the corridor is always the least efficient choice, so it's
always the one dropped.

> *"A cooperative that stops collecting from the same eight villages every hot
> week does not stay a cooperative."*

So the ranking is:

```
litres / marginalHotMinutes  ×  (1 + daysSinceLastServed) ^ 1.6
```

**First factor — efficiency.** On a normal day this decides almost everything,
because every village has been served recently and the second factor is near 1.

**Second factor — equity.** At exponent 1.6, three days of neglect multiplies
a village's claim by about 7.5. Enough to overcome a real efficiency gap
rather than merely nudge the order.

**Two cases sit outside the score:**

- A village at **three consecutive skips** is merged before anything is ranked
  at all. Fairness there is a rule, not a preference.
- A village with **no history** is treated as never served and ordered ahead
  of everything that has one.

**A village is as neglected as its most neglected point.** Averaging would let
a village with one long-ignored point look fine because its neighbours are
fresh.

### `marginalHotMinutes`

The real extra on-milk time that appending a village costs:

```java
to.internalMinutes()
  + legMinutes(from.exit → to.entry)
  + legMinutes(to.exit → plant)
  − legMinutes(from.exit → plant)      // the run home we no longer make
```

**Hot minutes rather than distance**, because hot time is the budget that
actually runs out. *A village further away but on the way home can be cheaper
than a nearer one that adds a detour.*

### The order of operations matters

```java
PartialRoute merged = optimiser.optimise(concat(a, b), ctx);   // order FIRST
if (leastCapableTankerThatCanRun(merged, ctx) == null) continue;  // then check
```

> *"Concatenating two routes produces an arbitrary village order, and an
> arbitrary order can spend an hour of hot time that a farthest-first order
> would not. Checking the concatenation as-built would reject merges that are
> perfectly feasible once ordered properly."*

### The fleet-fit gate

Asking whether a merged route can be run by *some* tanker isn't enough. The
loop can build more insulated-only routes than the six insulated tankers
available — and the assigner then correctly refuses them.

So before accepting a merge, it compares the sorted hot times of the top
`fleetSize` routes against the sorted hold budgets.

### There's no separate coverage planner

On a short day **the same loop runs**. The ranking decides who's served first,
and what the fleet can't crew comes back in `unassignedRoutes` with the plan
marked `COVERAGE_OPTIMISATION`.

> *"A second algorithm for the hard days is a second thing to get wrong — and
> the hard days are the ones that matter."*

---

## Step 4 — Order the villages in each route

**File:** `SequenceOptimiser.java`

### The free deadhead

A tanker leaving the plant is **empty**. Nothing is spoiling. The clock hasn't
started — it starts when the first milk goes in.

Three villages at 8, 25 and 40 km:

```
Nearest-first:   plant → A(8) → B(25) → C(40) → plant
                 the near village's milk sits through the whole trip,
                 including the 40 km drive home

Farthest-first:  plant → C(40) → B(25) → A(8) → plant
                 drive out empty, collect on the way home,
                 and home is now a short drive
```

**Same distance driven. The milk spends over 30 minutes less in the tanker.**

### How the code does it

```java
// 1. seed: sort villages by distance from the plant, DESCENDING
// 2. 2-opt: reverse segments, keep whatever lowers HOT TIME
if (hot < bestHot - 0.01) { best = candidate; }
```

**The one change from textbook 2-opt is the objective.** Classic 2-opt
minimises distance. This minimises hot time.

> *"Distance charges every leg equally. Hot time charges nothing for the empty
> run out, so an ordering that drives slightly further can deliver fresher
> milk. That substitution is what makes this a spoilage optimiser rather than
> a travelling-salesman solver."*

**Why 2-opt and not something cleverer:** fifteen villages maximum per route.
2-opt converges in milliseconds and you can explain it. A metaheuristic would
cost build time and a much harder explanation for maybe 2% more.

**`MIN_GAIN_MINUTES = 0.01`** stops floating-point noise making the loop
oscillate between two orderings forever.

**Returns a new route** — the savings loop may still discard the candidate, so
the operands must be undamaged.

**Doesn't check constraints**, deliberately. That's the planner's job, and in
that order.

---

## Step 5 — The four rules

**Files:** `ConstraintChecker.java` + four constraint classes

### Why four classes rather than four `if`s

- Each is testable without the others — a spoilage test needs no plant hours
  and no driver
- A rejection **names the rule** that caused it, which is what lets the
  feasibility report explain itself
- A fifth rule is a new class rather than an edit to the solver

That's the **Specification pattern**.

### Rule 1 — `SpoilageConstraint`

```
hot = Σ village internal work
    + Σ inter-village hops
    + run home from the last village
    + unload wait
```

**The plant → first village leg is absent.** That's the free deadhead,
expressed in code.

```java
if (hot > budget - 20) return fail(...);
```

**Safety buffer 20 minutes.** You're planning against estimated travel times.
A route booked to arrive with two minutes to spare fails the first wet
morning.

### Rule 2 — `CapacityConstraint`

```java
if (litres > capacity × 0.95) return fail(...);
```

**Headroom 5%** because daily volume varies by roughly a sixth.

**Rarely binds.** Five tankers would hold the whole morning's volume — the
dairy runs 22 because the district is spread out and the window is short.
*The constraint is time, not capacity.* Still checked, because a plan that
silently overfills a tanker is worse than one that admits it can't.

### Rule 3 — `ShiftLengthConstraint`

**The same calculation as spoilage, PLUS the outbound leg.**

> *"The tanker being empty means no milk is ageing. It does not mean the
> driver is not driving."*

**Read these two files side by side. That's the entire design in ten
seconds.**

### Rule 4 — `PlantWindowConstraint`

Will it arrive while the gate is open?

The **only** constraint that depends on *when* the session starts rather than
only how long it takes — which is also why shifting the evening departure
changes what's feasible.

---

## Step 6 — Give out the tankers

**File:** `TankerAssigner.java`

```java
routes sorted by hot time, descending
tankers sorted by hold budget, descending
zip them
```

**Riskiest route gets the tanker that holds milk longest.**

> *"Insulation is worth about six degrees, which on a hot evening is sixty-odd
> minutes of budget. Spending that on the route that already has the most
> slack, while the three-hour route runs on a plain tanker, is how a plan that
> looked fine on paper loses a load."*

### Every pairing is re-checked

Not belt and braces:

> *"The merge loop only established that SOME tanker could run this route. The
> one it actually gets may be weaker. Assigning without re-checking silently
> produces routes with negative slack — a route booked to arrive after its own
> milk has spoiled, which is the exact failure this system exists to prevent."*

**Two independent cursors.** A route that can't use the tanker it's offered
doesn't consume it: the biggest budget goes to the riskiest route first, and
if even that won't do, no smaller tanker will.

**Leftovers are returned unassigned, not dropped.**

---

## Tables this feature uses

**Reads:** `village` · `collection_point` · `tanker` · `driver` · `plant` ·
`temperature_profile` · `solver_parameter` · `point_coverage_state`

**Writes:** `route_plan` · `route` · `route_stop` · `plan_exclusion`

```sql
route_plan   id · version · session · source · mode · status
             planned_temp_c · effective_from · generated_at
             generation_ms · feasibility (jsonb)

route        id · plan_id · label · tanker_id · driver_id · plant_id
             planned_depart_at
             est_hot_minutes · hold_budget_minutes · slack_minutes
             est_volume_litres · est_distance_km · stop_count

route_stop   id · route_id · seq · collection_point_id
             planned_arrival_at (TIME) · planned_litres
             leg_minutes · leg_km
```

**The most important constraint in the schema:**

```sql
CREATE UNIQUE INDEX uq_one_published_per_session
    ON route_plan(session) WHERE status = 'PUBLISHED';
```

At most one published morning plan and one published evening plan.

*If two morning plans were live, trip creation would pick one arbitrarily and
half the fleet would run the wrong routes.* Enforced by Postgres, so no bug in
service code can produce it — not a race, not a double-clicked button.

---

# Feature 5 — Making today's trips

## What it does

Turns the published plan into trips the fleet will actually run.

## Files

`service/TripCreationService.java` · `controller/TripController.java`

## The important thing it does: **copy**

Every `route_stop` becomes a `trip_stop`. **From that moment the trip never
reads the plan again.**

> *"Ops publishes a new plan at 05:30 while twenty-two tankers are on the
> road. Without the copy, the stop list changes under a driver who is halfway
> through it, and the farmer endpoint starts promising visits from a plan
> nobody is running."*

There's a second reason too: the plan stores a **`TIME`** (5:52 AM — a daily
template), while a trip needs a **`TIMESTAMPTZ`** (5:52 AM *on 15 October*).
You need the conversion regardless.

## Reconciling with reality

A plan generated last night assigned tankers and drivers who may since have
gone into maintenance or called in sick.

**Substitute** — but only with a tanker of **at least equal capacity and
insulation**. Swapping an insulated tanker for a plain one would silently undo
the assigner's decision on the riskiest route.

**Nothing available → create the trip anyway, as `BLOCKED`, with its stops.**

> *"Creating nothing would be the quiet failure: a village simply not
> collected from, and no record of why."*

## Business date

Passed in, **never derived from the clock** — an evening session crossing
midnight stays filed under the day it started.

## Safe to run twice

```sql
UNIQUE (route_id, business_date, session)
```

## Tables

**Reads:** `route_plan` · `route` · `route_stop` · `tanker` · `driver`
**Writes:** `trip` · `trip_stop`

---

# Feature 6 — Recording what the driver did

## What it does

Takes what the phone reports and turns it into trip state and milk records.

## Files

| File | Job |
|---|---|
| `controller/DriverController.java` | The endpoints |
| `dto/request/DriverEventBatchRequest.java` | The batch shape |
| `service/EventIngestionService.java` | **The idempotent write** |
| `domain/trip/EventReplayer.java` | Applies each event |
| `domain/trip/TripStateMachine.java` | Which transitions are legal |
| `service/CollectionRecordingService.java` | Milk rows |

## The problem: drivers lose signal

A tanker goes into a valley and the phone is dark for thirty minutes. It keeps
recording — arrivals, collections, departures — and stores everything locally
with the times things actually happened.

When signal returns, it sends the whole backlog. **Including records the
server may already have**, because it can't know which of its earlier sends
got through.

## The solution: one database constraint

```sql
client_event_id UUID NOT NULL UNIQUE
```

The phone generates a UUID for every record **before** it tries to send. The
unique index rejects anything already there.

No acknowledgement protocol. No retry negotiation. One index.

## How the write works

```java
inOrder.sort(comparing(DriverEventRequest::clientTs));   // ← order

for (var request : inOrder) {
    int inserted = eventRepo.insertIfAbsent(request.clientEventId(), ...);
    if (inserted == 0) { duplicates++; continue; }
    replayer.apply(trip, stops, ...);
    applied++;
}
```

### Why `ON CONFLICT DO NOTHING` and not catch-the-exception

This is the detail worth learning.

The obvious approach is:

```java
try { save(event); }
catch (DataIntegrityViolationException e) { duplicates++; }
```

**That doesn't work.** Postgres aborts the **whole transaction** on a failed
statement. So after the first duplicate, every remaining event in the batch
fails too — twenty-nine good events lost to skip one.

`INSERT ... ON CONFLICT (client_event_id) DO NOTHING` pushes the conflict into
the insert. The same index is still the arbiter, the transaction stays usable,
and it's still correct if two copies of the same batch arrive simultaneously —
which a pre-check with `existsByClientEventId` would not be.

### Sort before replay

Order comes from **when things happened** (`client_ts`), not when they landed.
A reconnect batch arrives in whatever order the phone flushed it.

### One transaction for the batch

Either it all lands or none does. A partially applied batch would leave a trip
in a state no sequence of real events could produce.

### Clock drift

A phone more than an hour **ahead** is rejected with 422 — a wrong
`first_collection_at` produces a confidently wrong deadline, which is worse
than none.

A phone **behind** is accepted, because it's indistinguishable from legitimate
buffering.

## The replayer — generous input, strict records

| Situation | What happens | Why |
|---|---|---|
| `COLLECTED` with no arrival | Synthesise the arrival at `clientTs − serviceMinutes` | Losing a milk record is worse than an inferred arrival time |
| Driver at stop 9, system thinks 7 | Mark 7 and 8 `SEQUENCE_SKIP`, let him continue | He's there and knows something the system doesn't |
| Illegal transition from a stale event | Ignore, don't throw | A replayed batch contains events the trip has moved past |
| `DEPARTED_STOP` with nothing left | `→ RETURNING` | There's no driver event meaning "heading back" — a phone has no button for it |

## Where the spoilage clock starts

```java
if (trip.getFirstCollectionAt() == null) {
    trip.setFirstCollectionAt(event.clientTs());
    trip.setSpoilageDeadlineAt(
        event.clientTs().plusMinutes(trip.getHoldBudgetMinutes()));
}
```

**Written once, guarded by the null check, and the deadline is set in the same
block so the two can't diverge.**

That's the moment everything else is measured against.

## Milk records

**One row per farmer per visit.** `UNIQUE (trip_stop_id, farmer_id)`.

A point with two farmers gets **one stop and two collection rows**. That's the
brief's shared-point requirement, finally resolved.

**Corrections void and re-insert**, never update in place:

- Same litres again → it's a retry, write nothing
- Different litres → log loudly and keep the original, because that's a
  disagreement about money a person must resolve

## Tables

**Writes:** `driver_event` · `trip_stop` (updates) · `collection` · `trip`
(updates)

```sql
driver_event  id · client_event_id UUID UNIQUE · trip_id · trip_stop_id
              event_type · client_ts · server_ts · payload (jsonb)

collection    id · trip_stop_id · trip_id · farmer_id
              litres CHECK (0..500) · collected_at
              voided · void_reason
              UNIQUE (trip_stop_id, farmer_id)
```

**Both timestamps stored.** `client_ts` is when it happened; `server_ts` is
when we heard about it. A 40-minute gap is a dead zone, not an error.

---

# Feature 7 — Where is the tanker?

## Files

`domain/tracking/PositionResolver.java` · `service/PingService.java`

## The rule

> **Events decide which leg it's on. Pings only say how far along that leg.**

*A tanker that drives past a collection point has not collected from it. The
road goes past the point, the driver didn't stop, the farmer is still holding
his can.*

Only `ARRIVED_AT_STOP` moves `trip.current_seq`.

**If GPS advanced progress**, the board would show stop nine done, the farmer
endpoint would say collected, and the milk would still be at the roadside.
Easy to write, and very hard to notice.

## The calculation

```java
int fromSeq = trip.getCurrentSeq();               // set by events
GeoPoint legStart = locationOf(stops, fromSeq, plant);
GeoPoint legEnd   = locationOf(stops, fromSeq + 1, plant);

double legKm = legStart.haversineKm(legEnd);
double progress = Math.clamp(legStart.haversineKm(ping) / legKm, 0.0, 1.0);
```

**The clamp is the important line.** A wandering GPS fix can't push the tanker
past a stop nobody has arrived at.

**Past the last stop**, `locationOf` falls back to the plant — the tanker is
on its way home, so that's the right destination rather than an error.

**The sentence:** *"Position is a presentation detail; progress is a fact
about milk."*

## Pings have no idempotency key

Deliberately. **A duplicate GPS reading is harmless.** Events change state;
pings are observations. Adding UUIDs would double the write volume of the
busiest table for nothing.

## Tables

`tanker_ping` (writes) · `trip.last_lat` / `last_lng` / `last_ping_at`
(updates)

---

# Feature 8 — When will it arrive? (the ETA)

## Files

`domain/tracking/EtaCalculator.java` · `domain/tracking/EtaEstimate.java` ·
`service/TripTrackingService.java`

## The calculation, step by step

### Step 1 — finish the leg you're on

```java
GeoPoint legEnd = locationOf(stops, fromSeq + 1, plant);
double legMinutes = minutesBetween(position.at(), legEnd, trip);
Duration remaining = ofMinutes(legMinutes);
```

**Measured from where the tanker actually is**, not from the stop it left.
That's the difference between *"twelve minutes to the next stop"* and *"twelve
minutes from a village he left eight minutes ago"*.

### Step 2 — add every stop between here and the target

```java
for (int seq = fromSeq + 1; seq < targetSeq; seq++) {
    TripStop stop = stopAt(stops, seq);
    if (stop == null || isPast(stop)) continue;      // ← skipped stops cost nothing

    remaining = remaining
            .plus(serviceTimeOf(stop))
            .plus(ofMinutes(minutesBetween(
                    locationOf(stops, seq, plant),
                    locationOf(stops, seq + 1, plant),
                    trip)));
}
```

**`isPast(stop)` skips COLLECTED, SKIPPED and DEFERRED stops.** So a farmer
with no milk today makes **everyone downstream arrive earlier**. A naive
"stops remaining × average" would move it later.

### Step 3 — scale by how the driver is actually going

```java
double factor = observedDelayFactor(trip, stops);
Duration scaled = scale(remaining, factor);
```

```java
public double observedDelayFactor(Trip trip, List<TripStop> stops) {
    List<TripStop> done = completed(stops);
    if (done.size() < 3) return 1.0;               // not enough signal yet

    long actual  = between(done.getFirst().getArrivedAt(),
                           done.getLast().getArrivedAt()).toSeconds();
    long planned = between(done.getFirst().getPlannedArrivalAt(),
                           done.getLast().getPlannedArrivalAt()).toSeconds();

    return Math.clamp((double) actual / planned, 0.7, 2.0);
}
```

**A driver 15% slow through eight stops will probably be 15% slow through the
next eight.** Ten lines, and it's the difference between a naive estimate and
a useful one.

**Measured between the first and last COMPLETED stops**, so it needs no
departure time and can't be thrown by a late start the driver has already made
up.

**Clamped 0.7–2.0** so one weird stop doesn't blow up every downstream
estimate.

### Step 4 — decide how much to trust it

```java
if (lastPingAt == null)                       return LOST;
if (age > 15 minutes)                         return LOST;
if (age > 5 minutes)                          return LOW;
return stopsCompleted >= 3 ? HIGH : MEDIUM;
```

**Both staleness and progress gate it.**

> *"A fresh ping early in a trip still has no pace to extrapolate from, so
> it's worth a window rather than a time. A stale ping means the tanker is
> somewhere other than where the map says, whatever the pace was."*

## Two entry points

```java
toPlant(trip, stops, plant, now)              // for the spoilage monitor
toStop(trip, stops, plant, targetSeq, now)    // for the farmer endpoint
```

Same machinery, different target. A target beyond the last stop means the
plant, which is how the run home gets costed.

## Tables

**Reads:** `trip` · `trip_stop` · `plant`. Writes nothing — pure calculation.

---

# Feature 9 — Watching the milk clock

## Files

`service/SpoilageMonitorService.java` · `service/AlertService.java` ·
`service/MitigationService.java` · `domain/mitigation/*`

## The sweep

Every 60 seconds, over every trip on the road:

```java
// 1. RATCHET — can the budget shrink?
int current = spoilage.holdBudgetMinutes(currentAmbient, insulated);
if (current < trip.getHoldBudgetMinutes()) {
    trip.setHoldBudgetMinutes(current);
    trip.setSpoilageDeadlineAt(firstCollectionAt.plusMinutes(current));
    alerts.raise(DEADLINE_TIGHTENED, ...);
}

// 2. PROJECT — how old will the milk be on arrival?
EtaEstimate eta = tracking.refresh(trip);
long projectedAgeMin = between(firstCollectionAt, eta.at()).toMinutes();
double utilisation = projectedAgeMin / (double) trip.getHoldBudgetMinutes();

// 3. LEVEL
RiskLevel level = utilisation >= 0.95 ? CRITICAL
                : utilisation >= 0.80 ? WARNING
                :                       OK;

// 4. TRACKING
if (minutesSinceLastPing > 15) level = max(level, LOST);

// 5. ANNOUNCE — only on a change
if (level != trip.getRiskLevel()) { ... }
```

## The one-way ratchet

**The budget can only ever shrink.**

```java
if (current < trip.getHoldBudgetMinutes())   // ← the only write
```

> *"Milk that already spent an hour at 37 °C did not become fresher when a
> cloud passed. Bacterial damage is cumulative. Letting the budget grow would
> silently clear an alert on a trip that is genuinely in trouble."*

**And the comparison is against the trip's *current* budget, not its starting
one** — so once cut to 127, a later reading of 313 simply isn't smaller.
That's what makes it hold across sweeps rather than just within one.

**Proven by a mutation test:** changing `<` to `!=` — a two-way adjustment —
fails three of the eight ratchet tests.

## Tracking lost keeps counting

The projection still runs. *"The projection is still being made; nobody can
see whether it is still true."*

The milk is still being watched even though the tanker isn't visible.

## Alerts fire on the transition, not the condition

```java
if (level != trip.getRiskLevel()) { announce(...); }
```

Sixty sweeps an hour would otherwise produce sixty identical alerts. **An
alert board nobody reads is worse than no alert board.**

Belt and braces at the database level too:

```sql
CREATE UNIQUE INDEX uq_alert_open ON alert(dedupe_key) WHERE resolved_at IS NULL;
```

Key format: `SPOILAGE:{tripId}:{severity}`. So repeated WARNINGs collapse into
one row, but **a WARNING escalating to CRITICAL creates a genuinely new
alert** — because that's new information a dispatcher must see.

## Mitigations — generated, never executed

> *"Each option decides whose milk gets collected and whose is left at the
> roadside, and the information that actually settles that is not in this
> database: which village complained last week, whether the plant manager will
> hold the gate, how new the driver is."*

`ContinueAsPlanned` **always** appears with its cost stated.

> *"Showing only the cautious options would be a recommendation wearing the
> costume of a menu."*

**And when nothing works, it says so:**

```json
{
  "anySaves": false,
  "summary": "No option gets this load to the plant inside its budget.
              Choose which milk to save, and tell the plant what is coming."
}
```

> *"A system that always has a fix is lying, and the first time a dispatcher
> catches it inventing one they stop believing the rest."*

**Execution uses optimistic locking** on `trip.version` — two dispatchers
acting at once, the second gets a clean 409.

## Tables

**Reads:** `trip` · `trip_stop` · `tanker` · `temperature_profile` ·
`solver_parameter`
**Writes:** `trip` (budget, deadline, risk level) · `alert`

---

# Feature 10 — Answering the farmer

**This is one of the four problems from the brief, and it's the endpoint
everything else exists to serve.**

## Files

`controller/FarmerController.java` · `service/FarmerQueryService.java` ·
`dto/response/FarmerStatusResponse.java` · `enums/FarmerStatus.java`

## The flow

```
GET /api/v1/farmers/F-01044/tanker-status?session=MORNING
   │
1. find the farmer                    → 404 if unknown (generic, no leak)
2. any trips today?                   → NOT_SCHEDULED
3. is his point on one?               → NOT_SERVED_TODAY (+ reason + guaranteed date)
4. is the trip aborted?               → TRIP_ABORTED     ← checked FIRST
5. switch on the stop's status        → COLLECTED / SKIPPED / DEFERRED / on the way
6. if on the way, get the ETA         → EtaCalculator
7. gate the ETA by confidence
8. build the sentence
```

## Why step 4 comes before step 5

**There is always an ETA in the database. There is not always a tanker
coming.**

Getting that order wrong gives a farmer a precise arrival time for a tanker
that was recalled two hours ago. That's the worst possible failure of this
endpoint.

## The confidence gate — the most careful line in the system

```java
Instant quotable = eta.confidence() == HIGH || eta.confidence() == MEDIUM
        ? eta.at() : null;
```

**On LOW and LOST there is no ETA in the response at all.** Not null with a
flag. Absent.

> *"Nothing downstream can render a precise time from a quarter-hour-old
> position, because there is no number to render."*

And the phrasing follows:

| Confidence | What the farmer hears |
|---|---|
| HIGH | "The tanker is 7 stops away, expected around 6:41 am." |
| MEDIUM | "…expected between 6:31 and 6:51 am." |
| LOW | "…on its way. We do not have a reliable time for you yet." |
| LOST | "We have lost contact. Please call the dairy office." |

> *"Quote a farmer 6:41 and turn up at 7:15 and he never believes another
> number you give him."*

## The nine statuses

| Status | Message |
|---|---|
| `NOT_SCHEDULED` | "No morning collection has been dispatched yet today." |
| `SCHEDULED` | "The tanker has not left the plant yet. You are stop 31." |
| `EN_ROUTE` | "The tanker is 7 stops away, expected around 6:41 am." |
| `ARRIVING_NEXT` | "The tanker is on its way to you now." |
| `COLLECTED` | "Collected at 5:39 am, 24.4 litres." |
| `SKIPPED` | "The tanker came but did not collect here today (no milk)." |
| `DEFERRED` | "Postponed today. Scheduled for the next run." |
| `NOT_SERVED_TODAY` | "…not on today's round (coverage limit). Guaranteed by 2026-10-16." |
| `TRIP_ABORTED` | "Today's round was stopped. Please call the office." |

**The `message` field is the whole point.** A call-centre operator or an IVR
reads that one line aloud. They don't assemble a sentence from six fields.

## Where `guaranteedBy` comes from

The **three-strike rule**: nothing is skipped more than three sessions
running. At three, the village is merged before anything else is ranked.

So the system can promise a date, and keep it.

## Tables

`farmer` · `collection_point` · `trip` · `trip_stop` · `collection` ·
`plan_exclusion` · `point_coverage_state`

---

# Feature 11 — The advice

## Files

`service/TimingAdvisoryService.java` · `domain/advisory/TimingAdvisory.java` ·
`controller/AdvisoryController.java`

## What it does

Plans the same dairy **twice** — at the current departure and at one two hours
later — and reports both sides.

```java
PlanResult current = planningService.planWithoutPersisting(
        session, date, currentDepartAt, currentAmbient);

PlanResult shifted = planningService.planWithoutPersisting(
        session, date, shiftedDepartAt, shiftedAmbient);
```

**Neither is persisted.**

## Why twice, rather than estimating

> *"Coverage does not scale linearly with hold budget — an extra hour lets
> some routes absorb a whole extra village and others none at all — so the
> only honest way to say what leaving later would achieve is to work it out
> properly."*

## The output

```json
{
  "current":  { "departAt": "16:30", "ambientC": 35, "holdBudgetMin": 127, "coveragePct": 19.28 },
  "proposed": { "departAt": "18:30", "ambientC": 29, "holdBudgetMin": 193, "coveragePct": 37.28 },
  "coverageGainPct": 18.0,
  "litresRecoveredPerYear": 802686,
  "costToTheDairy": "None. Farmers milk 2 hours later.",
  "summary": "Depart 18:30 instead of 16:30: 29.0 C instead of 35.0 C, 193 minutes
              of hold budget instead of 127, and coverage rises from 19% to 37%.
              About 802,686 litres a year. Cost: None."
}
```

**Quotes the plain tanker's budget**, because that's what most of the fleet is
and the insulated figure would overstate the starting position.

**`worthDoing()` is false below a 0.5-point gain:**

> *"A shift that gains nothing is not a finding, and reporting it as one
> trains ops to ignore the advisory."*

## Why it's the most valuable thing in the system

> *"Every other lever costs money. More tankers cost money. Insulation costs
> money. A local chilling unit costs a great deal of money. This one is a
> decision, and the only reason a dairy would not already have made it is that
> nobody had put the numbers side by side."*

## Tables

Reads everything the planner reads. Writes nothing.

---

# Feature 12 — The simulation

## Files

`simulation/VirtualClock.java` · `simulation/VirtualDriver.java` ·
`simulation/SimulationEngine.java` · `simulation/SimulationTransport.java` ·
`simulation/ScenarioLoader.java` · `controller/SimulationController.java` ·
`resources/scenarios/*.yaml`

## The problem

You can't test a twice-daily system by waiting twice a day.

## The trick — one interface

```java
public interface ClockProvider { Instant now(); }
```

`SystemClock` (`@Profile("!sim")`) returns the real clock.
`VirtualClock` (`@Profile("sim")`) returns whatever the engine has set.

**`Instant.now()` appears exactly once in the entire codebase**, inside
`SystemClock`. Verify it:

```bash
grep -rn "Instant.now()" src/main/java
```

That rule went into the first commit, because retrofitting it later means
touching every class.

## The engine

```
advance the clock 30 seconds
tick every driver — has this one arrived? collected? departed?
sweep the spoilage monitor
recompute ETAs
repeat
```

> *"The loop is deliberately dull. All the interesting behaviour belongs to
> the code being simulated rather than to the simulator — a clever simulator
> would be a second implementation of the system, and passing it would prove
> only that the two agreed."*

**The monitor is swept from the loop, not on its `@Scheduled` timer**, because
under `sim` the scheduled sweep still runs on wall-clock time, which has
nothing to do with simulated time.

## The drivers use the real API

> *"It never touches a repository, never opens a transaction, never writes a
> row. Going through `POST /drivers/trips/{id}/events` exercises request
> validation, the clock-drift guard, the ingestion transaction, the unique
> index, the replayer and the state machine — the same path a phone in a
> village uses."*

**That's what makes the demo prove something.** It's not testing a fixture.

## The signal-loss window

Every driver goes dark for 30 minutes. Events are buffered, not sent.

On reconnect it sends the backlog **plus the last three events the server
already acknowledged**:

> *"…because a real phone cannot know which of its sends got through before
> the signal dropped, and resending is the correct, safe behaviour."*

```
[RECONNECT] trip 7 sent 15 events (12 buffered + 3 acked) → 12 applied, 3 duplicates
```

Twenty-two of those, every run. **That's the whole offline story,
demonstrated rather than asserted.**

## What it does NOT prove — say this before you're asked

`VirtualDriver` uses **fixed six-minute legs and three-minute stops**, not the
planned ones from `trip_stop.leg_minutes`. A 40 km hop and a 300 m one take
the same simulated time.

So it exercises the **plumbing** — ingestion, dedupe, state machine, monitor —
but not the **timing**.

> *"The simulation proves the logic: constraints hold, records don't
> duplicate, alerts fire at the right moment. It cannot prove the travel model
> is right, because I wrote both the model and the simulator."*

---

# The whole database, in one table

20 tables, 5 migrations. Flyway owns the schema; Hibernate runs
`ddl-auto: validate` and never generates DDL.

| Table | Feature | Key columns |
|---|---|---|
| `village` | Seeder, planner | code · name · lat · lng · active |
| `collection_point` | Seeder, planner | village_id · lat · lng · **service_minutes** · avg_morning_litres · avg_evening_litres |
| `farmer` | Seeder, farmer query | code · phone · **collection_point_id** (N:1) · animal_count |
| `plant` | Planner, trips | lat · lng · unload_minutes · opens_at · closes_at |
| `tanker` | Planner, trips | reg_no · capacity_litres · **insulated** · status |
| `driver` | Planner, trips | code · phone · **max_shift_min** |
| `temperature_profile` | Spoilage | month_no · session · ambient_c |
| `solver_parameter` | Everything | key · value (20 rows) |
| `route_plan` | Planner | version · session · mode · **status** · planned_temp_c · feasibility (jsonb) |
| `route` | Planner | plan_id · tanker_id · driver_id · **est_hot_minutes** · **hold_budget_minutes** · **slack_minutes** |
| `route_stop` | Planner | route_id · seq · collection_point_id · planned_arrival_at (**TIME**) · leg_minutes |
| `point_coverage_state` | Fairness | collection_point_id · last_served_date · **consecutive_skips** |
| `plan_exclusion` | Coverage | plan_id · collection_point_id · reason · litres_forgone |
| `trip` | Running | route_id · business_date · session · status · **first_collection_at** · **spoilage_deadline_at** · **hold_budget_minutes** · current_seq · risk_level · **version** |
| `trip_stop` | Running | trip_id · seq · planned_arrival_at (**TIMESTAMPTZ**) · status · arrived_at · actual_litres |
| `collection` | Money | trip_stop_id · **farmer_id** · litres · voided |
| `driver_event` | Offline | **client_event_id UUID UNIQUE** · client_ts · server_ts · payload |
| `tanker_ping` | Tracking | trip_id · lat · lng · recorded_at |
| `alert` | Monitoring | trip_id · alert_type · severity · **dedupe_key** · resolved_at |
| `intake_record` | Plant | trip_id · accepted_litres · rejected_litres · **oldest_milk_min** |

## The five constraints that do real work

| Constraint | Prevents |
|---|---|
| `uq_one_published_per_session` (partial unique) | Two live plans → half the fleet running wrong routes |
| `client_event_id UUID UNIQUE` | Duplicate milk records from an offline reconnect |
| `UNIQUE (trip_stop_id, farmer_id)` | A double-tap creating two rows for one farmer |
| `uq_alert_open` (partial unique) | Forty identical alerts and a board nobody reads |
| `UNIQUE (route_id, business_date, session)` | Trip creation running twice |

## The one column that will matter most in a year

```sql
intake_record.oldest_milk_min
```

Your entire spoilage model rests on one guessed number: 180 minutes at 30 °C.

After a few hundred sessions:

```sql
SELECT width_bucket(oldest_milk_min, 60, 240, 12) AS age_bucket,
       count(*) AS trips,
       avg(rejected_litres / NULLIF(received_litres, 0)) AS rejection_rate
FROM intake_record GROUP BY 1 ORDER BY 1;
```

That tells you whether the real threshold is 165 or 195. Then you change one
row in `solver_parameter` and every plan gets better.

**That's the answer to "how do you know your model is right?"** — you don't at
first, and this column is how you find out.
