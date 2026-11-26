package com.microui.zigeditor;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 编辑页底部的字符快捷键区。
 * <p>
 * 默认只显示一行（每行 8 个键位）；按住键区往上拖时高度连续跟随手指，
 * 逐行露出更多键位，松手后吸附到整行；往下拖收起。
 * 布局位于 adjustResize 窗口中，输入法弹出时随窗口整体上移。
 */
public class CharKeyBar extends LinearLayout {

    public interface OnKeyInsert {
        void onKey(String text);
    }

    private static final int COLS = 8;

    private final int rowHeight;
    private final int touchSlop;
    private final LinearLayout rowsBox;

    private List<String> keys = new ArrayList<>();
    private OnKeyInsert onKeyInsert;
    private int rows = 1;
    private float downX, downY;
    private int startHeight;
    private ValueAnimator snapAnimator;

    public CharKeyBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        rowHeight = dp(40);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setOrientation(VERTICAL);
        setGravity(Gravity.TOP);
        setClipChildren(true);
        setBackgroundColor(ThemeSettings.color(context, R.attr.palette_surface_container));

        // 所有键位一次性排好，拖动时只改整条高度，避免反复重建视图造成抖动
        rowsBox = new LinearLayout(context);
        rowsBox.setOrientation(VERTICAL);
        addView(rowsBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
    }

    public void setOnKeyInsert(OnKeyInsert listener) {
        onKeyInsert = listener;
    }

    public void setKeys(List<String> list) {
        keys = list == null ? new ArrayList<>() : list;
        rows = Math.max(1, Math.min(rows, maxRows()));
        rebuildAll();
        setBarHeight(rows * rowHeight);
    }

    private int maxRows() {
        return Math.max(1, (keys.size() + COLS - 1) / COLS);
    }

    /** 全部键位只构建一次，超出可视高度的部分由父布局裁剪。 */
    private void rebuildAll() {
        rowsBox.removeAllViews();
        ViewGroup.LayoutParams blp = rowsBox.getLayoutParams();
        blp.height = maxRows() * rowHeight;
        rowsBox.setLayoutParams(blp);

        int total = keys.size();
        for (int r = 0; r < maxRows(); r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            for (int c = 0; c < COLS; c++) {
                int index = r * COLS + c;
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(
                        0, rowHeight - dp(8), 1f);
                cell.setMargins(dp(2), dp(2), dp(2), dp(2));
                if (index < total) {
                    row.addView(makeKey(keys.get(index)), cell);
                } else {
                    row.addView(new View(getContext()), cell);
                }
            }
            rowsBox.addView(row, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
        }
    }

    private TextView makeKey(String text) {
        Context ctx = getContext();
        TextView tv = new TextView(ctx);
        tv.setText(label(text));
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTextColor(ThemeSettings.color(ctx, R.attr.palette_on_surface));
        tv.setOnClickListener(v -> {
            if (onKeyInsert != null) onKeyInsert.onKey(text);
        });
        return tv;
    }

    /** 特殊字符的显示文本。 */
    private static String label(String text) {
        if ("\t".equals(text)) return "Tab";
        if ("\n".equals(text)) return "⏎";
        if (" ".equals(text)) return "空格";
        if ("\\".equals(text)) return "\\";
        return text;
    }

    private void setBarHeight(int px) {
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null && lp.height != px) {
            lp.height = px;
            setLayoutParams(lp);
        }
    }

    private int currentHeight() {
        ViewGroup.LayoutParams lp = getLayoutParams();
        return lp == null || lp.height < 0 ? rowHeight : lp.height;
    }

    private int clampHeight(float px) {
        int max = maxRows() * rowHeight;
        return Math.round(Math.max(rowHeight, Math.min(max, px)));
    }

    private void cancelSnap() {
        if (snapAnimator != null) {
            snapAnimator.cancel();
            snapAnimator = null;
        }
    }

    private void snapTo(int target) {
        int from = currentHeight();
        if (from == target) return;
        cancelSnap();
        snapAnimator = ValueAnimator.ofInt(from, target);
        snapAnimator.setDuration(160);
        snapAnimator.addUpdateListener(a -> setBarHeight((int) a.getAnimatedValue()));
        snapAnimator.start();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelSnap();
                // 用屏幕坐标：键区高度变化会移动自身顶部，局部坐标会形成来回抖动
                downX = ev.getRawX();
                downY = ev.getRawY();
                startHeight = currentHeight();
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(ev.getRawX() - downX);
                float dy = Math.abs(ev.getRawY() - downY);
                // 明确的竖向拖动才拦截，横向/点击仍然交给按键
                if (dy > touchSlop && dy > dx) {
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                // 高度连续跟随手指，不再按整行跳动
                setBarHeight(clampHeight(startHeight + (downY - ev.getRawY())));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                int targetRows = Math.round(currentHeight() / (float) rowHeight);
                rows = Math.max(1, Math.min(maxRows(), targetRows));
                snapTo(rows * rowHeight);
                return true;
            default:
                return true;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
