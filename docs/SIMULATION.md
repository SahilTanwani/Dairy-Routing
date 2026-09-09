# Watching a session run

`demo.sh` shows the planner: three dairies, four plans, and the constraint that
binds in each. This is the other half — a whole collection session replayed, with
twenty-two tankers on the road, so you can ask where one is, ask a farmer when his
milk will be collected, and watch both answers change.

> **Every command here is bash.** On macOS and Linux, any terminal. **On Windows,
> use Git Bash** — right-click inside the project folder in File Explorer, choose
> "Show more options" if you are on Windows 11, then "Git Bash Here". Command
> Prompt cannot run these, and PowerShell needs different syntax.

Everything is HTTP. No database access needed.

---

## Why a simulation exists at all

A collection session happens twice a day and takes three hours. You cannot test
that by waiting for it, and you certainly cannot test *"what happens when a driver
loses signal for half an hour"* by waiting for a driver to lose signal.

So nothing in this system calls the system clock. Every class asks a
`ClockProvider` what time it is:

```java
public interface ClockProvider { Instant now(); }
```

In normal running it returns the real clock. Under the `sim` profile a different
implementation returns whatever time the simulation has set.

**`Instant.now()` appears exactly once in the codebase**, inside `SystemClock`.
That rule went into the first commit, because retrofitting it later would mean
touching every class.

The result: a three-hour session replays in about four minutes.

## The drivers are not a shortcut

Twenty-two `VirtualDriver` instances, one per tanker. Each walks its route —
arrives, records milk, departs, moves on.

**They post to the same HTTP endpoints a real phone would use and never touch the
database.** So a simulated collection goes through request validation, the
clock-drift guard, the ingestion transaction, the unique index, the event replayer
and the trip state machine. The identical path a driver in a village triggers.

That is what makes this exercise the production code rather than a parallel test
harness.

---

# Running it

## 1. Start the app in simulation mode

```bash
docker compose down
SPRING_PROFILES_ACTIVE=sim docker compose up
```

**Leave that window open and visible.** The reconnect lines land there and they
are the best thing in the run.

Wait for the seeder line:

```
Seeded 'baseline' (seed 88213): 60 villages, 1250 points, 1408 farmers, 22 tankers
```

## 2. In a second Git Bash window

```bash
B="http://localhost:8080/api/v1"
curl "$B/sim/status"
```

```json
{"state":"IDLE","simulatedTime":"2026-10-15T04:30:00Z","step":0,"drivers":0,"driversDone":0}
```

**4:30 in the morning on 15 October 2026.** That is not today's date. If you see
it, the fake clock is in place.

*If you have `jq` installed, add `| jq` to any command below for readable output.
Everything works without it.*

## 3. Start the session

```bash
curl -X POST "$B/sim/run?scenario=happy-morning"
```

The scenario handles its own setup: it reseeds the dairy, plans the morning,
publishes the plan and creates twenty-two trips. Then the drivers leave.

**Wait about 25 seconds before querying anything** — the setup runs first.

---

# The timeline

The whole run takes roughly **four minutes of real time**. Some things can only be
seen while they are happening.

| Real time | Simulated | What is happening |
|---|---|---|
| 0:00 | 04:30 | Setup: reseed, plan, publish, create trips |
| 0:25 | ~05:00 | Drivers leave. **Everyone is waiting — good time to ask a farmer.** |
| 0:25 – 1:10 | 05:00 – 05:25 | Collections under way |
| ~1:15 | 05:25 – 05:55 | **Every phone goes dark.** Confidence drops. |
| ~1:30 | 05:55 | **All 22 reconnect.** |
| 4:00 | ~12:40 | `FINISHED`, all tankers home |

Miss a moment and you can restart with the same `sim/run` call.

---

# What to look at

## The clock is moving

```bash
curl "$B/sim/status"
```

```json
{"state":"RUNNING","simulatedTime":"2026-10-15T05:55:00Z","step":140,"drivers":22,"driversDone":0}
```

