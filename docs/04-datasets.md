# 04 — Seed Data and Datasets

## Why the seeder matters more than it sounds

Start the app and the database is empty. You cannot test the planner, run the
simulation, or show anyone anything. The seeder fills an empty database with a realistic
fake dairy.

It is also:

- **The foundation of every demo** — the plan reports and the map all show seeded data
- **The foundation of every test** — scenario tests need a known, reproducible dairy
- **How you prove the system scales** — change one config file from 60 villages to 200

## The two rules

**Rule 1 — every number comes from a YAML config.** No count is written into the seeder
code. If a reviewer opens `SeedService.java` and finds a hardcoded 60, the entire "it is
dynamic" claim collapses.

**Rule 2 — everything uses a fixed random seed.** The same config always produces the
exact same dairy: same village positions, same farmer counts, same fleet. Non-negotiable
— tests assert specific numbers, and the demo must behave identically in the interview
and at home.

---

## The config record

`dto/DatasetConfig.java`

```java
public record DatasetConfig(
    String name,
    long   seed,

    // geography
    int    villageCount,
    int    corridorCount,
    Range  villageDistanceKm,
    Range  pointsPerVillage,
    double pointScatterMetres,

    // people
    int    targetFarmerCount,
    double twoFarmerPointRatio,
    Range  animalsPerFarmer,
    Range  litresPerAnimalPerDay,

    // fleet
    int          tankerCount,
    List<Integer> capacityMix,
    int          insulatedCount,
    int          driverCount,

    // plant
    int    plantCount,
    double plantLat,
    double plantLng,

    // session defaults
    String defaultSession,
    double defaultAmbientC
) {}

public record Range(double min, double max) {
    public double pick(Random rng) { return min + rng.nextDouble() * (max - min); }
    public int pickInt(Random rng) { return (int) Math.round(pick(rng)); }
}
```

Loaded with SnakeYAML, which is already on the classpath via Spring Boot.

---

## Generating the geography

### Anchor to a real place

Plant at **16.7050 N, 74.2433 E** — Kolhapur district, Maharashtra, a genuine dairy belt.

Costs nothing, and when you open a map you see tankers driving over real roads and
rivers instead of empty ocean. Reviewers notice.

### Villages along corridors, not scattered randomly

Real villages sit along roads, in lines radiating out from the town, dense near the
plant and sparse further out. Uniform random dots look obviously fake.

```java
private List<Village> seedVillages(DatasetConfig cfg, Plant plant, Random rng) {
    List<Village> out = new ArrayList<>();

    // Uneven bearings — real roads do not fan out at perfect intervals
    double[] bearings = new double[cfg.corridorCount()];
    double step = 360.0 / cfg.corridorCount();
    for (int i = 0; i < bearings.length; i++)
        bearings[i] = i * step + (rng.nextDouble() - 0.5) * step * 0.4;

    int perCorridor = (int) Math.ceil((double) cfg.villageCount() / cfg.corridorCount());

    for (double bearing : bearings) {
        double dist = cfg.villageDistanceKm().min();
        double span = cfg.villageDistanceKm().max() - dist;
        double gap  = span / perCorridor;

        for (int i = 0; i < perCorridor && out.size() < cfg.villageCount(); i++) {
            double wobbled = bearing + rng.nextGaussian() * 8;   // roads wander
            GeoPoint at = project(plant.location(), wobbled, dist);
            out.add(save(new Village("V-%04d".formatted(out.size() + 1), at)));

            // gaps widen with distance — settlement thins out
            dist += gap * (0.7 + rng.nextDouble() * 0.6);
        }
    }
    return out;
}
```

**The farthest village matters.** That is the one the planner struggles with, and the one
that goes critical in the demo.

### Points within villages

**Derive the count, do not draw it.** A point exists because somebody delivers milk to it,
so the number of points follows from the number of farmers:

```java
int wanted = round(cfg.targetFarmerCount() / (1 + cfg.twoFarmerPointRatio()));
```

`pointsPerVillage` then decides how big each village is *relative to its neighbours*, and
those draws are scaled to the total by largest remainder so the parts sum exactly:

```java
double exact = shape[i] / shapeTotal * wanted;
allocated[i] = max(floorPerVillage, (int) floor(exact));
// leftovers handed out largest-fraction-first
```

