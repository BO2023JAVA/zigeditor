package com.microui.zigeditor;

import android.content.Context;
import android.content.res.Configuration;

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
 */
final class TextMateSupport {

    private static final String LIGHT_THEME_NAME = "blueberry-light";
    private static final String LIGHT_THEME_ASSET = "textmate/blueberry-light.json";
    private static final String DARK_THEME_NAME = "blueberry-dark";
    private static final String DARK_THEME_ASSET = "textmate/blueberry-dark.json";
    private static boolean ready = false;

    private TextMateSupport() {
    }

    static synchronized void ensure(Context context) throws Exception {
        Context app = context.getApplicationContext();
        if (!ready) {
            FileProviderRegistry.getInstance().addFileProvider(new AssetsFileResolver(app.getAssets()));
            loadTheme(LIGHT_THEME_ASSET, LIGHT_THEME_NAME);
            loadTheme(DARK_THEME_ASSET, DARK_THEME_NAME);
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json");
            ready = true;
        }

        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        ThemeRegistry.getInstance().setTheme(night ? DARK_THEME_NAME : LIGHT_THEME_NAME);
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
