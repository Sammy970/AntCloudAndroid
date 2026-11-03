package co.antcloud.osc

import android.content.Context
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import com.limelight.binding.input.virtual_controller.VirtualController
import com.limelight.binding.input.virtual_controller.VirtualControllerElement as E
import org.json.JSONObject
import kotlin.math.max

// The on-screen gamepad's layout, the same model as the iOS app: which controls exist, where they sit (as
// fractions of the usable area, so a layout carries over between screens), how big they are, and which the
// player hid. The four face buttons and the four D-pad pieces move as one cluster each.

/** A movable control. */
enum class OscElement(val title: String, val x: Float, val y: Float) {
    LEFT_STICK("Left stick", 0.14f, 0.76f),
    RIGHT_STICK("Right stick", 0.62f, 0.77f),
    DPAD("D-pad", 0.36f, 0.76f),
    FACE("A B X Y buttons", 0.85f, 0.73f),
    BACK("Back", 0.27f, 0.07f),
    START("Start", 0.73f, 0.07f),
    // Below the editor's pill and hint, which take the top centre.
    GUIDE("Guide", 0.50f, 0.26f),
    LB("Left bumper (LB)", 0.165f, 0.37f),
    RB("Right bumper (RB)", 0.835f, 0.37f),
    LT("Left trigger (LT)", 0.10f, 0.25f),
    RT("Right trigger (RT)", 0.90f, 0.25f),
    LS("Left stick click (LS)", 0.045f, 0.54f),
    RS("Right stick click (RS)", 0.955f, 0.54f);

    val key get() = name.lowercase()
}

data class OscItem(val x: Float, val y: Float, val scale: Float = 1f, val hidden: Boolean = false)

class OscLayout(
    var opacity: Float = DEFAULT_OPACITY,
    val items: MutableMap<String, OscItem> = mutableMapOf(),
    /** Touch anywhere on the left or right half of the screen and a stick appears under the finger. The fixed sticks are hidden then. */
    var floatingSticks: Boolean = false,
    /** Seconds without a touch before the pad fades out; 0 keeps it always visible. */
    var fadeAfter: Int = 0,
) {
    fun item(e: OscElement) = items[e.key] ?: OscItem(e.x, e.y)
    fun set(e: OscElement, item: OscItem) { items[e.key] = item }
    val isDefault get() = items.isEmpty() && opacity == DEFAULT_OPACITY && !floatingSticks && fadeAfter == 0

    fun toJson(): String = JSONObject().put("opacity", opacity.toDouble()).put("floatingSticks", floatingSticks).put("fadeAfter", fadeAfter).put("items", JSONObject().also { o ->
        items.forEach { (k, v) -> o.put(k, JSONObject().put("x", v.x.toDouble()).put("y", v.y.toDouble()).put("scale", v.scale.toDouble()).put("hidden", v.hidden)) }
    }).toString()

    companion object {
        const val DEFAULT_OPACITY = 0.9f
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 1.8f
        const val MIN_OPACITY = 0.3f
        const val MAX_OPACITY = 1f
        /** The idle times the Controls screen offers, in seconds (0 = never fade). */
        val FADE_CHOICES = listOf(0, 5, 10, 20)

        /** Layouts saved before the new fields existed have no keys for them: they fall back to the defaults. */
        fun fromJson(text: String): OscLayout {
            val json = JSONObject(text)
            val layout = OscLayout(
                json.optDouble("opacity", DEFAULT_OPACITY.toDouble()).toFloat().coerceIn(MIN_OPACITY, MAX_OPACITY),
                floatingSticks = json.optBoolean("floatingSticks", false),
                fadeAfter = json.optInt("fadeAfter", 0).let { if (it in FADE_CHOICES) it else 0 },
            )
            val items = json.optJSONObject("items") ?: return layout
            for (e in OscElement.entries) {
                val o = items.optJSONObject(e.key) ?: continue
                layout.set(e, OscItem(o.optDouble("x", e.x.toDouble()).toFloat(), o.optDouble("y", e.y.toDouble()).toFloat(),
                    o.optDouble("scale", 1.0).toFloat().coerceIn(MIN_SCALE, MAX_SCALE), o.optBoolean("hidden", false)))
            }
            return layout
        }
    }
}

