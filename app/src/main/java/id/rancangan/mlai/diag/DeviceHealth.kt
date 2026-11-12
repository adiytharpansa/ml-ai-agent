package id.rancangan.mlai.diag

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Instant device-health reading from BatteryManager (Fase 0 experiment 5
 * measures heat/battery properly over a 15-minute session; this is the
 * single-moment reading used by the self-test panel).
 */
data class DeviceHealth(
    val batteryLevelPercent: Int?,
    /** Battery temperature in °C (BatteryManager reports tenths of a degree). */
    val temperatureCelsius: Float?,
    val charging: Boolean
)

object DeviceHealthReader {

    fun read(context: Context): DeviceHealth {
        val intent: Intent? = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        if (intent == null) return DeviceHealth(null, null, false)

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null

        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val tempC = if (tempTenths == Int.MIN_VALUE) null else tempTenths / 10f

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        return DeviceHealth(percent, tempC, charging)
    }
}
