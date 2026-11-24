"""Soniox real-time STT (+ one-way translation) over WebSocket, for the benchmark.

Audio is streamed in 100 ms chunks at real-time pace, like the app's microphone, so the
latencies are comparable with what a phone would see (plus this machine's network).
Docs: https://docs.soniox.com/stt/api-reference/websocket-api
"""
import asyncio
import json
import os
import time

import soundfile as sf
import websockets

URL = "wss://stt-rt.soniox.com/transcribe-websocket"
MODEL = "stt-rt-v6"
CHUNK_S = 0.1


async def _transcribe(path, api_key, target_language, realtime):
    samples, sr = sf.read(path, dtype="int16")
    chunk = int(sr * CHUNK_S)
    config = {
        "model": MODEL,
        "audio_format": "pcm_s16le",
        "sample_rate": sr,
        "num_channels": 1,
        "language_hints": ["en"],
    }
    if target_language:
        config["translation"] = {"type": "one_way", "target_language": target_language}

    original, translation = [], []
    t0 = time.perf_counter()
    first = audio_end = last_original = last_translation = None

    async with websockets.connect(URL, additional_headers={"Authorization": f"Bearer {api_key}"},
                                  open_timeout=30) as ws:
        await ws.send(json.dumps(config))

        async def send_audio():
            nonlocal audio_end
            for i in range(0, len(samples), chunk):
                await ws.send(samples[i:i + chunk].tobytes())
                if realtime:
                    await asyncio.sleep(CHUNK_S)
            audio_end = time.perf_counter()
            await ws.send("")  # empty text frame = end of audio

        sender = asyncio.create_task(send_audio())
        async for message in ws:
            res = json.loads(message)
            if res.get("error_code"):
                sender.cancel()
                raise RuntimeError(f"Soniox {res['error_code']}: {res.get('error_message')}")
            now = time.perf_counter()
            for tok in res.get("tokens", []):
                is_translation = tok.get("translation_status") == "translation"
                if first is None and not is_translation and tok["text"].strip():
                    first = now - t0
                if tok.get("is_final"):
                    if is_translation:
                        translation.append(tok["text"])
                        last_translation = now
                    else:
                        original.append(tok["text"])
                        last_original = now
            if res.get("finished"):
                break
        await sender

    after_end = lambda t: round(t - audio_end, 3) if t and audio_end else None
    return {
        "text": "".join(original).strip(),
        "translation": "".join(translation).strip(),
        "first_word_s": first,
        "text_after_end_s": after_end(last_original),
        "translation_after_end_s": after_end(last_translation),
        "audio_s": len(samples) / sr,
        "wall_s": time.perf_counter() - t0,
    }


async def _transcribe_all(paths, api_key, target_language, realtime, concurrency):
    sem = asyncio.Semaphore(concurrency)

    async def one(p):
        async with sem:
            # New accounts drop extra concurrent connections now and then; retry those
            # (auth/billing errors are RuntimeError and fail fast).
            for attempt in range(5):
                try:
                    return await _transcribe(p, api_key, target_language, realtime)
                except (websockets.exceptions.ConnectionClosedError, TimeoutError, OSError) as e:
                    if attempt == 4:
                        print(f"giving up on {p}: {e!r}")
                        return None
                    await asyncio.sleep(2 * (attempt + 1))

    return await asyncio.gather(*(one(p) for p in paths))


def transcribe_all(paths, target_language="ru", realtime=True, concurrency=4):
    api_key = os.environ.get("SONIOX_API_KEY")
    if not api_key:
        raise SystemExit("Set SONIOX_API_KEY to run the soniox config")
    return asyncio.run(_transcribe_all([str(p) for p in paths], api_key, target_language,
                                       realtime, concurrency))
