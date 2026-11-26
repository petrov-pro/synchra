package com.petroff.synchra.asr

data class ModelPaths(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
)

/** NVIDIA Nemotron 3.5 ASR streaming 0.6B, multilingual, int8, 560 ms chunks, for sherpa-onnx. */
const val MODEL_ARCHIVE_URL =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
        "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11.tar.bz2"

/** Identifies the model on disk; models share file names (tokens.txt), so names alone can't tell. */
const val MODEL_ID = MODEL_ARCHIVE_URL

val MODEL_FILES = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
