"""Free mode speaker labels without pauses: voice prints over a sliding window.

diarization_local.py labels whole pieces between pauses, so speakers who take turns with
little or no pause end up in one piece. Here every HOP of audio the last WINDOW of speech gets a
voice print; a different speaker must win CONFIRM windows in a row before the label changes, and
the change is dated BACK seconds before the first window that saw it (the window straddles the
change). Compared with the pause method on normal (0.2-2.5 s) and short (0.05-0.5 s) pauses.

Score: share of true speech time (100 ms frames) with the right label, labels matched one-to-one.
Usage: .venv/bin/python diarization_window.py [--model name] [--conversations 6]
"""
import argparse
import collections
import itertools
import random

import numpy as np

import diarization_local as d
from silence_gate_sim import MIN_SPEECH_RMS, SPEECH_OVER_NOISE, SilenceGate

F = d.CHUNK  # 100 ms frames


def speech_frames(audio):
    gate, flags = SilenceGate(), []
    for i in range(0, len(audio) - F + 1, F):
        chunk = audio[i:i + F]
        level = float(np.sqrt(np.mean(np.square(chunk, dtype=np.float64))))
        flags.append(level > max(MIN_SPEECH_RMS, gate.floor * SPEECH_OVER_NOISE))
        gate.process(chunk)
    return flags


def frame_truth(truth, n):
    out = [None] * n
    for a, b, who in truth:
        for f in range(a // F, min(n, b // F)):
            out[f] = who
    return out


def score_frames(pred, truth_frames):
    overlap = collections.Counter((p, t) for p, t in zip(pred, truth_frames) if t is not None and p is not None)
    used_p, used_t, correct = set(), set(), 0
    for (p, t), o in overlap.most_common():
        if p not in used_p and t not in used_t:
            used_p.add(p)
            used_t.add(t)
            correct += o
    total = sum(1 for t in truth_frames if t is not None)
    return correct / total, len({p for p in pred if p is not None})


def label_window(audio, flags, embed, window=15, hop=5, min_speech=10, thr=0.4, confirm=2, back=10, max_speakers=8):
    """Online: returns a label per frame (None before the first label)."""
    n = len(flags)
    labels = [None] * n
    sums, current, pending, pending_n, pending_at, pending_prints = [], None, None, 0, 0, []
    last_speech = collections.deque(maxlen=window)  # indices of speech frames in the window
    for f in range(n):
        if flags[f]:
            last_speech.append(f)
        if current is not None:
            labels[f] = current
        if (f + 1) % hop or len([x for x in last_speech if x > f - window]) < min_speech:
            continue
        idx = [x for x in last_speech if x > f - window]
        e = embed(np.concatenate([audio[x * F:(x + 1) * F] for x in idx]))
        scores = [float(np.dot(e, s / np.linalg.norm(s))) for s in sums]
        best = int(np.argmax(scores)) if scores else -1
        cand = best if best >= 0 and (scores[best] >= thr or len(sums) >= max_speakers) else -1  # -1: new
        if current is None:
            sums.append(e.copy())
            current = 0
            for g in range(max(0, f - window + 1), f + 1):
                labels[g] = current
            continue
        if cand == current:
            sums[current] += e
            pending, pending_n, pending_prints = None, 0, []
            continue
        if cand == pending:
            pending_n += 1
            pending_prints.append(e)
        else:
            pending, pending_n, pending_at, pending_prints = cand, 1, f, [e]
        if pending_n >= confirm:
            if pending == -1:
                sums.append(np.sum(pending_prints, axis=0))
                current = len(sums) - 1
            else:
                current = pending
                sums[current] += np.sum(pending_prints, axis=0)
            for g in range(max(0, pending_at - back), f + 1):
                labels[g] = current
            pending, pending_n, pending_prints = None, 0, []
    return labels


def label_pause(audio, truth_n, embed, thr=0.4):
    """The app's current method, as per-frame labels (a label holds until the next piece)."""
    d.PAUSE_CHUNKS, d.MIN_SECONDS = 6, 0.75
    segs = d.segments_pause(audio)
    labs = d.label_online([embed(audio[a:b]) for a, b in segs], [(b - a) / d.SR for a, b in segs], thr)
    out = [None] * truth_n
    starts = [a // F for a, _ in segs] + [truth_n]
    for i, lab in enumerate(labs):
        for f in range(0 if i == 0 else starts[i], starts[i + 1]):
            out[f] = lab
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--model", default="3dspeaker_speech_campplus_sv_en_voxceleb_16k")
    p.add_argument("--conversations", type=int, default=6)
    args = p.parse_args()
    ex = d.Extractor(d.MODELS / f"{args.model}.onnx")
    cache = {}

    def embed(x):
        key = (len(x), float(x[:50].sum()), float(x[-50:].sum()))
        if key not in cache:
            cache[key] = ex(x)
        return cache[key]

    speakers = sorted(p.name for p in d.CORPUS.iterdir() if p.is_dir())
    for gap_name, gap in (("normal", (0.2, 2.5)), ("short", (0.05, 0.5))):
        convs = []
        for n in (2, 3, 4):
            for k in range(args.conversations):
                rng = random.Random(1000 * n + k)
                audio, truth = d.build(rng.sample(speakers, n), 16, seed=1000 * n + k, gap=gap)
                flags = speech_frames(audio)
                convs.append((n, audio, flags, frame_truth(truth, len(flags))))
        print(f"\n### pauses {gap_name} {gap}")

        def report(name, fn):
            res = collections.defaultdict(list)
            for n, audio, flags, tf in convs:
                res[n].append(score_frames(fn(audio, flags), tf))
            allv = [a for v in res.values() for a, _ in v]
            print(f"{name:42s} " + " ".join(f"{n}spk {np.mean([a for a, _ in res[n]]):6.1%}/{np.mean([l for _, l in res[n]]):3.1f}" for n in (2, 3, 4)) + f"  all {np.mean(allv):6.1%}")

        report("pause 600ms (app now)", lambda a, fl: label_pause(a, len(fl), embed))
        for window, hop, thr, confirm, back in itertools.product((15, 20), (5,), (0.35, 0.45), (2, 3), (5, 10)):
            report(f"window {window/10}s hop {hop/10}s thr {thr} confirm {confirm} back {back/10}s",
                   lambda a, fl: label_window(a, fl, embed, window=window, hop=hop, min_speech=window * 2 // 3,
                                              thr=thr, confirm=confirm, back=back))


if __name__ == "__main__":
    main()
