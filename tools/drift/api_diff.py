"""Signature-level diff of two RuneLite jars (api or client).

Dumps `javap -p` for every class in both jars and reports added/removed
classes and changed member signature lines. This is the "what exactly broke"
artifact attached to the drift report when the canary build fails, so a
human or agent can fix the template/docs against real changes.

Usage:
    python api_diff.py <old.jar> <new.jar> [--out report.md] [--max-lines 400]
    # example: diff the two previous real releases
    python api_diff.py C:/Users/sahij/.runelite/repository2/runelite-api-1.13.5-runtime.jar ^
                        C:/Users/sahij/.runelite/repository2/runelite-api-1.13.6-runtime.jar
"""
import argparse
import datetime
import subprocess
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

CHUNK = 40  # classes per javap invocation (Windows command-line length safety)


def classes_of(jar: Path) -> list[str]:
    out = []
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if name.endswith(".class") and not name.endswith("module-info.class"):
                out.append(name[: -len(".class")].replace("/", "."))
    return sorted(out)


def javap_dump(jar: Path, classes: list[str]) -> dict[str, list[str]]:
    """Return {class FQN: [signature lines]} for all classes."""
    javap = "javap"
    dump: dict[str, list[str]] = {}
    for i in range(0, len(classes), CHUNK):
        chunk = classes[i : i + CHUNK]
        p = subprocess.run(
            [javap, "-cp", str(jar), "-p", *chunk],
            capture_output=True, text=True, timeout=600,
        )
        if p.returncode != 0 and not p.stdout.strip():
            raise RuntimeError(f"javap failed on {jar}: {p.stderr[:500]}")
        current = None
        for line in p.stdout.splitlines():
            line = line.rstrip()
            if line.startswith("Compiled from"):
                continue
            if line == "}":
                current = None
                continue
            if line and not line.startswith(" "):
                # class header: FQN is the token following class/interface/enum
                tokens = line.split("{")[0].replace(";", "").split()
                fqn = None
                for j, tok in enumerate(tokens):
                    if tok in ("class", "interface", "enum", "@interface") and j + 1 < len(tokens):
                        fqn = tokens[j + 1]
                        break
                if fqn is None:
                    fqn = next((t for t in tokens if "." in t), tokens[-1] if tokens else None)
                current = fqn
                if current is not None:
                    dump.setdefault(current, [])
                continue
            if current is not None:
                s = line.strip()
                if s:
                    dump[current].append(s)
    return dump


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("old_jar")
    ap.add_argument("new_jar")
    ap.add_argument("--out", default=None, help="write markdown report here (default: stdout)")
    ap.add_argument("--max-lines", type=int, default=400, help="truncate changed-signature detail")
    args = ap.parse_args()

    old_jar, new_jar = Path(args.old_jar), Path(args.new_jar)
    for f in (old_jar, new_jar):
        if not f.is_file():
            print(f"missing jar: {f}", file=sys.stderr)
            return 4

    old_cls, new_cls = classes_of(old_jar), classes_of(new_jar)
    old_set, new_set = set(old_cls), set(new_cls)
    removed, added = sorted(old_set - new_set), sorted(new_set - old_set)

    dump_old = javap_dump(old_jar, [c for c in old_cls if c in old_set])
    dump_new = javap_dump(new_jar, [c for c in new_cls if c in new_set])

    changed: dict[str, tuple[list[str], list[str]]] = {}
    for c in sorted(old_set & new_set):
        o = [l for l in dump_old.get(c, []) if not l.startswith(("public class", "public interface", "public final class", "public abstract class", "class ", "interface "))]
        n = [l for l in dump_new.get(c, []) if not l.startswith(("public class", "public interface", "public final class", "public abstract class", "class ", "interface "))]
        gone = [l for l in o if l not in set(n)]
        fresh = [l for l in n if l not in set(o)]
        if gone or fresh:
            changed[c] = (gone, fresh)

    lines = [
        f"# API diff: {old_jar.name} -> {new_jar.name}",
        f"generated {datetime.datetime.now(datetime.timezone.utc).isoformat()} by tools/drift/api_diff.py",
        "",
        "## Summary",
        f"- classes: {len(old_cls)} -> {len(new_cls)}",
        f"- removed classes: **{len(removed)}**",
        f"- added classes: **{len(added)}**",
        f"- classes with changed members: **{len(changed)}**",
        "",
    ]
    if removed:
        lines += ["## Removed classes"] + [f"- `{c}`" for c in removed] + [""]
    if added:
        lines += ["## Added classes"] + [f"- `{c}`" for c in added] + [""]
    if changed:
        lines += ["## Changed members (removed / added signature lines, per class)", ""]
        shown = 0
        for c, (gone, fresh) in changed.items():
            if shown >= args.max_lines:
                lines += [f"_… truncated at {args.max_lines} lines — rerun with --max-lines to see more_", ""]
                break
            lines.append(f"### `{c}`")
            for l in gone:
                lines.append(f"- REMOVED `{l}`")
                shown += 1
            for l in fresh:
                lines.append(f"- ADDED   `{l}`")
                shown += 1
            lines.append("")

    report = "\n".join(lines)
    if args.out:
        Path(args.out).write_text(report, encoding="utf-8")
        print(f"diff report -> {args.out}")
    print(report.split("## Changed")[0])  # summary to console regardless
    return 0


if __name__ == "__main__":
    sys.exit(main())
