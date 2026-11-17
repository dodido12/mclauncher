package com.example.mclauncher.display

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import com.example.mclauncher.instance.HdrSettings
import com.example.mclauncher.launch.NativeBridge
import java.util.function.Consumer

/**
 * Negociación HDR del lado Android:
 *  - Ventana en COLOR_MODE_HDR (API 26+)
 *  - SurfaceView con formato RGBA_1010102
 *  - Headroom HDR deseado con Window.setDesiredHdrHeadroom (API 35, Android 15+)
 *  - Seguimiento del ratio HDR/SDR del panel (API 34+) para ajustar el tone mapping en vivo
 *
 * El espacio de color Rec.2020 + PQ (ST 2084) lo fija el swapchain Vulkan nativo con
 * VK_COLOR_SPACE_HDR10_ST2084_EXT; el driver traduce eso al dataspace BT2020_PQ del Surface.
 */
class HdrDisplayManager(private val activity: Activity) {

    data class HdrCaps(
        val hdr10: Boolean,
        val hdr10Plus: Boolean,
        val dolbyVision: Boolean,
        val hlg: Boolean,
        val maxLuminance: Float,
        val maxAvgLuminance: Float,
        val minLuminance: Float,
        val currentHdrSdrRatio: Float
    ) { val anyHdr get() = hdr10 || hdr10Plus || hlg || dolbyVision }

    private val display: Display get() = activity.display!!
    private var ratioListener: Consumer<Display>? = null

    fun capabilities(): HdrCaps {
        val d = display
        @Suppress("DEPRECATION")
        val caps = d.hdrCapabilities
        val types: IntArray = if (Build.VERSION.SDK_INT >= 34) d.mode.supportedHdrTypes else caps?.supportedHdrTypes ?: IntArray(0)
        val ratio = if (Build.VERSION.SDK_INT >= 34 && d.isHdrSdrRatioAvailable) d.hdrSdrRatio else 1f
        return HdrCaps(
            hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in types,
            hdr10Plus = Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in types,
            dolbyVision = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types,
            hlg = Display.HdrCapabilities.HDR_TYPE_HLG in types,
            maxLuminance = caps?.desiredMaxLuminance ?: 0f,
            maxAvgLuminance = caps?.desiredMaxAverageLuminance ?: 0f,
            minLuminance = caps?.desiredMinLuminance ?: 0f,
            currentHdrSdrRatio = ratio
        )
    }

    /** Llamar en onCreate antes de que el SurfaceView cree su superficie. */
    fun enableHdr(settings: HdrSettings, holder: SurfaceHolder): Boolean {
        val caps = capabilities()
        Log.i(TAG, "Capacidades HDR: $caps")
        if (!settings.enabled || !caps.anyHdr) {
            holder.setFormat(PixelFormat.RGBA_8888)
            return false
        }
        val window = activity.window
        window.colorMode = ActivityInfo.COLOR_MODE_HDR
        holder.setFormat(PixelFormat.RGBA_1010102)

        // Pico efectivo = min(lo pedido, lo que el panel declara)
        val peak = if (caps.maxLuminance > 0) minOf(settings.maxLuminanceNits, caps.maxLuminance) else settings.maxLuminanceNits
        // Headroom pedido al sistema: pico / blanco de referencia (203 nits)
        val headroom = settings.copy(maxLuminanceNits = peak).systemHeadroom
        if (Build.VERSION.SDK_INT >= 35) setHeadroom35(headroom)

        NativeBridge.setHdrParams(
            enabled = true,
            mode = settings.mode.ordinal,
            paperWhiteNits = settings.paperWhiteNits,
            peakNits = peak,
            minNits = caps.minLuminance,
            maxFall = if (caps.maxAvgLuminance > 0) caps.maxAvgLuminance else peak * 0.4f
        )
        if (Build.VERSION.SDK_INT >= 34) listenRatio34(settings)
        return true
    }

    /** Calibración en vivo: vuelve a pedir el headroom para el nuevo pico. */
    fun updateHeadroom(settings: HdrSettings) {
        val caps = capabilities()
        val peak = if (caps.maxLuminance > 0) minOf(settings.maxLuminanceNits, caps.maxLuminance) else settings.maxLuminanceNits
        if (Build.VERSION.SDK_INT >= 35) setHeadroom35(settings.copy(maxLuminanceNits = peak).systemHeadroom)
    }

    /** Pico máximo que declara el panel (nits), o 1000 si no lo informa. */
    fun panelPeakNits(): Float = capabilities().maxLuminance.takeIf { it > 0 } ?: 1000f

    @RequiresApi(35)
    private fun setHeadroom35(headroom: Float) {
        activity.window.setDesiredHdrHeadroom(headroom)
        Log.i(TAG, "setDesiredHdrHeadroom($headroom)")
    }

    /**
     * El sistema puede reducir el headroom real (brillo ambiente, batería, temperatura).
     * Pasamos el ratio actual al presentador para que el tone mapping no recorte.
     */
    @RequiresApi(34)
    private fun listenRatio34(settings: HdrSettings) {
        val d = display
        if (!d.isHdrSdrRatioAvailable) return
        val l = Consumer<Display> { disp ->
            val effectivePeak = settings.paperWhiteNits * disp.hdrSdrRatio
            NativeBridge.updateHdrHeadroom(disp.hdrSdrRatio, effectivePeak)
        }
        d.registerHdrSdrRatioChangedListener(activity.mainExecutor, l)
        ratioListener = l
    }

    fun disableHdr() {
        if (Build.VERSION.SDK_INT >= 34) ratioListener?.let { display.unregisterHdrSdrRatioChangedListener(it) }
        ratioListener = null
        if (Build.VERSION.SDK_INT >= 35) activity.window.setDesiredHdrHeadroom(0f)
        activity.window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
        NativeBridge.setHdrParams(false, 0, 203f, 203f, 0f, 203f)
    }

    companion object { private const val TAG = "HdrDisplayManager" }
}
