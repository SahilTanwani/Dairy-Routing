# 05 — Edge Cases

Each row is a decided behaviour. Implement these as you build the relevant component,
not as an afterthought. This table also goes into the README — it reads as thoroughness
and it pre-answers half the interview.

---

## Planning

| # | Case | Behaviour |
|---|---|---|
| P1 | Village unreachable even as a solo route | Exclusion `UNREACHABLE_WITHIN_HOLD` with the arithmetic in `detail`; recommend a chilling unit. **Never silently drop.** |
| P2 | Point volume exceeds every tanker | Exclusion `EXCEEDS_ALL_CAPACITY`; suggest a dedicated trip or split visit |
| P3 | Not enough tankers overall | Switch to `COVERAGE_OPTIMISATION`; report unserved points with litres at risk |
| P4 | Zero tankers available | Return an empty plan with a clear message. Do not crash, do not return null. |
| P5 | Zero active collection points | Empty plan with an explicit message |
| P6 | All points in one village | Single block. Split geographically if internal time alone exceeds budget. |
| P7 | Every route under 10 min slack | Flag the plan `FRAGILE` in the feasibility report. Publishing allowed but warned. |
| P8 | Solver exceeds its time limit | Return the best feasible solution found, flagged `TIMEBOXED`, with iteration count |
| P9 | Ambient temperature outside 0–50 °C | Clamp to range, log a warning, use the clamped value |
| P10 | New village added since last plan | Matrix content hash changes → matrix rebuilds automatically on next plan |
| P11 | Village deactivated mid-generation | Snapshot the point set at generation start; ignore mid-run changes |
| P12 | Plan requested for a past date | Rejected with 400. Plans are generated for the current business date forward only; historical fleet state is not modelled |
| P13 | Two plans published concurrently | `uq_one_published_per_session` rejects the second; caller gets 409 |
| P14 | Publish a plan that is already published | Idempotent no-op, return current state |

---

## Trip creation

| # | Case | Behaviour |
|---|---|---|
| T1 | Creation job runs twice | `UNIQUE (route_id, business_date, session)` → no-op, no error |
| T2 | No published plan for this session | Log ERROR, raise an alert, create nothing. **Fail loudly** — silently doing nothing is worse. |
| T3 | Assigned tanker is in `MAINTENANCE` | Substitute a compatible `AVAILABLE` tanker. If none, create the trip as `BLOCKED` and alert. |
| T4 | Assigned driver is inactive | Same substitute-or-block |
| T5 | Evening session crosses midnight | `business_date` is set **explicitly** at creation, never derived from `now()`. Cutover hour in config. |
| T6 | New plan published at 05:30 mid-session | Running trips unaffected — they snapshotted their stops at creation |
| T7 | Fleet shrank since the plan was generated | Re-check availability at creation; excess routes become `BLOCKED` with an alert |
| T8 | Fleet grew since the plan was generated | New tankers idle. Report suggests regenerating the plan. |
| T9 | Collection point deactivated since planning | Stop is created but immediately `SKIPPED / POINT_INACTIVE` |

---

## Running — driver events

| # | Case | Behaviour |
|---|---|---|
| R1 | Duplicate event (network retry) | `client_event_id` unique index; catch `DataIntegrityViolationException`, count and skip silently |
| R2 | Batch arrives out of order after reconnect | Sort by `client_ts` before replaying through the state machine |
| R3 | Event for a stop already `COLLECTED` | 409 with the existing record. Raise `DATA_CONFLICT` if litres differ. **Never silently discard a milk record.** |
| R4 | `COLLECTED` with no prior `ARRIVED_AT_STOP` | Synthesise the arrival at `client_ts − service_time`, log a warning. Do not reject — the app may have dropped one event. |
| R5 | Driver jumps ahead (at stop 9, system thinks 7) | Auto-mark 7 and 8 `SKIPPED / SEQUENCE_SKIP`, alert ops. **Do not block him** — he is there and knows something the system does not. |
| R6 | Client clock slightly ahead of server | Clamp `client_ts` to `server_ts`, flag the event. Alert if drift > 10 min. |
| R7 | Client clock wrong by more than an hour | Reject the batch with 422 and tell the app to resync. A wrong `first_collection_at` is worse than a missing one. |
| R8 | Litres > 500 for one farmer | Rejected by the CHECK constraint and by request validation with the bound in the message |
| R9 | Negative litres | Rejected by the CHECK constraint |
| R10 | Driver corrects a mis-keyed amount | Void the original row (`voided = true`, reason recorded) and insert a correction. **Both rows kept.** |

---

## Running — trip lifecycle

