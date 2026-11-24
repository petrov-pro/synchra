package com.petroff.synchra

import android.app.Application

/** Owns the one AppContainer for the whole process, so screens and rotations share it. */
class SynchraApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
