package com.petroff.synchra.config

/**
 * Which engines a session uses. PAID: Soniox recognizes and translates, Claude summarizes.
 * FREE: recognition and translation run on the phone, nothing is sent and there are no summaries.
 */
enum class Mode { FREE, PAID }
