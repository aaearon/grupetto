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
import com.spop.poverlay.util.IsBikePlus
import com.spop.poverlay.util.IsTread
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
        // Detection is synchronous and model-based (util.IsTread): the Tread reports a
        // distinct model string (PLTN-TTR01), so deviceType — and thus the FTMS
        // characteristic BleServer builds at start() — is correct and race-free from the
        // first launch, with zero delay in Application.onCreate (no bind-probe, no ANR
        // risk). A bind-probe was unreliable: AffernetService returns a non-null
        // ITreadInterface binder on a bike too, so it misdetected bikes as Treads.
        return when (selectSensor(IsRunningOnPeloton, IsTread, IsG700CrossTrainer || IsBikePlus)) {
            SensorSelection.Tread -> PelotonTreadSensorInterface(this)
            SensorSelection.BikePlus -> PelotonBikePlusSensorInterface(this)
            SensorSelection.BikeV1 -> PelotonBikeSensorInterfaceV1New(this)
            SensorSelection.Dummy -> DummySensorInterface()
        }
    }
}