Drawing `pointsPerVillage` directly and handing out farmers afterwards is the obvious
version, and it is wrong: the farmer budget runs out partway through and the last hundred
points are left with nobody on them. Switching those off with `active = false` hides the
symptom and leaves the dairy carrying phantom infrastructure. Deriving the total means
every seeded point has a farmer and `collectionPoints` equals `activePoints`, which is
worth asserting on `dataset-check`.

Points end up 200–600 m apart, so travel between them is 1–2 min. **That is why the
two-phase algorithm works** — points cluster tightly into villages.

### Farmers, and the shared-point requirement

```java
for (CollectionPoint p : points) {
    int n = (rng.nextDouble() < cfg.twoFarmerPointRatio()) ? 2 : 1;
    double morning = 0, evening = 0;

    // the first farmer is never refused; the target caps second farmers only
    int n = alwaysOne + (wantsSecond && created + 2 <= cfg.targetFarmerCount() ? 1 : 0);
    for (int i = 0; i < n; i++) {
        int animals = cfg.animalsPerFarmer().pickInt(rng);
        double daily = animals * cfg.litresPerAnimalPerDay().pick(rng);
        save(new Farmer("F-%05d".formatted(++created), p, animals));
        morning += daily * 0.60;
        evening += daily * 0.40;
    }

    p.setServiceMinutes(round(2.0 + 0.35 * n, 1));    // stop cost grows with farmers
    p.setAvgMorningLitres(round(morning, 2));
    p.setAvgEveningLitres(round(evening, 2));
}
```

`targetFarmerCount` is a **target, not an exact count**. Because the first farmer at a
point is never refused, the total lands a fraction of a percent either side of it —
1,408 against a target of 1,400 on `baseline`. Chasing the exact figure would mean either
a point with nobody on it or a pile of special-case code, and both are worse than being
eight farmers out.

**Daily variance** is applied at trip creation, not seed time:

```java
double todaysLitres = baseAmount * (0.85 + rng.nextDouble() * 0.30);   // ±15%
```

This means the plan's estimate is always slightly wrong — realistic, and it lets a tanker
fill up earlier than expected.

### Fleet

```java
List<Integer> mix = cfg.capacityMix();
for (int i = 0; i < cfg.tankerCount(); i++) {
    int capacity     = mix.get(i % mix.size());          // round-robin
    boolean insulated = i < cfg.insulatedCount();
    save(new Tanker("MH-12-%s-%04d".formatted(letters(i), 1000 + i),
                    capacity, insulated, plant));
}
```

The round-robin is what keeps the mix configurable. `[2000, 3000, 5000]` gives an even
three-way split; `[2000, 2000, 3000]` gives two-thirds small tankers; a single entry gives
a uniform fleet. No special code for any of them.

**Why a uniform fleet here?** Every tanker in these datasets holds 4,000 L. The dairy
collects around 18,500 L a session against 88,000 L of fleet capacity, so volume runs at
roughly a fifth of what the tankers can hold. Dealing out mixed capacities would not change
that: capacity is not what this dairy runs out of.

What it runs out of is time, and saying so plainly is more honest than manufacturing a
second binding constraint to show off a code path. The capacity check still runs on every
merge — it is one of the four constraints, and `dataset-check` reports the volume ratio
that shows how far it is from binding — it simply never rejects a merge at this volume.

**Insulation is the axis that still varies**, and it is the one that matters: an insulated
tanker holds its milk about six degrees cooler, which is worth real minutes of budget.
That is what gives tanker assignment a decision to make. The riskiest route gets the
largest hold budget, and with a uniform fleet "largest budget" means "insulated" rather
than "biggest".

### Reference data

Temperature profiles and solver parameters are the same for every dataset, so they live in
`datasets/reference.yaml` and are seeded alongside whichever dataset is loaded.

They cannot go in a migration, because the reseed endpoint truncates both tables. They
should not go in `SeedService` either, because hard rule 4 keeps tuning numbers out of
code. A file next to the datasets is the remaining honest option, and it means the
nineteen parameters are written down in one readable place with a sentence each on what
they do.

```yaml
temperatureProfiles:
  - { month: 4, morning: 24.0, evening: 35.0 }    # 24 rows, 12 months x 2 sessions

solverParameters:
  - key: baseHoldMinutesAt30C
    value: 180
    description: How long raw milk holds at 30 C before quality is at risk
```

