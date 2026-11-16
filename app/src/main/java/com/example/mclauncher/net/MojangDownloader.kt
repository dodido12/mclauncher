package com.example.mclauncher.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Descarga oficial y verificada desde los servidores de Mojang.
 * El APK no contiene NINGÚN archivo de Mojang: todo se baja en el dispositivo del usuario.
 */
class MojangDownloader(private val http: HttpClient, private val shared: File) {

    val versionsDir = File(shared, "versions")
    val librariesDir = File(shared, "libraries")
    val assetsDir = File(shared, "assets")

    data class Progress(val stage: String, val done: Long, val total: Long)

    data class ManifestEntry(val id: String, val type: String, val url: String, val sha1: String, val releaseTime: String)

    suspend fun manifest(): List<ManifestEntry> {
        val root: JsonObject = http.get(MANIFEST_URL).body()
        return root["versions"]!!.jsonArray.map {
            val o = it.jsonObject
            ManifestEntry(o.str("id")!!, o.str("type")!!, o.str("url")!!, o.str("sha1")!!, o.str("releaseTime")!!)
        }
    }

    /** Descarga (o reutiliza) versions/<id>/<id>.json verificado por SHA-1 del manifiesto. */
    suspend fun versionJson(id: String): JsonObject = withContext(Dispatchers.IO) {
        val file = File(versionsDir, "$id/$id.json")
        if (!file.exists()) {
            val e = manifest().firstOrNull { it.id == id } ?: error("Versión $id no existe en el manifiesto")
            http.downloadVerified(e.url, file, e.sha1)
        }
        Http.json.parseToJsonElement(file.readText()).jsonObject
    }

    /** Descarga client.jar, librerías (según reglas para linux/arm64), índice de assets y objetos. */
    suspend fun installVersion(version: JsonObject, onProgress: (Progress) -> Unit) = coroutineScope {
        val id = version.str("id")!!
        val sem = Semaphore(8)
        val jobs = mutableListOf<Pair<String, suspend () -> Unit>>()
        fun job(name: String, block: suspend () -> Unit) { jobs += name to block }

        // 1) client.jar
        version["downloads"]?.jsonObject?.get("client")?.jsonObject?.let { c ->
            val f = File(versionsDir, "$id/$id.jar")
            job("client") { http.downloadVerified(c.str("url")!!, f, c.str("sha1")) }
        }

        // 2) librerías
        for (lib in version["libraries"]?.jsonArray.orEmpty().map { it.jsonObject }) {
            if (!rulesAllow(lib["rules"])) continue
            val art = lib["downloads"]?.jsonObject?.get("artifact")?.jsonObject
            if (art != null && art.str("url").isNullOrBlank()) {
                // Artefactos generados localmente por el instalador de Forge (url vacía)
                continue
            } else if (art != null) {
                val path = art.str("path")!!
                job(path) { http.downloadVerified(art.str("url")!!, File(librariesDir, path), art.str("sha1")) }
            } else if (lib.str("url") != null) {
                // Formato Maven (Fabric/Quilt): name + url base, sin sha1 en algunos perfiles
                val path = mavenPath(lib.str("name")!!)
                job(path) { http.downloadVerified(lib.str("url")!!.trimEnd('/') + "/" + path, File(librariesDir, path), lib.str("sha1")) }
            }
            // Natives "classifiers" de LWJGL (linux x86_64) se IGNORAN: en Android se usan
            // los natives de LWJGL compilados para aarch64-bionic que trae el APK (jniLibs).
        }

        // 3) logging config (log4j2 xml)
        version["logging"]?.jsonObject?.get("client")?.jsonObject?.get("file")?.jsonObject?.let { lf ->
            val f = File(assetsDir, "log_configs/${lf.str("id")}")
            job("log_config") { http.downloadVerified(lf.str("url")!!, f, lf.str("sha1")) }
        }

        val total = jobs.size.toLong(); val done = AtomicLong()
        jobs.map { (name, job) ->
            async(Dispatchers.IO) { sem.withPermit { job() }; onProgress(Progress("Archivos: $name", done.incrementAndGet(), total)) }
        }.awaitAll()

        // 4) assets
        version["assetIndex"]?.jsonObject?.let { ai -> installAssets(ai, onProgress) }
    }

    private suspend fun installAssets(assetIndex: JsonObject, onProgress: (Progress) -> Unit) = coroutineScope {
        val indexId = assetIndex.str("id")!!
        val indexFile = File(assetsDir, "indexes/$indexId.json")
        http.downloadVerified(assetIndex.str("url")!!, indexFile, assetIndex.str("sha1"))
        val objects = Http.json.parseToJsonElement(indexFile.readText()).jsonObject["objects"]!!.jsonObject
        val isLegacy = indexId == "legacy" || indexId == "pre-1.6"
        val sem = Semaphore(16); val done = AtomicLong(); val total = objects.size.toLong()
        objects.entries.map { (name, v) ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val hash = v.jsonObject.str("hash")!!
                    val obj = File(assetsDir, "objects/${hash.take(2)}/$hash")
                    http.downloadVerified("$RESOURCES_URL/${hash.take(2)}/$hash", obj, hash)
                    if (isLegacy) obj.copyTo(File(assetsDir, "virtual/legacy/$name"), overwrite = true)
                }
                val d = done.incrementAndGet()
                if (d % 50 == 0L || d == total) onProgress(Progress("Assets", d, total))
            }
        }.awaitAll()
    }

    /** Evalúa "rules" del version.json fingiendo ser Linux aarch64 (lo que es Android a nivel de JVM). */
    fun rulesAllow(rules: JsonElement?, features: Map<String, Boolean> = emptyMap()): Boolean {
        val arr = rules as? JsonArray ?: return true
        var allowed = false
        for (r in arr.map { it.jsonObject }) {
            val action = r.str("action") == "allow"
            val os = r["os"]?.jsonObject
            val osMatch = os == null || (
                (os.str("name") == null || os.str("name") == "linux") &&
                (os.str("arch") == null || os.str("arch") in setOf("arm64", "aarch64")))
            val feats = r["features"]?.jsonObject
            val featMatch = feats == null || feats.all { (k, v) -> (features[k] ?: false) == v.jsonPrimitive.booleanOrNull }
            if (osMatch && featMatch) allowed = action
        }
        return allowed
    }

    companion object {
        const val MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
        const val RESOURCES_URL = "https://resources.download.minecraft.net"

        /** "group:artifact:version[:classifier]" → ruta Maven relativa. */
        fun mavenPath(coord: String): String {
            val parts = coord.split(':')
            val (g, a, v) = Triple(parts[0], parts[1], parts[2])
            val classifier = parts.getOrNull(3)?.let { "-$it" } ?: ""
            return "${g.replace('.', '/')}/$a/$v/$a-$v$classifier.jar"
        }
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
