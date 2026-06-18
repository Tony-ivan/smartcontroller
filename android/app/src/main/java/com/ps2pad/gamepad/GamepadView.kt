package com.ps2pad.gamepad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Full PS2 control surface drawn with Canvas, with real multitouch:
 * D-pad, two analog sticks, the four face buttons, L1/L2/R1/R2, L3/R3,
 * Start and Select. Each finger drives one control independently.
 *
 * In [editMode] the same controls become draggable; positions are persisted
 * (normalised to the view size) in SharedPreferences so a custom layout
 * survives app restarts.
 */
class GamepadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    val state = InputState()

    /** Called with a fresh packet whenever input changes. */
    var onInput: ((ByteArray) -> Unit)? = null

    /** Tapping the very top-left corner requests the menu/connection bar. */
    var onMenu: (() -> Unit)? = null

    /** When true, fingers reposition controls instead of actuating them. */
    var editMode: Boolean = false
        set(value) {
            field = value
            releaseAll()
            invalidate()
        }

    private val layoutPrefs = context.getSharedPreferences("ps2pad_layout", Context.MODE_PRIVATE)

    private val controls = mutableListOf<Control>()
    private val pointerOwners = HashMap<Int, Control>()
    private val editGrabs = HashMap<Int, EditGrab>()

    // ---- paints ----
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FFD23B")
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val editDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFD23B")
    }

    private val baseColor = Color.parseColor("#2A2F3A")
    private val pressColor = Color.parseColor("#3B5BFF")
    private val edgeColor = Color.parseColor("#4A5161")

    // ---------------------------------------------------------------- layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        buildLayout(w.toFloat(), h.toFloat())
    }

    private fun buildLayout(w: Float, h: Float) {
        controls.clear()
        pointerOwners.clear()
        editGrabs.clear()
        if (w <= 0 || h <= 0) return

        val r = h * 0.082f
        val d = h * 0.135f
        val stickBase = h * 0.135f
        val stickKnob = h * 0.075f
        text.textSize = h * 0.05f
        stroke.strokeWidth = max(2f, h * 0.012f)
        editPaint.strokeWidth = max(2f, h * 0.008f)

        val shHW = w * 0.055f
        val shHH = h * 0.052f
        controls += RectButton("L1", InputState.L1, w * 0.075f, h * 0.14f, shHW, shHH, "L1")
        controls += TriggerButton("L2", true, w * 0.075f, h * 0.30f, shHW, shHH, "L2")
        controls += RectButton("R1", InputState.R1, w * 0.925f, h * 0.14f, shHW, shHH, "R1")
        controls += TriggerButton("R2", false, w * 0.925f, h * 0.30f, shHW, shHH, "R2")

        controls += RectButton("SELECT", InputState.SELECT, w * 0.43f, h * 0.16f, w * 0.06f, h * 0.045f, "SELECT")
        controls += RectButton("START", InputState.START, w * 0.57f, h * 0.16f, w * 0.06f, h * 0.045f, "START")

        controls += Dpad("DPAD", w * 0.20f, h * 0.54f, h * 0.16f)

        val fx = w * 0.80f
        val fy = h * 0.50f
        controls += SymbolButton("TRIANGLE", InputState.TRIANGLE, fx, fy - d, r, Symbol.TRIANGLE, Color.parseColor("#36C46B"))
        controls += SymbolButton("CIRCLE", InputState.CIRCLE, fx + d, fy, r, Symbol.CIRCLE, Color.parseColor("#F0566A"))
        controls += SymbolButton("CROSS", InputState.CROSS, fx, fy + d, r, Symbol.CROSS, Color.parseColor("#5B8CFF"))
        controls += SymbolButton("SQUARE", InputState.SQUARE, fx - d, fy, r, Symbol.SQUARE, Color.parseColor("#E07AD0"))

        controls += Stick("LSTICK", true, w * 0.32f, h * 0.80f, stickBase, stickKnob)
        controls += Stick("RSTICK", false, w * 0.68f, h * 0.80f, stickBase, stickKnob)

        controls += SymbolButton("L3", InputState.L3, w * 0.135f, h * 0.84f, r * 0.7f, Symbol.LABEL_L3, baseColor)
        controls += SymbolButton("R3", InputState.R3, w * 0.865f, h * 0.84f, r * 0.7f, Symbol.LABEL_R3, baseColor)

        // apply any saved custom positions
        for (c in controls) {
            val nx = layoutPrefs.getFloat("${c.id}_x", -1f)
            val ny = layoutPrefs.getFloat("${c.id}_y", -1f)
            if (nx in 0f..1f && ny in 0f..1f) {
                c.cx = nx * w
                c.cy = ny * h
            }
        }
    }

    private fun savePosition(c: Control) {
        if (width <= 0 || height <= 0) return
        layoutPrefs.edit()
            .putFloat("${c.id}_x", c.cx / width)
            .putFloat("${c.id}_y", c.cy / height)
            .apply()
    }

    /** Wipe custom positions and rebuild the default layout. */
    fun resetLayout() {
        layoutPrefs.edit().clear().apply()
        buildLayout(width.toFloat(), height.toFloat())
        releaseAll()
        invalidate()
    }

    private fun releaseAll() {
        for (c in controls) c.release()
        pointerOwners.clear()
        editGrabs.clear()
        state.clearButtons()
        state.lx = 0; state.ly = 0; state.rx = 0; state.ry = 0
        state.l2 = 0; state.r2 = 0
        emit()
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) return handleEdit(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val x = event.getX(i); val y = event.getY(i)
                if (x < width * 0.04f && y < height * 0.05f) {
                    onMenu?.invoke()
                } else {
                    claim(event.getPointerId(i), x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val owner = pointerOwners[event.getPointerId(i)] ?: continue
                    owner.press(event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val id = event.getPointerId(event.actionIndex)
                pointerOwners.remove(id)?.release()
            }
            MotionEvent.ACTION_CANCEL -> {
                for (c in pointerOwners.values) c.release()
                pointerOwners.clear()
            }
        }
        emit()
        invalidate()
        return true
    }

    private fun handleEdit(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val x = event.getX(i); val y = event.getY(i)
                val c = controls.firstOrNull { it.hit(x, y) }
                if (c != null) editGrabs[event.getPointerId(i)] = EditGrab(c, c.cx - x, c.cy - y)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val g = editGrabs[event.getPointerId(i)] ?: continue
                    g.control.cx = (event.getX(i) + g.dx).coerceIn(0f, width.toFloat())
                    g.control.cy = (event.getY(i) + g.dy).coerceIn(0f, height.toFloat())
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val id = event.getPointerId(event.actionIndex)
                editGrabs.remove(id)?.let { savePosition(it.control) }
            }
            MotionEvent.ACTION_CANCEL -> {
                for (g in editGrabs.values) savePosition(g.control)
                editGrabs.clear()
            }
        }
        invalidate()
        return true
    }

    private fun claim(id: Int, x: Float, y: Float) {
        val c = controls.firstOrNull { it.hit(x, y) } ?: return
        pointerOwners[id] = c
        c.press(x, y)
    }

    private fun emit() = onInput?.invoke(state.toPacket())

    // ---------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        for (c in controls) c.draw(canvas)
        if (editMode) {
            for (c in controls) c.drawEditHint(canvas)
            val ts = text.textSize
            text.textSize = height * 0.045f
            text.color = Color.parseColor("#FFD23B")
            canvas.drawText("EDIT MODE — drag controls, then tap Done", width / 2f, height * 0.97f, text)
            text.color = Color.WHITE
            text.textSize = ts
        }
    }

    private fun centeredTextY(cy: Float): Float = cy - (text.descent() + text.ascent()) / 2f

    private fun hintCircle(c: Canvas, cx: Float, cy: Float, r: Float) {
        c.drawCircle(cx, cy, r * 1.12f, editPaint)
        c.drawCircle(cx, cy, max(4f, r * 0.12f), editDot)
    }

    private fun hintRect(c: Canvas, cx: Float, cy: Float, hw: Float, hh: Float) {
        val m = max(hw, hh) * 0.15f
        c.drawRect(cx - hw - m, cy - hh - m, cx + hw + m, cy + hh + m, editPaint)
        c.drawCircle(cx, cy, max(4f, hh * 0.18f), editDot)
    }

    // ---------------------------------------------------------------- model

    private class EditGrab(val control: Control, val dx: Float, val dy: Float)

    private interface Control {
        val id: String
        var cx: Float
        var cy: Float
        fun hit(x: Float, y: Float): Boolean
        fun press(x: Float, y: Float)
        fun release()
        fun draw(c: Canvas)
        fun drawEditHint(c: Canvas)
    }

    private inner class RectButton(
        override val id: String, val bit: Int,
        override var cx: Float, override var cy: Float,
        val hw: Float, val hh: Float, val label: String
    ) : Control {
        var pressed = false
        private fun rect() = RectF(cx - hw, cy - hh, cx + hw, cy + hh)

        override fun hit(x: Float, y: Float) =
            x in cx - hw * 1.2f..cx + hw * 1.2f && y in cy - hh * 1.2f..cy + hh * 1.2f

        override fun press(x: Float, y: Float) { pressed = true; state.setButton(bit, true) }
        override fun release() { pressed = false; state.setButton(bit, false) }

        override fun draw(c: Canvas) {
            val rect = rect()
            fill.color = if (pressed) pressColor else baseColor
            c.drawRoundRect(rect, hh * 0.6f, hh * 0.6f, fill)
            stroke.color = edgeColor
            c.drawRoundRect(rect, hh * 0.6f, hh * 0.6f, stroke)
            val ts = text.textSize
            text.textSize = min(ts, hh * 1.4f)
            c.drawText(label, cx, centeredTextY(cy), text)
            text.textSize = ts
        }

        override fun drawEditHint(c: Canvas) = hintRect(c, cx, cy, hw, hh)
    }

    /** L2 / R2 — on-screen digital press maps to a full analog trigger pull. */
    private inner class TriggerButton(
        override val id: String, val isLeft: Boolean,
        override var cx: Float, override var cy: Float,
        val hw: Float, val hh: Float, val label: String
    ) : Control {
        var pressed = false
        private fun rect() = RectF(cx - hw, cy - hh, cx + hw, cy + hh)

        override fun hit(x: Float, y: Float) =
            x in cx - hw * 1.2f..cx + hw * 1.2f && y in cy - hh * 1.2f..cy + hh * 1.2f

        override fun press(x: Float, y: Float) {
            pressed = true
            if (isLeft) state.l2 = 255 else state.r2 = 255
        }

        override fun release() {
            pressed = false
            if (isLeft) state.l2 = 0 else state.r2 = 0
        }

        override fun draw(c: Canvas) {
            val rect = rect()
            fill.color = if (pressed) pressColor else baseColor
            c.drawRoundRect(rect, hh * 0.6f, hh * 0.6f, fill)
            stroke.color = edgeColor
            c.drawRoundRect(rect, hh * 0.6f, hh * 0.6f, stroke)
            val ts = text.textSize
            text.textSize = min(ts, hh * 1.4f)
            c.drawText(label, cx, centeredTextY(cy), text)
            text.textSize = ts
        }

        override fun drawEditHint(c: Canvas) = hintRect(c, cx, cy, hw, hh)
    }

    private inner class SymbolButton(
        override val id: String, val bit: Int,
        override var cx: Float, override var cy: Float, val r: Float,
        val symbol: Symbol, val symbolColor: Int
    ) : Control {
        var pressed = false
        override fun hit(x: Float, y: Float) = hypot(x - cx, y - cy) <= r * 1.18f
        override fun press(x: Float, y: Float) { pressed = true; state.setButton(bit, true) }
        override fun release() { pressed = false; state.setButton(bit, false) }

        override fun draw(c: Canvas) {
            fill.color = if (pressed) pressColor else baseColor
            c.drawCircle(cx, cy, r, fill)
            stroke.color = edgeColor
            c.drawCircle(cx, cy, r, stroke)
            drawSymbol(c, symbol, cx, cy, r * 0.5f, symbolColor)
        }

        override fun drawEditHint(c: Canvas) = hintCircle(c, cx, cy, r)
    }

    private inner class Dpad(
        override val id: String, override var cx: Float, override var cy: Float, val size: Float
    ) : Control {
        val arm = size * 0.42f
        val dead = size * 0.22f
        var up = false; var down = false; var left = false; var right = false

        override fun hit(x: Float, y: Float) =
            x in cx - size..cx + size && y in cy - size..cy + size

        override fun press(x: Float, y: Float) {
            val dx = x - cx; val dy = y - cy
            left = dx < -dead; right = dx > dead
            up = dy < -dead; down = dy > dead
            apply()
        }

        override fun release() {
            up = false; down = false; left = false; right = false; apply()
        }

        private fun apply() {
            state.setButton(InputState.DPAD_UP, up)
            state.setButton(InputState.DPAD_DOWN, down)
            state.setButton(InputState.DPAD_LEFT, left)
            state.setButton(InputState.DPAD_RIGHT, right)
        }

        override fun draw(c: Canvas) {
            fill.color = baseColor
            val v = RectF(cx - arm, cy - size, cx + arm, cy + size)
            val hRect = RectF(cx - size, cy - arm, cx + size, cy + arm)
            c.drawRoundRect(v, arm * 0.5f, arm * 0.5f, fill)
            c.drawRoundRect(hRect, arm * 0.5f, arm * 0.5f, fill)
            stroke.color = edgeColor
            c.drawRoundRect(v, arm * 0.5f, arm * 0.5f, stroke)
            c.drawRoundRect(hRect, arm * 0.5f, arm * 0.5f, stroke)

            fill.color = pressColor
            if (up) c.drawRoundRect(RectF(cx - arm, cy - size, cx + arm, cy - dead), arm * 0.4f, arm * 0.4f, fill)
            if (down) c.drawRoundRect(RectF(cx - arm, cy + dead, cx + arm, cy + size), arm * 0.4f, arm * 0.4f, fill)
            if (left) c.drawRoundRect(RectF(cx - size, cy - arm, cx - dead, cy + arm), arm * 0.4f, arm * 0.4f, fill)
            if (right) c.drawRoundRect(RectF(cx + dead, cy - arm, cx + size, cy + arm), arm * 0.4f, arm * 0.4f, fill)
        }

        override fun drawEditHint(c: Canvas) = hintRect(c, cx, cy, size, size)
    }

    private inner class Stick(
        override val id: String, val isLeft: Boolean,
        override var cx: Float, override var cy: Float,
        val baseR: Float, val knobR: Float
    ) : Control {
        var knobOffX = 0f; var knobOffY = 0f

        override fun hit(x: Float, y: Float) = hypot(x - cx, y - cy) <= baseR * 1.5f

        override fun press(x: Float, y: Float) {
            var dx = x - cx; var dy = y - cy
            val mag = hypot(dx, dy)
            if (mag > baseR) { dx = dx / mag * baseR; dy = dy / mag * baseR }
            knobOffX = dx; knobOffY = dy
            val nx = (dx / baseR).coerceIn(-1f, 1f)
            val ny = (dy / baseR).coerceIn(-1f, 1f)
            val vx = (nx * 32767).toInt()
            val vy = (-ny * 32767).toInt()
            if (isLeft) { state.lx = vx; state.ly = vy } else { state.rx = vx; state.ry = vy }
        }

        override fun release() {
            knobOffX = 0f; knobOffY = 0f
            if (isLeft) { state.lx = 0; state.ly = 0 } else { state.rx = 0; state.ry = 0 }
        }

        override fun draw(c: Canvas) {
            fill.color = Color.parseColor("#1B1F29")
            c.drawCircle(cx, cy, baseR, fill)
            stroke.color = edgeColor
            c.drawCircle(cx, cy, baseR, stroke)
            fill.color = pressColor
            c.drawCircle(cx + knobOffX, cy + knobOffY, knobR, fill)
            stroke.color = Color.parseColor("#88ABFF")
            c.drawCircle(cx + knobOffX, cy + knobOffY, knobR, stroke)
        }

        override fun drawEditHint(c: Canvas) = hintCircle(c, cx, cy, baseR)
    }

    // ---------------------------------------------------------------- symbols

    private enum class Symbol { TRIANGLE, CIRCLE, CROSS, SQUARE, LABEL_L3, LABEL_R3 }

    private val symPath = Path()

    private fun drawSymbol(c: Canvas, s: Symbol, cx: Float, cy: Float, r: Float, color: Int) {
        stroke.color = color
        val sw = stroke.strokeWidth
        stroke.strokeWidth = max(3f, r * 0.28f)
        when (s) {
            Symbol.TRIANGLE -> {
                symPath.reset()
                symPath.moveTo(cx, cy - r)
                symPath.lineTo(cx + r * 0.9f, cy + r * 0.7f)
                symPath.lineTo(cx - r * 0.9f, cy + r * 0.7f)
                symPath.close()
                c.drawPath(symPath, stroke)
            }
            Symbol.CIRCLE -> c.drawCircle(cx, cy, r * 0.85f, stroke)
            Symbol.CROSS -> {
                c.drawLine(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f, stroke)
                c.drawLine(cx + r * 0.8f, cy - r * 0.8f, cx - r * 0.8f, cy + r * 0.8f, stroke)
            }
            Symbol.SQUARE -> {
                val q = r * 0.8f
                c.drawRect(cx - q, cy - q, cx + q, cy + q, stroke)
            }
            Symbol.LABEL_L3, Symbol.LABEL_R3 -> {
                val ts = text.textSize
                text.textSize = r * 1.1f
                c.drawText(if (s == Symbol.LABEL_L3) "L3" else "R3", cx, centeredTextY(cy), text)
                text.textSize = ts
            }
        }
        stroke.strokeWidth = sw
    }
}
