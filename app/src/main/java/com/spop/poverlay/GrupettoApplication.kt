package com.spop.poverlay

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import com.spop.poverlay.ble.BleServer
import com.spop.poverlay.sensor.SensorSelection
import com.spop.poverlay.sensor.interfaces.DeviceType
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
        // so build the selection synchronously (zero delay) and let
        // TreadAwareSensorInterface confirm/correct the Tread delegate off-thread once
        // the shared cached probe resolves. Non-Peloton => no probe, no wrapper, no delay.
        //
        // A physical machine never changes type, so we seed the default from the
        // persisted detection: on every launch after the first the default is already
        // correct, which makes deviceType (and thus the FTMS characteristic BleServer
        // builds at start()) race-free and synchronous. Only a brand-new install's very
        // first BLE session can default to Bike on a Tread; the probe then persists the
        // real type and the next launch/TX restart self-corrects.
        val persistedIsTread =
            ConfigurationRepository.readDetectedDeviceType(this) == DeviceType.Tread
        val default = when (selectSensor(IsRunningOnPeloton, persistedIsTread, IsG700CrossTrainer || IsBikePlus)) {
            SensorSelection.Tread -> PelotonTreadSensorInterface(this)
            SensorSelection.BikePlus -> PelotonBikePlusSensorInterface(this)
            SensorSelection.BikeV1 -> PelotonBikeSensorInterfaceV1New(this)
            SensorSelection.Dummy -> DummySensorInterface()
        }
        return if (IsRunningOnPeloton) TreadAwareSensorInterface(this, default) else default
    }
}
