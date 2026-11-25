package com.microui.zigeditor;

import android.app.AlertDialog;
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
 * 内置 API 文档：按「常用 + 从易到难」排序，列出 microui 常用函数。
 * 每张卡片给一句通俗说明、示例代码；点「详情」弹窗看完整参数与注意事项。
 * 入口在编辑页侧滑栏，不依赖网络和外部文件。
 */
public class DocsActivity extends ThemedActivity {

    // 配色随应用主题切换，在 onCreate 里初始化
    private int BG, CARD, CODE_BG, ACCENT, TEXT, DESC, EFFECT, CODE_TEXT;
    private int C_COMMENT, C_STRING, C_KEYWORD, C_NUMBER, C_FUNC;

    private void initPalette() {
        boolean dark = ThemeSettings.isDark(this);
        BG = ThemeSettings.color(this, R.attr.palette_surface);
        CARD = ThemeSettings.color(this, R.attr.palette_surface_container);
        ACCENT = ThemeSettings.color(this, R.attr.palette_primary);
        TEXT = ThemeSettings.color(this, R.attr.palette_on_surface);
        DESC = ThemeSettings.color(this, R.attr.palette_on_surface_variant);
        CODE_BG = dark ? 0xFF111111 : 0xFFF3F3FA;
        CODE_TEXT = dark ? 0xFFD4D4D4 : 0xFF1F1F1F;
        EFFECT = dark ? 0xFF9CDCFE : 0xFF001080;
        C_COMMENT = dark ? 0xFF6A9955 : 0xFF008000;
        C_STRING = dark ? 0xFFCE9178 : 0xFFA31515;
        C_KEYWORD = dark ? 0xFF569CD6 : 0xFF0000FF;
        C_NUMBER = dark ? 0xFFB5CEA8 : 0xFF098658;
        C_FUNC = dark ? 0xFFDCDCAA : 0xFF795E26;
    }

    private static final class Doc {
        final String group, title, desc, effect, detail, code;
        Doc(String group, String title, String desc, String effect, String detail, String code) {
            this.group = group; this.title = title; this.desc = desc;
            this.effect = effect; this.detail = detail; this.code = code;
        }
    }

