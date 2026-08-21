package com.spop.poverlay

/**
 * What the BLE transport is actually doing, as opposed to what the user asked for.
 * Reported by `BleServer`; never inferred from the preference.
 */
enum class BleTransportState {
    Stopped,
    Starting,
    Advertising,
    AdapterUnavailable,
    AdvertiserUnavailable,
    PermissionDenied,
    Failed
}

const val NothingToRunMessage =
    "Nothing would run. Turn on the overlay, BLE or DIRCON before starting."

data class ServiceModeDecision(
    val attachOverlayWindow: Boolean,
    val showOverlayPermissionPrompt: Boolean,
    val mayStartService: Boolean,
    val startButtonLabel: String,
    val blockedReason: String?,
    val transportStatus: String?
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
    bleTransportState: BleTransportState,
    dirConRunning: Boolean,
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

    val statusLines = buildList {
        if (bleTxEnabled) {
            add(
                when (bleTransportState) {
                    BleTransportState.Advertising -> "BLE transmission is active"
                    BleTransportState.Starting -> "BLE is starting"
                    BleTransportState.Stopped -> "BLE is enabled but not running"
                    BleTransportState.AdapterUnavailable ->
                        "BLE unavailable: no Bluetooth adapter"
                    BleTransportState.AdvertiserUnavailable ->
                        "BLE unavailable: this device cannot advertise"
                    BleTransportState.PermissionDenied ->
                        "BLE blocked: Bluetooth permission not granted"
                    BleTransportState.Failed -> "BLE failed to start"
                }
            )
        }
        if (dirConEnabled) {
            add(
                if (dirConRunning) {
                    "DIRCON WiFi is active"
                } else {
                    "DIRCON WiFi is enabled but not running"
                }
            )
        }
    }

    return ServiceModeDecision(
        attachOverlayWindow = attachOverlayWindow,
        showOverlayPermissionPrompt = showOverlay && !canDrawOverlays,
        mayStartService = mayStartService,
        startButtonLabel = startButtonLabel,
        blockedReason = if (mayStartService) null else NothingToRunMessage,
        transportStatus = statusLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    )
}
