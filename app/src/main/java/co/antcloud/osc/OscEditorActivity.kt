package co.antcloud.osc

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.limelight.binding.input.virtual_controller.VirtualController

/**
 * Customize on-screen controls without a PC: the glass editor over a preview backdrop. Reached from the panel's
 * Stream settings. Debug extras for screenshots: `--es select dpad` picks a control, `--ez try true` opens "Try it".
 */
class OscEditorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        // A dusk-over-water picture, so the glass has something to refract against
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                Color.rgb(28, 24, 70), Color.rgb(96, 62, 128), Color.rgb(232, 138, 92), Color.rgb(70, 58, 130), Color.rgb(24, 22, 62)))
        }
        setContentView(root)

        val controller = VirtualController(null, root, this)
        root.post {
            controller.refreshLayout()
            controller.beginEditing(true) { finish() }
            val overlay = root.getChildAt(root.childCount - 1) as? OscEditorOverlay
            intent.getStringExtra("select")?.let { name -> overlay?.preselect(OscElement.entries.firstOrNull { it.key == name }) }
            if (intent.getBooleanExtra("try", false)) overlay?.setTrying(true)
        }
    }
}
