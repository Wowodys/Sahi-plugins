# 03 — String Deobfuscation: the LCG-XOR Scheme and Beyond

> Observed in both the Kuri launcher (`src/` decompile, launcher v1.5.6) and all
> three decrypted plugins (`plugin-src/`). The scheme is identical everywhere;
> only the seeds differ. This doc covers how to recognize it, the two decoding
> layers, and the method that defeats both.

## 1. Layer 1 — LCG-XOR per character

Every obfuscated string literal is compiled into the same shape: a `char[]` (or
`String.join`ed sequence) of `\uXXXX` escapes plus an `int` seed constant, run
through a linear congruential generator at decode time:

```java
int n = seed;                        // per-class / per-literal constant
for each char c:
    n = n * 1664525 + 1013904223;    // int32 wrap-around arithmetic
    c ^= n >>> 16;                   // XOR with the HIGH 16 bits of the LCG state
```

The constants `1664525` / `1013904223` are the classic Numerical Recipes LCG
multiplier/increment. Only the high half of the 32-bit state touches the
characters, so each step yields 16 bits of keystream. Each string carries its
own seed; the seeds are themselves ordinary (small) constants, or computed from
opaque predicates (§4).

Why this scheme is convenient for the analyst once you know it:

- No external key material — everything is in the constant pool.
- The transform is stateless per character position and trivially invertible
  given (chars, seed).
- The same literal always decodes to the same string, so you can annotate a
  whole decompiled tree mechanically.

### Recognizing it in decompiled output

CFR renders the literals as sequences of `"\uXXXX\uXXXX..."` escapes, usually
immediately followed by the seed as an int argument:

```java
return String.join("", new String[]{
    "\ua3f2\u0917...", -1843201159
});
```

The tooling regex targets exactly this: `"((?:\\u[0-9a-fA-F]{4})+)"\s*,\s*(-?\d+)\)`.
If a future decompiler inlines the char array differently
(`new char[]{0xa3f2, ...}`), adjust the regex — the LCG core never changes.

## 2. `deob_strings.py` — annotating a whole source tree

`tools/deob_strings.py` walks a decompiled tree, finds every escaped-literal +
seed pair, decodes it, and **appends an annotation comment next to the literal,
in place**:

```
"\ua3f2\u0917...", -1843201159)   /* => "https://api.kuriplugins.com/client-api/v1/access" */
```

Non-printable results are annotated as `<binary>` (some "strings" are actually
encoded blobs for layer 2). Because it rewrites files in place, run it on a
scratch copy of the decompiled sources:

```bash
# 1. copy the decompiled tree (the tool annotates IN PLACE)
cp -r kuri-analysis/src /tmp/kuri-src-annotated
# 2. annotate
python tools/deob_strings.py /tmp/kuri-src-annotated
# (or set the env var: DEOB_SRC=/tmp/kuri-src-annotated python tools/deob_strings.py)
# 3. re-grep for interesting decoded content
grep -rn "api.kuriplugins.com" /tmp/kuri-src-annotated
```

The one knob you might need: the `LIT` regex, if a different decompiler emits
a different literal shape. Everything downstream (the `decode()` LCG) is stable.

## 3. Layer 2 — the XOR-with-seed-string scheme for long strings

Short constants use layer 1 alone. Long strings (dialog text, URLs, message
templates) get a second layer whose keystream mixes **the decimal digits of
another obfuscated integer** with a key byte array:

```
data[i] ^= str(seedInt).getBytes()[i % len] ^ keyBytes[i % len]
```

then the result decodes as **UTF-16 big-endian**. The `seedInt` is itself
hidden behind `Integer.parseInt(<layer-1-obfuscated-string>)` and sometimes
mutated through opaque predicates before use (see §4).

Practical decoding recipe (no script needed — this layer shows up rarely):

1. Run `deob_strings.py` first. Layer-1 annotations reveal the seed strings.
2. In the decompiled source, locate the decode loop: it is the method that
   takes the `short[]`/`byte[]` blob plus the parsed seed and key bytes.
