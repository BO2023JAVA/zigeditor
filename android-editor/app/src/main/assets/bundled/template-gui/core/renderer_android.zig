//! microui 的安卓端渲染后端（SDL3 + SDL_ttf，手写绑定）
//!
//! 桌面渲染后端（renderer.zig，SDL3 + Windows GDI）已随桌面端一起移除：
//! - ASCII 走内置图集（atlas.zig）；
//! - 中文等 Unicode 字符用 SDL_ttf（FreeType）从内嵌字体栅格化到 512x512 动态纹理。
//!
//! 字体文件 font_simhei.ttf 编译期内嵌进 .so（@embedFile），免去 APK assets 访问。
//! 若要减小体积，可换成裁剪过的子集字体（如思源黑体子集）。
//! SDL 调用走 sdl3_android.zig 的手写绑定（Zig 0.16 无法为 Android 提供 cImport 所需 libc）。

const std = @import("std");
const microui = @import("microui");
const atlas = @import("atlas.zig");
const sdl = @import("sdl3_android");

const font_bytes = @embedFile("font_simhei.ttf");

// ===================== SDL 资源 =====================

var window: *sdl.SDL_Window = undefined;
var renderer: *sdl.SDL_Renderer = undefined;
var atlas_tex: *sdl.SDL_Texture = undefined;
var font: ?*sdl.TTF_Font = null;

/// 逻辑分辨率（dp）：1 逻辑单位 = 1dp，init 时按 物理尺寸/密度 换算
var logical_size: [2]c_int = .{ 400, 800 };

/// 屏幕密度（mdpi = 1.0）：逻辑坐标 × density = 物理像素
var density: f32 = 1.0;

/// 顶部安全区（dp）：状态栏/刘海高度，
/// 渲染时整体下移这么多、触摸换算时减去，避免内容被状态栏挡住
var inset_top: f32 = 0;

// ===================== 动态字集（SDL_ttf 按物理像素栅格化） =====================

/// 文字基准尺寸（dp）：microui 布局用 18dp 一行
const text_dp: i32 = 18;

/// 栅格化后的像素高度（init 时 = text_dp × density），保证高密度屏不糊
var text_px: i32 = 18;
/// 字体基线到行顶的距离（像素），由 TTF_GetFontAscent 给出
var font_ascent: i32 = 0;

/// 缓存纹理与单元格：单元边长按密度放大，一张纹理容纳全部常用字形
const glyph_tex_w = 1024;
const glyph_tex_h = 1024;
const glyph_map_max = 1024;
const max_cell = 128; // 单元边长上限（像素），支持到约 5 倍密度

var glyph_cell: i32 = 18; // 单元边长（像素）
var glyph_cols: i32 = 28;
var glyph_cell_total: usize = 784;

const Glyph = struct {
    cp: u32 = 0,
    x: i32 = 0, // 纹理内左上角
    y: i32 = 0,
    w: i32 = 0,
    h: i32 = 0,
    off_y: i32 = 0, // 相对基线的垂直偏移（负值向上）
    advance: i32 = 0, // 排版步进（像素）
};

var glyph_tex: *sdl.SDL_Texture = undefined;
var glyph_map: [glyph_map_max]Glyph = undefined;
var glyph_map_len: usize = 0;
var glyph_cursor: usize = 0;

