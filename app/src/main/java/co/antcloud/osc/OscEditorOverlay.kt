package co.antcloud.osc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.limelight.binding.input.virtual_controller.VirtualController
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The glass editor drawn over the controls (in the stream, or over a preview backdrop): drag a control to move
 * it, then use the floating panel to size it, hide it, change the pad's opacity, reset, or try the pad out.
 * Mirrors the iOS OSCEditorPanel. Touches in "Try it" go to the real controls, which send nothing.
 */
class OscEditorOverlay(
    context: Context,
    private val controller: VirtualController,
    private val tryable: Boolean,
    private val onDone: Runnable,
) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private val layout get() = controller.layout
    private var selected: OscElement? = null
    private var trying = false

    private var dragging: OscElement? = null
    private var dragPointer = -1
    private var grabX = 0f
    private var grabY = 0f
    private var guideX: Float? = null
    private var guideY: Float? = null

    private val selectionPaint = OscGlass.selectionPaint(d)
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255); strokeWidth = d }

    // Panel parts
    private val title = text(15f, 1f, bold = true)
    private val subtitle = text(11f, 0.6f)
    private val arrangeTab = tab("Arrange") { setTrying(false) }
    private val tryTab = tab("Try it") { setTrying(true) }
    private val sizeBar = SeekBar(context)
    private val sizeValue = text(11f, 0.7f)
    private val opacityBar = SeekBar(context)
    private val opacityValue = text(11f, 0.7f)
    private val hideButton = pill("Hide", false) { toggleHidden() }
    private val resetButton = pill("Reset", false) { reset() }
    private val doneButton = pill("Done", true) { onDone.run() }
    private var syncing = false

    init {
        setWillNotDraw(false)
        buildPanel()
        refreshPanel()
    }

    // MARK: Geometry

    private val factor get() = OscGeometry.deviceFactor(context)
    private val frameLayout get() = controller.frameLayout

    private fun area(): RectF = OscGeometry.area(frameLayout, frameLayout.width, frameLayout.height)

    private fun elementsOnScreen() = controller.elements.map { OscGeometry.element(it.elementId) }.distinct()

    private fun frame(e: OscElement) = OscGeometry.frame(e, layout, area(), factor, d)

    // MARK: Touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (trying) {
            // Let a control under the finger take the touch; swallow the rest so the stream sees nothing.
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                val x = event.getX(event.actionIndex); val y = event.getY(event.actionIndex)
                return controller.elements.none { it.visibility == View.VISIBLE && x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(event.getPointerId(0), event.x, event.y)
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(dragPointer)
                if (i >= 0) move(event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> up()
        }
        return true
    }

    private fun down(pointer: Int, x: Float, y: Float) {
        if (dragging != null) return
        var best: OscElement? = null
        var bestDistance = Float.MAX_VALUE
        for (e in elementsOnScreen()) {
            val f = frame(e).also { it.inset(-10 * d, -10 * d) }
            if (!f.contains(x, y)) continue
            val dist = hypot(x - f.centerX(), y - f.centerY())
            if (dist < bestDistance) { best = e; bestDistance = dist }
        }
        select(best)
        if (best != null) {
            val f = frame(best)
            dragging = best
            dragPointer = pointer
            grabX = x - f.centerX()
            grabY = y - f.centerY()
        }
    }

    private fun move(x: Float, y: Float) {
        val e = dragging ?: return
        val area = area()
        val item = layout.item(e)
        val ext = OscGeometry.extent(OscGeometry.parts(e, factor * item.scale, d))
        var cx = x - grabX
        var cy = y - grabY
        // Snap to the centre lines of the other controls and of the screen, and show the line.
        val others = OscGeometry.snapTargets(controller, e, layout, area, factor, d)
        val xs = listOf(area.centerX()) + others.map { it.centerX() }
        val ys = listOf(area.centerY()) + others.map { it.centerY() }
        val ex = (ext.left + ext.right) / 2
        val ey = (ext.top + ext.bottom) / 2
        guideX = null; guideY = null
        xs.minByOrNull { abs(it - (cx + ex)) }?.takeIf { abs(it - (cx + ex)) < 7 * d }?.let { cx = it - ex; guideX = it }
        ys.minByOrNull { abs(it - (cy + ey)) }?.takeIf { abs(it - (cy + ey)) < 7 * d }?.let { cy = it - ey; guideY = it }
        cx = cx.coerceIn(area.left - ext.left, maxOf(area.left - ext.left, area.right - ext.right))
        cy = cy.coerceIn(area.top - ext.top, maxOf(area.top - ext.top, area.bottom - ext.bottom))
        layout.set(e, item.copy(x = (cx - area.left) / maxOf(1f, area.width()), y = (cy - area.top) / maxOf(1f, area.height())))
        controller.relayout()
    }

    private fun up() {
        if (dragging != null) save()
        dragging = null; dragPointer = -1; guideX = null; guideY = null
        invalidate()
    }

    // MARK: Drawing

    override fun onDraw(canvas: Canvas) {
        if (trying) return
        val area = area()
        guideX?.let { canvas.drawLine(it, area.top, it, area.bottom, guidePaint) }
        guideY?.let { canvas.drawLine(area.left, it, area.right, it, guidePaint) }
        selected?.takeIf { it in elementsOnScreen() }?.let {
            canvas.drawRoundRect(frame(it).also { r -> r.inset(-10 * d, -10 * d) }, 16 * d, 16 * d, selectionPaint)
        }
    }

    // MARK: Actions

    private fun save() = OscStore.save(context, layout)

    private fun select(e: OscElement?) {
        selected = e
        refreshPanel()
        invalidate()
    }

    /** Sets which element is selected (for previews and screenshots). */
    fun preselect(e: OscElement?) = select(e)

    fun setTrying(on: Boolean) {
        if (on == trying) return
        trying = on
        dragging = null
        controller.setArranging(!on)
        refreshPanel()
        invalidate()
    }

    private fun toggleHidden() {
        val e = selected ?: return
        val item = layout.item(e)
        layout.set(e, item.copy(hidden = !item.hidden))
        save(); controller.relayout(); refreshPanel()
    }

    private fun reset() {
        layout.items.clear()
        layout.opacity = OscLayout.DEFAULT_OPACITY
        save(); controller.relayout(); refreshPanel(); invalidate()
    }

    // MARK: Panel

    private fun buildPanel() {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(205, 38, 40, 58), Color.argb(215, 18, 19, 30))).apply {
                cornerRadius = 20 * d
                setStroke(d.roundToInt().coerceAtLeast(1), Color.argb(70, 255, 255, 255))
            }
            elevation = 12 * d
            isClickable = true
        }
        panel.addView(title)
        panel.addView(subtitle, lp(top = 1))
        if (tryable) panel.addView(LinearLayout(context).apply {
            background = rounded(Color.argb(90, 0, 0, 0), 10f)
            setPadding((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
            addView(arrangeTab, LinearLayout.LayoutParams(0, (28 * d).toInt(), 1f))
            addView(tryTab, LinearLayout.LayoutParams(0, (28 * d).toInt(), 1f))
        }, lp(top = 8))
        panel.addView(slider("Size", sizeBar, sizeValue) { p ->
            val e = selected
            if (e != null) {
                layout.set(e, layout.item(e).copy(scale = OscLayout.MIN_SCALE + (OscLayout.MAX_SCALE - OscLayout.MIN_SCALE) * p / 100f))
                save(); controller.relayout(); refreshPanel(); invalidate()
            }
        }, lp(top = 8))
        panel.addView(slider("Opacity", opacityBar, opacityValue) { p ->
            layout.opacity = OscLayout.MIN_OPACITY + (OscLayout.MAX_OPACITY - OscLayout.MIN_OPACITY) * p / 100f
            save(); controller.relayout(); refreshPanel()
        }, lp(top = 6))
        val buttons = LinearLayout(context).apply {
            addView(hideButton, LinearLayout.LayoutParams(0, (34 * d).toInt(), 1f))
            addView(resetButton, LinearLayout.LayoutParams(0, (34 * d).toInt(), 1f).also { it.leftMargin = (8 * d).toInt() })
            addView(doneButton, LinearLayout.LayoutParams(0, (34 * d).toInt(), 1f).also { it.leftMargin = (8 * d).toInt() })
        }
        panel.addView(buttons, lp(top = 10))

        val width = min((330 * d).toInt(), resources.displayMetrics.widthPixels - (40 * d).toInt())
        addView(panel, LayoutParams(width, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.topMargin = (6 * d).toInt() })
    }

    private fun refreshPanel() {
        syncing = true
        val e = selected
        val item = e?.let { layout.item(it) }
        title.text = if (trying) "Try it" else e?.title ?: "Customize controls"
        subtitle.text = when {
            trying -> "Nothing is sent. Tap Arrange to edit again."
            e == null -> "Tap a control, then drag it."
            else -> "Drag to move it, Size to resize it."
        }
        arrangeTab.background = if (!trying) rounded(Color.argb(60, 255, 255, 255), 8f) else null
        tryTab.background = if (trying) rounded(Color.argb(60, 255, 255, 255), 8f) else null
        hideButton.visibility = if (trying) GONE else VISIBLE
        resetButton.visibility = if (trying) GONE else VISIBLE
        (sizeBar.parent as View).visibility = if (trying) GONE else VISIBLE
        (opacityBar.parent as View).visibility = if (trying) GONE else VISIBLE
        val scale = item?.scale ?: 1f
        sizeBar.progress = ((scale - OscLayout.MIN_SCALE) / (OscLayout.MAX_SCALE - OscLayout.MIN_SCALE) * 100).roundToInt()
        sizeValue.text = "${(scale * 100).roundToInt()}%"
        opacityBar.progress = ((layout.opacity - OscLayout.MIN_OPACITY) / (OscLayout.MAX_OPACITY - OscLayout.MIN_OPACITY) * 100).roundToInt()
        opacityValue.text = "${(layout.opacity * 100).roundToInt()}%"
        enable(sizeBar, e != null)
        hideButton.text = if (item?.hidden == true) "Show" else "Hide"
        enable(hideButton, e != null)
        enable(resetButton, !layout.isDefault)
        syncing = false
    }

    private fun enable(v: View, on: Boolean) { v.isEnabled = on; v.alpha = if (on) 1f else 0.4f }

    // MARK: View helpers

    private fun lp(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = (top * d).toInt() }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius * d }

    private fun text(size: Float, alpha: Float, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(Color.argb((alpha * 255).toInt(), 255, 255, 255))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        isSingleLine = true
    }

    private fun tab(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setOnClickListener { onClick() }
    }

    private fun pill(label: String, filled: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = rounded(if (filled) Color.rgb(74, 135, 232) else Color.argb(48, 255, 255, 255), 17f)
        setOnClickListener { if (isEnabled) onClick() }
    }

    private fun slider(label: String, bar: SeekBar, value: TextView, onChange: (Int) -> Unit) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(text(11.5f, 0.85f).also { it.text = label }, LinearLayout.LayoutParams((56 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        bar.max = 100
        bar.progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(74, 135, 232))
        bar.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        bar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser && !syncing) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        addView(bar, LinearLayout.LayoutParams(0, (28 * d).toInt(), 1f))
        value.gravity = Gravity.END
        addView(value, LinearLayout.LayoutParams((40 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
    }
}
