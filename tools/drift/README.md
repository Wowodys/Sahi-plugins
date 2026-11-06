# SDK Drift Pipeline — detect → verify → fix → signal → block

RuneLite releases land every few days and the local client auto-updates, so
`sdk/jars/` (the pinned compile SDK) drifts from what actually runs. The Kuri
framework jar and the capture agent's hook assumptions drift on their own
schedule. This pipeline makes drift **loud and self-healing where safe,
blocking where not** — a stale jar can never ship silently.

## What drifts (four kinds)

| Kind | Example | Detected by | Severity |
|---|---|---|---|
| newer-release | repo has `client-1.13.7.jar` > pinned 1.13.6 | file scan | blocks builds until resolved |
| content-replaced | same-version jar in repo differs byte-wise from sdk copy | sha256 | blocks builds until resolved |
| canary-fail | newest jars don't compile the template (API changed) | canary build | needs_human → blocked after policy window |
| kuri-framework | new `kuri-runelite-v1.7.65.jar`; capture-agent hooks may shift | file scan + magic/okhttp checks | informational (analysis tooling, never blocks plugin builds) |

## Components

```
tools/drift/check_drift.py     orchestrator: detect → canary → fix → state+report
tools/drift/api_diff.py        javap signature diff (the "what broke" artifact)
tools/drift/sdk-pin.json       machine state: pin + status + policy (read by the Gradle guard)
tools/drift/DRIFT-REPORT.md    human/agent-readable report of the latest run (rewritten per run)
tools/drift/api-diff.md        signature diff from the latest failed canary (if any)
templates/skeleton-plugin/build.gradle   native Gradle guard task `checkSdkPin` (the BLOCK leg)
sdk/jars/versions.txt          regenerated on every successful sync (marker-delimited section)
```

## Flow

```
            ┌──────────────── every build (templates/skeleton-plugin) ────────────────┐
            │  gradle build → checkSdkPin task:                                       │
            │    pin status ok + repo == pin → proceed                                │
            │    status needs_human/blocked OR repo > pin → FAIL THE BUILD            │
            │    (-PallowStaleSdk=true downgrades to loud warning)                    │
            └────────────────────────────────────────────────────────────────────────-┘

  scheduled/agent/human runs:  python tools/drift/check_drift.py [--apply]

     DETECT (always)        new/same-version drift vs sdk-pin.json
        │
        ├─ no drift ──► status ok (self-heals a stale needs_human) ──► exit 0
        │
        └─ drift
            ├─ --quick ──► status needs_human + DRIFT-REPORT.md ──► exit 2   (cron signal)
            │
            └─ full: stage overlay (newest jars, temp dir) → CANARY BUILD
                 ├─ compiles + --apply ──► mirror into sdk/jars, re-pin,
                 │    regenerate versions.txt ──► status auto-fixed ──► exit 1
                 ├─ compiles, no --apply ──► needs_human ("rerun with --apply") ──► exit 2
                 └─ fails ──► api_diff.py (old→new signatures) into api-diff.md
                      + needs_human ──► exit 2
                      (escalates to blocked after staleBlockDays, or immediately
                       with --policy-strict) ──► exit 3
```

## State machine (`sdk-pin.json` .status)

| Status | Meaning | Guard effect |
|---|---|---|
| `ok` | pin matches repo | build proceeds |
| `auto-fixed` | just synced to newer jars (next check promotes to `ok`) | build proceeds |
| `needs_human` | drift unresolved (canary failed, or fix not applied yet) | **build FAILS** |
| `blocked` | needs_human persisted > `policy.staleBlockDays` (default 14), or `--policy-strict` | **build FAILS** |

