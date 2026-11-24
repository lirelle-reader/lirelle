package app.tfdn.lirelle.feature.player.controller

import kotlin.time.Duration.Companion.seconds

/** How long a state of the player is still followed once nobody watches it: a screen turned, not left. */
internal val StopWatchingAfter = 5.seconds
