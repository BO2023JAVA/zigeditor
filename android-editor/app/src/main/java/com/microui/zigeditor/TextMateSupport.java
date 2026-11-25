package com.microui.zigeditor;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;

import org.eclipse.tm4e.core.registry.IThemeSource;

import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel;
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver;

/**
 * TextMate 资源只需在进程内初始化一次。
 * 默认（跟随界面）用蓝莓浅/深色；也可选几种流行编辑器配色。
 */
final class TextMateSupport {

    private static final String LIGHT_THEME_NAME = "blueberry-light";
    private static final String LIGHT_THEME_ASSET = "textmate/blueberry-light.json";
    private static final String DARK_THEME_NAME = "blueberry-dark";
    private static final String DARK_THEME_ASSET = "textmate/blueberry-dark.json";

    /** 额外配色：下标与 EditorSettings.KEY_SYNTAX_SCHEME 对应（0 表示跟随界面）。 */
    private static final String[] EXTRA_NAMES = {"one-dark", "dracula", "nord", "solarized-light"};
    private static final String[] EXTRA_ASSETS = {
            "textmate/one-dark.json",
            "textmate/dracula.json",
            "textmate/nord.json",
            "textmate/solarized-light.json"
    };

    private static boolean ready = false;

    private TextMateSupport() {
    }

    static synchronized void ensure(Context context) throws Exception {
        Context app = context.getApplicationContext();
        if (!ready) {
            FileProviderRegistry.getInstance().addFileProvider(new AssetsFileResolver(app.getAssets()));
            loadTheme(LIGHT_THEME_ASSET, LIGHT_THEME_NAME);
            loadTheme(DARK_THEME_ASSET, DARK_THEME_NAME);
            for (int i = 0; i < EXTRA_NAMES.length; i++) {
                loadTheme(EXTRA_ASSETS[i], EXTRA_NAMES[i]);
            }
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json");
            ready = true;
        }

        int scheme = EditorSettings.prefs(context)
                .getInt(EditorSettings.KEY_SYNTAX_SCHEME, EditorSettings.DEF_SYNTAX_SCHEME);
        String theme;
        if (scheme >= 1 && scheme <= EXTRA_NAMES.length) {
            theme = EXTRA_NAMES[scheme - 1];
        } else {
            theme = ThemeSettings.isDark(context) ? DARK_THEME_NAME : LIGHT_THEME_NAME;
        }
        ThemeRegistry.getInstance().setTheme(theme);
    }

    private static void loadTheme(String asset, String name) throws Exception {
        InputStream themeIn = FileProviderRegistry.getInstance().tryGetInputStream(asset);
        if (themeIn == null) {
            throw new IOException("缺少资源：" + asset);
        }
        ThemeRegistry.getInstance().loadTheme(
                new ThemeModel(IThemeSource.fromInputStream(themeIn, asset, null), name));
    }
}
