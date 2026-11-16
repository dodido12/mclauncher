package com.example.mclauncher.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.hypot
import kotlin.math.min

/**
 * Overlay táctil configurable dibujado sobre el SurfaceView del juego.
 *  - Joystick virtual → W/A/S/D (+ sprint automático al empujar al borde)
 *  - Botones → teclas GLFW o clics de mouse; opcionalmente "toggle"
 *  - Zona libre: arrastrar = mover cámara (delta de cursor); toque corto = clic derecho (usar),
 *    mantener = clic izquierdo (romper) — como Bedrock
 *  - En menús (cursor libre) los toques se convierten en posición absoluta + clic
 *  - Modo edición: arrastrar botones y guardar el layout por instancia
 */
@SuppressLint("ViewConstructor")
class TouchControlsEngine(context: Context, private val layoutFile: File) : View(context) {

    @Serializable data class Control(
        val id: String, val type: String, val label: String = "",
        var x: Float, var y: Float, val size: Float,
        val keycode: Int? = null, val mouse: Int? = null, val toggle: Boolean = false
    )
    @Serializable data class Layout(val version: Int = 1, val opacity: Float = 0.55f, val controls: MutableList<Control>)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    var layout: Layout = loadLayout(); private set
    var editMode = false
        set(v) { field = v; invalidate(); if (!v) saveLayout() }

    // Estado por puntero
    private sealed class Owner {
        data class Btn(val c: Control) : Owner()
        data object Stick : Owner()
        data class Look(var lastX: Float, var lastY: Float, val downAt: Long, var moved: Float = 0f, var holding: Boolean = false) : Owner()
        data class Drag(val c: Control) : Owner()
    }
    private val owners = HashMap<Int, Owner>()
    private val toggled = HashSet<String>()
    private val stickKeys = HashSet<Int>()
    private var stickVec = 0f to 0f

