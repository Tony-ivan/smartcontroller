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
    // active two-finger pinch-to-resize, if any (edit mode only)
    private var pinch: Pinch? = null

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
        // PS2 analog/mode button -> Xbox Guide button (bind it in PCSX2)
        controls += RectButton("ANALOG", InputState.ANALOG, w * 0.5f, h * 0.28f, w * 0.055f, h * 0.04f, "ANALOG")

        controls += Dpad("DPAD", w * 0.20f, h * 0.54f, h * 0.20f)

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

        // apply any saved custom positions and sizes
        for (c in controls) {
            val nx = layoutPrefs.getFloat("${c.id}_x", -1f)
            val ny = layoutPrefs.getFloat("${c.id}_y", -1f)
            if (nx in 0f..1f && ny in 0f..1f) {
                c.cx = nx * w
                c.cy = ny * h
            }
            val sc = layoutPrefs.getFloat("${c.id}_s", 1f)
            if (sc in MIN_SCALE..MAX_SCALE) c.scale = sc
        }
    }

    private fun saveControl(c: Control) {
        if (width <= 0 || height <= 0) return
        layoutPrefs.edit()
            .putFloat("${c.id}_x", c.cx / width)
            .putFloat("${c.id}_y", c.cy / height)
            .putFloat("${c.id}_s", c.scale)
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
                val id = event.getPointerId(i)
                val x = event.getX(i); val y = event.getY(i)
                val c = controls.firstOrNull { it.hit(x, y) }
                // A second finger on a control that's already grabbed starts a
                // pinch-to-resize on it instead of a second drag.
                val grab = editGrabs.entries.firstOrNull { it.value.control === c }
                if (c != null && grab != null && pinch == null) {
                    val oi = event.findPointerIndex(grab.key)
                    if (oi >= 0) {
                        val d = hypot(event.getX(oi) - x, event.getY(oi) - y)
                        pinch = Pinch(c, grab.key, id, max(1f, d), c.scale)
                    }
                } else if (c != null) {
                    editGrabs[id] = EditGrab(c, c.cx - x, c.cy - y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                pinch?.let { p ->
                    val i1 = event.findPointerIndex(p.p1)
                    val i2 = event.findPointerIndex(p.p2)
                    if (i1 >= 0 && i2 >= 0) {
                        val d = hypot(event.getX(i1) - event.getX(i2), event.getY(i1) - event.getY(i2))
                        p.control.scale = (p.startScale * d / p.startDist).coerceIn(MIN_SCALE, MAX_SCALE)
                    }
                }
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    // fingers driving an active pinch don't also drag the control
                    if (id == pinch?.p1 || id == pinch?.p2) continue
                    val g = editGrabs[id] ?: continue
                    g.control.cx = (event.getX(i) + g.dx).coerceIn(0f, width.toFloat())
                    g.control.cy = (event.getY(i) + g.dy).coerceIn(0f, height.toFloat())
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val id = event.getPointerId(event.actionIndex)
                val p = pinch
                if (p != null && (id == p.p1 || id == p.p2)) {
                    saveControl(p.control)
                    // drop both pinch fingers so the surviving one doesn't jump the control
                    editGrabs.remove(p.p1)
                    editGrabs.remove(p.p2)
                    pinch = null
                } else {
                    editGrabs.remove(id)?.let { saveControl(it.control) }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                for (g in editGrabs.values) saveControl(g.control)
                editGrabs.clear()
                pinch = null
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

    private class Pinch(
        val control: Control, val p1: Int, val p2: Int,
        val startDist: Float, val startScale: Float
    )

    private interface Control {
        val id: String
        var cx: Float
        var cy: Float
        /** Per-control size multiplier, adjusted by pinch-to-resize in edit mode. */
        var scale: Float
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
        override var scale = 1f
        var pressed = false
        private val shw get() = hw * scale
        private val shh get() = hh * scale
        private fun rect() = RectF(cx - shw, cy - shh, cx + shw, cy + shh)

        override fun hit(x: Float, y: Float) =
            x in cx - shw * 1.2f..cx + shw * 1.2f && y in cy - shh * 1.2f..cy + shh * 1.2f

        override fun press(x: Float, y: Float) { pressed = true; state.setButton(bit, true) }
        override fun release() { pressed = false; state.setButton(bit, false) }

        override fun draw(c: Canvas) {
            val rect = rect()
            fill.color = if (pressed) pressColor else baseColor
            c.drawRoundRect(rect, shh * 0.6f, shh * 0.6f, fill)
            stroke.color = edgeColor
            c.drawRoundRect(rect, shh * 0.6f, shh * 0.6f, stroke)
            val ts = text.textSize
            text.textSize = min(ts, shh * 1.4f)
            c.drawText(label, cx, centeredTextY(cy), text)
            text.textSize = ts
        }

        override fun drawEditHint(c: Canvas) = hintRect(c, cx, cy, shw, shh)
    }

    /** L2 / R2 — on-screen digital press maps to a full analog trigger pull. */
    private inner class TriggerButton(
        override val id: String, val isLeft: Boolean,
        override var cx: Float, override var cy: Float,
        val hw: Float, val hh: Float, val label: String
    ) : Control {
        override var scale = 1f
        var pressed = false
        private val shw get() = hw * scale
        private val shh get() = hh * scale
        private fun rect() = RectF(cx - shw, cy - shh, cx + shw, cy + shh)

        override fun hit(x: Float, y: Float) =
            x in cx - shw * 1.2f..cx + shw * 1.2f && y in cy - shh * 1.2f..cy + shh * 1.2f

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
            c.drawRoundRect(rect, shh * 0.6f, shh * 0.6f, fill)
            stroke.color = edgeColor
            c.drawRoundRect(rect, shh * 0.6f, shh * 0.6f, stroke)
            val ts = text.textSize
            text.textSize = min(ts, shh * 1.4f)
            c.drawText(label, cx, centeredTextY(cy), text)
            text.textSize = ts
        }

        override fun drawEditHint(c: Canvas) = hintRect(c, cx, cy, shw, shh)
    }

    private inner class SymbolButton(
        override val id: String, val bit: Int,
        override var cx: Float, override var cy: Float, val r: Float,
        val symbol: Symbol, val symbolColor: Int
    ) : Control {
        override var scale = 1f
        var pressed = false
        private val sr get() = r * scale
        override fun hit(x: Float, y: Float) = hypot(x - cx, y - cy) <= sr * 1.18f
        override fun press(x: Float, y: Float) { pressed = true; state.setButton(bit, true) }
        override fun release() { pressed = false; state.setButton(bit, false) }

        override fun draw(c: Canvas) {
            fill.color = if (pressed) pressColor else baseColor
            c.drawCircle(cx, cy, sr, fill)
            stroke.color = edgeColor
            c.drawCircle(cx, cy, sr, stroke)
            drawSymbol(c, symbol, cx, cy, sr * 0.5f, symbolColor)
        }

        override fun drawEditHint(c: Canvas) = hintCircle(c, cx, cy, sr)
    }

    private inner class Dpad(
        override val id: String, override var cx: Float, override var cy: Float, val size: Float
    ) : Control {
        override var scale = 1f
        private val ssize get() = size * scale
        private val arm get() = ssize * 0.42f
        private val dead get() = ssize * 0.22f
        var up = false; var down = false; var left = false; var right = false

        override fun hit(x: Float, y: Float) =
            x in cx - ssize..cx + ssize && y in cy - ssize..cy + ssize

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
            val v = RectF(cx - arm, cy - ssize, cx + arm, cy + ssize)
            val hRect = RectF(cx - ssize, cy - arm, cx + ssize, cy + arm)
            c.drawRoundRect(v, arm * 0.5f, arm * 0.5f, fill)
            c.drawRoundRect(hRect, arm * 0.5f, arm * 0.5f, fill)
            stroke.color = edgeColor
            c.drawRoundRect(v, arm * 0.5f, arm * 0.5f, stroke)
            c.drawRoundRect(hRect, arm * 0.5f, arm * 0.5f, stroke)

            fill.color = pressColor
            if (up) c.drawRoundRect(RectF(cx - arm, cy - ssize, cx + arm, cy - dead), arm * 0.4f, arm * 0.4f, fill)
            if (down) c.drawRoundRect(RectF(cx - arm, cy + dead, cx + arm, cy + ssize), arm * 0.4f, arm * 0.4f, fill)
            if (left) c.drawRoundRect(RectF(cx - ssize, cy - arm, cx - dead, cy + arm), arm * 0.4f, arm * 0.4f, fill)
            if (right) c.drawRoundRect(RectF(cx + dead, cy - arm, cx + ssize, cy + arm), arm * 0.4f, arm * 0.4f, fill)
        }

        override fun drawEditHint(c: Canvas) = hintRect(c, cx, cy, ssize, ssize)
    }

    private inner class Stick(
        override val id: String, val isLeft: Boolean,
        override var cx: Float, override var cy: Float,
        val baseR: Float, val knobR: Float
    ) : Control {
        override var scale = 1f
        private val sBaseR get() = baseR * scale
        private val sKnobR get() = knobR * scale
        var knobOffX = 0f; var knobOffY = 0f

        override fun hit(x: Float, y: Float) = hypot(x - cx, y - cy) <= sBaseR * 1.5f

        override fun press(x: Float, y: Float) {
            var dx = x - cx; var dy = y - cy
            val mag = hypot(dx, dy)
            val r = sBaseR
            if (mag > r) { dx = dx / mag * r; dy = dy / mag * r }
            knobOffX = dx; knobOffY = dy
            val nx = (dx / r).coerceIn(-1f, 1f)
            val ny = (dy / r).coerceIn(-1f, 1f)
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
            c.drawCircle(cx, cy, sBaseR, fill)
            stroke.color = edgeColor
            c.drawCircle(cx, cy, sBaseR, stroke)
            fill.color = pressColor
            c.drawCircle(cx + knobOffX, cy + knobOffY, sKnobR, fill)
            stroke.color = Color.parseColor("#88ABFF")
            c.drawCircle(cx + knobOffX, cy + knobOffY, sKnobR, stroke)
        }

        override fun drawEditHint(c: Canvas) = hintCircle(c, cx, cy, sBaseR)
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

    companion object {
        // bounds for per-control pinch-to-resize (1f = the default layout size)
        private const val MIN_SCALE = 0.5f
        private const val MAX_SCALE = 2.5f
    }
}
