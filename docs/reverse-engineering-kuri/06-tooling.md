# 06 — Tooling Reference: Exact Commands

> Every command in this doc was executed and verified inside `kuri-plugin-dev/`
> (see the verification log in the root `README.md`). Paths are relative to the
> folder root unless marked absolute.

## 0. Prerequisites on this machine

| Tool | Where | Notes |
|---|---|---|
| Python 3.13 | on PATH | `kenc_decrypt.py`, `deob_strings.py`, `fs_watcher.py` |
| `cryptography` pkg | `pip install --user cryptography` (v50.0.2 installed) | needed only by `kenc_decrypt.py decrypt`. **Caution:** `python -I` hides user-site packages — run the decryptor from a safe working directory without `-I`, and never `cd` into untrusted-data folders before running interpreters |
| JDK 11 (Temurin 11.0.28) | `C:\Program Files\Eclipse Adoptium\jdk-11.0.28.6-hotspot` | on PATH as `java` — fine for running dumps and CFR |
| JDK 17 (Temurin 17.0.19) | `C:\Users\sahij\AppData\Local\RuneLite\jre` | the client's own runtime; use for Gradle (see `sdk/jdk-notes.md`) |
| Gradle 9.1.0 | on PATH | building plugins (needs a 17+ JVM to run; set `JAVA_HOME` to the JDK 17 dir) |

## 1. `kenc_decrypt.py` — KENC v1 decryptor

**What it is**: standalone parser/decryptor for the Kuri container format
([01-kenc-container-format.md](01-kenc-container-format.md)). Uses only
`cryptography`'s AESGCM — no Kuri code involved.

**Analyze** (no keys needed — header parse only):

```bash
cd tools
python kenc_decrypt.py analyze testdata/test-container.kenc
# {"version": "1.1", "plaintext_len": 472, "nonce_hex": "9f84034b42c0bae244cb789d",
#  "tag_hex": "c93a24ac...", "file_sha256_hex": "de104596...",
#  "file": "test-container.kenc", "file_size": 524, "filename_matches_sha256": false}
```

Accepts multiple paths; prints one JSON block each. Tells you immediately:
format version, plaintext length (`file_size - 52`), nonce, tag, container
hash, and whether the filename follows the content-addressing convention.

**Decrypt** (needs the plan item — from a load-plan capture or a saved JSON):

```bash
cd tools
python kenc_decrypt.py decrypt testdata/test-container.kenc testdata/test-plan.json out.jar
# decrypted -> out.jar (472 bytes)
```

The plan JSON needs: `contentKey` (base64, 32 B), `nonce` (base64, 12 B — hex
also accepted), `pluginId`, `version`, `plaintextSha256` (hex), optional
`fileName` (default output name). All of these are in every load-plan item
(see the sample in `capture/load-plan.json` in the analysis folder, and
[04-transport-and-capture.md](04-transport-and-capture.md)).

**Failure modes** (all are format working as designed):

- `AEADBadTagException` → wrong `pluginId`/`version`/`plaintextSha256` in the
  plan (they are all inside the AAD — see 01 §4), or wrong/truncated key.
- `Header nonce does not match plan nonce` → plan belongs to a different
  container. (Historical note: the tool compared the header's *hex* nonce
  against the plan's *base64* nonce directly and always failed on plan-shaped
  JSON; fixed in this copy — `_norm_nonce` accepts either form.)
- `Size mismatch` / `Invalid KENC v1 magic` → not a v1 container, or truncated.

## 2. `deob_strings.py` — LCG-XOR string annotator

See [03-string-deobfuscation.md](03-string-deobfuscation.md) for the scheme.
**It rewrites files in place** — run on a scratch copy of the decompiled tree:

```bash
cp -r C:\Users\sahij\Documents\kuri-analysis\src C:\temp\kuri-src-annotated
python tools\deob_strings.py C:\temp\kuri-src-annotated
# decoded 1234 strings in 56 files
grep -rn "kuriplugins.com" C:\temp\kuri-src-annotated
```

Positional arg = source tree; `DEOB_SRC` env var also supported (legacy
behavior). Regex `LIT` at the top of the file is the only thing you may need to
adapt for a different decompiler's literal shape.

## 3. `ConstDump.java` / `EnumDump.java` — run-the-classes constant recovery

The definitive method for constants hidden behind string encryption +
runtime mutation (03 §4). Compile once:

```bash
cd tools
javac ConstDump.java EnumDump.java
```

