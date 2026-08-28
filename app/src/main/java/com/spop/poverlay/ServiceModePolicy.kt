package com.spop.poverlay

const val NothingToRunMessage =
    "Nothing would run. Turn on the overlay, BLE or DIRCON before starting."

data class ServiceModeDecision(
    val attachOverlayWindow: Boolean,
    val showOverlayPermissionPrompt: Boolean,
    val mayStartService: Boolean,
    val startButtonLabel: String,
    val blockedReason: String?
)

/**
 * The single source of truth for BLE-only mode. `OverlayService` and `ConfigurationPage` are
 * thin adapters over this; keep the branching here so it stays testable on the JVM.
 */
fun decideServiceMode(
    showOverlay: Boolean,
    bleTxEnabled: Boolean,
    dirConEnabled: Boolean,
    canDrawOverlays: Boolean,
    isServiceRunning: Boolean
): ServiceModeDecision {
    val attachOverlayWindow = showOverlay && canDrawOverlays
    val mayStartService = attachOverlayWindow || bleTxEnabled || dirConEnabled

    val startButtonLabel = when {
        attachOverlayWindow && isServiceRunning -> "Restart Overlay"
        attachOverlayWindow -> "Start Overlay"
        isServiceRunning -> "Restart Broadcasting"
        else -> "Start Broadcasting"
    }

    return ServiceModeDecision(
        attachOverlayWindow = attachOverlayWindow,
        showOverlayPermissionPrompt = showOverlay && !canDrawOverlays,
        mayStartService = mayStartService,
        startButtonLabel = startButtonLabel,
        blockedReason = if (mayStartService) null else NothingToRunMessage
    )
}
