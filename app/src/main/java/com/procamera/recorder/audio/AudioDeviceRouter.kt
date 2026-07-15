package com.procamera.recorder.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Picks and observes which physical device [NativeEngineBridge] should record from, per
 * §4.2's priority: USB Audio > 有線 Headset Mic > 内蔵 Mic. Every method here runs on a
 * normal JVM thread — like [NativeEngineBridge] itself, nothing here touches the audio
 * callback thread.
 *
 * This class only *selects and observes* devices; it does not open/close streams. The
 * caller ([com.procamera.recorder.pipeline.RecordingPipeline]) owns that, and must be the
 * one to fall back through [candidateInputDevices] in order — the native layer
 * (`OboeFullDuplexEngine::openInputStreamLocked`) only retries SharingMode/InputPreset
 * combinations on a *single* deviceId, it never tries a different device. Without that
 * fallback, a USB interface that happens to open as mono or at an unsupported rate would
 * fail `start()`/`reopenInputStream()` outright — regressing a device where the built-in
 * mic previously worked, which is the one outcome this feature must never cause.
 */
class AudioDeviceRouter(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var callback: AudioDeviceCallback? = null

    /** User-facing mic preference (§4.5 "現在の入力デバイス表示" + manual override). [Auto]
     * follows the USB > 有線 > 内蔵 priority; the others pin one kind to the front of
     * [candidateInputDevices] without giving up the fallback chain — see that method's doc. */
    enum class InputKind(val label: String) {
        Auto("自動 (USB > 有線 > 内蔵)"),
        Usb("USB Audio"),
        Wired("有線ヘッドセット"),
        BuiltIn("内蔵マイク"),
    }

    /**
     * Currently-connected input devices, ordered by [preferredKind] first (falling back to
     * the default USB > wired > built-in > other priority for everything that isn't the
     * preferred kind — [InputKind.Auto] just is that default order). Empty if the platform
     * reports no input devices at all (shouldn't normally happen; callers should treat that
     * as "let the OS choose" rather than failing outright).
     *
     * Note this is a *reordering* of the same device list, never a filter: even when the
     * user manually pins e.g. [InputKind.Usb], the built-in mic stays in the list (just
     * pushed to the back) so [com.procamera.recorder.pipeline.RecordingPipeline]'s
     * fallback-through-candidates loop still has something to land on if no USB device is
     * actually connected right now, rather than silently recording nothing.
     */
    fun candidateInputDevices(preferredKind: InputKind = InputKind.Auto): List<AudioDeviceInfo> {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.sortedBy { priorityOf(it.type) }
            ?: return emptyList()
        if (preferredKind == InputKind.Auto) return devices
        val (matching, rest) = devices.partition { matchesKind(it.type, preferredKind) }
        return matching + rest
    }

    /**
     * True if a wired/USB headphone-type output is currently connected. Gates live audio
     * monitoring (§4.5 "モニタリング再生"): enabling monitoring through the built-in
     * speaker while simultaneously recording from the mic would let the mic pick the
     * speaker output back up, causing a feedback howl. See
     * [com.procamera.recorder.pipeline.RecordingPipeline.setMonitoringEnabled]'s doc for
     * how this is enforced both at toggle-time and on hot-swap.
     *
     * Deliberately an *allowlist*, not "anything but the speaker": [AudioManager] can
     * report other always-present or ambiguous outputs (e.g. `TYPE_BUILTIN_SPEAKER_SAFE`
     * on API30+, or `TYPE_BLUETOOTH_A2DP`, which may be a room speaker rather than
     * headphones) that would make a denylist fail *open* — reporting safe when it isn't
     * — on devices this can't be verified against without hardware. A safety gate should
     * fail closed instead. Bluetooth is intentionally excluded, matching
     * [candidateInputDevices]'s USB/wired-only mic scope.
     */
    fun hasSafeMonitoringOutput(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return false
        return devices.any { it.type in SAFE_MONITORING_OUTPUT_TYPES }
    }

    /** Human-readable label for the UI (§4.5 "現在の入力デバイス表示"). */
    fun labelFor(device: AudioDeviceInfo?): String = when {
        device == null -> "既定"
        device.type in USB_INPUT_TYPES -> "USB Audio"
        device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有線ヘッドセット"
        device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内蔵マイク"
        else -> device.productName?.toString() ?: "不明な入力デバイス"
    }

    /**
     * Registers [onChanged] to fire whenever an input device is connected or disconnected
     * (e.g. a USB interface plugged/unplugged mid-recording). Only one callback may be
     * registered at a time; must be paired with [unregister] (see
     * `RecordingPipeline.stopAll()`). Delivered on the calling thread's Looper.
     */
    fun register(onChanged: () -> Unit) {
        unregister()
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onChanged()
        }
        callback = cb
        audioManager.registerAudioDeviceCallback(cb, null)
    }

    fun unregister() {
        callback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        callback = null
    }

    private fun priorityOf(type: Int): Int = when {
        type in USB_INPUT_TYPES -> 0
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> 1
        type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> 2
        else -> 3
    }

    private fun matchesKind(type: Int, kind: InputKind): Boolean = when (kind) {
        InputKind.Auto -> true
        InputKind.Usb -> type in USB_INPUT_TYPES
        InputKind.Wired -> type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        InputKind.BuiltIn -> type == AudioDeviceInfo.TYPE_BUILTIN_MIC
    }

    private companion object {
        val USB_INPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
        val SAFE_MONITORING_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
    }
}
