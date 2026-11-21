package com.proviewer.app.bench

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device run-condition capture for the benchmark report (Task 9). Task 10 records this once per
 * run and writes it into the benchmark JSON.
 */
data class Conditions(
    val model: String,
    val manufacturer: String,
    val sdkInt: Int,
    val release: String,
    val isEmulator: Boolean,
    val refreshHz: Float,
    val maxRefreshHz: Float,
    val thermalStatus: Int,
    val batteryPct: Int,
    val charging: Boolean,
)

/**
 * Capture the device conditions of [activity]. `isEmulator` is true when `Build.HARDWARE` names
 * the ranchu/goldfish emulator boards or `Build.FINGERPRINT` contains `generic`.
 */
fun captureConditions(activity: Activity): Conditions {
    val display = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val default = display.getDisplay(Display.DEFAULT_DISPLAY)
    val battery = activity.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val power = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
    return Conditions(
        model = Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        sdkInt = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE,
        isEmulator = isEmulator(),
        refreshHz = default.refreshRate,
        maxRefreshHz = default.supportedModes.maxOf { it.refreshRate },
        thermalStatus = power.currentThermalStatus,
        batteryPct = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
        charging = battery.isCharging,
    )
}

private fun isEmulator(): Boolean =
    Build.HARDWARE.contains("ranchu") ||
        Build.HARDWARE.contains("goldfish") ||
        Build.FINGERPRINT.contains("generic")

/**
 * Thermal gate for starting and continuing a benchmark run (Task 10). A run may only start below
 * [PowerManager.THERMAL_STATUS_MODERATE]; [tripped] flips to true as soon as the device reaches it
 * so an in-flight run can abort. Implements the runner's [ThermalGate] seam so tests can inject a
 * fake instead of the device's real thermal state.
 */
class ThermalGuard(context: Context) : ThermalGate {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val _tripped = MutableStateFlow(false)

    /** Emits true once the thermal status reaches `THERMAL_STATUS_MODERATE` or worse. */
    override val tripped: StateFlow<Boolean> = _tripped.asStateFlow()

    private val statusListener = PowerManager.OnThermalStatusChangedListener { status ->
        _tripped.value = status >= PowerManager.THERMAL_STATUS_MODERATE
    }

    init {
        powerManager.addThermalStatusListener(statusListener)
    }

    /** True when the device is cool enough to start a benchmark run. */
    override fun canStart(): Boolean =
        powerManager.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE
}
