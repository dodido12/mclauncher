package com.example.mclauncher.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.sign

/**
 * Mandos físicos (Xbox, DualShock/DualSense, 8BitDo, genéricos BT/USB).
 *
 * Dos salidas en paralelo:
 *  1. Emulación teclado/mouse para Minecraft vanilla (stick izq = WASD, stick der = cámara...).
 *  2. Estado GLFW gamepad completo (glfwGetGamepadState) para mods con soporte nativo
 *     de mando como Controlify; si se detecta, se puede desactivar la emulación.
 *
 * Integración: la Activity debe reenviar dispatchKeyEvent / dispatchGenericMotionEvent
 * y llamar a tick() en cada vsync (Choreographer).
 */
class GamepadManager {

    var emulateKeyboardMouse = true
    var cameraSpeed = 18f          // píxeles de cursor por frame a deflexión completa
    var deadzone = 0.15f
    var activeDeviceId = -1; private set

    // Layout GLFW_GAMEPAD_BUTTON_*: A B X Y LB RB BACK START GUIDE LTHUMB RTHUMB DUP DRIGHT DDOWN DLEFT
    private val buttons = ByteArray(15)
    // GLFW_GAMEPAD_AXIS_*: LX LY RX RY LT RT (gatillos en [-1, 1])
    private val axes = floatArrayOf(0f, 0f, 0f, 0f, -1f, -1f)
    private val heldMoveKeys = HashSet<Int>()
    private var ltDown = false; private var rtDown = false

    fun isGamepad(dev: InputDevice?): Boolean {
        val s = dev?.sources ?: return false
        return (s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) ||
            (s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
    }

    fun connectedGamepads(): List<InputDevice> =
        InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.filterNotNull().filter { isGamepad(it) && !it.isVirtual }

    /** Botón Android → índice GLFW + acción de emulación. */
    private data class Mapping(val glfwButton: Int, val key: Int? = null, val mouse: Int? = null, val scroll: Int = 0)

    private val map = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to Mapping(0, key = LwjglBridge.KEY_SPACE),            // saltar
        KeyEvent.KEYCODE_BUTTON_B to Mapping(1, key = LwjglBridge.KEY_LEFT_SHIFT),       // agacharse
        KeyEvent.KEYCODE_BUTTON_X to Mapping(2, key = LwjglBridge.KEY_Q),                // soltar
        KeyEvent.KEYCODE_BUTTON_Y to Mapping(3, key = LwjglBridge.KEY_E),                // inventario
        KeyEvent.KEYCODE_BUTTON_L1 to Mapping(4, scroll = +1),                           // hotbar ←
        KeyEvent.KEYCODE_BUTTON_R1 to Mapping(5, scroll = -1),                           // hotbar →
        KeyEvent.KEYCODE_BUTTON_SELECT to Mapping(6, key = LwjglBridge.KEY_F5),          // cámara
        KeyEvent.KEYCODE_BUTTON_START to Mapping(7, key = LwjglBridge.KEY_ESCAPE),       // pausa
        KeyEvent.KEYCODE_BUTTON_MODE to Mapping(8, key = LwjglBridge.KEY_TAB),           // lista jugadores
        KeyEvent.KEYCODE_BUTTON_THUMBL to Mapping(9, key = LwjglBridge.KEY_LEFT_CONTROL),// sprint
        KeyEvent.KEYCODE_BUTTON_THUMBR to Mapping(10, mouse = LwjglBridge.MOUSE_MIDDLE), // pick block
        KeyEvent.KEYCODE_DPAD_UP to Mapping(11, key = LwjglBridge.KEY_F),                // swap mano
        KeyEvent.KEYCODE_DPAD_RIGHT to Mapping(12, key = LwjglBridge.KEY_T),             // chat
        KeyEvent.KEYCODE_DPAD_DOWN to Mapping(13, key = LwjglBridge.KEY_F1),             // ocultar HUD
        KeyEvent.KEYCODE_DPAD_LEFT to Mapping(14, key = LwjglBridge.KEY_F3)              // debug
    )

    fun onKeyEvent(e: KeyEvent): Boolean {
        if (!isGamepad(e.device)) return false
        val m = map[e.keyCode] ?: return false
        activeDeviceId = e.deviceId
        if (e.repeatCount > 0) return true
        val down = e.action == KeyEvent.ACTION_DOWN
        buttons[m.glfwButton] = if (down) 1 else 0
        if (emulateKeyboardMouse) {
            val a = if (down) LwjglBridge.PRESS else LwjglBridge.RELEASE
            m.key?.let { LwjglBridge.sendKey(it, 0, a, 0) }
            m.mouse?.let { LwjglBridge.sendMouseButton(it, a, 0) }
            if (down && m.scroll != 0) LwjglBridge.sendScroll(0.0, m.scroll.toDouble())
        }
        pushState()
        return true
    }

