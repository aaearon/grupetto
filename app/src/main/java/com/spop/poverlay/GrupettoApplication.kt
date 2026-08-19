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
import com.spop.poverlay.sensor.tread.detectIsTread
import com.spop.poverlay.util.IsBikePlus
import com.spop.poverlay.util.IsG700CrossTrainer
import com.spop.poverlay.util.IsRunningOnPeloton
import kotlinx.coroutines.runBlocking
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
        // Detection is a suspend bind-probe. It only runs on a Peloton (the probe
        // target doesn't exist elsewhere), so non-Peloton startup incurs no delay.
        // runBlocking bounds the wait to the detection timeout and keeps bleServer
        // construction synchronous (its consumers assume it is set after onCreate).
        val isTread = IsRunningOnPeloton && runBlocking { detectIsTread(this@GrupettoApplication) }
        return when (selectSensor(IsRunningOnPeloton, isTread, IsG700CrossTrainer || IsBikePlus)) {
            SensorSelection.Tread -> PelotonTreadSensorInterface(this)
            SensorSelection.BikePlus -> PelotonBikePlusSensorInterface(this)
            SensorSelection.BikeV1 -> PelotonBikeSensorInterfaceV1New(this)
            SensorSelection.Dummy -> DummySensorInterface()
        }
    }
}
