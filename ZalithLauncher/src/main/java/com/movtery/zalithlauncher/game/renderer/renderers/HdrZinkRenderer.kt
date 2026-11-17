/*
 * Zalith Launcher 2 (fork con HDR)
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.movtery.zalithlauncher.game.renderer.renderers

import com.movtery.zalithlauncher.game.renderer.RendererInterface

/**
 * Zink sobre OSMesa (bridge osm_bridge de libpojavexec). Es el camino que usa la
 * salida HDR (hdr_output.c): OSMesa dibuja en un buffer propio y al presentar se
 * convierte a FP16 scRGB. Las versiones con HDR activado usan siempre este renderizador.
 */
object HdrZinkRenderer : RendererInterface {
    override fun getRendererId(): String = "vulkan_zink"

    override fun getUniqueIdentifier(): String = "5b1f6c2e-9d0a-4f3b-8a7e-2c4d6e8f0a1b"

    override val mesaBased: Boolean = true

    override val needsZinkDriverOverride: Boolean = true

    override fun getRendererName(): String = "Vulkan Zink (HDR)"

    override fun getMaxMCVersion(): String = "26.3-snapshot-3"

    override fun getDisplayMaxMCVersion(): String = "26.2"

    /**
     * Driver Vulkan del sistema: el Turnip incluido (Mesa 24.3) no soporta Adreno 8xx
     * y Zink se cae al crear el dispositivo (SIGSEGV en libOSMesa_8.so).
     */
    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        mapOf("POJAV_ZINK_PREFER_SYSTEM_DRIVER" to "1")
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libOSMesa_8.so"
}
