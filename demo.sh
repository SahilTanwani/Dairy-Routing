#!/usr/bin/env bash
#
# demo.sh — the guided tour.
#
# Three datasets, four plans, one advisory. The point of the sequence is not that the
# endpoints return 200; it is that the same code, given three different dairies, reports
# three different reasons for failing to serve everybody — and names which one binds.
#
#   Act 1  baseline        MORNING at 22 C   the driver roster binds
#   Act 2  heat-crisis     EVENING at 35 C   spoilage binds
#   Act 3  heat-crisis     the timing advisory: two hours later is worth 18 points of coverage
#   Act 4  sparse-district EVENING at 30 C   geography binds, and the plan says who it dropped
#
# Usage:
#   ./demo.sh                 run straight through
#   PAUSE=1 ./demo.sh         stop between acts (for a live walkthrough)
#   BASE_URL=http://host:8080 ./demo.sh
#   VERBOSE=1 ./demo.sh       print whole response bodies, not just the summary lines
#
# Requires: bash, curl. jq is used if present and not needed if it is not.
# Reseeding wipes the database, so the app must be running with the dev or sim profile —
# which is what docker-compose.yml sets.

set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}/api/v1"
PAUSE="${PAUSE:-0}"
VERBOSE="${VERBOSE:-0}"

# ─── output helpers ────────────────────────────────────────────────────────────────────

if [ -t 1 ] && command -v tput >/dev/null 2>&1 && [ "$(tput colors 2>/dev/null || echo 0)" -ge 8 ]; then
    BOLD=$(tput bold); DIM=$(tput dim); CYAN=$(tput setaf 6); YELLOW=$(tput setaf 3); OFF=$(tput sgr0)
else
    BOLD=""; DIM=""; CYAN=""; YELLOW=""; OFF=""
fi

rule()    { printf '%s\n' "${DIM}────────────────────────────────────────────────────────────────────────────${OFF}"; }
act()     { printf '\n\n%s\n' "${BOLD}${CYAN}══ $* ══${OFF}"; }
say()     { printf '%s\n' "$*"; }
note()    { printf '%s\n' "${DIM}$*${OFF}"; }
result()  { printf '%s\n' "${BOLD}${YELLOW}→ $*${OFF}"; }

pause_here() {
    [ "$PAUSE" = "1" ] || return 0
    [ -t 0 ] || return 0
    printf '\n%s' "${DIM}[enter to continue]${OFF}"; read -r _
}

# Pretty-print JSON if a pretty-printer is around; otherwise print it as it came.
pretty() {
    if command -v jq >/dev/null 2>&1; then jq .
    elif command -v python3 >/dev/null 2>&1; then python3 -m json.tool 2>/dev/null || cat
    else cat
    fi
}

# field <key> — pull one top-level value out of $RESPONSE without needing jq. Records
# serialise their components in declaration order, so the *first* match of a key is the
# top-level one even when the same name repeats inside a nested array — hence grep -o
# piped to head, rather than a sed substitution, whose greedy .* would find the last.
field() {
    printf '%s' "$RESPONSE" \
        | grep -o "\"$1\"[[:space:]]*:[[:space:]]*\(\"[^\"]*\"\|[^,}]*\)" \
        | head -1 \
        | sed "s/^\"$1\"[[:space:]]*:[[:space:]]*//" \
        | tr -d '"'
}

# call <METHOD> <path> [json body] — show the request, make it, keep the body in $RESPONSE.
RESPONSE=""
call() {
    local method="$1" path="$2" body="${3:-}" code
    if [ -n "$body" ]; then
        note "\$ curl -X $method $BASE$path -d '$body'"
        RESPONSE=$(curl -sS -m 600 -X "$method" "$BASE$path" \
            -H 'Content-Type: application/json' -d "$body" -w $'\n%{http_code}')
    else
        note "\$ curl -X $method $BASE$path"
        RESPONSE=$(curl -sS -m 600 -X "$method" "$BASE$path" -w $'\n%{http_code}')
    fi
    code="${RESPONSE##*$'\n'}"
    RESPONSE="${RESPONSE%$'\n'*}"

    case "$code" in
        2*) ;;
        *)  printf '%s\n' "HTTP $code"; printf '%s\n' "$RESPONSE" | pretty; exit 1 ;;
    esac
    [ "$VERBOSE" = "1" ] && printf '%s\n' "$RESPONSE" | pretty
    return 0
}

# ─── preflight ─────────────────────────────────────────────────────────────────────────

command -v curl >/dev/null 2>&1 || { echo "demo.sh needs curl"; exit 1; }

printf '\n%s\n' "${BOLD}MilkRoute — collection routing under a spoilage clock${OFF}"
note "against $BASE"

