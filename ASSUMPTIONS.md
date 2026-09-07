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
| 18 °C | 429 min | 480 (capped) |
| 22 °C | 325 min | 480 (capped) |
| 27 °C | 230 min | 350 min |
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
above rather than being designed in.

**Work required per session:**

```
1,250 stops × ~3.2 min in-village work   = 4,000 min
~22 inter-village hops × ~12 min          =   264 min
22 return legs to plant × ~28 min         =   616 min
                                            ---------
                                            ≈ 4,880 tanker-minutes
```

**Capacity available = 22 tankers × hold budget:**

| Session | Ambient | Budget each | Fleet total | Verdict |
|---|---|---|---|---|
| Morning | 22 °C | 325 min | 7,150 min | Comfortable (ratio 0.68) |
| Morning (summer) | 27 °C | 230 min | 5,060 min | Tight but feasible |
| Evening (mild) | 28 °C | 208 min | 4,576 min | Short by ~300 min |
| Evening (summer) | 35 °C | 127 min | 3,226 min | **Short by ~1,650 min** |

**Conclusion: the morning session fits comfortably with 22 tankers. The hot-weather
evening session does not fit at all.** Serving every point on a 35 °C evening would
need roughly 38 tankers.

This is not a flaw in my model — it is what "milk that sits too long is rejected
outright" means in practice. The dairy is almost certainly losing evening loads in
summer and treating it as normal.

The system's job is to make that visible and offer levers. The most valuable one costs
nothing: shifting the evening departure from 16:30 to 18:30 drops ambient from 35 °C to
29 °C, raises the budget from 127 to 194 minutes, and takes coverage from ~67% to ~91%.

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

**What I assumed:** a cooperative cannot abandon its weakest members, so the scoring
function has three terms and a hard rule on top.

```
score = (litres / marginalMinutes)            ← efficiency
      × (1 + daysSinceLastCollection) ^ 1.6   ← equity
      × (wasSkippedLastSession ? 2.5 : 1.0)   ← urgency
```

| Parameter | Value | Reasoning |
|---|---|---|
| `equityExponent` | 1.6 | At 1.0 a point skipped 3 days is only 4× more attractive — not enough to overcome a real efficiency gap. At 1.6 it is ~7.5×, which reliably pulls it in. **Tunable.** |
| Urgency multiplier | 2.5 | A farmer skipped this morning must not also be skipped tonight. **Estimate.** |
| `maxConsecutiveSkips` | 3 | Hard cap. A point at 3 becomes mandatory in the next plan, inserted before anything else is considered. |

The hard cap guarantees every farmer is served at least once every two days. That is a
promise the dairy can actually make, and it is the source of the `guaranteedBy` date in
the farmer-facing response.

---

## 8. Consolidation suggestions

| Parameter | Value | Reasoning |
|---|---|---|
| `maxWalkMetres` | 500 | About a 7-minute walk carrying 20 kg of cans. Beyond this, farmers stop supplying. **This is the single most important judgement call in the feature and it should be validated with farmers before any merge is implemented.** |
| Minimum cluster size | 2 | The brief already confirms two-farmer points exist at this dairy, so merging pairs is proven acceptable here. |
| Cross-village merges | Never proposed | Two villages can be 400 m apart and have a century of reasons not to share a collection point. The algorithm cannot see that, so it does not try. |
| Hub selection | Minimises the **maximum** walk, not the average | The farmer with the longest walk is the one who will refuse. Optimising the worst case is what makes a proposal acceptable. |

Every proposal is flagged `requiresFieldValidation`. Walking distances are
straight-line, not footpaths, and farmer consent is required. **The system proposes;
humans decide.**

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
