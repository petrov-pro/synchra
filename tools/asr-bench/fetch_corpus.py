"""Download an accented-English test corpus and write wav files + manifest.tsv.

Source: Svarah (Indian-accented English, spontaneous speech, with transcripts),
test split mirror on Hugging Face. Audio is never committed (see .gitignore).
"""
import argparse
import io
import random
import re
import urllib.request
from pathlib import Path

import pyarrow.parquet as pq
import soundfile as sf

URL = "https://huggingface.co/datasets/amrithanandini/svarah/resolve/main/data/test-00000-of-00001.parquet"


def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^a-z' ]", " ", text.lower())).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(Path(__file__).parent / "corpus"))
    ap.add_argument("--count", type=int, default=200)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    out = Path(args.out)
    (out / "wav").mkdir(parents=True, exist_ok=True)
    parquet = out / "svarah-test.parquet"
    if not parquet.exists():
        print("downloading", URL)
        urllib.request.urlretrieve(URL, parquet)

    rows = pq.read_table(parquet).to_pylist()
    rows = [r for r in rows if 2.0 <= r["duration"] <= 15.0 and normalize(r["sentence"])]
    random.Random(args.seed).shuffle(rows)

    with open(out / "manifest.tsv", "w") as m:
        m.write("file\treference\taccent\n")
        for i, r in enumerate(rows[: args.count]):
            data, sr = sf.read(io.BytesIO(r["audio"]["bytes"]), dtype="float32")
            if data.ndim > 1:
                data = data.mean(axis=1)
            assert sr == 16000, sr
            name = f"svarah_{i:04d}.wav"
            sf.write(out / "wav" / name, data, sr, subtype="PCM_16")
            m.write(f"wav/{name}\t{normalize(r['sentence'])}\tindian\n")
    print("wrote", args.count, "clips to", out)


if __name__ == "__main__":
    main()
