package com.microui.zigeditor;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * 编辑器外观与行为设置的统一入口。
 * <p>
 * 设置全局保存在 SharedPreferences（editor_settings），与具体项目无关，
 * 打开任何文件时都会应用同一套偏好。所有可配置项都直接映射到
 * {@link CodeEditor} 公开 API 或其 {@link CodeEditor#getProps()} 字段，
 * 不引入额外依赖。
 */
public final class EditorSettings {

    private static final String PREFS = "editor_settings";
    private static final String KEY_INIT = "initialized";

    // ---- 显示 ----
    public static final String KEY_WORDWRAP = "wordwrap";
    public static final String KEY_TEXT_SIZE = "text_size";          // 单位 sp
    public static final String KEY_LINE_SPACING = "line_spacing";     // 单位 dp（额外行间距）
    public static final String KEY_LINE_NUMBER = "line_number";
    public static final String KEY_HIGHLIGHT_LINE = "highlight_line";
    public static final String KEY_HIGHLIGHT_BRACKET = "highlight_bracket";
    public static final String KEY_WHITESPACE = "whitespace";
    public static final String KEY_LIGATURE = "ligature";

    // ---- 编辑 ----
    public static final String KEY_AUTO_INDENT = "auto_indent";
    public static final String KEY_SYMBOL_PAIR = "symbol_pair";
    public static final String KEY_TAB_WIDTH = "tab_width";
    public static final String KEY_SCALABLE = "scalable";

    // ---- 滚动 ----
    public static final String KEY_STICKY_SCROLL = "sticky_scroll";
    public static final String KEY_OVER_SCROLL = "over_scroll";

    // ---- 代码高亮 ----
    public static final String KEY_SYNTAX_SCHEME = "syntax_scheme";   // 0 跟随界面 1 One Dark ...
    public static final int DEF_SYNTAX_SCHEME = 0;

    // ---- 字符快捷键 ----
    public static final String KEY_CHAR_KEYS = "char_keys";
    /** 默认按键：英文逗号分隔，反斜杠转义（\\t 制表符、\\, 逗号、\\\\ 反斜杠）。 */
    public static final String DEF_CHAR_KEYS =
            "\\t,{,},(,),[,],;,<,>,=,+,-,*,/,&,|,!,?,:,\",',@,#,$,%,^,~,_,\\,,.,\\\\";

    private static final String KEY_INDENT_MIGRATED = "indent_on_migrated";

    // ---- 默认值（与 sora-editor 默认行为保持一致，避免覆盖现有体验）----
    public static final boolean DEF_WORDWRAP = false;
    public static final int DEF_TEXT_SIZE = 18;
    public static final int DEF_LINE_SPACING = 0;
    public static final boolean DEF_LINE_NUMBER = true;
    public static final boolean DEF_HIGHLIGHT_LINE = true;
    public static final boolean DEF_HIGHLIGHT_BRACKET = true;
    public static final boolean DEF_WHITESPACE = false;
    public static final boolean DEF_LIGATURE = false;
    public static final boolean DEF_AUTO_INDENT = true;
    public static final boolean DEF_SYMBOL_PAIR = true;
    public static final int DEF_TAB_WIDTH = 4;
    public static final boolean DEF_SCALABLE = true;
    public static final boolean DEF_STICKY_SCROLL = false;
    public static final boolean DEF_OVER_SCROLL = true;

    public static final int MIN_TEXT_SIZE = 10;
    public static final int MAX_TEXT_SIZE = 32;
    public static final int MIN_LINE_SPACING = 0;
    public static final int MAX_LINE_SPACING = 16;
    public static final int MIN_TAB_WIDTH = 2;
    public static final int MAX_TAB_WIDTH = 8;

    /** 打开「显示空白字符」时启用的绘制标志组合。 */
    private static final int WHITESPACE_FLAGS_ON =
            CodeEditor.FLAG_DRAW_WHITESPACE_LEADING
                    | CodeEditor.FLAG_DRAW_WHITESPACE_INNER
                    | CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING
                    | CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE
                    | CodeEditor.FLAG_DRAW_LINE_SEPARATOR
                    | CodeEditor.FLAG_DRAW_SOFT_WRAP;

    private EditorSettings() { }

    /** 获取偏好存储；首次访问时写入默认值。 */
    public static SharedPreferences prefs(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(KEY_INIT, false)) {
            writeDefaults(p.edit()).apply();
        } else if (!p.getBoolean(KEY_INDENT_MIGRATED, false)) {
            // 旧版本默认关闭自动缩进；统一改成默认打开一次
            p.edit().putBoolean(KEY_AUTO_INDENT, true)
                    .putBoolean(KEY_INDENT_MIGRATED, true).apply();
        }
        return p;
    }

    public static String charKeysRaw(Context ctx) {
        return prefs(ctx).getString(KEY_CHAR_KEYS, DEF_CHAR_KEYS);
    }

    public static void setCharKeysRaw(Context ctx, String raw) {
        prefs(ctx).edit().putString(KEY_CHAR_KEYS, raw).apply();
    }

    /**
     * 解析字符快捷键：英文逗号分隔；反斜杠转义，
     * \t 制表符、\n 换行、\, 逗号、\\ 反斜杠，其它 \x 按 x 处理。
     */
    public static java.util.List<String> parseCharKeys(String raw) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        StringBuilder cur = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                if (c == 't') cur.append('\t');
                else if (c == 'n') cur.append('\n');
                else cur.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == ',') {
                if (cur.length() > 0) out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static SharedPreferences.Editor writeDefaults(SharedPreferences.Editor e) {
        e.putBoolean(KEY_INIT, true);
        e.putBoolean(KEY_WORDWRAP, DEF_WORDWRAP);
        e.putInt(KEY_TEXT_SIZE, DEF_TEXT_SIZE);
        e.putInt(KEY_LINE_SPACING, DEF_LINE_SPACING);
        e.putBoolean(KEY_LINE_NUMBER, DEF_LINE_NUMBER);
        e.putBoolean(KEY_HIGHLIGHT_LINE, DEF_HIGHLIGHT_LINE);
        e.putBoolean(KEY_HIGHLIGHT_BRACKET, DEF_HIGHLIGHT_BRACKET);
        e.putBoolean(KEY_WHITESPACE, DEF_WHITESPACE);
        e.putBoolean(KEY_LIGATURE, DEF_LIGATURE);
        e.putBoolean(KEY_AUTO_INDENT, DEF_AUTO_INDENT);
        e.putBoolean(KEY_SYMBOL_PAIR, DEF_SYMBOL_PAIR);
        e.putInt(KEY_TAB_WIDTH, DEF_TAB_WIDTH);
        e.putBoolean(KEY_SCALABLE, DEF_SCALABLE);
        e.putBoolean(KEY_STICKY_SCROLL, DEF_STICKY_SCROLL);
        e.putBoolean(KEY_OVER_SCROLL, DEF_OVER_SCROLL);
        e.putInt(KEY_SYNTAX_SCHEME, DEF_SYNTAX_SCHEME);
        e.putString(KEY_CHAR_KEYS, DEF_CHAR_KEYS);
        e.putBoolean(KEY_INDENT_MIGRATED, true);
        return e;
    }

    /** 恢复全部默认值。 */
    public static void reset(Context ctx) {
        writeDefaults(prefs(ctx).edit()).apply();
    }

    /** 把当前偏好应用到编辑器。 */
    public static void applyAll(CodeEditor editor, Context ctx) {
        if (editor == null) return;
        SharedPreferences p = prefs(ctx);
        editor.setWordwrap(p.getBoolean(KEY_WORDWRAP, DEF_WORDWRAP));
        editor.setTextSize(p.getInt(KEY_TEXT_SIZE, DEF_TEXT_SIZE));
        editor.setLineSpacingExtra(dp(ctx, p.getInt(KEY_LINE_SPACING, DEF_LINE_SPACING)));
        editor.setLineNumberEnabled(p.getBoolean(KEY_LINE_NUMBER, DEF_LINE_NUMBER));
        editor.setHighlightCurrentLine(p.getBoolean(KEY_HIGHLIGHT_LINE, DEF_HIGHLIGHT_LINE));
        editor.setHighlightBracketPair(p.getBoolean(KEY_HIGHLIGHT_BRACKET, DEF_HIGHLIGHT_BRACKET));
        editor.setLigatureEnabled(p.getBoolean(KEY_LIGATURE, DEF_LIGATURE));
        editor.setNonPrintablePaintingFlags(
                p.getBoolean(KEY_WHITESPACE, DEF_WHITESPACE) ? WHITESPACE_FLAGS_ON : 0);
        editor.setTabWidth(p.getInt(KEY_TAB_WIDTH, DEF_TAB_WIDTH));
        editor.setScalable(p.getBoolean(KEY_SCALABLE, DEF_SCALABLE));

        io.github.rosemoe.sora.widget.DirectAccessProps props = editor.getProps();
        if (props != null) {
            props.autoIndent = p.getBoolean(KEY_AUTO_INDENT, DEF_AUTO_INDENT);
            props.symbolPairAutoCompletion = p.getBoolean(KEY_SYMBOL_PAIR, DEF_SYMBOL_PAIR);
            props.stickyScroll = p.getBoolean(KEY_STICKY_SCROLL, DEF_STICKY_SCROLL);
            props.overScrollEnabled = p.getBoolean(KEY_OVER_SCROLL, DEF_OVER_SCROLL);
        }
    }

    private static float dp(Context ctx, int value) {
        return value * ctx.getResources().getDisplayMetrics().density;
    }
}
