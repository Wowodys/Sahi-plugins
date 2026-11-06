# 02 — Capturing Content Keys and Plaintexts with a `-javaagent`

> This document explains the design of the capture agent in
> `tools/capture-agent/` (sources: `src/kuri/CaptureAgent.java`, `src/kuri/Helper.java`;
> built jars: `capture-agent.jar`, `capture-helper.jar`). It is a complete
> description of *why* the agent is shaped the way it is, so you can repair it
> when Kuri ships a new framework version.
>
> Scope note: this technique was used against the analyst's own machine and own
> subscription, purely to preserve access to plugins they had paid for and to
> document the platform. The captured plan JSON contains your account auth
> token — treat `capture/capture.jsonl` as a secret.

## 1. Why a javaagent, and what it must catch

Content keys exist in exactly two places, both transient (see
[01](01-kenc-container-format.md) §7):

1. the **HTTP response body** of the load-plan call (TLS on the wire, parsed
   into JSON in memory),
2. the arguments/return value of the **KencFile decrypt routine** inside the
   framework (`k.n52d6dca4ff92` in v1.7.64) at the moment a plugin syncs.

A javaagent observes both *inside* the process, after TLS termination — no
network interception, no certificates, no proxy detection surface. Two hooks
cover the two spots:

- **Hook A — okhttp3.RealCall**: the framework uses okhttp for its API calls.
  Instrumenting `RealCall.getResponseWithInterceptorChain()` catches every
  response the client receives through okhttp, including the load plan with
  its per-release `contentKey` fields.
- **Hook B — the KencFile parser**: dumps the key-material arguments and the
  decrypted plaintext at the moment of decryption. This is the ground truth —
  it works even if the framework later switches HTTP libraries or moves the
  JSON parsing.

Capturing both is deliberate redundancy: if one hook misses (library swap,
method rename), the other still lands, and the two capture streams
cross-validate each other (response-body keys vs. actually-used keys vs.
plaintext hashes).

## 2. Hook A: wrapping `okhttp3.RealCall.getResponseWithInterceptorChain`

Chosen because it is the single choke point through which *every* okhttp
response passes after the interceptor chain (application and network
interceptors included) has run, on every okhttp3.x release so far. The
transformation, done with ASM (`ClassWriter.COMPUTE_MAXS`):

1. Rename the original method to `getResponseWithInterceptorChain$capOrig`
   (marked `ACC_SYNTHETIC`).
2. Emit a new method under the **original name** with a straight-line body:

   ```
   r = this.getResponseWithInterceptorChain$capOrig();
   Helper.onOkhttpResponse(r);      // INVOKESTATIC kuri/Helper
   return r;
   ```

   No try/catch → no StackMapTable frames to compute; `COMPUTE_MAXS` suffices
   (no frames computed, which also avoids visiting old frames). The wrapper is
   safe because `Helper` is contractually never-throwing (§4) — every reflective
   access inside Helper is wrapped in its own try/ignore.

`Helper.onOkhttpResponse` works purely reflectively (`response.request().url().host()`,
`response.peekBody(64MiB).string()`) so the agent jar has **no compile-time
dependency on okhttp** — it keeps working if okhttp is repackaged inside the
app. Responses whose host does not end in `kuriplugins.com` are dropped
immediately (configurable via `-Dkuri.capture.host=`); everything else is
appended as one JSON line (`"kind":"okhttp"`) to `capture.jsonl` with method,
URL, status, body length, and the body itself.

> `peekBody` is used (not `body().string()`) precisely so the agent does not
> consume the response stream the application is about to read — peeking
> buffers a copy without closing anything.

## 3. Hook B: locating KencFile by its constant pool — the "KURIKENC" marker

The framework is fully obfuscated; class names are hash-derived
(`k.n52d6dca4ff92` today, something else in v1.7.65). Matching on class or
method names would break every release. Instead the transformer does a raw
**byte scan of every class file as it loads**:

```java
if (className.startsWith("k/") && contains(bytes, "KURIKENC")) {
    return transformKencFile(bytes);
}
```

The magic string `KURIKENC` must appear in the parser's constant pool because
the parser itself checks the header magic — that check *is* the marker. This
survives:

- class/method renaming between framework versions (the string is format-bound),
- package reshuffles (guarded only by the cheap `k/` prefix, which is also
  just an optimization — the byte scan alone is sufficient),
- recompilation with different compilers.

Two candidate strings could theoretically match; in practice exactly one class
in `k/` contains the literal, and the method-shape filter below pins it further.

Inside the matched class, the transformer wraps every **static method shaped
`(InputStream, X, Y) -> byte[]`** (non-abstract, non-native) the same way as
Hook A:

```
Helper.onKencArgs(new Object[]{a0, a1, a2});
r = original$cap(a0, a1, a2);
Helper.onKencResult(a1, r);
return r;
```

- `onKencArgs` reflectively dumps **all non-static fields** of the 2nd/3rd
  arguments — these are the `KencReleaseIdentity` (pluginId/version/sha/size)
  and `KencKeyMaterial` (the 32-byte contentKey + 12-byte nonce, hex + base64
  in the JSON line) — into `capture.jsonl` as `"kind":"kenc"` lines.
- `onKencResult` writes the decrypted plaintext to
  `plaintexts/<sha256-of-plaintext>.jar` (deduplicated) and logs a
  `"kind":"kenc-plaintext"` line with the hash, size, and identity summary.

This shape heuristic is what makes the agent robust: Kuri could rename the
method (the wrapper preserves the original name and moves the body to
`name$cap`, so all internal call sites still work), but as long as the parser
takes a stream plus identity-plus-key-material and returns the jar bytes, it is
caught. If the signature changes shape, update the `candidate` predicate — that
is the only maintenance point.

