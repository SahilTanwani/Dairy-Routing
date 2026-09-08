# 03 — Algorithms

All classes here live in `domain/` and are **plain Java with no Spring annotations**.
They are constructed with `new`, take dependencies via constructor, and are unit-testable
without a Spring context.

---

## 1. SpoilageCalculator

`domain/spoilage/SpoilageCalculator.java`

```java
public final class SpoilageCalculator {
    private final double baseAt30C;
    private final double q10;
    private final double insulationOffsetC;
    private final double minHold;
    private final double maxHold;

    public SpoilageCalculator(double baseAt30C, double q10,
                              double insulationOffsetC,
                              double minHold, double maxHold) { ... }

    public int holdBudgetMinutes(double ambientC, boolean insulated) {
        double effective = ambientC - (insulated ? insulationOffsetC : 0);
        double budget = baseAt30C * Math.pow(q10, (30.0 - effective) / 10.0);
        return (int) Math.round(Math.min(maxHold, Math.max(minHold, budget)));
    }
}
```

**Test this hardest.** Everything downstream is wrong if it is wrong.

| Input | Expected |
|---|---|
| (18.0, false) | 414 |
| (22.0, false) | 313 |
| (27.0, false) | 222 |
| (30.0, false) | 180 |
| (35.0, false) | 127 |
| (39.0, false) | 96 |
| (35.0, true) | 193 |
| (22.0, true) | 475 (just under the ceiling) |
| (10.0, false) | 480 (ceiling) |
| (55.0, false) | 60 (floor) |

**Why the clamps.** Without the ceiling, a 10 °C morning gives 12 hours — bacteriologically
true but ignores every other reason not to leave milk in a tanker all day. Without the
floor, a sensor reading 50 °C puts every trip into alarm. A model that is not bounded is
not finished.

---

## 2. TravelTimeProvider

`domain/geo/TravelTimeProvider.java`

```java
public interface TravelTimeProvider {
    Duration between(GeoPoint a, GeoPoint b, Session session);
    TravelMatrix matrix(List<GeoPoint> points, Session session);
}
```

`domain/geo/HaversineTravelTime.java`

```java
public final class HaversineTravelTime implements TravelTimeProvider {
    private final SolverParams p;

    @Override
    public Duration between(GeoPoint a, GeoPoint b, Session session) {
        double straightKm = haversineKm(a, b);
        double roadKm = straightKm * p.circuityFactor();          // 1.35

        double speed = roadKm < 2  ? p.speedUnder2Km()            // 15
                     : roadKm < 10 ? p.speed2To10Km()             // 26
                     :               p.speedOver10Km();           // 34

        speed *= (session == Session.MORNING)
               ? p.morningSpeedFactor()                           // 1.15
               : p.eveningSpeedFactor();                          // 0.90

        return Duration.ofSeconds((long) (roadKm / speed * 3600));
    }
}
```

**Test case from the spec:** two points 10.4 km apart in a straight line →
14.0 km road → 21.5 min morning, 27.4 min evening (±0.5 min tolerance).

`domain/geo/TravelMatrix.java` — precompute all pairs once, hold in a `double[][]`.
Cache keyed by SHA-256 of the sorted point set, so adding or moving a village
invalidates automatically. Persist to a `travel_matrix_cache` table so restarts are
instant.

`domain/geo/OsrmTravelTime.java` — a stub behind `@Profile("osrm")`. Not wired into the
demo. **Its existence proves the seam is real, not decorative.**

---

## 3. Hot time — the definition everything depends on

```
hotTime = Σ (village internal traverse minutes)
        + Σ (inter-village hop minutes)
        + travel(last village exit → plant)
        + plant unload minutes
```

**The plant → first village leg is excluded.** The tanker is empty; no milk is aging.

Consequence: drive out empty to the farthest village and collect coming home.

```
Villages at 8, 25, 40 km.
Nearest-first:   plant → A → B → C → plant = 319 min hot
Farthest-first:  plant → C → B → A → plant = 265 min hot
Same distance. 54 minutes of risk removed.
```

---

## 4. ConstraintChecker

`domain/routing/ConstraintChecker.java` — **the class a reviewer will read most closely.**

Structured as four independent constraints so each is testable alone and a failure names
which one blocked a merge.

```java
public interface RouteConstraint {
    ConstraintResult check(PartialRoute r, Tanker t, PlanningContext ctx);
    String name();
}
```

**SpoilageConstraint**