Guard overrides: `-PallowStaleSdk=true` (build anyway, loudly), `-PskipSdkGuard=true`
(used by the pipeline's own canary), `-PruneliteRepo=<dir>` (test fixtures).

## How to run it (the three operators)

**Automation (cron):** daily quick scan — signal only, cheap, no builds:

```
python tools/drift/check_drift.py --quick      # exit 0 ok / 2 drift → then full+apply
```

**Agent (e.g. this coding agent, on the cron's report):** run the full check
with `--apply`; if exit 2 with "auto-fixable", apply; if exit 3 (canary
failed), read `DRIFT-REPORT.md` + `api-diff.md`, fix `templates/` and
affected `docs/` to the changed signatures, rebuild, rerun `--apply` to
re-pin, and confirm the guard passes.

**Human:** same as the agent, starting from `DRIFT-REPORT.md`'s
"Action required" section. `api-diff.md` lists exactly which classes/members
were removed, added, or changed between the pinned and newest jar, so the fix
is mechanical.

## Exit codes (for automation wiring)

| Code | Meaning |
|---|---|
| 0 | in sync |
| 1 | drift detected **and auto-fixed** |
| 2 | drift detected, needs human/agent |
| 3 | blocked (policy window exceeded / strict) |
| 4 | environment error (repo or gradle missing, fixture misuse) |

## Safety properties

- The canary build compiles against a **temp overlay** — the real `sdk/jars`
  is never touched unless the canary **succeeded** and `--apply` was passed.
- `--apply` refuses fixture misuse (non-default `--pin-file` with default sdk dir).
- Old api/client versions are removed from `sdk/jars` on sync — duplicate
  version jars on one javac classpath are a silent-wrong-classes hazard.
- `kuri/` framework jar updates never auto-block plugin builds; they only
  annotate the report and the capture-agent maintenance checklist
  (`tools/capture-agent/USAGE.md`).
- The guard is native Gradle (JsonSlurper + file scan) — no Python needed at
  build time; a missing `sdk-pin.json` only warns, never fabricates a status.

## Testing the pipeline (all verified 2026-11-03 — see root README log)

```bash
cd /c/Users/sahij/Documents/kuri-plugin-dev

# 1. quick + full on the real (in-sync) environment
python tools/drift/check_drift.py --quick          # → exit 0
python tools/drift/check_drift.py                  # → exit 0 (canary PASS, nothing changed)

# 2. simulated drift + auto-fix, fully sandboxed:
TMP=$(mktemp -d)
cp -r sdk/jars "$TMP/sdk"
mkdir -p "$TMP/repo/k" && cp /c/Users/sahij/.runelite/repository2/*.jar "$TMP/repo/"
cp /c/Users/sahij/.runelite/repository2/k/kuri-runelite-v1.7.64.jar "$TMP/repo/k/"
# rename newest api/client copies to a fake newer version:
cp "$TMP/repo/runelite-api-1.13.6-runtime.jar" "$TMP/repo/runelite-api-1.13.7-runtime.jar"
cp "$TMP/repo/client-1.13.6.jar" "$TMP/repo/client-1.13.7.jar"
cp tools/drift/sdk-pin.json "$TMP/pin.json"
python tools/drift/check_drift.py --repo-dir "$TMP/repo" --sdk-dir "$TMP/sdk" \
    --pin-file "$TMP/pin.json" --report-file "$TMP/DRIFT-REPORT.md" --apply
#   → exit 1 (FIXED), $TMP/sdk now holds the "1.13.7" jars, pin updated, real files untouched

# 3. real signature diff between two actual releases:
python tools/drift/api_diff.py /c/Users/sahij/.runelite/repository2/runelite-api-1.13.5-runtime.jar \
                               /c/Users/sahij/.runelite/repository2/runelite-api-1.13.6-runtime.jar

# 4. guard block tests:
gradle build --no-daemon -PruneliteRepo="$TMP/repo"      # → checkSdkPin FAILS (repo newer)
gradle build --no-daemon -PruneliteRepo="$TMP/repo" -PallowStaleSdk=true   # → builds with warnings
# (temporarily set sdk-pin.json .status="blocked" → plain build fails; restore afterwards)
```

## Suggested cadence

- `--quick` daily (cheap, no network beyond the local disk — the repo is the signal).
- full `--apply` run when quick signals drift, or weekly as a safety net.
- Before shipping any plugin jar, run full `--apply` and confirm `checkSdkPin`
  passes — that is the contract that the jar was compiled against what the
  installed client actually runs.
