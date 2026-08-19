package com.spop.poverlay.sensor.interfaces

import android.content.Context
import android.os.IBinder
import com.spop.poverlay.sensor.tread.TreadCombinedSensor
import com.spop.poverlay.sensor.tread.getTreadBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import timber.log.Timber

/**
 * Peloton Tread sensor interface. Mirrors [PelotonBikeSensorInterfaceV1New]:
 * binds the affernet tread interface, drives a [TreadCombinedSensor], and exposes
 * the scaled flows. Unlike the bike, speed and incline are real measured values,
 * so [speed] is overridden with the real flow rather than being derived from power.
 */
class PelotonTreadSensorInterface(val context: Context) : SensorInterface, CoroutineScope {

    private val binder = MutableSharedFlow<IBinder>(replay = 1)

    init {
        launch(Dispatchers.IO) {
            try {
                val service = getTreadBinder(context)
                binder.emit(service)
                Timber.d("Tread service connected successfully")
            } catch (e: Exception) {
                Timber.w(e, "Failed to connect to tread service: ${e.message}")
                // Don't crash the app if the tread service isn't available;
                // the sensor flows simply stay empty.
            }
        }
    }

    override val coroutineContext: CoroutineContext
        get() = SupervisorJob()

    override val deviceType: DeviceType
        get() = DeviceType.Tread

    fun stop() {
        coroutineContext.cancelChildren()
    }

    private val combinedSensorState = binder.transformLatest { service ->
        val sensor = TreadCombinedSensor(service, context)
        sensor.start()
        emit(sensor)
        try {
            awaitCancellation()
        } finally {
            sensor.stop()
        }
    }.shareIn(this, SharingStarted.Lazily, 1)

    override val power: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.power }

    override val cadence: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.cadence }

    override val resistance: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.resistance }

    override val speed: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.speed }

    override val incline: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.incline }
}
