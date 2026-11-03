package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.List;

import co.antcloud.osc.OscElement;
import co.antcloud.osc.OscGeometry;
import co.antcloud.osc.OscItem;
import co.antcloud.osc.OscLayout;

/**
 * AntCloud: floating sticks. A transparent layer between the stream and the controls. While the layout has
 * floating sticks on, a touch on empty screen spawns the real left (left half) or right (right half) stick
 * view under the finger and feeds it that finger's moves, so it behaves exactly like the fixed stick. Anything
 * on top of this layer (buttons, D-pad, bumpers, the keyboard button) is hit first and wins.
 *
 * One stick per side: a second finger landing on a side whose stick is already out is swallowed (ignored, not
 * sent to the stream). With floating sticks off the layer returns false for every touch, so the stream sees
 * exactly what it saw before.
 */
class FloatingSticks extends View {
    private static final long FADE_OUT_MS = 150;
    private static final int LEFT = 0, RIGHT = 1;

    private final VirtualController controller;
    private final AnalogStick[] sticks = new AnalogStick[2];
    private final int[] pointerIds = {-1, -1};
    private final boolean[] out = new boolean[2];
    private final int[] lefts = new int[2], tops = new int[2], widths = new int[2], heights = new int[2];
    private final long[] downTimes = new long[2];
    private final int[] generation = new int[2];

    FloatingSticks(Context context, VirtualController controller) {
        super(context);
        this.controller = controller;
    }

    /** True from the spawn until the fade after release is done: the layout must not touch that stick view. */
    boolean isOut(int elementId) {
        return out[elementId == VirtualControllerElement.EID_LS ? LEFT : RIGHT];
    }

    boolean hasActive() {
        return pointerIds[LEFT] != -1 || pointerIds[RIGHT] != -1;
    }

