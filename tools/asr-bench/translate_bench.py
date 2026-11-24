"""EN->RU translation benchmark: Kimi (as the app calls it) vs Claude Haiku 5.5.

Source phrases are Soniox's transcripts of the Svarah clips (results/soniox.tsv: real ASR
output with punctuation and hesitations, like the app would send). Both models get the
app's exact prompt (KimiTranslationClient.kt). An optional blind judge (Claude Opus 5.5,
A/B order randomized, model names hidden) scores each pair.

Keys: KIMI_API_KEY and ANTHROPIC_API_KEY from the environment, or from
~/.config/synchra-bench.env (KEY=value lines) so they never have to be pasted anywhere.

Usage: translate_bench.py [--limit N] [--judge]
"""
import argparse
import csv
import json
import os
import random
import statistics
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import anthropic
import httpx

HERE = Path(__file__).parent
ENV_FILE = Path.home() / ".config" / "synchra-bench.env"
KIMI_URL = "https://api.moonshot.ai/v1/chat/completions"

# Must match KimiTranslationClient.kt
SYSTEM = ("You are a real-time interpreter. Translate CURRENT from en to ru. "
          "Use CONTEXT only to disambiguate. Reply with only the translation, no extra text.")

# $ per 1M tokens (input, output) — official pricing pages, 2026-11
PRICES = {"kimi-k2.6": (0.95, 4.00), "claude-haiku-5-5": (0.10, 0.50)}


def load_env_file():
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip())


def user_message(phrase, context=""):
    return f"CONTEXT: {context}\nCURRENT: {phrase}"


def kimi(http, phrase):
    t0 = time.perf_counter()
    r = http.post(KIMI_URL, json={
        "model": "kimi-k2.6",
        "messages": [{"role": "system", "content": SYSTEM},
                     {"role": "user", "content": user_message(phrase)}],
        "temperature": 0.6,
        "thinking": {"type": "disabled"},
    })
    r.raise_for_status()
    body = r.json()
    u = body.get("usage", {})
    return dict(text=body["choices"][0]["message"]["content"].strip(),
                seconds=time.perf_counter() - t0,
                tokens_in=u.get("prompt_tokens", 0), tokens_out=u.get("completion_tokens", 0))


def haiku(client, phrase):
    t0 = time.perf_counter()
    resp = client.messages.create(
        model="claude-haiku-5-5",
        max_tokens=1024,
        system=SYSTEM,
        # Translation needs no reasoning; thinking off (allowed at effort <= high) keeps it fast/cheap.
        thinking={"type": "disabled"},
        output_config={"effort": "low"},
        messages=[{"role": "user", "content": user_message(phrase)}],
    )
    text = "".join(b.text for b in resp.content if b.type == "text").strip()
    return dict(text=text, seconds=time.perf_counter() - t0,
                tokens_in=resp.usage.input_tokens, tokens_out=resp.usage.output_tokens)


JUDGE_SCHEMA = {
    "type": "object",
    "properties": {
        "score_a": {"type": "integer"},
        "score_b": {"type": "integer"},
        "better": {"type": "string", "enum": ["A", "B", "tie"]},
        "reason": {"type": "string"},
    },
    "required": ["score_a", "score_b", "better", "reason"],
    "additionalProperties": False,
}


def judge(client, phrase, a, b):
    prompt = (
        "You are grading English->Russian translations made by a real-time interpreter app.\n"
        "The English source is a speech-recognition transcript of accented speech; it may "
        "contain hesitations (um, uh) that a good interpreter drops.\n\n"
        f"SOURCE: {phrase}\n\nTRANSLATION A: {a}\n\nTRANSLATION B: {b}\n\n"
        "Score each 1-10 for accuracy of meaning, natural Russian, and brevity suitable for "
        "live subtitles. Say which is better (or tie) and give a one-sentence reason in Russian."
    )
    resp = client.messages.create(
        model="claude-opus-5-5",
        max_tokens=4096,
        output_config={"effort": "low", "format": {"type": "json_schema", "schema": JUDGE_SCHEMA}},
        messages=[{"role": "user", "content": prompt}],
    )
    return json.loads(next(b.text for b in resp.content if b.type == "text"))


