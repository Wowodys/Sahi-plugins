# capture-agent — Kuri key/plaintext capture javaagent

> Design rationale and internals: `docs/reverse-engineering-kuri/02-key-capture-method.md`
> (read that first). This file is the operational quick reference.

## What it captures

| Hook | Trigger | Output |
|---|---|---|
| okhttp3.RealCall | every response to `*.kuriplugins.com` | `"kind":"okhttp"` JSONL line: method, URL, status, body (incl. the **load plan with contentKeys** and your auth token) |
| KencFile (found by the `KURIKENC` constant in any `k/*` class) | every container decrypt | `"kind":"kenc"` lines (identity fields + 32-byte key + 12-byte nonce, hex+b64) and `"kind":"kenc-plaintext"` + `plaintexts/<sha256>.jar` |

Both hooks fire on the client JVM. `capture.jsonl` lines carry `"proc"` =
`client`/`launcher` attribution and ISO timestamps.

## Layout

```
src/kuri/CaptureAgent.java   ASM transformer (premain; two hooks)
src/kuri/Helper.java         dependency-free sink (goes on the BOOTSTRAP loader)
capture-agent.jar            prebuilt agent (Premain-Class: kuri.CaptureAgent)
capture-helper.jar           prebuilt Helper (appendToBootstrapClassLoaderSearch)
lib/asm-9.8.jar              ASM for rebuilds
examples/AgentTest*.java     the four smoke tests used before the live capture
```

## Using it

Add to the client fork's JVM args (Kuri launcher `config.json` `vmArgs`
layer, or settings `jvmArguments` — **guard against double application**, the
agent tolerates it, but both configs writing the same line is wasteful):

```
-javaagent:C:\Users\sahij\Documents\kuri-plugin-dev\tools\capture-agent\capture-agent.jar
-Dkuri.capture.dir=C:\Users\sahij\Documents\kuri-capture-out
```

Then trigger a sync (install/update a plugin in the Kuri manager, or restart
the client) and inspect:

```bash
# the plan (keys!) — redact before sharing:
python -c "import json;
for line in open(r'C:\Users\sahij\Documents\kuri-capture-out\capture.jsonl', encoding='utf-8'):
    d = json.loads(line)
    if d.get('kind') == 'okhttp' and 'load-plan' in d.get('url', ''): print(d['url'])"

# keys + plaintexts:
python -c "import json;
for line in open(r'C:\Users\sahij\Documents\kuri-capture-out\capture.jsonl', encoding='utf-8'):
    d = json.loads(line)
    if d.get('kind') in ('kenc', 'kenc-plaintext'): print(d.get('sha256', d.get('value', '')[:60]))"

# decrypt a container with a captured plan item (plan JSON: contentKey, nonce,
# pluginId, version, plaintextSha256):
python ..\kenc_decrypt.py decrypt <container.kenc> plan-item.json out.jar
```

Run `tools/fs_watcher.py` alongside for a file-system timeline that lines up
with the capture (cache writes ↔ decrypts).

## Rebuilding from source

```bash
cd tools/capture-agent
mkdir -p build/classes
javac -cp lib/asm-9.8.jar -d build/classes src/kuri/CaptureAgent.java src/kuri/Helper.java
jar --create --file capture-helper.jar -C build/classes kuri/Helper.class
jar --create --file capture-agent.jar -C build/classes kuri/CaptureAgent.class \
    -C build/classes 'kuri/CaptureAgent$T.class' -C build/classes 'kuri/CaptureAgent$T$1.class' \
    --manifest 'Premain-Class: kuri.CaptureAgent\r\n'
# bundle ASM into the agent jar (so the classpath doesn't need lib/):
cd build/classes && jar --update --file ../../capture-agent.jar -C ../../lib/asm-9.8.jar .
# (or unzip asm-9.8.jar into a folder and jar -C that folder .)
```

Rules the build enforces (break them and the agent breaks at runtime):

1. `capture-helper.jar` must contain **only** `kuri/Helper` classes.
2. `Helper` stays JDK-only and never-throwing (the injected bytecode has no
   exception handlers).
3. The agent jar keeps `Premain-Class: kuri.CaptureAgent`.

## Maintenance checklist per Kuri framework release

- [ ] still an okhttp3 client? (`RealCall` hook target — check the framework
      jar for `okhttp3` classes)
- [ ] `KURIKENC` magic unchanged and parser still in a `k/` package
- [ ] decrypt method still shaped `(InputStream, X, Y) -> byte[]` (the
      transformer's `candidate` predicate) — `examples/AgentTest3.java`
      lists candidates reflectively
- [ ] artifact host still `*.kuriplugins.com` (`-Dkuri.capture.host=` to override)

## Warnings

- `capture.jsonl` contains **live auth tokens and content keys** — keep the
  output directory local, never commit it, redact before sharing.
- This is an analysis instrument for your own machine and subscription. Do
  not attach it to anyone else's client, and do not distribute plugins that
  embed it.
- The `rlicn` anti-tamper native lib loads in the client. It did not block
  instrumentation during the 2026-11-03 capture; treat that as an empirical
  fact about that build, not a guarantee about the next one. If a future
  build detects and blocks agents, stop — do not attempt evasion.
