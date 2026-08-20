package com.spop.poverlay.sensor

/** The sensor interface implementation chosen for the current device. */
enum class SensorSelection { Tread, BikePlus, BikeV1, Dummy }

/**
 * Pure selection logic for which [com.spop.poverlay.sensor.interfaces.SensorInterface]
 * to construct. Extracted so it can be unit-tested with an injected [isTread] probe
 * result without touching Android binding.
 *
 * Tread is checked FIRST (before the Bike+/V1 branch) because the shared tablet
 * model would otherwise be misclassified (research doc section 8). `isTread` is only
 * meaningful when running on a Peloton.
 */
fun selectSensor(
    isRunningOnPeloton: Boolean,
    isTread: Boolean,
    isBikePlusOrG700: Boolean
): SensorSelection {
    if (!isRunningOnPeloton) return SensorSelection.Dummy
    if (isTread) return SensorSelection.Tread
    return if (isBikePlusOrG700) SensorSelection.BikePlus else SensorSelection.BikeV1
}