printf '\n'
note "waiting for the app to answer /health ..."
for attempt in $(seq 1 60); do
    if curl -sSf -m 5 "$BASE/health" >/dev/null 2>&1; then break; fi
    [ "$attempt" = "60" ] && { echo "app never came up — is docker compose running?"; exit 1; }
    sleep 2
done
call GET /health
result "$(field status) — server clock $(field timestamp)"

# ═══ ACT 1 ═════════════════════════════════════════════════════════════════════════════

act "ACT 1 · The comfortable morning that is not comfortable"

say "Seed the baseline dairy: 60 villages along seven roads out of the plant, ~1,250"
say "collection points, ~1,400 farmers, 22 tankers, 26 drivers. Everything below is"
say "generated from datasets/baseline.yaml and a fixed random seed, so it is the same"
say "dairy every time this script runs."
rule
call POST "/admin/reseed?dataset=baseline"
POINTS=$(field points)
result "$(field villages) villages · $(field points) points · $(field farmers) farmers · $(field tankers) tankers · $(field drivers) drivers"

say ""
say "Before planning anything, ask the arithmetic question: how many tanker-minutes does"
say "this dairy need, and how many does the fleet have? A tanker is bounded by two things"
say "at once — the milk spoils, and the driver goes home — so it can only contribute"
say "min(hold budget, driver shift) minutes of work with milk on board."
rule
call GET "/admin/dataset-check?dataset=baseline"
result "required $(field requiredHotMinutes) min · available $(field availableHotMinutes) min · ratio $(field timeRatio)"
note "Volume ratio $(field volumeRatio): five tankers would hold all the milk. The constraint is time."

say ""
say "A ratio above 1.0 says the morning does not fit. Plan it anyway and watch the system"
say "say so rather than quietly serving the easy villages and reporting success."
rule
call POST /plans '{"session":"MORNING","ambientTempC":22}'
MORNING_PLAN=$(field id)
MORNING_SERVED=$(field stopCount)
result "mode $(field mode) · $(field routeCount) routes · $MORNING_SERVED of $POINTS points · $(field litres) L · thinnest slack $(field minSlackMin) min · solved in $(field generationMs) ms"

say ""
say "The feasibility report is the sentence ops can act on."
rule
call GET "/plans/$MORNING_PLAN/feasibility"
result "$(field tankersRequired) tankers required, $(field tankerCount) on the yard"
say ""
say "${BOLD}The morning finding:${OFF} at 22 C the milk holds for 313 minutes and the driver goes"
say "home after 300, so every tanker is capped by the roster, not the weather. The gap is"
say "small — 1.06 — and the fix is free: on a 600-minute shift the same fleet plans 1,216"
say "of 1,250 points in FULL_SERVICE mode. Same tankers, same villages, one rota change."
pause_here

# ═══ ACT 2 ═════════════════════════════════════════════════════════════════════════════

act "ACT 2 · The evening, where the milk decides"

say "Same dairy, same seed, same 1,250 points — heat-crisis.yaml differs from baseline.yaml"
say "in two lines: the session and the temperature. Diff them on screen and the whole"
say "difference is the weather, which is what makes this a comparison and not an anecdote."
rule
call POST "/admin/reseed?dataset=heat-crisis"
POINTS=$(field points)
result "$(field villages) villages · $(field points) points · $(field farmers) farmers — identical to Act 1"

rule
call GET "/admin/dataset-check?dataset=heat-crisis"
result "required $(field requiredHotMinutes) min · available $(field availableHotMinutes) min · ratio $(field timeRatio)"
note "Same fleet as the morning. Half the usable minutes, because at 35 C the milk is finished in two hours."

say ""
say "Q10 rule: bacterial growth doubles every 10 C. 180 minutes at 30 C becomes 127 at"
say "35 C for a plain tanker, 193 for an insulated one. Plan against that."
rule
call POST /plans '{"session":"EVENING","ambientTempC":35}'
EVENING_PLAN=$(field id)
EVENING_SERVED=$(field stopCount)
result "mode $(field mode) · $(field routeCount) routes · $EVENING_SERVED of $POINTS points · $(field litres) L · thinnest slack $(field minSlackMin) min"
say ""
say "${BOLD}The evening finding:${OFF} 19% coverage, and the ratio of 2.55 says serving everyone"
say "would take about 61 tankers against a fleet of 22. No rostering change touches that."
say "This dairy is losing evening loads in summer and has probably stopped noticing."
pause_here

# ═══ ACT 3 ═════════════════════════════════════════════════════════════════════════════

act "ACT 3 · The lever that costs nothing"

