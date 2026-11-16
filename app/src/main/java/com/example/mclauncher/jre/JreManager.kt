package com.example.mclauncher.jre

import android.content.Context
import android.system.Os
import android.util.Log
import com.example.mclauncher.instance.InstanceConfig
import com.example.mclauncher.instance.JavaRuntime
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Instala y selecciona runtimes OpenJDK compilados para Android/bionic aarch64.
 *
 * Fuentes, en orden:
 *   1. assets/runtimes/jre-<N>-aarch64.tar.xz (si decides empaquetarlo en el APK)
 *   2. Repositorio oficial de Termux: openjdk-17 / openjdk-21 / openjdk-25 (GPLv2 + Classpath
 *      Exception), verificados con el SHA-256 del índice "Packages". Nada se incluye en el APK.
 *
 * Disposición: files/runtimes/java-<N>/{bin,conf,lib,release,...} y java-<N>/deps/ (librerías .so de Termux)
 */
class JreManager(private val context: Context, private val http: HttpClient) {

    val runtimesDir = File(context.filesDir, "runtimes").apply { mkdirs() }

    /** Java 8 no tiene build de Termux: Minecraft 1.13–1.16 funciona bien sobre Java 17. */
    fun effective(rt: JavaRuntime): JavaRuntime =
        if (rt == JavaRuntime.JAVA_8 && !hasAsset(rt)) JavaRuntime.JAVA_17 else rt

    private fun hasAsset(rt: JavaRuntime) =
        runCatching { context.assets.open("runtimes/jre-${rt.major}-aarch64.tar.xz").close() }.isSuccess

    fun homeOf(rt: JavaRuntime) = File(runtimesDir, "java-${effective(rt).major}")
    fun isInstalled(rt: JavaRuntime) = libjvmOf(rt)?.exists() == true && File(homeOf(rt), ".installed").exists()

    /** JDK 9+: lib/server/libjvm.so — JDK 8: lib/aarch64/server/libjvm.so */
    fun libjvmOf(rt: JavaRuntime): File? = listOf(
        File(homeOf(rt), "lib/server/libjvm.so"),
        File(homeOf(rt), "lib/aarch64/server/libjvm.so"),
        File(homeOf(rt), "jre/lib/aarch64/server/libjvm.so")
    ).firstOrNull { it.exists() }

    /** Dependencias de Termux que deben cargarse ANTES de libjvm (libandroid-spawn, libz.so.1…). */
    fun depLibsOf(rt: JavaRuntime): List<File> {
        val d = File(homeOf(rt), "deps")
        return listOf("libandroid-spawn.so", "libandroid-shmem.so", "libz.so.1", "libiconv.so", "liblcms2.so", "libjpeg.so.8")
            .map { File(d, it) }.filter { it.exists() }
    }

