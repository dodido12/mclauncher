package com.example.mclauncher.input

import org.lwjgl.glfw.CallbackBridge

/**
 * Puente de entrada hacia la capa GLFW de Android (libmcl_native → cola de eventos que
 * consume el shim GLFW de LWJGL dentro del proceso del juego).
 * Las constantes son las de GLFW 3.3 (glfw3.h).
 */
object LwjglBridge {
    init { System.loadLibrary("mcl_native") }

    const val PRESS = 1; const val RELEASE = 0; const val REPEAT = 2
    const val MOUSE_LEFT = 0; const val MOUSE_RIGHT = 1; const val MOUSE_MIDDLE = 2

    // Teclas GLFW más usadas
    const val KEY_SPACE = 32; const val KEY_W = 87; const val KEY_A = 65; const val KEY_S = 83; const val KEY_D = 68
    const val KEY_E = 69; const val KEY_Q = 81; const val KEY_F = 70; const val KEY_T = 84
    const val KEY_ESCAPE = 256; const val KEY_ENTER = 257; const val KEY_TAB = 258; const val KEY_BACKSPACE = 259
    const val KEY_F1 = 290; const val KEY_F3 = 292; const val KEY_F5 = 294
    const val KEY_LEFT_SHIFT = 340; const val KEY_LEFT_CONTROL = 341
    const val KEY_RIGHT = 262; const val KEY_LEFT = 263; const val KEY_DOWN = 264; const val KEY_UP = 265
    const val KEY_1 = 49

    /**
     * true cuando el juego corre con el motor Zalith/Pojav (libpojavexec): los eventos van a
     * org.lwjgl.glfw.CallbackBridge en vez del shim GLFW propio de libmcl_native.
     */
    @Volatile @JvmStatic var zalith = false

    fun sendKey(key: Int, scancode: Int, action: Int, mods: Int) =
        if (zalith) CallbackBridge.sendKey(key, scancode, action, mods) else nSendKey(key, scancode, action, mods)
    fun sendChar(codepoint: Int, mods: Int) {
        if (!zalith) return nSendChar(codepoint, mods)
        // CallbackBridge recibe char UTF-16: los códigos fuera del BMP van como par sustituto
        String(Character.toChars(codepoint)).forEach { CallbackBridge.sendChar(it, mods) }
    }
    fun sendMouseButton(button: Int, action: Int, mods: Int) =
        if (zalith) CallbackBridge.sendMouseButton(button, action, mods) else nSendMouseButton(button, action, mods)
    /** Posición absoluta en píxeles de la ventana del juego (no de la vista Android si hay renderScale). */
    fun sendCursorPos(x: Double, y: Double) =
        if (zalith) CallbackBridge.sendCursorPos(x.toFloat(), y.toFloat()) else nSendCursorPos(x, y)
    /** Movimiento relativo; con el cursor capturado se acumula sin límites. */
    fun sendCursorDelta(dx: Double, dy: Double) {
        if (!zalith) return nSendCursorDelta(dx, dy)
        var x = CallbackBridge.mouseX + dx.toFloat()
        var y = CallbackBridge.mouseY + dy.toFloat()
        if (!CallbackBridge.isGrabbing()) {
            val w = CallbackBridge.windowWidth; val h = CallbackBridge.windowHeight
            if (w > 0) x = x.coerceIn(0f, (w - 1).toFloat())
            if (h > 0) y = y.coerceIn(0f, (h - 1).toFloat())
        }
        CallbackBridge.sendCursorPos(x, y)
    }
    fun sendScroll(dx: Double, dy: Double) = if (zalith) CallbackBridge.sendScroll(dx, dy) else nSendScroll(dx, dy)
    /** Estado completo para glfwGetGamepadState (Controlify, MidnightControls...). 15 botones + 6 ejes. */
    fun sendGamepadState(jid: Int, buttons: ByteArray, axes: FloatArray, connected: Boolean) {
        if (!zalith) nSendGamepadState(jid, buttons, axes, connected)   // el GLFW de Pojav no expone mandos
    }
    /** true si el juego tiene el cursor capturado (en partida) vs. libre (menús). */
    fun isCursorGrabbed(): Boolean = if (zalith) CallbackBridge.isGrabbing() else nIsCursorGrabbed()
    fun windowSize(): IntArray =
        if (zalith) intArrayOf(CallbackBridge.windowWidth, CallbackBridge.windowHeight) else nWindowSize()

    @JvmStatic private external fun nSendKey(key: Int, scancode: Int, action: Int, mods: Int)
    @JvmStatic private external fun nSendChar(codepoint: Int, mods: Int)
    @JvmStatic private external fun nSendMouseButton(button: Int, action: Int, mods: Int)
    @JvmStatic private external fun nSendCursorPos(x: Double, y: Double)
    @JvmStatic private external fun nSendCursorDelta(dx: Double, dy: Double)
    @JvmStatic private external fun nSendScroll(dx: Double, dy: Double)
    @JvmStatic private external fun nSendGamepadState(jid: Int, buttons: ByteArray, axes: FloatArray, connected: Boolean)
    @JvmStatic private external fun nIsCursorGrabbed(): Boolean
    @JvmStatic private external fun nWindowSize(): IntArray

    fun tapKey(key: Int) { sendKey(key, 0, PRESS, 0); sendKey(key, 0, RELEASE, 0) }
}
