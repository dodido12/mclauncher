package com.example.mclauncher.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.util.Log
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.example.mclauncher.display.HdrDisplayManager
import com.example.mclauncher.input.GamepadManager
import com.example.mclauncher.input.LwjglBridge
import com.example.mclauncher.input.TouchControlsEngine
import com.example.mclauncher.instance.InstanceConfig
import com.example.mclauncher.instance.InstanceManager
import com.example.mclauncher.launch.LaunchSpec
import com.oracle.dalvik.VMLauncher
import kotlinx.serialization.json.Json
import net.kdt.pojavlaunch.Logger
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge
import java.io.File

/**
 * Motor "Zalith": corre el juego con libpojavexec de ZalithLauncher/PojavLauncher (GPLv3).
 * La JVM arranca con VMLauncher (JLI_Launch), el GLFW es el de Pojav en Java, Mesa OSMesa/Zink
 * dibuja directamente en la ventana y, con HDR, osm_bridge convierte cada cuadro a scRGB FP16.
 * Corre en el proceso ":game" (HotSpot no se puede reiniciar dentro de un proceso).
 */
class ZalithGameActivity : Activity(), SurfaceHolder.Callback, InputManager.InputDeviceListener, Choreographer.FrameCallback {

    private lateinit var cfg: InstanceConfig
    private lateinit var spec: LaunchSpec
    private lateinit var instanceDir: File
    private lateinit var surfaceView: SurfaceView
    private lateinit var touch: TouchControlsEngine
    private var hdr: HdrDisplayManager? = null
    private val gamepads = GamepadManager()
    private var jvmStarted = false
    private var lastFrameNs = 0L
    @Volatile private var exited = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(GameActivity.EXTRA_INSTANCE) ?: return finish()
        val instances = InstanceManager(this)
        instanceDir = instances.instanceDir(id)
        listOf(LaunchLog.EXIT_FILE, LaunchLog.LOG_FILE, LaunchLog.ZALITH_LOG).forEach { File(instanceDir, it).delete() }
        try {
            cfg = instances.load(id)
            spec = Json { ignoreUnknownKeys = true }.decodeFromString(LaunchSpec.serializer(), File(instanceDir, "launch_spec.json").readText())
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo leer la configuración de lanzamiento", e)
            File(instanceDir, LaunchLog.EXIT_FILE).writeText("-100\n$e")
            return finish()
        }

