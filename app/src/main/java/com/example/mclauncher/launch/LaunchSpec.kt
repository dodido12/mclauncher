package com.example.mclauncher.launch

import kotlinx.serialization.Serializable

/** Todo lo que el proceso :game necesita para arrancar, serializado a disco por el launcher. */
@Serializable
data class LaunchSpec(
    val instanceId: String,
    val javaHome: String,
    val libjvm: String,
    val preloadLibs: List<String>,
    val env: Map<String, String>,
    val jvmArgs: List<String>,
    val mainClass: String,
    val gameArgs: List<String>,
    val gameDir: String,
    val renderer: Int,
    val hdrEnabled: Boolean,
    /** "mclauncher" (libmcl_native) o "zalith" (libpojavexec + VMLauncher). */
    val engine: String = "mclauncher",
    /** Solo motor zalith: LD_LIBRARY_PATH del linker para libjli/libjvm/natives. */
    val ldLibraryPath: String = ""
)
