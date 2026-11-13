package io.github.tuxkoh.bcsfe;

import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;

/** Long-press paints selected rows and scrolls while the finger stays at an edge. */
final class DragSelectionController {
    interface RangeSelection { void select(int first, int last); }

    private final ListView list;
    private final RangeSelection selection;
    private final float density;
    private boolean dragging;
    private boolean frameScheduled;
    private int pointer = -1;
    private int previousPosition = AdapterView.INVALID_POSITION;
    private float x, y;

    DragSelectionController(ListView list, Runnable beginSelection, RangeSelection selection) {
        this.list = list;
        this.selection = selection;
        density = list.getResources().getDisplayMetrics().density;
        list.setOnTouchListener((view, event) -> touch(event));
        list.setOnItemLongClickListener((parent, row, position, id) -> {
            int activePointer = pointer;
            float touchX = x, touchY = y;
            beginSelection.run();
            pointer = activePointer;
            x = touchX; y = touchY;
            previousPosition = position;
            selection.select(position, position);
            if (pointer != -1) {
                dragging = true;
                cancelNativeTouch();
                list.getParent().requestDisallowInterceptTouchEvent(true);
                scheduleFrame();
            }
            return true;
        });
        list.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) { cancel(); }
        });
    }

    private boolean touch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancel();
            pointer = event.getPointerId(0);
            x = event.getX(); y = event.getY();
            return false;
        }
        boolean consume = dragging;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == pointer)) {
            if (dragging && action == MotionEvent.ACTION_UP) {
                x = event.getX(); y = event.getY();
                selectAtFinger();
            }
            cancel();
            return consume;
        }
        int index = event.findPointerIndex(pointer);
        if (index >= 0) { x = event.getX(index); y = event.getY(index); }
        if (dragging) { selectAtFinger(); scheduleFrame(); }
        return consume;
    }

    private void selectAtFinger() {
        if (list.isLayoutRequested() || list.getChildCount() == 0) return;
        int touchX = (int) Math.max(list.getPaddingLeft(), Math.min(x, list.getWidth() - list.getPaddingRight() - 1));
        int touchY = (int) Math.max(list.getPaddingTop(), Math.min(y, list.getHeight() - list.getPaddingBottom() - 1));
        int position = list.pointToPosition(touchX, touchY);
        if (position == AdapterView.INVALID_POSITION) return;
        selection.select(Math.min(previousPosition, position), Math.max(previousPosition, position));
        previousPosition = position;
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            frameScheduled = false;
            if (!dragging || !list.isShown()) { cancel(); return; }
            if (list.isLayoutRequested()) { scheduleFrame(); return; }
            selectAtFinger();
            float edge = Math.min(56 * density, list.getHeight() / 3f);
            if (edge <= 0) return;
            float strength = y < edge ? -(edge - y) / edge
                    : y > list.getHeight() - edge ? (y - list.getHeight() + edge) / edge : 0;
            int delta = Math.round(Math.max(-1, Math.min(1, strength)) * 12 * density);
            if (delta != 0 && list.canScrollVertically(delta > 0 ? 1 : -1)) {
                list.scrollListBy(delta);
                scheduleFrame();
            }
        }
    };

    private void scheduleFrame() {
        if (!frameScheduled) { frameScheduled = true; list.postOnAnimation(frame); }
    }

    void cancel() {
        if (dragging) cancelNativeTouch();
        dragging = false;
        pointer = -1;
        previousPosition = AdapterView.INVALID_POSITION;
        list.removeCallbacks(frame);
        frameScheduled = false;
        if (list.getParent() != null) list.getParent().requestDisallowInterceptTouchEvent(false);
    }

    private void cancelNativeTouch() {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, x, y, 0);
        list.onTouchEvent(cancel);
        cancel.recycle();
        list.setPressed(false);
    }
}
