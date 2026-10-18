package com.ivarna.fluxlinux.clone;

import android.content.Context;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * Input injection inside the shell process. Every event is tagged with the
 * clone display id, so nothing can leak to the real screen. Ported from
 * ShadowAuto's InputBridge.
 */
final class InputBridge {
    private static final int INJECT_WAIT_FOR_FINISH = 2;
    private final Object inputManager;
    private final Method inject;
    private final Method setDisplayId;

    InputBridge(ShellContext context) throws Exception {
        inputManager = context.getSystemService(Context.INPUT_SERVICE);
        inject = inputManager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
        setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
    }

    boolean tap(int displayId, int x, int y) throws Exception {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = motion(now, now, MotionEvent.ACTION_DOWN, x, y);
        MotionEvent up = motion(now, now + 80, MotionEvent.ACTION_UP, x, y);
        boolean downOk = send(displayId, down);
        boolean upOk = send(displayId, up);
        return downOk && upOk;
    }

    boolean longPress(int displayId, int x, int y, int durationMs) throws Exception {
        int duration = Math.max(450, durationMs);
        long now = SystemClock.uptimeMillis();
        boolean downOk = send(displayId, motion(now, now, MotionEvent.ACTION_DOWN, x, y));
        SystemClock.sleep(duration);
        boolean upOk = send(displayId, motion(now, now + duration, MotionEvent.ACTION_UP, x, y));
        return downOk && upOk;
    }

    boolean swipe(int displayId, int startX, int startY, int endX, int endY, int durationMs) throws Exception {
        int duration = Math.max(120, durationMs);
        int steps = Math.max(6, duration / 24);
        long downTime = SystemClock.uptimeMillis();
        boolean ok = send(displayId, motion(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY));
        for (int i = 1; i < steps; i++) {
            float progress = i / (float) steps;
            int x = Math.round(startX + (endX - startX) * progress);
            int y = Math.round(startY + (endY - startY) * progress);
            long eventTime = downTime + Math.round(duration * progress);
            ok = send(displayId, motion(downTime, eventTime, MotionEvent.ACTION_MOVE, x, y)) && ok;
            SystemClock.sleep(8);
        }
        return send(displayId, motion(downTime, downTime + duration, MotionEvent.ACTION_UP, endX, endY)) && ok;
    }

    boolean key(int displayId, int keyCode) throws Exception {
        long now = SystemClock.uptimeMillis();
        boolean downOk = send(displayId, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        boolean upOk = send(displayId, new KeyEvent(now, now + 20, KeyEvent.ACTION_UP, keyCode, 0));
        return downOk && upOk;
    }

    boolean text(int displayId, String text) throws Exception {
        if (text == null || text.isEmpty()) {
            return true;
        }
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
        if (events == null || events.length == 0) {
            throw new IllegalArgumentException("text cannot be converted to key events");
        }
        boolean ok = true;
        for (KeyEvent event : events) {
            ok = send(displayId, event) && ok;
        }
        return ok;
    }

    private MotionEvent motion(long downTime, long eventTime, int action, int x, int y) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 1f, 1f, 0, 1f, 1f, 0, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return event;
    }

    private boolean send(int displayId, InputEvent event) throws Exception {
        setDisplayId.invoke(event, displayId);
        Object result = inject.invoke(inputManager, event, INJECT_WAIT_FOR_FINISH);
        return !(result instanceof Boolean) || (Boolean) result;
    }
}
