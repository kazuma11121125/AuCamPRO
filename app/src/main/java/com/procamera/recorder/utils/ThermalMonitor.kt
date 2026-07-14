package com.procamera.recorder.utils

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Wraps `PowerManager`'s thermal APIs (§4.6). `addThermalStatusListener` is available from
 * API 29 (this app's minSdk); `getThermalHeadroom()` — a *predictive* signal, forecasting
 * throttling before [PowerManager.getCurrentThermalStatus] actually escalates — is only
 * available from API 30, so it is used only as a supplementary early-warning signal when
 * present, guarded by an explicit SDK_INT check (per the spec's explicit note that this is
 * a documented API-level mismatch, not an oversight).
 *
 * **スコープ(重要)**: このクラスは温度状態の監視とコールバック通知のみを行う。
 * 命令書§4.6が定める段階的品質低下(プレビュー解像度低下→プレビューfps低下→
 * ユーザー警告、**録画品質自体は自動変更しない**)のうち、実装済みなのは最終段の
 * 「ユーザーへ警告」のみ——`CameraControlViewModel`がこのクラスの
 * `onStatusChanged`を受けてUIバナーを表示する形で連携している
 * (`ui/viewmodel/CameraUiState.kt`の`thermalWarning`)。プレビュー解像度/fpsの
 * 動的引き下げは、現状の`CameraParams`/`CameraSessionController`にプレビュー
 * ストリームサイズを独立して変更する仕組みが無く、別途Surfaceサイズ管理を
 * 含む相応の規模の変更が必要なため未実装(follow-up)。
 */
class ThermalMonitor(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    /**
     * Starts observing thermal status changes. [onStatusChanged] is invoked immediately
     * with the current status, then again on every change, on the caller's choice of
     * executor (main thread here — this feeds UI state directly).
     */
    fun start(onStatusChanged: (Int) -> Unit) {
        if (listener != null) return
        val newListener = PowerManager.OnThermalStatusChangedListener { status ->
            Log.i(TAG, "Thermal status changed: ${describeStatus(status)}")
            onStatusChanged(status)
        }
        listener = newListener
        powerManager.addThermalStatusListener(newListener)
        onStatusChanged(currentStatus())
    }

    fun stop() {
        listener?.let { powerManager.removeThermalStatusListener(it) }
        listener = null
    }

    fun currentStatus(): Int = powerManager.currentThermalStatus

    /**
     * Forecasted thermal status [forecastSeconds] into the future, in [0f, 1f] where 1.0
     * corresponds to [PowerManager.THERMAL_STATUS_SEVERE] — see [PowerManager.getThermalHeadroom].
     * Returns null on API < 30 (see class doc) or if the platform can't estimate it (also
     * represented as NaN by the platform API; normalised to null here for a simpler caller
     * contract).
     */
    fun thermalHeadroom(forecastSeconds: Int = 10): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val headroom = powerManager.getThermalHeadroom(forecastSeconds)
        return headroom.takeUnless { it.isNaN() }
    }

    companion object {
        private const val TAG = "ThermalMonitor"

        fun describeStatus(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN($status)"
        }
    }
}
