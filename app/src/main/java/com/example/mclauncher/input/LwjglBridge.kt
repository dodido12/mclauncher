package com.example.mclauncher.input

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

    @JvmStatic external fun sendKey(key: Int, scancode: Int, action: Int, mods: Int)
    @JvmStatic external fun sendChar(codepoint: Int, mods: Int)
    @JvmStatic external fun sendMouseButton(button: Int, action: Int, mods: Int)
    /** Posición absoluta en píxeles de la ventana del juego (no de la vista Android si hay renderScale). */
    @JvmStatic external fun sendCursorPos(x: Double, y: Double)
    /** Movimiento relativo; el shim lo suma cuando el juego tiene el cursor capturado. */
    @JvmStatic external fun sendCursorDelta(dx: Double, dy: Double)
    @JvmStatic external fun sendScroll(dx: Double, dy: Double)
    /** Estado completo para glfwGetGamepadState (Controlify, MidnightControls...). 15 botones + 6 ejes. */
    @JvmStatic external fun sendGamepadState(jid: Int, buttons: ByteArray, axes: FloatArray, connected: Boolean)
    /** true si el juego tiene el cursor capturado (en partida) vs. libre (menús). */
    @JvmStatic external fun isCursorGrabbed(): Boolean
    @JvmStatic external fun windowSize(): IntArray

    fun tapKey(key: Int) { sendKey(key, 0, PRESS, 0); sendKey(key, 0, RELEASE, 0) }
}
