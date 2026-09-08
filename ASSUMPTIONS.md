# Assumptions

The brief is deliberately under-specified. This document records every gap I filled,
the number I chose, and why. Where a number is a guess, I say so.

---

## 1. The collection point model

**The brief says:** ~1,400 farmers, ~60 villages, and "some collection points serve
two farmers."

**What I assumed:** most collection points serve one farmer, and about 12% serve two.

| | Count |
|---|---|
| Single-farmer points | ~1,100 |
| Two-farmer points | ~150 |
| **Total points** | **~1,250** |
| **Total farmers** | **~1,400** |

That works out to about 21 collection points per village.

**Why this matters more than it looks.** Points within a village end up 200–600 m
apart, so driving between them takes 1–2 minutes, while hopping between villages takes
8–15 minutes. The problem is therefore not "arrange 1,250 scattered stops" — it is
"assign 60 village clusters to 22 tankers." That observation drives the whole
two-phase algorithm and is the reason the system scales.

**The alternative I rejected:** treating every farmer as a separate stop. 1,400 stops
across 22 tankers is 64 stops per route, which at ~3 min each plus travel exceeds any
plausible spoilage window. It is not a hard optimisation problem; it is an impossible
one. Real dairies use village collection societies for exactly this reason.

---

## 2. Milk spoilage is temperature-dependent, not a fixed window

**The brief says:** "Milk that sits in a tanker too long before it reaches the chilling
plant is rejected outright." It does not say how long.

**What I assumed:** the hold window is a function of ambient temperature, not a constant.

Bacterial growth in raw milk roughly doubles per 10 °C rise (the Q10 rule, standard
food science). So:

```
holdMinutes = baseAt30C × 2 ^ ((30 − effectiveTemp) / 10)
effectiveTemp = ambientC − (insulated ? 6.0 : 0)
```

With `baseAt30C = 180`:

| Ambient | Standard tanker | Insulated (−6 °C) |
|---|---|---|
| 18 °C | 414 min | 480 (capped) |
| 22 °C | 313 min | 475 min |
| 27 °C | 222 min | 336 min |
| 30 °C | 180 min | 273 min |
| 35 °C | 127 min | 193 min |
| 39 °C | 96 min | 146 min |

**Parameters and their basis:**

| Parameter | Value | Basis |
|---|---|---|
| `baseHoldMinutesAt30C` | 180 | Common dairy practice: raw milk to chilling within 2–4 h. 3 h at 30 °C is the mid-point. |
| `q10Factor` | 2.0 | Standard Q10 for bacterial growth. |
| `insulationOffsetC` | 6.0 | An insulated tanker roughly doubles hold time; 6 °C is the offset that produces that. **Estimate.** |
| `minHoldMinutes` | 60 | Floor. Guards against a bad temperature reading producing an absurd deadline. |
| `maxHoldMinutes` | 480 | Ceiling. At 12 °C the bacteriology allows 12 h, but there are other reasons not to leave milk in a tanker all day. |

**Why bound the model at both ends:** without the ceiling, a cold morning gives a
budget nobody would actually trust. Without the floor, a sensor error reading 50 °C
would put every trip into alarm. A model that is not bounded is not finished.

**The one-way ratchet.** If ambient temperature rises mid-trip, the deadline moves
earlier immediately. If it falls again, the deadline does **not** move back. Milk that
has already spent an hour at 37 °C did not become fresher when a cloud passed —
bacterial damage is cumulative. Allowing the budget to grow would silently clear an
alert on a trip that is genuinely in trouble.

**Where temperature comes from:** a seasonal `temperature_profile` table keyed by month
and session. In production this would be a weather API or an on-tanker sensor; the
interface is `AmbientTemperatureProvider` and swapping it is a one-class change.

---

## 3. The spoilage clock starts at first collection

**What I assumed:** the clock starts when the first litre enters the tanker, not when
the tanker leaves the plant.

The tanker is empty on the outbound leg, so no milk is aging. Hot time is therefore:

```
hotTime = Σ in-village traverse minutes
        + Σ inter-village hop minutes
        + travel(last village exit → plant)
        + plant unload wait
```

The plant → first-village leg is deliberately excluded.

**The consequence, which is the core optimisation in this system:** you should drive
out empty to the farthest village and collect on the way home. Worked example with
villages at 8, 25 and 40 km:

