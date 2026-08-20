package com.spop.poverlay.sensor.heartrate

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class HeartRateDevice(
    val address: String,
    val name: String?,
)

object HeartRateManager {
    private const val PrefsName = "heart_rate"
    private const val PrefSavedDevices = "hr_saved_devices"
    private const val PrefSelectedDevice = "hr_selected_device"
    private const val PrefNamePrefix = "hr_name_"
    private const val PrefZone12 = "hr_zone_12"
    private const val PrefZone23 = "hr_zone_23"
    private const val PrefZone34 = "hr_zone_34"
    private const val PrefZone45 = "hr_zone_45"
    private const val PrefMatchByName = "hr_match_by_name"

    private const val ReconnectDelayMs = 3_000L
    private const val AutoReconnectScanMs = 10_000L
    private const val StaleHeartRateTimeoutMs = 12_000L

    /**
     * Upper bound on how long a GATT connect may be considered in flight. Android's own
     * direct-connect timeout is ~30s; this is the backstop that stops a callback that
     * never arrives from wedging the in-flight guard forever.
     */
    internal const val ConnectTimeoutMs = 45_000L

    private val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate

    private val _connectedDevice = MutableStateFlow<HeartRateDevice?>(null)
    val connectedDevice: StateFlow<HeartRateDevice?> = _connectedDevice

    private val _discoveredDevices = MutableStateFlow<List<HeartRateDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<HeartRateDevice>> = _discoveredDevices

    private val _savedDevices = MutableStateFlow<List<HeartRateDevice>>(emptyList())
    val savedDevices: StateFlow<List<HeartRateDevice>> = _savedDevices

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _zone12 = MutableStateFlow<Int?>(null)
    private val _zone23 = MutableStateFlow<Int?>(null)
    private val _zone34 = MutableStateFlow<Int?>(null)
    private val _zone45 = MutableStateFlow<Int?>(null)
    val zone12: StateFlow<Int?> = _zone12
    val zone23: StateFlow<Int?> = _zone23
    val zone34: StateFlow<Int?> = _zone34
    val zone45: StateFlow<Int?> = _zone45
    private val _heartRateZones = MutableStateFlow<List<Int>?>(null)
    val heartRateZones: StateFlow<List<Int>?> = _heartRateZones

    private val _matchByName = MutableStateFlow(false)
    val matchByName: StateFlow<Boolean> = _matchByName

    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null

    @Volatile
    private var discoveryCallback: ScanCallback? = null

    @Volatile
    private var appContext: Context? = null

    private var prefs: SharedPreferences? = null
    private var selectedAddress: String? = null
    private val stopped = AtomicBoolean(true)

    /**
     * Exactly one GATT connect may be in flight at a time. Android registers a client
     * per `connectGatt` and only unregisters it on `close()` once a client interface has
     * been assigned, so a second overlapping connect permanently leaks a registry slot;
     * after ~32 leaks `connectGatt` silently returns null and HR never connects again.
     */
    private val connectInFlight = AtomicBoolean(false)

    @Volatile
    private var connectStartedAtMs: Long = 0L
    private var autoReconnectJob: kotlinx.coroutines.Job? = null
    private var manageSessionJob: kotlinx.coroutines.Job? = null
    private var isManaging = false
    @Volatile
    private var manualDisconnectRequested = false

    @Volatile
    private var lastHeartRateAtMs: Long = 0L

    @Volatile
    private var lastConnectedAtMs: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Test seam: every Android Bluetooth call goes through here. */
    @Volatile
    internal var transport: HeartRateBleTransport = DefaultHeartRateBleTransport

    /** Test seam for the connect watchdog clock. */
    @Volatile
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    @Synchronized
    fun start(context: Context) {
        try {
            if (appContext != null && bluetoothGatt != null) return
            appContext = context.applicationContext
            prefs = appContext?.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            loadHeartRateZones()
            _matchByName.value = prefs?.getBoolean(PrefMatchByName, false) ?: false
            stopped.set(false)
            loadSavedDevices()
            selectedAddress = prefs?.getString(PrefSelectedDevice, null)
            if (_savedDevices.value.isNotEmpty()) {
                startAutoReconnectScan()
            } else {
                selectedAddress?.let { connectToAddress(it) }
            }
        } catch (ex: Exception) {
            Timber.w(ex, "HeartRateManager start failed")
        }
    }

    @Synchronized
    fun stop() {
        stopped.set(true)
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        manageSessionJob?.cancel()
        manageSessionJob = null
        isManaging = false
        stopDiscovery()
        try { bluetoothGatt?.disconnect() } catch (_: Exception) {}
        try { bluetoothGatt?.close() } catch (_: Exception) {}
        bluetoothGatt = null
        clearConnectInFlight()
        _heartRate.value = null
        _connectedDevice.value = null
    }

