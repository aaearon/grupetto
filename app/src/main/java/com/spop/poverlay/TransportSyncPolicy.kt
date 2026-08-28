package com.spop.poverlay

/**
 * The outbound transports as the sync policy needs to see them: what is desired is a preference,
 * what is running is this. `BleServer` implements it; the tests fake it.
 */
interface OutboundTransports {
    /** True while the GATT server registration is open. */
    val isBleServerRunning: Boolean

    /** True while the DIRCON listening socket is open, GATT-attached or DIRCON-only. */
    val isDirConServerRunning: Boolean

    fun start()
    fun stop()
    fun setDirConTransportEnabled(enabled: Boolean)
}

/**
 * Applies the transport preferences to the running transports.
 *
 * `OverlayService.syncBackgroundExecutionGuards()` and
 * `ConfigurationViewModel.syncOutboundTransports()` are thin adapters over this.
 */
fun applyTransportSync(
    transports: OutboundTransports,
    bleTxEnabled: Boolean,
    hasBluetoothPermissions: Boolean,
    dirConEnabled: Boolean
) {
    val plan = planTransportSync(
        bleTxEnabled = bleTxEnabled,
        hasBluetoothPermissions = hasBluetoothPermissions,
        dirConEnabled = dirConEnabled,
        bleServerRunning = transports.isBleServerRunning,
        dirConRunning = transports.isDirConServerRunning
    )

    if (plan.stopBleServer) transports.stop()
    if (plan.applyDirConTransport) transports.setDirConTransportEnabled(dirConEnabled)
    if (plan.startBleServer) transports.start()
}

data class TransportSyncPlan(
    val stopBleServer: Boolean,
    val applyDirConTransport: Boolean,
    val startBleServer: Boolean
) {
    val isNoOp: Boolean
        get() = !stopBleServer && !applyDirConTransport && !startBleServer
}

/**
 * Compares the desired transport state against what is actually running and returns only the
 * delta. This has to be idempotent: `OverlayService.onStartCommand` re-runs the sync on every
 * intent, including the ones that only mean "the overlay preference changed". A sync that
 * unconditionally stopped and restarted the transports closed the DIRCON listening socket and
 * opened a fresh one, dropping any connected bike computer mid-ride.
 *
 * `BleServer.stop()` tears down BLE and DIRCON together, so a genuine BLE change still bounces
 * DIRCON - that is inherent to the transports sharing one GATT registration, and it only happens
 * on a deliberate BLE toggle.
 */
fun planTransportSync(
    bleTxEnabled: Boolean,
    hasBluetoothPermissions: Boolean,
    dirConEnabled: Boolean,
    bleServerRunning: Boolean,
    dirConRunning: Boolean
): TransportSyncPlan {
    val bleWanted = bleTxEnabled && hasBluetoothPermissions
    val bleChanged = bleWanted != bleServerRunning

    // `setDirConTransportEnabled` is itself idempotent - it never restarts a DIRCON server that is
    // already up - but it also carries the preference down into BleServer, whose flag defaults to
    // enabled. So it must be re-applied whenever BLE is about to start or has just been stopped.
    val dirConChanged = dirConEnabled != dirConRunning

    return TransportSyncPlan(
        stopBleServer = bleServerRunning && !bleWanted,
        applyDirConTransport = bleChanged || dirConChanged,
        startBleServer = bleWanted && !bleServerRunning
    )
}
