"""KENC v1 (KURIKENC) container parser/decryptor.

Reconstructed from Kuri's client code (kuri-runelite-v1.7.64.jar,
classes k/n52d6dca4ff92 KencFile, k/n874f7bc9a85c KencReleaseIdentity,
k/nce2b3b005977 KencKeyMaterial, k/n023d27f1b5a2 load-plan JSON parser).

Container layout (total = 52 + plaintext_len):
    [0:8]    magic      "KURIKENC" (ASCII)
    [8]      major      0x01
    [9]      minor      0x01
    [10:12]  u16 be     header-extension length == 0
    [12:14]  u16 be     AAD header length == 36
    [14]     u8         nonce length == 12
    [15]     u8         GCM tag length == 16
    [16:24]  u64 be     plaintext length (1 .. 0x4000000)
    [24:36]  12 bytes   AES-GCM nonce (must equal plan's `nonce`)
    [36:36+n]           AES-256-GCM ciphertext (n = plaintext_len)
    [36+n:]  16 bytes   GCM authentication tag (128-bit)

Filename convention: sha256-hex of the entire encrypted file.

AAD (bound into the GCM tag; built by KencFile.nc556d7d7a991(header, identity)):
    header[0:36]
    b"KURIAAD1"
    0x01 0x01
    u16be(len(pluginId)) + pluginId ASCII   ([a-z0-9][a-z0-9-]{0,127})
    u16be(len(version)) + version ASCII     ([A-Za-z0-9][A-Za-z0-9._+-]{0,127})
    expected_plaintext_sha256               (32 bytes, plan's `plaintextSha256`)
    u64be(plaintext_len)

Decryption: AES-256-GCM (AES/GCM/NoPadding), 128-bit tag.
Key: 32-byte `contentKey` from the authorized load plan
     (POST/GET api.kuriplugins.com /client-api/v1/access) — never persisted.
"""
import sys, os, json, base64, hashlib, struct

MAGIC = b"KURIKENC"
AAD_MAGIC = b"KURIAAD1"


def parse_header(blob: bytes) -> dict:
    if len(blob) < 36:
        raise ValueError("Truncated KENC v1 header")
    hdr = blob[:36]
    if hdr[:8] != MAGIC:
        raise ValueError("Invalid KENC v1 magic")
    major, minor = hdr[8], hdr[9]
    if major != 1 or minor != 1:
        raise ValueError(f"Unsupported KENC format version {major}.{minor}")
    ext_len, aad_len = struct.unpack(">HH", hdr[10:14])
    nonce_len, tag_len = hdr[14], hdr[15]
    if ext_len != 0 or aad_len != 36 or nonce_len != 12 or tag_len != 16:
        raise ValueError("Invalid KENC v1 fixed lengths")
    (plain_len,) = struct.unpack(">Q", hdr[16:24])
    if not (1 <= plain_len <= 0x4000000):
        raise ValueError("KENC v1 ciphertext size outside format bounds")
    nonce = hdr[24:36]
    total_expected = 36 + plain_len + 16
    if len(blob) != total_expected:
        raise ValueError(f"Size mismatch: file={len(blob)} expected={total_expected}")
    return {
        "version": f"{major}.{minor}",
        "plaintext_len": plain_len,
        "nonce_hex": nonce.hex(),
        "ciphertext": blob[36:36 + plain_len],
        "tag_hex": blob[36 + plain_len:].hex(),
        "file_sha256_hex": hashlib.sha256(blob).hexdigest(),
    }


def build_aad(hdr: bytes, plugin_id: str, version: str, plaintext_sha256: bytes,
              plaintext_len: int) -> bytes:
    pid = plugin_id.encode("ascii")
    ver = version.encode("ascii")
    aad = bytearray()
    aad += hdr
    aad += AAD_MAGIC
    aad += bytes([1, 1])
    aad += struct.pack(">H", len(pid)) + pid
    aad += struct.pack(">H", len(ver)) + ver
    aad += plaintext_sha256
    aad += struct.pack(">Q", plaintext_len)
    return bytes(aad)


def _norm_nonce(nonce: str) -> bytes:
    """Accept the nonce in either form: 24-char hex or plan-JSON base64."""
    if len(nonce) == 24:
        return bytes.fromhex(nonce)
    return base64.b64decode(nonce)


def decrypt(blob: bytes, content_key: bytes, plugin_id: str, version: str,
            plaintext_sha256: bytes = None, nonce_hex: str = None) -> bytes:
    """Decrypt a KENC v1 blob. Returns the plaintext (a JAR).

    plaintext_sha256: 32-byte expected digest (plan's `plaintextSha256`);
        if None, a zero digest placeholder is used and the caller must
        verify separately (decryption will still authenticate, but Kuri's
        own check would fail with a different plan digest).
    nonce_hex: if given, validates the header nonce; accepts the plan's
        base64 form ("n4QDS0LAuuJEy3id") or a hex string.
    """
    meta = parse_header(blob)
    if nonce_hex and meta["nonce_hex"] != _norm_nonce(nonce_hex).hex():
        raise ValueError("Header nonce does not match plan nonce")
    hdr = blob[:36]
    if plaintext_sha256 is None:
        plaintext_sha256 = b"\x00" * 32
    if len(content_key) != 32:
        raise ValueError("KENC v1 content key must be exactly 32 bytes")

    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    aad = build_aad(hdr, plugin_id, version, plaintext_sha256, meta["plaintext_len"])
    aes = AESGCM(content_key)
    pt = aes.decrypt(bytes.fromhex(meta["nonce_hex"]),
                     meta["ciphertext"] + bytes.fromhex(meta["tag_hex"]), aad)
    got = hashlib.sha256(pt).digest()
    if plaintext_sha256 not in (b"\x00" * 32, None) and got != plaintext_sha256:
        raise ValueError("Plaintext SHA-256 mismatch")
    return pt


def analyze(path: str) -> dict:
    blob = open(path, "rb").read()
    meta = parse_header(blob)
    meta["file"] = os.path.basename(path)
    meta["file_size"] = len(blob)
    meta["filename_matches_sha256"] = (
        os.path.basename(path) == meta["file_sha256_hex"] + "")
    return {k: v for k, v in meta.items() if k != "ciphertext"}


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "decrypt":
        # decrypt <file> <plan.json>  (plan.json: contentKey, nonce, pluginId,
        # version, plaintextSha256 — from /client-api/v1/access load plan)
        path, plan_path = sys.argv[2], sys.argv[3]
        plan = json.load(open(plan_path))
        key = base64.b64decode(plan["contentKey"])
        sha = bytes.fromhex(plan["plaintextSha256"])
        pt = decrypt(open(path, "rb").read(), key, plan["pluginId"],
                     plan["version"], sha, plan.get("nonce"))
        out = sys.argv[4] if len(sys.argv) > 4 else plan["fileName"]
        open(out, "wb").write(pt)
        print(f"decrypted -> {out} ({len(pt)} bytes)")
    else:
        for path in sys.argv[1:]:
            print(json.dumps(analyze(path), indent=1))
