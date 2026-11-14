// 占位库：只提供 Java 壳需要的 JNI 入口，编译到 app/src/main/jniLibs/arm64-v8a/，
// 保证模板 APK 里存在 lib/arm64-v8a/libmicrozig.so 这个条目，端上重打包时整包替换。
// 重新生成：powershell -ExecutionPolicy Bypass -File make_template.ps1

export fn Java_org_libsdl_app_ConsoleActivity_run(env: ?*anyopaque, cls: ?*anyopaque) callconv(.c) c_int {
    _ = env;
    _ = cls;
    return 0;
}