    /**
     * 顺序即难度：入门 → 常用控件 → 容器 → 窗口 → 布局进阶 → 自绘 → 底层。
     */
    private static final Doc[] DOCS = {
        // ---------------------------------------------------------- 入门
        new Doc("入门：先学会这 4 个",
            "label",
            "显示一行文字，最常用。",
            "在当前位置画出一行文字。",
            "label 只显示一行，不会自动换行；文字太长会超出布局宽度。\n\n"
            + "参数：label(文字)\n返回：无\n\n"
            + "用法要点：\n"
            + "1. 一般先 layoutRow 规定这一行的列宽，label 才会画在正确的宽度里。\n"
            + "2. 需要自动换行的长文本用 text，不要用 label。\n"
            + "3. 文字颜色默认取样式表里的 text 色，可在 applyTheme 里统一修改。",
            "ctx.layoutRow(1, &.{-1}, 0);\n"
            + "ctx.label(\"Hello, World!\");"),

        new Doc("入门：先学会这 4 个",
            "text",
            "多行文字，自动折行。",
            "长文本按宽度自动换行，\\n 强制换行。",
            "文字太长时按空格拆词自动折行，遇到 \\n 立即换行。\n\n"
            + "参数：text(文字)\n返回：无\n\n"
            + "用法要点：\n"
            + "1. 调用前用 layoutRow 给出整体宽度和行高即可，内部会自己排布。\n"
            + "2. 没有空格的长串会整段换到下一行。\n"
            + "3. 和 label 的区别：text 会换行，label 只画一行。",
            "ctx.layoutRow(1, &.{-1}, 0);\n"
            + "ctx.text(\"第一行\\n第二行\\n第三行\");"),

        new Doc("入门：先学会这 4 个",
            "button",
            "普通按钮，点了返回非 0。",
            "按下高亮，松手触发一次点击。",
            "参数：button(按钮文字)\n"
            + "返回：被点击时非 0（通常是 res_submit = 2），否则 0。\n\n"
            + "用法要点：\n"
            + "1. 判断 != 0 即可，不要写 == 1。\n"
            + "2. 按钮文字同时用于生成控件 id，同一帧里两个同名按钮会冲突，可用 pushId 区分。\n"
            + "3. 需要图标按钮或不居中文字时用 buttonEx。",
            "if (ctx.button(\"确定\") != 0) {\n"
            + "    // 点击处理\n"
            + "}"),

        new Doc("入门：先学会这 4 个",
            "layoutRow",
            "规划一行：几列、每列多宽、行高。",
            "后续控件按列宽横向排列，排满自动换行。",
            "参数：layoutRow(列数, 列宽数组, 行高)\n\n"
            + "- 列数：这一行放几个控件。\n"
            + "- 列宽：i32 数组，-1 表示占满剩余宽度，0 表示按内容自适应。\n"
            + "- 行高：0 表示自动，负值表示占满剩余高度。\n\n"
            + "用法要点：\n"
            + "1. 几乎每个控件前都要先调用它，否则会沿用上一行的布局。\n"
            + "2. 数组长度必须和列数一致。\n"
            + "3. 多列并排时配合 layoutBeginColumn / layoutEndColumn。",
            "ctx.layoutRow(2, &.{ 100, -1 }, 0);\n"
            + "ctx.label(\"名称:\");\n"
            + "ctx.label(\"值\");"),

        // ---------------------------------------------------------- 常用控件
        new Doc("常用控件",
            "checkbox",
            "复选框，结果存进一个 bool。",
            "方框点击切换勾选，状态自己保存。",
            "参数：checkbox(文字, &变量)\n"
            + "返回：状态变化时含 res_change，否则 0。\n\n"
            + "用法要点：\n"
            + "1. 变量要放在 onFrame 外面的持久存储里（App 字段或全局），否则每帧会被重置。\n"
            + "2. 控件只负责改这个变量，勾选状态由变量自己跨帧保存。",
            "var enabled = true;\n"
            + "_ = ctx.checkbox(\"启用\", &enabled);"),

        new Doc("常用控件",
            "sliderEx / slider",
            "滑块，左右拖动改数值。",
            "拖动调整数值，滑块上实时显示。",
            "参数：sliderEx(&值, 最小值, 最大值, 步长, 格式串, 对齐)\n"
            + "返回：数值变化时含 res_change。\n\n"
            + "- 步长为 0 表示连续；设为 1 表示只能取整数。\n"
            + "- 格式串用 Zig 格式化，如 \"{d:.0}\" 显示整数、\"{d:.2}\" 保留两位小数。\n"
            + "- 对齐常用 opt_align_center / opt_align_right。\n\n"
            + "小技巧：Shift + 点击可以切换成键盘直接输入数值。\n"
            + "简单版：slider(&值, 最小, 最大) 使用默认格式。",
            "var volume: f32 = 50;\n"
            + "ctx.sliderEx(&volume, 0, 100, 0, \"{d:.0}\", microui.opt_align_center);"),

        new Doc("常用控件",
            "number / numberEx",
            "数字框，左右拖动微调。",
            "按住左右拖动按步长加减，Shift + 点击可键入。",
            "参数：number(&值, 步长) / numberEx(&值, 步长, 格式串, 对齐)\n"
            + "返回：数值变化时含 res_change。\n\n"
            + "用法要点：\n"
            + "1. 每拖动 1 像素加减一个步长，步长常设 1 或 0.1。\n"
            + "2. Shift + 点击可直接输入数值。\n"
            + "3. 需要范围限制时，自己用 std.math.clamp 夹一下。",
            "var count: f32 = 1;\n"
            + "_ = ctx.number(&count, 1);"),

        new Doc("常用控件",
            "textbox / textboxEx",
            "单行输入框，文字写进缓冲区。",
            "点击聚焦弹出键盘，可编辑文字。",
            "参数：textbox(&缓冲区) / textboxEx(&缓冲区, 选项)\n"
            + "返回：回车提交时含 res_submit；文字变化时含 res_change。\n\n"
            + "缓冲区必须是 [N]u8，并以 0 结尾。\n\n"
            + "用法要点：\n"
            + "1. 缓冲区要比预计输入略大，至少留 1 字节给结尾的 0。\n"
            + "2. 安卓后端会在聚焦时自动拉起输入法并定位候选窗。\n"
            + "3. 连续多个输入框会用各自的缓冲区地址区分 id，不会串焦点。",
            "var input = @as([64]u8, @splat(0));\n"
            + "if ((ctx.textbox(&input) & microui.res_submit) != 0) {\n"
            + "    // 回车提交\n"
            + "}"),

        new Doc("常用控件",
            "buttonEx",
            "带图标或自定义对齐的按钮。",
            "图标按钮，或文字不居中的按钮。",
            "参数：buttonEx(文字或 null, 图标 id 或 null, 选项)\n"
            + "返回：被点击时非 0。\n\n"
            + "用法要点：\n"
            + "1. 文字传 null 时，会用图标 id 生成控件 id。\n"
            + "2. 图标 id 来自 microui.Icon 枚举，例如 @intFromEnum(microui.Icon.check)。\n"
            + "3. 对齐用 opt_align_center / opt_align_right。",
            "if (ctx.buttonEx(null, @intFromEnum(microui.Icon.check), microui.opt_align_center) != 0) {\n"
            + "    // 点击图标按钮\n"
            + "}"),

        // ---------------------------------------------------------- 折叠与滚动
        new Doc("折叠与滚动",
            "header / headerEx",
            "可折叠的标题。",
            "点击展开/收起下面的内容。",
            "参数：header(文字) / headerEx(文字, 选项)\n"
            + "返回：展开时非 0，这时才需要绘制内部内容。\n\n"
            + "用法要点：\n"
            + "1. 必须用 if 包住内部内容，收起时才不会绘制。\n"
            + "2. 加 opt_expanded 可以让初始状态就是展开的。\n"
            + "3. 需要树形层级时用 beginTreeNode。",
            "if (ctx.headerEx(\"高级选项\", microui.opt_expanded) != 0) {\n"
            + "    ctx.label(\"展开后可见的内容\");\n"
            + "}"),

        new Doc("折叠与滚动",
            "beginTreeNode / endTreeNode",
            "树节点，缩进 + 展开箭头。",
            "按层级缩进显示，点击箭头展开/收起。",
            "参数：beginTreeNode(文字) / beginTreeNodeEx(文字, 选项)\n"
            + "返回：展开时非 0，此时在内部绘制子项，最后调用 endTreeNode。\n\n"
            + "用法要点：\n"
            + "1. 节点文字就是 id，同帧出现同名节点要 pushId 区分。\n"
            + "2. 只在展开分支里调用 endTreeNode，保持配对。\n"
            + "3. 可以层层嵌套，实现多级树。",
            "if (ctx.beginTreeNode(\"设置\") != 0) {\n"
            + "    ctx.label(\"子项\");\n"
            + "    ctx.endTreeNode();\n"
            + "}"),

        new Doc("折叠与滚动",
            "beginPanel / beginPanelEx",
            "带背景和滚动条的面板。",
            "内容超出高度时出现滚动条，可独立滚动。",
            "参数：beginPanel(名字) / beginPanelEx(名字, 选项)\n"
            + "返回：无；必须配对调用 endPanel。\n\n"
            + "用法要点：\n"
            + "1. 面板本身是布局里的一个矩形，先用 layoutRow 给出宽高。\n"
            + "2. 面板内部是独立的滚动容器，超出部分自动可滚。\n"
            + "3. 名字用于生成 id，同帧同名面板会共享滚动状态。",
            "ctx.layoutRow(1, &.{ 300 }, 120);\n"
            + "ctx.beginPanel(\"日志\");\n"
            + "ctx.layoutRow(1, &.{-1}, -1);\n"
            + "ctx.text(\"面板里的内容\");\n"
            + "ctx.endPanel();"),

        // ---------------------------------------------------------- 窗口与弹窗
        new Doc("窗口与弹窗",
            "beginWindowEx / endWindow",
            "创建一个独立窗口。",
            "带标题栏的窗口，可拖动、缩放、关闭。",
            "参数：beginWindowEx(标题, 矩形, 选项) / beginWindow(标题, 矩形)\n"
            + "返回：窗口打开时非 0，此时才绘制内容，最后必须 endWindow。\n\n"
            + "常用选项：\n"
            + "- opt_no_resize：禁止缩放\n"
            + "- opt_no_close：去掉关闭按钮\n"
            + "- opt_no_title：不显示标题栏\n"
            + "- opt_auto_size：窗口随内容自动大小\n\n"
            + "用法要点：\n"
            + "1. 必须判断返回值非 0，再进入 begin / end 配对绘制。\n"
            + "2. 矩形只在窗口第一次创建时使用，之后会记住上次的位置和大小。",
            "if (ctx.beginWindowEx(\"我的窗口\", microui.Rect.init(10, 20, 320, 240),\n"
            + "        microui.opt_no_resize | microui.opt_no_close) != 0) {\n"
            + "    ctx.label(\"Hello\");\n"
            + "    ctx.endWindow();\n"
            + "}"),

        new Doc("窗口与弹窗",
            "openPopup / beginPopup / endPopup",
            "点击后弹出的小窗口。",
            "在点击位置附近弹出，点外部自动关闭。",
            "参数：openPopup(名字)；beginPopup(名字) 返回非 0 时绘制内容；endPopup。\n\n"
            + "用法要点：\n"
            + "1. openPopup 与 beginPopup 必须使用同一个名字。\n"
            + "2. 弹窗自带 opt_popup，点击窗口外部会自动关闭。\n"
            + "3. 同名窗口和弹窗会共用同一个容器，不要混用名字。",
            "if (ctx.button(\"打开\") != 0) ctx.openPopup(\"弹窗\");\n"
            + "if (ctx.beginPopup(\"弹窗\") != 0) {\n"
            + "    ctx.label(\"弹窗内容\");\n"
            + "    ctx.endPopup();\n"
            + "}"),

        // ---------------------------------------------------------- 布局进阶
        new Doc("布局进阶",
            "layoutBeginColumn / layoutEndColumn",
            "把一行分成左右多列。",
            "多列并排，各自独立布局。",
            "参数：layoutBeginColumn() / layoutEndColumn()，必须成对出现。\n\n"
            + "用法要点：\n"
            + "1. 每列内部仍然要用 layoutRow 规划自己的行。\n"
            + "2. 列宽由外层 layoutRow 决定。\n"
            + "3. 适合左右两栏、卡片网格这类并排布局。",
            "ctx.layoutBeginColumn();\n"
            + "ctx.layoutRow(1, &.{-1}, 0);\n"
            + "ctx.label(\"左列\");\n"
            + "ctx.layoutEndColumn();\n"
            + "ctx.layoutBeginColumn();\n"
            + "ctx.layoutRow(1, &.{-1}, 0);\n"
            + "ctx.label(\"右列\");\n"
            + "ctx.layoutEndColumn();"),

        new Doc("布局进阶",
            "layoutWidth / layoutHeight / layoutSetNext",
            "微调下一块布局的尺寸或位置。",
            "精确控制控件宽高，或直接指定矩形。",
            "参数：\n"
            + "- layoutWidth(宽度)：设置当前行控件宽度，-1 占满。\n"
            + "- layoutHeight(高度)：设置当前行高度。\n"
            + "- layoutSetNext(矩形, 是否相对)：强行指定下一个控件的位置和大小。\n\n"
            + "用法要点：\n"
            + "1. 一般布局够用，需要异形界面时才用。\n"
            + "2. layoutSetNext 第二个参数传 true 表示相对当前布局位置。\n"
            + "3. 用 layoutNext() 可以取出下一个控件的矩形，自己拿去做自绘。",
            "ctx.layoutRow(1, &.{-1}, 24);\n"
            + "ctx.layoutWidth(-1);\n"
            + "ctx.label(\"占满整行\");"),

        new Doc("布局进阶",
            "pushId / popId",
            "给控件 id 加一层命名空间。",
            "同名控件互不干扰，各自独立。",
            "参数：pushId(数据) / popId()，必须成对。\n\n"
            + "什么时候需要：\n"
            + "1. 循环里创建多个同名控件（比如列表里的按钮）。\n"
            + "2. 结构相同、文字也相同的多个控件。\n"
            + "3. 嵌套容器里可能出现重名时。\n\n"
            + "数据可以是字符串、整数、变量地址等任意字节，只要同一帧内唯一即可。",
            "var key: usize = 42;\n"
            + "ctx.pushId(std.mem.asBytes(&key));\n"
            + "// 这里创建的控件 id 会带上 key\n"
            + "ctx.popId();"),

        // ---------------------------------------------------------- 自绘与主题
        new Doc("自绘与主题",
            "drawRect / drawControlText",
            "直接画色块和文字，不参与布局。",
            "可画色卡、进度条、自定义背景。",
            "参数：drawRect(矩形, 颜色)；drawControlText(文字, 矩形, 颜色 id, 对齐)。\n\n"
            + "用法要点：\n"
            + "1. 矩形一般用 layoutNext() 取，避免和布局打架。\n"
            + "2. 颜色用 microui.Color.init(r, g, b, a)，各分量取值 0-255。\n"
            + "3. drawRect 会被当前裁剪区裁剪，在面板/弹窗里绘制也安全。",
            "const r = ctx.layoutNext();\n"
            + "ctx.drawRect(r, microui.Color.init(80, 160, 255, 255));\n"
            + "ctx.drawControlText(\"#50A0FF\", r, .text, microui.opt_align_center);"),

        new Doc("自绘与主题",
            "drawText / drawIcon / drawBox",
            "画文字、图标、空心边框。",
            "可精确定位文字和图标，画出边框。",
            "参数：\n"
            + "- drawText(文字, 位置, 颜色)：位置是左上角坐标。\n"
            + "- drawIcon(图标 id, 矩形, 颜色)：图标铺满矩形。\n"
            + "- drawBox(矩形, 颜色)：画 1 像素宽的边框。\n\n"
            + "用法要点：\n"
            + "1. 三者都不参与布局，坐标要自己计算。\n"
            + "2. 图标 id 来自 microui.Icon 枚举。\n"
            + "3. 大量自绘建议放在窗口或面板内部，自动裁剪不越界。",
            "ctx.drawBox(ctx.layoutNext(), microui.Color.init(80, 160, 255, 255));"),

        new Doc("自绘与主题",
            "style.colors / applyTheme",
            "改颜色、间距、控件尺寸。",
            "整体配色与控件密度改变（例如暗色主题）。",
            "参数：ctx.style.colors[颜色 id] = 颜色；applyTheme 是 App.init 传入的一次性回调。\n\n"
            + "用法要点：\n"
            + "1. applyTheme 在启动时调用一次，适合放整套主题。\n"
            + "2. 颜色 id 来自 microui.ColorId，例如 button、button_hover、text、window_bg。\n"
            + "3. 还能修改 padding、title_height、thumb_size 等尺寸字段。",
            "ctx.style.colors[@intFromEnum(microui.ColorId.button)] =\n"
            + "    microui.Color.init(52, 58, 70, 255);"),

        // ---------------------------------------------------------- 底层
        new Doc("底层：自己写循环时才用",
            "begin / end",
            "手动跑一帧的开始和结束。",
            "控制每帧的命令收集与状态收尾。",
            "参数：begin() / end()，必须成对。\n\n"
            + "App 框架已经替你调用，只有自己写事件循环时才需要：\n"
            + "ctx.begin();\n"
            + "// 画控件\n"
            + "ctx.end();\n\n"
            + "用法要点：\n"
            + "1. begin 清空上一帧命令，并计算鼠标位移。\n"
            + "2. end 处理滚动、焦点、窗口层级，并重置本帧临时输入。\n"
            + "3. 不成对调用会触发断言失败。",
            "ctx.begin();\n"
            + "ctx.layoutRow(1, &.{-1}, 0);\n"
            + "ctx.label(\"自己管理帧\");\n"
            + "ctx.end();"),

        new Doc("底层：自己写循环时才用",
            "inputMouse* / inputKey* / inputText / inputScroll",
            "把触摸、键盘事件喂给 microui。",
            "自建后端时，点击、滚动、输入才会生效。",
            "常用输入函数：\n"
            + "- inputMouseMove(x, y)\n"
            + "- inputMouseDown(x, y, 按钮)；inputMouseUp(x, y, 按钮)，按钮常用 1 表示左键\n"
            + "- inputScroll(dx, dy)：滚轮/滑动滚动\n"
            + "- inputKeyDown(key)；inputKeyUp(key)\n"
            + "- inputText(\"文字\")：输入法上屏文字\n\n"
            + "用法要点：\n"
            + "1. 每帧先处理事件并调用这些函数，再 begin 和绘制。\n"
            + "2. 安卓输入法整句提交用 inputText，内部会保证 UTF-8 不被截断。\n"
            + "3. App 框架已在后端 poll 里做好映射，一般不需要自己调用。",
            "ctx.inputMouseMove(100, 80);\n"
            + "ctx.inputMouseDown(100, 80, 1);\n"
            + "// 下一帧\n"
            + "ctx.inputMouseUp(100, 80, 1);"),

        new Doc("底层：自己写循环时才用",
            "pushClipRect / popClipRect / setClip",
            "限制绘制区域。",
            "超出指定矩形的部分不会被画出来。",
            "参数：\n"
            + "- pushClipRect(矩形)：进入新的裁剪区，与当前区取交集。\n"
            + "- popClipRect()：退出裁剪区，必须成对。\n"
            + "- setClip(矩形)：给渲染器发一条立即设置裁剪的命令。\n\n"
            + "用法要点：\n"
            + "1. 滚动面板和弹窗内部都靠裁剪防止内容溢出。\n"
            + "2. 自己画自定义控件时，超出范围的内容要手动裁剪。\n"
            + "3. 裁剪栈必须严格成对，否则断言失败。",
            "ctx.pushClipRect(microui.Rect.init(0, 0, 200, 100));\n"
            + "ctx.drawRect(microui.Rect.init(0, 0, 400, 300),\n"
            + "    microui.Color.init(255, 0, 0, 255));\n"
            + "ctx.popClipRect();"),
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initPalette();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView head = new TextView(this);
        head.setText("microui API 文档");
        head.setTextColor(TEXT);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        head.setPadding(0, 0, 0, dp(4));
        root.addView(head);

        TextView sub = new TextView(this);
        sub.setText("按「常用 + 从易到难」排列。每张卡片有简明说明和示例，点「详情」看完整参数与注意事项。");
        sub.setTextColor(DESC);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setPadding(0, 0, 0, dp(8));
        root.addView(sub);

        String lastGroup = null;
        for (Doc d : DOCS) {
            if (!d.group.equals(lastGroup)) {
                root.addView(groupHeader(d.group));
                lastGroup = d.group;
            }
            root.addView(card(d));
        }

        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView groupHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(ACCENT);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(0, dp(10), 0, dp(8));
        return tv;
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
        c.addView(line("效果：" + d.effect, EFFECT, 13, dp(10)));

        TextView code = new TextView(this);
        code.setText(highlight(d.code));
        code.setTextColor(CODE_TEXT);
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

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(4);
        btns.setLayoutParams(blp);

        Button copy = new Button(this);
        copy.setText("复制代码");
        copy.setAllCaps(false);
        copy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        copy.setOnClickListener(v -> copyCode(d.code, d.title));
        btns.addView(copy);

        Button detail = new Button(this);
        detail.setText("详情");
        detail.setAllCaps(false);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(8);
        detail.setLayoutParams(dlp);
        detail.setOnClickListener(v -> showDetail(d));
        btns.addView(detail);

        c.addView(btns);
        return c;
    }

