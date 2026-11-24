package com.petroff.transyncai

import android.app.Application

/** Owns the one AppContainer for the whole process, so screens and rotations share it. */
class TransyncApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
