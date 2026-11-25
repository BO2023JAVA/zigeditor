package com.microui.zigeditor;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Window;

/**
 * 应用外观设置：深色 / 浅色 / 跟随系统 + 配色方案。
 * 默认浅色、蓝莓配色；用户可在编辑页右上角菜单里主动切换。
 */
public final class ThemeSettings {

    private static final String PREFS = "theme_settings";
    private static final String KEY_MODE = "mode";
    private static final String KEY_SCHEME = "scheme";

    public static final int MODE_DARK = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_SYSTEM = 2;
    public static final int DEF_MODE = MODE_LIGHT;

    public static final int SCHEME_BLUEBERRY = 0;
    public static final int SCHEME_FOREST = 1;
    public static final int SCHEME_VIOLET = 2;
    public static final int SCHEME_SUNSET = 3;
    public static final int DEF_SCHEME = SCHEME_BLUEBERRY;

    private ThemeSettings() { }

    public static int getMode(Context ctx) {
        return prefs(ctx).getInt(KEY_MODE, DEF_MODE);
    }

    public static void setMode(Context ctx, int mode) {
        prefs(ctx).edit().putInt(KEY_MODE, mode).apply();
    }

    public static int getScheme(Context ctx) {
        return prefs(ctx).getInt(KEY_SCHEME, DEF_SCHEME);
    }

    public static void setScheme(Context ctx, int scheme) {
        prefs(ctx).edit().putInt(KEY_SCHEME, scheme).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 当前实际是否深色（跟随系统时按系统夜间模式判断）。 */
    public static boolean isDark(Context ctx) {
        int mode = getMode(ctx);
        if (mode == MODE_DARK) return true;
        if (mode == MODE_LIGHT) return false;
        return (Resources.getSystem().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 配色方案对应的主题样式。 */
    private static int themeRes(int scheme) {
        switch (scheme) {
            case SCHEME_FOREST: return R.style.AppTheme_Forest;
            case SCHEME_VIOLET: return R.style.AppTheme_Violet;
            case SCHEME_SUNSET: return R.style.AppTheme_Sunset;
            default: return R.style.AppTheme;
        }
    }

    /**
     * attachBaseContext 用：把资源配置里的夜间位强制成偏好值。
     * 只靠 Activity 里改 Resources 配置，主题内 framework 样式（文字/按钮/弹窗/菜单）仍会按系统夜间模式解析，
     * 导致浅色界面里出现白字、深色按钮；在这里用配置 Context 才能保证整套主题一致。
     */
    public static Context wrap(Context base) {
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        int nightBits = isDark(base) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightBits;
        return base.createConfigurationContext(cfg);
    }

    /**
     * 在 setContentView 之前调用：应用对应配色主题。
     */
    public static void apply(Activity activity) {
        activity.setTheme(themeRes(getScheme(activity)));
        Window window = activity.getWindow();
        int surface = color(activity, R.attr.palette_surface);
        window.setBackgroundDrawable(new ColorDrawable(surface));
        window.setStatusBarColor(surface);
        window.setNavigationBarColor(surface);
    }

    /** 解析当前主题里的 palette 颜色（布局与代码共用同一套配色）。 */
    public static int color(Context ctx, int attr) {
        TypedValue tv = new TypedValue();
        if (ctx.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) return ctx.getColor(tv.resourceId);
            return tv.data;
        }
        return 0xFF000000;
    }
}