## 4. The bootstrap-classloader trick (why `Helper` is a separate jar)

The instrumented classes do **not** live on the system classpath:

- okhttp is loaded by the application classloader;
- Kuri plugins are loaded from decrypted jars by a child URLClassLoader
  (`ExternalPluginManager`-style);
- and the Kuri launcher *relaunches* the client JVM (packr-style) with a custom
  `--classpath` loader that does **not** see the agent jar on the system
  classpath at all.

Yet the injected bytecode contains `INVOKESTATIC kuri/Helper.onOkhttpResponse`.
For that to link from *any* loader, `Helper` must be visible to the oldest
ancestor of all of them — the **bootstrap classloader**. The agent's premain
does exactly that:

```java
inst.appendToBootstrapClassLoaderSearch(new JarFile(helperJar));
```

where `helperJar` is `capture-helper.jar`, found next to the agent jar via its
own `CodeSource` location. Consequences and rules:

- **Only `Helper` goes on the bootstrap.** Appending the *whole* agent jar
  would make the bootstrap loader also define `CaptureAgent$T` while the
  application loader defines its own copy → two class identities →
  `IllegalAccessError` at runtime. Keeping the transformer on the system
  classpath (where `-javaagent` puts it) and only lifting the tiny, dependency-
  free sink class avoids split identity.
- `Helper` must therefore stay **JDK-only and dependency-free** (it is:
  reflection, `Files.write`, `MessageDigest`, no okhttp/ASM imports) and
  **never-throwing** (the injected bytecode has no exception handlers; any
  Throwable inside Helper is caught internally and logged as an `"error"` line).
- The agent jar itself still needs ASM at *agent* load time; it is shaded/
  bundled when building (`lib/asm-9.8.jar` in this folder).

## 5. Remaining design details worth knowing

- **Double-injection guard**: a `static boolean installed` flag in `premain`.
  This is not paranoia — during the live capture the client fork applied vmArgs
  from two config layers (`config.json vmArgs` *and* settings `jvmArguments`),
  and the agent was loaded twice.
- **Fail-open**: the transformer catches everything and returns `null`
  (= load untransformed) on any error. A broken hook must never break the
  client — you are instrumenting a live session.
- **Process attribution**: each JSON line carries `"proc"` — `"client"` vs
  `"launcher"` — detected from `java.class.path`/`sun.java.command` markers
  (`kuri-runelite`, `injected-client`, `--classpath`), because both processes
  may run okhttp and the plan is fetched by the client.
- **Output directory**: `-Dkuri.capture.dir=` property (default was the
  analysis workspace at capture time); `Helper` creates it and appends
  JSONL atomically under a lock (both hooks can fire from any thread).

## 6. Building it

```bash
cd tools/capture-agent
mkdir -p build/classes
# JDK 11+ (the client runs on Temurin 17; compile target 8 is fine for both)
javac -cp lib/asm-9.8.jar -d build/classes src/kuri/CaptureAgent.java src/kuri/Helper.java

# helper jar: ONLY kuri/Helper.class (goes on the bootstrap loader)
jar --create --file capture-helper.jar -C build/classes kuri/Helper.class

# agent jar: CaptureAgent classes + bundled ASM + premain manifest
jar --create --file capture-agent.jar \
    -C build/classes kuri/CaptureAgent.class \
    -C build/classes 'kuri/CaptureAgent$T.class' \
    -C build/classes 'kuri/CaptureAgent$T$1.class' \
    --main-class kuri.CaptureAgent \
    --manifest 'Premain-Class: kuri.CaptureAgent\r\nCan-Retransform-Classes: false\r\n'
# (alternatively write a MANIFEST.MF with Premain-Class and use jar -m)
# then unpack asm into it or run with -javaagent plus a Class-Path manifest entry
```

`examples/AgentTest*.java` are the four smoke tests used to validate the hooks
against a local HTTP server and the real container bytes before the live run —
read `AgentTest.java` for the reflection probing pattern (find static
`(InputStream,X,Y)->byte[]` methods) and `AgentTest4.java` for driving the real
`KencFile` method with synthetic identity + key material.

## 7. Attaching it

The Kuri launcher reads JVM args from its config (`config.json` `vmArgs`, plus
settings-level `jvmArguments`) before forking the client. Add:

```
-javaagent:C:\path\to\capture-agent.jar
-Dkuri.capture.dir=C:\path\to\capture-out
```

then trigger a plugin sync (install/update a plugin in the manager, or restart
the client) and watch `capture-out/capture.jsonl` for `kenc` / `kenc-plaintext`
lines and the load-plan response. `tools/fs_watcher.py` gives you a parallel
timeline of the cache/plugin directories so you can correlate what landed on
disk with what was decrypted in memory.

## 8. Caveats and failure modes

- **Anti-tamper**: the client loads an `rlicn` native library via JNI
  (`Java_client_initRLICN`) at startup. It did not interfere with
  instrumentation during the capture, but treat that as luck, not guarantee —
  a future build could detect agents (e.g. scan for `Premain-Class`, or check
  `Instrumentation` presence) and refuse to run. No evasion work was done and
  none should be: if a future version blocks agents, that is your cue to stop.
- **Version drift**: the `k/` package assumption, the `(InputStream,X,Y)->byte[]`
  shape, and the okhttp host filter are the three things to re-check per
  framework release (`kuri-runelite-v1.7.*`).
- **Secrets in output**: `capture.jsonl` holds the bearer token and the
  content keys. Keep the folder local, don't sync it, redact before sharing.
- **Don't ship it**: the agent is an analysis instrument. Bundling it with any
  distributed plugin would be both malicious and pointless.