---

## Running and reseeding

```java
@Component
public class SeedRunner implements ApplicationRunner {
    @Value("${milkroute.dataset:baseline}")
    private String datasetName;

    public void run(ApplicationArguments args) {
        if (villageRepo.count() > 0) return;       // already seeded
        var r = seedService.seed(loader.load(datasetName));
        log.info("Seeded '{}': {} villages, {} points, {} farmers, {} tankers",
                 r.name(), r.villages(), r.points(), r.farmers(), r.tankers());
    }
}
```

**Reseed endpoint** — for switching datasets live during a demo without a container
restart:

```java
@PostMapping("/reseed")   // under ApiPaths.V1 + "/admin"
@Transactional
public SeedResult reseed(@RequestParam String dataset) {
    var config = loader.load(dataset);    // ← load FIRST, fail before wiping
    guardProfile();                       // ← dev/sim only
    wipeAll();
    matrixCache.clear();                  // ← easy to forget, breaks everything
    return seedService.seed(config);
}
```

```sql
TRUNCATE village, collection_point, farmer, tanker, driver, plant,
         route_plan, route, route_stop, trip, trip_stop, collection,
         driver_event, tanker_ping, alert, intake_record,
         point_coverage_state, plan_exclusion,
         temperature_profile, solver_parameter
RESTART IDENTITY CASCADE;
```

Three details that matter:

1. **Load the config before truncating.** A bad dataset name should fail while the
   database is still intact.
2. **Clear the travel matrix cache.** A stale matrix from the previous dataset silently
   produces nonsense distances. This one will bite you.
3. **Guard to dev/sim profiles.** Also a nice thing for a reviewer to see.

**Why reseed rather than version the data?** Versioning would mean a `dataset_version`
column on 20 tables and a filter in every query. One missed filter gives you a plan that
mixes two datasets — subtle, intermittent, and it would surface during the demo.
Truncate takes ~3 seconds, keeps the schema clean, and there is no partial state to
reason about. If multiple datasets were needed simultaneously, the right answer is
separate Postgres schemas, not a version column.

**Performance:** batch inserts with `saveAll()` in chunks of 500, not `save()` in a loop.
`baseline` should reseed in under 4 seconds.

---

## The datasets

Each stresses a **different** pressure point. Five variations of "bigger" prove nothing.

| Dataset | Villages | Farmers | Tankers | Temp | What binds | Priority |
|---|---|---|---|---|---|---|
| `baseline` | 60 | 1,400 | 22 | 22 °C | nothing — happy path | **Build** |
| `heat-crisis` | 60 | 1,400 | 22 | 35 °C | **time** | **Build** |
| `sparse-district` | 45 | 620 | 16 | 30 °C | **geography** | **Build** |
| `dense-cluster` | 28 | 2,100 | 22 | 24 °C | capacity | Cut if short |
| `large-scale` | 200 | 6,800 | 85 | 26 °C | scale | Cut if short |

### `baseline.yaml`

```yaml
# Expect: 1,250 points / 1,408 farmers, time ratio 0.78, volume ratio 0.21,
# 0 unreachable, farthest village 43.3 km
name: baseline
seed: 88213
villageCount: 60
corridorCount: 7
villageDistanceKm: { min: 4, max: 45 }
pointsPerVillage: { min: 14, max: 30 }
pointScatterMetres: 800
targetFarmerCount: 1400
twoFarmerPointRatio: 0.12
animalsPerFarmer: { min: 2, max: 6 }
litresPerAnimalPerDay: { min: 4, max: 7 }
tankerCount: 22
capacityMix: [4000]
insulatedCount: 6
driverCount: 26
plantCount: 1
plantLat: 16.7050
plantLng: 74.2433
defaultSession: MORNING
defaultAmbientC: 22
```

### `heat-crisis.yaml`

