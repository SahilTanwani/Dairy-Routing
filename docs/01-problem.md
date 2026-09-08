# 01 — The Problem and the Core Ideas

## The situation

A dairy plant sits in the middle of a district. Around it are about 60 villages. In
those villages, roughly 1,400 farmers milk their buffaloes twice a day and carry the
milk to a nearby collection point — usually a small building with a weighing scale.
Twenty-two tankers drive out from the plant, collect the milk, and bring it back.

**What makes it hard.** Raw milk goes bad fast. In hot weather it can spoil in under two
hours. When a tanker reaches the plant the milk is tested, and if it fails they throw
away the **entire tanker** — not just the bad part, because it is all mixed together in
one compartment.

So the whole job is a race against a clock, and the clock starts the moment the first
drop of milk goes in.

## The four failures from the brief

| Failure | How the system answers it |
|---|---|
| Routes drawn on paper in 2019, never revised | Versioned planning with a solver and a feasibility report |
| Milk rejected when it sits too long | Temperature-driven spoilage budget, enforced at plan time and monitored live |
| Some collection points serve two farmers | `collection_point` is the routing entity, `farmer` the attribution entity, N:1 |
| Nobody can tell a farmer where the tanker is | GPS → ETA engine → one farmer endpoint with honest confidence levels |

## What we are building

1. **A planner** — decides which tanker visits which villages in what order. Runs
   monthly or when something changes.
2. **A trip tracker** — creates today's trips twice a day and records what actually
   happens.
3. **A milk clock watcher** — watches every tanker's timer and raises the alarm
   *before* milk is lost.
4. **An answer for the farmer** — one question, one plain sentence back.
5. **Suggestions** — the system studies the setup and says "here are things you could
   change to collect more milk." This turns out to be the most valuable part.

---

## Idea 1 — The drive out is free

A tanker leaving the plant is empty. No milk, so nothing is spoiling. The clock has not
started. It starts when the first milk goes in.

Consider three villages at 8 km, 25 km and 40 km.

**Nearest first** — the milk from the near village sits in the tanker through the whole
journey, including the long drive home from the far village.

**Farthest first** — drive out empty to 40 km, collect, then 25, then 8, then home.
Home is now a short drive because you are already close.

```
Nearest-first:   plant → A(8) → B(25) → C(40) → plant
                 hot = 67 + 22 + 67 + 28 + 67 + 68 = 319 min

Farthest-first:  plant → C(40) → B(25) → A(8) → plant
                 hot = 67 + 28 + 67 + 22 + 67 + 14 = 265 min
```

**Same distance driven. 54 minutes of spoilage risk removed.**

Experienced dairy managers do this by instinct. The system does it by construction: the
sequence optimiser seeds farthest-first and its objective function is hot time, not
distance.

---

## Idea 2 — How long milk lasts depends on the weather

A fixed "milk lasts 3 hours" rule is wrong, and it is wrong twice a day.

Bacteria multiply roughly twice as fast for every 10 °C rise (the Q10 rule).

```
holdMinutes = baseAt30C × 2 ^ ((30 − effectiveTemp) / 10)
effectiveTemp = ambientC − (insulated ? 6.0 : 0)
```

| Temperature | Standard tanker | Insulated | When |
|---|---|---|---|
| 18 °C | 414 min | capped 480 | Cool winter morning |
| 22 °C | 313 min | 475 min | Normal morning |
| 27 °C | 222 min | 336 min | Warm morning |
| 30 °C | 180 min | 273 min | Baseline |
| 35 °C | 127 min | 193 min | Hot evening |
| 39 °C | 96 min | 146 min | Peak summer evening |

**The morning budget is 2.46× the evening budget.** The same route plan cannot
serve both sessions. Morning routes can be long; evening routes must be short.

Because insulation buys real time, the planner should give insulated tankers to the
longest, riskiest routes. It does — routes are sorted by hot time and tankers by hold
budget, then paired off.

**The one-way ratchet.** If ambient rises mid-trip, the deadline moves earlier
immediately. If it falls again, the deadline does **not** move back. Milk that spent an
hour at 37 °C did not become fresher when a cloud passed. Damage is cumulative, and
letting the budget grow would silently clear an alert on a trip in real trouble.

**Bounds.** `minHoldMinutes = 60` and `maxHoldMinutes = 480`. Without the ceiling, a
10 °C morning gives 12 hours, which is bacteriologically true but ignores every other
reason not to leave milk in a tanker all day. Without the floor, a sensor error reading
50 °C puts every trip into alarm.

---

## Idea 3 — Sometimes it is impossible, and saying so is the point

**Work required per session:**

```
1,250 stops × ~3.2 min in-village work  = 4,000 min
~22 inter-village hops × ~12 min        =   264 min
22 return legs × ~28 min                =   616 min
                                          ≈ 4,880 tanker-minutes
```

**Capacity available = 22 tankers × hold budget:**

