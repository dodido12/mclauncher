package com.example.mclauncher.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File
import java.security.MessageDigest

object Http {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) { connectTimeoutMillis = 15_000; requestTimeoutMillis = 10 * 60_000; socketTimeoutMillis = 60_000 }
            install(HttpRequestRetry) { retryOnExceptionOrServerErrors(maxRetries = 3); exponentialDelay() }
            engine { config { retryOnConnectionFailure(true) } }
        }
    }
}

/** Descarga a un .part, verifica hash (sha1/sha512) y renombra atómicamente. */
suspend fun HttpClient.downloadVerified(
    url: String,
    dest: File,
    expectedHash: String?,
    algorithm: String = "SHA-1",
    onBytes: (Long) -> Unit = {}
): File = withContext(Dispatchers.IO) {
    if (dest.exists() && expectedHash != null && hashOf(dest, algorithm).equals(expectedHash, true)) return@withContext dest
    dest.parentFile?.mkdirs()
    val part = File(dest.path + ".part")
    val md = MessageDigest.getInstance(algorithm)
    prepareGet(url).execute { resp ->
        check(resp.status.isSuccess()) { "HTTP ${resp.status.value} en $url" }
        val ch = resp.bodyAsChannel()
        part.outputStream().use { out ->
            val buf = ByteArray(1 shl 16)
            while (!ch.isClosedForRead) {
                val n = ch.readAvailable(buf, 0, buf.size)
                if (n <= 0) continue
                out.write(buf, 0, n); md.update(buf, 0, n); onBytes(n.toLong())
            }
        }
    }
    val got = md.digest().toHex()
    if (expectedHash != null && !got.equals(expectedHash, true)) {
        part.delete(); error("Hash $algorithm inválido para ${dest.name}: esperado $expectedHash, obtenido $got")
    }
    if (!part.renameTo(dest)) { dest.delete(); check(part.renameTo(dest)) { "No se pudo mover ${part.name}" } }
    dest
}

fun hashOf(f: File, algorithm: String): String {
    val md = MessageDigest.getInstance(algorithm)
    f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
    return md.digest().toHex()
}

fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