```java
int budget = spoilage.holdBudgetMinutes(ctx.ambientTempC(), t.isInsulated());

double hot = 0;
List<VillageBlock> blocks = r.blocks();
for (int i = 0; i < blocks.size(); i++) {
    hot += blocks.get(i).internalMinutes();
    if (i < blocks.size() - 1)
        hot += travel(blocks.get(i).exit(), blocks.get(i + 1).entry());
}
hot += travel(r.lastBlock().exit(), ctx.plant().location());
hot += ctx.plant().unloadMinutes();
// NOTE: travel(plant → first block entry) is deliberately NOT counted

if (hot > budget - params.safetyBufferMin())      // buffer = 20
    return fail(SPOILAGE, hot, budget);
```

**CapacityConstraint**

```java
if (r.totalLitres() > t.capacityLitres() * params.capacityHeadroom())   // 0.95
    return fail(CAPACITY, r.totalLitres(), t.capacityLitres());
```

5% headroom because volume estimates carry ±15% daily variance.

**ShiftLengthConstraint**

```java
double shift = travel(ctx.plant().location(), r.firstBlock().entry())   // counted here
             + hot;
if (shift > ctx.driverMaxShiftMin())
    return fail(SHIFT_LENGTH, shift, ctx.driverMaxShiftMin());
```

The outbound leg is free for spoilage but not for the driver's working day.

**PlantWindowConstraint**

```java
if (!ctx.plant().acceptsAt(r.plantArrivalTime()))
    return fail(PLANT_WINDOW, r.plantArrivalTime(), null);
```

**Tests to write:** one per constraint, plus boundary cases — a route exactly at
`budget − buffer` passes, one minute over fails.

---

## 5. Two-phase routing

### Phase 1 — VillageSolver

`domain/routing/VillageSolver.java`

Points within a village are 200–600 m apart, so travel between them is 1–2 min.
Nearest-neighbour seed, then 2-opt. Sixty small problems, milliseconds each.

```java
public VillageBlock solve(Village v, List<CollectionPoint> points, Session s) {
    List<CollectionPoint> seq = nearestNeighbour(points);
    seq = twoOpt(seq, this::pathMinutes);

    return new VillageBlock(
        v, seq,
        pathMinutes(seq) + seq.stream().mapToDouble(CollectionPoint::serviceMinutes).sum(),
        seq.stream().mapToDouble(p -> expectedLitres(p, s)).sum(),
        seq.get(0),                     // entry
        seq.get(seq.size() - 1));       // exit
}
```

**Edge case:** if a block's internal time alone exceeds the hold budget, split it
geographically into two blocks and treat them independently.

**Why decompose at all.** The problem shrinks from "arrange 1,250 points" to "arrange 60
blocks." That is why the system scales — complexity grows with village count, not point
count.

### Phase 2 — RoutePlanner (full-service mode)

`domain/routing/RoutePlanner.java`

Clarke-Wright savings over village blocks.

```java
// Seed: one route per block
List<PartialRoute> routes = blocks.stream()
    .map(b -> PartialRoute.of(plant, b))
    .collect(toList());

// Savings: merging i and j saves the return from i and the outbound to j
double saving(Block i, Block j) {
    return travel(plant, i.entry())
         + travel(plant, j.entry())
         - travel(i.exit(), j.entry());
}
// 60 blocks → 1,770 pairs. Sort descending.

// Merge greedily where all four constraints pass
for (Saving sv : savings) {
    PartialRoute a = routeEndingWith(routes, sv.i());
    PartialRoute b = routeStartingWith(routes, sv.j());
    if (a == null || b == null || a == b) continue;

    PartialRoute merged = sequenceOptimiser.optimise(concat(a, b), plant);
    Tanker best = bestAvailableTanker(tankers, merged, ctx);
    if (best == null) continue;

    routes.remove(a); routes.remove(b); routes.add(merged);
}
```

Note the sequence is optimised **before** the constraint check, because a bad ordering
would reject a merge that is actually feasible.

### SequenceOptimiser

`domain/routing/SequenceOptimiser.java`

```java
// A. Seed farthest-first — near-optimal for hot time (see section 3)
seq.sort(comparingDouble(b -> -distance(plant, b.entry())));

// B. 2-opt with HOT TIME as the objective, not distance
//    Re-check constraints after every accepted move.

// C. Or-opt: relocate single blocks. Catches what 2-opt cannot reach.
//    (Cuttable if short on time — the farthest-first seed does most of the work.)
```

### TankerAssigner

```java
routes.sort(comparing(Route::hotMinutes).reversed());
tankers.sort(comparing(t -> spoilage.holdBudgetMinutes(ambient, t.isInsulated()))
             .reversed());
// pair off — riskiest route gets the largest budget (i.e. the insulated tankers)
```