/// 取码点对应的字形；缺失时用 SDL_ttf 按当前像素尺寸栅格化并缓存。返回 null 表示失败（占位跳过）
fn glyphCell(cp: u32) ?Glyph {
    for (glyph_map[0..glyph_map_len]) |g| {
        if (g.cp == cp) return g;
    }
    const fnt = font orelse return null;
    if (cp > 0xFFFF) return null; // 暂不支持代理对（emoji 等）
    if (glyph_map_len >= glyph_cell_total) {
        // 网格用尽：清空缓存从头复用（单元格会被整格覆盖，不会残留旧字形）
        glyph_map_len = 0;
        glyph_cursor = 0;
    }

    // 渲染字形（白色，后面只取 alpha 通道，颜色由纹理调制）
    const surf = sdl.ttf_render_glyph_blended(fnt, cp, sdl.SDL_Color{ .r = 255, .g = 255, .b = 255, .a = 255 }) orelse {
        diagLog("glyph null cp={d} err={s}", .{ cp, sdl.errorString() });
        return null;
    };
    defer sdl.destroySurface(surf);

    // 排版步进（像素）：由字体度量给出，ASCII 才是等宽比例正确
    var minx: c_int = 0;
    var maxx: c_int = 0;
    var miny: c_int = 0;
    var maxy: c_int = 0;
    var adv: c_int = 0;
    _ = sdl.ttf_get_glyph_metrics(fnt, cp, &minx, &maxx, &miny, &maxy, &adv);
    const advance: i32 = if (adv > 0) adv else text_px;

    // 分配网格：水平居中；垂直方向把表面顶端贴到单元格顶部
    const col = glyph_cursor % @as(usize, @intCast(glyph_cols));
    const row = glyph_cursor / @as(usize, @intCast(glyph_cols));
    const cx: i32 = @intCast(col * @as(usize, @intCast(glyph_cell)));
    const cy: i32 = @intCast(row * @as(usize, @intCast(glyph_cell)));
    glyph_cursor += 1;

    const cell = glyph_cell;
    const dx = @divTrunc(cell - @as(i32, surf.w), 2);
    // 实测：SDL_ttf 的单字表面高度就是整行高（font height），
    // 字形在表面内已按 "ascent - sz_top" 摆好（H 在第 6 行、e 在第 14 行）。
    // 所以把表面顶端统一对到行顶，所有字形自然共用同一条基线；
    // 若按每个字的 sz_top 单独偏移，小写字母就会整体下沉。
    const sx0: i32 = @max(0, -dx);
    const gw: i32 = @min(@as(i32, surf.w) - sx0, cell - @max(0, dx));
    const gh: i32 = @min(@as(i32, surf.h), cell);
    if (gw <= 0 or gh <= 0) return null;

    // 缓冲区按 max_cell 行宽排版，上传时只取 cell×cell 区域（pitch = max_cell*4）
    var pixels: [max_cell * max_cell * 4]u8 = @splat(0);
    const src: [*]const u8 = @ptrCast(surf.pixels);
    var yy: i32 = 0;
    while (yy < gh) : (yy += 1) {
        var xx: i32 = 0;
        while (xx < gw) : (xx += 1) {
            // SDL_ttf 输出 ARGB8888，小端内存序 B,G,R,A → alpha 在字节 3
            const sy = yy;
            const sx = sx0 + xx;
            const a = src[@as(usize, @intCast(sy)) * @as(usize, @intCast(surf.pitch)) + @as(usize, @intCast(sx)) * 4 + 3];
            const px = @max(0, dx) + xx;
            const py = yy;
            const idx = @as(usize, @intCast(py)) * @as(usize, max_cell) * 4 + @as(usize, @intCast(px)) * 4;
            pixels[idx + 0] = 255;
            pixels[idx + 1] = 255;
            pixels[idx + 2] = 255;
            pixels[idx + 3] = a;
        }
    }
    var cell_rect = sdl.SDL_Rect{ .x = cx, .y = cy, .w = cell, .h = cell };
    if (!sdl.updateTexture(glyph_tex, &cell_rect, &pixels, max_cell * 4)) {
        diagLog("glyph update fail cp={d} err={s}", .{ cp, sdl.errorString() });
    }

    const g = Glyph{
        .cp = cp,
        .x = cx + @max(0, dx),
        .y = cy,
        .w = gw,
        .h = gh,
        // drawGlyph 按 "行顶 + ascent + off_y" 放置表面；表面顶端即行顶
        .off_y = -font_ascent,
        .advance = advance,
    };
    glyph_map[glyph_map_len] = g;
    glyph_map_len += 1;
    return g;
}