    /** Test-only: returns the singleton to a pristine state between unit tests. */
    @Synchronized
    internal fun resetForTest() {
        stop()
        transport = DefaultHeartRateBleTransport
        nowMs = { System.currentTimeMillis() }
        appContext = null
        prefs = null
        selectedAddress = null
        manualDisconnectRequested = false
        isManaging = false
        discoveryCallback = null
        clearConnectInFlight()
        _isScanning.value = false
        _discoveredDevices.value = emptyList()
        _savedDevices.value = emptyList()
        _matchByName.value = false
        _zone12.value = null
        _zone23.value = null
        _zone34.value = null
        _zone45.value = null
        _heartRateZones.value = null
        lastHeartRateAtMs = 0L
        lastConnectedAtMs = 0L
    }

    private fun readIntOrNull(key: String): Int? {
        val sharedPrefs = prefs ?: return null
        return if (sharedPrefs.contains(key)) sharedPrefs.getInt(key, 0) else null
    }

    private fun loadHeartRateZones() {
        _zone12.value = readIntOrNull(PrefZone12)
        _zone23.value = readIntOrNull(PrefZone23)
        _zone34.value = readIntOrNull(PrefZone34)
        _zone45.value = readIntOrNull(PrefZone45)
        updateHeartRateZones()
    }

    fun setHeartRateZones(zone12: Int?, zone23: Int?, zone34: Int?, zone45: Int?) {
        prefs?.edit {
            if (zone12 != null) putInt(PrefZone12, zone12) else remove(PrefZone12)
            if (zone23 != null) putInt(PrefZone23, zone23) else remove(PrefZone23)
            if (zone34 != null) putInt(PrefZone34, zone34) else remove(PrefZone34)
            if (zone45 != null) putInt(PrefZone45, zone45) else remove(PrefZone45)
        }
        _zone12.value = zone12
        _zone23.value = zone23
        _zone34.value = zone34
        _zone45.value = zone45
        updateHeartRateZones()
    }

    fun setMatchByName(enabled: Boolean) {
        _matchByName.value = enabled
        prefs?.edit { putBoolean(PrefMatchByName, enabled) }
    }

    private fun updateHeartRateZones() {
        val z12 = _zone12.value
        val z23 = _zone23.value
        val z34 = _zone34.value
        val z45 = _zone45.value
        _heartRateZones.value = if (
            z12 != null && z23 != null && z34 != null && z45 != null &&
                z12 > 0 && z12 < z23 && z23 < z34 && z34 < z45
        ) {
            listOf(z12, z23, z34, z45)
        } else {
            null
        }
    }