### Objectives, in priority order

1. Zero hard-constraint violations
2. Maximise points served
3. Minimise tankers used
4. **Maximise the minimum slack across routes** — raise the floor, not the average
5. Minimise total distance

Objective 4 is unusual and worth explaining: nineteen comfortable routes plus one at 4
minutes of slack is worse than twenty routes at 30 minutes each, even at higher total
distance. The dairy cares about not losing a load, not about average efficiency.

---

## 6. Coverage mode

### When it triggers

`domain/routing/FeasibilityAssessor.java`

```java
double required  = blocks.stream().mapToDouble(VillageBlock::hotMinutesRequired).sum();
double available = fleet.stream()
    .mapToInt(t -> spoilage.holdBudgetMinutes(ambientC, t.isInsulated()))
    .sum();

return required <= available * params.feasibilityMargin()      // 0.92
     ? Mode.FULL_SERVICE
     : Mode.COVERAGE_OPTIMISATION;
```

This is the Team Orienteering Problem — a fleet, a time budget per vehicle, a prize per
node, and you cannot visit them all. Worth naming.

### The ranking, inside RoutePlanner

There is no `PointScorer` and no `CoveragePlanner`. The scoring lives in the merge
ordering of the one planner, in `RoutePlanner.candidates()`, and coverage mode is the same
loop degrading honestly rather than a second algorithm.

When choosing which village to merge next:

```java
score = litres / max(marginalHotMinutes, 0.5)
      * Math.pow(1 + daysSinceLastServed, equityExponent);   // 1.6
```

`marginalHotMinutes` is what appending this village actually costs the route it would join
— its internal work, the hop to reach it, and the change in the run home:

```java
to.internalMinutes()
    + travel(from.exit(), to.entry())
    + travel(to.exit(), plant)
    - travel(from.exit(), plant)
```

Hot minutes rather than distance, because hot time is the budget that runs out. A village
that is further away but on the way home can be cheaper than a nearer one that adds a
detour.

**Why the equity term.** Pure efficiency ranking serves the same villages every hot day.
The far end of the corridor is always the least efficient choice, so it is always the one
dropped — and a cooperative that stops collecting from the same eight villages every hot
week does not stay a cooperative. At an exponent of 1.6, three days of neglect multiplies a
village's claim by about 7.5, which is enough to overcome a real efficiency gap rather than
merely nudging the order.

**Two cases sit outside the score.**

```java
// merged before anything is ranked at all
worstSkips >= params.maxConsecutiveSkips()      // 3
```

A village at the skip limit stops competing. Fairness there is a rule, not a preference,
and it is what guarantees every farmer is served within two days — the source of
`guaranteedBy` in the farmer response.

A village with no coverage row at all is treated as never served and ordered ahead of
everything with a history, with efficiency deciding between them. On a freshly seeded dairy
that is every village, so the first plan is ordered purely on efficiency.

A village is as neglected as its most neglected point. Averaging would let a village with
one long-ignored point look fine because its neighbours are fresh.

### Coverage mode is the same loop

`FeasibilityAssessor` picks the mode; `RoutePlanner` runs either way. On a day the fleet
cannot serve everyone, the ranking decides who goes first, whatever the fleet cannot crew
comes back in `unassignedRoutes`, and the plan is stamped `COVERAGE_OPTIMISATION` because
that is what the arithmetic said — not because a different class produced it.

`PlanResult` then reports `pointsServed`, `pointsTotal`, `coveragePct`, `litresCollected`
and `litresTotal`, and every unserved point gets a `plan_exclusion` row with reason
`COVERAGE_LIMIT` and the litres it forgoes.

**Deliberately not built.** Both of these raise coverage a few points and both buy a class
of bug worth more than the points:

- **Partial fill** — taking some points of a village and leaving the rest. A village that is
  silently half-served is worse than one honestly skipped, because nobody gets an exclusion
  row and the farmers who were missed look served.
- **Ejection chains** — undoing an accepted merge to make room for a better one. The failure
  mode is a route left corrupt by an abandoned ejection, and the plan thrashing between
  near-equal alternatives makes the day-over-day diff unreadable.

---

## 7. Advisories

All four share a skeleton: find candidates → simulate impact → rank → summarise.

### TimingAdvisory — build this one first

Twenty minutes of work, best line in the demo, costs the dairy nothing.

