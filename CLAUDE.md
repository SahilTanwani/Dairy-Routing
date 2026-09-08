# MilkRoute — Dairy Milk Collection Routing System

## What this is

Backend for a dairy that collects milk twice daily from ~1,400 farmers across ~60
villages using 22 tankers. Milk spoils based on ambient temperature; every route must
reach the chilling plant before its milk goes bad. See `docs/01-problem.md`.

## Read these before starting work

| File | Contents |
|---|---|
| `docs/01-problem.md` | Domain, the four core ideas, travel time model |
| `docs/02-schema.md` | Every table and why it is shaped that way |
| `docs/03-algorithms.md` | Routing, coverage mode, advisories, ETA, monitoring |
| `docs/04-datasets.md` | Seed datasets and the seeder design |
| `docs/05-edge-cases.md` | Expected behaviour for every failure case |
| `docs/TASKS.md` | The task list — work in order |
| `ASSUMPTIONS.md` | Scope decisions and their reasoning |

## Stack

Java 21, Spring Boot 4.0.8, PostgreSQL 16, Flyway, Maven, Docker Compose.
springdoc-openapi 3.0.3. Nine dependencies total. **Do not add more without asking.**

Note Boot 4 starter names: `spring-boot-starter-webmvc` (not `-web`),
`spring-boot-starter-flyway`, and per-module test starters.

## HARD RULES — never break these

1. **`Instant.now()` appears exactly ONCE in the codebase**, inside
   `config/SystemClock.java`. Everywhere else, inject `ClockProvider`. This is what
   makes the simulation possible; retrofitting it later means touching every class.

2. **Nothing in `domain/` imports `org.springframework`.** Domain classes are plain
   Java, built with `new`, dependencies passed via constructor. Spring lives only in
   `config/`, `controller/`, `service/`, `repository/`. If you are tempted to add
   `@Service` to a class in `domain/`, it belongs in `service/` instead.

3. **No hardcoded counts.** Not 22 tankers, not 60 villages, not 1400 farmers, not
   1250 points. Every count is a query result or a config value.

4. **Tuning numbers live in the `solver_parameter` table, not in code.** Safety buffer,
   circuity factor, equity exponent, spoilage constants, thresholds.

5. **Flyway owns the schema.** `ddl-auto: validate`. Hibernate never generates DDL.

6. **Commit messages carry no AI attribution.** Never add "Co-Authored-By"
   trailers, "Generated with Claude Code" lines, or any similar marker.
   Plain descriptive messages only.

7. **Never run git commands.** Do not stage, commit, push, or amend.
   Report what changed and stop. I will review and commit myself.

## Key domain rules

- The spoilage clock starts at **first collection**, not departure. The
  plant → first-village leg carries no milk, so it is excluded from hot time.
- Hold budget is computed from ambient temperature (Q10 rule), never a constant.
- The budget ratchets **DOWN only** when temperature rises mid-trip. Never up.
  Bacterial damage is cumulative.
- `trip_stop` **SNAPSHOTS** `route_stop` at trip creation. Trips never re-read the plan.
- `collection_point` is the routing entity; `farmer` is the attribution entity (N:1).
  One stop, one visit, N milk records.
- **Events drive trip progress. Pings only refine position.** Driving past a point is
  not collecting from it.
- Mitigations are generated and ranked, **never auto-executed**.
- Sequence optimisation minimises **hot time**, not distance. Seed farthest-first.

## Package structure

```
com.dairy.milkroute
├── config/       ClockProvider, SystemClock, scheduling, solver params
├── controller/   @RestController only. Thin. No business logic.
├── domain/       PLAIN JAVA. No Spring annotations anywhere in here.
│   ├── geo/         GeoPoint, TravelTimeProvider, HaversineTravelTime, TravelMatrix
│   ├── spoilage/    SpoilageCalculator
│   ├── routing/     ConstraintChecker, VillageSolver, RoutePlanner,
│   │                SequenceOptimiser, TankerAssigner, FeasibilityAssessor
│   ├── tracking/    PositionResolver, EtaCalculator
│   ├── trip/        TripStateMachine, EventReplayer
│   └── advisory/    TimingAdvisory
├── dto/          request/ and response/
├── entity/       JPA entities
├── enums/
├── error/        GlobalExceptionHandler, custom exceptions
├── mapper/       Entity ↔ DTO
├── repository/   Spring Data interfaces
├── security/     RoleInterceptor stub
├── service/      Spring beans. Orchestration and transactions.
└── simulation/   VirtualClock, VirtualDriver, SimulationEngine
```

## Conventions

- Migrations: `src/main/resources/db/migration/V{n}__{name}.sql`
- Datasets: `src/main/resources/datasets/{name}.yaml`
- Scenarios: `src/main/resources/scenarios/{name}.yaml`
- Tests: JUnit 5. Domain classes tested with **no** Spring context.
- Records for value objects and DTOs where possible (Java 21).
- Sealed interfaces for closed hierarchies (mitigations, trip events).

## Workflow

Work **ONE task at a time** from `docs/TASKS.md`, in order.

For each task:
1. Summarise what the task requires before writing code.
2. For complex tasks (ConstraintChecker, RoutePlanner.candidates(),
   EventIngestionService), outline the approach first and wait for confirmation.
3. Implement.
4. Run the build and tests.
5. Explain what you wrote in plain language.
6. Mark the task complete in `docs/TASKS.md` and commit with a descriptive message.

Do not implement tasks beyond the one you were asked for. Ask before deviating from
the spec.

## Time budget

This is a 48-hour take-home with roughly 24 working hours remaining. Prefer correct and
simple over complete and clever. If a task is overrunning, say so rather than
continuing — there is a documented cut list in `docs/TASKS.md`.