    private AnalogStick findStick(int side) {
        int id = side == LEFT ? VirtualControllerElement.EID_LS : VirtualControllerElement.EID_RS;
        for (VirtualControllerElement element : controller.getElements()) {
            if (element.getElementId() == id && element instanceof AnalogStick) {
                return (AnalogStick) element;
            }
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!controller.isFloatingActive()) {
            return false;
        }

        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                // A mouse click is the stream's, not a finger
                if (event.getToolType(index) == MotionEvent.TOOL_TYPE_MOUSE) {
                    return false;
                }
                boolean consumed = spawn(event, index);
                if (consumed) {
                    controller.onUserTouch();
                }
                return consumed;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int side = 0; side < 2; side++) {
                    if (pointerIds[side] != -1) {
                        int index = event.findPointerIndex(pointerIds[side]);
                        if (index >= 0) {
                            feed(side, MotionEvent.ACTION_MOVE, event.getEventTime(), event.getX(index), event.getY(index));
                        }
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int id = event.getPointerId(event.getActionIndex());
                for (int side = 0; side < 2; side++) {
                    if (pointerIds[side] == id) {
                        release(side, MotionEvent.ACTION_UP, event.getEventTime(),
                                event.getX(event.getActionIndex()), event.getY(event.getActionIndex()), true);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                for (int side = 0; side < 2; side++) {
                    if (pointerIds[side] != -1) {
                        int index = event.findPointerIndex(pointerIds[side]);
                        float x = index >= 0 ? event.getX(index) : lefts[side] + widths[side] / 2f;
                        float y = index >= 0 ? event.getY(index) : tops[side] + heights[side] / 2f;
                        release(side, action == MotionEvent.ACTION_UP ? MotionEvent.ACTION_UP : MotionEvent.ACTION_CANCEL,
                                event.getEventTime(), x, y, true);
                    }
                }
                return true;
            }
        }
        return false;
    }

    private boolean spawn(MotionEvent event, int index) {
        float x = event.getX(index), y = event.getY(index);
        int side = x < getWidth() / 2f ? LEFT : RIGHT;
        AnalogStick stick = findStick(side);
        if (stick == null) {
            return false;
        }
        OscLayout layout = controller.getLayout();
        OscElement element = OscGeometry.INSTANCE.element(stick.getElementId());
        OscItem item = layout.item(element);
        // A stick the player hid never appears
        if (item.getHidden()) {
            return false;
        }
        // This side already has its stick out: swallow the touch
        if (pointerIds[side] != -1) {
            return true;
        }

        float density = getResources().getDisplayMetrics().density;
        float factor = OscGeometry.INSTANCE.deviceFactor(getContext());
        List<OscGeometry.Part> parts = OscGeometry.INSTANCE.parts(element, factor * item.getScale(), density);
        RectF rect = parts.get(0).getRect();
        int w = Math.max(1, (int) rect.width()), h = Math.max(1, (int) rect.height());

        // Centred on the finger, kept inside the usable area like any control
        RectF area = OscGeometry.INSTANCE.area(controller.getFrameLayout(), getWidth(), getHeight());
        float minX = area.left + w / 2f, minY = area.top + h / 2f;
        float cx = Math.min(Math.max(x, minX), Math.max(minX, area.right - w / 2f));
        float cy = Math.min(Math.max(y, minY), Math.max(minY, area.bottom - h / 2f));
        int left = (int) (cx - w / 2f), top = (int) (cy - h / 2f);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) stick.getLayoutParams();
        lp.width = w;
        lp.height = h;
        lp.leftMargin = left;
        lp.topMargin = top;
        stick.setLayoutParams(lp);

        generation[side]++;
        stick.animate().cancel();
        stick.setAlpha(layout.getOpacity());
        stick.setVisibility(View.VISIBLE);
        // The stick reads its own size when it gets the touch, so lay it out now rather than on the next frame
        stick.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        stick.layout(left, top, left + w, top + h);

        sticks[side] = stick;
        lefts[side] = left;
        tops[side] = top;
        widths[side] = w;
        heights[side] = h;
        pointerIds[side] = event.getPointerId(index);
        out[side] = true;
        downTimes[side] = event.getEventTime();

        // The knob starts in the middle; the finger's moves then pull it, as on the fixed stick
        feed(side, MotionEvent.ACTION_DOWN, event.getEventTime(), left + w / 2f, top + h / 2f);
        return true;
    }

    /** Gives the stick one event of its finger, in the stick's own coordinates. */
    private void feed(int side, int action, long time, float x, float y) {
        MotionEvent translated = MotionEvent.obtain(downTimes[side], time, action, x - lefts[side], y - tops[side], 0);
        sticks[side].onTouchEvent(translated);
        translated.recycle();
    }

    private void release(int side, int action, long time, float x, float y, boolean animate) {
        if (pointerIds[side] == -1) {
            return;
        }
        // The stick zeroes its output and clicks off on the up
        feed(side, action, time, x, y);
        pointerIds[side] = -1;

        final AnalogStick stick = sticks[side];
        final int side_ = side;
        final int gen = ++generation[side];
        if (animate) {
            stick.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction(new Runnable() {
                @Override
                public void run() {
                    if (generation[side_] == gen) {
                        hideNow(side_);
                    }
                }
            });
        } else {
            stick.animate().cancel();
            hideNow(side);
        }
    }

    private void hideNow(int side) {
        out[side] = false;
        if (sticks[side] != null) {
            sticks[side].setVisibility(View.GONE);
        }
    }

    /** Lifts every floating stick (output goes to zero). [animate] false removes the visuals at once. */
    void releaseAll(boolean animate) {
        for (int side = 0; side < 2; side++) {
            if (pointerIds[side] != -1) {
                release(side, MotionEvent.ACTION_CANCEL, android.os.SystemClock.uptimeMillis(),
                        lefts[side] + widths[side] / 2f, tops[side] + heights[side] / 2f, animate);
            } else if (!animate && out[side]) {
                generation[side]++;
                if (sticks[side] != null) {
                    sticks[side].animate().cancel();
                }
                hideNow(side);
            }
        }
    }
}
