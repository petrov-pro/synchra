"""Free mode speaker labels: how well on-device speaker embeddings tell speakers apart.

Builds conversations of 2-4 real speakers taking turns (Mini LibriSpeech dev-clean-2, as in
diarization_probe.py), cuts each into speech segments, gives every segment a speaker embedding
(sherpa-onnx SpeakerEmbeddingExtractor) and labels it online: the closest speaker heard so far
if the cosine score reaches the threshold, else a new speaker. Segments shorter than MIN_SECONDS
keep the previous label (too short for a reliable embedding).

Segmentations:
  gate   - the app's paragraphs today: SilenceGate speaking runs (a pause of 1.5 s splits);
  pause  - shorter pauses split too (PAUSE_CHUNKS × 100 ms of non-speech);
  oracle - the true utterances (upper bound: only the embeddings can be wrong).

Score: share of speech time given the right speaker (labels matched one-to-one to the true
speakers, greedily by overlap; extra labels count as wrong), and labels used vs true speakers.

Models: corpus/speaker-models/*.onnx from
https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models
Usage: .venv/bin/python diarization_local.py [--models name ...] [--conversations 6]
"""
import argparse
import collections
import random
import time
from pathlib import Path

import numpy as np
import sherpa_onnx
import soundfile as sf

from silence_gate_sim import MIN_SPEECH_RMS, SPEECH_OVER_NOISE, SilenceGate

HERE = Path(__file__).parent
CORPUS = HERE / "corpus" / "minilibri" / "LibriSpeech" / "dev-clean-2"
MODELS = HERE / "corpus" / "speaker-models"
SR = 16000
CHUNK = SR // 10
MIN_SECONDS = 1.0
PAUSE_CHUNKS = 3
THRESHOLDS = [0.3, 0.35, 0.4, 0.45, 0.5, 0.55, 0.6, 0.65]


def utterances(speaker):
    files = sorted((CORPUS / speaker).rglob("*.flac"))
    return [f for f in files if 1.5 <= sf.info(f).duration <= 9]


def build(speakers, turns, seed):
    """Turns of 1-2 utterances; pauses 0.2-2.5 s (often under the gate's 1.5 s); each speaker
    at their own loudness, as people at different distances from the phone."""
    rng = random.Random(seed)
    noise = np.random.default_rng(seed)
    pools = {s: rng.sample(utterances(s), k=len(utterances(s))) for s in speakers}
    gain = {s: rng.uniform(0.35, 1.0) for s in speakers}
    parts, truth, pos = [], [], 0

    def add(x, who):
        nonlocal pos
        parts.append(x)
        if who is not None:
            truth.append((pos, pos + len(x), who))
        pos += len(x)

    last = None
    for _ in range(turns):
        who = rng.choice([s for s in speakers if s != last])
        last = who
        for _ in range(rng.choice([1, 1, 2])):
            if not pools[who]:
                break
            add(noise.normal(0, 0.002, int(rng.uniform(0.2, 2.5) * SR)).astype("float32"), None)
            x, sr = sf.read(pools[who].pop(), dtype="float32")
            assert sr == SR
            add(x * gain[who] + noise.normal(0, 0.002, len(x)).astype("float32"), who)
    add(noise.normal(0, 0.002, 2 * SR).astype("float32"), None)
    return np.concatenate(parts), truth


def segments_gate(audio):
    """[start, end) sample ranges the app treats as one paragraph: SilenceGate speaking runs."""
    gate, out, start = SilenceGate(), [], None
    for i in range(0, len(audio) - CHUNK + 1, CHUNK):
        speaking = bool(gate.process(audio[i:i + CHUNK])[0])
        if speaking and start is None:
            start = i
        elif not speaking and start is not None:
            out.append((start, i))
            start = None
    if start is not None:
        out.append((start, len(audio)))
    return out


def segments_pause(audio):
    """Speech runs split by PAUSE_CHUNKS of non-speech (same level test as SilenceGate)."""
    gate, out, start, quiet, last_speech = SilenceGate(), [], None, 0, 0
    for i in range(0, len(audio) - CHUNK + 1, CHUNK):
        chunk = audio[i:i + CHUNK]
        level = float(np.sqrt(np.mean(np.square(chunk, dtype=np.float64))))
        speech = level > max(MIN_SPEECH_RMS, gate.floor * SPEECH_OVER_NOISE)
        gate.process(chunk)  # keeps the noise floor as the app's gate does
        if speech:
            if start is None:
                start = i
            quiet, last_speech = 0, i + CHUNK
        elif start is not None:
            quiet += 1
            if quiet >= PAUSE_CHUNKS:
                out.append((start, last_speech))
                start = None
    if start is not None:
        out.append((start, last_speech))
    return out


def segments_oracle(truth):
    return [(a, b) for a, b, _ in truth]


