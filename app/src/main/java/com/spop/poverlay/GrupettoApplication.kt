package com.spop.poverlay

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import com.spop.poverlay.ble.BleServer
import com.spop.poverlay.sensor.SensorSelection
import com.spop.poverlay.sensor.interfaces.DummySensorInterface
import com.spop.poverlay.sensor.interfaces.PelotonBikePlusSensorInterface
import com.spop.poverlay.sensor.interfaces.PelotonBikeSensorInterfaceV1New
import com.spop.poverlay.sensor.interfaces.PelotonTreadSensorInterface
import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.sensor.selectSensor
import com.spop.poverlay.sensor.tread.TreadAwareSensorInterface
import com.spop.poverlay.util.IsBikePlus
import com.spop.poverlay.util.IsG700CrossTrainer
import com.spop.poverlay.util.IsRunningOnPeloton
import timber.log.Timber

class GrupettoApplication : Application() {
    lateinit var bleServer: BleServer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val sensorInterface = createSensorInterface()
        bleServer = BleServer(this, bluetoothManager, sensorInterface)
    }

    private fun createSensorInterface(): SensorInterface {
        // NEVER bind-probe on the main thread: it is a ~3s suspend op and blocking
        // Application.onCreate on it risks an ANR. bleServer must be assigned before
        // onCreate returns (MainActivity → ConfigurationViewModel reads it at launch),
        // so build the non-Tread selection synchronously (zero delay) and let
        // TreadAwareSensorInterface swap in the Tread delegate off-thread once the
        // shared cached probe resolves. Non-Peloton => no probe, no wrapper, no delay.
        val default = when (selectSensor(IsRunningOnPeloton, false, IsG700CrossTrainer || IsBikePlus)) {
            SensorSelection.Tread -> PelotonTreadSensorInterface(this)
            SensorSelection.BikePlus -> PelotonBikePlusSensorInterface(this)
            SensorSelection.BikeV1 -> PelotonBikeSensorInterfaceV1New(this)
            SensorSelection.Dummy -> DummySensorInterface()
        }
        return if (IsRunningOnPeloton) TreadAwareSensorInterface(this, default) else default
    }
}
