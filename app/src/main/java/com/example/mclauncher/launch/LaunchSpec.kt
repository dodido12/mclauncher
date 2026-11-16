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
    val hdrEnabled: Boolean
)
