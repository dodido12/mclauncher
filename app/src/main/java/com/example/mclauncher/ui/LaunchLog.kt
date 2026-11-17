package com.example.mclauncher.ui

import java.io.File

/**
 * Registro del último lanzamiento, para poder ver por qué el juego no abrió sin conectar
 * el teléfono a un PC. Una app puede leer con `logcat -d` las líneas de su propio UID,
 * que incluyen las del proceso :game (HotSpot, Minecraft, mcl_gl, mcl_vk, mcl_jvm…).
 */
object LaunchLog {
    const val EXIT_FILE = "last_exit.txt"
    const val LOG_FILE = "last_launch.log"
    /** Pasos y errores de la preparación (lado launcher, antes de abrir el juego). */
    const val PREPARE_FILE = "last_prepare.log"
    /** stdout/stderr de la JVM con el motor Zalith (Logger.begin de libpojavexec). */
    const val ZALITH_LOG = "zalith_jvm.log"
    private const val TAG = "MCLauncher"

    fun clear(instanceDir: File) {
        listOf(EXIT_FILE, LOG_FILE, PREPARE_FILE, ZALITH_LOG).forEach { File(instanceDir, it).delete() }
    }

    fun stage(instanceDir: File, msg: String) {
        android.util.Log.i(TAG, msg)
        runCatching { File(instanceDir, PREPARE_FILE).appendText("${java.time.LocalTime.now().withNano(0)} $msg\n") }
    }

    fun error(instanceDir: File, t: Throwable) {
        android.util.Log.e(TAG, "La preparación falló", t)
        runCatching { File(instanceDir, PREPARE_FILE).appendText("ERROR: ${android.util.Log.getStackTraceString(t)}\n") }
    }

    /** Últimas líneas de logcat de esta app (todos sus procesos). */
    fun readLogcat(maxLines: Int = 3000): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", maxLines.toString())
            .redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text
    }.getOrElse { "No se pudo leer logcat: $it" }

    /** Código de salida del último lanzamiento, o null si no hay registro. */
    fun lastExitCode(instanceDir: File): Int? =
        File(instanceDir, EXIT_FILE).takeIf { it.exists() }?.readLines()?.firstOrNull()?.trim()?.toIntOrNull()

    fun describeExit(code: Int?): String = when (code) {
        null -> "Sin código de salida (el proceso del juego se cerró de golpe: crash nativo o sigue abierto)."
        0 -> "El juego se cerró normalmente (código 0)."
        -10 -> "No se pudo cargar libjvm.so (Java dañado o incompleto). Borra datos de la app y reintenta."
        -11 -> "libjvm.so no exporta JNI_CreateJavaVM."
        -12 -> "La JVM no pudo arrancar (opciones inválidas o memoria insuficiente: baja la RAM de la instancia)."
        -13 -> "No se encontró la clase principal del juego (descarga incompleta)."
        -14 -> "La clase principal no tiene main()."
        -100 -> "No se pudo leer launch_spec.json."
        else -> "El juego terminó con código $code."
    }

    /**
     * Texto para mostrar/copiar: código de salida, líneas relevantes de logcat y el final
     * de .minecraft/logs/latest.log (log4j del propio juego).
     */
    fun report(instanceDir: File, gameDir: File): String = buildString {
        val code = lastExitCode(instanceDir)
        appendLine("== Último lanzamiento ==")
        appendLine(describeExit(code))
        File(instanceDir, EXIT_FILE).takeIf { it.exists() }?.readLines()?.drop(1)?.forEach { appendLine(it) }

        File(instanceDir, PREPARE_FILE).takeIf { it.exists() }?.let { f ->
            appendLine()
            appendLine("== Preparación (launcher) ==")
            appendLine(f.readLines().takeLast(80).joinToString("\n"))
        }

        val saved = File(instanceDir, LOG_FILE).takeIf { it.exists() }?.readText()
        val logcat = saved ?: readLogcat()
        appendLine()
        appendLine(if (saved != null) "== logcat (guardado al cerrar el juego) ==" else "== logcat (actual) ==")
        appendLine(filterRelevant(logcat))

        File(instanceDir, ZALITH_LOG).takeIf { it.exists() && it.length() > 0 }?.let { f ->
            appendLine()
            appendLine("== Salida de la JVM (motor Zalith, final) ==")
            appendLine(f.readLines().takeLast(250).joinToString("\n"))
        }

        File(gameDir, "logs/latest.log").takeIf { it.exists() }?.let { f ->
            appendLine()
            appendLine("== .minecraft/logs/latest.log (final) ==")
            appendLine(f.readLines().takeLast(150).joinToString("\n"))
        }
        gameDir.listFiles { f -> f.name.startsWith("hs_err_pid") }?.maxByOrNull { it.lastModified() }?.let { f ->
            appendLine()
            appendLine("== ${f.name} (crash de la JVM) ==")
            appendLine(f.readLines().take(60).joinToString("\n"))
        }
    }

    private val TAGS = listOf(
        "MCLauncher", "Input", "MCLInput", "Touch", "mcl_", "Zalith", "pojavexec", "GLBridge", "OSMDroid", "exit_hook", "jrelog", "Native", "HotSpot", "Minecraft", "GameActivity", "JreManager", "FramePacer", "HdrDisplayManager",
        "AndroidRuntime", "libc", "DEBUG", "linker", "zink", "MESA", "Mesa", "gl4es", "LIBGL", "vulkan", "System.err"
    )

    /** Líneas que llenan el registro sin aportar (trazas de buffers gráficos, auditoría SELinux). */
    private val NOISE = listOf("GraphicBufferAllocator", "avc:  granted", "dlopen /data/", "vulkan_enhance_pipeline_cache")

    private fun filterRelevant(log: String): String {
        val lines = log.lines()
        val relevant = lines.filter { l -> TAGS.any { l.contains(it) } && NOISE.none { l.contains(it) } }
        return (relevant.ifEmpty { lines }).takeLast(400).joinToString("\n")
    }
}