| # | Case | Behaviour |
|---|---|---|
| R11 | Tanker fills at stop 34 of 43 | `TANKER_FULL` event → remaining stops `DEFERRED`, route to plant, raise `SECOND_TRIP_NEEDED` with unserved litres |
| R12 | Farmer has no milk today | `SKIPPED / NO_MILK`. Downstream ETAs shift **earlier** — a nice thing to demo. |
| R13 | One of two farmers at a shared point absent | Stop is `COLLECTED` with one `collection` row, **not** `SKIPPED` |
| R14 | Driver's phone battery dies | After `trackingLostMinutes` (15) with no ping → `TRACKING_LOST`. Farmer messages switch to last-known-position phrasing. **The spoilage monitor keeps counting on the planned timeline** — the milk is still watched even though the tanker is not visible. |
| R15 | Phone recovers at stop 18 | Buffer flushes, stops 12–17 reconstructed from `client_ts`, ETAs recomputed, `TRACKING_LOST` resolved |
| R16 | Breakdown with milk aboard | Critical alert with minutes-to-deadline front and centre. Transfer proposal **only if** the transfer still lands inside the window. If it does not, state plainly that the load cannot be saved. |
| R17 | Breakdown before first collection | No milk aboard, no spoilage risk. Reassign the route if a spare tanker exists. |
| R18 | Deadline passes while still collecting | `SPOILAGE_EXCEEDED` alert. Recommend continuing to the plant anyway — some loads still pass testing. **Never auto-abort.** |
| R19 | Trip never started | After `plannedDepart + 45 min` with no `TRIP_STARTED`, raise `TRIP_NOT_STARTED` (CRITICAL); ops reassigns |
| R20 | Trip completes but intake never recorded | After 60 min at the plant, raise `INTAKE_MISSING` |
| R21 | Plant rejects the load | `intake_record` with `rejected_litres` and reason; alert; all `collection` rows for the trip flagged so the payment side knows |
| R22 | Two dispatchers execute different mitigations at once | Optimistic lock on `trip.version` → second gets 409 with current state |
| R23 | Diversion executed, then the road clears | Diversion is **one-way in v1**. Reverting mid-route is an ops phone call. Documented as a limitation. |

---

## Running — temperature and GPS

| # | Case | Behaviour |
|---|---|---|
| R24 | Ambient rises mid-trip | Budget shrinks, deadline moves earlier, `DEADLINE_TIGHTENED` alert |
| R25 | Ambient falls mid-trip | **Budget does not increase.** Bacterial damage is cumulative. One-way ratchet only. |
| R26 | Ping with `accuracy_m > 200` | Store it, but exclude from position estimation — it would drag the estimate |
| R27 | Physically impossible ping (200 km/h between fixes) | Store, flag `IMPLAUSIBLE`, exclude from position estimation |
| R28 | No pings at all for a whole trip | ETAs fall back to the planned timeline with `LOST` confidence. Monitor still runs. |

---

## Farmer queries

| # | Case | What the farmer hears |
|---|---|---|
| Q1 | Asks before any trip today | `NOT_SCHEDULED` — "Not started yet, usually arrives around 5:30 PM" |
| Q2 | Asks while the tanker is en route | `EN_ROUTE` — "Tanker is 7 stops away, expected around 6:41 PM" |
| Q3 | Asks after his milk was collected | `COLLECTED` — "Collected at 5:41 PM, 24 litres" |
| Q4 | His point was deferred today | `DEFERRED` — reason plus expected second-trip window |
| Q5 | His point was excluded by coverage | `NOT_SERVED_TODAY` — reason, `nextExpectedSession`, and `guaranteedBy` |
| Q6 | His point was merged into another | The hub point code and the office number |
| Q7 | Unknown farmer code | Generic 404. **Do not reveal whether a code exists.** |
| Q8 | The whole trip was aborted | `TRIP_ABORTED` with the office number. **Never leave a stale ETA for a tanker that is not coming.** |
| Q9 | Tracking is lost | LOW or LOST confidence, last-known-position phrasing, no precise time |
| Q10 | Two farmers at one point query separately | Both see the same tanker info; each sees only their own litres |

**The nine farmer statuses:** `NOT_SCHEDULED`, `SCHEDULED`, `EN_ROUTE`, `ARRIVING_NEXT`,
`COLLECTED`, `SKIPPED`, `DEFERRED`, `NOT_SERVED_TODAY`, `TRIP_ABORTED`.

Every response carries a `message` field containing one plain sentence, because an
operator or an IVR system has to read it aloud. Do not make them assemble a sentence
from six fields.

---

## Scale and data changes

| # | Case | Behaviour |
|---|---|---|
| S1 | Village added mid-week | Included in the next plan. Matrix hash changes → automatic rebuild. |
| S2 | 50 points added at once | ~2 s matrix rebuild. Report suggests regenerating the plan. |
| S3 | Tanker retired mid-session | Today's trip completes normally; excluded from tomorrow's plan |
| S4 | Farmer moves to a different point | Row update. Volume estimates recompute from active farmers. |
| S5 | Points merged via the advisory | Old point `active = false`, `merged_into_id` set, farmers repointed. **Historical rows intact.** |
| S6 | Matrix cache stale after a point moves | Content hash changes → automatic rebuild. No manual invalidation. |
| S7 | Dairy triples in size | Works. Village decomposition means complexity grows with village count, not point count. |
| S8 | Ping table growth | ~8,000 rows/session. Monthly partitioning is the 100× answer, not needed now. |

---

## Priority

If short on time, these are the ones that must work, because they are the ones an
interviewer will actually poke at:

1. **R1, R2** — duplicate and out-of-order events. The offline story is the most
   defensible engineering in the project.
2. **R14** — phone dies, monitor keeps counting. Shows you thought about partial failure.
3. **R24, R25** — the one-way temperature ratchet. The most likely follow-up question on
   the whole spoilage model.
4. **T6** — mid-session republish does nothing. Demonstrates why the snapshot exists.
5. **Q8** — never leave a farmer with a stale ETA. Shows you thought about the human on
   the other end.
6. **P1** — unreachable is reported with arithmetic, not silently dropped. The difference
   between hard and impossible.