    fun onGenericMotion(e: MotionEvent): Boolean {
        if (!isGamepad(e.device) || e.action != MotionEvent.ACTION_MOVE) return false
        activeDeviceId = e.deviceId
        axes[0] = dz(e.getAxisValue(MotionEvent.AXIS_X))
        axes[1] = dz(e.getAxisValue(MotionEvent.AXIS_Y))
        // Xbox/genéricos usan Z/RZ para el stick derecho; algunos mandos usan RX/RY
        val rx = e.getAxisValue(MotionEvent.AXIS_Z).takeIf { it != 0f } ?: e.getAxisValue(MotionEvent.AXIS_RX)
        val ry = e.getAxisValue(MotionEvent.AXIS_RZ).takeIf { it != 0f } ?: e.getAxisValue(MotionEvent.AXIS_RY)
        axes[2] = dz(rx); axes[3] = dz(ry)
        val lt = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
        axes[4] = lt * 2f - 1f; axes[5] = rt * 2f - 1f

        // D-pad como HAT en algunos mandos
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        buttons[11] = if (hy < -0.5f) 1 else 0; buttons[13] = if (hy > 0.5f) 1 else 0
        buttons[14] = if (hx < -0.5f) 1 else 0; buttons[12] = if (hx > 0.5f) 1 else 0

        if (emulateKeyboardMouse) {
            updateMoveKeys()
            // Gatillos: RT = atacar/romper (clic izq), LT = usar (clic der)
            val rtNow = rt > 0.5f; val ltNow = lt > 0.5f
            if (rtNow != rtDown) { rtDown = rtNow; LwjglBridge.sendMouseButton(LwjglBridge.MOUSE_LEFT, if (rtNow) 1 else 0, 0) }
            if (ltNow != ltDown) { ltDown = ltNow; LwjglBridge.sendMouseButton(LwjglBridge.MOUSE_RIGHT, if (ltNow) 1 else 0, 0) }
        }
        pushState()
        return true
    }

    /** Llamar en cada vsync: la cámara se integra por frame para que sea suave a cualquier Hz. */
    fun tick(frameDtSec: Float) {
        if (!emulateKeyboardMouse || activeDeviceId < 0) return
        val scale = cameraSpeed * (frameDtSec * 60f)
        val dx = curve(axes[2]) * scale; val dy = curve(axes[3]) * scale
        if (dx == 0f && dy == 0f) return
        if (LwjglBridge.isCursorGrabbed()) LwjglBridge.sendCursorDelta(dx.toDouble(), dy.toDouble())
        else LwjglBridge.sendCursorDelta(dx * 0.6, dy * 0.6)   // el shim mueve el cursor libre en menús
    }

    private fun updateMoveKeys() {
        val want = buildSet {
            if (axes[1] < -0.35f) add(LwjglBridge.KEY_W); if (axes[1] > 0.35f) add(LwjglBridge.KEY_S)
            if (axes[0] < -0.35f) add(LwjglBridge.KEY_A); if (axes[0] > 0.35f) add(LwjglBridge.KEY_D)
        }
        (heldMoveKeys - want).forEach { LwjglBridge.sendKey(it, 0, LwjglBridge.RELEASE, 0) }
        (want - heldMoveKeys).forEach { LwjglBridge.sendKey(it, 0, LwjglBridge.PRESS, 0) }
        heldMoveKeys.clear(); heldMoveKeys.addAll(want)
    }

    private fun pushState() = LwjglBridge.sendGamepadState(0, buttons, axes, activeDeviceId >= 0)

    private fun dz(v: Float): Float = if (abs(v) < deadzone) 0f else sign(v) * (abs(v) - deadzone) / (1f - deadzone)
    private fun curve(v: Float) = sign(v) * v * v   // respuesta cuadrática: precisión cerca del centro

    fun onDeviceRemoved(deviceId: Int) {
        if (deviceId != activeDeviceId) return
        heldMoveKeys.forEach { LwjglBridge.sendKey(it, 0, LwjglBridge.RELEASE, 0) }
        heldMoveKeys.clear(); buttons.fill(0); axes.fill(0f); axes[4] = -1f; axes[5] = -1f
        activeDeviceId = -1; pushState()
    }
}
