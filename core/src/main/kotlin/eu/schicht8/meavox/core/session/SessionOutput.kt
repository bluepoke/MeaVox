// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.session

import eu.schicht8.meavox.core.model.OutputPath
import eu.schicht8.meavox.core.model.OutputRoute

/** The Session's output as the Track screen shows it (#9, ADR 13, ADR 16, ADR 23). */
data class SessionOutput(
    /** Null until the Session's output is open. */
    val route: OutputRoute?,
    val path: OutputPath,
    /** The reported round trip plus the route and path's Latency Correction; null while the microphone is closed. */
    val roundTripMs: Int?,
    /** A Latency Correction is stored for the route and path. */
    val corrected: Boolean,
    val monitoring: Boolean,
    /** 0..1. */
    val monitoringLevel: Float,
    /** Why Monitoring can't be switched on over this output, or null if it can. */
    val monitoringUnavailable: MonitoringUnavailable?,
) {
    /** Monitoring is on, and the singer hears themselves noticeably late (ADR 13: above [MONITORING_DELAY_WARNING_MS]). */
    val monitoringDelayed: Boolean get() = monitoring && roundTripMs != null && roundTripMs > MONITORING_DELAY_WARNING_MS

    /** The microphone may pick the Backing Audio up: the output may not be headphones. */
    val withoutHeadphones: Boolean get() = route != null && route !in HEADPHONES

    companion object {
        /** Provisional (ADR 13). */
        const val MONITORING_DELAY_WARNING_MS = 40
    }
}

/** Why Monitoring is unavailable over an output (ADR 4, ADR 13). */
enum class MonitoringUnavailable {
    /** The microphone would pick it up and howl. */
    Speaker,

    /** Too late to sing with, and the platform may switch to a headset profile. */
    Bluetooth,

    /** It may not be headphones. */
    UnknownOutput,
}

/**
 * The Latency Correction of the route and output path a Take in Review was recorded on, in [ms] (#9, ADR 23).
 * [checked] is false until it was set, or a Take on them was kept: Review asks to check the timing then.
 */
data class LatencyCorrection(val route: OutputRoute, val path: OutputPath, val ms: Int, val checked: Boolean)

/** Outputs the microphone can't pick up. */
internal val HEADPHONES = setOf(OutputRoute.Wired, OutputRoute.Usb, OutputRoute.Bluetooth)

/** Monitoring is for wired and USB headphones only (ADR 13). */
internal fun OutputRoute?.monitoringUnavailable(): MonitoringUnavailable? = when (this) {
    OutputRoute.Wired, OutputRoute.Usb -> null
    OutputRoute.Speaker -> MonitoringUnavailable.Speaker
    OutputRoute.Bluetooth -> MonitoringUnavailable.Bluetooth
    OutputRoute.Other, null -> MonitoringUnavailable.UnknownOutput
}