/// 解码一个 UTF-8 码点并推进索引（非法字节按单字节处理）
fn decodeUtf8(s: []const u8, i: *usize) u32 {
    const b0 = s[i.*];
    var cp: u32 = b0;
    var len: usize = 1;
    if (b0 >= 0xF0) {
        cp = b0 & 0x07;
        len = 4;
    } else if (b0 >= 0xE0) {
        cp = b0 & 0x0F;
        len = 3;
    } else if (b0 >= 0xC0) {
        cp = b0 & 0x1F;
        len = 2;
    } else if (b0 >= 0x80) {
        i.* += 1;
        return cp; // 孤立续字节
    }
    if (i.* + len > s.len) {
        i.* += 1;
        return cp;
    }
    var k: usize = 1;
    while (k < len) : (k += 1) {
        const b = s[i.* + k];
        if ((b & 0xc0) != 0x80) {
            i.* += 1;
            return cp;
        }
        cp = (cp << 6) | (b & 0x3f);
    }
    i.* += len;
    return cp;
}

/// 致命错误：Debug 构建打印原因后退出，发布构建直接退出（避免拖入格式化代码）
fn fail(comptime fmt: []const u8, args: anytype) noreturn {
    if (std.debug.runtime_safety) {
        std.debug.print(fmt ++ "\n", args);
    }
    std.process.exit(1);
}

fn sdlErr() []const u8 {
    return sdl.errorString();
}

/// 格式化并写 logcat（诊断用，tag = ZigDemo）
fn diagLog(comptime fmt: []const u8, args: anytype) void {
    var buf: [192]u8 = undefined;
    const s = std.fmt.bufPrint(&buf, fmt, args) catch return;
    sdl.logMsg(s);
}

// ===================== 公开接口 =====================

