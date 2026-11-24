"""Streaming-ASR WER/latency benchmark mirroring the app's sherpa-onnx config.

latency s: streaming = time to first partial; offline = decode time after the phrase ends;
soniox = time from end of audio to the last final original token (network included).
Usage: bench.py [--corpus DIR] [--configs a,b] [--save-baseline] [--check]
Corpus = DIR/manifest.tsv (file<TAB>reference<TAB>accent) + wav files (16 kHz mono).
"""
import argparse
import csv
import json
import re
import time
import urllib.request
from collections import defaultdict
from pathlib import Path

import jiwer
import numpy as np
import sherpa_onnx
import soundfile as sf

HERE = Path(__file__).parent
CACHE = Path.home() / ".cache" / "asr-bench"
HF = "https://huggingface.co"
ZIP = "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26"
STEM = "epoch-99-avg-1-chunk-16-left-128"


def zipformer(suffix, method, threads):
    return dict(repo=ZIP, method=method, threads=threads, model_type="",
                files={k: f"{k}-{STEM}{suffix}" for k in ("encoder", "decoder", "joiner")})


def nemo(repo, method="greedy_search", threads=4):
    return dict(repo=repo, method=method, threads=threads, model_type="nemo_transducer",
                files={k: f"{k}.int8.onnx" for k in ("encoder", "decoder", "joiner")})


# zipformer entries mirror AppContainer.kt; the rest are candidate replacements
CONFIGS = {
    "int8_greedy": zipformer(".int8.onnx", "greedy_search", 2),
    "fp32_greedy": zipformer(".onnx", "greedy_search", 4),
    "int8_beam": zipformer(".int8.onnx", "modified_beam_search", 4),
    "fp32_beam": zipformer(".onnx", "modified_beam_search", 4),
    "nemo_fc_80ms": nemo("csukuangfj/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-80ms-int8"),
    "nemo_fc_480ms": nemo("csukuangfj/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8"),
    "nemo_fc_1040ms": nemo("csukuangfj/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-1040ms-int8"),
    "nemotron_0.6b": nemo("csukuangfj/sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14"),
}


def whisper(name):
    repo = f"csukuangfj/sherpa-onnx-whisper-{name}"
    return dict(repo=repo, kind="whisper", threads=4, tokens=f"{name}-tokens.txt",
                files={k: f"{name}-{k}.int8.onnx" for k in ("encoder", "decoder")})


# Offline (whole phrase after a pause) — not streaming, latency = decode time after speech ends
CONFIGS.update({
    "whisper_base.en": whisper("base.en"),
    "whisper_small.en": whisper("small.en"),
    "whisper_medium.en": whisper("medium.en"),
    "whisper_distil_large_v3.5": whisper("distil-large-v3.5"),
    "whisper_turbo": whisper("turbo"),
    "parakeet_tdt_0.6b_v2": dict(
        repo="csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8", kind="offline_nemo", threads=4,
        files={k: f"{k}.int8.onnx" for k in ("encoder", "decoder", "joiner")}),
})
# Cloud, streaming, real-time paced; needs SONIOX_API_KEY. Translation is requested in the
# same stream (as the app would) — WER is computed on the original-language tokens only.
CONFIGS.update({
    "soniox": dict(kind="soniox", target_language="ru"),
    "soniox_no_translation": dict(kind="soniox", target_language=None),
})
LEAD_S, TAIL_S = 0.5, 2.0
CHUNK = 1600  # 0.1 s, like MicAudioSource-sized buffers


def fetch(repo, name):
    p = CACHE / repo.replace("/", "__") / name
    if not p.exists():
        print("downloading", repo, name)
        p.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(f"{HF}/{repo}/resolve/main/{name}", p)
    return str(p)


def make_offline(c):
    f = {k: fetch(c["repo"], v) for k, v in c["files"].items()}
    if c["kind"] == "whisper":
        return sherpa_onnx.OfflineRecognizer.from_whisper(
            tokens=fetch(c["repo"], c["tokens"]), language="en", task="transcribe",
            num_threads=c["threads"], **f)
    return sherpa_onnx.OfflineRecognizer.from_transducer(
        tokens=fetch(c["repo"], "tokens.txt"), model_type="nemo_transducer",
        num_threads=c["threads"], sample_rate=16000, feature_dim=80, **f)


def transcribe_offline(rec, wav):
    samples, sr = sf.read(wav, dtype="float32")
    t0 = time.perf_counter()
    s = rec.create_stream()
    s.accept_waveform(sr, samples)
    rec.decode_stream(s)
    dt = time.perf_counter() - t0
    return norm(s.result.text), dt / (len(samples) / sr), dt


def make_recognizer(cfg):
    c = CONFIGS[cfg]
    f = {k: fetch(c["repo"], v) for k, v in c["files"].items()}
    kw = dict(model_type=c["model_type"]) if c["model_type"] else {}
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=fetch(c["repo"], "tokens.txt"),
        num_threads=c["threads"],
        sample_rate=16000,
        feature_dim=80,
        decoding_method=c["method"],
        max_active_paths=4,
        enable_endpoint_detection=False,  # whole clip -> one final result
        **f, **kw,
    )


# Svarah references omit hesitations; models that transcribe them (Soniox does) would be
# penalised for hearing correctly, so they are dropped from both sides.
FILLERS = {"um", "uh", "uhm", "umm", "ah", "eh", "er", "erm", "hm", "hmm", "mm"}


def norm(t):
    words = re.sub(r"[^a-z' ]", " ", t.lower()).split()
    return " ".join(w for w in words if w not in FILLERS)