```
Nearest-first:   plant → A → B → C → plant   hot time = 319 min
Farthest-first:  plant → C → B → A → plant   hot time = 265 min
```

Same distance driven. 54 minutes of spoilage risk removed by reversing the order.
The sequence optimiser seeds farthest-first for this reason, and its objective
function is hot time rather than distance.

**What I did not model:** the milk was already some minutes old when the tanker
arrived, because the farmer milked before the tanker got there. That is real, but it is
not in the brief, it is roughly constant across points, and modelling it would only
shift every deadline by a fixed offset. Noted as a limitation.

---

## 4. Travel time

No maps API is used. A key would break the "runs on a clean machine" requirement.

```
roadKm    = haversineKm × 1.35
speedKmph = 15 if roadKm < 2, else 26 if roadKm < 10, else 34
speedKmph × 1.15 (morning) or × 0.90 (evening)
```

| Parameter | Value | Basis |
|---|---|---|
| `circuityFactor` | 1.35 | Published rural road-network studies put the road-to-straight-line ratio at 1.2–1.5. Flat grid areas near 1.2, hilly winding areas near 1.5. **Estimate for mixed terrain.** |
| Speed < 2 km | 15 km/h | Village lanes, cattle, pedestrians. **Estimate.** |
| Speed 2–10 km | 26 km/h | Connecting roads. **Estimate.** |
| Speed > 10 km | 34 km/h | District road, loaded heavy vehicle. **Estimate.** |
| Morning multiplier | 1.15 | Empty roads at 05:00. **Estimate.** |
| Evening multiplier | 0.90 | Market traffic, school children at 17:00. **Estimate.** |

**Worked example.** Two villages 10.4 km apart in a straight line:

```
Road distance 10.4 × 1.35 = 14.0 km
Morning: 34 × 1.15 = 39 km/h → 21.5 min
Evening: 34 × 0.90 = 31 km/h → 27.4 min
```

Six minutes longer per leg in the evening. Over eight village hops that is 48 extra
minutes — in the session that already has the least spoilage budget. The evening is
squeezed from both ends.

**These five numbers are the weakest part of the model and I am not pretending
otherwise.** Every trip records predicted versus actual arrival, so after two weeks of
real operation the guesses would be replaced with measurements. See "Known
limitations" below.

**The seam is real, not decorative:** `TravelTimeProvider` is an interface with a
Haversine implementation (default, no key) and an OSRM implementation behind a Spring
profile. The planner does not know which it is using.

---

## 5. The feasibility finding

This is the most important thing the system produces, and it falls out of the numbers
rather than being designed in. The figures below are what the running system reports on
`baseline`, not estimates.

### A tanker is bounded by two things

Milk spoils, and drivers go home. A tanker can only contribute
`min(holdBudget, driverShift)` minutes of on-milk work, and which of the two binds depends
entirely on the weather:

| Session | Ambient | Hold budget (plain / insulated) | Driver shift | Usable each | Fleet total | Required | Ratio |
|---|---|---|---|---|---|---|---|
| Morning | 22 °C | 313 / 475 min | 300 min | **300 / 300** | 6,600 min | 7,021 min | **1.06** |
| Evening | 35 °C | 127 / 193 min | 300 min | **127 / 193** | 3,190 min | 8,133 min | **2.55** |

**In the morning the roster binds. In the evening spoilage binds.** At 22 °C the milk
would last five hours and the driver goes home after five; at 35 °C the milk is finished
in two and the shift never gets a chance to matter.

### What that means in practice

Both sessions come back `COVERAGE_OPTIMISATION`. Neither fits.

| Session | Routes | Points served | Coverage | Thinnest slack |
|---|---|---|---|---|
| Morning, 22 °C | 22 | 868 of 1,250 | 69% | 93 min |
| Evening, 35 °C | 14 | 241 of 1,250 | 19% | 22 min |

**This corrects an earlier claim in this document.** An earlier version of this section
said the morning "fits comfortably with 22 tankers" at a ratio of 0.71. That was arithmetic
on hold budget alone, and it was wrong: it ignored the driver roster entirely. Serving every
point on a 22 °C morning needs about 26 tankers on a 300-minute shift, not 22. The dairy is
short in the morning too — just for a different reason, and by much less.

### The lever the morning finding hands you

Extending the morning shift from 300 to 600 minutes changes the arithmetic decisively: each
tanker becomes bounded by its hold budget again, fleet capacity rises from 6,600 to 7,858
minutes, and the ratio falls from 1.06 to **0.89** — inside the feasibility margin.