        LwjglBridge.zalith = true
        CallbackBridge.appContext = applicationContext
        // Igual que ZalithLauncher para Minecraft 1.13+: los eventos táctiles/teclado se encolan y
        // el hilo del juego los procesa en cada cuadro (glfwPollEvents). Sin esto los callbacks de
        // GLFW se llaman desde el hilo de Android y Minecraft ignora los clics en los menús.
        CallbackBridge.nativeSetUseInputStackQueue(true)
        JREUtils.exitListener = JREUtils.ExitListener { code, isSignal -> recordExit(code, if (isSignal) "señal $code" else null) }
        // Salida normal (código 0): libpojavexec llama a System.exit(0) de Android
        Runtime.getRuntime().addShutdownHook(Thread { recordExit(0, null) })

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            preferredDisplayModeId = display?.supportedModes?.maxByOrNull { it.refreshRate }?.modeId ?: 0
        }

        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@ZalithGameActivity)
            isFocusable = true; isFocusableInTouchMode = true
        }
        touch = TouchControlsEngine(this, File(instanceDir, "controls.json"))
        setContentView(FrameLayout(this).apply {
            addView(surfaceView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(touch, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        hideSystemBars()

        // HDR: ventana en modo HDR + headroom; el formato FP16/scRGB lo fija libpojavexec (hdr_output.c)
        hdr = HdrDisplayManager(this).also { h ->
            val on = h.enableHdr(cfg.hdr.copy(enabled = spec.hdrEnabled), surfaceView.holder)
            if (on) surfaceView.holder.setFormat(PixelFormat.RGBA_F16)
            Log.i(TAG, "HDR ${if (on) "activo" else "inactivo"}")
        }

        CallbackBridge.grabListener = CallbackBridge.GrabListener { grabbing ->
            runOnUiThread { if (grabbing) surfaceView.requestPointerCapture() else surfaceView.releasePointerCapture() }
        }
        getSystemService(InputManager::class.java).registerInputDeviceListener(this, null)
        // Siempre visibles al inicio: algunos teléfonos (p. ej. Xiaomi) reportan sensores o
        // botones internos como "joystick". Se ocultan solo cuando de verdad se usa un mando.
        touch.visibility = View.VISIBLE
        Log.i("Input", "Mandos detectados: " + gamepads.connectedGamepads().joinToString { "${it.name} (${it.id})" })
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------- Superficie ----------------
    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        CallbackBridge.physicalWidth = width; CallbackBridge.physicalHeight = height
        CallbackBridge.windowWidth = width; CallbackBridge.windowHeight = height
        JREUtils.setupBridgeWindow(holder.surface)
        if (!jvmStarted) { jvmStarted = true; startJvm() }
        else CallbackBridge.sendUpdateWindowSize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

    private fun startJvm() {
        Thread(null, {
            val code = try {
                // stdout/stderr de la JVM a archivo (el registro no debe impedir que el juego abra)
                runCatching {
                    val logFile = File(instanceDir, LaunchLog.ZALITH_LOG).apply { parentFile?.mkdirs(); createNewFile() }
                    Logger.begin(logFile.absolutePath)
                }.onFailure { Log.w(TAG, "No se pudo abrir el registro de la JVM: $it") }
                for ((k, v) in spec.env) Os.setenv(k, v, true)
                JREUtils.setLdLibraryPath(spec.ldLibraryPath)
                for (lib in spec.preloadLibs) if (!JREUtils.dlopen(lib)) Log.w(TAG, "dlopen falló: $lib")
                JREUtils.setupExitMethod(applicationContext)
                JREUtils.initializeGameExitHook()
                JREUtils.chdir(spec.gameDir)
                val args = buildList {
                    add("java")
                    addAll(spec.jvmArgs)
                    add(spec.mainClass)
                    addAll(spec.gameArgs)
                }
                Log.i(TAG, "Lanzando JVM con ${args.size} argumentos (motor Zalith)")
                VMLauncher.launchJVM(args.toTypedArray())
            } catch (t: Throwable) {
                Log.e(TAG, "Error lanzando la JVM", t)
                recordExit(-200, Log.getStackTraceString(t))
                -200
            }
            recordExit(code, null)
            runOnUiThread { finishAndRemoveTask() }
            Thread.sleep(500)
            Process.killProcess(Process.myPid())
        }, "MinecraftMain", 8L * 1024 * 1024).start()
    }

    /** Guarda código de salida y logcat para el botón "Registro" del launcher (una sola vez). */
    private fun recordExit(code: Int, detail: String?) {
        if (exited) return
        exited = true
        Log.i(TAG, "Minecraft terminó con código $code")
        runCatching {
            File(instanceDir, LaunchLog.EXIT_FILE).writeText(code.toString() + (detail?.let { "\n$it" } ?: ""))
            File(instanceDir, LaunchLog.LOG_FILE).writeText(LaunchLog.readLogcat())
        }
    }

    // ---------------- Mandos (sin callback de cuadro del juego: usamos Choreographer) ----------------
    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameNs != 0L) gamepads.tick(((frameTimeNanos - lastFrameNs) / 1e9f).coerceAtMost(0.1f))
        lastFrameNs = frameTimeNanos
        if (!isFinishing) Choreographer.getInstance().postFrameCallback(this)
    }

    // ---------------- Entrada ----------------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {   // Atrás = Esc
            if (event.repeatCount == 0) LwjglBridge.sendKey(256, 0, if (event.action == KeyEvent.ACTION_DOWN) LwjglBridge.PRESS else LwjglBridge.RELEASE, 0)
            return true
        }
        if (gamepads.onKeyEvent(event)) { touch.visibility = View.GONE; return true }
        val glfw = AndroidKeyMap.toGlfw(event.keyCode)
        if (glfw != null && event.repeatCount == 0) {
            LwjglBridge.sendKey(glfw, event.scanCode, if (event.action == KeyEvent.ACTION_DOWN) LwjglBridge.PRESS else LwjglBridge.RELEASE, AndroidKeyMap.mods(event))
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.unicodeChar > 0 && !event.isCtrlPressed) LwjglBridge.sendChar(event.unicodeChar, AndroidKeyMap.mods(event))
        return glfw != null || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (gamepads.onGenericMotion(ev)) { touch.visibility = View.GONE; return true }
        if (ev.isFromSource(android.view.InputDevice.SOURCE_MOUSE) || ev.isFromSource(android.view.InputDevice.SOURCE_MOUSE_RELATIVE)) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_SCROLL -> LwjglBridge.sendScroll(ev.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble(), ev.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble())
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE ->
                    if (LwjglBridge.isCursorGrabbed()) LwjglBridge.sendCursorDelta(ev.getAxisValue(MotionEvent.AXIS_RELATIVE_X).toDouble(), ev.getAxisValue(MotionEvent.AXIS_RELATIVE_Y).toDouble())
                    else LwjglBridge.sendCursorPos(ev.x.toDouble(), ev.y.toDouble())
                MotionEvent.ACTION_BUTTON_PRESS -> LwjglBridge.sendMouseButton(mouseBtn(ev.actionButton), LwjglBridge.PRESS, 0)
                MotionEvent.ACTION_BUTTON_RELEASE -> LwjglBridge.sendMouseButton(mouseBtn(ev.actionButton), LwjglBridge.RELEASE, 0)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun mouseBtn(b: Int) = when (b) {
        MotionEvent.BUTTON_SECONDARY -> LwjglBridge.MOUSE_RIGHT
        MotionEvent.BUTTON_TERTIARY -> LwjglBridge.MOUSE_MIDDLE
        else -> LwjglBridge.MOUSE_LEFT
    }

    override fun onInputDeviceAdded(deviceId: Int) = Unit

    /** Tocar la pantalla vuelve a mostrar los controles táctiles si un mando los había ocultado. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (touch.visibility != View.VISIBLE && ev.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)) touch.visibility = View.VISIBLE
        return super.dispatchTouchEvent(ev)
    }
    override fun onInputDeviceRemoved(deviceId: Int) {
        gamepads.onDeviceRemoved(deviceId)
        touch.visibility = View.VISIBLE
    }
    override fun onInputDeviceChanged(deviceId: Int) = Unit

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { hideSystemBars(); if (CallbackBridge.isGrabbing()) surfaceView.requestPointerCapture() }
    }

    override fun onDestroy() {
        Choreographer.getInstance().removeFrameCallback(this)
        hdr?.disableHdr()
        if (::surfaceView.isInitialized) getSystemService(InputManager::class.java).unregisterInputDeviceListener(this)
        super.onDestroy()
    }

    companion object { private const val TAG = "ZalithGameActivity" }
}