/// 初始化后端：SDL + 窗口 + 渲染器 + 图集 + SDL_ttf 字体 + 动态字形纹理。
/// 在 Android 上由 SDLActivity 先初始化 SDL 环境，SDL_main 内直接创建窗口。
pub fn init(title: [:0]const u8, _: c_int, _: c_int) void {
    if (!sdl.init(sdl.SDL_INIT_VIDEO)) {
        fail("SDL_Init failed: {s}", .{sdlErr()});
    }
    // 强制显示屏幕键盘：模拟器/真机存在物理键盘时 SDL 默认不弹 IME
    //（AutoShowingScreenKeyboard 只在无实体键盘或显式开启时返回 true）
    _ = sdl.setHint("SDL_ENABLE_SCREEN_KEYBOARD", "1");

    // 尺寸传 0：Android 上窗口由系统表面决定（全屏）
    var win: ?*sdl.SDL_Window = null;
    var rr: ?*sdl.SDL_Renderer = null;
    if (!sdl.createWindowAndRenderer(title.ptr, 0, 0, 0, &win, &rr)) {
        fail("SDL_CreateWindowAndRenderer failed: {s}", .{sdlErr()});
    }
    window = win.?;
    renderer = rr.?;
    // 逻辑分辨率：按安卓的 dp 模型，1 逻辑单位 = 1dp = density 物理像素。
    // 渲染时统一做 dp→px 换算，文字按物理像素栅格化，因此不会像整屏拉伸那样发虚。
    var pw: c_int = 0;
    var ph: c_int = 0;
    _ = sdl.getWindowSize(window, &pw, &ph);
    density = sdl.getWindowDisplayScale(window);
    if (!(density >= 1.0)) density = 1.0; // 取不到时按 1 倍
    if (density > 4.0) density = 4.0;
    text_px = @intFromFloat(@round(@as(f32, @floatFromInt(text_dp)) * density));
    glyph_cell = text_px + @divTrunc(text_px, 4) + 2; // 留出上下伸部空间
    if (glyph_cell > max_cell) glyph_cell = max_cell;
    glyph_cols = @divTrunc(glyph_tex_w, glyph_cell);
    glyph_cell_total = @intCast(glyph_cols * @divTrunc(glyph_tex_h, glyph_cell));
    if (glyph_cell_total > glyph_map_max) glyph_cell_total = glyph_map_max;
    logical_size = .{
        @intFromFloat(@round(@as(f32, @floatFromInt(pw)) / density)),
        @intFromFloat(@round(@as(f32, @floatFromInt(ph)) / density)),
    };
    // 诊断：确认渲染目标就是窗口物理像素（output < pixels 说明被降分辨率再放大，画面必糊）
    var ow: c_int = 0;
    var oh: c_int = 0;
    var iw: c_int = 0;
    var ih: c_int = 0;
    _ = sdl.getRenderOutputSize(renderer, &ow, &oh);
    _ = sdl.getWindowSizeInPixels(window, &iw, &ih);
    diagLog("density={d} window={d}x{d} dp={d}x{d} pixels={d}x{d} output={d}x{d} text={d}px cell={d}px cells={d}", .{
        @as(i32, @intFromFloat(density)), pw, ph, logical_size[0], logical_size[1], iw, ih, ow, oh,
        text_px, glyph_cell, glyph_cell_total,
    });

    // 状态栏/刘海安全区：物理像素换算成 dp，渲染整体下移、触摸反向减去
    var safe = sdl.SDL_Rect{ .x = 0, .y = 0, .w = 0, .h = 0 };
    if (sdl.getWindowSafeArea(window, &safe) and ph > 0) {
        inset_top = @as(f32, @floatFromInt(safe.y)) / density;
        diagLog("safe area y={d} h={d} -> inset_top={d}dp", .{ safe.y, safe.h, @as(i32, @intFromFloat(inset_top)) });
    }
    // 文本输入（IME）由文本框聚焦驱动，见 frameEnd / setTextInputArea

    // 把 128x128 的 alpha 位图打包成 RGBA
    var rgba: [atlas.W * atlas.H * 4]u8 = undefined;
    for (atlas.texture, 0..) |a, i| {
        rgba[i * 4 + 0] = 0xff;
        rgba[i * 4 + 1] = 0xff;
        rgba[i * 4 + 2] = 0xff;
        rgba[i * 4 + 3] = a;
    }
    // 注意：RGBA8888 在小端机器上的内存字节序是 A,B,G,R（见 SDL_pixels.h 的别名表），
    // 直接按 [R,G,B,A] 上传会错位导致文字出现彩色底块。
    // SDL_PIXELFORMAT_RGBA32（小端 = ABGR8888）的内存字节序为 R,G,B,A，与上传数据一致。
    atlas_tex = sdl.createTexture(renderer, sdl.SDL_PIXELFORMAT_RGBA32, sdl.SDL_TEXTUREACCESS_STATIC, atlas.W, atlas.H) orelse
        fail("SDL_CreateTexture failed: {s}", .{sdlErr()});
    if (!sdl.updateTexture(atlas_tex, null, &rgba, atlas.W * 4)) {
        fail("SDL_UpdateTexture failed: {s}", .{sdlErr()});
    }
    _ = sdl.setTextureBlendMode(atlas_tex, sdl.SDL_BLENDMODE_BLEND);
    // 图标按密度放大绘制，用线性过滤减少块状锯齿
    _ = sdl.setTextureScaleMode(atlas_tex, sdl.SDL_SCALEMODE_LINEAR);

    // 动态字集：SDL_ttf 按物理像素尺寸栅格化 + 1024x1024 透明纹理（STREAMING：按需更新）
    if (!sdl.ttf_init()) {
        fail("TTF_Init failed: {s}", .{sdlErr()});
    }
    const io = sdl.ioFromConstMem(font_bytes, font_bytes.len); // @embedFile 返回的即指针
    font = sdl.ttf_open_font_io(io.?, true, @floatFromInt(text_px));
    if (font == null) {
        fail("TTF_OpenFontIO failed: {s}", .{sdlErr()});
    }
    diagLog("TTF font ok, bytes={d} size={d}px", .{ font_bytes.len, text_px });
    // TTF_GetFontAscent 直接返回 ascent（像素），不是出参
    font_ascent = sdl.ttf_get_font_ascent(font.?);
    diagLog("font ascent={d}px text_px={d}px", .{ font_ascent, text_px });
    glyph_tex = sdl.createTexture(renderer, sdl.SDL_PIXELFORMAT_RGBA32, sdl.SDL_TEXTUREACCESS_STREAMING, glyph_tex_w, glyph_tex_h) orelse
        fail("创建字形纹理失败: {s}", .{sdlErr()});
    // 单元格在首次使用时会整格写入（含清零），无需预清空纹理
    _ = sdl.setTextureBlendMode(glyph_tex, sdl.SDL_BLENDMODE_BLEND);
    // 字形永远按物理像素 1:1 贴图：必须用最近邻，否则非整数密度下线性采样会把字糊掉
    _ = sdl.setTextureScaleMode(glyph_tex, sdl.SDL_SCALEMODE_NEAREST);
    diagLog("glyph tex ok, renderer ready", .{});
}

