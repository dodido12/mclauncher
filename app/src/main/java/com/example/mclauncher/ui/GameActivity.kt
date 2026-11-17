package com.example.mclauncher.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.example.mclauncher.display.FramePacer
import com.example.mclauncher.display.HdrDisplayManager
import com.example.mclauncher.input.GamepadManager
import com.example.mclauncher.input.LwjglBridge
import com.example.mclauncher.input.TouchControlsEngine
import com.example.mclauncher.instance.InstanceConfig
import com.example.mclauncher.instance.InstanceManager
import com.example.mclauncher.launch.LaunchSpec
import com.example.mclauncher.launch.NativeBridge
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Corre en el proceso ":game". Crea la superficie, negocia HDR, monta controles y
 * arranca HotSpot en un hilo propio cuando la superficie está lista.
 */
class GameActivity : Activity(), SurfaceHolder.Callback, NativeBridge.Callbacks, InputManager.InputDeviceListener {

    private lateinit var cfg: InstanceConfig
    private lateinit var spec: LaunchSpec
    private lateinit var surfaceView: SurfaceView
    private lateinit var touch: TouchControlsEngine
    private lateinit var hdr: HdrDisplayManager
    private lateinit var pacer: FramePacer
    private val gamepads = GamepadManager()
    private var jvmStarted = false
    private lateinit var instanceDir: File
    @Volatile private var exited = false
    private var lastVsync = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_INSTANCE) ?: return finish()
        val instances = InstanceManager(this)
        instanceDir = instances.instanceDir(id)
        File(instanceDir, LaunchLog.EXIT_FILE).delete()
        File(instanceDir, LaunchLog.LOG_FILE).delete()
        try {
            cfg = instances.load(id)
            spec = Json { ignoreUnknownKeys = true }.decodeFromString(LaunchSpec.serializer(), File(instanceDir, "launch_spec.json").readText())
        } catch (e: Exception) {
            Log.e("GameActivity", "No se pudo leer la configuración de lanzamiento", e)
            File(instanceDir, LaunchLog.EXIT_FILE).writeText("-100\n${e}")
            return finish()
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Pide el modo de mayor refresco; FramePacer afina con Surface.setFrameRate
            preferredDisplayModeId = display?.supportedModes?.maxByOrNull { it.refreshRate }?.modeId ?: 0
        }

        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@GameActivity)
            isFocusable = true; isFocusableInTouchMode = true
        }
        touch = TouchControlsEngine(this, File(instances.instanceDir(id), "controls.json"))
        setContentView(FrameLayout(this).apply {
            addView(surfaceView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(touch, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        hideSystemBars()

        hdr = HdrDisplayManager(this)
        hdr.enableHdr(cfg.hdr.copy(enabled = spec.hdrEnabled), surfaceView.holder)
        pacer = FramePacer(this, surfaceView, cfg.display)
        NativeBridge.setCallbacks(this)
        getSystemService(InputManager::class.java).registerInputDeviceListener(this, null)
        // Siempre visibles al inicio: algunos teléfonos (p. ej. Xiaomi) reportan sensores o
        // botones internos como "joystick". Se ocultan solo cuando de verdad se usa un mando.
        touch.visibility = View.VISIBLE
        Log.i("Input", "Mandos detectados: " + gamepads.connectedGamepads().joinToString { "${it.name} (${it.id})" })
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------- Surface ----------------
    override fun surfaceCreated(holder: SurfaceHolder) { pacer.onSurfaceCreated(holder) }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NativeBridge.setSurface(holder.surface, spec.renderer, width, height)
        pacer.start()
        if (!jvmStarted) { jvmStarted = true; startJvm() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pacer.stop()
        NativeBridge.setSurface(null, spec.renderer, 0, 0)   // el render espera hasta tener superficie
    }

    private fun startJvm() {
        // Pila de 8 MB: el hilo principal de Minecraft necesita bastante más que el default de Android
        Thread(null, {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            val code = NativeBridge.launchJvm(
                spec.javaHome, spec.libjvm, spec.preloadLibs.toTypedArray(),
                spec.env.map { "${it.key}=${it.value}" }.toTypedArray(),
                spec.jvmArgs.toTypedArray(), spec.mainClass, spec.gameArgs.toTypedArray(), spec.gameDir
            )
            onGameExited(code)
        }, "MinecraftMain", 8L * 1024 * 1024).start()
    }

    // ---------------- Callbacks nativos ----------------
    override fun onRenderThreadStarted(tids: IntArray) = pacer.attachRenderThreads(tids)
    override fun onFrameWork(actualNs: Long) {
        pacer.reportFrameWork(actualNs)
        val now = System.nanoTime()
        if (lastVsync != 0L) gamepads.tick(((now - lastVsync) / 1e9f).coerceAtMost(0.1f))
        lastVsync = now
    }
    override fun onGameExited(code: Int) {
        if (exited) return
        exited = true
        Log.i("GameActivity", "Minecraft terminó con código $code")
        // Guardar el registro ANTES de matar el proceso: el launcher lo muestra en "Registro".
        runCatching {
            File(instanceDir, LaunchLog.EXIT_FILE).writeText(code.toString())
            File(instanceDir, LaunchLog.LOG_FILE).writeText(LaunchLog.readLogcat())
        }
        runOnUiThread { finishAndRemoveTask() }
        // HotSpot no se puede reiniciar en el mismo proceso: cerramos :game por completo.
        Thread { Thread.sleep(500); Process.killProcess(Process.myPid()) }.start()
    }

    override fun setClipboard(text: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        runOnUiThread { cm.setPrimaryClip(android.content.ClipData.newPlainText("Minecraft", text)) }
    }
    override fun getClipboard(): String {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
    }

    // ---------------- Entrada ----------------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Atrás = Esc (menú de pausa). Sin esto Android cerraba la actividad y el juego
        // seguía corriendo sin superficie (pantalla negra al volver).
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.repeatCount == 0) LwjglBridge.sendKey(256, 0, if (event.action == KeyEvent.ACTION_DOWN) LwjglBridge.PRESS else LwjglBridge.RELEASE, 0)
            return true
        }
        if (gamepads.onKeyEvent(event)) { touch.visibility = View.GONE; return true }
        val glfw = AndroidKeyMap.toGlfw(event.keyCode)
        if (glfw != null && event.repeatCount == 0) {
            LwjglBridge.sendKey(glfw, event.scanCode, if (event.action == KeyEvent.ACTION_DOWN) LwjglBridge.PRESS else LwjglBridge.RELEASE, AndroidKeyMap.mods(event))
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.unicodeChar > 0 && !event.isCtrlPressed) LwjglBridge.sendChar(event.unicodeChar, 0)
        return glfw != null || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (gamepads.onGenericMotion(ev)) { touch.visibility = View.GONE; return true }
        // Mouse físico (Bluetooth/USB/DeX) con captura de puntero
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
        if (hasFocus) { hideSystemBars(); if (LwjglBridge.isCursorGrabbed()) surfaceView.requestPointerCapture() }
    }

    override fun onDestroy() {
        if (::pacer.isInitialized) {
            pacer.release(); hdr.disableHdr()
            getSystemService(InputManager::class.java).unregisterInputDeviceListener(this)
        }
        super.onDestroy()
    }

    companion object { const val EXTRA_INSTANCE = "instance_id" }
}

