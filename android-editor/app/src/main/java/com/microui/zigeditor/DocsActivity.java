package com.microui.zigeditor;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 内置 API 文档：列出 microui 常用函数的效果说明与示例代码，代码可一键复制。
 * 入口在编辑页侧滑栏，不依赖网络和外部文件。
 */
public class DocsActivity extends Activity {

    private static final int BG = 0xFF1E1E1E;
    private static final int CARD = 0xFF252526;
    private static final int CODE_BG = 0xFF111111;
    private static final int ACCENT = 0xFF4FC1FF;
    private static final int TEXT = 0xFFD4D4D4;

    private static final class Doc {
        final String title, desc, effect, code;
        Doc(String title, String desc, String effect, String code) {
            this.title = title; this.desc = desc; this.effect = effect; this.code = code;
        }
    }

    private static final Doc[] DOCS = {
        new Doc("beginWindowEx / endWindow",
            "创建一个窗口；返回非 0 时才需要绘制内容，最后必须 endWindow。",
            "屏幕上出现带标题栏的矩形窗口，可拖动、可关闭（除非禁用）。",
            "if (ctx.beginWindowEx(\"我的窗口\", microui.Rect.init(10, 20, 320, 240),\n"
            + "        microui.opt_no_resize | microui.opt_no_close) != 0) {\n"
            + "    ctx.label(\"Hello\");\n"
            + "    ctx.endWindow();\n"
            + "}"),
        new Doc("layoutRow",
            "设置当前行的列数、每列宽度与行高；-1 表示占满剩余宽度。",
            "后续控件按设定的列宽横向排列，排满后自动换到下一行。",
            "ctx.layoutRow(2, &.{ 100, -1 }, 0);\n"
            + "ctx.label(\"名称:\");\n"
            + "ctx.label(\"值\");"),
        new Doc("button",
            "普通按钮；返回非 0 表示本次被点击。",
            "按钮按下时高亮，松开后触发一次点击。",
            "if (ctx.button(\"确定\") != 0) {\n"
            + "    // 点击处理\n"
            + "}"),
        new Doc("label",
            "单行文本，不换行。",
            "直接显示一行文字，宽度按内容自动计算。",
            "ctx.layoutRow(1, &.{-1}, 0);\n"
            + "ctx.label(\"Hello, World!\");"),
        new Doc("text",
            "多行文本，支持 \\n 换行，超出宽度时自动折行。",
            "显示一段带换行的文字。",
            "ctx.layoutRow(1, &.{-1}, 0);\n"
            + "ctx.text(\"第一行\\n第二行\\n第三行\");"),
        new Doc("header / headerEx",
            "可折叠区域的标题；headerEx 可指定初始展开状态。",
            "点击标题展开/收起内容，类似折叠面板。",
            "if (ctx.headerEx(\"高级选项\", microui.opt_expanded) != 0) {\n"
            + "    ctx.label(\"展开后可见的内容\");\n"
            + "}"),
        new Doc("beginTreeNode / endTreeNode",
            "树节点；返回非 0 表示已展开，需要在内部绘制子项。",
            "按层级缩进显示，点击箭头展开/收起。",
            "if (ctx.beginTreeNode(\"设置\") != 0) {\n"
            + "    ctx.label(\"子项\");\n"
            + "    ctx.endTreeNode();\n"
            + "}"),
        new Doc("checkbox",
            "复选框，状态绑定到一个 bool 变量。",
            "可勾选的方框，勾选状态跨帧保留。",
            "var enabled = true;\n"
            + "_ = ctx.checkbox(\"启用\", &enabled);"),
        new Doc("textbox",
            "单行输入框；返回值与 res_submit 按位与非 0 表示按下回车。",
            "可编辑的输入框，带光标与文字输入。",
            "var input = @as([64]u8, @splat(0));\n"
            + "if ((ctx.textbox(&input) & microui.res_submit) != 0) {\n"
            + "    // 回车提交\n"
            + "}"),
        new Doc("sliderEx",
            "滑块；可设置范围、显示的格式字符串与对齐方式。",
            "左右拖动调整数值，滑块上显示当前值。",
            "var volume: f32 = 50;\n"
            + "ctx.sliderEx(&volume, 0, 100, 0, \"{d:.0}\", microui.opt_align_center);"),
        new Doc("openPopup / beginPopup / endPopup",
            "弹窗。openPopup 与 beginPopup 必须在同一 id 作用域内。",
            "点击按钮后在按钮附近弹出小窗口，点窗口外部自动关闭。",
            "if (ctx.button(\"打开\") != 0) ctx.openPopup(\"弹窗\");\n"
            + "if (ctx.beginPopup(\"弹窗\") != 0) {\n"
            + "    ctx.label(\"弹窗内容\");\n"
            + "    ctx.endPopup();\n"
            + "}"),
        new Doc("beginPanel / endPanel",
            "带背景和滚动条的内嵌面板。",
            "面板内的内容超出高度时出现滚动条，可独立滚动。",
            "ctx.layoutRow(1, &.{ 300 }, 120);\n"
            + "ctx.beginPanel(\"日志\");\n"
            + "ctx.layoutRow(1, &.{-1}, -1);\n"
            + "ctx.text(\"面板里的内容\");\n"
            + "ctx.endPanel();"),
        new Doc("drawRect / drawControlText",
            "直接绘制矩形和文字，不参与布局。",
            "可画出颜色块、色卡、进度条等自定义图形。",
            "const r = ctx.layoutNext();\n"
            + "ctx.drawRect(r, microui.Color.init(80, 160, 255, 255));\n"
            + "ctx.drawControlText(\"#50A0FF\", r, .text, microui.opt_align_center);"),
        new Doc("pushId / popId",
            "手动压入 id 作用域；同一位置循环创建的控件需要它来区分。",
            "多个结构相同的控件（如 4 个滑块）互不串扰、焦点独立。",
            "var key: usize = 42;\n"
            + "ctx.pushId(std.mem.asBytes(&key));\n"
            + "// 这里创建的控件 id 会带上 key\n"
            + "ctx.popId();"),
        new Doc("layoutBeginColumn / layoutEndColumn",
            "开始/结束一个列容器，可在同一行里排布多列内容。",
            "左右两列并排显示，各自独立布局。",
            "ctx.layoutBeginColumn();\n"
            + "ctx.layoutRow(1, &.{-1}, 0);\n"
            + "ctx.label(\"左列\");\n"
            + "ctx.layoutEndColumn();\n"
            + "ctx.layoutBeginColumn();\n"
            + "ctx.layoutRow(1, &.{-1}, 0);\n"
            + "ctx.label(\"右列\");\n"
            + "ctx.layoutEndColumn();"),
        new Doc("style.colors / applyTheme",
            "全局样式表；改颜色、间距、控件尺寸后整屏生效。",
            "整体配色与控件密度改变（例如暗色主题）。",
            "ctx.style.colors[@intFromEnum(microui.ColorId.button)] =\n"
            + "    microui.Color.init(52, 58, 70, 255);"),
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView head = new TextView(this);
        head.setText("microui API 文档");
        head.setTextColor(0xFFFFFFFF);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        head.setPadding(0, 0, 0, dp(4));
        root.addView(head);

        TextView sub = new TextView(this);
        sub.setText("内置文档：查看常用函数的效果说明，点「复制」把示例代码粘贴到项目里。");
        sub.setTextColor(0xFF9A9A9A);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setPadding(0, 0, 0, dp(16));
        root.addView(sub);

        for (Doc d : DOCS) root.addView(card(d));

        scroll.addView(root);
        setContentView(scroll);
    }

