package com.example.mclauncher.launch

import android.content.Context
import android.content.Intent
import com.example.mclauncher.auth.LaunchSession
import com.example.mclauncher.instance.InstanceConfig
import com.example.mclauncher.instance.InstanceManager
import com.example.mclauncher.instance.JavaRuntime
import com.example.mclauncher.instance.RendererType
import com.example.mclauncher.jre.JreManager
import com.example.mclauncher.net.MojangDownloader
import com.example.mclauncher.net.str
import com.example.mclauncher.net.downloadVerified
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import com.example.mclauncher.ui.GameActivity
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Orquesta: resolver versión+loader → descargar → elegir JRE → construir classpath y
 * argumentos → escribir launch_spec.json → abrir GameActivity en el proceso :game.
 */
class MinecraftLauncher(
    private val context: Context,
    private val http: HttpClient,
    private val instances: InstanceManager,
    private val jre: JreManager,
    private val mojang: MojangDownloader
) {
    private val resolver = VersionResolver(http, mojang)
    private val forge = ForgeInstaller(context, http, jre, instances.sharedDir)
    private val json = Json { prettyPrint = true }
    private val nativeDir: String get() = context.applicationInfo.nativeLibraryDir

    companion object {
        /**
         * Versión de LWJGL cuyos natives van en el APK (natives/build-natives.sh). Los jars
         * org.lwjgl:* del version.json se sustituyen por esta versión, bajada de Maven Central,
         * para que Java y natives coincidan siempre.
         */
        // Minecraft 26.x está compilado contra LWJGL 3.4 (p. ej. tinyfd_messageBox devuelve int).
        // Los natives liblwjgl*.so los compila el workflow con natives/build-natives.sh (ONLY=lwjgl).
        const val LWJGL_VERSION = "3.4.1"
        /** CDN de librerías de Mojang y Maven Central como respaldo. */
        /**
         * ${variable} de los argumentos del version.json. La "}" va escapada: el motor de regex
         * de Android (ICU) la rechaza sin escapar, aunque Java de escritorio la acepta.
         */
        private val VAR_REGEX = Regex("""\$\{([a-zA-Z0-9_]+)\}""")
        private val LWJGL_REPOS = listOf("https://libraries.minecraft.net", "https://repo1.maven.org/maven2")
    }

    suspend fun prepare(requested: InstanceConfig, session: LaunchSession, onProgress: (String, Float) -> Unit): LaunchSpec =
        withContext(Dispatchers.IO) {
            var cfg = requested
            onProgress("Resolviendo versión", 0f)
            var forgeId: String? = null
            if (cfg.loader == com.example.mclauncher.instance.LoaderType.FORGE || cfg.loader == com.example.mclauncher.instance.LoaderType.NEOFORGE) {
                // El instalador necesita el vanilla ya descargado (client.jar + librerías)
                mojang.installVersion(mojang.versionJson(cfg.minecraftVersion)) { p -> onProgress(p.stage, p.done.toFloat() / p.total.coerceAtLeast(1)) }
                forgeId = forge.ensureInstalled(cfg) { onProgress(it, 0.5f) }
            }
            val version = resolver.resolve(cfg, forgeId)
            val vanillaId = version.str("_vanillaId") ?: version.str("id")!!
            val vanillaJson = mojang.versionJson(vanillaId)

            mojang.installVersion(vanillaJson) { p -> onProgress(p.stage, p.done.toFloat() / p.total.coerceAtLeast(1)) }
            if (cfg.loader != com.example.mclauncher.instance.LoaderType.VANILLA) mojang.installVersion(stripForLoaderLibs(version)) { p -> onProgress(p.stage, p.done.toFloat() / p.total.coerceAtLeast(1)) }

            // Java: el de la instancia o el mínimo que pide el version.json (8/17/21/25)
            val required = vanillaJson["javaVersion"]?.jsonObject?.get("majorVersion")?.jsonPrimitive?.int ?: 8

            // Minecraft 1.17+ (Java 16+) exige OpenGL 3.2 core: gl4es solo da GL 2.1 y el juego
            // se cierra al crear la ventana. En ese caso se usa Zink automáticamente.
            if (cfg.renderer == RendererType.GL4ES && required >= 16) {
                cfg = cfg.copy(renderer = RendererType.ZINK_VULKAN)
                instances.update(cfg.id) { it.copy(renderer = RendererType.ZINK_VULKAN) }
                onProgress("Minecraft ${cfg.minecraftVersion} necesita OpenGL 3.2: usando Zink (Vulkan)", 0.9f)
            }
            val rt = jre.effective(cfg.javaVersion?.takeIf { it.major >= required } ?: JavaRuntime.forRequiredMajor(required))
            jre.ensureInstalled(rt) { onProgress(it, 0.95f) }
            val javaHome = jre.homeOf(rt)
            val libjvm = jre.libjvmOf(rt)!!

            val gameDir = instances.gameDir(cfg.id)
            ensureLwjgl(version) { onProgress(it, 0.97f) }
            val classpath = buildClasspath(version, vanillaId)
            val vars = mapOf(
                "auth_player_name" to session.username,
                "auth_uuid" to session.uuid.replace("-", ""),
                "auth_access_token" to session.accessToken,
                "auth_session" to session.accessToken,
                "auth_xuid" to session.xuid,
                "user_type" to session.userType,
                "clientid" to "0",
                "user_properties" to "{}",
                "version_name" to (version.str("id") ?: vanillaId),
                "version_type" to (vanillaJson.str("type") ?: "release"),
                "game_directory" to gameDir.absolutePath,
                "assets_root" to mojang.assetsDir.absolutePath,
                "game_assets" to File(mojang.assetsDir, "virtual/legacy").absolutePath,
                "assets_index_name" to (vanillaJson["assetIndex"]?.jsonObject?.str("id") ?: "legacy"),
                "natives_directory" to nativeDir,
                "launcher_name" to "MCLauncher",
                "launcher_version" to "0.1.0",
                "classpath" to classpath,
                "classpath_separator" to ":",
                "library_directory" to mojang.librariesDir.absolutePath,
                "resolution_width" to "1280",
                "resolution_height" to "720"
            )

            val jvmArgs = buildList {
                addAll(jre.jvmFlags(rt, cfg))
                addAll(androidJvmProps(cfg, javaHome))
                // log4j2 config oficial (también mitiga Log4Shell en versiones antiguas)
                vanillaJson["logging"]?.jsonObject?.get("client")?.jsonObject?.let { lc ->
                    val f = File(mojang.assetsDir, "log_configs/${lc["file"]!!.jsonObject.str("id")}")
                    add(lc.str("argument")!!.replace("\${path}", f.absolutePath))
                }
                add("-Dlog4j2.formatMsgNoLookups=true")
                addAll(argList(version, "jvm", vars).ifEmpty { listOf("-Djava.library.path=$nativeDir", "-cp", classpath) })
                // El version.json apunta estos directorios a ${natives_directory} (nativeLibraryDir,
                // de solo lectura en Android): JNA/Netty/LWJGL no podrían extraer nada. Van al final
                // para que ganen; dedupeJvmArgs quita los anteriores.
                val tmp = File(context.cacheDir, "jvmtmp").absolutePath
                add("-Djna.tmpdir=$tmp")
                add("-Dio.netty.native.workdir=$tmp")
                add("-Dorg.lwjgl.system.SharedLibraryExtractPath=$tmp")
                // libjnidispatch.so compilado para Android (bionic) en jniLibs: el que trae el jar
                // de JNA es para glibc y no carga. Lo usa OSHI para leer CPU/memoria.
                add("-Djna.boot.library.path=$nativeDir")
                add("-Djna.nounpack=true")
            }.let(::dedupeJvmArgs)

            val gameArgs = argList(version, "game", vars).ifEmpty {
                version.str("minecraftArguments")!!.split(' ').map { substitute(it, vars) }
            }

            val spec = LaunchSpec(
                instanceId = cfg.id,
                javaHome = javaHome.absolutePath,
                libjvm = libjvm.absolutePath,
                preloadLibs = jre.depLibsOf(rt).map { it.absolutePath } + libjvm.absolutePath + jrePreloads(javaHome),
                env = rendererEnv(cfg, javaHome),
                jvmArgs = jvmArgs,
                mainClass = version.str("mainClass")!!,
                gameArgs = gameArgs,
                gameDir = gameDir.absolutePath,
                renderer = cfg.renderer.ordinal,
                hdrEnabled = cfg.hdr.enabled && cfg.renderer == RendererType.ZINK_VULKAN_HDR
            )
            File(instances.instanceDir(cfg.id), "launch_spec.json").writeText(json.encodeToString(LaunchSpec.serializer(), spec))
            onProgress("Listo", 1f)
            spec
        }

    fun start(cfg: InstanceConfig) {
        instances.markPlayed(cfg.id)
        context.startActivity(Intent(context, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_INSTANCE, cfg.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------------- classpath ----------------
    private fun lwjglModules(version: JsonObject): Set<String> =
        version["libraries"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject.str("name") }
            .map { it.split(':') }.filter { it[0] == "org.lwjgl" && it.size == 3 }.map { it[1] }.toSet()

    private fun lwjglJar(artifact: String) =
        File(mojang.librariesDir, "org/lwjgl/$artifact/$LWJGL_VERSION/$artifact-$LWJGL_VERSION.jar")

    private suspend fun ensureLwjgl(version: JsonObject, onProgress: (String) -> Unit) {
        for (a in lwjglModules(version)) {
            val f = lwjglJar(a)
            if (f.exists()) continue
            onProgress("LWJGL $LWJGL_VERSION: $a")
            val path = "org/lwjgl/$a/$LWJGL_VERSION/$a-$LWJGL_VERSION.jar"
            var lastError: Throwable? = null
            for (repo in LWJGL_REPOS) {
                val ok = runCatching {
                    val sha1 = http.get("$repo/$path.sha1").bodyAsText().trim().take(40).takeIf { it.length == 40 }
                    http.downloadVerified("$repo/$path", f, sha1)
                }.onFailure { lastError = it }.isSuccess
                if (ok) break
            }
            if (!f.exists()) throw IllegalStateException("No se pudo descargar LWJGL $a", lastError)
        }
    }

    private fun buildClasspath(version: JsonObject, vanillaId: String): String {
        val seen = LinkedHashMap<String, File>()
        for (lib in version["libraries"]?.jsonArray.orEmpty().map { it.jsonObject }) {
            if (!mojang.rulesAllow(lib["rules"])) continue
            val name = lib.str("name") ?: continue
            if (lib["natives"] != null && lib["downloads"]?.jsonObject?.get("artifact") == null) continue
            val parts = name.split(':')
            if (parts.getOrNull(3)?.startsWith("natives-") == true) continue   // natives de escritorio: no sirven
            val key = parts[0] + ":" + parts[1] + (parts.getOrNull(3)?.let { ":$it" } ?: "")
            if (parts[0] == "org.lwjgl") { seen.putIfAbsent(key, lwjglJar(parts[1])); continue }
            val path = lib["downloads"]?.jsonObject?.get("artifact")?.jsonObject?.str("path") ?: MojangDownloader.mavenPath(name)
            seen.putIfAbsent(key, File(mojang.librariesDir, path))
        }
        val files = seen.values + File(mojang.versionsDir, "$vanillaId/$vanillaId.jar")
        return files.filter { it.exists() }.joinToString(":") { it.absolutePath }
    }

    // ---------------- argumentos ----------------
    private fun argList(version: JsonObject, kind: String, vars: Map<String, String>): List<String> {
        val arr = version["arguments"]?.jsonObject?.get(kind) as? JsonArray ?: return emptyList()
        val out = mutableListOf<String>()
        for (el in arr) when {
            el is JsonPrimitive -> out += substitute(el.content, vars)
            else -> {
                val o = el.jsonObject
                if (!mojang.rulesAllow(o["rules"], features = mapOf("has_custom_resolution" to false, "is_demo_user" to false))) continue
                when (val v = o["value"]) {
                    is JsonPrimitive -> out += substitute(v.content, vars)
                    is JsonArray -> v.forEach { out += substitute(it.jsonPrimitive.content, vars) }
                    else -> Unit
                }
            }
        }
        return out
    }

    private fun substitute(s: String, vars: Map<String, String>) =
        VAR_REGEX.replace(s) { m -> vars[m.groupValues[1]] ?: m.value }

    /** Si hay -Xmx/-Xss duplicados (version.json + nuestros), gana el último. */
    private fun dedupeJvmArgs(args: List<String>): List<String> {
        val single = listOf("-Xmx", "-Xms", "-Xss", "-Djava.library.path=", "-Djna.tmpdir=", "-Dio.netty.native.workdir=",
            "-Dorg.lwjgl.system.SharedLibraryExtractPath=", "-Djna.boot.library.path=")
        val result = ArrayList<String>()
        for (a in args) {
            val p = single.firstOrNull { a.startsWith(it) }
            if (p != null) result.removeAll { it.startsWith(p) }
            result += a
        }
        return result
    }

    private fun androidJvmProps(cfg: InstanceConfig, javaHome: File): List<String> {
        val tmp = File(context.cacheDir, "jvmtmp").apply { mkdirs() }
        return listOf(
            "-Djava.home=${javaHome.absolutePath}",
            "-Djava.io.tmpdir=${tmp.absolutePath}",
            "-Duser.home=${instances.gameDir(cfg.id).absolutePath}",
            "-Dorg.lwjgl.librarypath=$nativeDir",
            "-Dorg.lwjgl.system.SharedLibraryExtractPath=${tmp.absolutePath}",
            "-Dorg.lwjgl.util.NoChecks=true",
            // libjemalloc.so no va en el APK: usar malloc de bionic directamente
            "-Dorg.lwjgl.system.allocator=system",
            // LWJGL carga libmcl_native.so como "libGL": exporta glXGetProcAddress y redirige
            // a gl4es o a Mesa/Zink según MCL_RENDERER (ver cpp/gl_loader.cpp).
            "-Dorg.lwjgl.opengl.libname=$nativeDir/libmcl_native.so",
            "-Dorg.lwjgl.glfw.libname=$nativeDir/libglfw.so",          // shim GLFW (cpp/glfw)
            "-Dorg.lwjgl.openal.libname=$nativeDir/libopenal.so",       // OpenAL Soft (AAudio/OpenSL)
            "-Dorg.lwjgl.freetype.libname=$nativeDir/libfreetype.so",
            // Android no tiene /etc/localtime ni un idioma de sistema visible para Java
            "-Duser.timezone=${java.util.TimeZone.getDefault().id}",
            "-Duser.language=${java.util.Locale.getDefault().language}",
            "-Dos.version=Android-${android.os.Build.VERSION.RELEASE}",
            // Algunos SoC reportan menos núcleos a la JVM (cgroups de Android)
            "-XX:ActiveProcessorCount=${Runtime.getRuntime().availableProcessors()}",
            // POSIX_SPAWN ejecuta jspawnhelper desde files/, bloqueado por W^X en targetSdk 29+
            "-Djdk.lang.Process.launchMechanism=FORK",
            "-Dorg.lwjgl.vulkan.libname=libvulkan.so",
            "-Dfml.earlyprogresswindow=false",          // Forge/NeoForge: ventana de carga temprana no soportada
            "-Dsodium.checks.issue2561=false"
        )
    }

    /**
     * Librerías de la JRE que conviene precargar con ruta absoluta (el linker de Android no
     * busca en files/runtimes/ por DT_NEEDED). Las del renderizador las carga gl_loader.cpp
     * con RTLD_LOCAL para no chocar con el libEGL/libGLES del sistema que usa HWUI.
     */
    private fun jrePreloads(javaHome: File): List<String> {
        val order = listOf("libjli.so", "libverify.so", "libjava.so", "libjimage.so", "libzip.so", "libnet.so", "libnio.so", "libextnet.so", "libawt.so", "libawt_headless.so", "libmanagement.so")
        val dirs = listOf(File(javaHome, "lib"), File(javaHome, "lib/jli"), File(javaHome, "lib/aarch64"), File(javaHome, "lib/aarch64/jli"))
        return order.mapNotNull { n -> dirs.map { File(it, n) }.firstOrNull { it.exists() }?.absolutePath }
    }

    private fun rendererEnv(cfg: InstanceConfig, javaHome: File): Map<String, String> {
        val base = mapOf(
            "JAVA_HOME" to javaHome.absolutePath,
            "HOME" to instances.gameDir(cfg.id).absolutePath,
            "TMPDIR" to File(context.cacheDir, "jvmtmp").absolutePath,
            "MCL_NATIVE_DIR" to nativeDir,
            "MCL_RENDERER" to when (cfg.renderer) { RendererType.GL4ES -> "gl4es"; RendererType.ZINK_VULKAN -> "zink"; RendererType.ZINK_VULKAN_HDR -> "zink_hdr" }
        )
        return base + when (cfg.renderer) {
            RendererType.GL4ES -> mapOf("LIBGL_ES" to "3", "LIBGL_MIPMAP" to "3", "LIBGL_NORMALIZE" to "1", "LIBGL_NOINTOVLHACK" to "1")
            RendererType.ZINK_VULKAN, RendererType.ZINK_VULKAN_HDR -> mapOf(
                "GALLIUM_DRIVER" to "zink",
                "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
                // OJO: NO poner LIBGL_ALWAYS_SOFTWARE=1. Zink lo interpreta como "usa una GPU de CPU
                // (lavapipe)" y falla con "CPU device requested but none found". Sin /dev/dri, el EGL
                // surfaceless de Mesa cae solo a la ruta sin DRM y carga Zink sobre la GPU real.
                "MESA_GL_VERSION_OVERRIDE" to "4.6",
                "MESA_GLSL_VERSION_OVERRIDE" to "460",
                "MESA_NO_ERROR" to "1",
                "mesa_glthread" to "true",
                "ZINK_DESCRIPTORS" to "auto",
                "MCL_HDR" to if (cfg.renderer == RendererType.ZINK_VULKAN_HDR && cfg.hdr.enabled) "1" else "0"
            )
        }
    }

    /** Las libs del loader se bajan por URL Maven; sin assets ni client propio. */
    private fun stripForLoaderLibs(v: JsonObject) =
        JsonObject(v.filterKeys { it == "id" || it == "libraries" })
}