The planner delivers it. On a 600-minute shift the same dairy plans as **22 routes covering
1,216 of 1,250 points — 97%, mode `FULL_SERVICE`, thinnest slack 31 minutes**, against 868
points on the 300-minute roster. Same fleet, same villages, same weather; one rostering
change is worth 348 collection points a morning.

**That is a rostering decision, not a capital one.** Every other lever for the morning
shortfall — more tankers, more insulation, a second chilling centre — costs money. This one
costs a conversation about shift patterns, and it is the single largest improvement
available to this dairy.

### The evening is a different problem

At a ratio of 2.55, no rostering change touches it. Serving every point on a 35 °C evening
would need about 61 tankers against a fleet of 22. This is not a flaw in the model — it is
what "milk that sits too long is rejected outright" means once you put numbers on it. The
dairy is almost certainly losing evening loads in summer and treating it as normal.

The system's job is to make that visible and offer levers. The most valuable one still costs
nothing: shifting the evening departure from 16:30 to 18:30 drops ambient from 35 °C to
29 °C, raises the plain-tanker budget from 127 to 193 minutes, and takes coverage from
**19% to 37%** — about 18 points, for no money at all.

---

## 6. Capacity is not the binding constraint

```
1,400 farmers × ~12 L morning  ≈ 16,800 L
22 tankers × ~4,000 L average  ≈ 88,000 L
```

Five tankers would hold all the milk. The dairy owns 22 because the area is spread out
and the window is short — **the constraint is time, not volume.**

Two consequences:

1. Capacity is checked but rarely binds. I use a mixed fleet (2,000 / 3,000 / 5,000 L)
   so it binds occasionally and that code path is exercised.
2. "Minimise tankers used" is a genuinely valuable objective. Serving the morning with
   19 tankers instead of 22 saves three drivers' wages and three vehicles of fuel every
   day, and the feasibility report reports it.

---

## 7. Fairness when the fleet is too small

When coverage mode triggers, the naive objective — rank points by litres per minute
and take the best — is mathematically optimal and destroys the business. It serves the
same high-yield near villages every day and never serves the small far ones. Those
farmers' milk spoils in their own cans and they leave the cooperative within a month.

The reason it fails is structural rather than a tuning problem. The far end of the
corridor is *always* the least efficient choice, so it is *always* the one dropped. The
optimiser is not making a fresh judgement each hot day; it is making the same judgement
every hot day, about the same eight villages, until they stop supplying.

**What I assumed:** a cooperative cannot abandon its weakest members, so efficiency alone
cannot be the objective.

### What was built

The ranking lives in the merge ordering inside `RoutePlanner`, not in a separate scorer.
When the planner chooses which village to merge next:

```
score = (litres / marginalHotMinutes)         ← efficiency
      × (1 + daysSinceLastServed) ^ 1.6       ← equity
```

with a hard rule above it: a village at `maxConsecutiveSkips` is merged **before** anything
is ranked at all.

| Parameter | Value | Reasoning |
|---|---|---|
| `equityExponent` | 1.6 | At 1.0 a point skipped 3 days is only 4× more attractive — not enough to overcome a real efficiency gap. At 1.6 it is ~7.5×, which reliably pulls it in. **Tunable.** |
| `maxConsecutiveSkips` | 3 | Hard cap. A village at 3 stops competing on score and is merged first. |

The hard cap guarantees every farmer is served at least once every two days. That is a
promise the dairy can actually make, and it is the source of the `guaranteedBy` date in
the farmer-facing response.

Two details worth stating. A village is as neglected as its **most** neglected point —
averaging would let a village with one long-ignored point look fine because its neighbours
are fresh. And a point with no history at all is ranked ahead of any amount of recorded
neglect, rather than being folded in as a very large day count, so a village the dairy has
never reached cannot be overtaken by arithmetic.

None of this works without `CoverageStateService` writing the counters back after every
**published** plan. Counters move on publish rather than on generate: drafts are produced
and discarded during a normal morning, and treating a discarded draft as a served session
would tell the system a village had its milk collected when no tanker ever left.

### What was cut, and why

