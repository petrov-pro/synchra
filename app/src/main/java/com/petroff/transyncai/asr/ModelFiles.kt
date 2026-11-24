package com.petroff.transyncai.asr

data class ModelPaths(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
)

// NVIDIA Nemotron streaming 0.6B: 18% WER vs 45% for the old 2023 zipformer on
// accented (Indian) English — see tools/asr-bench.
internal const val MODEL_BASE_URL =
    "https://huggingface.co/csukuangfj/sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14/resolve/main"

val MODEL_FILES = listOf(
    "encoder.int8.onnx",
    "decoder.int8.onnx",
    "joiner.int8.onnx",
    "tokens.txt",
)

/** Identifies the model set on disk; different models can share file names (tokens.txt). */
const val MODEL_ID = MODEL_BASE_URL

fun modelFileUrl(filename: String): String = "$MODEL_BASE_URL/$filename"