class Extractor:
    def __init__(self, path):
        self.ex = sherpa_onnx.SpeakerEmbeddingExtractor(
            sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=str(path), num_threads=2))
        self.seconds, self.calls = 0.0, 0

    def __call__(self, audio):
        t = time.perf_counter()
        s = self.ex.create_stream()
        s.accept_waveform(sample_rate=SR, waveform=audio)
        s.input_finished()
        e = np.array(self.ex.compute(s), dtype=np.float32)
        self.seconds += time.perf_counter() - t
        self.calls += 1
        return e / (np.linalg.norm(e) + 1e-9)


def label_online(embeddings, durations, threshold, max_speakers=8):
    """The app's online labeling: closest centroid if score ≥ threshold, else a new speaker."""
    sums, labels, prev = [], [], None
    for e, d in zip(embeddings, durations):
        if d < MIN_SECONDS and prev is not None:
            labels.append(prev)
            continue
        scores = [float(np.dot(e, s / np.linalg.norm(s))) for s in sums]
        best = int(np.argmax(scores)) if scores else -1
        if best >= 0 and (scores[best] >= threshold or len(sums) >= max_speakers):
            sums[best] = sums[best] + e * d  # longer segments weigh more
            label = best
        else:
            sums.append(e * d)
            label = len(sums) - 1
        labels.append(label)
        prev = label
    return labels


def score(segments, labels, truth):
    """Share of true speech time with the right label (one-to-one greedy match by overlap).
    A label holds until the next segment starts, as in the app: speech the segmentation missed
    (too quiet) still lands in the current paragraph."""
    overlap = collections.Counter()
    starts = [a for a, _ in segments]
    held = [(0 if i == 0 else a, starts[i + 1] if i + 1 < len(starts) else 10**12) for i, a in enumerate(starts)]
    for (a, b), label in zip(held, labels):
        for ta, tb, who in truth:
            o = min(b, tb) - max(a, ta)
            if o > 0:
                overlap[(label, who)] += o
    used_l, used_t, correct = set(), set(), 0
    for (label, who), o in overlap.most_common():
        if label not in used_l and who not in used_t:
            used_l.add(label)
            used_t.add(who)
            correct += o
    total = sum(b - a for a, b, _ in truth)
    return correct / total, len(set(labels))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--models", nargs="*", default=sorted(m.stem for m in MODELS.glob("*.onnx")))
    p.add_argument("--conversations", type=int, default=6, help="per speaker count")
    p.add_argument("--turns", type=int, default=16)
    args = p.parse_args()

    speakers = sorted(d.name for d in CORPUS.iterdir() if d.is_dir())
    convs = []
    for n in (2, 3, 4):
        for k in range(args.conversations):
            rng = random.Random(1000 * n + k)
            audio, truth = build(rng.sample(speakers, n), args.turns, seed=1000 * n + k)
            convs.append((n, audio, truth, {
                "gate": segments_gate(audio), "pause": segments_pause(audio), "oracle": segments_oracle(truth)}))
    hours = sum(len(a) for _, a, _, _ in convs) / SR / 3600
    print(f"{len(convs)} conversations, {hours * 60:.1f} min of audio")
    for kind in ("gate", "pause", "oracle"):
        segs = [len(c[3][kind]) for c in convs]
        print(f"  {kind:6s}: {sum(segs)} segments")

    for model in args.models:
        ex = Extractor(MODELS / f"{model}.onnx")
        cache = {}
        for ci, (_, audio, _, segs) in enumerate(convs):
            for kind, ranges in segs.items():
                cache[(ci, kind)] = [ex(audio[a:b]) for a, b in ranges]
        print(f"\n== {model} (dim {ex.ex.dim}): {1000 * ex.seconds / ex.calls:.0f} ms per segment on 2 threads")
        print(f"{'seg':6s} {'thr':>5s} " + " ".join(f"{f'{n}spk acc':>9s} {'labels':>6s}" for n in (2, 3, 4)) + f" {'all':>6s}")
        for kind in ("gate", "pause", "oracle"):
            for thr in THRESHOLDS:
                by_n = collections.defaultdict(list)
                for ci, (n, _, truth, segs) in enumerate(convs):
                    ranges = segs[kind]
                    labels = label_online(cache[(ci, kind)], [(b - a) / SR for a, b in ranges], thr)
                    by_n[n].append(score(ranges, labels, truth))
                cells = " ".join(
                    f"{np.mean([a for a, _ in by_n[n]]):9.1%} {np.mean([l for _, l in by_n[n]]):6.1f}" for n in (2, 3, 4))
                overall = np.mean([a for v in by_n.values() for a, _ in v])
                print(f"{kind:6s} {thr:5.2f} {cells} {overall:6.1%}")


if __name__ == "__main__":
    main()