```java
// Re-plan at a shifted departure time with the cooler temperature
double shiftedTemp = tempProvider.ambientC(date, session, shiftedDeparture);
PlanResult shifted = planner.plan(contextWith(shiftedTemp));

return new TimingAdvisory(
    current.departAt(), current.ambientC(), current.budgetMin(), current.coveragePct(),
    shiftedDeparture, shiftedTemp, shiftedBudget, shifted.coveragePct(),
    "None. Farmers milk two hours later.",
    litresRecoveredPerYear(current, shifted));
```

Expected on `heat-crisis`: 16:30 @ 35 °C, 127 min, 67% → 18:30 @ 29 °C, 194 min, 91%.

### ChillingUnitAdvisory (cuttable)

For villages flagged `UNREACHABLE_WITHIN_HOLD`, recommend a local cooler with farmer
count, litres per day, and coverage unlocked.

### InsulationAdvisory (cuttable)

Re-plan with N more tankers insulated, report the coverage delta. Recommend insulating
the tankers on the longest routes first.

---

## 8. Position and ETA

### PositionResolver

`domain/tracking/PositionResolver.java`

```java
// Events drive progress. Pings only refine position within the current leg.
int fromSeq = trip.getCurrentSeq();          // set by ARRIVED_AT_STOP events
GeoPoint legStart = fromSeq == 0 ? plant.location() : stopLocation(trip, fromSeq);
GeoPoint legEnd   = stopLocation(trip, fromSeq + 1);

double progress = clamp(dist(legStart, ping) / dist(legStart, legEnd), 0.0, 1.0);
```

**Why events and not pings drive progress:** a tanker driving past a point has not
collected from it. Only `ARRIVED_AT_STOP` advances the sequence.

### EtaCalculator

`domain/tracking/EtaCalculator.java`

```java
Duration remaining = remainderOfCurrentLeg(pos);

for (int s = pos.fromSeq() + 1; s < targetSeq; s++) {
    if (stop(trip, s).isSkippedOrDeferred()) continue;   // skipped stops shift ETA EARLIER
    remaining = remaining.plus(serviceTime(trip, s)).plus(legTravelTime(s));
}

remaining = scale(remaining, observedDelayFactor(trip));
return clock.now().plus(remaining);
```

```java
double observedDelayFactor(Trip t) {
    List<TripStop> done = completedStops(t);
    if (done.size() < 3) return 1.0;                    // not enough signal yet
    double raw = (double) actualElapsed(t).toSeconds()
               / plannedElapsed(done).toSeconds();
    return Math.min(2.0, Math.max(0.7, raw));           // clamp
}
```

A driver 15% slow through eight stops will likely be 15% slow through the next eight.
Ten lines, and it is the difference between a naive ETA and a useful one. The clamp stops
one weird stop blowing up every downstream estimate.

### Confidence — so we never lie to a farmer

| Condition | Confidence | Farmer hears |
|---|---|---|
| Ping < 5 min, ≥3 stops done | HIGH | "expected around 6:41 PM" |
| Ping < 5 min, <3 stops done | MEDIUM | "expected between 6:35 and 6:55 PM" |
| Ping 5–15 min | LOW | "last seen near Kolhewadi at 6:12 PM" |
| Ping > 15 min | LOST | "we have lost contact — please call the office" |

Quote a farmer 6:41 and turn up at 7:15 and he will never believe another number.

---

## 9. Spoilage monitor

`service/SpoilageMonitorService.java` — `@Scheduled(fixedDelay = 60_000)`

```java
for (Trip trip : activeTrips()) {
    if (trip.getFirstCollectionAt() == null) continue;    // still empty, no clock

    // ONE-WAY RATCHET: budget may shrink, never grow
    int current = spoilage.holdBudgetMinutes(currentTempC, tanker.isInsulated());
    if (current < trip.getHoldBudgetMinutes()) {
        trip.setHoldBudgetMinutes(current);
        trip.setSpoilageDeadlineAt(trip.getFirstCollectionAt().plusMinutes(current));
        alerts.raise(DEADLINE_TIGHTENED, trip,
                     "Ambient rose to " + currentTempC + " C; deadline moved earlier");
    }

    long projectedAge = between(trip.getFirstCollectionAt(), etaToPlant(trip)).toMinutes();
    double utilisation = (double) projectedAge / trip.getHoldBudgetMinutes();

    RiskLevel level = utilisation >= params.spoilageCriticalPct() ? CRITICAL   // 0.95
                    : utilisation >= params.spoilageWarnPct()     ? WARNING    // 0.80
                    :                                               OK;

    // Tracking loss is its own risk: keep counting on the PLANNED timeline
    if (minutesSinceLastPing(trip) > params.trackingLostMinutes()) {
        level = max(level, LOST);
    }

    if (level != trip.getRiskLevel()) {
        trip.setRiskLevel(level);
        if (level.isAlerting()) alerts.raise(SPOILAGE_RISK, trip, level, projectedAge);
    }
}
```