/// 释放全部资源
pub fn deinit() void {
    if (font) |f| sdl.ttf_close_font(f);
    sdl.ttf_quit();
    sdl.destroyTexture(atlas_tex);
    sdl.destroyTexture(glyph_tex);
    sdl.destroyRenderer(renderer);
    sdl.destroyWindow(window);
    sdl.quit();
}

/// 清屏：用给定颜色填充整个画面
pub fn begin(color: microui.Color) void {
    // 安卓的 insets 常在窗口创建之后才生效：只在 init 查一次会拿到 0，
    // 表现就是窗口标题被状态栏挡住。这里每帧同步一次（读缓存，开销可忽略）。
    syncWindowMetrics();
    _ = sdl.setRenderDrawColor(renderer, color.r, color.g, color.b, color.a);
    _ = sdl.renderClear(renderer);
}

/// 同步逻辑尺寸（dp）与顶部安全区；变化时才更新并打日志
fn syncWindowMetrics() void {
    var pw: c_int = 0;
    var ph: c_int = 0;
    if (!sdl.getWindowSize(window, &pw, &ph) or ph <= 0) return;
    const dw: c_int = @intFromFloat(@round(@as(f32, @floatFromInt(pw)) / density));
    const dh: c_int = @intFromFloat(@round(@as(f32, @floatFromInt(ph)) / density));
    if (dw != logical_size[0] or dh != logical_size[1]) {
        logical_size = .{ dw, dh };
        diagLog("resize: window={d}x{d} dp={d}x{d}", .{ pw, ph, dw, dh });
    }

    var safe = sdl.SDL_Rect{ .x = 0, .y = 0, .w = 0, .h = 0 };
    if (sdl.getWindowSafeArea(window, &safe)) {
        const top: f32 = @as(f32, @floatFromInt(safe.y)) / density;
        if (@abs(top - inset_top) > 0.3) {
            inset_top = top;
            diagLog("inset_top -> {d}dp (window {d}x{d})", .{ @as(i32, @intFromFloat(top)), pw, ph });
        }
    }
}

/// 提交本帧画面
pub fn end() void {
    _ = sdl.renderPresent(renderer);
}

/// 当前窗口像素尺寸（物理屏幕，诊断用）
pub fn windowSize() [2]c_int {
    var w: c_int = 0;
    var h: c_int = 0;
    _ = sdl.getWindowSize(window, &w, &h);
    return .{ w, h };
}

/// 逻辑分辨率（UI 坐标系尺寸；触摸归一化坐标换算用）
pub fn logicalSize() [2]c_int {
    return logical_size;
}

/// 当前手指跟踪：只认第一个按下的手指，后续手指（误触/多指）忽略
var active_finger: ?u64 = null;

/// 触摸归一化坐标 → 逻辑像素坐标（与渲染坐标系一致）
fn fingerToPixels(x: f32, y: f32) struct { ix: i32, iy: i32 } {
    const sz = logical_size;
    return .{
        .ix = @intFromFloat(x * @as(f32, @floatFromInt(sz[0]))),
        .iy = @intFromFloat(y * @as(f32, @floatFromInt(sz[1])) - inset_top),
    };
}