def transcribe(rec, wav):
    samples, sr = sf.read(wav, dtype="float32")
    assert sr == 16000, f"{wav}: need 16 kHz"
    # Svarah clips start right at the first word; streaming models drop that word with no
    # lead-in (a live mic always has some), so prepend silence like a real pause would.
    samples = np.concatenate([np.zeros(int(LEAD_S * sr), np.float32), samples])
    s = rec.create_stream()
    t0, first = time.perf_counter(), None
    for i in range(0, len(samples), CHUNK):
        s.accept_waveform(sr, samples[i:i + CHUNK])
        while rec.is_ready(s):
            rec.decode_stream(s)
        if first is None and rec.get_result(s).strip():
            first = time.perf_counter() - t0
    # Generous trailing silence: with 0.5 s the last word was often cut off, while the app
    # only finalizes a phrase after 0.6-1.2 s of silence anyway.
    s.accept_waveform(sr, np.zeros(int(TAIL_S * sr), np.float32))
    s.input_finished()
    while rec.is_ready(s):
        rec.decode_stream(s)
    dt = time.perf_counter() - t0
    return norm(rec.get_result(s)), dt / (len(samples) / sr), first


def run_soniox(cfg, rows, corpus):
    import soniox_client

    out = soniox_client.transcribe_all([corpus / r["file"] for r in rows],
                                       target_language=CONFIGS[cfg]["target_language"])
    failed = sum(o is None for o in out)
    rows, out = zip(*[(r, o) for r, o in zip(rows, out) if o is not None])
    refs, hyps = defaultdict(list), defaultdict(list)
    for r, o in zip(rows, out):
        for k in (r["accent"], "ALL"):
            refs[k].append(r["reference"])
            hyps[k].append(norm(o["text"]))
    res = {k: round(jiwer.wer(refs[k], hyps[k]) * 100, 2) for k in refs}
    mean = lambda key: round(sum(o[key] for o in out if o[key] is not None)
                             / max(1, sum(o[key] is not None for o in out)), 3)
    res["rtf"] = "-"
    res["first_partial_s"] = mean("text_after_end_s")
    res["first_word_s"] = mean("first_word_s")
    if CONFIGS[cfg]["target_language"]:
        res["translation_after_end_s"] = mean("translation_after_end_s")
    res["failed_clips"] = failed
    res["audio_hours"] = round(sum(o["audio_s"] for o in out) / 3600, 3)

    # Side-by-side file for eyeballing recognition and translation quality.
    (HERE / "results").mkdir(exist_ok=True)
    with open(HERE / "results" / f"{cfg}.tsv", "w") as f:
        f.write("file\treference\tsoniox_text\ttranslation\n")
        for r, o in zip(rows, out):
            f.write(f"{r['file']}\t{r['reference']}\t{o['text']}\t{o['translation']}\n")
    return res


def run(cfg, rows, corpus):
    if CONFIGS[cfg].get("kind") == "soniox":
        return run_soniox(cfg, rows, corpus)
    offline = "kind" in CONFIGS[cfg]
    rec = make_offline(CONFIGS[cfg]) if offline else make_recognizer(cfg)
    do = transcribe_offline if offline else transcribe
    refs, hyps, rtf, firsts = defaultdict(list), defaultdict(list), [], []
    for r in rows:
        hyp, x, first = do(rec, corpus / r["file"])
        for k in (r["accent"], "ALL"):
            refs[k].append(r["reference"])
            hyps[k].append(hyp)
        rtf.append(x)
        if first is not None:
            firsts.append(first)
    res = {k: round(jiwer.wer(refs[k], hyps[k]) * 100, 2) for k in refs}
    res["rtf"] = round(sum(rtf) / len(rtf), 3)
    res["first_partial_s"] = round(sum(firsts) / max(len(firsts), 1), 3)
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", default=str(HERE / "corpus"))
    ap.add_argument("--configs", default=",".join(CONFIGS))
    ap.add_argument("--save-baseline", action="store_true")
    ap.add_argument("--check", action="store_true", help="fail if WER regresses vs baseline.json")
    ap.add_argument("--tolerance", type=float, default=1.0, help="allowed WER increase, points")
    a = ap.parse_args()

    corpus = Path(a.corpus)
    rows = list(csv.DictReader(open(corpus / "manifest.tsv"), delimiter="\t"))
    rows = [{**r, "reference": norm(r["reference"])} for r in rows]
    results = {c: run(c, rows, corpus) for c in a.configs.split(",")}

    print(f"\n{len(rows)} clips, corpus={corpus}")
    print(f"{'config':<27}{'WER% ALL':>9}{'RTF':>8}{'latency s':>11}   per-accent WER%")
    for c, r in results.items():
        acc = " ".join(f"{k}={v}" for k, v in r.items() if k not in ("ALL", "rtf", "first_partial_s"))
        acc = acc.replace("first_word_s=", "1st word s=")
        print(f"{c:<27}{r['ALL']:>9}{r['rtf']:>8}{r['first_partial_s']:>11}   {acc}")

    base_path = HERE / "baseline.json"
    if a.save_baseline:
        base_path.write_text(json.dumps(results, indent=2) + "\n")
        print("baseline saved")
    if a.check:
        base = json.loads(base_path.read_text())
        bad = [c for c, r in results.items() if c in base and r["ALL"] > base[c]["ALL"] + a.tolerance]
        if bad:
            raise SystemExit(f"WER regression: {bad}")
        print("no regression")


if __name__ == "__main__":
    main()