    fun startDiscovery() {
        if (_isScanning.value) return

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val hasHrService = result.scanRecord?.serviceUuids?.any { it.uuid == HR_SERVICE } == true
                if (!hasHrService) return
                if (maybeAutoConnectSaved(device)) return
                addDiscoveredDevice(device)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }
        }

        discoveryCallback = callback
        try {
            if (!transport.startHrScan(HR_SERVICE, callback)) {
                discoveryCallback = null
                return
            }
            _isScanning.value = true
        } catch (sec: SecurityException) {
            Timber.w(sec, "Failed to start HR scan")
            discoveryCallback = null
        }
    }

    fun stopDiscovery() {
        val callback = discoveryCallback ?: return
        try { transport.stopScan(callback) } catch (_: Exception) {}
        discoveryCallback = null
        _isScanning.value = false
    }

    fun setManaging(active: Boolean) {
        isManaging = active
        if (!active) {
            manageSessionJob?.cancel()
            manageSessionJob = null
            return
        }
        manageSessionJob?.cancel()
        manageSessionJob = scope.launch {
            while (isManaging && !stopped.get()) {
                val connected = _connectedDevice.value
                if (connected != null) {
                    val lastSignalAtMs = maxOf(lastHeartRateAtMs, lastConnectedAtMs)
                    if (lastSignalAtMs > 0) {
                        val ageMs = System.currentTimeMillis() - lastSignalAtMs
                        if (ageMs > StaleHeartRateTimeoutMs) {
                            try { bluetoothGatt?.disconnect() } catch (_: Exception) {}
                            _connectedDevice.value = null
                            _heartRate.value = null
                            lastConnectedAtMs = 0L
                            lastHeartRateAtMs = 0L
                        }
                    }
                }
                delay(1_000L)
            }
        }
    }

    @Synchronized
    fun connectTo(device: HeartRateDevice) {
        manualDisconnectRequested = false
        // Stop scanning FIRST: a scan result delivered mid-connect used to fire a second,
        // overlapping connectGatt for the same device.
        stopDiscovery()
        saveDevice(device)
        selectedAddress = device.address
        connectToAddress(device.address)
    }

    fun disconnectCurrent() {
        manualDisconnectRequested = true
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        stopDiscovery()
        selectedAddress = null
        _heartRate.value = null
        _connectedDevice.value = null
        lastConnectedAtMs = 0L
        lastHeartRateAtMs = 0L
        clearConnectInFlight()
        try { bluetoothGatt?.disconnect() } catch (_: Exception) {}
    }

    fun forgetDevice(address: String) {
        val p = prefs ?: return
        val saved = p.getStringSet(PrefSavedDevices, emptySet()).orEmpty().toMutableSet()
        saved.remove(address)
        p.edit {
            putStringSet(PrefSavedDevices, saved)
            remove(PrefNamePrefix + address)
            if (selectedAddress == address) remove(PrefSelectedDevice)
        }
        if (selectedAddress == address) {
            selectedAddress = null
            try { bluetoothGatt?.disconnect() } catch (_: Exception) {}
        }
        loadSavedDevices()
        pruneDiscovered()
    }

    private fun saveDevice(device: HeartRateDevice) {
        val p = prefs ?: return
        val saved = p.getStringSet(PrefSavedDevices, emptySet()).orEmpty().toMutableSet()
        saved.add(device.address)
        p.edit {
            putStringSet(PrefSavedDevices, saved)
            putString(PrefSelectedDevice, device.address)
            if (!device.name.isNullOrBlank()) putString(PrefNamePrefix + device.address, device.name)
        }
        loadSavedDevices()
        pruneDiscovered()
    }

    private fun loadSavedDevices() {
        val p = prefs ?: return
        val saved = p.getStringSet(PrefSavedDevices, emptySet()).orEmpty()
        _savedDevices.value = saved.map { HeartRateDevice(it, p.getString(PrefNamePrefix + it, null)) }
            .sortedBy { it.name ?: it.address }
    }

    private fun addDiscoveredDevice(device: BluetoothDevice) {
        val address = device.address ?: return
        if (address == selectedAddress) return
        if (_savedDevices.value.any { it.address == address }) return
        val current = _discoveredDevices.value.toMutableList()
        if (current.none { it.address == address }) {
            current.add(HeartRateDevice(address, device.name))
            _discoveredDevices.value = current.sortedBy { it.name ?: it.address }
        }
    }

    private fun maybeAutoConnectSaved(device: BluetoothDevice): Boolean {
        if (manualDisconnectRequested) return false
        if (_connectedDevice.value != null) return false
        // A manual connect (or an earlier auto-connect) is already in flight; a second
        // one would leak a GATT client registration.
        if (isConnectInFlight()) return false
        val address = device.address ?: return false

        // Exact MAC match
        if (_savedDevices.value.any { it.address == address }) {
            selectedAddress = address
            stopDiscovery()
            connectToAddress(address)
            return true
        }

        // Name-only match (handles randomized/changed MAC addresses)
        if (_matchByName.value) {
            val deviceName = device.name?.takeIf { it.isNotBlank() } ?: return false
            val matched = _savedDevices.value.firstOrNull {
                !it.name.isNullOrBlank() && it.name == deviceName
            } ?: return false
            updateSavedDeviceAddress(oldAddress = matched.address, newAddress = address, name = deviceName)
            selectedAddress = address
            stopDiscovery()
            connectToAddress(address)
            return true
        }

        return false
    }

    private fun updateSavedDeviceAddress(oldAddress: String, newAddress: String, name: String) {
        val p = prefs ?: return
        val saved = p.getStringSet(PrefSavedDevices, emptySet()).orEmpty().toMutableSet()
        saved.remove(oldAddress)
        saved.add(newAddress)
        val wasSelected = p.getString(PrefSelectedDevice, null) == oldAddress
        p.edit {
            putStringSet(PrefSavedDevices, saved)
            remove(PrefNamePrefix + oldAddress)
            putString(PrefNamePrefix + newAddress, name)
            if (wasSelected) putString(PrefSelectedDevice, newAddress)
        }
        Timber.i("HR device name-matched '%s': updated address %s → %s", name, oldAddress, newAddress)
        loadSavedDevices()
        pruneDiscovered()
    }

    private fun pruneDiscovered() {
        val savedAddresses = _savedDevices.value.map { it.address }.toSet()
        _discoveredDevices.value = _discoveredDevices.value.filter {
            it.address != selectedAddress && !savedAddresses.contains(it.address)
        }
    }

    private fun isConnectInFlight(): Boolean {
        if (!connectInFlight.get()) return false
        if (nowMs() - connectStartedAtMs <= ConnectTimeoutMs) return true
        Timber.w("HR connect to %s timed out with no callback; releasing guard", selectedAddress)
        clearConnectInFlight()
        return false
    }

    private fun clearConnectInFlight() {
        connectInFlight.set(false)
        connectStartedAtMs = 0L
    }

    @Synchronized
    private fun connectToAddress(address: String) {
        val context = appContext ?: return
        if (isConnectInFlight()) {
            Timber.i("HR connect to %s skipped: a connect is already in flight", address)
            return
        }
        val device = try {
            transport.getRemoteDevice(address)
        } catch (ex: IllegalArgumentException) {
            Timber.w(ex, "Invalid HR device address: %s", address)
            null
        } ?: return
        connectInFlight.set(true)
        connectStartedAtMs = nowMs()
        connect(context, device)
    }

    private fun connect(context: Context, device: BluetoothDevice) {
        // Separate try blocks: a throwing disconnect() must not skip close(), which is
        // what actually releases the GATT client registration.
        val previous = bluetoothGatt
        bluetoothGatt = null
        if (previous != null) {
            try { previous.disconnect() } catch (ex: Exception) { Timber.w(ex, "HR gatt disconnect failed") }
            try { previous.close() } catch (ex: Exception) { Timber.w(ex, "HR gatt close failed") }
        }
        val gatt = transport.connectGatt(context, device, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Timber.i(
                    "HR %s onConnectionStateChange status=%d newState=%d",
                    device.address, status, newState,
                )
                clearConnectInFlight()
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    try { gatt.close() } catch (_: Exception) {}
                    if (bluetoothGatt === gatt) bluetoothGatt = null
                    if (!manualDisconnectRequested) {
                        scheduleReconnect()
                    }
                    return
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        lastConnectedAtMs = System.currentTimeMillis()
                        val name = device.name ?: prefs?.getString(PrefNamePrefix + device.address, null)
                        _connectedDevice.value = HeartRateDevice(device.address, name)
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        try { gatt.close() } catch (_: Exception) {}
                        if (bluetoothGatt === gatt) bluetoothGatt = null
                        _heartRate.value = null
                        _connectedDevice.value = null
                        lastConnectedAtMs = 0L
                        if (!manualDisconnectRequested) {
                            scheduleReconnect()
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) return
                val service = gatt.getService(HR_SERVICE) ?: return
                val characteristic = service.getCharacteristic(HR_MEASUREMENT) ?: return
                if (!gatt.setCharacteristicNotification(characteristic, true)) return
                val desc = characteristic.getDescriptor(CCC_UUID) ?: return
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                try { gatt.writeDescriptor(desc) } catch (sec: SecurityException) {
                    Timber.w(sec, "Failed to write HR CCC")
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid != HR_MEASUREMENT) return
                val data = characteristic.value ?: return
                if (data.size < 2) return
                val flags = data[0].toInt()
                val format16 = flags and 0x01 != 0
                val bpm = if (format16) {
                    if (data.size >= 3) ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF) else null
                } else {
                    data[1].toInt() and 0xFF
                }
                if (bpm != null && bpm > 0) {
                    _heartRate.value = bpm
                    lastHeartRateAtMs = System.currentTimeMillis()
                }
            }
        })
        if (gatt == null) {
            // Android returns null once the per-process GATT client registry is full.
            Timber.e("HR connectGatt returned null for %s; GATT client registry likely exhausted", device.address)
            clearConnectInFlight()
            return
        }
        bluetoothGatt = gatt
    }

    private fun scheduleReconnect() {
        if (stopped.get()) return
        if (manualDisconnectRequested) return
        if (_savedDevices.value.isNotEmpty()) {
            startAutoReconnectScan()
            return
        }
        val target = selectedAddress ?: return
        scope.launch {
            delay(ReconnectDelayMs)
            if (!stopped.get()) connectToAddress(target)
        }
    }

    private fun startAutoReconnectScan() {
        if (stopped.get()) return
        if (_isScanning.value) return
        autoReconnectJob?.cancel()
        autoReconnectJob = scope.launch {
            startDiscovery()
            delay(AutoReconnectScanMs)
            if (_isScanning.value && _connectedDevice.value == null) {
                stopDiscovery()
            }
        }
    }
}