    /** 详情弹窗：完整参数、返回值与注意事项。 */
    private void showDetail(Doc d) {
        TextView body = new TextView(this);
        body.setText(d.detail);
        body.setTextColor(TEXT);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setLineSpacing(dp(4), 1f);
        int p = dp(20);
        body.setPadding(p, dp(8), p, dp(8));

        ScrollView sv = new ScrollView(this);
        sv.addView(body);

        new AlertDialog.Builder(this)
                .setTitle(d.title)
                .setView(sv)
                .setPositiveButton("复制代码", (dialog, which) -> copyCode(d.code, d.title))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void copyCode(String code, String title) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("microui code", code));
            Toast.makeText(this, "已复制：" + title, Toast.LENGTH_SHORT).show();
        }
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

    private CharSequence highlight(String src) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(src);
        boolean[] locked = new boolean[src.length()];   // 已着色的区间，后面的规则不再覆盖
        paint(sb, locked, RE_COMMENT, C_COMMENT);
        paint(sb, locked, RE_STRING, C_STRING);
        paint(sb, locked, RE_KEYWORD, C_KEYWORD);
        paint(sb, locked, RE_NUMBER, C_NUMBER);
        paint(sb, locked, RE_FUNC, C_FUNC);
        return sb;
    }

    private void paint(android.text.SpannableStringBuilder sb, boolean[] locked,
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