3. Reimplement the 3-line XOR loop in Python over the dumped blob constants
   (they survive as array initializers or layer-1 strings), decode UTF-16BE.
4. Sanity check: the result must be printable English/game text. If you get
   garbage, your seedInt is being mutated by an opaque predicate — go to §4.

## 4. Opaque predicates and why "just run it" wins

The plugins (and launcher) fold string setup into `switch` statements on values
produced by junk math like `qqrnfthdchipcafl.vkgridvuyyuvyqbi(n)`
(`xdjkqfomzkutdtcl.*` classes — bit-shuffle helpers that always return a fixed
value per input, but which no decompiler evaluates statically). CFR emits
`Unable to fully structure code` and you cannot reason about which seed reaches
which literal.

There are two responses:

1. **Emulate the junk math** (translate the shuffle to Python, evaluate the
   switch) — works, but slow and brittle.
2. **Run the code and let the JVM decode for you** — the recommended default,
   because it defeats *every* layer at once, including runtime key mutation:

```bash
cd tools
javac ConstDump.java EnumDump.java

# dump decrypted static constants + the injected menu text of the construction plugin:
java -cp ".;sdk/jars/*;sdk/jars/kuri/kuri-runelite-v1.7.64.jar;../kuri-analysis/decrypted/kuri-click-construction-1.0.31.jar" \
    ConstDump | tee const-dump.txt

# dump an enum with its per-value getter values (servant table):
java -cp ".;sdk/jars/*;sdk/jars/kuri/kuri-runelite-v1.7.64.jar;../kuri-analysis/decrypted/kuri-click-construction-1.0.31.jar" \
    EnumDump | tee enum-dump.txt
```

Why this works: obfuscated constants tables are populated in `<clinit>` /
constructor code by the very string-decrypt routines you would otherwise
reverse. Loading the class on the real JVM executes that code, and reflection
reads the **decrypted, final values**:

- `ConstDump` reflects over `getDeclaredFields()` (static, non-synthetic) of the
  constants class and prints `name : type = value`.
- `ConstDump`'s second half instantiates the `MenuConfig` implementation class
  and invokes `getMenuOptionText()` — recovering the exact injected menu string
  (`<col=8532cb>Kuri Click Construction`) without touching layer 2 at all.
- `EnumDump` calls `getEnumConstants()` and prints every zero-arg
  `String`/`int` getter per constant — that recovered the whole servant table
  (npc id / greeting / capacity / wage) in one run.

Classloading requirements: the target class's supertypes and anything touched
during constant initialization must be on the classpath. That is why the
command above includes the RuneLite jars (`net.runelite.*` supertypes), the
**Kuri framework jar** (`com.kuri.*` interfaces the plugin classes implement),
and the decrypted plugin jar itself. If a dump fails with
`NoClassDefFoundError`, the missing class name tells you exactly which jar is
missing from the classpath — add it and retry.

Editing the dumps: `ConstDump.dump("com.kuri.plugin.kuriclickconstruction.e.a")`
hardcodes the class; for a new plugin, change the package segment (or take it
from `args[0]`), and likewise the `a.b` / `a.c` names in `main`. The obfuscated
`a`/`b`/`c` positions are per-plugin but stable within a release; the
decompiled `summary.txt` (see §tooling doc) tells you which letter is which.

## 5. Decision table

| Situation | Tool |
|---|---|
| Thousands of layer-1 strings across a decompiled tree | `deob_strings.py` (annotate in place) |
| A handful of layer-2 strings | Manual 3-line XOR re-implementation (§3) |
| Constants tables, enums, menu text — anything computed at class-init | `ConstDump` / `EnumDump` (run the classes) |
| Seed values hidden behind opaque predicates | `ConstDump`/`EnumDump` — reflection sidesteps them |
| Launcher strings (pre-classload, e.g. config keys) | `deob_strings.py` on the launcher decompile |

See [06-tooling.md](06-tooling.md) for exact, verified command lines for all of
these, and [05-plugin-internals-tour.md](05-plugin-internals-tour.md) for where
these obfuscation layers sit inside a plugin's architecture.
