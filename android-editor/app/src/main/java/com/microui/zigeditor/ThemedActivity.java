package com.microui.zigeditor;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

/**
 * 所有页面基类：创建时应用外观偏好；主题或配色在别处被修改后，回到该页时自动重建。
 */
public abstract class ThemedActivity extends Activity {

    private boolean darkAtCreate;
    private int schemeAtCreate;

    /** 先把夜间配置固定成用户偏好，再让主题按该配置解析，避免浅色界面出现深色控件/白字。 */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ThemeSettings.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeSettings.apply(this);
        darkAtCreate = ThemeSettings.isDark(this);
        schemeAtCreate = ThemeSettings.getScheme(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ThemeSettings.isDark(this) != darkAtCreate
                || ThemeSettings.getScheme(this) != schemeAtCreate) {
            recreate();
        }
    }
}