| Session | Ambient | Budget each | Fleet total | Ratio | Verdict |
|---|---|---|---|---|---|
| Morning | 22 °C | 313 min | 6,886 | 0.71 | Comfortable |
| Morning (summer) | 27 °C | 222 min | 4,884 | 0.999 | Tight |
| Evening (mild) | 28 °C | 207 min | 4,554 | 1.07 | Short |
| Evening (summer) | 35 °C | 127 min | 2,794 | **1.75** | **Impossible** |

**The morning fits comfortably. The hot evening does not fit at all** — you would need
about 38 tankers and the dairy has 22.

The dairy is almost certainly losing evening milk in summer and treating it as normal.
Making that visible, with arithmetic, is the most valuable thing this system does.

**The four levers it offers:**

| Fix | Cost | Effect |
|---|---|---|
| **Depart 18:30 instead of 16:30** | **Nothing** | Ambient 35 → 29 °C, budget 127 → 194 min, coverage 67% → 91% |
| Merge nearby collection points | Farmers walk further | Frees ~800 min of fleet time |
| Insulate 8 more tankers | Capital | Coverage 67% → 79% |
| Chilling units in 8 far villages | Capital | Removes them from the time-critical set |

The first one is free. The dairy has collected at 16:30 since before anyone remembers,
and nobody had worked out that simply waiting two hours recovers a quarter of the lost
evening milk.

---

## Idea 4 — When you must skip farmers, skip fairly

On a hot evening you cannot serve everyone. The naive objective — rank by litres per
minute and take the best — is mathematically optimal and destroys the business:

- High-yield villages near the plant win every day
- Small far villages lose every day
- Those farmers' milk spoils in their own cans
- Within a month they leave the cooperative

**A cooperative that abandons its weakest members stops being a cooperative.**

```
score = (litres / marginalMinutes)            ← efficiency
      × (1 + daysSinceLastCollection) ^ 1.6   ← equity, grows fast
      × (wasSkippedLastSession ? 2.5 : 1.0)   ← urgency
```

Plus a hard rule: **no point is skipped more than 3 times in a row.** At three it becomes
mandatory in the next plan, inserted before anything else is considered.

That guarantees every farmer is served within two days — a promise the dairy can make,
and the source of the `guaranteedBy` date in the farmer response.

---

## Travel time

No maps API. A key would break the "runs on a clean machine" requirement.

**Step 1 — straight-line distance.** Haversine formula. Ten lines, exact.

**Step 2 — roads wander.** Multiply by `circuityFactor = 1.35`. Published rural
road-network studies put the ratio at 1.2–1.5; flat grid areas near 1.2, hilly winding
areas near 1.5.

**Step 3 — speed by leg length:**

| Leg | Speed | Why |
|---|---|---|
| < 2 km | 15 km/h | Village lanes, cattle, pedestrians |
| 2–10 km | 26 km/h | Connecting roads |
| > 10 km | 34 km/h | District road, loaded heavy vehicle |

**Step 4 — time of day.** Morning × 1.15 (empty roads at 05:00). Evening × 0.90 (market
traffic, school children).

**Worked example** — two villages 10.4 km apart in a straight line:

```
Road distance:  10.4 × 1.35 = 14.0 km
Morning:        34 × 1.15 = 39 km/h → 21.5 min
Evening:        34 × 0.90 = 31 km/h → 27.4 min
```

Six minutes longer per leg in the evening. Over eight hops, 48 extra minutes — in the
session that already has the least budget. **The evening is squeezed from both ends.**

**These five numbers are guesses.** Every trip records predicted versus actual so they
can be replaced with measurements after two weeks of real operation. That is the honest
answer to "how do you know your travel times are right?" — you do not at first, and the
system is built to find out.

**The interface:**

```java
public interface TravelTimeProvider {
    Duration between(GeoPoint a, GeoPoint b, Session session);
    TravelMatrix matrix(List<GeoPoint> points, Session session);
}
```

Two implementations: `HaversineTravelTime` (default, no key, runs anywhere) and
`OsrmTravelTime` (Spring profile). The planner does not know which it is using. Writing
the second proves the seam is real, not decorative.

**Matrix caching.** All pairs are precomputed once and keyed by a SHA-256 hash of the
sorted point set. Add or move a village and the hash changes, so the matrix rebuilds
automatically. No manual invalidation.

---

## Why this scales

The two-phase decomposition is the reason. You never solve over 1,250 points — you
solve 60 small in-village problems, then one 60-node problem over village blocks.
**Complexity grows with village count, not point count.**

| Scale | Points | Solver time | What changes |
|---|---|---|---|
| Demo | 1,250 | ~2 s | Nothing |
| 5× | 6,000 | ~25 s | Move plan generation to a background job |
| 10× | 12,000 | ~90 s | Sparse k-nearest-neighbour matrix |
| 50× | 60,000 | — | Real routing engine, geographic sharding |
