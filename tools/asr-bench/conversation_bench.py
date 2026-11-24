"""Conversation benchmark for the app's silence gate (SilenceGate.kt / silence_gate_sim.py).

Svarah clips are single dense phrases, so they show no savings. Here clips are glued into
"conversations" with natural pauses (2-10 s, a quiet room's noise in between) and each
conversation is streamed to Soniox twice with the app's settings: all audio, and only what
the silence gate lets through. Reports billed audio, WER and translation latency.

Usage: SONIOX_API_KEY=... conversation_bench.py [--conversations 10] [--phrases 10]
"""
import argparse
import asyncio
import csv
import os
import random
from pathlib import Path

import jiwer
import numpy as np
import soundfile as sf

import soniox_client
from bench import norm

HERE = Path(__file__).parent


def build(rows, conversations, phrases, seed=0):
    rng = random.Random(seed)
    noise = np.random.default_rng(seed)
    picked = rows[: conversations * phrases]
    convs = []
    for c in range(conversations):
        parts, refs = [], []
        for r in picked[c * phrases:(c + 1) * phrases]:
            pause = rng.uniform(2, 10)
            parts.append((noise.normal(0, 0.001, int(pause * 16000)) * 32768).astype("int16"))
            x, _ = sf.read(HERE / "corpus" / r["file"], dtype="int16")
            parts.append(x)
            refs.append(r["reference"])
        parts.append((noise.normal(0, 0.001, 3 * 16000) * 32768).astype("int16"))  # pause before Stop
        convs.append((np.concatenate(parts), " ".join(refs)))
    return convs


async def run(convs, api_key, gate, concurrency=5):
    sem = asyncio.Semaphore(concurrency)

    async def one(audio):
        async with sem:
            for attempt in range(3):
                try:
                    return await soniox_client._transcribe(
                        (audio, 16000), api_key, "ru", realtime=True, gate=gate, endpoint=True)
                except Exception as e:  # noqa: BLE001 - a flaky connection gets another try
                    print("retry:", repr(e))
                    await asyncio.sleep(2)
            return None

    return await asyncio.gather(*(one(a) for a, _ in convs))


def report(name, convs, out):
    ok = [(c, o) for c, o in zip(convs, out) if o]
    refs = [norm(c[1]) for c, _ in ok]
    hyps = [norm(o["text"]) for _, o in ok]
    audio = sum(o["audio_s"] for _, o in ok)
    sent = sum(o["sent_audio_s"] for _, o in ok)
    tl = [o["translation_after_end_s"] for _, o in ok if o["translation_after_end_s"] is not None]
    print(f"{name:<14} WER {jiwer.wer(refs, hyps) * 100:5.2f}%   billed audio {sent / 60:5.1f} of {audio / 60:5.1f} min "
          f"({sent / audio:.0%})   translation after end {np.mean(tl):.2f}s   failed {len(convs) - len(ok)}")
    return sent


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--conversations", type=int, default=10)
    ap.add_argument("--phrases", type=int, default=10)
    a = ap.parse_args()
    api_key = os.environ.get("SONIOX_API_KEY") or exit("Set SONIOX_API_KEY")
    rows = list(csv.DictReader(open(HERE / "corpus" / "manifest.tsv"), delimiter="\t"))
    convs = build(rows, a.conversations, a.phrases)
    print(f"{len(convs)} conversations x {a.phrases} phrases, pauses 2-10 s")
    full = asyncio.run(run(convs, api_key, gate=False))
    gated = asyncio.run(run(convs, api_key, gate=True))
    s_full = report("all audio", convs, full)
    s_gated = report("silence gate", convs, gated)
    print(f"billed audio saved by the gate: {1 - s_gated / s_full:.0%}")


if __name__ == "__main__":
    main()
