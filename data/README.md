# Dictionary data

## dehkhoda.jsonl.gz

Full Dehkhoda dictionary (Loghatnameh), 343,316 entries, gzip-compressed
UTF-8 JSONL. One JSON object per line: `{"w":"headword","d":"definition"}`.
Definitions keep their original simple HTML (`span`, `br`, `sup`); no
Persian normalization is applied — the app normalizes on import
(`PersianText` in `Data.kt`). Homographs are separate lines.

- Source: https://github.com/DehkhodaProject/DehkhodaProject — release 1.0,
  `dehkhoda.zip` (raw HTML dump of https://icps.ut.ac.ir/fa/dictionary).
- License: CC0 1.0 Universal (public domain dedication), as released by the
  DehkhodaProject authors. Full text: `LICENSE` inside the source zip and
  https://creativecommons.org/publicdomain/zero/1.0/
- Converted with `scripts/convert_dehkhoda.py` (entry order = source id):

      python scripts/convert_dehkhoda.py dehkhoda.zip dehkhoda.jsonl
      gzip -9 -c dehkhoda.jsonl > data/dehkhoda.jsonl.gz

- SHA-256 of the uncompressed JSONL:
  `38fde5e78d4fb2264f198010959eadcfdaa4da08d85b8721f642fab81e8b4b3d`
- SHA-256 of `dehkhoda.jsonl.gz`:
  `758c915b2fd277b376d863153364879108c1608598ffe35487e5f759d5db961d`

The source dump omits 506 entries of the printed dictionary (its `MISSING`
file lists them); they are not present upstream.

`sample.jsonl` (16 entries) is kept as a fallback for the CI build.
The uncompressed `dehkhoda.jsonl` is an intermediate file and is not
committed (see `.gitignore`).
