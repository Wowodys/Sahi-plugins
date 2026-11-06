"""Watch Kuri-related dirs and log a timeline of file changes.

Usage:  python fs_watcher.py [OUT.jsonl] [DIR [DIR ...]]
Defaults watch the standard Kuri cache / plugin dirs and write fs-timeline.jsonl
next to the current directory.
"""
import os, time, json, hashlib, sys

WATCH = sys.argv[2:] or [
    r"C:\Users\sahij\AppData\Local\Kuri\cache\plugins",
    r"C:\Users\sahij\.runelite\plugins",
    r"C:\Users\sahij\.runelite\repository2\k",
]
OUT = sys.argv[1] if len(sys.argv) > 1 else "fs-timeline.jsonl"

def snap():
    state = {}
    for root in WATCH:
        if not os.path.isdir(root):
            continue
        for fn in os.listdir(root):
            p = os.path.join(root, fn)
            try:
                st = os.stat(p)
                if not os.path.isfile(p):
                    continue
                if st.st_size <= 1_000_000:  # hash small files only
                    h = hashlib.sha256(open(p, "rb").read()).hexdigest()[:16]
                else:
                    h = f"size:{st.st_size}"
                state[p] = (st.st_size, st.st_mtime, h)
            except OSError:
                pass
    return state

def main():
    prev = snap()
    with open(OUT, "a", encoding="utf-8") as f:
        f.write(json.dumps({"ts": time.time(), "event": "watch-start",
                            "known": len(prev)}) + "\n")
        f.flush()
        while True:
            time.sleep(2)
            cur = snap()
            for p, v in cur.items():
                if p not in prev:
                    f.write(json.dumps({"ts": time.time(), "event": "added",
                                        "file": p, "size": v[0], "hash16": v[2]}) + "\n")
                elif prev[p][0] != v[0] or prev[p][2] != v[2]:
                    f.write(json.dumps({"ts": time.time(), "event": "changed",
                                        "file": p, "size": v[0], "hash16": v[2],
                                        "prev": prev[p][2]}) + "\n")
            for p in prev:
                if p not in cur:
                    f.write(json.dumps({"ts": time.time(), "event": "removed",
                                        "file": p, "prev_hash16": prev[p][2]}) + "\n")
            f.flush()
            prev = cur

if __name__ == "__main__":
    main()
