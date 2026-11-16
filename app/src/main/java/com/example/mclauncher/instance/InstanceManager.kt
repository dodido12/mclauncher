package com.example.mclauncher.instance

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Estructura en disco:
 *
 * files/
 *  ├─ shared/                 ← compartido entre instancias (se descarga una sola vez)
 *  │   ├─ versions/<id>/<id>.json, <id>.jar
 *  │   ├─ libraries/...
 *  │   └─ assets/{indexes,objects,log_configs}
 *  └─ instances/<instance_id>/
 *      ├─ instance.json
 *      └─ .minecraft/{mods,resourcepacks,shaderpacks,saves,config,screenshots,logs}
 */
class InstanceManager(private val context: Context) {

    val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    val root: File = File(context.filesDir, "instances").apply { mkdirs() }
    val sharedDir: File = File(context.filesDir, "shared").apply { mkdirs() }

    private val idRegex = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

    fun instanceDir(id: String): File {
        require(idRegex.matches(id)) { "ID de instancia inválido: $id" }
        return File(root, id)
    }

    fun gameDir(id: String) = File(instanceDir(id), ".minecraft")
    fun modsDir(id: String) = File(gameDir(id), "mods")
    fun resourcePacksDir(id: String) = File(gameDir(id), "resourcepacks")
    fun shaderPacksDir(id: String) = File(gameDir(id), "shaderpacks")

    suspend fun list(): List<InstanceConfig> = withContext(Dispatchers.IO) {
        root.listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { dir -> runCatching { load(dir.name) }.getOrNull() }
            .sortedByDescending { it.lastPlayed }
    }

    suspend fun create(
        name: String,
        minecraftVersion: String,
        loader: LoaderType = LoaderType.VANILLA,
        loaderVersion: String? = null,
        renderer: RendererType = RendererType.GL4ES,
        ramMb: Int = recommendedRamMb()
    ): InstanceConfig = withContext(Dispatchers.IO) {
        val id = slug(name) + "-" + UUID.randomUUID().toString().take(6)
        val cfg = InstanceConfig(
            id = id, name = name, minecraftVersion = minecraftVersion,
            loader = loader, loaderVersion = loaderVersion,
            renderer = renderer, ramMb = clampRam(ramMb)
        )
        val game = gameDir(id)
        listOf("mods", "resourcepacks", "shaderpacks", "saves", "config", "screenshots", "logs")
            .forEach { File(game, it).mkdirs() }
        save(cfg)
        cfg
    }

    fun load(id: String): InstanceConfig =
        json.decodeFromString(InstanceConfig.serializer(), File(instanceDir(id), "instance.json").readText())

    /** Escritura atómica: tmp + rename, para no corromper instance.json si el proceso muere. */
    fun save(cfg: InstanceConfig) {
        val dir = instanceDir(cfg.id).apply { mkdirs() }
        val tmp = File(dir, "instance.json.tmp")
        tmp.writeText(json.encodeToString(InstanceConfig.serializer(), cfg.copy(ramMb = clampRam(cfg.ramMb))))
        if (!tmp.renameTo(File(dir, "instance.json"))) error("No se pudo guardar instance.json")
    }

    fun update(id: String, block: (InstanceConfig) -> InstanceConfig): InstanceConfig =
        block(load(id)).also(::save)

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { instanceDir(id).deleteRecursively() }

    suspend fun duplicate(id: String, newName: String): InstanceConfig = withContext(Dispatchers.IO) {
        val src = load(id)
        val newId = slug(newName) + "-" + UUID.randomUUID().toString().take(6)
        instanceDir(id).copyRecursively(instanceDir(newId))
        src.copy(id = newId, name = newName, createdAt = System.currentTimeMillis(), lastPlayed = 0).also(::save)
    }

    fun markPlayed(id: String) = update(id) { it.copy(lastPlayed = System.currentTimeMillis()) }

    // ---- RAM ----
    private fun totalRamMb(): Long {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        return info.totalMem / (1024 * 1024)
    }

    /** Deja al menos ~3 GB para Android, el driver gráfico y la memoria nativa de la JVM. */
    fun maxRamMb(): Int = ((totalRamMb() - 3072).coerceAtLeast(1024)).toInt()
    fun recommendedRamMb(): Int = (totalRamMb() / 4).toInt().coerceIn(1024, 6144)
    private fun clampRam(mb: Int) = mb.coerceIn(512, maxRamMb())

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifEmpty { "instancia" }
}