| Field | Means |
|---|---|
| `simulatedTime` | Where the fake clock is |
| `step` | Each step is 30 simulated seconds |
| `driversDone` | Tankers back at the plant |

**Run it twice, ten seconds apart.** If `step` has increased, the session is
genuinely running rather than stuck.

---

## A driver's round

Driver codes run `D-0001` to `D-0022`.

```bash
curl "$B/drivers/D-0005/trip?date=2026-10-15&session=MORNING" | jq
```

Without `jq`, the same thing but on one line:

```bash
curl "$B/drivers/D-0005/trip?date=2026-10-15&session=MORNING"
```

You get the tanker, its hold budget, and every stop in order:

```json
{
  "tripId": 5,
  "routeLabel": "R-05",
  "tankerRegNo": "MH-12-AE-1004",
  "insulated": false,
  "holdBudgetMinutes": 313,
  "stopCount": 44,
  "stops": [
    { "seq": 1, "pointCode": "CP-00412", "villageName": "Village 44",
      "status": "COLLECTED", "plannedArrivalAt": "..." },
    { "seq": 2, "pointCode": "CP-00413", "status": "COLLECTED", ... },
    { "seq": 3, "pointCode": "CP-00414", "status": "ARRIVED", ... },
    { "seq": 4, "pointCode": "CP-00415", "status": "PENDING", ... }
  ]
}
```

`holdBudgetMinutes: 313` — at 22 °C this tanker's milk stays good for 313 minutes.
An insulated tanker would show 475.

This is exactly what the phone downloads at quarter to five and caches, because it
is about to lose signal.

**Two things worth noticing.**

The boundary between `COLLECTED` and `PENDING` is **where the tanker is right now**.

**Stop 1 is in one of the farthest villages.** That is deliberate: the tanker
drives out empty, and an empty tanker carries no milk, so that leg costs nothing
against the spoilage clock. Go far first, collect on the way home. Same distance
driven, materially less time with milk on board.

---

## A farmer, asked once

Farmer codes run `F-00001` to `F-01408`. To find several who are still waiting:

```bash
for i in $(seq 25 25 1000); do
  code=$(printf "F-%05d" $i)
  curl -s "$B/farmers/$code/tanker-status?session=MORNING" \
    | grep -o '"message":"[^"]*"' | sed "s/^/$code  /"
done
```

Or, with `jq`, only the ones still waiting:

```bash
for i in $(seq 25 25 1000); do
  code=$(printf "F-%05d" $i)
  curl -s "$B/farmers/$code/tanker-status?session=MORNING" \
    | jq -r 'select(.status=="EN_ROUTE") | "\(.farmerCode)  \(.stopsAway) stops  \(.message)"'
done
```

```
F-00025  29 stops  The tanker is 29 stops away, expected around 10:17 AM.
F-00100   3 stops  The tanker is 3 stops away, expected around 6:46 AM.
F-00225  41 stops  The tanker is 41 stops away, expected around 12:23 PM.
F-00950   2 stops  The tanker is 2 stops away, expected around 7:21 AM.
```

Different tankers in different places, so different answers. **Nothing here is
stored** — every line was computed when you asked, from where that farmer's tanker
currently is.

The `message` field is the point. An operator on a phone, or an automated line
reading it aloud, should not have to assemble a sentence out of six fields.

---

## The same farmer, thirty seconds later

```bash
code=F-00225
curl -s "$B/farmers/$code/tanker-status?session=MORNING"
sleep 30
curl -s "$B/farmers/$code/tanker-status?session=MORNING"
```

```
..."stopsAway":41,"etaAt":"2026-10-15T12:23:00Z","message":"The tanker is 41 stops away..."
..."stopsAway":35,"etaAt":"2026-10-15T12:44:46Z","message":"The tanker is 35 stops away..."
```

**Six stops closer.** No timer counted down — in those thirty seconds the virtual
driver arrived at six stops and reported each one, the trip's position advanced,
and the estimate was recalculated from the new position.

