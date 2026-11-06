# 01 — The KENC v1 Encrypted Plugin Container

> Provenance: reverse-engineered 2026-11-03 from the Kuri RuneLite framework jar
> (`kuri-runelite-v1.7.64.jar`, served by `api.kuriplugins.com`) and verified
> end-to-end against the live service the same day. The classes involved:
>
> | Obfuscated name | Identity |
> |---|---|
> | `k.n52d6dca4ff92` | **KencFile** — container parser / decryptor |
> | `k.n874f7bc9a85c` | **KencReleaseIdentity** — pluginId / version / sha256 / size |
> | `k.nce2b3b005977` | **KencKeyMaterial** — contentKey (32 B) + nonce (12 B) |
> | `k.n2359b6444a27` | **EncryptedPluginLoadPlanItem** — release + download URL + keys |
> | `k.n023d27f1b5a2` | load-plan JSON parser (`/client-api/v1/access` response) |
> | `k.nfbc6af3c8247` | decrypt facade (temp-file / IO handling) |
>
> The obfuscated class names are hash-derived and will change between framework
> versions; the wire format and magic constants below are the stable part.
> Local copy of the framework jar for reference: `sdk/jars/kuri/kuri-runelite-v1.7.64.jar`.

## 1. What KENC is for

Kuri (kuriplugins.com) distributes RuneLite plugins as encrypted artifacts so
that a plugin jar is never downloadable without an active subscription. Each
release is one **KENC v1 container**: a 36-byte header, the AES-256-GCM
ciphertext of a normal plugin jar, and a 16-byte authentication tag. The
container is content-addressed by its own SHA-256 — the filename *is* the
integrity check, and the client's on-disk cache (`%LOCALAPPDATA%\Kuri\cache\plugins\`)
is keyed by it.

Plaintext is an unmodified plugin JAR. There is no extra packaging layer: strip
the 52 bytes of crypto envelope and you have the jar RuneLite's
`ExternalPluginManager` loads.

## 2. Container layout

```
offset  size  field
0       8     magic  "KURIKENC" (US-ASCII)
8       1     format major = 0x01
9       1     format minor = 0x01
10      2     u16be header-extension length = 0
12      2     u16be AAD header length = 36
14      1     nonce length = 12
15      1     GCM tag length = 16
16      8     u64be plaintext (jar) length, 1 .. 0x4000000 (64 MiB)
24      12    AES-GCM nonce / IV          (== plan JSON "nonce")
36      N     AES-256-GCM ciphertext      (N = plaintext length)
36+N    16    GCM authentication tag (128-bit)
```

Total file size is exactly `36 + plaintext_len + 16` = `plaintext_len + 52`.

Every "fixed" value in the header is a *validated* constant, not decoration —
the parser rejects the file if any of them disagree (`ext_len != 0`,
`aad_len != 36`, `nonce_len != 12`, `tag_len != 16`, plaintext length outside
`1..0x4000000`, or total size mismatch). That means:

- **you can measure the plaintext length from the file alone** (`file_size - 52`),
- the format version is readable without keys,
- the nonce is readable without keys — only the 32-byte content key is secret.

The `header-extension length` and `AAD header length` fields are forward-
compatibility scaffolding: an unknown extension is meant to be skipped, and the
AAD length tells future parsers where the authenticated region ends. In v1.1
they are always `0` and `36`.

**Filename convention:** the cache filename is the lowercase hex SHA-256 of the
*entire encrypted file* — all observed containers match this, and the load plan
duplicates the value in its `sha256` field. The client uses it as a cache key:
if `cache/plugins/<plan.sha256>` already exists, it decrypts the cached blob
without re-downloading. (Side effect for analysis: a renamed container still
decrypts fine; only the cache-matching step cares about the name. Our synthetic
test container, `tools/testdata/test-container.kenc`, deliberately does *not*
have a hash filename and decrypts fine.)

## 3. Crypto

