"""SDK drift detector / auto-fixer for kuri-plugin-dev.

Pipeline legs (see tools/drift/README.md for the full design):

  DETECT   compare the pinned SDK (tools/drift/sdk-pin.json) against the live
           RuneLite artifact repo (~/.runelite/repository2): newer versions,
           same-version content replacement, Kuri framework releases.
  VERIFY   canary-compile the template plugin against the NEWEST repo jars in
           a temp overlay (never touching the real sdk/jars).
  FIX      if the canary compiles and --apply is given: mirror the overlay
           into sdk/jars, update the pin + versions.txt (auto-fix leg).
  SIGNAL   write tools/drift/DRIFT-REPORT.md + sdk-pin.json state; exit code.
  BLOCK    the Gradle guard in templates/skeleton-plugin/build.gradle reads the
           pin state and fails stale builds (this script never builds the
           real template against drifted jars).

Exit codes:
  0  in sync
  1  drift detected AND auto-fixed (state now ok)
  2  drift detected, needs human/agent (canary failed, or apply not requested)
  3  blocked (needs_human persisted past policy.staleBlockDays, or --policy-strict)
  4  environment error (repo/gradle missing)

Examples:
  python tools/drift/check_drift.py --quick          # fast scan, no canary (cron-friendly)
  python tools/drift/check_drift.py                  # full: scan + canary compile
  python tools/drift/check_drift.py --apply          # full + auto-fix on canary success
  # test the whole pipeline against a fake newer release without touching
  # the real sdk/pin/report:
  python tools/drift/check_drift.py --repo-dir <fixture> --sdk-dir <tmp> \\
      --pin-file <tmp>/sdk-pin.json --report-file <tmp>/DRIFT-REPORT.md --apply
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]           # kuri-plugin-dev/
DEFAULT_REPO = Path.home() / ".runelite" / "repository2"
DEFAULT_SDK = ROOT / "sdk" / "jars"
DEFAULT_PROJECT = ROOT / "templates" / "skeleton-plugin"
DEFAULT_PIN = ROOT / "tools" / "drift" / "sdk-pin.json"
DEFAULT_REPORT = ROOT / "tools" / "drift" / "DRIFT-REPORT.md"
DEFAULT_GRADLE_JAVA_HOME = Path.home() / "AppData" / "Local" / "RuneLite" / "jre"

RE_API = re.compile(r"^runelite-api-(\d+(?:\.\d+)*)(-runtime)?\.jar$")
RE_CLIENT = re.compile(r"^client-(\d+(?:\.\d+)*)\.jar$")
RE_INJECTED = re.compile(r"^injected-client-(\d+(?:\.\d+)*)\.jar$")
RE_KURI = re.compile(r"^kuri-runelite-(v[\d.]+)\.jar$", re.IGNORECASE)
RE_MAGIC = b"KURIKENC"

STATUS_OK = "ok"
STATUS_FIXED = "auto-fixed"
STATUS_HUMAN = "needs_human"
STATUS_BLOCKED = "blocked"

def now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")

def now_ms() -> int:
    return int(datetime.datetime.now(datetime.timezone.utc).timestamp() * 1000)

def ver_tuple(v: str):
    return tuple(int(x) for x in v.split("."))

def vstr(t) -> str:
    return ".".join(str(x) for x in t)


# ---------------------------------------------------------------- scan

def scan_repo(repo: Path) -> dict:
    """Inventory the artifact repo. Returns newest per family + untouched 'other' jars."""
    out = {
        "runeliteApi": None, "client": None, "kuri": None,
        "other": [],              # (name, path) — non-versioned-family root jars
        "apiVersions": {}, "clientVersions": {}, "kuriVersions": {},  # ver -> sha256
        "okhttp": None,
    }
    def sha(p: Path) -> str:
        return hashlib.sha256(p.read_bytes()).hexdigest()

    if not repo.is_dir():
        raise FileNotFoundError(f"artifact repo not found: {repo}")

    for f in sorted(repo.glob("*.jar")):
        if (m := RE_API.match(f.name)):
            out["apiVersions"][m.group(1)] = sha(f)
        elif (m := RE_CLIENT.match(f.name)):
            out["clientVersions"][m.group(1)] = sha(f)
        elif RE_INJECTED.match(f.name):
            pass  # not used for compilation
        else:
            out["other"].append((f.name, f))
        if f.name.startswith("okhttp-"):
            out["okhttp"] = f.name
    if (k := repo / "k").is_dir():
        for f in sorted(k.glob("kuri-runelite-*.jar")):
            m = RE_KURI.match(f.name)
            if m:
                out["kuriVersions"][m.group(1)] = sha(f)

    if out["apiVersions"]:
        v = max(out["apiVersions"], key=ver_tuple)
        out["runeliteApi"] = (v, repo / f"runelite-api-{v}-runtime.jar")
    if out["clientVersions"]:
        v = max(out["clientVersions"], key=ver_tuple)
        out["client"] = (v, repo / f"client-{v}.jar")
    if out["kuriVersions"]:
        v = max(out["kuriVersions"], key=lambda s: ver_tuple(s.lstrip("v")))
        out["kuri"] = (v, repo / "k" / f"kuri-runelite-{v}.jar")
    return out


def kuri_hook_check(jar: Path, has_okhttp: bool) -> dict:
    """Static checks for the capture-agent assumptions against a framework jar."""
    res = {"jar": jar.name, "containsKurikencMarker": False, "okhttpOnRepo": has_okhttp}
    try:
        import zipfile
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if name.endswith(".class") and name.startswith("k/"):
                    if RE_MAGIC in z.read(name):
                        res["containsKurikencMarker"] = True
                        break
    except Exception as e:  # unreadable jar = its own signal
        res["error"] = str(e)
    return res


# ---------------------------------------------------------------- staging / sync

def stage_overlay(repo: Path, scan: dict, sdk_dir: Path, stage_dir: Path) -> int:
    """Build the 'what the SDK would become' dir: newest api/client + all
    non-versioned-family libs from the repo + newest Kuri jar under kuri/."""
    n = 0
    for fam in ("runeliteApi", "client"):
        if scan[fam]:
            shutil.copy2(scan[fam][1], stage_dir / scan[fam][1].name)
            n += 1
    for name, path in scan["other"]:
        if RE_INJECTED.match(name):
            continue
        shutil.copy2(path, stage_dir / name)
        n += 1
    if scan["kuri"]:
        (stage_dir / "kuri").mkdir(exist_ok=True)
        shutil.copy2(scan["kuri"][1], stage_dir / "kuri" / scan["kuri"][1].name)
        n += 1
    return n


def apply_sync(stage_dir: Path, sdk_dir: Path) -> int:
    """Mirror the staged overlay into the real sdk/jars (jars only)."""
    n = 0
    for old in list(sdk_dir.glob("*.jar")) + list((sdk_dir / "kuri").glob("*.jar")):
        old.unlink()
    for f in stage_dir.glob("*.jar"):
        shutil.copy2(f, sdk_dir / f.name)
        n += 1
    for f in (stage_dir / "kuri").glob("*.jar"):
        shutil.copy2(f, sdk_dir / "kuri" / f.name)
        n += 1
    return n


VERSIONS_TXT_TEMPLATE = """\
# sdk/jars — mirrored from the local RuneLite artifact repository by
# tools/drift/check_drift.py --apply. The machine-readable pin lives in
# tools/drift/sdk-pin.json; this file is regenerated on every sync — make
# durable notes in docs/ instead of editing the generated section below.