say "Every fix for the evening costs money — more tankers, more insulation, a second"
say "chilling centre — except one. Ambient falls about 3 C an hour after the afternoon"
say "peak. Leaving two hours later collects the same milk in a cooler evening."
say ""
say "The advisory plans the dairy twice and reports both sides, so the claim can be"
say "checked rather than believed."
rule
call GET "/advisory/session-timing?session=EVENING&ambientTempC=35&shiftHours=2"
result "$(field summary)"
note "worthDoing=$(field worthDoing) · cost: $(field costToTheDairy)"
say ""
say "18 points of coverage and 66 minutes of hold budget for the price of a conversation"
say "about when farmers milk. That is the single best-value recommendation in the system."
pause_here

# ═══ ACT 4 ═════════════════════════════════════════════════════════════════════════════

act "ACT 4 · When geography is the constraint"

say "A third failure mode. sparse-district is a thinner dairy — 45 villages, 16 tankers —"
say "strung down four very long corridors. Some villages are so far out that the round"
say "trip exceeds the hold window whatever the fleet does. No routing fixes those; the"
say "honest answer is a local chilling unit, and the system should say so rather than"
say "pretend to optimise."
rule
call POST "/admin/reseed?dataset=sparse-district"
SPARSE_POINTS=$(field points)
SPARSE_VILLAGES=$(field villages)
result "$(field villages) villages · $(field points) points · $(field farmers) farmers · $(field tankers) tankers"

rule
call GET "/admin/dataset-check?dataset=sparse-district"
SPARSE_UNREACHABLE=$(field unreachableVillages)
result "ratio $(field timeRatio) · farthest village $(field farthestVillageKm) km · unreachable villages $SPARSE_UNREACHABLE"
note "unreachable = no tanker on the yard, insulated or not, can reach it and get home inside the budget."

say ""
say "Plan the evening at 30 C. Coverage mode ranks villages by litres per marginal hot"
say "minute, multiplied by an equity term that promotes whoever was skipped last time —"
say "and a village skipped three sessions running is merged before anything is ranked."
say "Pure litres-per-minute would be optimal on paper and would starve the same eight far"
say "villages every hot day until they left the cooperative."
rule
call POST /plans '{"session":"EVENING","ambientTempC":30}'
SPARSE_PLAN=$(field id)
result "mode $(field mode) · $(field routeCount) routes · $(field stopCount) of $SPARSE_POINTS points · thinnest slack $(field minSlackMin) min"

say ""
say "Publishing is what commits the plan: it moves the fairness counters and writes one"
say "exclusion row per point left out. Counters move on publish rather than generate,"
say "because a discarded draft must never mark a village as collected."
rule
call POST "/plans/$SPARSE_PLAN/publish"
result "status $(field status) · version $(field version)"

say ""
say "And this is the part a dairy can actually use: not 'coverage was 33%' but a list of"
say "who was left, in which village, and what it cost in litres."
rule
call GET "/plans/$SPARSE_PLAN/exclusions"
result "$(field excludedPoints) points excluded · $(field litresForgone) L left in farmers' cans"
note "first three rows:"
printf '%s' "$RESPONSE" | tr '{' '\n' | grep '"collectionPointCode"' | head -3 | sed \
    's/.*"collectionPointCode":"\([^"]*\)".*"villageCode":"\([^"]*\)".*"reason":"\([^"]*\)".*"litresForgone":\([0-9.]*\).*/    \1  village \2  \3  \4 L/'

say ""
say "Try to publish the same session twice and the database refuses it — one published"
say "plan per session is a unique index, not an application check."
rule
call POST /plans '{"session":"EVENING","ambientTempC":30}'
SECOND_PLAN=$(field id)
note "\$ curl -X POST $BASE/plans/$SECOND_PLAN/publish   (expects 409)"
SECOND_CODE=$(curl -sS -m 600 -o /dev/null -w '%{http_code}' -X POST "$BASE/plans/$SECOND_PLAN/publish")
result "HTTP $SECOND_CODE — uq_one_published_per_session held the line"

# ═══ CLOSE ═════════════════════════════════════════════════════════════════════════════

act "What the four acts said"

say "  Morning, 22 C   the ${BOLD}driver roster${OFF} binds     $MORNING_SERVED of $POINTS points   fix: a longer shift"
say "  Evening, 35 C   ${BOLD}spoilage${OFF} binds              $EVENING_SERVED of $POINTS points   fix: depart at 18:30"
say "  Evening, 18:30  ${BOLD}the same fleet${OFF}, cooler air   coverage 19% to 37%, no money spent"
say "  Sparse, 30 C    ${BOLD}geography${OFF} binds             $SPARSE_UNREACHABLE of $SPARSE_VILLAGES villages no fleet can reach"
say ""
say "Three different reasons, one planner, and in every case a named constraint rather"
say "than a shrug. Both morning and evening fixes are operational, not capital."
say ""
note "The database is left holding sparse-district. Reseed baseline to put it back:"
note "  curl -X POST '$BASE/admin/reseed?dataset=baseline'"
printf '\n'
