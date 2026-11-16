package com.example.mclauncher.ui

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.LinearLayout
import android.view.Gravity
import com.example.mclauncher.launch.LaunchSpec
import com.example.mclauncher.launch.NativeBridge
import com.example.mclauncher.net.Http
import java.io.File

/** Proceso ":installer": corre el instalador oficial de Forge/NeoForge en HotSpot y sale. */
class InstallerActivity : Activity(), NativeBridge.Callbacks {
    private lateinit var resultFile: File
    @Volatile private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val spec = Http.json.decodeFromString(LaunchSpec.serializer(), File(intent.getStringExtra(EXTRA_SPEC)!!).readText())
        resultFile = File(intent.getStringExtra(EXTRA_RESULT)!!)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(ProgressBar(this@InstallerActivity))
            addView(TextView(this@InstallerActivity).apply { text = "Instalando ${spec.mainClass.substringAfterLast('.')}…\nNo cierres la app." })
        })
        NativeBridge.setCallbacks(this)
        Thread(null, {
            val code = NativeBridge.launchJvm(spec.javaHome, spec.libjvm, spec.preloadLibs.toTypedArray(),
                spec.env.map { "${it.key}=${it.value}" }.toTypedArray(), spec.jvmArgs.toTypedArray(),
                spec.mainClass, spec.gameArgs.toTypedArray(), spec.gameDir)
            onGameExited(code)
        }, "ForgeInstaller", 8L * 1024 * 1024).start()
    }

    override fun onRenderThreadStarted(tids: IntArray) = Unit
    override fun onFrameWork(actualNs: Long) = Unit
    override fun setClipboard(text: String) = Unit
    override fun getClipboard(): String = ""
    override fun onGameExited(code: Int) {
        if (reported) return
        reported = true
        resultFile.writeText(code.toString())
        runOnUiThread { finishAndRemoveTask() }
        Thread { Thread.sleep(300); Process.killProcess(Process.myPid()) }.start()
    }

    companion object { const val EXTRA_SPEC = "spec"; const val EXTRA_RESULT = "result" }
}