/** The player's layout, kept in SharedPreferences so the stream and the editor see the same one. */
object OscStore {
    private const val PREFS = "antcloud_osc"
    private const val KEY = "antcloud.osc.layout.v1"

    @JvmStatic fun load(context: Context): OscLayout = runCatching {
        OscLayout.fromJson(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)!!)
    }.getOrDefault(OscLayout())

    @JvmStatic fun save(context: Context, layout: OscLayout) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (layout.isDefault) edit.remove(KEY) else edit.putString(KEY, layout.toJson())
        edit.apply()
    }

    @JvmStatic fun reset(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
}

/** Sizes (dp, at the standard scale), rectangles and placement of the controls. */
object OscGeometry {
    private const val FACE = 50f; private const val FACE_OX = 68f; private const val FACE_OY = 55f
    private const val DPAD_OFFSET = 41f; const val DPAD_SHORT = 42f; const val DPAD_LONG = 52f
    const val STICK_OUTER = 124f; const val STICK_INNER = 58f
    private const val GEM_W = 58f; private const val GEM_H = 54f
    private const val PILL_W = 66f; private const val PILL_H = 38f
    private const val CLICK = 46f

    /** One view of an element: its Moonlight element id and its rectangle around the element's centre. */
    class Part(val id: Int, val rect: RectF)

    fun element(id: Int): OscElement = when (id) {
        E.EID_A, E.EID_B, E.EID_X, E.EID_Y -> OscElement.FACE
        E.EID_DPAD -> OscElement.DPAD
        E.EID_LS -> OscElement.LEFT_STICK
        E.EID_RS -> OscElement.RIGHT_STICK
        E.EID_BACK -> OscElement.BACK
        E.EID_START -> OscElement.START
        E.EID_GDB -> OscElement.GUIDE
        E.EID_LB -> OscElement.LB
        E.EID_RB -> OscElement.RB
        E.EID_LT -> OscElement.LT
        E.EID_RT -> OscElement.RT
        E.EID_LSB -> OscElement.LS
        else -> OscElement.RS
    }

    /** Size factor for the screen: phones under 340dp tall shrink a little, tablets grow. */
    fun deviceFactor(context: Context): Float {
        val c = context.resources.configuration
        return if (c.smallestScreenWidthDp >= 600) 1.3f else if (c.screenHeightDp in 1..339) 0.88f else 1f
    }

    fun parts(e: OscElement, f: Float, density: Float): List<Part> {
        val k = f * density
        fun rect(w: Float, h: Float, cx: Float = 0f, cy: Float = 0f) = RectF((cx - w / 2) * k, (cy - h / 2) * k, (cx + w / 2) * k, (cy + h / 2) * k)
        return when (e) {
            OscElement.FACE -> listOf(Part(E.EID_A, rect(FACE, FACE, 0f, FACE_OY)), Part(E.EID_B, rect(FACE, FACE, FACE_OX, 0f)),
                Part(E.EID_X, rect(FACE, FACE, -FACE_OX, 0f)), Part(E.EID_Y, rect(FACE, FACE, 0f, -FACE_OY)))
            OscElement.DPAD -> (2 * DPAD_OFFSET + DPAD_LONG).let { listOf(Part(E.EID_DPAD, rect(it, it))) }
            OscElement.LEFT_STICK -> listOf(Part(E.EID_LS, rect(STICK_OUTER, STICK_OUTER)))
            OscElement.RIGHT_STICK -> listOf(Part(E.EID_RS, rect(STICK_OUTER, STICK_OUTER)))
            OscElement.BACK -> listOf(Part(E.EID_BACK, rect(PILL_W, PILL_H)))
            OscElement.START -> listOf(Part(E.EID_START, rect(PILL_W, PILL_H)))
            OscElement.GUIDE -> listOf(Part(E.EID_GDB, rect(PILL_W, PILL_H)))
            OscElement.LB -> listOf(Part(E.EID_LB, rect(GEM_W, GEM_H)))
            OscElement.RB -> listOf(Part(E.EID_RB, rect(GEM_W, GEM_H)))
            OscElement.LT -> listOf(Part(E.EID_LT, rect(GEM_W, GEM_H)))
            OscElement.RT -> listOf(Part(E.EID_RT, rect(GEM_W, GEM_H)))
            OscElement.LS -> listOf(Part(E.EID_LSB, rect(CLICK, CLICK)))
            OscElement.RS -> listOf(Part(E.EID_RSB, rect(CLICK, CLICK)))
        }
    }

