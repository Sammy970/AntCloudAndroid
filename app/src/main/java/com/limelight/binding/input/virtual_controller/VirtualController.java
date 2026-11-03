/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.ControllerHandler;

import co.antcloud.osc.OscEditorOverlay;
import co.antcloud.osc.OscGeometry;
import co.antcloud.osc.OscLayout;
import co.antcloud.osc.OscStore;

import java.util.ArrayList;
import java.util.List;

public class VirtualController {
    public static class ControllerInputContext {
        public short inputMap = 0x0000;
        public byte leftTrigger = 0x00;
        public byte rightTrigger = 0x00;
        public short rightStickX = 0x0000;
        public short rightStickY = 0x0000;
        public short leftStickX = 0x0000;
        public short leftStickY = 0x0000;
    }

    public enum ControllerMode {
        Active,
        MoveButtons,
        ResizeButtons
    }

    private static final boolean _PRINT_DEBUG_INFORMATION = false;

    private final ControllerHandler controllerHandler;
    private final Context context;
    private final Handler handler;

    private final Runnable delayedRetransmitRunnable = new Runnable() {
        @Override
        public void run() {
            sendControllerInputContextInternal();
        }
    };

    private FrameLayout frame_layout = null;

    ControllerMode currentMode = ControllerMode.Active;
    ControllerInputContext inputContext = new ControllerInputContext();

    private Button buttonConfigure = null;

    private List<VirtualControllerElement> elements = new ArrayList<>();

    // AntCloud: the player's layout (co.antcloud.osc) and the glass editor over it
    private OscLayout layout;
    private OscEditorOverlay editor = null;
    private boolean arranging = false;
    private boolean previewOnly = false;
    private int appliedWidth = -1, appliedHeight = -1;

    // AntCloud: floating sticks (a touch on empty screen spawns a stick) and the idle fade
    private static final long FADE_OUT_MS = 400, FADE_IN_MS = 150;
    private final FloatingSticks floating;
    private boolean hidden = false;
    private boolean faded = false;
    private boolean idlePosted = false;
    private boolean streamHeld = false;
    private long lastTouchTime = 0;
    private final Runnable idleRunnable = new Runnable() {
        @Override
        public void run() {
            idlePosted = false;
            if (!canFade()) {
                return;
            }
            long remaining = lastTouchTime + layout.getFadeAfter() * 1000L - SystemClock.uptimeMillis();
            if (remaining > 0) {
                postIdle(remaining);
            } else if (isTouchHeld()) {
                postIdle(1000);
            } else {
                fadeOut();
            }
        }
    };