- Cipher: `AES/GCM/NoPadding` — "AES-256-GCM" in the plan JSON. 128-bit tag.
- Key: the 32-byte `contentKey` delivered inside the authorized load-plan
  response (base64 in JSON).
- Nonce: the 12 bytes at offset 24 of the file. The client cross-checks the
  header nonce against the plan's `nonce` field before decrypting — a container
  and a plan item are a matched pair.

GCM does both jobs at once: confidentiality, and authentication of
ciphertext + AAD (§4). There is no separate MAC and no key-derivation step —
the raw 32 content-key bytes *are* the AES key.

## 4. Additional Authenticated Data (AAD)

`KencFile` builds a blob and feeds it to `Cipher.updateAAD(...)` before
processing the ciphertext:

```
header[0:36]                       the raw 36-byte header, verbatim
"KURIAAD1"                         second magic
0x01 0x01                          format major, minor again
u16be(len(pluginId)) + pluginId    ASCII, [a-z0-9][a-z0-9-]{0,127}
u16be(len(version))  + version     ASCII, [A-Za-z0-9][A-Za-z0-9._+-]{0,127}
expected_plaintext_sha256          32 bytes (plan "plaintextSha256")
u64be(plaintext_len)
```

Why this matters to you as an analyst:

- **Length-prefixing with `u16be` makes the AAD unambiguous** — plugin id and
  version of different lengths can't collide.
- The expected plaintext digest and length are *inside* the authenticated data.
  A container cannot be re-associated with a different plugin/release, nor
  truncated/extended, without breaking the GCM tag.
- The version/format bytes appear twice (header + literal), binding both.
- Practically: **when decrypting offline you must supply the exact
  `pluginId`, `version`, and `plaintextSha256` from the plan item**, or the tag
  check fails. One wrong character = `AEADBadTagException`. There is no
  "decrypt without metadata" path (GCM tag recovery is not feasible).

After decryption the client re-hashes the plaintext (SHA-256) and compares it
against the identity digest; any mismatch discards the buffer. So even a
hypothetical tag forgery would still need a preimage.

## 5. Key distribution — the load plan

`GET https://api.kuriplugins.com/client-api/v1/plugins/load-plan`
(Authorization: Bearer access token, plus an `X-H-UUID` header) returns:

```jsonc
{
  "serverTime": "...Z",
  "items": [{
    "releaseId": "kuri-click-construction:1.0.31",
    "pluginId": "kuri-click-construction",
    "version": "1.0.31",
    "name": "Kuri Click Construction",
    "description": "One click anywhere to train construction",
    "mainClass": "com.kuri.plugin.kuriclickconstruction.KuriClickConstructionPlugin",
    "artifactFormat": "KURI_ENCRYPTED_JAR_V1",
    "algorithm": "AES-256-GCM",
    "fileName": "kuri-click-construction-1.0.31.kenc",
    "sha256": "<ciphertext sha256 — = cache filename>",
    "size": 168486,                 // = plaintextSize + 52
    "plaintextSha256": "0d10449f...",
    "plaintextSize": 168434,
    "downloadUrl": "https://artifacts.kuriplugins.com/plugins/...",
    "downloadExpiresAt": "...Z",    // signed, short-lived artifact URL
    "loadExpiresAt": "...Z",        // authorization window for THIS plan
    "contentKey": "<base64, 32 bytes>",
    "nonce": "<base64, 12 bytes>",
    "hooks": {                      // obfuscated-client field map, see 04
      "revision": "1.13.6",
      "catalog": [ ... ]
    },
    "beta": false,
    "channelChoice": false
  }],
  "authorization": { "status": "AUTHORIZED"|"DENIED", "authorizations": [...] }
}
```

Related endpoints seen live (same bearer + `X-H-UUID`):