def summarize(name, results):
    ok = [r for r in results if r]
    if not ok:
        print(f"{name:<18} ok=0/{len(results)}")
        return
    p_in, p_out = PRICES[name]
    cost = sum(r["tokens_in"] * p_in + r["tokens_out"] * p_out for r in ok) / 1e6
    secs = sorted(r["seconds"] for r in ok)
    print(f"{name:<18} ok={len(ok)}/{len(results)}  median={statistics.median(secs):.2f}s  "
          f"p90={secs[int(len(secs) * 0.9) - 1]:.2f}s  cost=${cost:.4f}  "
          f"per-call=${cost / max(1, len(ok)):.6f}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=200)
    ap.add_argument("--judge", action="store_true")
    a = ap.parse_args()

    load_env_file()
    for key in ("KIMI_API_KEY", "ANTHROPIC_API_KEY"):
        if not os.environ.get(key):
            raise SystemExit(f"Set {key} (environment or {ENV_FILE})")

    rows = list(csv.DictReader(open(HERE / "results" / "soniox.tsv"), delimiter="\t"))[: a.limit]
    phrases = [r["soniox_text"] for r in rows]
    http = httpx.Client(headers={"Authorization": f"Bearer {os.environ['KIMI_API_KEY']}"}, timeout=60)
    claude = anthropic.Anthropic()

    def safe(fn, *args):
        try:
            return fn(*args)
        except Exception as e:  # keep going; failures are counted in the summary
            print(f"{fn.__name__} failed: {e!r}")
            return None

    with ThreadPoolExecutor(4) as pool:
        kimi_out = list(pool.map(lambda p: safe(kimi, http, p), phrases))
        haiku_out = list(pool.map(lambda p: safe(haiku, claude, p), phrases))

    judged = [None] * len(rows)
    if a.judge:
        rng = random.Random(0)

        def grade(i):
            k, h = kimi_out[i], haiku_out[i]
            if not (k and h):
                return None
            swap = rng.random() < 0.5  # blind: hide which model is A
            first, second = (h, k) if swap else (k, h)
            g = safe(judge, claude, phrases[i], first["text"], second["text"])
            if not g:
                return None
            kimi_score, haiku_score = (g["score_b"], g["score_a"]) if swap else (g["score_a"], g["score_b"])
            winner = {"tie": "tie", "A": "haiku" if swap else "kimi", "B": "kimi" if swap else "haiku"}[g["better"]]
            return dict(kimi=kimi_score, haiku=haiku_score, winner=winner, reason=g["reason"])

        with ThreadPoolExecutor(4) as pool:
            judged = list(pool.map(grade, range(len(rows))))

    out = HERE / "results" / "translations.tsv"
    with open(out, "w") as f:
        f.write("file\tsource\tkimi\thaiku\tsoniox\tkimi_score\thaiku_score\twinner\treason\n")
        for r, p, k, h, j in zip(rows, phrases, kimi_out, haiku_out, judged):
            j = j or {}
            f.write("\t".join([r["file"], p, (k or {}).get("text", "ERROR"), (h or {}).get("text", "ERROR"),
                               r["translation"], str(j.get("kimi", "")), str(j.get("haiku", "")),
                               j.get("winner", ""), j.get("reason", "")]) + "\n")

    print(f"\n{len(rows)} phrases -> {out}")
    summarize("kimi-k2.6", kimi_out)
    summarize("claude-haiku-5-5", haiku_out)
    graded = [j for j in judged if j]
    if graded:
        wins = {w: sum(j["winner"] == w for j in graded) for w in ("kimi", "haiku", "tie")}
        print(f"judge (Opus 5.5, blind): kimi avg={statistics.mean(j['kimi'] for j in graded):.2f}  "
              f"haiku avg={statistics.mean(j['haiku'] for j in graded):.2f}  wins={wins}")


if __name__ == "__main__":
    main()