    private LinearLayout card(Doc d) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(CARD);
        int p = dp(12);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setText(d.title);
        title.setTextColor(ACCENT);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        c.addView(title);

        c.addView(line(d.desc, TEXT, 13, dp(6)));
        c.addView(line("效果：" + d.effect, 0xFF9CDCFE, 13, dp(10)));

        TextView code = new TextView(this);
        code.setText(highlight(d.code));
        code.setTextColor(0xFFD4D4D4);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        code.setBackgroundColor(CODE_BG);
        int cp = dp(10);
        code.setPadding(cp, cp, cp, cp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        code.setLayoutParams(clp);
        c.addView(code);

        Button copy = new Button(this);
        copy.setText("复制代码");
        copy.setAllCaps(false);
        copy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("microui code", d.code));
                Toast.makeText(this, "已复制：" + d.title, Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(4);
        copy.setLayoutParams(blp);
        c.addView(copy);
        return c;
    }

    private TextView line(String text, int color, int sp, int bottomMargin) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = bottomMargin;
        t.setLayoutParams(lp);
        return t;
    }

    // ------------------------------------------------------------ 代码高亮

    /** 简易 Zig 高亮：注释 / 字符串 / 关键字 / 数字 / 函数名。 */
    private static final java.util.regex.Pattern RE_COMMENT =
            java.util.regex.Pattern.compile("//[^\\n]*");
    private static final java.util.regex.Pattern RE_STRING =
            java.util.regex.Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final java.util.regex.Pattern RE_KEYWORD = java.util.regex.Pattern.compile(
            "\\b(if|else|while|for|switch|return|var|const|fn|pub|try|catch|struct|enum|union"
                    + "|defer|orelse|and|or|break|continue|unreachable|inline|comptime"
                    + "|null|true|false)\\b");
    private static final java.util.regex.Pattern RE_NUMBER =
            java.util.regex.Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final java.util.regex.Pattern RE_FUNC =
            java.util.regex.Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\s*(?=\\()");

    private static final int C_COMMENT = 0xFF6A9955;
    private static final int C_STRING = 0xFFCE9178;
    private static final int C_KEYWORD = 0xFF569CD6;
    private static final int C_NUMBER = 0xFFB5CEA8;
    private static final int C_FUNC = 0xFFDCDCAA;

    private static CharSequence highlight(String src) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(src);
        boolean[] locked = new boolean[src.length()];   // 已着色的区间，后面的规则不再覆盖
        paint(sb, locked, RE_COMMENT, C_COMMENT);
        paint(sb, locked, RE_STRING, C_STRING);
        paint(sb, locked, RE_KEYWORD, C_KEYWORD);
        paint(sb, locked, RE_NUMBER, C_NUMBER);
        paint(sb, locked, RE_FUNC, C_FUNC);
        return sb;
    }

    private static void paint(android.text.SpannableStringBuilder sb, boolean[] locked,
                              java.util.regex.Pattern p, int color) {
        java.util.regex.Matcher m = p.matcher(sb.toString());
        while (m.find()) {
            int s = m.start(), e = m.end();
            boolean free = true;
            for (int i = s; i < e; i++) {
                if (locked[i]) { free = false; break; }
            }
            if (!free) continue;
            for (int i = s; i < e; i++) locked[i] = true;
            sb.setSpan(new android.text.style.ForegroundColorSpan(color), s, e,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