/** KeyEvent de Android → código de tecla GLFW. */
object AndroidKeyMap {
    private val table: Map<Int, Int> = buildMap {
        for (i in 0..25) put(KeyEvent.KEYCODE_A + i, 65 + i)
        for (i in 0..9) put(KeyEvent.KEYCODE_0 + i, 48 + i)
        for (i in 0..11) put(KeyEvent.KEYCODE_F1 + i, 290 + i)
        put(KeyEvent.KEYCODE_SPACE, 32); put(KeyEvent.KEYCODE_ENTER, 257); put(KeyEvent.KEYCODE_ESCAPE, 256)
        put(KeyEvent.KEYCODE_TAB, 258); put(KeyEvent.KEYCODE_DEL, 259); put(KeyEvent.KEYCODE_FORWARD_DEL, 261)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 262); put(KeyEvent.KEYCODE_DPAD_LEFT, 263); put(KeyEvent.KEYCODE_DPAD_DOWN, 264); put(KeyEvent.KEYCODE_DPAD_UP, 265)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, 340); put(KeyEvent.KEYCODE_CTRL_LEFT, 341); put(KeyEvent.KEYCODE_ALT_LEFT, 342)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, 344); put(KeyEvent.KEYCODE_CTRL_RIGHT, 345); put(KeyEvent.KEYCODE_ALT_RIGHT, 346)
        put(KeyEvent.KEYCODE_MINUS, 45); put(KeyEvent.KEYCODE_EQUALS, 61); put(KeyEvent.KEYCODE_LEFT_BRACKET, 91); put(KeyEvent.KEYCODE_RIGHT_BRACKET, 93)
        put(KeyEvent.KEYCODE_BACKSLASH, 92); put(KeyEvent.KEYCODE_SEMICOLON, 59); put(KeyEvent.KEYCODE_APOSTROPHE, 39); put(KeyEvent.KEYCODE_GRAVE, 96)
        put(KeyEvent.KEYCODE_COMMA, 44); put(KeyEvent.KEYCODE_PERIOD, 46); put(KeyEvent.KEYCODE_SLASH, 47)
        put(KeyEvent.KEYCODE_PAGE_UP, 266); put(KeyEvent.KEYCODE_PAGE_DOWN, 267); put(KeyEvent.KEYCODE_MOVE_HOME, 268); put(KeyEvent.KEYCODE_MOVE_END, 269)
    }
    fun toGlfw(androidKey: Int): Int? = table[androidKey]
    fun mods(e: KeyEvent): Int = (if (e.isShiftPressed) 1 else 0) or (if (e.isCtrlPressed) 2 else 0) or (if (e.isAltPressed) 4 else 0)
}