**Threshold reasoning.** WARNING at 80% gives ~35 min of notice on a 180-min budget —
enough time to act. CRITICAL at 95% is where options run out.

**When tracking is lost, the monitor keeps counting on the planned timeline.** The milk
is still being watched even though the tanker is not visible. This matters.

**Alert dedupe:** `dedupe_key = "SPOILAGE:" + tripId + ":" + severity`, with the partial
unique index on open alerts. WARNING → CRITICAL creates a new alert; repeated WARNINGs
do not.

---

## 10. Mitigations — generated, never executed

```java
public sealed interface Mitigation
        permits SkipRemaining, DivertToPlant, TransferLoad, ContinueAsPlanned {
    String action();
    double litresSaved();
    Instant projectedArrival();
    int projectedMilkAgeMin();
    MitigationResult execute(Trip trip, MitigationContext ctx);
}
```

Ranked by litres saved. A dispatcher picks one. Execution uses optimistic locking on
`trip.version`, so two dispatchers acting at once produces a clean 409.

**`ContinueAsPlanned` is shown deliberately**, with its risk spelled out, so the
dispatcher sees the real trade-off rather than only the "safe" options.

**If no mitigation saves the load, say so plainly.** A system that always offers a fix is
lying. That honesty is the point of the breakdown scenario.

---

## 11. Trip state machine

`domain/trip/TripStateMachine.java`

```java
private static final Map<TripStatus, Set<TripStatus>> ALLOWED = Map.of(
    SCHEDULED,   Set.of(IN_PROGRESS, ABORTED, BLOCKED),
    IN_PROGRESS, Set.of(RETURNING, BREAKDOWN, ABORTED),
    RETURNING,   Set.of(AT_PLANT, BREAKDOWN, ABORTED),
    AT_PLANT,    Set.of(COMPLETED),
    BREAKDOWN,   Set.of(RETURNING, ABORTED)
);

public void transition(Trip t, TripStatus target) {
    if (!ALLOWED.getOrDefault(t.getStatus(), Set.of()).contains(target))
        throw new IllegalTransitionException(t.getStatus(), target);
    t.setStatus(target);
}
```

One table, no `if` cascade. Prevents the invalid transitions that out-of-order offline
events would otherwise cause.

---

## 12. Event ingestion — the idempotency piece

`service/EventIngestionService.java`

```java
@Transactional
public IngestResult ingest(Long tripId, List<DriverEventRequest> batch) {
    batch.sort(comparing(DriverEventRequest::clientTs));   // out-of-order safe

    int applied = 0, duplicates = 0;
    for (var e : batch) {
        try {
            eventRepo.saveAndFlush(toEntity(e));           // client_event_id UNIQUE
            replayer.apply(trip, e);                       // via state machine
            applied++;
        } catch (DataIntegrityViolationException dup) {
            duplicates++;                                  // already have it, skip
        }
    }
    etaService.recompute(trip);
    return new IngestResult(applied, duplicates);
}
```

**Test:** send 30 events, resend 5 of them, assert 25 applied / 5 duplicates and no
double `collection` rows.

**EventReplayer edge case:** a `COLLECTED` with no prior `ARRIVED_AT_STOP` synthesises
the arrival at `client_ts − service_time` and logs a warning. Do not reject — the
driver's app may have dropped one event, and losing a milk record is worse.

---

## Design patterns used, and why

| Pattern | Where | Problem it solves |
|---|---|---|
| Strategy | `TravelTimeProvider`, `PlanningStrategy` | Swap Haversine/OSRM and full-service/coverage without touching callers |
| Specification | `RouteConstraint` | Each constraint independently testable; failure names which one blocked |
| State machine | `TripStateMachine` | Out-of-order offline events cannot produce nonsense states |
| Command | `Mitigation` sealed interface | Each fix describes and executes itself; adding a fourth is one class |
| Template method | `Advisory<T>` | Four advisories share find → simulate → rank → summarise |
| Observer | Spring `ApplicationEventPublisher` | Monitor publishes; alerting/notification/board listen independently |
| Facade | `GET /ops/board` | One call instead of four every two seconds |
| Repository | Spring Data | Why nothing is hardcoded — every count is a query result |

**Not used, deliberately:** Factory (constructors are fine), Singleton (Spring does it),
Decorator, Visitor. Adding patterns you cannot justify is worse than adding none.