The arrival time moved *later* even though the tanker got closer. That is the delay
factor: the estimate compares how the driver is actually going against how the plan
expected, and scales the remaining legs by the difference. A driver fifteen percent
slow through eight stops will probably be fifteen percent slow through the next
eight.

---

## During the blackout

Between simulated **05:25 and 05:55**, every driver's phone is dark. Ask the same
farmer:

```bash
curl -s "$B/farmers/F-00225/tanker-status?session=MORNING"
```

```json
{
  "farmerCode": "F-00225",
  "status": "EN_ROUTE",
  "confidence": "LOW",
  "message": "The tanker is on its way. We do not have a reliable time for you yet."
}
```

**There is no `etaAt` field at all.** Not null with a flag beside it — absent.

The last known position is a quarter of an hour old. Nothing downstream can render
a precise time from it, because there is no number to render.

Quote a farmer 6:41 and turn up at 7:15 and he will never believe another number
you give him. So when the system cannot stand behind a time, it does not produce
one.

| Confidence | What the farmer is told |
|---|---|
| HIGH | "expected around 6:41 am" |
| MEDIUM | "expected between 6:31 and 6:51 am" |
| LOW | "on its way — no reliable time yet" |
| LOST | "we have lost contact, please call the office" |

---

## The reconnect

At simulated 05:55 the signal comes back. In the app window:

```
Driver D-0001 reconnected on trip 1: sent 15 events (12 buffered + 3 already
acknowledged) -> 12 applied, 3 duplicates
Driver D-0002 reconnected on trip 2: sent 15 events (12 buffered + 3 already
acknowledged) -> 12 applied, 3 duplicates
...
Driver D-0022 reconnected on trip 22: sent 15 events (12 buffered + 3 already
acknowledged) -> 12 applied, 3 duplicates
```

Or afterwards:

```bash
docker logs milkroute-app | grep reconnected
```

**Twenty-two lines, every one the same shape.**

### Why three already acknowledged

A phone that has been offline cannot know which of its earlier sends got through
before the signal dropped. So on reconnect it sends the last few again. That is the
correct, safe behaviour — and the server has to cope with it.

### How the server copes

Every event carries a `client_event_id` — a UUID the phone generates **before it
tries to send**. There is a unique index on that column.

The batch is sorted by the phone's own timestamps, so events apply in the order
things happened rather than the order they arrived. Then each is inserted with:

```sql
INSERT ... ON CONFLICT (client_event_id) DO NOTHING
```

Duplicates are counted and dropped. Twelve applied, three duplicates, no error, no
double milk records.

### Why not just catch the exception

The obvious alternative is to catch the constraint violation and count it. **That
does not work.** PostgreSQL aborts the whole transaction on a failed statement, so
after the first duplicate every event queued behind it is lost too — twenty-nine
good events thrown away to skip one.

Pushing the conflict into the insert keeps the index as the arbiter and the
transaction usable. It is also still correct if two copies of the same batch arrive
simultaneously, which a pre-check with `existsBy` would not be.

### Proving it

```bash
docker exec milkroute-db psql -U milkroute -d milkroute -c \
  "select count(*) rows, count(distinct client_event_id) ids from driver_event;"
```

```
 rows | ids
 2670 | 2670
```

Sixty-six duplicates arrived across the fleet. Every row is still distinct.
**One unique index, no acknowledgement protocol, no retry negotiation.**

---

## All twenty-two at once

```bash
curl -s "$B/ops/board" | jq
```

Every trip with its position, its deadline and its risk level.

Find one insulated trip and one plain one and compare `minutesToSpoil`:

```
plain tanker      minutesToSpoil 308
insulated tanker  minutesToSpoil 470
```

Same departure, same first collection. **Insulation is worth two hours forty**,
which is why the planner gives insulated tankers to the routes carrying milk
longest.

---

## Skipping ahead, and finishing

```bash
curl -X POST "$B/sim/jump-to?at=2026-10-15T12:00:00Z"
```

**Forward only.** Jumping backwards is refused with a 400 — events already recorded
cannot be un-happened by rewinding a clock.