| Cut | Why |
|---|---|
| **Urgency multiplier** (`wasSkippedLastSession ? 2.5 : 1.0`) | The equity term already covers it. A point skipped this morning has a non-zero `daysSinceLastServed` by tonight, and a third multiplier on the same signal is a second thing to tune for no new information. |
| **Partial fill** — taking some points of a village and leaving the rest | Raises coverage a few points. A village that is silently half-served is worse than one honestly skipped: nobody gets an exclusion row, and the farmers who were missed look served. |
| **Ejection chains** — undoing an accepted merge to make room for a better one | Raises coverage a few more points. The failure mode is a route left corrupt by an abandoned ejection, and the plan thrashing between near-equal alternatives makes the day-over-day diff unreadable, which is what ops actually reads. |
| **A separate `CoveragePlanner`** | Coverage mode is the same greedy loop with the same ranking, degrading honestly and reporting what it could not place. A second algorithm for the hard days would have been a second thing to get wrong, and the hard days are the ones that matter. |

---

## 8. Consolidation suggestions — designed, then cut

Some villages have four or five collection points within a few hundred metres of each
other, a legacy of which households volunteered a doorstep first. Serving them as one
stop would save several minutes a session, and on a hot evening minutes are coverage.

An advisory that clustered nearby points and proposed merges was designed and then
left out of this build. It is a real feature, but it answers a different question from
the one the brief asks — get the milk to the plant before it spoils — and building it
would have meant two tables, an entity graph and a DBSCAN pass that nothing else in
the system reads. The design decisions are recorded here because they are the part
worth keeping: whoever picks this up would have to make the same four calls, and three
of them are about people rather than geometry.

| Decision | Value it would have taken | Reasoning |
|---|---|---|
| Maximum walk | 500 m | About a seven-minute walk carrying 20 kg of cans. Beyond that farmers stop supplying, and a merge that loses a supplier has not saved anything. This would have been the single most important judgement call in the feature, and it should be validated with farmers before any merge is implemented rather than settled by a developer picking a round number. |
| Minimum cluster size | 2 | The brief confirms two-farmer points already exist at this dairy, so merging a pair is proven acceptable here rather than assumed. |
| Cross-village merges | Never proposed | Two villages can be 400 m apart and have a century of reasons not to share a collection point. The algorithm cannot see any of that, so the rule is to not try — a constraint on the search, not a penalty in the scoring. |
| Hub selection | Minimise the **maximum** walk, not the average | The farmer with the longest walk is the one who refuses. Averaging hides exactly the person whose consent decides whether the merge happens at all. |

Two consequences of leaving it out. There is no `merge_proposal` table and no
`ConsolidationAnalyser`, so `collection_point.merged_into_id` was dropped as well:
nothing would ever have set it. And `maxWalkMetres` is no longer a solver parameter,
because the clustering radius was its only consumer.

Had it been built, every proposal would have carried `requiresFieldValidation`.
Walking distances here are straight-line, not footpaths, and farmer consent is not
something a solver can infer. **The system would have proposed; humans would still
decide.**

---

## 9. Operational assumptions

| Assumption | Value | Reasoning |
|---|---|---|
| Sessions | Morning ~05:00, Evening ~16:30 | Typical Indian dairy practice. Both are configurable. |
| Morning / evening yield split | 60 / 40 | Common for buffalo herds. |
| Daily volume variance per farmer | ±15% | Plan estimates are therefore always slightly wrong, which is realistic and exercises the capacity path. |
| Service time per point | `2.0 + 0.35 × farmerCount` min | Fixed cost of stopping (park, valve, paperwork) plus per-farmer time. **Estimate.** |
| Driver max shift | 300 min | Configurable. |
| Plant unload | 20 min | Configurable. |
| Safety buffer | 20 min | Planning against estimated travel times that will be wrong. A route planned to arrive with 2 minutes to spare fails the first time it rains. |
| Capacity headroom | 5% | Because volume estimates carry ±15% variance. |
| Spoilage WARNING | 80% of budget | ~35 min of notice on a 180-min budget — enough time to act. |
| Spoilage CRITICAL | 95% of budget | Below this a dispatcher still has options; above it, mostly not. |
| Tracking lost | 15 min without a ping | Below this, brief signal gaps are normal. |
| Business date | Set explicitly at trip creation | The evening session can cross midnight; deriving it from `now()` would be a bug. |

---

## 10. Design decisions worth stating

**Collection point and farmer are separate entities, N:1.** The tanker routes over
points and visits each once; milk is attributed per farmer. This is the direct answer to
"some collection points serve two farmers." Routing over farmers would produce duplicate
stops at identical coordinates.

