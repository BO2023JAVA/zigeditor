package com.microui.zigeditor;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * 编辑页根布局：识别侧滑栏的按住拖动手势。
 * 关闭时从屏幕左边缘向右拖出；展开后在栏内向左拖回，松手后自动吸附。
 * 只有明确的横向拖动才拦截事件，不影响文件树滚动和编辑器触摸。
 */
public class DrawerRootLayout extends FrameLayout {

    public interface Listener {
        boolean isDrawerOpen();

        /** 拖动过程中实时更新侧滑栏位置。 */
        void onDrawerDrag(float translationX);

        /** 松手后按目标位置执行吸附动画。 */
        void onDrawerDragEnd(boolean open);
    }

    private Listener listener;
    private final int touchSlop;
    private float downX, downY;
    private float dragTranslation;
    private int drawerWidth;
    private boolean waitingForDrag, dragging, dragFromOpen;
    private VelocityTracker velocityTracker;

    public DrawerRootLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (listener == null) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                recycleVelocityTracker();
                downX = ev.getX();
                downY = ev.getY();
                dragFromOpen = listener.isDrawerOpen();
                drawerWidth = findDrawerWidth();
                dragTranslation = dragFromOpen ? 0f : -drawerWidth;
                // 展开时只拖侧滑栏本身；关闭时只从编辑区左边缘拖出。
                waitingForDrag = dragFromOpen ? downX <= drawerWidth : downX <= dp(24);
                dragging = false;
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(ev);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!waitingForDrag) break;
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                    waitingForDrag = false;
                    break;
                }
                boolean horizontal = Math.abs(dx) > touchSlop
                        && Math.abs(dx) > Math.abs(dy) * 1.2f;
                boolean rightDirection = dragFromOpen ? dx < 0 : dx > 0;
                if (horizontal && rightDirection) {
                    dragging = true;
                    waitingForDrag = false;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) resetTouch();
                break;
            default:
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) return super.onTouchEvent(ev);
        if (velocityTracker != null) velocityTracker.addMovement(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                updateDrag(ev.getX());
                return true;
            case MotionEvent.ACTION_UP:
                velocityTracker.computeCurrentVelocity(1000);
                float velocityX = velocityTracker.getXVelocity();
                boolean open = dragTranslation > -drawerWidth / 2f;
                if (velocityX > dp(500)) open = true;
                else if (velocityX < -dp(500)) open = false;
                listener.onDrawerDragEnd(open);
                resetTouch();
                return true;
            case MotionEvent.ACTION_CANCEL:
                listener.onDrawerDragEnd(dragFromOpen);
                resetTouch();
                return true;
            default:
                return true;
        }
    }

    private void updateDrag(float x) {
        float base = dragFromOpen ? 0f : -drawerWidth;
        dragTranslation = Math.max(-drawerWidth, Math.min(0f, base + x - downX));
        listener.onDrawerDrag(dragTranslation);
    }

    private int findDrawerWidth() {
        View drawer = findViewById(R.id.drawer);
        int width = drawer == null ? 0 : drawer.getWidth();
        return width > 0 ? width : dp(280);
    }

    private void resetTouch() {
        waitingForDrag = false;
        dragging = false;
        getParent().requestDisallowInterceptTouchEvent(false);
        recycleVelocityTracker();
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
