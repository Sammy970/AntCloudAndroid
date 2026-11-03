package co.antcloud.osc

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import com.limelight.binding.input.virtual_controller.VirtualController
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The glass editor drawn over the controls (in the stream, or over a preview backdrop), after Steam Link's layout
 * editor: the controls stay in the open and the chrome hugs the screen edges. Drag a control to move it, pinch to
 * resize it, drag it to the bin to remove it (the tray's "+ LB" chips bring it back). A slim pill at the top has
 * the menu (Try it, Reset layout) and Done; the round button at the top right opens the Appearance card (size,
 * opacity). Mirrors the iOS OSCEditor. Touches in "Try it" go to the real controls, which send nothing.
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
    private var binHot = false

    // Pinch: the element being resized and where the fingers and the size started
    private var pinching = false
    private var pinchTarget: OscElement? = null
    private var pinchStartDistance = 1f
    private var pinchStartScale = 1f
    /** Set after a tap that only closed the Appearance card, or a pinch that ended: the rest of that gesture does nothing. */
    private var ignoring = false

    private val selectionPaint = OscGlass.selectionPaint(d)
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255); strokeWidth = d }
    private val overlapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = RED; strokeWidth = 1.4f * d }

    // Chrome
    private val menuButton = Glyph(Kind.ELLIPSIS)
    private val doneButton = Glyph(Kind.DONE, "Done").also { it.fill = ACCENT }
    private val pill = LinearLayout(context)
    private val optionsButton = Glyph(Kind.SLIDERS).also { it.glass = true }
    private val optionsCard = LinearLayout(context)
    private val sizeBar = SeekBar(context)
    private val sizeValue = text(11f, 0.7f)
    private val sizeNote = text(11f, 0.55f)
    private val opacityBar = SeekBar(context)
    private val opacityValue = text(11f, 0.7f)
    private val hint = text(12f, 0.85f)
    private val tray = LinearLayout(context)
    private val bin = Glyph(Kind.BIN)
    private val chipScroll = HorizontalScrollView(context)
    private val chipRow = LinearLayout(context)
    private var chipKeys = emptyList<OscElement>()
    private var optionsOpen = false
    private var syncing = false
    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(400).start() }

    init {
        setWillNotDraw(false)
        buildChrome()
        refreshPanel()
        showHint()
    }

    // MARK: Geometry

    private val factor get() = OscGeometry.deviceFactor(context)
    private val frameLayout get() = controller.frameLayout

    private fun area(): RectF = OscGeometry.area(frameLayout, frameLayout.width, frameLayout.height)

    private fun elementsOnScreen() = controller.elements.map { OscGeometry.element(it.elementId) }.distinct()

    private fun visibleElements() = elementsOnScreen().filter { !layout.item(it).hidden }

    private fun frame(e: OscElement) = OscGeometry.frame(e, layout, area(), factor, d)

    /** The rectangles of an element's individual buttons on screen (not the cluster's bounding box). */
    private fun partRects(e: OscElement): List<RectF> {
        val item = layout.item(e)
        val (cx, cy) = OscGeometry.center(e, item, area(), factor, d)
        return OscGeometry.parts(e, factor * item.scale, d).map { RectF(it.rect).also { r -> r.offset(cx, cy) } }
    }

    private fun touching(a: List<RectF>, b: List<RectF>) = a.any { ra -> b.any { rb -> RectF.intersects(ra, rb) } }

    /** The visible elements with a button over another visible element's button. */
    private fun overlapping(): Set<OscElement> {
        val rects = visibleElements().associateWith { partRects(it) }
        return rects.filter { (a, ra) -> rects.any { (b, rb) -> a != b && touching(ra, rb) } }.keys
    }

    private fun elementAt(x: Float, y: Float): OscElement? {
        var best: OscElement? = null
        var bestDistance = Float.MAX_VALUE
        for (e in visibleElements()) {
            val f = frame(e).also { it.inset(-10 * d, -10 * d) }
            if (!f.contains(x, y)) continue
            val dist = hypot(x - f.centerX(), y - f.centerY())
            if (dist < bestDistance) { best = e; bestDistance = dist }
        }
        return best
    }

    /** The bin's hit area in this view's coordinates, generous so a thumb can land on it. */
    private fun binRect(): RectF {
        if (tray.width == 0) return RectF()
        return RectF((tray.left + bin.left).toFloat(), (tray.top + bin.top).toFloat(), (tray.left + bin.right).toFloat(), (tray.top + bin.bottom).toFloat())
            .also { it.inset(-16 * d, -16 * d) }
    }

    // MARK: Touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (trying) {
            // Any touch brings a faded pad back
            controller.onUserTouch()
            // Let a control under the finger take the touch; swallow the rest so the stream sees nothing.
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                val x = event.getX(event.actionIndex); val y = event.getY(event.actionIndex)
                val onControl = controller.elements.any { it.visibility == View.VISIBLE && x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }
                // With floating sticks on, an empty touch belongs to the layer underneath, which spawns a stick (the
                // editor has no stream to hide it from), so let it through.
                return !onControl && !controller.isFloatingActive
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ignoring = false
                if (optionsOpen) { showOptions(false); ignoring = true } else down(event.getPointerId(0), event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (!ignoring && !pinching && event.pointerCount == 2) startPinch(event)
            MotionEvent.ACTION_MOVE -> when {
                ignoring -> {}
                pinching -> pinch(event)
                else -> {
                    val i = event.findPointerIndex(dragPointer)
                    if (i >= 0) move(event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (pinching) endPinch()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pinching) endPinch()
                up()
                ignoring = false
            }
        }
        return true
    }

    private fun down(pointer: Int, x: Float, y: Float) {
        if (dragging != null) return
        val best = elementAt(x, y)
        select(best)
        if (best != null) {
            val f = frame(best)
            dragging = best
            dragPointer = pointer
            grabX = x - f.centerX()
            grabY = y - f.centerY()
            refreshPanel()
        }
    }

    private fun move(x: Float, y: Float) {
        val e = dragging ?: return
        setBinHot(binRect().contains(x, y))
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
        val e = dragging
        if (e != null) { if (binHot) remove(e) else save() }
        dragging = null; dragPointer = -1; guideX = null; guideY = null
        setBinHot(false)
        refreshPanel()
        invalidate()
    }

    // Two fingers resize the selected control, or the one under their midpoint when nothing is selected.
    private fun startPinch(event: MotionEvent) {
        if (dragging != null) {
            // The first finger's drag is over: keep where it got to
            save()
            dragging = null; dragPointer = -1; guideX = null; guideY = null
            setBinHot(false)
        }
        val target = selected?.takeIf { it in visibleElements() }
            ?: elementAt((event.getX(0) + event.getX(1)) / 2, (event.getY(0) + event.getY(1)) / 2)
        if (target == null) { ignoring = true; refreshPanel(); return }
        select(target)
        pinchTarget = target
        pinchStartDistance = maxOf(1f, hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1)))
        pinchStartScale = layout.item(target).scale
        pinching = true
        refreshPanel()
    }

    private fun pinch(event: MotionEvent) {
        val e = pinchTarget ?: return
        if (event.pointerCount < 2) return
        val distance = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
        val scale = (pinchStartScale * distance / pinchStartDistance).coerceIn(OscLayout.MIN_SCALE, OscLayout.MAX_SCALE)
        layout.set(e, layout.item(e).copy(scale = scale))
        controller.relayout()
        refreshPanel()
        invalidate()
    }

    private fun endPinch() {
        pinching = false
        pinchTarget = null
        // The finger left over must not start dragging
        ignoring = true
        save()
        refreshPanel()
    }

    // MARK: Drawing

    override fun onDraw(canvas: Canvas) {
        if (trying) return
        // A faint blue wash tells you the pad is in edit mode
        canvas.drawColor(Color.argb(30, 74, 135, 232))
        val area = area()
        for (e in overlapping()) for (r in partRects(e)) canvas.drawRoundRect(r, 12 * d, 12 * d, overlapPaint)
        guideX?.let { canvas.drawLine(it, area.top, it, area.bottom, guidePaint) }
        guideY?.let { canvas.drawLine(area.left, it, area.right, it, guidePaint) }
        selected?.takeIf { it in visibleElements() }?.let {
            canvas.drawRoundRect(frame(it).also { r -> r.inset(-10 * d, -10 * d) }, 16 * d, 16 * d, selectionPaint)
        }
    }

    // MARK: Actions

    private fun save() = OscStore.save(context, layout)

    private fun select(e: OscElement?) {
        val was = selected
        selected = e
        if (e != was) { if (e == null) showHint() else hint.animate().alpha(0f).setDuration(200).start() }
        refreshPanel()
        invalidate()
    }

    /** Sets which element is selected (for previews and screenshots). */
    fun preselect(e: OscElement?) = select(e)

    /** Opens or closes the Appearance card (for previews and screenshots). */
    fun showOptions(on: Boolean) {
        optionsOpen = on && !trying
        optionsCard.visibility = if (optionsOpen) VISIBLE else GONE
        refreshPanel()
    }

    /** Removes controls without saving, so the tray shows (for previews and screenshots). */
    fun previewRemoved(vararg elements: OscElement) {
        for (e in elements) layout.set(e, layout.item(e).copy(hidden = true))
        controller.relayout()
        refreshPanel()
    }

    fun setTrying(on: Boolean) {
        if (on == trying) return
        trying = on
        dragging = null
        pinching = false
        setBinHot(false)
        if (on) showOptions(false)
        controller.setArranging(!on)
        refreshPanel()
        showHint()
        invalidate()
    }

    private fun remove(e: OscElement) {
        layout.set(e, layout.item(e).copy(hidden = true))
        save(); controller.relayout()
        select(null)
    }

    /** Puts a removed control back where it was, unless that would cover another one: then at its default spot. */
    private fun restore(e: OscElement) {
        layout.set(e, layout.item(e).copy(hidden = false))
        val mine = partRects(e)
        if (visibleElements().any { it != e && touching(mine, partRects(it)) }) layout.set(e, layout.item(e).copy(x = e.x, y = e.y))
        save(); controller.relayout()
        select(e)
    }

    private fun reset() {
        layout.items.clear()
        layout.opacity = OscLayout.DEFAULT_OPACITY
        save(); controller.relayout(); refreshPanel(); invalidate()
    }

    private fun setBinHot(on: Boolean) {
        if (on == binHot) return
        binHot = on
        bin.fill = if (on) RED else Color.argb(48, 255, 255, 255)
        bin.invalidate()
        bin.animate().scaleX(if (on) 1.3f else 1f).scaleY(if (on) 1.3f else 1f).setDuration(120).start()
    }

    private fun showMenu() {
        val menu = PopupMenu(context, menuButton)
        if (tryable) menu.menu.add(0, 1, 0, "Try it").apply { isCheckable = true; isChecked = trying }
        menu.menu.add(0, 2, 1, "Reset layout").isEnabled = !layout.isDefault
        menu.setOnMenuItemClickListener {
            when (it.itemId) { 1 -> setTrying(!trying); 2 -> reset() }
            true
        }
        menu.show()
    }

    private fun showHint() {
        removeCallbacks(hideHint)
        hint.animate().cancel()
        hint.text = if (trying) "Try it: nothing is sent" else "Drag to move · pinch to resize · drag to the bin to remove"
        if (trying || selected == null) {
            hint.alpha = 1f
            postDelayed(hideHint, 4000)
        } else hint.alpha = 0f
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideHint)
        super.onDetachedFromWindow()
    }

    // MARK: Chrome

    private fun buildChrome() {
        // Top centre: the menu and Done in a slim glass pill
        pill.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((5 * d).toInt(), 0, (5 * d).toInt(), 0)
            background = glass(22f)
            elevation = 8 * d
            isClickable = true
            addView(menuButton, LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()))
            addView(doneButton, LinearLayout.LayoutParams((56 * d).toInt(), (34 * d).toInt()).also { it.leftMargin = (6 * d).toInt() })
        }
        menuButton.setOnClickListener { showMenu() }
        doneButton.setOnClickListener { onDone.run() }
        addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, (44 * d).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        // Top right: Appearance
        optionsButton.setOnClickListener { showOptions(!optionsOpen) }
        addView(optionsButton, LayoutParams((44 * d).toInt(), (44 * d).toInt(), Gravity.TOP or Gravity.END))

        optionsCard.apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            background = glass(20f)
            elevation = 12 * d
            isClickable = true
            visibility = GONE
            addView(text(15f, 1f, bold = true).also { it.text = "Appearance" })
            addView(slider("Size", sizeBar, sizeValue) { p ->
                val e = selected
                if (e != null) {
                    layout.set(e, layout.item(e).copy(scale = OscLayout.MIN_SCALE + (OscLayout.MAX_SCALE - OscLayout.MIN_SCALE) * p / 100f))
                    controller.relayout(); refreshPanel(); invalidate()
                }
            }, lp(top = 10))
            addView(sizeNote.also { it.text = "Select a control to resize it" }, lp(top = 0))
            addView(slider("Opacity", opacityBar, opacityValue) { p ->
                layout.opacity = OscLayout.MIN_OPACITY + (OscLayout.MAX_OPACITY - OscLayout.MIN_OPACITY) * p / 100f
                controller.relayout(); refreshPanel()
            }, lp(top = 6))
        }
        addView(optionsCard, LayoutParams(min((280 * d).toInt(), resources.displayMetrics.widthPixels - (24 * d).toInt()), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))

        // Under the pill: a line of help that fades away and takes no touches
        hint.apply {
            setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
            background = rounded(Color.argb(110, 0, 0, 0), 12f)
            isClickable = false
        }
        addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        // Bottom centre: the bin and the chips of removed controls
        bin.fill = Color.argb(48, 255, 255, 255)
        chipScroll.apply { isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; addView(chipRow) }
        tray.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * d).toInt(), 0, (8 * d).toInt(), 0)
            background = glass(21f)
            elevation = 8 * d
            isClickable = true
            visibility = GONE
            addView(bin, LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()))
            addView(chipScroll, LinearLayout.LayoutParams(0, (34 * d).toInt()))
        }
        addView(tray, LayoutParams(LayoutParams.WRAP_CONTENT, (42 * d).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
    }

    /** Puts the chrome at the screen edges, clear of the notch and the rounded corners. */
    private fun placeChrome() {
        val cut = rootWindowInsets?.displayCutout
        val top = maxOf(8 * d, (cut?.safeInsetTop ?: 0).toFloat()).toInt()
        val side = maxOf(12 * d, (cut?.safeInsetRight ?: 0).toFloat()).toInt()
        val bottom = maxOf(8 * d, (cut?.safeInsetBottom ?: 0).toFloat()).toInt()
        (pill.layoutParams as LayoutParams).topMargin = top
        (optionsButton.layoutParams as LayoutParams).apply { topMargin = top; rightMargin = side }
        (optionsCard.layoutParams as LayoutParams).apply { topMargin = top + (52 * d).toInt(); rightMargin = side }
        (hint.layoutParams as LayoutParams).topMargin = top + (50 * d).toInt()
        (tray.layoutParams as LayoutParams).bottomMargin = bottom
        requestLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        placeChrome()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { placeChrome() }
    }

    private fun refreshPanel() {
        syncing = true
        val e = selected
        val item = e?.let { layout.item(it) }
        val scale = item?.scale ?: 1f
        sizeBar.progress = ((scale - OscLayout.MIN_SCALE) / (OscLayout.MAX_SCALE - OscLayout.MIN_SCALE) * 100).roundToInt()
        sizeValue.text = if (e != null) "${(scale * 100).roundToInt()}%" else ""
        sizeNote.visibility = if (e == null) VISIBLE else GONE
        opacityBar.progress = ((layout.opacity - OscLayout.MIN_OPACITY) / (OscLayout.MAX_OPACITY - OscLayout.MIN_OPACITY) * 100).roundToInt()
        opacityValue.text = "${(layout.opacity * 100).roundToInt()}%"
        enable(sizeBar, e != null)
        optionsButton.visibility = if (trying) GONE else VISIBLE
        if (trying) { optionsOpen = false; optionsCard.visibility = GONE }

        // The tray: while dragging (the bin) or when something was removed (the chips)
        val removed = elementsOnScreen().filter { layout.item(it).hidden }
        if (removed != chipKeys) {
            chipKeys = removed
            chipRow.removeAllViews()
            for (r in removed) chipRow.addView(chip(r), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (30 * d).toInt()).also { it.leftMargin = (6 * d).toInt() })
            chipScroll.layoutParams = (chipScroll.layoutParams as LinearLayout.LayoutParams).also { it.width = (min(removed.size * 64, 220) * d).toInt() }
        }
        chipScroll.visibility = if (removed.isEmpty()) GONE else VISIBLE
        tray.visibility = if (!trying && (dragging != null || removed.isNotEmpty())) VISIBLE else GONE
        syncing = false
    }

    private fun enable(v: View, on: Boolean) { v.isEnabled = on; v.alpha = if (on) 1f else 0.4f }

    private fun shortName(e: OscElement) = when (e) {
        OscElement.LB, OscElement.RB, OscElement.LT, OscElement.RT, OscElement.LS, OscElement.RS -> e.name
        else -> e.title
    }

    private fun chip(e: OscElement) = TextView(context).apply {
        text = "+ ${shortName(e)}"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
        background = rounded(Color.argb(48, 255, 255, 255), 15f)
        setOnClickListener { restore(e) }
    }

    // MARK: View helpers

    private fun lp(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = (top * d).toInt() }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius * d }

    private fun glass(radius: Float) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(205, 38, 40, 58), Color.argb(215, 18, 19, 30))).apply {
        cornerRadius = radius * d
        setStroke(d.roundToInt().coerceAtLeast(1), Color.argb(70, 255, 255, 255))
    }

    private fun text(size: Float, alpha: Float, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(Color.argb((alpha * 255).toInt(), 255, 255, 255))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        isSingleLine = true
    }

    private fun slider(label: String, bar: SeekBar, value: TextView, onChange: (Int) -> Unit) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(text(11.5f, 0.85f).also { it.text = label }, LinearLayout.LayoutParams((52 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        bar.max = 100
        bar.progressTintList = ColorStateList.valueOf(ACCENT)
        bar.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        bar.progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser && !syncing) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            // Sizes and opacity change live; they are written once the finger lifts
            override fun onStopTrackingTouch(s: SeekBar) { save() }
        })
        addView(bar, LinearLayout.LayoutParams(0, (28 * d).toInt(), 1f))
        value.gravity = Gravity.END
        addView(value, LinearLayout.LayoutParams((40 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private enum class Kind { ELLIPSIS, SLIDERS, DONE, BIN }

    /** A small drawn icon button (menu dots, sliders, bin) or the Done pill with its checkmark. */
    private inner class Glyph(private val kind: Kind, private val label: String = "") : View(this@OscEditorOverlay.context) {
        var fill = 0
        var glass = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        init { isClickable = true }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val cx = w / 2; val cy = h / 2
            if (glass || fill != 0) {
                p.style = Paint.Style.FILL
                p.color = if (glass) Color.argb(210, 26, 28, 44) else fill
                c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
                if (glass) {
                    p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = Color.argb(70, 255, 255, 255)
                    c.drawRoundRect(d / 2, d / 2, w - d / 2, h - d / 2, h / 2, h / 2, p)
                }
            }
            p.color = Color.WHITE
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.7f * d; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            when (kind) {
                Kind.ELLIPSIS -> {
                    p.style = Paint.Style.FILL
                    for (i in -1..1) c.drawCircle(cx + i * 6 * d, cy, 2 * d, p)
                }
                Kind.SLIDERS -> {
                    for ((dy, knob) in listOf(-4.5f to -3f, 4.5f to 3f)) {
                        p.style = Paint.Style.STROKE
                        c.drawLine(cx - 8 * d, cy + dy * d, cx + 8 * d, cy + dy * d, p)
                        p.style = Paint.Style.FILL; p.color = Color.argb(255, 38, 40, 58)
                        c.drawCircle(cx + knob * d, cy + dy * d, 3 * d, p)
                        p.style = Paint.Style.STROKE; p.color = Color.WHITE
                        c.drawCircle(cx + knob * d, cy + dy * d, 3 * d, p)
                    }
                }
                Kind.DONE -> {
                    p.style = Paint.Style.FILL; p.textSize = 13f * d; p.typeface = Typeface.DEFAULT_BOLD
                    val textWidth = p.measureText(label)
                    val total = 11 * d + 5 * d + textWidth
                    val x = (w - total) / 2
                    c.drawText(label, x + 16 * d, cy - (p.ascent() + p.descent()) / 2, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d
                    c.drawPath(Path().apply { moveTo(x, cy); lineTo(x + 4 * d, cy + 4 * d); lineTo(x + 11 * d, cy - 4 * d) }, p)
                }
                Kind.BIN -> {
                    c.drawLine(cx - 8 * d, cy - 6 * d, cx + 8 * d, cy - 6 * d, p)
                    c.drawLine(cx - 3 * d, cy - 9 * d, cx + 3 * d, cy - 9 * d, p)
                    c.drawRoundRect(cx - 6 * d, cy - 4 * d, cx + 6 * d, cy + 9 * d, 2 * d, 2 * d, p)
                    c.drawLine(cx, cy - 1 * d, cx, cy + 6 * d, p)
                }
            }
        }
    }

    private companion object {
        val ACCENT = Color.rgb(74, 135, 232)
        val RED = Color.rgb(235, 78, 78)
    }
}