**`trip_stop` snapshots `route_stop` at trip creation.** If a new plan is published at
05:30 while tankers are on the road, nothing changes for them. Without the copy, a
mid-session republish silently rewrites the stop list under a driver halfway through it.

**Events drive trip progress; GPS pings only refine position.** A tanker driving past a
point has not collected from it. Only an `ARRIVED_AT_STOP` event advances the sequence.

**Driver events carry a client-generated UUID with a unique index.** Drivers lose signal
constantly; the phone buffers events locally with their real timestamps and uploads the
backlog on reconnect, including some the server already has. One database constraint
gives idempotency with no protocol complexity.

**Mitigations are generated and ranked, never auto-executed.** Auto-diverting a tanker
because a travel estimate was pessimistic would be worse than the problem. And when no
mitigation saves the load, the system says so — a system that always offers a fix is
lying.

**Hold budget is computed at runtime, never stored on the tanker.** It changes twice a
day with temperature. It *is* copied onto the trip at creation, so editing a tanker
record mid-morning cannot change a running deadline.

**Domain logic is plain Java with no Spring annotations.** Everything in `domain/` is
constructed with `new` and unit-testable without a context. Spring handles HTTP,
transactions and scheduling only.

---

## 11. Scope — what I did not build, and why

| Not built | Reason |
|---|---|
| Real authentication | Role-header stub plus an interceptor. The intended design (driver JWT with device binding, farmer access via IVR/OTP only, ops RBAC with audit) is documented. A half-built Spring Security config is worse than an honest placeholder. |
| Real map / traffic API | Requires an account key, which breaks the clean-machine requirement. `TravelTimeProvider` is an interface with an OSRM implementation to prove the seam is real. |
| SMS / IVR delivery | A delivery-channel concern with no bearing on routing or tracking correctness. Notifications are logged instead. |
| Payments, fat/SNF pricing | A separate bounded context. |
| Driver mobile app, ops web console | This is a backend brief. |
| Multi-plant optimisation | The schema supports several plants because the "divert" mitigation needs a destination, but choosing which plant each route serves is a whole extra optimisation dimension. |
| Volume forecasting | Needs historical data the system does not have yet. The variance report is the foundation for it. |
| Kafka / microservices | 22 tankers pinging every 30 s is about 0.7 writes per second. The difficulty here is the domain, so that is where the effort went. |
| Multi-day fairness simulation | The fairness rule and the three-strike cap are implemented; only the multi-day demonstration of them is deferred. |
| Comparison against a hand-entered 2019 plan | I would be inventing the bad plan myself, which makes the finding circular. The infeasibility finding in Section 5 comes from real arithmetic instead. |

---

## 12. Known limitations

1. **Travel times are synthetic.** Five parameters in Section 4 are educated guesses.
   The variance report exists precisely because they need calibrating against real GPS
   traces. I would run the system in shadow mode for two weeks — generating plans and
   comparing predicted against actual arrivals — before publishing an algorithmic plan.

2. **The spoilage constant is from general practice, not this dairy's data.** 180 min
   at 30 °C is a reasonable industry figure. `intake_record.oldest_milk_min` is recorded
   on every trip so that rejection rate can eventually be plotted against milk age and
   the constant replaced with a measured one.

3. **The delay-factor ETA model is naive.** It scales remaining time by the ratio of
   actual to planned elapsed time so far. It does not account for the specific road
   ahead being worse than the road behind.

4. **Diversion is one-way.** Once a trip is diverted, reverting mid-route is an
   operations phone call, not a system feature.

5. **Pre-collection milk age is not modelled** (see Section 3). Every deadline is
   therefore slightly optimistic by a roughly constant offset.

6. **The simulation validates logic, not the model.** It proves constraints hold, events
   apply idempotently, and alerts fire at the right thresholds. It cannot prove the
   travel-time model is correct, because I wrote both the model and the simulator.

---

## 13. If I had two more weeks

- Wire OSRM and calibrate `circuityFactor` and the speed bands against real trips
- Run in shadow mode alongside the existing paper routes, comparing predicted vs actual
- Build the driver Android app with proper offline sync
- Calibrate the hold window against real rejection data from `intake_record`
- Volume forecasting per point, so plan estimates stop being ±15% wrong
- Move plan generation to a background job with a status endpoint (needed above ~5,000
  points)
