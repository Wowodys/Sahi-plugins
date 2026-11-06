# 04 — Transport, API Contract, Licensing, and What the Capture Sees

> Sources: decompiled framework (`kuri-runelite-v1.7.64.jar`, classes in `k/`)
> and launcher (v1.5.6), the Tauri manager string scan, and the live capture
> (`capture/load-plan.json`, `capture/capture.jsonl`). Class names below use the
> recovered identities; see [05-plugin-internals-tour.md](05-plugin-internals-tour.md)
> for the naming scheme itself.

## 1. Who talks to whom, over what

There are three network actors, and they use **different HTTP stacks** — this
is the single most important fact for capture work:

| Actor | Library | What it does |
|---|---|---|
| **Client framework** (`kuri-runelite-v*.jar`) | **okhttp3** (bundled `okhttp-3.14.9` / `okio-1.17.2`) | load plan, artifact download, heartbeat |
| **Launcher** (`RuneLite.jar` Kuri fork, v1.5.6) | **`java.net.http.HttpClient`** (JDK 11) | OAuth login/exchange, token refresh, subscription check (`/client-api/v1/access`), launcher self-update |
| **Manager** (`manager.exe`, Tauri) | Rust reqwest (native) | manager self-update (GitHub releases), launcher provisioning via the backend |

Consequence: the okhttp hook in the capture agent
([02-key-capture-method.md](02-key-capture-method.md)) sees **client-side
traffic only** — the load plan, artifact downloads, heartbeats. Login, token
refresh, and bootstrap flows pass through `java.net.http` and never appear
there. If you ever need those, the hook point is the JDK client
(`jdk.internal.net.http.*`) — and that traffic contains no plugin content keys,
so for plugin recovery it is not needed.

Endpoints seen live (all under `https://api.kuriplugins.com`, artifacts under
`https://artifacts.kuriplugins.com`):

```
GET  /client-api/v1/plugins/load-plan      ← the one that matters (per-release contentKeys)
GET  /client-api/v1/access                 {active: true} subscription probe
GET  /client-api/v1/licenses               license rows
POST /client-api/v1/client/register        → {id: clientInstanceId}
POST /client-api/v1/client/heartbeat       → per-release authorizations[] + loadExpiresAt
GET  /client-api/v1/client/bootstrap.json  launcher/client jar manifest (signed URLs)
POST /auth-api/v1/login/exchange           OAuth PKCE code exchange (launcher)
POST /auth-api/v1/refresh                  refresh token rotation (launcher)
```

Common headers on client-api calls: `Authorization: Bearer <access token>` and
`X-H-UUID: <install UUID>`. The UUID is a **random per-install identifier**
stored at `%LOCALAPPDATA%\RuneLite\install_id.txt` — there is no hardware
fingerprint; the whole machine-binding story is "this install id + this
account".

## 2. The load-plan request

The client posts a JSON body (built by the wire codec, `k.n023d27f1b5a2`):

```jsonc
{
  "clientInstanceId": "<uuid>",
  "transportVersion": 1,
  "clientRevision": "1.13.6",             // RuneLiteProperties.getVersion() — the client version
  "supportedArtifactFormats": ["KURI_ENCRYPTED_JAR_V1"],
  "pluginIds": [...],                     // optional filters
  "stablePluginIds": [...]
}
```

