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
 */
final class TextMateSupport {

    private static final String THEME_NAME = "quietlight";
    private static final String THEME_ASSET = "textmate/quietlight.json";
    private static boolean ready = false;

    private TextMateSupport() {
    }

    static synchronized void ensure(Context context) throws Exception {
        if (ready) {
            return;
        }
        Context app = context.getApplicationContext();
        FileProviderRegistry.getInstance().addFileProvider(new AssetsFileResolver(app.getAssets()));

        InputStream themeIn = FileProviderRegistry.getInstance().tryGetInputStream(THEME_ASSET);
        if (themeIn == null) {
            throw new IOException("缺少资源：" + THEME_ASSET);
        }
        ThemeRegistry.getInstance().loadTheme(
                new ThemeModel(IThemeSource.fromInputStream(themeIn, THEME_ASSET, null), THEME_NAME));
        ThemeRegistry.getInstance().setTheme(THEME_NAME);

        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json");
        ready = true;
    }
}