    fun extent(parts: List<Part>) = RectF(parts[0].rect).also { r -> parts.forEach { r.union(it.rect) } }

    /** The area controls may use: the container minus the notch side and a small margin. */
    fun area(container: View, width: Int, height: Int): RectF {
        val d = container.resources.displayMetrics.density
        val cut = container.rootWindowInsets?.displayCutout
        val left = max(10 * d, (cut?.safeInsetLeft ?: 0) * 0.55f)
        val right = max(10 * d, (cut?.safeInsetRight ?: 0) * 0.55f)
        return RectF(left, 8 * d, max(left, width - right), max(8 * d, height - 8 * d))
    }

    /** Where an element's centre is, with its extent kept inside the area. */
    fun center(e: OscElement, item: OscItem, area: RectF, f: Float, density: Float): Pair<Float, Float> {
        val ext = extent(parts(e, f * item.scale, density))
        val cx = (area.left + item.x * area.width()).coerceIn(area.left - ext.left, max(area.left - ext.left, area.right - ext.right))
        val cy = (area.top + item.y * area.height()).coerceIn(area.top - ext.top, max(area.top - ext.top, area.bottom - ext.bottom))
        return cx to cy
    }

    /** Absolute frame of an element on screen. */
    fun frame(e: OscElement, layout: OscLayout, area: RectF, f: Float, density: Float): RectF {
        val item = layout.item(e)
        val (cx, cy) = center(e, item, area, f, density)
        return extent(parts(e, f * item.scale, density)).also { it.offset(cx, cy) }
    }

    /**
     * Sizes and places every control view of the controller. While arranging, everything shows (hidden controls
     * faintly); otherwise hidden ones are gone and the whole pad takes the layout's opacity.
     */
    @JvmStatic fun apply(controller: VirtualController, container: View, width: Int, height: Int, layout: OscLayout, arranging: Boolean) {
        val density = container.resources.displayMetrics.density
        val f = deviceFactor(container.context)
        val area = area(container, width, height)
        // Floating sticks replace the fixed ones (unless a floating one is out right now, which owns its view)
        val floating = controller.isFloatingActive()
        for (view in controller.elements) {
            if (floating && (view.elementId == E.EID_LS || view.elementId == E.EID_RS)) {
                if (!controller.isFloatingStickOut(view.elementId)) view.visibility = View.GONE
                continue
            }
            val e = element(view.elementId)
            val item = layout.item(e)
            val (cx, cy) = center(e, item, area, f, density)
            val part = parts(e, f * item.scale, density).first { it.id == view.elementId }
            val lp = view.layoutParams as FrameLayout.LayoutParams
            lp.width = max(1, part.rect.width().toInt())
            lp.height = max(1, part.rect.height().toInt())
            lp.leftMargin = (cx + part.rect.left).toInt()
            lp.topMargin = (cy + part.rect.top).toInt()
            view.layoutParams = lp
            view.visibility = if (arranging || !item.hidden) View.VISIBLE else View.GONE
            view.alpha = if (arranging) (if (item.hidden) 0.3f else 1f) else if (controller.isFaded()) 0f else layout.opacity
        }
    }

    fun snapTargets(controller: VirtualController, except: OscElement?, layout: OscLayout, area: RectF, f: Float, density: Float): List<RectF> =
        controller.elements.map { element(it.elementId) }.distinct()
            .filter { it != except && !layout.item(it).hidden }.map { frame(it, layout, area, f, density) }

}