- `GET /client-api/v1/access` → `{active: true}` subscription check
- `GET /client-api/v1/licenses` → license rows (id, name, pluginNames, activation/expiry)
- `POST /client-api/v1/client/register` → `{id: clientInstanceId}`
- `POST /client-api/v1/client/heartbeat` → per-release `authorizations[]`
  (releaseId + AUTHORIZED/DENIED + `loadExpiresAt`) — the server-side gate that
  keeps keys flowing only to active subscriptions.

A real captured plan (with content keys) is in the analysis folder at
`capture/load-plan.json`; note that `capture/capture.jsonl` also contains the
**account auth token — treat that file as a secret and never commit it**.

## 6. Runtime flow

1. The launcher (Tauri manager provisions it; v1.5.6 at analysis time) patches
   `injected-client-*.jar` — injecting a `client.class` hook that loads the
   `rlicn_<arch>.dll` anti-tamper native lib — and forks the client JVM.
2. Client authenticates (OAuth PKCE over a 127.0.0.1 loopback; tokens land in
   `~/.runelite/kuri-session/session.properties`), checks the subscription.
3. Client fetches the load plan → downloads encrypted artifacts to
   `AppData\Local\Kuri\cache\plugins\<sha256>` → decrypts per §3–4 → writes the
   plaintext jar to `~/.runelite/plugins/<slug>_<base64url-sha256>.jar` →
   RuneLite's `ExternalPluginManager` loads it as an ordinary external plugin.

## 7. Key hygiene (as implemented)

- `KencKeyMaterial` holds the key only in memory and zeroes it on close.
- Keys are **never written to disk** — not to the cache, not to config.
- The Tauri manager binary never touches plugin keys at all; it only
  provisions launcher jars. (Corroborated by the `manager_strings.txt` scan.)
- Consequence: the only place content keys exist is the TLS-protected load-plan
  response at sync time. To keep a plugin decryptable across releases you must
  capture the plan (or the plaintext) when your client syncs — see
  [02-key-capture-method.md](02-key-capture-method.md).

## 8. Offline recoverability summary

From the encrypted file alone you learn: format version, plaintext length,
nonce, GCM tag, ciphertext hash. Decryption requires the per-release
`contentKey`, delivered only inside an authenticated load-plan response.
Superseded releases whose keys were never captured are unrecoverable — their
cache blobs are dead weight. Current plugin jars, once synced, are already
present decrypted under `~/.runelite/plugins/`.

## 9. Local verification (reproducible in this folder)

The synthetic container in `tools/testdata/` exercises the whole format:

```
cd tools
python kenc_decrypt.py analyze testdata/test-container.kenc
# → version 1.1, plaintext_len 472, file_size 524 (= 472+52), nonce 9f84034b42c0bae244cb789d
python kenc_decrypt.py decrypt testdata/test-container.kenc testdata/test-plan.json testdata/out-test-plugin.jar
# → "decrypted -> testdata/out-test-plugin.jar (472 bytes)"
```

`test-plan.json` holds the plan item (`pluginId: test-plugin`, `version: 1.0.0`,
`contentKey`, `nonce`, `plaintextSha256 = 121ceced…`). The decrypt succeeds only
because the AAD metadata matches exactly; flipping any character in pluginId or
version yields `AEADBadTagException` — try it, it's a good format intuition
builder. The decrypted plaintext hashes to the expected digest and begins with
`PK\x03\x04` ("fake plugin jar payload for KENC validation").

The same tooling decrypted all three live containers byte-identically to the
client's own runtime output during the original capture (plaintext SHA-256
comparison against `capture/plaintexts/*.jar`).

## 10. If the format changes

Watch for: a bumped `format major` byte (parser will reject old assumptions),
non-zero header-extension length (new unauthenticated fields between header and
nonce — check where the nonce actually sits before assuming offset 24), or an
`artifactFormat` string other than `KURI_ENCRYPTED_JAR_V1` in the plan. The
capture agent in [02](02-key-capture-method.md) locates the parser by the
`KURIKENC` magic rather than by class name, which survives most of these
evolutions.
