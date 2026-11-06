"""Decode Kuri LCG-XOR-obfuscated string literals in decompiled sources.

Scheme (from $k1odpwal):
    n = seed
    for each char c: n = n*1664525 + 1013904223 (i32 wrap); c ^= n >>> 16
Appends `/* => "decoded" * /` annotation after each obfuscated literal.
NOTE: annotates files IN PLACE — run it on a scratch copy of the sources.
"""
import os, re, sys

# Source tree to annotate: positional arg wins, then DEOB_SRC env var.
# Example:  python deob_strings.py C:\path\to\decompiled\src
SRC = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("DEOB_SRC")
if not SRC:
    raise SystemExit("usage: python deob_strings.py <decompiled-src-dir>   "
                     "(or set DEOB_SRC)")
M32 = 0xFFFFFFFF

def decode(chars: str, seed: int) -> str:
    n = seed & M32
    out = []
    for ch in chars:
        n = (n * 1664525 + 1013904223) & M32
        out.append(chr(ord(ch) ^ (n >> 16)))
    return "".join(out)

LIT = r'"((?:\\u[0-9a-fA-F]{4})+)"\s*,\s*(-?\d+)\)'
rx = re.compile(LIT)
esc_rx = re.compile(r'\\u([0-9a-fA-F]{4})')

def esc_visible(s: str) -> str:
    return (s.replace("\\", "\\\\").replace('"', '\\"')
             .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"))

changed = total = 0
for dirpath, _, files in os.walk(SRC):
    for fn in files:
        if not fn.endswith(".java"):
            continue
        path = os.path.join(dirpath, fn)
        text = open(path, "r", encoding="utf-8", errors="replace").read()
        out = []
        pos = 0
        n_hits = 0
        for m in rx.finditer(text):
            esc, seed = m.group(1), int(m.group(2))
            chars = "".join(chr(int(h, 16)) for h in esc_rx.findall(esc))
            dec = decode(chars, seed)
            total += 1
            printable = all(32 <= ord(c) < 127 or c in "\n\r\t" for c in dec)
            ann = dec if printable else "<binary>"
            out.append(text[pos:m.start()])
            out.append(f'{m.group(0)} /* => "{esc_visible(ann)}" */')
            pos = m.end()
            n_hits += 1
        out.append(text[pos:])
        if n_hits:
            open(path, "w", encoding="utf-8").write("".join(out))
            changed += 1
print(f"decoded {total} strings in {changed} files")