# pinned: runelite-api {api} | client {client} | kuri-runelite {kuri}  (synced {date})

# >>> generated section (tools/drift/check_drift.py --apply)
{jars}
# <<< generated section

# Notes:
# - runelite-api/client compile plugins standalone (class file major 55 = Java 11).
# - The remaining jars are the client's compile-time libs (guice/guava/slf4j/okhttp/...).
# - kuri/kuri-runelite-*.jar is Kuri's PROPRIETARY framework jar: included ONLY so
#   tools/ConstDump.java and tools/EnumDump.java can run against decrypted Kuri
#   plugin jars. Never link com.kuri.* from a plugin; never distribute this jar.
# - If Gradle cannot find a JDK 11 toolchain, run it with
#   -Dorg.gradle.java.installations.paths="C:\\Program Files\\Eclipse Adoptium\\jdk-11.0.28.6-hotspot"
#   (see sdk/jdk-notes.md).
"""


def write_versions_txt(sdk_dir: Path, api: str, client: str, kuri: str, jar_names: list[str]) -> None:
    (sdk_dir / "versions.txt").write_text(
        VERSIONS_TXT_TEMPLATE.format(
            api=api, client=client, kuri=kuri, date=now(),
            jars="\n".join(sorted(jar_names)),
        ),
        encoding="utf-8",
    )


# ---------------------------------------------------------------- canary

def run_canary(project: Path, stage_dir: Path, gradle_java_home: Path | None) -> tuple[bool, str]:
    gradle = shutil.which("gradle")
    if not gradle:
        return False, "gradle not found on PATH"
    env = os.environ.copy()
    if gradle_java_home and gradle_java_home.is_dir():
        env["JAVA_HOME"] = str(gradle_java_home)
    cmd = [gradle, "-p", str(project), "clean", "build", "--no-daemon", "--console=plain",
           "-PskipSdkGuard=true", f"-PsdkDir={stage_dir}"]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=1200)
    except subprocess.TimeoutExpired:
        return False, "canary build timed out"
    out = ((p.stdout or "") + (p.stderr or ""))[-4000:]
    return p.returncode == 0, out


# ---------------------------------------------------------------- pin & report

def load_pin(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def save_pin(path: Path, pin: dict) -> None:
    pin["lastCheck"] = now()
    pin["lastCheckEpochMs"] = now_ms()
    path.write_text(json.dumps(pin, indent=2) + "\n", encoding="utf-8")


def write_report(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")
    print(f"report -> {path}")


def md_table(rows: list[list[str]]) -> str:
    if not rows:
        return "_nothing to report_\n"
    head = "| " + " | ".join(rows[0]) + " |\n| " + " | ".join("---" for _ in rows[0]) + " |\n"
    return head + "".join("| " + " | ".join(r) + " |\n" for r in rows[1:])


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--quick", action="store_true", help="version/hash scan only — no canary compile")
    ap.add_argument("--apply", action="store_true", help="auto-fix (sync sdk/jars) when the canary compiles")
    ap.add_argument("--policy-strict", action="store_true", help="escalate straight to blocked on unresolvable drift")
    ap.add_argument("--stale-block-days", type=int, default=None, help="override policy.staleBlockDays")
    ap.add_argument("--repo-dir", type=Path, default=DEFAULT_REPO)
    ap.add_argument("--sdk-dir", type=Path, default=DEFAULT_SDK)
    ap.add_argument("--project-dir", type=Path, default=DEFAULT_PROJECT)
    ap.add_argument("--pin-file", type=Path, default=DEFAULT_PIN)
    ap.add_argument("--report-file", type=Path, default=DEFAULT_REPORT)
    ap.add_argument("--gradle-java-home", type=Path, default=DEFAULT_GRADLE_JAVA_HOME)
    args = ap.parse_args()

    try:
        pin = load_pin(args.pin_file)
    except FileNotFoundError:
        print(f"pin file missing: {args.pin_file}", file=sys.stderr)
        return 4
    policy = pin.get("policy", {})
    stale_block_days = args.stale_block_days or policy.get("staleBlockDays", 14)

    try:
        scan = scan_repo(args.repo_dir)
    except FileNotFoundError as e:
        print(f"ENVIRONMENT ERROR: {e}", file=sys.stderr)
        return 4

    # ---------- drift computation ----------
    drift = []          # list of (kind, detail)
    fixable = True      # canary outcome decides
    now_pin_api = pin.get("runeliteApi")
    now_pin_client = pin.get("client")
    now_pin_kuri = pin.get("kuriFramework")

    repo_api = scan["runeliteApi"]
    repo_client = scan["client"]
    repo_kuri = scan["kuri"]

    if repo_api and ver_tuple(repo_api[0]) > ver_tuple(now_pin_api):
        drift.append(("newer-release", f"runelite-api {repo_api[0]} > pinned {now_pin_api}"))
    if repo_client and ver_tuple(repo_client[0]) > ver_tuple(now_pin_client):
        drift.append(("newer-release", f"client {repo_client[0]} > pinned {now_pin_client}"))

    # same-version content replacement
    sdk_api_jar = args.sdk_dir / f"runelite-api-{now_pin_api}-runtime.jar"
    sdk_client_jar = args.sdk_dir / f"client-{now_pin_client}.jar"
    if repo_api and now_pin_api in scan["apiVersions"] and sdk_api_jar.is_file():
        if scan["apiVersions"][now_pin_api] != hashlib.sha256(sdk_api_jar.read_bytes()).hexdigest():
            drift.append(("content-replaced", f"sdk runelite-api {now_pin_api} differs from repository2 copy"))
    if repo_client and now_pin_client in scan["clientVersions"] and sdk_client_jar.is_file():
        if scan["clientVersions"][now_pin_client] != hashlib.sha256(sdk_client_jar.read_bytes()).hexdigest():
            drift.append(("content-replaced", f"sdk client {now_pin_client} differs from repository2 copy"))

    # Kuri framework leg (analysis tooling, never blocks plugin builds)
    kuri_note = None
    kuri_checks = None
    if repo_kuri and now_pin_kuri and repo_kuri[0] != now_pin_kuri:
        kuri_note = f"kuri-runelite {repo_kuri[0]} available (pinned {now_pin_kuri})"
        kuri_checks = kuri_hook_check(repo_kuri[1], bool(scan["okhttp"]))

    report = ["# SDK Drift Report", f"generated {now()}", "",
              f"- pin: runelite-api {now_pin_api} | client {now_pin_client} | kuri {now_pin_kuri}",
              f"- repo newest: runelite-api {repo_api[0] if repo_api else 'n/a'} | "
              f"client {repo_client[0] if repo_client else 'n/a'} | kuri {repo_kuri[0] if repo_kuri else 'n/a'}",
              f"- mode: {'quick' if args.quick else 'full'}{' + apply' if args.apply else ''}", ""]

    # ---------- no drift: possibly self-heal ----------
    if not drift:
        healed = pin["status"] in (STATUS_HUMAN, STATUS_BLOCKED, STATUS_FIXED)
        if healed:
            pin.update({"status": STATUS_OK, "reason": None,
                        "needsHumanSince": None, "needsHumanSinceEpochMs": None})
        save_pin(args.pin_file, pin)
        report += ["## Result", "in sync — no drift.", ""]
        if healed:
            report += ["_note: previous drift condition is resolved; state reset to ok._", ""]
        if kuri_note:
            report += ["## Kuri framework (informational)", kuri_note,
                       "Hook checks: " + json.dumps(kuri_checks), "",
                       "Capture-agent maintenance checklist: tools/capture-agent/USAGE.md.", ""]
        write_report(args.report_file, "\n".join(report))
        print("OK: sdk pin in sync with repository2")
        return 0

    # ---------- drift: quick mode stops at the signal ----------
    if args.quick:
        if pin["status"] == STATUS_OK:
            pin.update({"status": STATUS_HUMAN,
                        "reason": "; ".join(f"{k}: {d}" for k, d in drift),
                        "needsHumanSince": now(), "needsHumanSinceEpochMs": now_ms(),
                        "lastCheckKind": "quick"})
            save_pin(args.pin_file, pin)
        report += ["## Result", "DRIFT DETECTED (quick scan):", "",
                   md_table([["kind", "detail"]] + [[k, d] for k, d in drift]),
                   "Run the full check to canary-compile and (optionally) auto-fix:",
                   "`python tools/drift/check_drift.py --apply`", ""]
        write_report(args.report_file, "\n".join(report))
        print("DRIFT (quick): " + "; ".join(f"{k}: {d}" for k, d in drift))
        return 2

    # ---------- full mode: canary compile against the newest jars ----------
    stage_dir = args.sdk_dir.parent / ".drift-stage"
    if stage_dir.exists():
        shutil.rmtree(stage_dir)
    stage_dir.mkdir(parents=True)
    staged = stage_overlay(args.repo_dir, scan, args.sdk_dir, stage_dir)
    ok, out = run_canary(args.project_dir, stage_dir, args.gradle_java_home)
    report += ["## Drift detected", md_table([["kind", "detail"]] + [[k, d] for k, d in drift])]

    if ok:
        report += ["## Canary build", f"**PASS** — template compiled against newest jars ({staged} staged in {stage_dir})."]
        if args.apply:
            if args.sdk_dir.resolve() == DEFAULT_SDK.resolve() and args.pin_file.resolve() != DEFAULT_PIN.resolve():
                print("refusing: --apply with default sdk but non-default pin (fixture misuse)", file=sys.stderr)
                return 4
            moved = apply_sync(stage_dir, args.sdk_dir)
            write_versions_txt(args.sdk_dir, repo_api[0], repo_client[0],
                               repo_kuri[0] if repo_kuri else now_pin_kuri,
                               [p.name for p in args.sdk_dir.glob("*.jar")]
                               + ["kuri/" + p.name for p in (args.sdk_dir / "kuri").glob("*.jar")])
            pin.update({"runeliteApi": repo_api[0], "client": repo_client[0],
                        "kuriFramework": repo_kuri[0] if repo_kuri else now_pin_kuri,
                        "pinnedAt": now(), "pinnedAtEpochMs": now_ms(),
                        "status": STATUS_FIXED, "reason": None,
                        "needsHumanSince": None, "needsHumanSinceEpochMs": None,
                        "lastCheckKind": "full",
                        "canary": {"compiled": True, "against": repo_api[0]}})
            save_pin(args.pin_file, pin)
            report += ["## Auto-fix", f"**APPLIED** — mirrored {moved} jars into {args.sdk_dir}; "
                       f"pin now runelite-api {repo_api[0]} / client {repo_client[0]}.",
                       "", "## Kuri framework (informational)",
                       (kuri_note + "  Hook checks: " + json.dumps(kuri_checks)) if kuri_note else "no change", ""]
            write_report(args.report_file, "\n".join(report))
            print(f"FIXED: sdk synced to runelite-api {repo_api[0]} / client {repo_client[0]}")
            shutil.rmtree(stage_dir, ignore_errors=True)
            return 1
        pin.update({"status": STATUS_HUMAN,
                    "reason": "drift is auto-fixable; rerun with --apply",
                    "needsHumanSince": pin.get("needsHumanSince") or now(),
                    "needsHumanSinceEpochMs": pin.get("needsHumanSinceEpochMs") or now_ms(),
                    "lastCheckKind": "full",
                    "canary": {"compiled": True, "against": repo_api[0]}})
        save_pin(args.pin_file, pin)
        report += ["## Action required", "Drift is auto-fixable — rerun with `--apply` to sync.", ""]
        write_report(args.report_file, "\n".join(report))
        print("DRIFT (auto-fixable): rerun with --apply")
        return 2

    # canary FAILED → the interesting failure: produce the signature diff
    report += ["## Canary build", "**FAIL** — template does not compile against the newest jars.",
               "", "```", out.strip(), "```", ""]
    diff_file = args.report_file.parent / "api-diff.md"
    diff_rc = subprocess.run(
        [sys.executable, str(Path(__file__).parent / "api_diff.py"),
         str(sdk_api_jar if sdk_api_jar.is_file() else args.sdk_dir / f"runelite-api-{now_pin_api}.jar"),
         str(repo_api[1]), "--out", str(diff_file)],
        capture_output=True, text=True)
    if diff_rc.returncode == 0:
        report += ["## Signature diff", f"see `{diff_file.name}` (removed/added classes + changed members) — "
                   "use it to update the template and docs, then rerun `python tools/drift/check_drift.py --apply`.", ""]
    else:
        report += ["## Signature diff", f"api_diff failed: {diff_rc.stderr[:300]}", ""]

    age_days = None
    if pin.get("needsHumanSinceEpochMs"):
        age_days = (now_ms() - pin["needsHumanSinceEpochMs"]) / 86400000.0
    if args.policy_strict or (age_days is not None and age_days > stale_block_days):
        new_status, esc = STATUS_BLOCKED, (f"strict policy" if args.policy_strict
                                           else f"needs_human persisted {age_days:.0f}d > {stale_block_days}d")
    else:
        new_status, esc = STATUS_HUMAN, "canary compile failed"
    pin.update({"status": new_status, "reason": "; ".join(f"{k}: {d}" for k, d in drift) + f" ({esc})",
                "needsHumanSince": pin.get("needsHumanSince") or now(),
                "needsHumanSinceEpochMs": pin.get("needsHumanSinceEpochMs") or now_ms(),
                "lastCheckKind": "full",
                "canary": {"compiled": False, "against": repo_api[0]}})
    save_pin(args.pin_file, pin)
    report += ["## Action required (human/agent)",
               "1. Read the canary errors + `api-diff.md`.",
               "2. Update `templates/skeleton-plugin` (and affected `docs/`) to the changed API.",
               "3. Rebuild the template, then rerun `python tools/drift/check_drift.py --apply` to re-pin.",
               f"4. Status is `{new_status}` ({esc}); the Gradle guard blocks stale builds while it holds.", ""]
    write_report(args.report_file, "\n".join(report))
    print(f"DRIFT UNRESOLVED ({new_status}): see {args.report_file}")
    shutil.rmtree(stage_dir, ignore_errors=True)
    return 3 if new_status == STATUS_BLOCKED else 2


if __name__ == "__main__":
    sys.exit(main())
