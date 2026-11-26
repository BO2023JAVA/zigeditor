<!-- tab: 配置 -->
# 项目配置 config.json

项目根目录的 `config.json` 决定编译与打包行为。旧项目里叫 `microui.json`，两个名字都认。

## 字段

- `package`：安卓包名，如 `com.zigeditor.demo`
- `appName`：安装后显示的应用名
- `versionName`：应用版本号，如 `1.0`
- `kind`：项目类型，`gui` / `console` / `native`
- `zigVersion`：编译用的 Zig 包 id，如 `zig-0.17.1-x64`
- `icon`：图标文件名，默认 `icon.png`
- `modules`：自定义 Zig 模块，可选

## modules

把额外的 Zig 模块注册成 `app` 的依赖，业务代码里可以直接 `@import`：

```json
{
  "modules": [
    { "name": "mylib", "path": "lib/mylib.zig", "deps": ["microui"] }
  ]
}
```

- `name`：必须是合法标识符
- `path`：相对项目根的源文件
- `deps`：这个模块依赖的其他模块名，可省略

## 缺省行为

- 没有 `kind` 的老项目按图形界面处理
- 修改 `package` 会变成另一个应用，旧版本不会被覆盖安装