/// 轮询本帧全部 SDL 事件并驱动 microui 输入；返回 true 表示请求退出。
/// 触摸映射为鼠标左键，IME 文本进 inputText，物理键盘可打印字符直通。
pub fn poll(ctx: *microui.Context) bool {
    var e: sdl.SDL_Event = undefined;
    while (sdl.pollEvent(&e)) {
        switch (e.type) {
            sdl.SDL_EVENT_QUIT => return true,
            sdl.SDL_EVENT_FINGER_DOWN => {
                // 无条件接管跟踪：模拟器/设备可能丢失 UP 或 finger_id 不稳定
                active_finger = e.finger.finger_id;
                const p = fingerToPixels(e.finger.x, e.finger.y);
                ctx.inputMouseDown(p.ix, p.iy, microui.mouse_left);
            },
            sdl.SDL_EVENT_FINGER_MOTION => {
                const f = active_finger orelse continue;
                if (f != e.finger.finger_id) continue;
                const p = fingerToPixels(e.finger.x, e.finger.y);
                ctx.inputMouseMove(p.ix, p.iy);
            },
            sdl.SDL_EVENT_FINGER_UP => {
                if (active_finger == null) continue;
                active_finger = null;
                const p = fingerToPixels(e.finger.x, e.finger.y);
                ctx.inputMouseUp(p.ix, p.iy, microui.mouse_left);
            },
            sdl.SDL_EVENT_TEXT_INPUT => {
                const s = std.mem.span(e.text.text orelse continue);
                // 临时诊断：确认粘贴/输入法提交的字符是否完整达到（中文字节是否丢失）
                var dbg: [192]u8 = undefined;
                const d = std.fmt.bufPrint(&dbg, "TI len={d} hex={x}", .{ s.len, s[0..@min(s.len, 24)] }) catch "";
                sdl.logMsg(d);
                ctx.inputText(s);
            },
            sdl.SDL_EVENT_KEY_DOWN, sdl.SDL_EVENT_KEY_UP => {
                if (e.key.key == sdl.SDLK_RETURN) {
                    if (e.type == sdl.SDL_EVENT_KEY_DOWN) ctx.inputKeyDown(microui.key_return);
                } else if (e.key.key == sdl.SDLK_BACKSPACE) {
                    if (e.type == sdl.SDL_EVENT_KEY_DOWN) ctx.inputKeyDown(microui.key_backspace);
                } else if (e.key.key >= 32 and e.key.key < 0x7F and e.type == sdl.SDL_EVENT_KEY_DOWN) {
                    // 可打印字符直接当文本输入：模拟器/外接键盘直通，不依赖系统 IME
                    var ch: [2]u8 = .{ @intCast(e.key.key), 0 };
                    ctx.inputText(ch[0..1]);
                } else if (e.key.key == 9 and e.type == sdl.SDL_EVENT_KEY_DOWN) {
                    // Tab 键：push 一个 TEXT_INPUT 事件，模拟 IME 提交中文（验证用）
                    var tev = sdl.SDL_Event{ .text = .{
                        .type = sdl.SDL_EVENT_TEXT_INPUT,
                        .reserved = 0,
                        .timestamp = 0,
                        .window_id = 0,
                        .text = "中文测试",
                    } };
                    _ = sdl.pushEvent(&tev);
                }
            },
            else => {},
        }
    }
    return false;
}

/// 文本输入（IME）状态：只在有文本框聚焦时打开，失焦后关掉。
/// 注意 SDL3 Android 的 SDL_StartTextInput 会直接拉起系统键盘，不能每帧调用，
/// 否则用户按返回键收起的键盘会在下一帧又被弹出（表现为「输入法一直弹」）。
var ime_open = false;
/// 本帧是否有文本框聚焦（由 setTextInputArea 每帧打点）
var ime_focused = false;

/// 帧末钩子：本帧没有文本框聚焦就收起 IME。
/// SDL3 的 SDL_SendKeyboardText 只在 text input active 时派发 TEXT_INPUT，
/// 聚焦期间由 setTextInputArea 保持激活，失焦后在这里关闭。
pub fn frameEnd() void {
    if (!ime_focused and ime_open) {
        ime_open = false;
        sdl.stopTextInput(window);
    }
    ime_focused = false;
}

/// 确保文本输入激活（给没有输入框、但要自己接收 IME 文本的应用用）。
pub fn ensureTextInput() void {
    ime_focused = true;
    if (!ime_open) {
        ime_open = true;
        _ = sdl.startTextInput(window);
    }
}

