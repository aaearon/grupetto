package com.spop.poverlay.sensor.tread

import android.content.Context
import com.spop.poverlay.ConfigurationRepository
import com.spop.poverlay.sensor.interfaces.DeviceType
import com.spop.poverlay.sensor.interfaces.PelotonBikePlusSensorInterface
import com.spop.poverlay.sensor.interfaces.PelotonBikeSensorInterfaceV1New
import com.spop.poverlay.sensor.interfaces.PelotonTreadSensorInterface
import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A [SensorInterface] that starts on a [default] delegate (seeded from the persisted
 * detected type) and swaps to a [PelotonTreadSensorInterface] once the shared, cached
 * bind-probe ([isTreadCached]) resolves to a Tread — unless the default is already a
 * Tread, in which case there is nothing to correct. The resolved type is persisted so
 * the next launch seeds the correct default synchronously.
 *
 * This exists so the two startup call sites (Application → [com.spop.poverlay.ble.BleServer]
 * and OverlayService view models) can be built SYNCHRONOUSLY with zero delay: the
 * bind-probe is a ~3s suspend operation and running it inline on the main thread
 * (the old `runBlocking { detectIsTread(...) }`) risked an ANR. Here the default is
 * ready immediately and the probe runs off the main thread; the moment it resolves,
 * every exposed flow re-targets the new delegate live via [delegate] + flatMapLatest,
 * and [deviceType] reflects it.
 *
 * Only used when running on a Peloton (the only place a Tread can exist), so
 * [default] is always a stoppable Peloton interface.
 */
class TreadAwareSensorInterface(
    context: Context,
    private val default: SensorInterface,
) : SensorInterface {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val delegate = MutableStateFlow(default)

    init {
        scope.launch {
            val isTread = isTreadCached(context)
            // Persist the resolved type so the next launch seeds the correct default
            // synchronously (see GrupettoApplication.createSensorInterface). A physical
            // machine never changes type, so this makes the steady state race-free.
            ConfigurationRepository.persistDetectedDeviceType(
                context,
                if (isTread) DeviceType.Tread else DeviceType.Bike
            )
            // Swap only when the default seeded a non-Tread delegate; when the persisted
            // type already produced a Tread default there is nothing to correct.
            if (isTread && delegate.value.deviceType != DeviceType.Tread) {
                Timber.d("Tread detected; swapping sensor interface to PelotonTreadSensorInterface")
                val previous = delegate.value
                delegate.value = PelotonTreadSensorInterface(context.applicationContext)
                stopDelegate(previous)
            }
        }
    }

    override val deviceType: DeviceType
        get() = delegate.value.deviceType

    override val power: Flow<Float>
        get() = delegate.flatMapLatest { it.power }

    override val cadence: Flow<Float>
        get() = delegate.flatMapLatest { it.cadence }

    override val resistance: Flow<Float>
        get() = delegate.flatMapLatest { it.resistance }

    override val speed: Flow<Float>
        get() = delegate.flatMapLatest { it.speed }

    override val incline: Flow<Float>
        get() = delegate.flatMapLatest { it.incline }

    /** Stops whichever delegate is active and cancels the detection/swap coroutine. */
    fun stop() {
        scope.cancel()
        stopDelegate(delegate.value)
    }

    private fun stopDelegate(sensor: SensorInterface) {
        when (sensor) {
            is PelotonTreadSensorInterface -> sensor.stop()
            is PelotonBikeSensorInterfaceV1New -> sensor.stop()
            is PelotonBikePlusSensorInterface -> sensor.stop()
        }
    }
}
