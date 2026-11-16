package com.example.mclauncher.launch

import com.example.mclauncher.instance.InstanceConfig
import com.example.mclauncher.instance.LoaderType
import com.example.mclauncher.net.Http
import com.example.mclauncher.net.MojangDownloader
import com.example.mclauncher.net.str
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Produce un version.json "plano" combinando vanilla + perfil del loader (inheritsFrom).
 *  - Fabric:  https://meta.fabricmc.net/v2/versions/loader/<mc>/<loader>/profile/json
 *  - Quilt:   https://meta.quiltmc.org/v3/versions/loader/<mc>/<loader>/profile/json
 *  - Forge / NeoForge: requieren ejecutar su instalador oficial (processors que parchean
 *    el client.jar). Se ejecuta una sola vez con la misma JVM en modo headless
 *    (ForgeInstallerRunner) y produce versions/<id>/<id>.json, que aquí solo se lee.
 */
class VersionResolver(private val http: HttpClient, private val mojang: MojangDownloader) {

    suspend fun resolve(cfg: InstanceConfig, forgeVersionId: String? = null): JsonObject {
        val vanilla = mojang.versionJson(cfg.minecraftVersion)
        val loaderProfile: JsonObject? = when (cfg.loader) {
            LoaderType.VANILLA -> null
            LoaderType.FABRIC -> fabricLike("fabric", cfg, "https://meta.fabricmc.net/v2/versions/loader/${cfg.minecraftVersion}")
            LoaderType.QUILT -> fabricLike("quilt", cfg, "https://meta.quiltmc.org/v3/versions/loader/${cfg.minecraftVersion}")
            LoaderType.FORGE, LoaderType.NEOFORGE -> {
                val id = forgeVersionId ?: installedForgeId(cfg) ?: error(
                    "${cfg.loader} ${cfg.loaderVersion ?: ""} no instalado (ForgeInstaller.ensureInstalled)")
                Http.json.parseToJsonElement(File(mojang.versionsDir, "$id/$id.json").readText()).jsonObject
            }
        }
        return if (loaderProfile == null) vanilla else merge(vanilla, loaderProfile)
    }

    /**
     * Perfil de Fabric/Quilt. Con internet se actualiza; sin internet se usa la copia guardada,
     * así una instancia ya jugada vuelve a abrir en modo avión.
     */
    private suspend fun fabricLike(kind: String, cfg: InstanceConfig, base: String): JsonObject {
        val f = File(mojang.versionsDir, "$kind-${cfg.minecraftVersion}-${cfg.loaderVersion ?: "latest"}.json")
        if (cfg.loaderVersion != null && f.exists()) return Http.json.parseToJsonElement(f.readText()).jsonObject
        val fresh = runCatching {
            val ver = cfg.loaderVersion ?: http.get(base).body<JsonArray>().first().jsonObject["loader"]!!.jsonObject.str("version")!!
            val o: JsonObject = http.get("$base/$ver/profile/json").body()
            f.parentFile?.mkdirs(); f.writeText(o.toString()); o
        }.getOrNull()
        return fresh ?: f.takeIf { it.exists() }?.let { Http.json.parseToJsonElement(it.readText()).jsonObject }
            ?: error("Sin internet y el perfil de $kind para ${cfg.minecraftVersion} aún no está descargado")
    }

    private fun installedForgeId(cfg: InstanceConfig): String? {
        val prefix = if (cfg.loader == LoaderType.NEOFORGE) "neoforge-" else "${cfg.minecraftVersion}-forge-"
        return mojang.versionsDir.listFiles()?.map { it.name }
            ?.firstOrNull { it.startsWith(prefix) && (cfg.loaderVersion == null || it.contains(cfg.loaderVersion)) }
    }

    /** Hijo sobrescribe mainClass/args; librerías del hijo van primero y reemplazan mismo group:artifact. */
    fun merge(parent: JsonObject, child: JsonObject): JsonObject {
        val out = parent.toMutableMap()
        child.str("mainClass")?.let { out["mainClass"] = child["mainClass"]!! }
        child["id"]?.let { out["id"] = it }
        val childLibs = child["libraries"]?.jsonArray.orEmpty()
        val childKeys = childLibs.mapNotNull { it.jsonObject.str("name")?.split(':')?.take(2)?.joinToString(":") }.toSet()
        val parentLibs = parent["libraries"]?.jsonArray.orEmpty().filterNot {
            it.jsonObject.str("name")?.split(':')?.take(2)?.joinToString(":") in childKeys
        }
        out["libraries"] = JsonArray(childLibs + parentLibs)
        child["arguments"]?.jsonObject?.let { ca ->
            val pa = parent["arguments"]?.jsonObject
            out["arguments"] = JsonObject(mapOf(
                "game" to JsonArray(pa?.get("game")?.jsonArray.orEmpty() + ca["game"]?.jsonArray.orEmpty()),
                "jvm" to JsonArray(pa?.get("jvm")?.jsonArray.orEmpty() + ca["jvm"]?.jsonArray.orEmpty())
            ))
        }
        child["minecraftArguments"]?.let { out["minecraftArguments"] = it }
        out["_vanillaId"] = parent["id"]!!
        return JsonObject(out)
    }
}
