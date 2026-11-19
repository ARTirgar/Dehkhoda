#!/usr/bin/env python3
"""Convert the DehkhodaProject raw dump into the app's JSONL format.

Source
------
https://github.com/DehkhodaProject/DehkhodaProject  (release 1.0, dehkhoda.zip)
A raw HTML dump of the Dehkhoda dictionary (Loghatnameh) from the University
of Tehran website (https://icps.ut.ac.ir/fa/dictionary), released by the
DehkhodaProject authors under CC0 1.0 Universal (public domain dedication).
Each numeric file in the zip holds one dictionary entry:

    Match 1 was found at <range>: <article> ... <h1 ...>HEADWORD</h1> ...
    <div class="content text-justify">DEFINITION HTML</div> ... </article>

Output
------
UTF-8 JSONL, exactly one JSON object per line: {"w":"headword","d":"definition"}
- Headwords: outer whitespace trimmed, HTML entities decoded. No other change.
- Definitions: original simple HTML (span/br/sup) kept verbatim, outer
  whitespace trimmed. No Persian normalization is applied anywhere -- the
  app performs its own normalization on import.
- Homographs remain separate lines. Entries are ordered by source entry id.
- Files without an entry (empty placeholders listed in the dump's MISSING
  file, plus README/LICENSE/NOTES metadata) are skipped.

Usage
-----
    python scripts/convert_dehkhoda.py <dehkhoda.zip> <output.jsonl>

The script validates its own output: every line is re-parsed, empty
headwords and blank lines are rejected, and 20 random samples are printed.
"""

import html
import json
import random
import re
import sys
import zipfile

MATCH_RE = re.compile(r"Match \d+ was found at [\d-]+: ")
H1_RE = re.compile(r"<h1[^>]*>(.*?)</h1>", re.S)
CONTENT_RE = re.compile(r'<div class="content text-justify">(.*?)</div>', re.S)
NUMERIC_NAME_RE = re.compile(r"^\d+$")


def iter_entries(zip_path):
    """Yield (entry_id, headword, definition_html) in source order."""
    with zipfile.ZipFile(zip_path) as z:
        entry_files = []
        for name in z.namelist():
            base = name.rsplit("/", 1)[-1]
            if NUMERIC_NAME_RE.match(base):
                entry_files.append((int(base), name))
        entry_files.sort()
        for entry_id, name in entry_files:
            text = z.read(name).decode("utf-8")
            m = MATCH_RE.search(text)
            if not m:
                continue  # empty placeholder (see MISSING file in the dump)
            seg = text[m.end():]
            h1 = H1_RE.search(seg)
            content = CONTENT_RE.search(seg)
            if not h1 or not content:
                raise ValueError(f"{name}: entry markup not found")
            word = html.unescape(h1.group(1)).strip()
            definition = content.group(1).strip()
            if not word:
                raise ValueError(f"{name}: empty headword")
            yield entry_id, word, definition


def convert(zip_path, out_path):
    count = 0
    with open(out_path, "w", encoding="utf-8", newline="\n") as out:
        for _, word, definition in iter_entries(zip_path):
            out.write(json.dumps({"w": word, "d": definition},
                                 ensure_ascii=False, separators=(",", ":")))
            out.write("\n")
            count += 1
    return count


def validate(out_path, sample_size=20, seed=20261119):
    """Re-parse every line; check structure, encoding and Persian chars."""
    entries = []
    with open(out_path, encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            if not line.strip():
                raise ValueError(f"line {lineno}: blank line")
            obj = json.loads(line)
            if set(obj) != {"w", "d"}:
                raise ValueError(f"line {lineno}: unexpected keys {set(obj)}")
            if not isinstance(obj["w"], str) or not obj["w"].strip():
                raise ValueError(f"line {lineno}: empty headword")
            if not isinstance(obj["d"], str):
                raise ValueError(f"line {lineno}: definition not a string")
            if "\ufffd" in obj["w"] or "\ufffd" in obj["d"]:
                raise ValueError(f"line {lineno}: U+FFFD replacement char")
            entries.append(obj)
    text = "".join(e["w"] for e in entries)
    for ch, name in (("\u06cc", "Persian ye"), ("\u06a9", "Persian kaf")):
        if ch not in text:
            raise ValueError(f"expected {name} ({ch!r}) in headwords")
    rng = random.Random(seed)
    samples = rng.sample(entries, min(sample_size, len(entries)))
    print(f"validated: {len(entries)} entries, all lines parse, "
          f"no blank lines, no empty headwords, no U+FFFD")
    print(f"--- {len(samples)} random samples ---")
    for e in samples:
        d = e["d"]
        print(json.dumps({"w": e["w"], "d": d[:120] + ("..." if len(d) > 120 else "")},
                         ensure_ascii=False))
    return len(entries)


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    zip_path, out_path = sys.argv[1], sys.argv[2]
    written = convert(zip_path, out_path)
    print(f"wrote {written} entries to {out_path}")
    validated = validate(out_path)
    if written != validated:
        sys.exit(f"count mismatch: wrote {written}, validated {validated}")


if __name__ == "__main__":
    main()
