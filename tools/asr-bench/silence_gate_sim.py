"""Python twin of the app's SilenceGate.kt (keep the constants in sync).

Decides which 100 ms microphone chunks go to Soniox: speech, a 1.5 s tail after it so Soniox can
finish the phrase, a 0.5 s lead-in when speech resumes; nothing during longer silence (a keepalive
every 10 s holds the stream open instead).
"""
from collections import deque

import numpy as np

MIN_SPEECH_RMS = 0.004
SPEECH_OVER_NOISE = 3.0
INITIAL_NOISE_FLOOR = 0.003
MAX_NOISE_FLOOR = 0.05
NOISE_FLOOR_RISE = 1.02


class SilenceGate:
    def __init__(self, hangover_chunks=15, preroll_chunks=5, keepalive_chunks=100):
        self.hangover, self.keepalive_every = hangover_chunks, keepalive_chunks
        self.floor = INITIAL_NOISE_FLOOR
        self.since_speech = 10**9
        self.since_sent = 0
        self.spoke = False
        self.preroll = deque(maxlen=preroll_chunks)

    def process(self, chunk):
        """Returns (chunks_to_send, send_keepalive)."""
        level = float(np.sqrt(np.mean(np.square(chunk, dtype=np.float64)))) if len(chunk) else 0.0
        speech = level > max(MIN_SPEECH_RMS, self.floor * SPEECH_OVER_NOISE)
        if level < self.floor:
            self.floor = max(level, INITIAL_NOISE_FLOOR / 3)
        else:
            self.floor = min(self.floor * NOISE_FLOOR_RISE, min(level, MAX_NOISE_FLOOR))
        if speech:
            self.spoke, self.since_speech, self.since_sent = True, 0, 0
            out = list(self.preroll) + [chunk]
            self.preroll.clear()
            return out, False
        self.since_speech += 1
        if self.spoke and self.since_speech <= self.hangover:
            self.since_sent = 0
            return [chunk], False
        self.preroll.append(chunk)
        self.since_sent += 1
        if self.since_sent >= self.keepalive_every:
            self.since_sent = 0
            return [], True
        return [], False
