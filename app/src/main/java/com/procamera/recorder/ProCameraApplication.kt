package com.procamera.recorder

import android.app.Application
import android.util.Log
import com.procamera.recorder.pipeline.RecordingPipeline

/**
 * Composition root. Dependency wiring uses manual DI via [AppContainer] rather than
 * Hilt/Dagger — see docs/ARCHITECTURE.md §DI方式の明確化 for the rationale
 * (single :app module, moderate graph size, avoiding KSP/KAPT build overhead on top of
 * an already NDK-heavy build).
 *
 * Also installs a process-wide [Thread.UncaughtExceptionHandler] (§4.6 crash safety) that
 * makes a best-effort attempt to finalize the currently-open recording segment (see
 * [RecordingPipeline.emergencyFinalizeCurrentSegment]'s doc for exactly what this does and
 * doesn't cover) before re-throwing to the platform's default handler — the crash still
 * happens and is still reported normally, this only tries to save the in-progress
 * segment first.
 */
class ProCameraApplication : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * Set by [com.procamera.recorder.ui.viewmodel.CameraControlViewModel] to the single
     * [RecordingPipeline] instance it owns, so the crash handler below can reach it
     * without a DI graph. `emergencyFinalizeCurrentSegment()` is already a no-op when
     * nothing is being recorded, so this is left set for the ViewModel's whole lifetime
     * rather than cleared between recordings.
     */
    var activeRecordingPipeline: RecordingPipeline? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        installCrashSafeMuxerFinalizer()
    }

    private fun installCrashSafeMuxerFinalizer() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Uncaught exception — attempting emergency muxer finalize", throwable)
                activeRecordingPipeline?.emergencyFinalizeCurrentSegment()
            } catch (e: Throwable) {
                Log.e(TAG, "Crash handler itself failed", e)
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private companion object {
        const val TAG = "ProCameraApplication"
    }
}