    var lookSensitivity = 1.6f
    private val longPressMs = 220L
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }

    private val holdChecker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            owners.values.filterIsInstance<Owner.Look>().forEach { o ->
                if (!o.holding && o.moved < 24f && now - o.downAt >= longPressMs && LwjglBridge.isCursorGrabbed()) {
                    o.holding = true
                    LwjglBridge.sendMouseButton(LwjglBridge.MOUSE_LEFT, LwjglBridge.PRESS, 0)
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            }
            if (owners.isNotEmpty()) postDelayed(this, 30)
        }
    }

    private fun loadLayout(): Layout {
        val text = if (layoutFile.exists()) layoutFile.readText()
        else context.assets.open("controls/default_layout.json").bufferedReader().use { it.readText() }
        return json.decodeFromString(Layout.serializer(), text)
    }
    fun saveLayout() { layoutFile.parentFile?.mkdirs(); layoutFile.writeText(json.encodeToString(Layout.serializer(), layout)) }
    fun resetLayout() { layoutFile.delete(); layout = loadLayout(); invalidate() }

    // ---- geometría ----
    private fun rectOf(c: Control): RectF {
        val s = c.size * min(width, height)
        val cx = c.x * width; val cy = c.y * height
        return RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
    }
    private fun hit(x: Float, y: Float): Control? = layout.controls.lastOrNull { c ->
        val r = rectOf(c); val pad = if (c.type == "JOYSTICK") r.width() * 0.25f else 0f
        x in (r.left - pad)..(r.right + pad) && y in (r.top - pad)..(r.bottom + pad)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> down(e.getPointerId(idx), e.getX(idx), e.getY(idx))
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(idx), e.getX(idx), e.getY(idx))
            MotionEvent.ACTION_CANCEL -> owners.keys.toList().forEach { up(it, 0f, 0f) }
        }
        invalidate()
        return true
    }

    private fun down(pid: Int, x: Float, y: Float) {
        val c = hit(x, y)
        if (editMode) { c?.let { owners[pid] = Owner.Drag(it) }; return }
        when {
            c?.type == "JOYSTICK" -> { owners[pid] = Owner.Stick; updateStick(c, x, y) }
            c != null -> { owners[pid] = Owner.Btn(c); press(c, true); performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
            !LwjglBridge.isCursorGrabbed() -> {
                // Menú/inventario: cursor absoluto + clic izquierdo
                val (gx, gy) = toGame(x, y)
                LwjglBridge.sendCursorPos(gx, gy)
                LwjglBridge.sendMouseButton(LwjglBridge.MOUSE_LEFT, LwjglBridge.PRESS, 0)
                owners[pid] = Owner.Look(x, y, System.currentTimeMillis(), holding = true)
            }
            else -> { owners[pid] = Owner.Look(x, y, System.currentTimeMillis()); removeCallbacks(holdChecker); post(holdChecker) }
        }
    }

    private fun move(pid: Int, x: Float, y: Float) {
        when (val o = owners[pid]) {
            is Owner.Drag -> { o.c.x = (x / width).coerceIn(0f, 1f); o.c.y = (y / height).coerceIn(0f, 1f) }
            is Owner.Stick -> layout.controls.first { it.type == "JOYSTICK" }.let { updateStick(it, x, y) }
            is Owner.Look -> {
                val dx = x - o.lastX; val dy = y - o.lastY
                o.moved += hypot(dx, dy); o.lastX = x; o.lastY = y
                if (LwjglBridge.isCursorGrabbed()) LwjglBridge.sendCursorDelta(dx * lookSensitivity.toDouble(), dy * lookSensitivity.toDouble())
                else { val (gx, gy) = toGame(x, y); LwjglBridge.sendCursorPos(gx, gy) }
            }
            else -> Unit
        }
    }

    private fun up(pid: Int, x: Float, y: Float) {
        when (val o = owners.remove(pid)) {
            is Owner.Btn -> press(o.c, false)
            is Owner.Stick -> { setStickKeys(emptySet()); stickVec = 0f to 0f }
            is Owner.Look -> {
                if (o.holding) LwjglBridge.sendMouseButton(LwjglBridge.MOUSE_LEFT, LwjglBridge.RELEASE, 0)
                else if (o.moved < 24f && System.currentTimeMillis() - o.downAt < longPressMs) {
                    // Toque corto en partida = usar/colocar
                    LwjglBridge.sendMouseButton(LwjglBridge.MOUSE_RIGHT, LwjglBridge.PRESS, 0)
                    LwjglBridge.sendMouseButton(LwjglBridge.MOUSE_RIGHT, LwjglBridge.RELEASE, 0)
                }
            }
            else -> Unit
        }
    }

    private fun press(c: Control, down: Boolean) {
        if (c.toggle) {
            if (!down) return
            val on = toggled.add(c.id).also { if (!it) toggled.remove(c.id) }
            emit(c, on); return
        }
        emit(c, down)
    }

    private fun emit(c: Control, down: Boolean) {
        val action = if (down) LwjglBridge.PRESS else LwjglBridge.RELEASE
        c.keycode?.let { LwjglBridge.sendKey(it, 0, action, 0) }
        c.mouse?.let { LwjglBridge.sendMouseButton(it, action, 0) }
    }

    private fun updateStick(c: Control, x: Float, y: Float) {
        val r = rectOf(c); val radius = r.width() / 2
        var dx = (x - r.centerX()) / radius; var dy = (y - r.centerY()) / radius
        val len = hypot(dx, dy); if (len > 1f) { dx /= len; dy /= len }
        stickVec = dx to dy
        val dead = 0.25f
        val keys = buildSet {
            if (dy < -dead) add(LwjglBridge.KEY_W); if (dy > dead) add(LwjglBridge.KEY_S)
            if (dx < -dead) add(LwjglBridge.KEY_A); if (dx > dead) add(LwjglBridge.KEY_D)
            if (len > 1.15f && dy < -0.6f) add(LwjglBridge.KEY_LEFT_CONTROL)   // sprint al borde
        }
        setStickKeys(keys)
    }

    private fun setStickKeys(keys: Set<Int>) {
        (stickKeys - keys).forEach { LwjglBridge.sendKey(it, 0, LwjglBridge.RELEASE, 0) }
        (keys - stickKeys).forEach { LwjglBridge.sendKey(it, 0, LwjglBridge.PRESS, 0) }
        stickKeys.clear(); stickKeys.addAll(keys)
    }

    /** Convierte coordenadas de la vista a píxeles de la ventana del juego (respeta renderScale). */
    private fun toGame(x: Float, y: Float): Pair<Double, Double> {
        val ws = LwjglBridge.windowSize()
        return (x / width * ws[0]).toDouble() to (y / height * ws[1]).toDouble()
    }

    override fun onDraw(canvas: Canvas) {
        val alpha = (layout.opacity * 255).toInt()
        for (c in layout.controls) {
            val r = rectOf(c)
            val active = owners.values.any { (it as? Owner.Btn)?.c == c } || c.id in toggled
            paintFill.color = if (editMode) Color.argb(alpha, 80, 160, 255) else if (active) Color.argb(alpha, 255, 255, 255) else Color.argb(alpha / 2, 30, 30, 30)
            if (c.type == "JOYSTICK") {
                canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2, paintFill)
                paintFill.color = Color.argb(alpha, 220, 220, 220)
                val (sx, sy) = stickVec
                canvas.drawCircle(r.centerX() + sx * r.width() / 2, r.centerY() + sy * r.width() / 2, r.width() / 5, paintFill)
            } else {
                canvas.drawRoundRect(r, r.width() * 0.25f, r.width() * 0.25f, paintFill)
                paintText.textSize = r.height() * 0.45f
                paintText.alpha = alpha
                canvas.drawText(c.label, r.centerX(), r.centerY() + paintText.textSize / 3, paintText)
            }
        }
    }
}