/// textbox 聚焦回调：拉起系统 IME 并设置输入框矩形。
/// 坐标是逻辑坐标（与渲染一致），SDL 用它定位输入法候选窗。
/// focus 保持期间 textboxRaw 每帧都调本回调：键盘只在首次聚焦（或失焦后再次聚焦）时拉起，
/// 矩形按坐标变化防抖，避免每帧 showSoftInput 把 IME 动画取消掉。
var last_ime_rect: [4]i32 = .{ 0, 0, 0, 0 };
var last_ime_valid = false;
pub fn setTextInputArea(_: *microui.Context, x: i32, y: i32, w: i32, h: i32) void {
    ime_focused = true;
    if (!ime_open) {
        ime_open = true;
        _ = sdl.startTextInput(window);
    }
    if (last_ime_valid and last_ime_rect[0] == x and last_ime_rect[1] == y and last_ime_rect[2] == w and last_ime_rect[3] == h) return;
    last_ime_rect = .{ x, y, w, h };
    last_ime_valid = true;
    // microui 给的是 dp 坐标，SDL 需要物理像素
    const r = sdl.SDL_Rect{
        .x = @intFromFloat(@as(f32, @floatFromInt(x)) * density),
        .y = @intFromFloat((@as(f32, @floatFromInt(y)) + inset_top) * density),
        .w = @intFromFloat(@as(f32, @floatFromInt(w)) * density),
        .h = @intFromFloat(@as(f32, @floatFromInt(h)) * density),
    };
    _ = sdl.setTextInputArea(window, &r, 0);
}

/// 设置裁剪区域（库用超大矩形表示"不裁剪"，此时直接关闭 SDL 裁剪）
pub fn setClipRect(rect: microui.Rect) void {
    if (rect.w >= 0x1000000 or rect.h >= 0x1000000) {
        _ = sdl.setRenderClipRect(renderer, null);
    } else {
        var r = sdl.SDL_Rect{
            .x = @intFromFloat(@round(@as(f32, @floatFromInt(rect.x)) * density)),
            .y = @intFromFloat(@round((@as(f32, @floatFromInt(rect.y)) + inset_top) * density)),
            .w = @intFromFloat(@round(@as(f32, @floatFromInt(rect.w)) * density)),
            .h = @intFromFloat(@round(@as(f32, @floatFromInt(rect.h)) * density)),
        };
        _ = sdl.setRenderClipRect(renderer, &r);
    }
}

/// 填充矩形（边线对齐到整数像素，非整数密度下也不会出现半像素虚边）
pub fn drawRect(rect: microui.Rect, color: microui.Color) void {
    _ = sdl.setRenderDrawColor(renderer, color.r, color.g, color.b, color.a);
    var fr = sdl.SDL_FRect{
        .x = @round(@as(f32, @floatFromInt(rect.x)) * density),
        .y = @round((@as(f32, @floatFromInt(rect.y)) + inset_top) * density),
        .w = @round(@as(f32, @floatFromInt(rect.w)) * density),
        .h = @round(@as(f32, @floatFromInt(rect.h)) * density),
    };
    _ = sdl.renderFillRect(renderer, &fr);
}

/// 绘制一帧内的一个字形（纹理 1:1 像素绘制，不放大）
/// x / line_top 都是物理像素整数：非整数密度下也先取整，避免线性采样把字糊掉
fn drawGlyph(g: Glyph, x: i32, line_top: i32) void {
    const src = sdl.SDL_FRect{
        .x = @floatFromInt(g.x),
        .y = @floatFromInt(g.y),
        .w = @floatFromInt(g.w),
        .h = @floatFromInt(g.h),
    };
    var dst = sdl.SDL_FRect{
        .x = @floatFromInt(x),
        .y = @floatFromInt(line_top + font_ascent + g.off_y),
        .w = @floatFromInt(g.w),
        .h = @floatFromInt(g.h),
    };
    if (!sdl.renderTexture(renderer, glyph_tex, &src, &dst)) {
        if (std.debug.runtime_safety) std.debug.print("RenderTexture 失败: {s}\n", .{sdlErr()});
    }
}

