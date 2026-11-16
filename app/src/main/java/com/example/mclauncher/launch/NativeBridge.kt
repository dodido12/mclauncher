package com.example.mclauncher.launch

import android.view.Surface

/** JNI hacia libmcl_native.so (app/src/main/cpp). */
object NativeBridge {
    init { System.loadLibrary("mcl_native") }

    /** Callback desde C++ (hilo de render) para FramePacer/PerformanceHint. */
    interface Callbacks {
        fun onRenderThreadStarted(tids: IntArray)
        fun onFrameWork(actualNs: Long)
        fun onGameExited(code: Int)
        fun setClipboard(text: String)
        fun getClipboard(): String
    }

    @JvmStatic external fun setCallbacks(cb: Callbacks)

    // --- superficie y renderizador ---
    /** renderer: 0 = GL4ES, 1 = Zink SDR, 2 = Zink + presentador HDR. */
    @JvmStatic external fun setSurface(surface: Surface?, renderer: Int, width: Int, height: Int)
    @JvmStatic external fun setHdrParams(enabled: Boolean, mode: Int, paperWhiteNits: Float, peakNits: Float, minNits: Float, maxFall: Float)
    @JvmStatic external fun updateHdrHeadroom(hdrSdrRatio: Float, effectivePeakNits: Float)
    @JvmStatic external fun setTargetFrameRate(hz: Float)
    @JvmStatic external fun onVsync(frameTimeNanos: Long, periodNanos: Long)

    // --- JVM ---
    /**
     * Arranca HotSpot dentro de este proceso (:game) en un hilo nativo con pila grande.
     * Bloquea hasta que el juego termina; luego el proceso se cierra con el código de salida.
     */
    @JvmStatic external fun launchJvm(
        javaHome: String,
        libjvmPath: String,
        preloadLibs: Array<String>,
        env: Array<String>,          // "CLAVE=valor"
        jvmArgs: Array<String>,
        mainClass: String,
        gameArgs: Array<String>,
        workDir: String
    ): Int
}