```yaml
# Expect: time ratio 2.55, COVERAGE_OPTIMISATION, ~19% coverage (241 of 1,250 points)
# Measured on baseline geography at EVENING / 35 C, which is what this file produces:
# same seed, so the villages, points and farmers are identical.
# IDENTICAL to baseline except the last two lines. Same seed = same geography.
name: heat-crisis
seed: 88213                    # ← MUST match baseline
villageCount: 60
corridorCount: 7
villageDistanceKm: { min: 4, max: 45 }
pointsPerVillage: { min: 14, max: 30 }
pointScatterMetres: 800
targetFarmerCount: 1400
twoFarmerPointRatio: 0.12
animalsPerFarmer: { min: 2, max: 6 }
litresPerAnimalPerDay: { min: 4, max: 7 }
tankerCount: 22
capacityMix: [4000]
insulatedCount: 6
driverCount: 26
plantCount: 1
plantLat: 16.7050
plantLng: 74.2433
defaultSession: EVENING        # ← changed
defaultAmbientC: 35            # ← changed
```

**The shared seed is the most important detail on this page.** It makes the demo a
controlled experiment: identical geography, identical farmers, identical fleet, two lines
different. You can `diff` the two files on screen in five seconds.

### `sparse-district.yaml`

```yaml
# Expect: >= 5 villages flagged UNREACHABLE_WITHIN_HOLD
name: sparse-district
seed: 44102
villageCount: 45
corridorCount: 4                    # fewer roads, more spread
villageDistanceKm: { min: 12, max: 75 }
pointsPerVillage: { min: 8, max: 16 }
pointScatterMetres: 1200
targetFarmerCount: 620
twoFarmerPointRatio: 0.08
animalsPerFarmer: { min: 2, max: 5 }
litresPerAnimalPerDay: { min: 4, max: 6 }
tankerCount: 16
capacityMix: [4000]
insulatedCount: 4
driverCount: 18
plantCount: 1
plantLat: 16.7050
plantLng: 74.2433
defaultSession: EVENING
defaultAmbientC: 30
```

Proves the system knows the difference between **hard** and **impossible**. These
villages are not unserved because of a tanker shortage — the round trip physically
exceeds the hold window. The chilling-unit recommendation is the right answer, and
saying so is better than pretending to route around physics.

---

## Tuning — do not skip this

Writing a config does not guarantee it hits the constraint you intended.

Build a verification endpoint:

```java
@GetMapping("/dataset-check")   // under ApiPaths.V1 + "/admin"
public DatasetDiagnostics check() {
    var ctx = contextFactory.build(defaultSession, defaultAmbientC);
    return new DatasetDiagnostics(
        villageRepo.count(), pointRepo.count(), farmerRepo.count(),
        ctx.requiredHotMinutes(), ctx.availableHotMinutes(),
        ctx.requiredHotMinutes() / ctx.availableHotMinutes(),
        totalExpectedLitres(), totalFleetCapacity(),
        countUnreachableVillages(ctx), farthestVillageKm());
}
```

Then check each dataset against its target:

| Dataset | Must show | If it does not, tune |
|---|---|---|
| `baseline` | ratio **< 0.8**, all reachable | Lower `villageDistanceKm.max` or raise `tankerCount` |
| `heat-crisis` | ratio **> 1.3**, coverage mode | Same config as baseline — if this fails, check the spoilage model |
| `sparse-district` | **≥ 5 unreachable** | Raise `villageDistanceKm.max` until they appear |

Ten minutes per dataset. Write the expected numbers as a comment at the top of each YAML
so you are never surprised in the interview.

---

## Demo sequence

```bash
# 1. Baseline — comfortable morning
POST /api/v1/admin/reseed?dataset=baseline
POST /api/v1/plans {"session":"MORNING","ambientTempC":22}
# → 19 routes, 100% coverage, 3 tankers spare

# 2. Same dairy, hot evening  ← the headline
diff datasets/baseline.yaml datasets/heat-crisis.yaml     # two lines
POST /api/v1/admin/reseed?dataset=heat-crisis
POST /api/v1/plans {"session":"EVENING","ambientTempC":35}
# → COVERAGE_OPTIMISATION, 19%, 1,009 unserved
GET /api/v1/advisory/session-timing
# → depart 18:30, 37% coverage, costs nothing

# 3. Sparse district — a different kind of failure
POST /api/v1/admin/reseed?dataset=sparse-district
POST /api/v1/plans {"session":"EVENING","ambientTempC":30}
GET /api/v1/plans/{id}/exclusions
# → 8 villages UNREACHABLE_WITHIN_HOLD with the arithmetic
```
