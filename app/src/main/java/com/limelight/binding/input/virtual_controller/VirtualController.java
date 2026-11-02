/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
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

    public VirtualController(final ControllerHandler controllerHandler, FrameLayout layout, final Context context) {
        this.controllerHandler = controllerHandler;
        this.frame_layout = layout;
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
        this.layout = OscStore.load(context);

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

    }

    Handler getHandler() {
        return handler;
    }

    public void hide() {
        for (VirtualControllerElement element : elements) {
            element.setVisibility(View.INVISIBLE);
        }

        buttonConfigure.setVisibility(View.INVISIBLE);
    }

    public void show() {
        // Controls the player hid stay hidden
        relayout();

        buttonConfigure.setVisibility(editor == null ? View.VISIBLE : View.GONE);
    }

    public void removeElements() {
        if (editor != null) {
            frame_layout.removeView(editor);
            editor = null;
        }
        for (VirtualControllerElement element : elements) {
            frame_layout.removeView(element);
        }
        elements.clear();

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

        // Rebuild from the player's saved layout
        layout = OscStore.load(context);
        VirtualControllerConfigurationLoader.createDefaultLayout(this, context);
        relayout();
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