```bash
curl "$B/sim/status"
```

```json
{"state":"FINISHED","step":949,"drivers":22,"driversDone":22}
```

All twenty-two tankers home.

---

# Loading a different dairy by hand

`demo.sh` does this for you, but if you would rather step through it:

```bash
# a normal morning
curl -X POST "$B/admin/reseed?dataset=baseline"
curl -X POST "$B/plans" -H 'Content-Type: application/json' \
     -d '{"session":"MORNING","ambientTempC":22}'

# the same dairy, thirteen degrees hotter
curl -X POST "$B/admin/reseed?dataset=heat-crisis"
curl -X POST "$B/plans" -H 'Content-Type: application/json' \
     -d '{"session":"EVENING","ambientTempC":35}'

# what leaving two hours later is worth
curl "$B/advisory/session-timing?session=EVENING&ambientTempC=35&shiftHours=2"

# a thinly spread district, and who it had to drop
curl -X POST "$B/admin/reseed?dataset=sparse-district"
curl -X POST "$B/plans" -H 'Content-Type: application/json' \
     -d '{"session":"EVENING","ambientTempC":30}'
curl "$B/plans/1/exclusions"
```

Baseline and heat-crisis share a random seed, so they build the identical dairy —
same villages, same 1,250 collection points, same 1,408 farmers, same 22 tankers.
Only the session and the temperature differ:

```bash
cd src/main/resources/datasets
diff <(grep -v '^ *#' baseline.yaml) <(grep -v '^ *#' heat-crisis.yaml)
```

> **Reseeding empties the database.** Every plan, trip and collection goes. Reseed
> *before* you start a simulation run, never during one.

---

# The second scenario

```bash
curl -X POST "$B/sim/run?scenario=spoilage-crisis"
```

Heats the air from 30 °C to 38 °C forty minutes into the session. The hold budget
shrinks mid-trip, deadlines move earlier, and alerts escalate from warning to
critical.

**The budget only ever ratchets down.** If the air cools again the deadline does
not move back — milk that has already spent an hour at 37 °C did not become fresher
when a cloud passed. Bacterial damage is cumulative, and letting the budget grow
would silently clear an alert on a trip that is genuinely in trouble.

---

# What this proves, and what it does not

**It proves the pipeline.** Events are recorded through the real endpoints,
duplicates are rejected by a real index, trips move through a real state machine,
alerts fire at the right thresholds, and every one of the twenty-two trips reaches
`COMPLETED`.

**It does not prove the timing.** The simulated drivers move at a fixed six minutes
per leg rather than the times the planner worked out, so a 40 km hop and a
300-metre one take the same simulated time.

That is enough for what the simulation is for. It could not validate the travel
model anyway — the model and the simulator were written by the same person, and
they would agree with each other whatever the truth was. The honest test for the
travel model is the variance report: every trip records both the predicted and the
actual arrival time, and in real use you would compare those two columns over a
fortnight before trusting a plan.

---

# Quick reference

```bash
B="http://localhost:8080/api/v1"

# start / control
curl -X POST "$B/sim/run?scenario=happy-morning"
curl         "$B/sim/status"
curl -X POST "$B/sim/jump-to?at=2026-10-15T12:00:00Z"
curl -X POST "$B/sim/pause"
curl -X POST "$B/sim/resume"

# a driver  (D-0001 .. D-0022)
curl "$B/drivers/D-0005/trip?date=2026-10-15&session=MORNING"
curl "$B/drivers/trips/5"

# a farmer  (F-00001 .. F-01408)
curl "$B/farmers/F-00225/tanker-status?session=MORNING"
curl "$B/farmers/F-00225/collections"

# the fleet
curl "$B/ops/board"
curl "$B/ops/alerts?status=OPEN"

# the reconnect
docker logs milkroute-app | grep reconnected
```

| Scenario | What it adds |
|---|---|
| `happy-morning` | A normal session, with one thirty-minute blackout per driver |
| `spoilage-crisis` | The same, plus the air heating 30 °C → 38 °C mid-session |