    public VirtualController(final ControllerHandler controllerHandler, FrameLayout layout, final Context context) {
        this.controllerHandler = controllerHandler;
        this.frame_layout = layout;
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
        this.layout = OscStore.load(context);
        this.floating = new FloatingSticks(context, this);

        // The glass controls draw their shadow and glow outside their bounds
        frame_layout.setClipChildren(false);
        // A new screen size (rotation, split screen) re-places the controls
        frame_layout.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (!elements.isEmpty() && (r - l != appliedWidth || b - t != appliedHeight)) {
                    relayout();
                }
            }
        });

        buttonConfigure = new Button(context);
        buttonConfigure.setAlpha(0.25f);
        buttonConfigure.setFocusable(false);
        buttonConfigure.setBackgroundResource(R.drawable.ic_settings);
        buttonConfigure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                beginEditing(true, null);
            }
        });
        buttonConfigure.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                onUserTouch();
                return false;
            }
        });

    }

    Handler getHandler() {
        return handler;
    }

    public void hide() {
        hidden = true;
        cancelIdle();
        faded = false;
        floating.releaseAll(false);
        for (VirtualControllerElement element : elements) {
            element.animate().cancel();
            element.setVisibility(View.INVISIBLE);
        }
        buttonConfigure.animate().cancel();

        buttonConfigure.setVisibility(View.INVISIBLE);
    }

    public void show() {
        hidden = false;
        // Controls the player hid stay hidden
        relayout();

        buttonConfigure.setAlpha(0.25f);
        buttonConfigure.setVisibility(editor == null ? View.VISIBLE : View.GONE);
        restartIdle();
    }

    public void removeElements() {
        cancelIdle();
        faded = false;
        floating.releaseAll(false);
        frame_layout.removeView(floating);
        if (editor != null) {
            frame_layout.removeView(editor);
            editor = null;
        }
        for (VirtualControllerElement element : elements) {
            element.animate().cancel();
            frame_layout.removeView(element);
        }
        elements.clear();

        buttonConfigure.animate().cancel();
        buttonConfigure.setAlpha(0.25f);
        frame_layout.removeView(buttonConfigure);
    }

    public void setOpacity(int opacity) {
        layout.setOpacity(opacity / 100f);
        relayout();
    }

    public OscLayout getLayout() {
        return layout;
    }

    public FrameLayout getFrameLayout() {
        return frame_layout;
    }

    public Context getContext() {
        return context;
    }

    public boolean isArranging() {
        return arranging;
    }

    /** Sizes, places and shows the controls from the layout. Cheap enough to call on every change. */
    public void relayout() {
        int w = frame_layout.getWidth(), h = frame_layout.getHeight();
        if (w <= 0 || h <= 0) {
            DisplayMetrics screen = context.getResources().getDisplayMetrics();
            w = screen.widthPixels;
            h = screen.heightPixels;
        }
        appliedWidth = frame_layout.getWidth();
        appliedHeight = frame_layout.getHeight();
        if (arranging) {
            // The editor shows everything at full strength
            cancelIdle();
            if (faded) {
                faded = false;
                for (VirtualControllerElement element : elements) {
                    element.animate().cancel();
                }
            }
        }
        if (!isFloatingActive()) {
            floating.releaseAll(false);
        }
        OscGeometry.apply(this, frame_layout, w, h, layout, arranging);
        if (editor != null) {
            editor.invalidate();
        }
    }

    /**
     * Opens the glass editor over the controls. While it is open nothing reaches the PC: the pad is released
     * first, then input is held back. [tryable] adds the "Try it" preview.
     */
    public void beginEditing(boolean tryable, final Runnable onDone) {
        if (editor != null) {
            return;
        }
        inputContext = new ControllerInputContext();
        sendControllerInputContextInternal();
        handler.removeCallbacks(delayedRetransmitRunnable);
        previewOnly = true;
        buttonConfigure.setVisibility(View.GONE);

        editor = new OscEditorOverlay(context, this, tryable, new Runnable() {
            @Override
            public void run() {
                endEditing();
                if (onDone != null) {
                    onDone.run();
                }
            }
        });
        setArranging(true);
        frame_layout.addView(editor, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void endEditing() {
        if (editor != null) {
            frame_layout.removeView(editor);
            editor = null;
        }
        previewOnly = false;
        arranging = false;
        currentMode = ControllerMode.Active;
        // Rebuild so nothing the player pressed while trying stays lit
        refreshLayout();
        buttonConfigure.setVisibility(View.VISIBLE);
    }

    /** Arranging: every control shows, and touches belong to the editor. Otherwise the controls take touches (the preview does not send them). */
    public void setArranging(boolean on) {
        arranging = on;
        currentMode = on ? ControllerMode.MoveButtons : ControllerMode.Active;
        relayout();
        if (!on) {
            // Back from arranging (to "Try it"): the idle wait starts over
            restartIdle();
        }
    }


    public void addElement(VirtualControllerElement element, int x, int y, int width, int height) {
        elements.add(element);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(width, height);
        layoutParams.setMargins(x, y, 0, 0);

        frame_layout.addView(element, layoutParams);
    }

    public List<VirtualControllerElement> getElements() {
        return elements;
    }

    private static final void _DBG(String text) {
        if (_PRINT_DEBUG_INFORMATION) {
            LimeLog.info("VirtualController: " + text);
        }
    }

    public void refreshLayout() {
        if (editor != null) {
            // A new screen size while editing: just re-place
            relayout();
            return;
        }
        removeElements();

        DisplayMetrics screen = context.getResources().getDisplayMetrics();

        int buttonSize = (int)(screen.heightPixels*0.06f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(buttonSize, buttonSize);
        params.leftMargin = 15;
        params.topMargin = 15;
        frame_layout.addView(buttonConfigure, params);

        // The floating-stick layer sits just above the stream and below every control and overlay button
        View stream = frame_layout.findViewById(R.id.surfaceView);
        frame_layout.addView(floating, stream != null ? frame_layout.indexOfChild(stream) + 1 : frame_layout.getChildCount(),
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Rebuild from the player's saved layout
        layout = OscStore.load(context);
        VirtualControllerConfigurationLoader.createDefaultLayout(this, context);
        relayout();
        restartIdle();
    }

    /** True while touches on empty screen spawn floating sticks (the fixed sticks are hidden then). */
    public boolean isFloatingActive() {
        return layout.getFloatingSticks() && !arranging && !hidden;
    }

    /** True while a floating stick (by element id) is out or fading, so the layout leaves its view alone. */
    public boolean isFloatingStickOut(int elementId) {
        return floating.isOut(elementId);
    }

    /** True while the pad is faded out by the idle timer: the layout must not bring the opacity back. */
    public boolean isFaded() {
        return faded;
    }

    /** Any touch on the screen, on a control or not: brings a faded pad back and restarts the idle timer. */
    public void onUserTouch() {
        lastTouchTime = SystemClock.uptimeMillis();
        if (faded) {
            fadeIn();
        } else if (!idlePosted && canFade()) {
            postIdle(layout.getFadeAfter() * 1000L);
        }
    }

    /** As {@link #onUserTouch()}, and remembers whether a finger is still down on the stream (the pad stays then). */
    public void onUserTouch(MotionEvent event) {
        int action = event.getActionMasked();
        streamHeld = !(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ||
                (action == MotionEvent.ACTION_POINTER_UP && event.getPointerCount() <= 2));
        onUserTouch();
    }

    private boolean canFade() {
        // Not while arranging (the editor shows everything at full strength); "Try it" does fade, like the stream
        return layout.getFadeAfter() > 0 && !arranging && !hidden && !elements.isEmpty();
    }

    private boolean isTouchHeld() {
        if (streamHeld || floating.hasActive()) {
            return true;
        }
        for (VirtualControllerElement element : elements) {
            if (element.isTouching()) {
                return true;
            }
        }
        return false;
    }

    private void postIdle(long delay) {
        handler.removeCallbacks(idleRunnable);
        idlePosted = true;
        handler.postDelayed(idleRunnable, delay);
    }

    private void cancelIdle() {
        handler.removeCallbacks(idleRunnable);
        idlePosted = false;
    }

    private void restartIdle() {
        lastTouchTime = SystemClock.uptimeMillis();
        if (canFade()) {
            postIdle(layout.getFadeAfter() * 1000L);
        } else {
            cancelIdle();
        }
    }

    private void fadeOut() {
        faded = true;
        for (VirtualControllerElement element : elements) {
            element.animate().alpha(0f).setDuration(FADE_OUT_MS);
        }
        buttonConfigure.animate().alpha(0f).setDuration(FADE_OUT_MS);
    }

    private void fadeIn() {
        faded = false;
        for (VirtualControllerElement element : elements) {
            element.animate().alpha(layout.getOpacity()).setDuration(FADE_IN_MS);
        }
        buttonConfigure.animate().alpha(0.25f).setDuration(FADE_IN_MS);
        restartIdle();
    }

    public ControllerMode getControllerMode() {
        return currentMode;
    }

    public ControllerInputContext getControllerInputContext() {
        return inputContext;
    }

    private void sendControllerInputContextInternal() {
        _DBG("INPUT_MAP + " + inputContext.inputMap);
        _DBG("LEFT_TRIGGER " + inputContext.leftTrigger);
        _DBG("RIGHT_TRIGGER " + inputContext.rightTrigger);
        _DBG("LEFT STICK X: " + inputContext.leftStickX + " Y: " + inputContext.leftStickY);
        _DBG("RIGHT STICK X: " + inputContext.rightStickX + " Y: " + inputContext.rightStickY);

        if (controllerHandler != null && !previewOnly) {
            controllerHandler.reportOscState(
                    inputContext.inputMap,
                    inputContext.leftStickX,
                    inputContext.leftStickY,
                    inputContext.rightStickX,
                    inputContext.rightStickY,
                    inputContext.leftTrigger,
                    inputContext.rightTrigger
            );
        }
    }

    void sendControllerInputContext() {
        // Cancel retransmissions of prior gamepad inputs
        handler.removeCallbacks(delayedRetransmitRunnable);

        sendControllerInputContextInternal();

        // HACK: GFE sometimes discards gamepad packets when they are received
        // very shortly after another. This can be critical if an axis zeroing packet
        // is lost and causes an analog stick to get stuck. To avoid this, we retransmit
        // the gamepad state a few times unless another input event happens before then.
        handler.postDelayed(delayedRetransmitRunnable, 25);
        handler.postDelayed(delayedRetransmitRunnable, 50);
        handler.postDelayed(delayedRetransmitRunnable, 75);
    }
}
