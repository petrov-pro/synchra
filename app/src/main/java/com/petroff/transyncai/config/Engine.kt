package com.petroff.transyncai.config

/** Who does recognition + translation. */
enum class Engine {
    /** On-device Nemotron recognition, Claude translation. */
    LOCAL,

    /** Soniox cloud stream: recognition and translation together. */
    SONIOX,
}
