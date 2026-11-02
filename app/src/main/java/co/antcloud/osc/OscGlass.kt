package co.antcloud.osc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import com.limelight.binding.input.virtual_controller.VirtualControllerElement as E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Draws the on-screen gamepad's glass controls. Each control is rendered once into a bitmap (and cached) instead
 * of blurring the stream behind it: a live blur under ~15 controls would cost GPU time on every video frame, and
 * latency matters more here than a true refraction.
 *
 * The look: a smoky, translucent body so the picture shows through, a soft highlight along the top edge like light
 * on glass, a thin bright rim that fades toward the bottom-right, and a gentle drop shadow. A pressed control
 * fills with AntCloud blue and glows.
 */
object OscGlass {
    enum class Shape { CIRCLE, PILL, GEM, DPAD, STICK_BASE, KNOB }

    /** Everything that changes the picture; sizes are in pixels. */
    data class Spec(
        val shape: Shape, val w: Int, val h: Int, val label: String = "", val labelAngle: Int = 0,
        val pressed: Boolean = false, val dir: Int = 0, val density: Float = 3f,
    )

    private val BLUE = Color.rgb(74, 135, 232) // (0.29, 0.53, 0.91)
    private val cache = object : LruCache<Spec, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: Spec, value: Bitmap) = value.byteCount
    }

    private fun white(a: Float) = Color.argb((a * 255).toInt(), 255, 255, 255)
    private fun black(a: Float) = Color.argb((a * 255).toInt(), 0, 0, 0)

    private fun bitmap(spec: Spec): Bitmap = cache.get(spec) ?: render(spec).also { cache.put(spec, it) }
    private fun pad(density: Float) = 14 * density

    private fun blit(canvas: Canvas, spec: Spec, cx: Float, cy: Float, squash: Boolean) {
        val b = bitmap(spec)
        canvas.save()
        if (squash && spec.pressed) canvas.scale(0.94f, 0.94f, cx, cy)
        canvas.drawBitmap(b, cx - b.width / 2f, cy - b.height / 2f, null)
        canvas.restore()
    }

    // MARK: Entry points used by the stock elements

    /** A button, bumper, trigger, Back/Start or LS/RS click, filling a view of [w] × [h] pixels. */
    @JvmStatic fun drawButton(canvas: Canvas, id: Int, label: String, w: Int, h: Int, pressed: Boolean, density: Float) {
        val shape = when (id) {
            E.EID_LB, E.EID_RB, E.EID_LT, E.EID_RT -> Shape.GEM
            E.EID_BACK, E.EID_START, E.EID_GDB -> Shape.PILL
            else -> Shape.CIRCLE
        }
        val angle = when (id) { E.EID_LB, E.EID_LT -> -42; E.EID_RB, E.EID_RT -> 42; else -> 0 }
        blit(canvas, Spec(shape, w, h, label, angle, pressed, 0, density), w / 2f, h / 2f, true)
    }

    /** The D-pad: four shield pieces, lit by the bits of [direction] (stock DigitalPad flags: left 1, up 2, right 4, down 8). */
    @JvmStatic fun drawDpad(canvas: Canvas, w: Int, direction: Int, density: Float) {
        val k = w / (2 * 41f + OscGeometry.DPAD_LONG)
        val s = (OscGeometry.DPAD_SHORT * k).toInt()
        val l = (OscGeometry.DPAD_LONG * k).toInt()
        val o = 41f * k
        val c = w / 2f
        // dir: 0 up, 1 right, 2 down, 3 left
        blit(canvas, Spec(Shape.DPAD, s, l, "", 0, direction and 2 != 0, 0, density), c, c - o, true)
        blit(canvas, Spec(Shape.DPAD, l, s, "", 0, direction and 4 != 0, 1, density), c + o, c, true)
        blit(canvas, Spec(Shape.DPAD, s, l, "", 0, direction and 8 != 0, 2, density), c, c + o, true)
        blit(canvas, Spec(Shape.DPAD, l, s, "", 0, direction and 1 != 0, 3, density), c - o, c, true)
    }

    /** A stick: the big ring, and the knob [kx], [ky] pixels from its centre. */
    @JvmStatic fun drawStick(canvas: Canvas, w: Int, left: Boolean, kx: Float, ky: Float, pressed: Boolean, density: Float) {
        val c = w / 2f
        blit(canvas, Spec(Shape.STICK_BASE, w, w, "", 0, false, 0, density), c, c, false)
        val d = (w * OscGeometry.STICK_INNER / OscGeometry.STICK_OUTER).toInt()
        blit(canvas, Spec(Shape.KNOB, d, d, if (left) "L" else "R", 0, pressed, 0, density), c + kx, c + ky, false)
    }

    // MARK: Rendering

    private fun render(spec: Spec): Bitmap {
        val pad = pad(spec.density)
        val bmp = Bitmap.createBitmap((spec.w + pad * 2).toInt(), (spec.h + pad * 2).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rect = RectF(pad, pad, pad + spec.w, pad + spec.h)
        val path = shapePath(spec, rect)
        val big = spec.shape == Shape.STICK_BASE
        val pressed = spec.pressed
        val d = spec.density
        val bounds = RectF().also { path.computeBounds(it, true) }

        // Shadow (and glow when pressed), drawn from a solid shape that is then punched out so the glass stays see-through.
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            if (pressed) setShadowLayer(13 * d, 0f, 0f, Color.argb(217, 74, 135, 232))
            else setShadowLayer((if (big) 12 else 8) * d, 0f, 2.5f * d, black(0.42f))
        }
        canvas.drawPath(path, shadow)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })

        // Body, clipped to the shape.
        canvas.save()
        canvas.clipPath(path)
        fun fill(shader: Shader) = canvas.drawRect(bounds, Paint().apply { this.shader = shader })
        fun vertical(top: Float, bottom: Float, colors: IntArray, pos: FloatArray?) = LinearGradient(bounds.centerX(), top, bounds.centerX(), bottom, colors, pos, Shader.TileMode.CLAMP)

        // Smoky tint: darker than the picture so white labels stay readable on bright scenes.
        canvas.drawRect(bounds, Paint().apply { color = if (pressed) Color.argb(158, 74, 135, 232) else Color.argb((if (big) 0.20f else 0.36f).times(255).toInt(), 15, 15, 15) })
        // Light falling from above.
        fill(vertical(bounds.top, bounds.bottom, intArrayOf(white(if (pressed) 0.34f else 0.27f), white(0.07f), white(0.02f)), floatArrayOf(0f, 0.5f, 1f)))
        // A little shade pooling at the bottom edge gives the body depth.
        fill(vertical(bounds.top, bounds.bottom, intArrayOf(0, 0, black(0.20f)), floatArrayOf(0f, 0.55f, 1f)))

        if (spec.shape == Shape.KNOB) {
            // A glass bead: the highlight sits off-centre, toward the light.
            fill(RadialGradient(bounds.left + bounds.width() * 0.36f, bounds.top + bounds.height() * 0.30f, bounds.width() * 0.62f,
                intArrayOf(white(0.55f), white(0f)), null, Shader.TileMode.CLAMP))
        } else if (!big) {
            // The glossy band across the top.
            val band = RectF(bounds.left + bounds.width() * 0.10f, bounds.top + bounds.height() * 0.05f,
                bounds.left + bounds.width() * 0.90f, bounds.top + bounds.height() * 0.49f)
            canvas.save()
            val r = min(band.height() * 0.5f, 14 * d)
            canvas.clipPath(Path().apply { addRoundRect(band, r, r, Path.Direction.CW) })
            canvas.drawRect(band, Paint().apply { shader = LinearGradient(band.centerX(), band.top, band.centerX(), band.bottom, white(0.36f), white(0.03f), Shader.TileMode.CLAMP) })
            canvas.restore()
        }
        canvas.restore()

        // Rim: bright where the light hits (top-left), fading toward the bottom-right.
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (if (big) 1.2f else 1.5f) * d
            shader = LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                intArrayOf(white(if (pressed) 0.95f else 0.80f), white(0.16f), white(0.42f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        })

        decorate(canvas, spec, rect)
        return bmp
    }

    /** Labels, the D-pad arrow, and the stick ring's markings. */
    private fun decorate(canvas: Canvas, spec: Spec, rect: RectF) {
        val d = spec.density
        when (spec.shape) {
            Shape.STICK_BASE -> {
                // Four notches on the rim show where the stick's travel ends.
                val r = rect.width() / 2
                val notch = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white(0.55f); strokeWidth = 2.2f * d; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
                for (i in 0 until 4) {
                    val a = i * PI.toFloat() / 2
                    canvas.drawLine(rect.centerX() + cos(a) * (r - 11 * d), rect.centerY() + sin(a) * (r - 11 * d),
                        rect.centerX() + cos(a) * (r - 5 * d), rect.centerY() + sin(a) * (r - 5 * d), notch)
                }
                // A faint guide ring the knob sits on at rest.
                canvas.drawCircle(rect.centerX(), rect.centerY(), r * 0.46f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = white(0.10f); strokeWidth = d })
            }
            Shape.DPAD -> {
                // A small arrow near the outer end, pointing away from the centre.
                val cx = rect.centerX(); val cy = rect.centerY()
                val short = min(rect.width(), rect.height()); val long = max(rect.width(), rect.height())
                val u = short * 0.2f
                val tipY = cy - long * 0.5f + long * 0.2f
                val glyph = Path().apply { moveTo(cx, tipY); lineTo(cx + u, tipY + u * 1.1f); lineTo(cx - u, tipY + u * 1.1f); close() }
                glyph.transform(Matrix().apply { setRotate(spec.dir * 90f, cx, cy) })
                canvas.drawPath(glyph, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white(0.92f); setShadowLayer(2 * d, 0f, d, black(0.5f)) })
            }
            else -> {}
        }
        if (spec.label.isEmpty()) return
        val size = when (spec.shape) {
            Shape.PILL -> spec.h * 0.36f
            Shape.GEM -> min(spec.w, spec.h) * 0.31f
            Shape.KNOB -> spec.w * 0.34f
            else -> min(spec.w, spec.h) * (if (spec.label.length > 1) 0.34f else 0.45f)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            color = white(0.97f)
            textAlign = Paint.Align.CENTER
            if (spec.shape == Shape.PILL) letterSpacing = 0.06f
            setShadowLayer(2.5f * d, 0f, d, black(0.5f))
        }
        canvas.save()
        canvas.translate(rect.centerX(), rect.centerY())
        canvas.rotate(spec.labelAngle.toFloat())
        canvas.drawText(spec.label, 0f, -(text.ascent() + text.descent()) / 2, text)
        canvas.restore()
    }

    // MARK: Shapes

    private fun shapePath(spec: Spec, rect: RectF): Path = when (spec.shape) {
        Shape.CIRCLE, Shape.STICK_BASE, Shape.KNOB -> Path().apply { addOval(rect, Path.Direction.CW) }
        Shape.PILL -> Path().apply { val r = min(rect.height() * 0.32f, 13 * spec.density); addRoundRect(rect, r, r, Path.Direction.CW) }
        Shape.GEM -> {
            val w = rect.width(); val h = rect.height()
            roundedPolygon(listOf(
                rect.centerX() to rect.top, rect.right to rect.top + h * 0.26f, rect.right to rect.bottom - h * 0.26f,
                rect.centerX() to rect.bottom, rect.left to rect.bottom - h * 0.26f, rect.left to rect.top + h * 0.26f,
            ), min(w, h) * 0.2f)
        }
        Shape.DPAD -> {
            // Built pointing up in a short x long box, then turned to face the right way.
            val short = min(rect.width(), rect.height()); val long = max(rect.width(), rect.height())
            val box = RectF(rect.centerX() - short / 2, rect.centerY() - long / 2, rect.centerX() + short / 2, rect.centerY() + long / 2)
            roundedPolygon(listOf(
                box.left to box.top, box.right to box.top, box.right to box.top + long * 0.60f,
                box.centerX() to box.bottom, box.left to box.top + long * 0.60f,
            ), short * 0.24f).also { it.transform(Matrix().apply { setRotate(spec.dir * 90f, rect.centerX(), rect.centerY()) }) }
        }
    }

    /** A polygon with every corner rounded by a tangent arc of [radius] (drawn as a quadratic curve). */
    private fun roundedPolygon(points: List<Pair<Float, Float>>, radius: Float): Path {
        val n = points.size
        val path = Path()
        for (i in 0 until n) {
            val (px, py) = points[(i + n - 1) % n]; val (x, y) = points[i]; val (nx, ny) = points[(i + 1) % n]
            val toPrev = hypot(px - x, py - y); val toNext = hypot(nx - x, ny - y)
            val angle = abs((atan2(py - y, px - x) - atan2(ny - y, nx - x)).toDouble()).let { if (it > PI) 2 * PI - it else it }
            val cut = min(radius / tan(angle / 2).toFloat(), min(toPrev, toNext) / 2)
            val ax = x + (px - x) / toPrev * cut; val ay = y + (py - y) / toPrev * cut
            val bx = x + (nx - x) / toNext * cut; val by = y + (ny - y) / toNext * cut
            if (i == 0) path.moveTo(ax, ay) else path.lineTo(ax, ay)
            path.quadTo(x, y, bx, by)
        }
        path.close()
        return path
    }


    /** Dashed outline around the selected control in the editor. */
    @JvmStatic fun selectionPaint(density: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = white(0.92f); strokeWidth = 1.6f * density
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 5 * density), 0f)
    }
}
