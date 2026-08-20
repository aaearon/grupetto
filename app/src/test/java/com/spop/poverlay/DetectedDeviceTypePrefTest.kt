package com.spop.poverlay

import com.spop.poverlay.sensor.SensorSelection
import com.spop.poverlay.sensor.interfaces.DeviceType
import com.spop.poverlay.sensor.selectSensor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure tests for the [DeviceType] pref serialization and the synchronous startup
 * selection (see GrupettoApplication.createSensorInterface). Detection is now model-based
 * and synchronous (util.isTreadModel), so a Tread selects the Tread interface from the
 * first frame and BleServer.start() builds Treadmill Data (0x2ACD), not Indoor Bike Data
 * (0x2AD2), with no bind-probe race. The pref only carries the (still supported) manual
 * device-type serialization.
 */
class DetectedDeviceTypePrefTest {

    @Test
    fun `device type round-trips through pref serialization`() {
        for (type in DeviceType.values()) {
            assertEquals(
                type,
                ConfigurationRepository.deviceTypeFromPref(
                    ConfigurationRepository.deviceTypeToPref(type)
                )
            )
        }
    }

    @Test
    fun `missing or unknown pref defaults to bike`() {
        assertEquals(DeviceType.Bike, ConfigurationRepository.deviceTypeFromPref(null))
        assertEquals(DeviceType.Bike, ConfigurationRepository.deviceTypeFromPref(""))
        assertEquals(DeviceType.Bike, ConfigurationRepository.deviceTypeFromPref("garbage"))
    }

    @Test
    fun `persisted tread seeds tread selection synchronously`() {
        val persistedIsTread =
            ConfigurationRepository.deviceTypeFromPref(DeviceType.Tread.name) == DeviceType.Tread
        assertEquals(
            SensorSelection.Tread,
            selectSensor(
                isRunningOnPeloton = true,
                isTread = persistedIsTread,
                isBikePlusOrG700 = false
            )
        )
    }

    @Test
    fun `unpersisted type keeps existing bike selection`() {
        val persistedIsTread =
            ConfigurationRepository.deviceTypeFromPref(null) == DeviceType.Tread
        assertEquals(
            SensorSelection.BikeV1,
            selectSensor(
                isRunningOnPeloton = true,
                isTread = persistedIsTread,
                isBikePlusOrG700 = false
            )
        )
    }
}
