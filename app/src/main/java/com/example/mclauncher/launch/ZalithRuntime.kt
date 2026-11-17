package com.example.mclauncher.launch

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Archivos del motor Zalith que no pueden ir en jniLibs (chocarían con los del motor propio):
 *  - natives LWJGL 3.3.6 para Android (liblwjgl*.so) → files/zalith/natives
 *  - lwjgl-glfw-classes.jar (LWJGL 3.3.6 + GLFW en Java de Pojav) → files/zalith/lwjgl3
 *  - MioLibPatcher.jar (agente Java: OSHI, narrador, Sodium…) → files/zalith
 * Se vuelven a copiar cuando cambia el APK.
 */
object ZalithRuntime {
    private const val TAG = "ZalithRuntime"

    fun dir(ctx: Context) = File(ctx.filesDir, "zalith")
    fun nativesDir(ctx: Context) = File(dir(ctx), "natives")
    fun lwjglJar(ctx: Context) = File(dir(ctx), "lwjgl3/lwjgl-glfw-classes.jar")
    fun patcherJar(ctx: Context) = File(dir(ctx), "MioLibPatcher.jar")

    @Synchronized
    fun ensureExtracted(ctx: Context) {
        val stampFile = File(dir(ctx), ".apk_stamp")
        val stamp = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        val natives = ctx.assets.list("zalith/natives").orEmpty()
        val ok = stampFile.takeIf { it.exists() }?.readText() == stamp &&
            lwjglJar(ctx).isFile && patcherJar(ctx).isFile && natives.all { File(nativesDir(ctx), it).isFile }
        if (ok) return

        dir(ctx).deleteRecursively()
        nativesDir(ctx).mkdirs()
        for (n in natives) {
            val out = File(nativesDir(ctx), n)
            copyAsset(ctx, "zalith/natives/$n", out)
            Os.chmod(out.absolutePath, "755".toInt(8))
        }
        copyAsset(ctx, "zalith/lwjgl-glfw-classes.jar", lwjglJar(ctx))
        copyAsset(ctx, "zalith/MioLibPatcher.jar", patcherJar(ctx))
        stampFile.writeText(stamp)
        Log.i(TAG, "Motor Zalith extraído: ${natives.size} natives")
    }

    private fun copyAsset(ctx: Context, path: String, out: File) {
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".tmp")
        ctx.assets.open(path).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
        check(tmp.renameTo(out)) { "No se pudo escribir ${out.name}" }
    }
}
