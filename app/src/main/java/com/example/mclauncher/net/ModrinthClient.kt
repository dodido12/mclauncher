package com.example.mclauncher.net

import com.example.mclauncher.instance.InstanceConfig
import com.example.mclauncher.instance.LoaderType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Cliente Modrinth API v2 — https://docs.modrinth.com/api/ */
class ModrinthClient(private val http: HttpClient, private val userAgent: String) {

    enum class ProjectType(val api: String) { MOD("mod"), RESOURCE_PACK("resourcepack"), SHADER("shader"), MODPACK("modpack") }

    @Serializable data class SearchResponse(val hits: List<SearchHit>, val offset: Int, val limit: Int, @SerialName("total_hits") val totalHits: Int)

    @Serializable data class SearchHit(
        @SerialName("project_id") val projectId: String,
        val slug: String,
        val title: String,
        val description: String,
        @SerialName("project_type") val projectType: String,
        val downloads: Long = 0,
        @SerialName("icon_url") val iconUrl: String? = null,
        val categories: List<String> = emptyList(),
        val versions: List<String> = emptyList()
    )

    @Serializable data class Version(
        val id: String,
        @SerialName("project_id") val projectId: String,
        val name: String,
        @SerialName("version_number") val versionNumber: String,
        @SerialName("game_versions") val gameVersions: List<String>,
        val loaders: List<String>,
        @SerialName("version_type") val versionType: String,
        val files: List<VersionFile>,
        val dependencies: List<Dependency> = emptyList()
    )

    @Serializable data class VersionFile(val url: String, val filename: String, val primary: Boolean = false, val size: Long = 0, val hashes: Hashes)
    @Serializable data class Hashes(val sha512: String, val sha1: String? = null)
    @Serializable data class Dependency(
        @SerialName("version_id") val versionId: String? = null,
        @SerialName("project_id") val projectId: String? = null,
        @SerialName("dependency_type") val type: String
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Búsqueda filtrada por tipo, versión de MC y loader.
     * Facets: AND entre listas externas, OR dentro de cada lista.
     */
    suspend fun search(
        query: String,
        type: ProjectType,
        gameVersion: String?,
        loader: String?,
        limit: Int = 20,
        offset: Int = 0,
        index: String = "relevance"
    ): SearchResponse {
        val facets = buildList {
            add(listOf("project_type:${type.api}"))
            if (gameVersion != null) add(listOf("versions:$gameVersion"))
            if (loader != null) add(listOf("categories:$loader"))
        }
        val facetsJson = facets.joinToString(",", "[", "]") { g -> g.joinToString(",", "[", "]") { "\"$it\"" } }
        return getJson("$API/search") {
            parameter("query", query); parameter("facets", facetsJson)
            parameter("limit", limit); parameter("offset", offset); parameter("index", index)
        }
    }

    suspend fun projectVersions(projectIdOrSlug: String, loaders: List<String>?, gameVersions: List<String>?): List<Version> =
        getJson("$API/project/$projectIdOrSlug/version") {
            loaders?.let { l -> parameter("loaders", l.joinToString(",", "[", "]") { "\"$it\"" }) }
            gameVersions?.let { g -> parameter("game_versions", g.joinToString(",", "[", "]") { "\"$it\"" }) }
        }

    suspend fun version(id: String): Version = getJson("$API/version/$id")

    /** Loader que corresponde a cada tipo de proyecto para una instancia. */
    fun loaderFacetFor(type: ProjectType, cfg: InstanceConfig): String? = when (type) {
        ProjectType.MOD, ProjectType.MODPACK -> cfg.loader.name.lowercase().takeIf { cfg.loader != LoaderType.VANILLA }
        ProjectType.SHADER -> "iris"            // Iris (+ Sodium) en Fabric/Quilt/NeoForge
        ProjectType.RESOURCE_PACK -> "minecraft"
    }

    fun targetDir(type: ProjectType, gameDir: File) = when (type) {
        ProjectType.MOD -> File(gameDir, "mods")
        ProjectType.RESOURCE_PACK -> File(gameDir, "resourcepacks")
        ProjectType.SHADER -> File(gameDir, "shaderpacks")
        ProjectType.MODPACK -> File(gameDir, "modpacks")
    }

    /**
     * Instala la mejor versión compatible + dependencias requeridas (recursivo).
     * Cada archivo se verifica con SHA-512 antes de quedar en la carpeta final.
     */
    suspend fun install(
        projectId: String,
        type: ProjectType,
        cfg: InstanceConfig,
        gameDir: File,
        visited: MutableSet<String> = mutableSetOf(),
        onProgress: (String) -> Unit = {}
    ): List<File> {
        if (!visited.add(projectId)) return emptyList()
        val loader = loaderFacetFor(type, cfg)
        val candidates = projectVersions(projectId, loader?.let(::listOf), listOf(cfg.minecraftVersion))
        val v = candidates.firstOrNull { it.versionType == "release" } ?: candidates.firstOrNull()
            ?: error("Sin versión de $projectId para ${cfg.minecraftVersion} / ${loader ?: "cualquier loader"}")
        return installVersion(v, type, cfg, gameDir, visited, onProgress)
    }

    suspend fun installVersion(
        v: Version, type: ProjectType, cfg: InstanceConfig, gameDir: File,
        visited: MutableSet<String> = mutableSetOf(v.projectId), onProgress: (String) -> Unit = {}
    ): List<File> {
        val file = v.files.firstOrNull { it.primary } ?: v.files.first()
        require(!file.filename.contains('/') && !file.filename.contains("..")) { "Nombre de archivo sospechoso" }
        onProgress("Descargando ${file.filename}")
        val out = http.downloadVerified(file.url, File(targetDir(type, gameDir), file.filename), file.hashes.sha512, "SHA-512")
        val result = mutableListOf(out)
        if (type == ProjectType.MOD) {
            for (dep in v.dependencies.filter { it.type == "required" }) {
                when {
                    dep.versionId != null -> {
                        val dv = version(dep.versionId)
                        if (visited.add(dv.projectId)) result += installVersion(dv, type, cfg, gameDir, visited, onProgress)
                    }
                    dep.projectId != null -> result += install(dep.projectId, type, cfg, gameDir, visited, onProgress)
                }
            }
        }
        return result
    }

    private suspend inline fun <reified T> getJson(url: String, crossinline block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): T {
        val resp = http.get(url) { header(HttpHeaders.UserAgent, userAgent); block() }
        check(resp.status.isSuccess()) { "Modrinth HTTP ${resp.status.value}: $url" }
        return resp.body()
    }

    companion object { const val API = "https://api.modrinth.com/v2" }
}