`clientRevision` is load-bearing: the `hooks` catalog returned per item must
carry `"revision"` equal to it, or the client rejects the plan ("Hook revision
does not match this client"). The captured plan
(`capture/load-plan.json`) had `hooks.revision = "1.13.6"` — i.e. the hook
catalog is **per client build**, not per plugin release.

## 3. The load-plan response — a strict contract

The response parser is unusually paranoid; knowing its rules tells you exactly
what a *valid* captured plan looks like (and why hand-edited plans fail):

- Top level: only `{serverTime, items}` — unknown keys rejected, duplicate JSON
  keys rejected, trailing content rejected, total input capped at 262,144 chars.
- Each item allows exactly: `releaseId, pluginId, version, name, description,
  mainClass, artifactFormat, fileName, sha256, size, plaintextSha256,
  plaintextSize, downloadUrl, downloadExpiresAt, loadExpiresAt, algorithm,
  contentKey, nonce, hooks, beta, channelChoice, betaVersion, stableVersion`.
- Enforced per item:
  - `artifactFormat == "KURI_ENCRYPTED_JAR_V1"`, `algorithm == "AES-256-GCM"`;
  - unique `releaseId` and `pluginId` across items;
  - `sha256` / `plaintextSha256` are 64 lowercase hex chars;
  - **`size == plaintextSize + 52`** (the KENC envelope is not negotiable);
  - `downloadExpiresAt` / `loadExpiresAt` strictly after `serverTime`
    (the plan's authority is time-boxed);
  - `contentKey` = **canonical** base64 of exactly 32 bytes (`base64(encode-decode(x)) == x`),
    same for the 12-byte `nonce`;
  - `mainClass` matches a fully-qualified Java class name regex;
  - `fileName` = `<pluginId>-<version>.kenc`;
  - `downloadUrl` must be an https URL pinned to the artifacts origin (no
    userinfo/query/fragment tricks).
- `hooks = {revision, catalog}` with the revision check above and a catalog
  capped at 256 entries.

## 4. The hooks catalog (what it is, what it means for porting)

Each item's `hooks.catalog` is an obfuscated-client field map — entries like:

```jsonc
{ "name": "ap", "obfuscatedName": "ap",
  "fields": [ {"name": "action", "obfuscatedName": "as", "owner": "ap",
               "descriptor": "Ljava/lang/String;", ...} ],
  "methods": [...] }
```

Recovered names in the captured catalog (revision 1.13.6): menu-action internals
(`action`, `identifier`, … on class `ap`), `spellWidget`/`spellChildIndex`,
`tempMenuAction`, scene `baseX`/`baseY`. This is how the Kuri API layer reaches
client internals that vanilla RuneLite's public API does not expose — the same
job RuneLite's own mixin/hook system does for its API.

**For porting to vanilla RuneLite this catalog is a red flag list**: any place
a plugin's behaviour depends on a hook-catalog-backed API is a place where the
vanilla API may genuinely not offer an equivalent (e.g. `tempMenuAction`
handling). In the three analyzed plugins, none of the game-facing logic needed
it — everything was reproducible with public API (see the port map in
[05-plugin-internals-tour.md](05-plugin-internals-tour.md) §6).

## 5. Licensing, heartbeat, and revocation

The subscription gate is a **lease**, not a one-time check:

1. `POST /client-api/v1/client/register` → the client gets its
   `clientInstanceId` once.
2. The client periodically posts a heartbeat: `{id, transportVersion,
   loadedReleases: [{releaseId, pluginId, version, sha256}] (≤100),
   enabledPluginIds}` — i.e. it reports **which releases are loaded and
   enabled**.
3. The response is `{serverTime, authorizations: [{releaseId, status:
   AUTHORIZED|DENIED, loadExpiresAt}]}` — a time-boxed grant per release.
4. Kuri's PluginManager (framework class
   `com/kuri/client/plugin/n1c859db689ff`) checks the current authorization
   **before every `plugin.startUp()`** and runs a periodic revoke pass that
   stops plugins whose release went DENIED/expired. Auth state changes surface
   as framework events (`KuriPluginAuthorizationChanged`, `KuriAuthStateChanged`,
   `KuriAuthExpired`) on the normal RuneLite event bus.

Token lifecycle (launcher side): OAuth PKCE via a loopback `127.0.0.1` callback
server; access + refresh tokens persisted in
`~/.runelite/kuri-session/session.properties` (atomically written via
`session-*.tmp` + rename, guarded by `refresh.lock`/`state.lock`); refresh via
`POST /auth-api/v1/refresh`. Expired refresh → "Kuri login expired" dialog and
`KuriAuthExpired`.

RFC 7807 problem-detail responses are recognized by the client (the `type,
title, status, detail, code, instance` key set) — `bootstrap.json` in the
analysis root is an example (a 401 `authentication-required` problem JSON).

## 6. Cache, disk layout, and hot reload

- Encrypted containers: `%LOCALAPPDATA%\Kuri\cache\plugins\<ciphertext-sha256>`
  — content-addressed; a cached blob matching the plan's `sha256` is decrypted
  directly without re-download.
- Decrypted jars: `~/.runelite/plugins/<slug>_<base64url-sha256>.jar` —
  RuneLite's normal external-plugin directory, so they load through RuneLite's
  own plugin machinery.
- The Kuri PluginManager reconciles disk against the plan: any jar in the
  plugins dir that is not in the authorized plan is **deleted**, and kept jars
  are size+sha256 verified. Hot reload = re-fetch plan → stop changed plugins →
  delete stale jars → decrypt/install new ones → re-register configs → post
  `KuriPluginLoaded`.

## 7. Anti-tamper (rlicn) — observed facts only

- Native libs `rlicn_{amd64,x86,aarch64}.dll` ship inside
  `repository2/rlicn-1.1-windows.jar`; the only exported JNI symbol is
  `Java_client_initRLICN` — a class literally named `client` in the default
  package must call it.
- The launcher's patch step rewrites `client.class` inside
  `injected-client-1.13.6.jar` (see §8) to load `rlicn_<arch>.dll` at startup
  (arch from `os.name`, loader via URLClassLoader/platform classloader
  delegation) and carries a 64-byte `client.serial` blob (unreadable token).
- What the DLL checks is not determinable from static analysis (one export, no
  diagnostics). Empirically, during the capture it did not interfere with the
  `-javaagent`. Do not assume that will stay true; do not attempt to defeat it
  — if a future build blocks instrumentation, that is the end of this method.

## 8. Launcher bootstrap flow (how everything above gets started)

1. **Manager** (Tauri) provisions launcher jars: downloads from the backend
   (signed URL + sha256 + size verified), swaps/backs up the official
   `RuneLite.jar`, tracks state in `install-metadata.json`. It checks the
   official RuneLite launcher GitHub releases for baseline versions and refuses
   too-old installs. It never touches plugin keys.
2. **Launcher** (Kuri fork of the RuneLite launcher): update-checks itself,
   reads `launcher.properties` (`kuri.baseurl`, `runelite.main`,
   `runelite.bootstrap`, `kuri.group`), fetches `bootstrap.json` (the client
   jar manifest with expected hashes), then runs the **patch step**:
   - finds `injected-client` on the classpath, compares its hash with the
     bootstrap expectation and with on-disk patch metadata
     (`%USERPROFILE%\.runelite\injected-client.info`, obfuscated JSON keys
     `a`/`b` = vanilla/patched hash),
   - if stale, loads `com.kuri.client.patch.KuriPatcher` from the framework jar
     via a fresh URLClassLoader and calls `patch(byte[])` reflectively →
     rewrites `client.class` (rlicn loader) inside `injected-client`,
   - idempotent; on patcher failure it leaves the vanilla jar.
3. **Fork**: the launcher re-executes itself packr-style
   (`<exe> -c [-J <jvmArg>]... -- --classpath <names>... <clientArgs>`) —
   `-J`-prefixed entries are passed straight to the child JVM. **This is where
   a `-javaagent` argument rides along** (and why the child process needs the
   bootstrap-classloader trick from
   [02-key-capture-method.md](02-key-capture-method.md): the forked child's
   custom `--classpath` loader does not see the agent jar on the system
   classpath).
4. The child starts the patched client → rlicn loads → OAuth/login state
   restored → framework PluginManager loads the plan → plugins.

Local layout map (verified on this machine):

| Path | Content |
|---|---|
| `%LOCALAPPDATA%\RuneLite\` | `RuneLite.jar` (current Kuri launcher), `config.json`, `settings.json`, `install_id.txt`, `jre\` (Temurin 17 runtime) |
| `%LOCALAPPDATA%\Kuri\manager\` | manager state: `artifacts\launcher\current\v1.5.6\`, `backups\` |
| `%LOCALAPPDATA%\Kuri\cache\plugins\` | KENC containers, content-addressed |
| `~/.runelite/plugins\` | decrypted plugin jars |
| `~/.runelite/kuri-session\` | session tokens (mode 600) |
| `~/.runelite/repository2\` | artifact repo: `client-1.13.6.jar`, `runelite-api-1.13.6-runtime.jar`, `kuri-runelite-v1.7.*.jar`, bundled libs, `rlicn-1.1-windows.jar` |
| `~/.runelite/injected-client.info` | patch metadata (`{"a": vanilla-hash, "b": patched-hash}`) |
