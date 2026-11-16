package com.example.mclauncher.launch

import android.content.Context
import android.content.Intent
import com.example.mclauncher.instance.InstanceConfig
import com.example.mclauncher.instance.JavaRuntime
import com.example.mclauncher.instance.LoaderType
import com.example.mclauncher.jre.JreManager
import com.example.mclauncher.net.Http
import com.example.mclauncher.net.downloadVerified
import com.example.mclauncher.ui.InstallerActivity
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.jar.JarFile

/**
 * Instala Forge / NeoForge ejecutando su instalador OFICIAL (`--installClient`) dentro de
 * HotSpot en un proceso aparte (:installer). El instalador descarga sus librerías, corre
 * los "processors" que parchean el client.jar y escribe versions/<id>/<id>.json en
 * files/shared, que es justo la estructura de .minecraft que espera.
 */
class ForgeInstaller(
    private val context: Context,
    private val http: HttpClient,
    private val jre: JreManager,
    private val sharedDir: File
) {
    private val versionsDir = File(sharedDir, "versions")

    /** Versión concreta del loader (la de la instancia o la recomendada/última). */
    suspend fun resolveLoaderVersion(cfg: InstanceConfig): String = cfg.loaderVersion ?: when (cfg.loader) {
        LoaderType.FORGE -> {
            val promos: JsonObject = http.get("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json").body()
            val p = promos["promos"]!!.jsonObject
            (p["${cfg.minecraftVersion}-recommended"] ?: p["${cfg.minecraftVersion}-latest"])?.jsonPrimitive?.content
                ?: error("Forge no tiene versiones para ${cfg.minecraftVersion}")
        }
        LoaderType.NEOFORGE -> {
            val res: JsonObject = http.get("https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge").body()
            val prefix = neoForgePrefix(cfg.minecraftVersion)
            res["versions"]!!.jsonArray.map { it.jsonPrimitive.content }
                .filter { it.startsWith(prefix) && !it.contains("beta") }
                .ifEmpty { res["versions"]!!.jsonArray.map { it.jsonPrimitive.content }.filter { it.startsWith(prefix) } }
                .lastOrNull() ?: error("NeoForge no tiene versiones para ${cfg.minecraftVersion}")
        }
        else -> error("No es Forge/NeoForge")
    }

    /** 1.21.1 → "21.1." ; 1.21 → "21.0." ; 26.1 → "26.1." */
    private fun neoForgePrefix(mc: String): String {
        val p = mc.removePrefix("1.").split('.')
        return "${p[0]}.${p.getOrElse(1) { "0" }}."
    }

    fun installedVersionId(cfg: InstanceConfig, loaderVersion: String): String? {
        val candidates = when (cfg.loader) {
            LoaderType.FORGE -> listOf("${cfg.minecraftVersion}-forge-$loaderVersion", "${cfg.minecraftVersion}-forge${cfg.minecraftVersion}-$loaderVersion")
            LoaderType.NEOFORGE -> listOf("neoforge-$loaderVersion")
            else -> emptyList()
        }
        return candidates.firstOrNull { File(versionsDir, "$it/$it.json").exists() }
    }

    private fun installedAny(cfg: InstanceConfig): String? {
        val prefix = if (cfg.loader == LoaderType.NEOFORGE) "neoforge-" else "${cfg.minecraftVersion}-forge"
        return versionsDir.listFiles()?.map { it.name }?.filter { it.startsWith(prefix) && File(versionsDir, "$it/$it.json").exists() }?.maxOrNull()
    }

    /** Devuelve el id de versión instalado, ejecutando el instalador si hace falta. */
    suspend fun ensureInstalled(cfg: InstanceConfig, onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val ver = try {
            resolveLoaderVersion(cfg)
        } catch (e: Exception) {
            // Sin internet: usa la versión ya instalada si existe
            installedAny(cfg)?.let { return@withContext it } ?: throw e
        }
        installedVersionId(cfg, ver)?.let { return@withContext it }

        val (url, name) = when (cfg.loader) {
            LoaderType.FORGE -> "https://maven.minecraftforge.net/net/minecraftforge/forge/${cfg.minecraftVersion}-$ver/forge-${cfg.minecraftVersion}-$ver-installer.jar" to "forge-${cfg.minecraftVersion}-$ver-installer.jar"
            else -> "https://maven.neoforged.net/releases/net/neoforged/neoforge/$ver/neoforge-$ver-installer.jar" to "neoforge-$ver-installer.jar"
        }
        onProgress("Descargando instalador de ${cfg.loader.name.lowercase()} $ver")
        val installer = File(context.cacheDir, "installers/$name")
        if (!installer.exists()) {
            val sha1 = runCatching { http.get("$url.sha1").body<String>().trim().take(40) }.getOrNull()
            http.downloadVerified(url, installer, sha1)
        }

        // El instalador exige un launcher_profiles.json en el directorio destino
        File(sharedDir, "launcher_profiles.json").takeIf { !it.exists() }?.writeText("""{"profiles":{},"selectedProfile":""}""")

        val rt = jre.effective(JavaRuntime.JAVA_21)
        jre.ensureInstalled(rt, onProgress)
        val mainClass = JarFile(installer).use { it.manifest.mainAttributes.getValue("Main-Class") }
            ?: "net.minecraftforge.installer.SimpleInstaller"
        val tmp = File(context.cacheDir, "jvmtmp").apply { mkdirs() }
        val home = jre.homeOf(rt)
        val spec = LaunchSpec(
            instanceId = cfg.id,
            javaHome = home.absolutePath,
            libjvm = jre.libjvmOf(rt)!!.absolutePath,
            preloadLibs = jre.depLibsOf(rt).map { it.absolutePath },
            env = mapOf("JAVA_HOME" to home.absolutePath, "HOME" to sharedDir.absolutePath, "TMPDIR" to tmp.absolutePath),
            jvmArgs = listOf(
                "-Xmx1024M", "-XX:-UsePerfData", "-Djava.awt.headless=true",
                "-Djava.home=${home.absolutePath}", "-Djava.io.tmpdir=${tmp.absolutePath}",
                "-Duser.home=${sharedDir.absolutePath}", "-Dos.name=Linux",
                "-cp", installer.absolutePath
            ),
            mainClass = mainClass,
            gameArgs = listOf("--installClient", sharedDir.absolutePath),
            gameDir = sharedDir.absolutePath,
            renderer = 0,
            hdrEnabled = false
        )
        val specFile = File(context.cacheDir, "installer_spec.json")
        val resultFile = File(context.cacheDir, "installer_result.txt").apply { delete() }
        specFile.writeText(Http.json.encodeToString(LaunchSpec.serializer(), spec))

        onProgress("Ejecutando instalador oficial (puede tardar varios minutos)…")
        context.startActivity(Intent(context, InstallerActivity::class.java)
            .putExtra(InstallerActivity.EXTRA_SPEC, specFile.absolutePath)
            .putExtra(InstallerActivity.EXTRA_RESULT, resultFile.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        val deadline = System.currentTimeMillis() + 20 * 60_000
        while (!resultFile.exists()) {
            if (System.currentTimeMillis() > deadline) error("El instalador no terminó en 20 minutos")
            delay(1000)
        }
        val code = resultFile.readText().trim().toIntOrNull() ?: -1
        check(code == 0) { "El instalador terminó con código $code (revisa logcat: tag Minecraft/HotSpot)" }
        installedVersionId(cfg, ver) ?: error("El instalador terminó pero no se encontró la versión generada")
    }
}
