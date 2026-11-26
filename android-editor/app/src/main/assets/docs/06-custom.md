<!-- tab: 自定义 -->
# 自定义控件与框架源码

## 自定义控件（不用改框架）

microui 是立即模式 UI，控件本身就是用公开的绘制与命中原语拼出来的。常用原语：

- 绘制：`drawRect`、`drawBox`、`drawText`、`drawIcon`、`drawControlFrame`、`drawControlText`
- 命中：`mouseOver(rect)`、`mouse_pos`、`mouse_down`、`mouse_pressed`
- 布局与裁剪：`layoutNext`、`layoutRow`、`pushClipRect`、`setClip`

一个自己的开关控件：

```zig
fn mySwitch(ctx: *microui.Context, on: *bool) bool {
    const r = ctx.layoutNext();
    ctx.drawControlFrame(ctx.getId("sw"), r, .button, 0);
    const knob = microui.Rect.init(
        r.x + if (on.*) r.w / 2 else 0, r.y, r.w / 2, r.h);
    ctx.drawBox(knob, ctx.style.colors[@intFromEnum(microui.ColorId.button_hover)]);
    if (ctx.mouseOver(r) and (ctx.mouse_pressed & microui.mouse_left) != 0) {
        on.* = !on.*;
        return true;
    }
    return false;
}
```

这类改动只写在 `app.zig`，不需要修改框架源码。

## 什么时候需要框架源码

- 新增绘制能力：图片、线段、渐变、圆角、阴影
- 改字体渲染、控件动画、脏矩形刷新
- 改事件循环、接入新的原生能力

这种情况下需要框架源码，改动位置是 `core/renderer_android.zig`、`core/microui_app.zig` 或 `ui/microui.zig`。

## 修改框架

框架源码就在项目的 `core/`、`ui/` 里，改动会直接参与编译。注意：

- 保留 `core/entry.zig` 与 `SDL_main`，否则应用无法启动
- 保持 `App.init`、`onFrame` 的签名，业务代码就不用改
- 修改渲染后端时，命令队列的消费端和 `drawRect` 等提交端要一起改
- 字体是 `core/font.ttf`，替换文件即可