Run against a decrypted plugin jar (example: construction v1.0.31, which is in
the analysis folder at `C:\Users\sahij\Documents\kuri-analysis\decrypted\`):

```bash
java -cp ".;../sdk/jars/*;../sdk/jars/kuri/kuri-runelite-v1.7.64.jar;C:/Users/sahij/Documents/kuri-analysis/decrypted/kuri-click-construction-1.0.31.jar" ConstDump
# === com.kuri.plugin.kuriclickconstruction.e.a static fields ===
#   b : int[] = [...]          (widget/coords constants, decrypted)
#   ...
# MENU OPTION TEXT = <col=8532cb>Kuri Click Construction

java -cp "<same classpath>" EnumDump
# a.c enum (5 values):
#   RICK [getId=221 getGreeting=Yes, guv? getCapacity=6 getWage=500 ...]
#   ...
```

Classpath anatomy — all four parts are required, in this order of importance:

1. the **decrypted plugin jar** (defines the classes you dump),
2. `sdk/jars/kuri/kuri-runelite-v1.7.64.jar` (the `com.kuri.*` interfaces those
   classes implement — `NoClassDefFoundError` without it),
3. `sdk/jars/*` (RuneLite api/client + libs — supertypes of the framework),
4. `.` (the compiled `ConstDump`/`EnumDump` classes).

**Adapting to a new plugin**: the class names inside `ConstDump.main`
(`com.kuri.plugin.kuriclickconstruction.e.a`, `a.b`) are hardcoded. For a new
jar: decrypt → decompile (`summary.txt`, §4) → find the constants class and the
`MenuConfig` implementation by their *shape* (a class full of static arrays; a
class with a `getMenuOptionText()` method) → edit the two `Class.forName`
arguments and rerun. The dump takes seconds; always prefer it over manual
deobfuscation.

## 4. `cfr.jar` — decompiler

 CFR 0.152 (same version Kuri's own `summary.txt` files were produced with):

```bash
# decompile one jar into a source tree (Java 11 runtime is fine)
java -jar sdk/cfr.jar decrypted-plugin.jar --outputdir decompiled \
     --caseinsensitivefs true --outputencoding UTF-8

# The same command with --analysetype true gives extra type-inference info,
# rarely needed; keep defaults otherwise.
```

Notes:

- `--caseinsensitivefs true` is **required on Windows**: obfuscated packages
  routinely contain classes that differ only by case.
- After decompiling, write a `summary.txt` like the ones in
  `plugin-src/*/summary.txt` (one line per class with its inferred role) — the
  rest of the workflow (ConstDump targets, grep targets) keys off it.
- Expect `Unable to fully structure code` on control-flow-flattened classes —
  that is the obfuscator working, not a tool failure. Read the `switch` state
  machine directly or dump values at runtime (§3).

## 5. `fs_watcher.py` — capture-time file timeline

Polls a set of directories every 2 s and logs added/changed/removed files
(with short hashes) as JSONL. During the original capture it watched the Kuri
cache, `~/.runelite/plugins`, and the launcher's repository dir, producing
`capture/fs-timeline.jsonl` that lines up with `capture.jsonl` timestamps.

```bash
python tools/fs_watcher.py out/fs-timeline.jsonl \
    "C:\Users\sahij\AppData\Local\Kuri\cache\plugins" \
    "C:\Users\sahij\.runelite\plugins"
# no args = default dirs + fs-timeline.jsonl in cwd
```

Stop with Ctrl+C. Large files (>1 MB) are logged by size only, not hashed.

## 6. `tools/capture-agent/` — the key-capture javaagent

Design and build instructions live in
[02-key-capture-method.md](02-key-capture-method.md) (the doc), the sources in
`tools/capture-agent/src/`, prebuilt jars + ASM libs alongside, smoke tests in
`examples/`. Quick usage:

```
-javaagent:C:\path\kuri-plugin-dev\tools\capture-agent\capture-agent.jar
-Dkuri.capture.dir=C:\path\capture-out
```

added to the client fork's JVM args (launcher `config.json` `vmArgs` layer).
Output: `capture.jsonl` (okhttp responses incl. load plan, KencFile args =
identity + key material, plaintext dumps) + `plaintexts/<sha256>.jar`.

## 7. Auxiliary scripts (left in the analysis folder, not copied)

`scripts/find_magic.py`, `scan_strings.py`, `scan_transport.py`, `scan_exe.py`
in `kuri-analysis/` are one-shot scanners used during the initial hunt (search
binaries for magic constants, scan `manager.exe` strings, enumerate transport
code). They are discovery aids, not part of the repeatable pipeline — nothing
in this folder depends on them.