/// 绘制文本：所有字符都走 SDL_ttf 字形纹理（按物理像素栅格化，密度无关的清晰度）
pub fn drawText(str: []const u8, pos: microui.Vec2, color: microui.Color) void {
    _ = sdl.setTextureColorMod(glyph_tex, color.r, color.g, color.b);
    _ = sdl.setTextureAlphaMod(glyph_tex, color.a);

    // 步进也在物理像素上累加（整数）：字形落点一律是整数像素，密度不是整数也不会发虚
    var pen_x: i32 = @intFromFloat(@round(@as(f32, @floatFromInt(pos.x)) * density));
    const line_top: i32 = @intFromFloat(@round((@as(f32, @floatFromInt(pos.y)) + inset_top) * density));
    var i: usize = 0;
    while (i < str.len) {
        const ch = str[i];
        if (ch < 0x80) {
            i += 1;
            if (ch < 32) continue;
            if (glyphCell(ch)) |g| {
                drawGlyph(g, pen_x, line_top);
                pen_x += g.advance;
            } else {
                pen_x += text_px;
            }
        } else if ((ch & 0xc0) == 0x80) {
            i += 1; // 孤立续字节，跳过
        } else {
            // Unicode：解码码点，动态生成/复用字形
            const cp = decodeUtf8(str, &i);
            if (glyphCell(cp)) |g| {
                drawGlyph(g, pen_x, line_top);
                pen_x += g.advance;
            } else {
                pen_x += text_px; // 无字形也占位，保持对齐
            }
        }
    }
}

/// 绘制图标（居中对齐到 rect 内；按密度放大，配合线性过滤）
pub fn drawIcon(id: u32, rect: microui.Rect, color: microui.Color) void {
    if (id < 1 or id > atlas.icons.len) return;
    const g = atlas.icons[id - 1];
    const src = sdl.SDL_FRect{
        .x = @floatFromInt(g.x),
        .y = @floatFromInt(g.y),
        .w = @floatFromInt(g.w),
        .h = @floatFromInt(g.h),
    };
    const rw: f32 = @round(@as(f32, @floatFromInt(rect.w)) * density);
    const rh: f32 = @round(@as(f32, @floatFromInt(rect.h)) * density);
    const gw: f32 = @round(@as(f32, @floatFromInt(g.w)) * density);
    const gh: f32 = @round(@as(f32, @floatFromInt(g.h)) * density);
    var dst = sdl.SDL_FRect{
        .x = @round(@as(f32, @floatFromInt(rect.x)) * density + (rw - gw) * 0.5),
        .y = @round((@as(f32, @floatFromInt(rect.y)) + inset_top) * density + (rh - gh) * 0.5),
        .w = gw,
        .h = gh,
    };
    _ = sdl.setTextureColorMod(atlas_tex, color.r, color.g, color.b);
    _ = sdl.setTextureAlphaMod(atlas_tex, color.a);
    _ = sdl.renderTexture(renderer, atlas_tex, &src, &dst);
}

/// 渲染一整帧：遍历 microui 的命令列表并逐条绘制（在 begin/end 之间调用）
pub fn render(ctx: *microui.Context) void {
    var it = ctx.commandIter();
    while (it.next()) |cmd| {
        switch (cmd) {
            .text => |t| drawText(t.str[0..t.len], t.pos, t.color),
            .rect => |r| drawRect(r.rect, r.color),
            .icon => |ic| drawIcon(ic.id, ic.rect, ic.color),
            .clip => |rc| setClipRect(rc),
            else => {},
        }
    }
}

/// microui 文本宽度回调：按字形物理步进累加后换算回 dp
pub fn textWidth(ctx: *microui.Context, str: []const u8) i32 {
    _ = ctx;
    var px: i32 = 0;
    var i: usize = 0;
    while (i < str.len) {
        const ch = str[i];
        if (ch < 0x80) {
            i += 1;
            if (ch < 32) continue;
            px += if (glyphCell(ch)) |g| g.advance else text_px;
        } else if ((ch & 0xc0) == 0x80) {
            i += 1; // 孤立续字节
        } else {
            const cp = decodeUtf8(str, &i);
            px += if (glyphCell(cp)) |g| g.advance else text_px;
        }
    }
    return @intFromFloat(@round(@as(f32, @floatFromInt(px)) / density));
}

/// microui 文本高度回调（dp）
pub fn textHeight(ctx: *microui.Context) i32 {
    _ = ctx;
    return text_dp;
}
