# Kirisaki（切先）

**Cut the Override.**

用于 ColorOS 16 的 LSPosed 模块，阻止系统强制重新开启 Google 位置精确度（Google Location Accuracy，GLA）。

无网络权限、无遥测，不运行后台常驻服务。只做这一件事。

## 使用

需要 Android 12 或更高版本，以及正常工作的 LSPosed。

1. 安装 Kirisaki，在 LSPosed 中启用模块。
2. 作用域仅勾选 **`com.oplus.location`**，然后重启设备。
3. 在系统设置中关闭 Google 位置精确度。

模块随定位进程加载，不需要先打开 Kirisaki，也不用开启应用自启动。应用界面用于查看状态。

已在 OnePlus PLQ110 上确认目标写入可被拦截。其他机型或固件需要自行验证。

## 原理

ColorOS 的定位进程会通过 `ContentResolver.update()` 尝试写入：

```text
URI    content://com.google.settings/partner
name   network_location_opt_in
value  1
```

Kirisaki 在 `com.oplus.location` 主进程中 Hook 这个写入接口。只有 URI、设置键和值全部匹配，且 `ContentValues` 仅包含 `name` 和 `value` 时，才阻断写入。关闭操作写入的 `0` 和其他设置照常放行；模块不主动修改开关。

第一次拦截时，根据实际调用栈和方法签名定位入口并安装方法 Hook，不依赖固定的混淆类名，也不跳过整个方法。写入层的过滤始终保留：即使以后出现其他修改入口，符合条件的写入仍会被拦截，界面会显示提示。

界面通过受签名权限保护的请求获取状态，并校验 Binder 回传方的 UID。记录只保存在应用私有目录中。

## 构建

使用 Android Studio，或在项目目录运行：

```sh
./gradlew :app:assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

测试与静态检查：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

## 许可

本项目采用 [GNU GPL v3.0](LICENSE)（`GPL-3.0-only`）。
