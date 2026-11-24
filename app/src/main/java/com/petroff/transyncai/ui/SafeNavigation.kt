package com.petroff.transyncai.ui

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController

// A tap during a screen transition lands on a screen that is no longer the resumed one.
private val NavController.isIdle: Boolean
    get() = currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED

/** Navigates unless a transition is in progress, so a double tap opens the screen once. */
fun NavController.navigateOnce(route: String) {
    if (isIdle) navigate(route) { launchSingleTop = true }
}

/** Goes back unless a transition is in progress or this is the first screen (never a blank app). */
fun NavController.popOnce() {
    if (isIdle && previousBackStackEntry != null) popBackStack()
}
