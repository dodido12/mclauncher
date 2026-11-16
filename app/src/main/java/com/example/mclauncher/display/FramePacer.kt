package com.example.mclauncher.display

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import com.example.mclauncher.instance.DisplaySettings
import com.example.mclauncher.launch.NativeBridge
import kotlin.math.roundToInt

/**
 * Sincroniza el juego con el panel:
 *  - Surface.setFrameRate fija la tasa del panel (evita que el sistema baje a 60 Hz o cambie de modo).
 *  - Choreographer entrega el vsync al hilo de render nativo (timestamp + deadline).
 *  - PerformanceHintManager informa al governor de CPU la duración real de cada frame
 *    para que suba/baje frecuencias de los núcleos del hilo de render a tiempo.
 *  - renderScale < 1 usa SurfaceHolder.setFixedSize: la GPU renderiza menos píxeles y el
 *    escalador de hardware de la pantalla (HWC/DPU, o chip de visualización dedicado si el
 *    fabricante lo engancha a esa ruta) hace el reescalado sin costo de GPU.
 *
 * Nota: MEMC / Super Resolution de chips de pantalla tipo "display co-processor" de Xiaomi
 * no tienen API pública; se activan desde Game Turbo/HyperOS por app. Lo que sí podemos hacer
 * es entregarles un flujo de frames estable, de tasa fija y con latencia baja.
 */
class FramePacer(private val context: Context, private val surfaceView: SurfaceView, private val settings: DisplaySettings) :
    Choreographer.FrameCallback {

    private var hintSession: PerformanceHintManager.Session? = null
    private var running = false
    private var refreshHz = 60f

    fun onSurfaceCreated(holder: SurfaceHolder) {
        val display = surfaceView.display ?: return
        // Elegimos el modo de mayor refresco disponible a la resolución actual.
        val modes = display.supportedModes
        val cur = display.mode
        val best = modes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: cur
        refreshHz = if (settings.targetFps > 0) settings.targetFps.toFloat() else best.refreshRate

        if (settings.renderScale in 0.3f..0.99f) {
            val w = (surfaceView.width * settings.renderScale).roundToInt().coerceAtLeast(320)
            val h = (surfaceView.height * settings.renderScale).roundToInt().coerceAtLeast(180)
            holder.setFixedSize(w, h)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            holder.surface.setFrameRate(refreshHz, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
        } else if (Build.VERSION.SDK_INT >= 30) {
            holder.surface.setFrameRate(refreshHz, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
        NativeBridge.setTargetFrameRate(refreshHz)
        Log.i(TAG, "Panel ${best.refreshRate} Hz, objetivo $refreshHz FPS, escala ${settings.renderScale}")
    }

    /** El hilo de render nativo nos pasa su TID cuando arranca. */
    fun attachRenderThreads(tids: IntArray) {
        if (Build.VERSION.SDK_INT >= 31) createHintSession(tids)
    }

    @RequiresApi(31)
    private fun createHintSession(tids: IntArray) {
        val phm = context.getSystemService(PerformanceHintManager::class.java) ?: return
        val targetNs = (1_000_000_000.0 / refreshHz).toLong()
        hintSession = phm.createHintSession(tids, targetNs)?.also { s ->
            if (Build.VERSION.SDK_INT >= 35) s.setPreferPowerEfficiency(false)
        }
        Log.i(TAG, "PerformanceHint session=${hintSession != null} target=${targetNs}ns")
    }

    /** Llamado desde JNI al final de cada frame con el tiempo de CPU de trabajo. */
    fun reportFrameWork(actualNs: Long) {
        if (Build.VERSION.SDK_INT >= 31) hintSession?.reportActualWorkDuration(actualNs)
    }

    fun start() { if (!running) { running = true; Choreographer.getInstance().postFrameCallback(this) } }
    fun stop() { running = false; Choreographer.getInstance().removeFrameCallback(this) }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        // Entregamos el vsync al nativo; ahí se calcula el deadline de presentación
        // (VK_GOOGLE_display_timing si está disponible) y el ritmo del hilo de juego.
        NativeBridge.onVsync(frameTimeNanos, (1_000_000_000.0 / refreshHz).toLong())
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun release() {
        stop()
        if (Build.VERSION.SDK_INT >= 31) hintSession?.close()
        hintSession = null
    }

    companion object { private const val TAG = "FramePacer" }
}
