package com.microui.zigeditor;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/**
 * 编辑页根布局：只在编辑区左边缘识别右滑手势，用于展开侧滑栏。
 * 侧滑栏展开后由遮罩负责关闭，不影响编辑器自身的触摸。
 */
public class DrawerRootLayout extends FrameLayout {

    public interface Listener {
        boolean isDrawerOpen();

        void requestOpenDrawer();
    }

    private Listener listener;
    private float downX, downY;
    private boolean tracking;

    public DrawerRootLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (listener == null) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                tracking = !listener.isDrawerOpen() && downX < dp(24);
                break;
            case MotionEvent.ACTION_MOVE:
                if (tracking) {
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (dx > dp(32) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        tracking = false;
                        listener.requestOpenDrawer();
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                break;
            default:
                break;
        }
        return false;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