    suspend fun ensureInstalled(requested: JavaRuntime, onProgress: (String) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val rt = effective(requested)
        if (isInstalled(rt)) { ensureCacerts(homeOf(rt)); return@withContext homeOf(rt) }
        if (hasAsset(rt)) {
            onProgress("Extrayendo Java ${rt.major} incluido…")
            val home = homeOf(rt).apply { deleteRecursively(); mkdirs() }
            context.assets.open("runtimes/jre-${rt.major}-aarch64.tar.xz").use { raw ->
                TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(raw, 1 shl 16))).use { tar ->
                    while (true) {
                        val e = tar.nextEntry ?: break
                        val rel = e.name.substringAfter('/', e.name)
                        if (rel.isNotEmpty()) writeEntry(tar, e, home, rel)
                    }
                }
            }
        } else {
            installFromTermux(rt, onProgress)
        }
        postInstall(rt)
        ensureCacerts(homeOf(rt))
        homeOf(rt)
    }

    /**
     * El JDK de Termux trae lib/security/cacerts como enlace a
     * /data/data/com.termux/files/usr/etc/... que aquí no existe: sin él, todo HTTPS dentro
     * de la JVM (instalador de Forge, skins, Realms) falla con "trustAnchors must be non-empty".
     * Se genera un keystore JKS con los certificados raíz del sistema Android.
     */
    private fun ensureCacerts(home: File) {
        val dest = File(home, "lib/security/cacerts")
        if (dest.isFile && dest.length() > 1024) return     // existe y no es un enlace roto
        runCatching {
            val cf = java.security.cert.CertificateFactory.getInstance("X.509")
            val certs = LinkedHashMap<String, ByteArray>()
            for (dir in listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")) {
                File(dir).listFiles()?.sortedBy { it.name }?.forEach { f ->
                    if (f.name in certs) return@forEach
                    runCatching { f.inputStream().use { cf.generateCertificate(it).encoded } }.getOrNull()?.let { certs[f.name] = it }
                }
            }
            check(certs.isNotEmpty()) { "sin certificados del sistema" }
            dest.parentFile?.mkdirs()
            runCatching { Os.remove(dest.absolutePath) }          // borra también el enlace roto
            dest.writeBytes(buildJks(certs, "changeit"))
            Log.i(TAG, "cacerts generado con ${certs.size} certificados en $dest")
        }.onFailure { Log.w(TAG, "No se pudo generar cacerts: $it") }
    }

    /** Formato JKS (solo entradas "trusted certificate"), legible por cualquier OpenJDK. */
    private fun buildJks(certs: Map<String, ByteArray>, password: String): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(bos)
        out.writeInt(0xFEEDFEED.toInt()); out.writeInt(2); out.writeInt(certs.size)
        val now = System.currentTimeMillis()
        for ((alias, der) in certs) {
            out.writeInt(2)                         // TRUSTED_CERT_ENTRY
            out.writeUTF(alias.lowercase())
            out.writeLong(now)
            out.writeUTF("X.509")
            out.writeInt(der.size); out.write(der)
        }
        out.flush()
        val body = bos.toByteArray()
        val md = MessageDigest.getInstance("SHA-1")
        for (c in password) { md.update((c.code shr 8).toByte()); md.update(c.code.toByte()) }
        md.update("Mighty Aphrodite".toByteArray(Charsets.UTF_8))
        md.update(body)
        return body + md.digest()
    }

    // ---------------------------------------------------------------- Termux
    private data class DebPkg(val name: String, val version: String, val filename: String, val sha256: String)

    private suspend fun termuxIndex(): Map<String, DebPkg> {
        val text = http.prepareGet("$TERMUX_REPO/dists/stable/main/binary-aarch64/Packages")
            .execute { it.bodyAsChannel().toInputStream().bufferedReader().readText() }
        val out = HashMap<String, DebPkg>()
        for (stanza in text.split("\n\n")) {
            val f = stanza.lines().filter { ':' in it }.associate { it.substringBefore(':') to it.substringAfter(':').trim() }
            val name = f["Package"] ?: continue
            val file = f["Filename"] ?: continue
            val sha = f["SHA256"] ?: continue
            out[name] = DebPkg(name, f["Version"] ?: "", file, sha)
        }
        return out
    }

    private suspend fun installFromTermux(rt: JavaRuntime, onProgress: (String) -> Unit) {
        val home = homeOf(rt).apply { deleteRecursively(); mkdirs() }
        val deps = File(home, "deps").apply { mkdirs() }
        onProgress("Consultando el repositorio de Termux…")
        val index = termuxIndex()
        val jdkPkg = "openjdk-${rt.major}"
        val jdkPrefix = "./data/data/com.termux/files/usr/lib/jvm/java-${rt.major}-openjdk/"
        val libPrefix = "./data/data/com.termux/files/usr/lib/"
        for (name in listOf(jdkPkg) + TERMUX_DEPS) {
            val pkg = index[name] ?: error("El repositorio de Termux no tiene $name")
            onProgress("Descargando ${pkg.name} ${pkg.version}…")
            val deb = File(context.cacheDir, pkg.filename.substringAfterLast('/'))
            http.prepareGet("$TERMUX_REPO/${pkg.filename}").execute { resp ->
                resp.bodyAsChannel().toInputStream().use { inp -> deb.outputStream().use { inp.copyTo(it, 1 shl 16) } }
            }
            check(sha256(deb).equals(pkg.sha256, ignoreCase = true)) { "SHA-256 inválido en ${pkg.name}" }
            onProgress("Extrayendo ${pkg.name}…")
            deb.inputStream().buffered(1 shl 16).use { input ->
                val data = arMember(input, "data.tar.xz") ?: error("${pkg.name}: no contiene data.tar.xz")
                TarArchiveInputStream(XZCompressorInputStream(data)).use { tar ->
                    while (true) {
                        val e = tar.nextEntry ?: break
                        val n = e.name
                        if (name == jdkPkg && n.startsWith(jdkPrefix)) {
                            val rel = n.removePrefix(jdkPrefix)
                            if (rel.isEmpty() || SKIP_JDK.any { rel == it || rel.startsWith("$it/") }) continue
                            writeEntry(tar, e, home, rel)
                        } else if (name != jdkPkg && n.startsWith(libPrefix)) {
                            val rel = n.removePrefix(libPrefix)
                            if ('/' in rel || ".so" !in rel) continue
                            writeEntry(tar, e, deps, rel)
                        }
                    }
                }
            }
            deb.delete()
        }
    }

    /** Recorre un archivo ar (.deb) y devuelve un stream limitado al miembro pedido. */
    private fun arMember(input: InputStream, wanted: String): InputStream? {
        val magic = ByteArray(8); readFully(input, magic)
        require(String(magic, Charsets.US_ASCII) == "!<arch>\n") { "No es un .deb válido" }
        val hdr = ByteArray(60)
        while (true) {
            var first = input.read()
            if (first == '\n'.code) first = input.read()   // relleno a tamaño par
            if (first < 0) return null
            hdr[0] = first.toByte()
            readFully(input, hdr, 1, 59)
            val name = String(hdr, 0, 16, Charsets.US_ASCII).trim().trimEnd('/')
            val size = String(hdr, 48, 10, Charsets.US_ASCII).trim().toLong()
            if (name == wanted) return BoundedInputStream(input, size)
            var left = size
            while (left > 0) { val k = input.skip(left); if (k <= 0) error("deb truncado"); left -= k }
        }
    }

    private class BoundedInputStream(private val inp: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int = if (left <= 0) -1 else inp.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inp.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    private fun readFully(i: InputStream, b: ByteArray, off: Int = 0, len: Int = b.size) {
        var o = off; var l = len
        while (l > 0) { val n = i.read(b, o, l); if (n < 0) error("EOF inesperado"); o += n; l -= n }
    }

    private fun writeEntry(tar: TarArchiveInputStream, e: TarArchiveEntry, dest: File, rel: String) {
        val out = File(dest, rel)
        require(out.canonicalPath.startsWith(dest.canonicalPath + File.separator)) { "Entrada tar fuera del destino: ${e.name}" }
        when {
            e.isDirectory -> out.mkdirs()
            e.isSymbolicLink -> {
                out.parentFile?.mkdirs(); out.delete()
                Os.symlink(e.linkName, out.absolutePath)
            }
            else -> {
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                // Termux empaqueta con 0600: el dueño necesita r+x para exec/dlopen
                Os.chmod(out.absolutePath, (e.mode and 0b111_111_111) or 0b111_000_000)
            }
        }
    }

    private fun postInstall(rt: JavaRuntime) {
        val home = homeOf(rt)
        checkNotNull(libjvmOf(rt)) { "libjvm.so no encontrado en ${home.absolutePath}" }
        File(home, ".installed").writeText(readReleaseVersion(home) ?: rt.major.toString())
        Log.i(TAG, "Java ${rt.major} instalado en $home (${readReleaseVersion(home)})")
    }

    fun readReleaseVersion(home: File): String? = File(home, "release").takeIf { it.exists() }
        ?.readLines()?.firstOrNull { it.startsWith("JAVA_VERSION=") }
        ?.substringAfter('=')?.trim('"')

    /**
     * Flags de la JVM ajustados por versión.
     * Java 25: G1 con pausas cortas, cabeceras compactas (JEP 519, producto en 25),
     * Vector API (incubadora) y Virtual Threads (estándar desde 21, sin flag).
     */
    fun jvmFlags(rt: JavaRuntime, cfg: InstanceConfig, cpuCores: Int = Runtime.getRuntime().availableProcessors()): List<String> {
        val f = mutableListOf(
            "-Xms${(cfg.ramMb / 2).coerceAtLeast(256)}M",
            "-Xmx${cfg.ramMb}M",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=37",
            "-XX:+ParallelRefProcEnabled",
            "-XX:G1HeapRegionSize=8M",
            "-XX:-UseTransparentHugePages",   // en Android THP suele estar desactivado o causar picos
            "-XX:ReservedCodeCacheSize=240M",
            "-XX:ParallelGCThreads=${(cpuCores - 2).coerceAtLeast(2)}",
            "-XX:ConcGCThreads=${(cpuCores / 4).coerceAtLeast(1)}",
            "-Dfile.encoding=UTF-8",
            "-Djava.awt.headless=true",
            "-Dos.name=Linux",
            "-Dos.arch=aarch64",
            "-XX:-UsePerfData"   // sin hsperfdata en /tmp (no existe en Android)
        )
        if (rt.major >= 17) {
            f += "-XX:+UnlockExperimentalVMOptions"
            f += listOf("-XX:G1NewSizePercent=20", "-XX:G1ReservePercent=20", "-XX:G1MixedGCLiveThresholdPercent=90")
        }
        if (rt.major >= 21) {
            // Virtual threads son estándar; limitamos su scheduler a los núcleos grandes.
            f += "-Djdk.virtualThreadScheduler.parallelism=${(cpuCores - 2).coerceAtLeast(2)}"
            f += "--enable-native-access=ALL-UNNAMED"
        }
        if (rt.major >= 25) {
            f += "-XX:+UseCompactObjectHeaders"         // JEP 519: ~10-20% menos heap en objetos pequeños
            f += "--add-modules=jdk.incubator.vector"   // Vector API (NEON/SVE en aarch64)
            f += "-XX:+UseStringDeduplication"
        }
        if (rt.major == 8) {
            f += "-XX:+UnlockExperimentalVMOptions"
            f += "-XX:G1NewSizePercent=20"
        }
        return f + cfg.extraJvmArgs
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "JreManager"
        const val TERMUX_REPO = "https://packages.termux.dev/apt/termux-main"
        private val TERMUX_DEPS = listOf("libandroid-spawn", "libandroid-shmem", "zlib", "libiconv", "littlecms", "libjpeg-turbo")
        private val SKIP_JDK = listOf("include", "jmods", "demo", "man", "lib/src.zip", "lib/ct.sym")
    }
}
