package com.example.mclauncher.instance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class LoaderType {
    @SerialName("vanilla") VANILLA,
    @SerialName("fabric") FABRIC,
    @SerialName("forge") FORGE,
    @SerialName("quilt") QUILT,
    @SerialName("neoforge") NEOFORGE
}

@Serializable
enum class JavaRuntime(val major: Int) {
    @SerialName("8") JAVA_8(8),
    @SerialName("17") JAVA_17(17),
    @SerialName("21") JAVA_21(21),
    @SerialName("25") JAVA_25(25);

    companion object {
        /** Elige el runtime mínimo que satisface `javaVersion.majorVersion` del version.json. */
        fun forRequiredMajor(required: Int): JavaRuntime =
            entries.firstOrNull { it.major >= required } ?: JAVA_25
    }
}

@Serializable
enum class RendererType {
    /** OpenGL 2.1 → GLES 3 vía gl4es. Máxima compatibilidad, solo SDR 8 bits. */
    @SerialName("gl4es") GL4ES,
    /** Mesa Zink (OpenGL 4.6 sobre Vulkan), salida SDR. */
    @SerialName("zink") ZINK_VULKAN,
    /** Zink + presentador Vulkan propio con swapchain 10 bits PQ/Rec.2020. */
    @SerialName("zink_hdr") ZINK_VULKAN_HDR
}

@Serializable
enum class HdrMode {
    /** El juego renderiza SDR; el presentador hace expansión inversa (SDR→HDR) controlada. */
    @SerialName("inverse_tonemap") INVERSE_TONEMAP,
    /** El shaderpack escribe luminancia lineal escena-referida (FP16); el presentador codifica PQ. */
    @SerialName("shader_linear") SHADER_LINEAR_PASSTHROUGH
}

@Serializable
data class HdrSettings(
    val enabled: Boolean = false,
    val mode: HdrMode = HdrMode.INVERSE_TONEMAP,
    /** Blanco de referencia para UI/SDR (BT.2408 recomienda 203 nits). */
    val paperWhiteNits: Float = 203f,
    /** Pico objetivo; se recorta al máximo que reporte el panel. */
    val maxLuminanceNits: Float = 1000f
)

@Serializable
data class DisplaySettings(
    /** 0.5–1.0: resolución interna. <1.0 deja que el escalador de hardware/HWC reescale. */
    val renderScale: Float = 1.0f,
    /** 0 = usar la tasa nativa del panel. */
    val targetFps: Int = 0,
    val lowLatency: Boolean = true
)

@Serializable
data class InstanceConfig(
    val id: String,
    val name: String,
    val minecraftVersion: String,
    val loader: LoaderType = LoaderType.VANILLA,
    val loaderVersion: String? = null,
    /** null = automático según version.json. */
    val javaVersion: JavaRuntime? = null,
    val ramMb: Int = 2048,
    val renderer: RendererType = RendererType.GL4ES,
    val hdr: HdrSettings = HdrSettings(),
    val display: DisplaySettings = DisplaySettings(),
    val extraJvmArgs: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastPlayed: Long = 0
)
