# 酒馆 (Tavern) — 手机上的无 UI 网页浏览器

项目地址：[https://github.com/Qrkrk/tavern-android-webview]

专为手机端 [SillyTavern](https://github.com/SillyTavern/SillyTavern) 用户打造的极简 WebView 浏览器。

SillyTavern 通过 Termux 部署在手机上后，需要用浏览器打开。但手机浏览器都有地址栏、标签页、菜单等 UI 遮挡，本来就小的屏幕被占掉一大块，留给文字的空间更少了。

这个 App 就是一个**纯粹的画布**——无地址栏、无标题栏、无任何按钮，100% 的屏幕都给聊天内容。同时因为是原生 WebView，比系统浏览器更省电。

- **📱 零 UI 遮挡**：地址栏、标签栏、菜单统统没有，所有像素属于内容
- **⚡ 极轻量**：APK 不足 5 MB，原生 WebView 比浏览器省电
- **🎯 专为酒馆优化**：角色卡导出、文件上传、沉浸式全屏
- **🔒 零隐私顾虑**：仅网络权限，不碰存储、不碰相册

## 技术栈

| 项 | 值 |
|---|-----|
| 语言 | Kotlin |
| 布局 | XML |
| minSdk | 24 (Android 7.0) |
| targetSdk | 34 (Android 14) |
| AGP | 8.2.0 |
| Gradle | 8.5 |
| Kotlin | 1.9.22 |

## 项目结构

```
tavern-android-webview/
├── build.gradle.kts                  # 项目级 Gradle
├── settings.gradle.kts               # 模块设置
├── gradle.properties                 # Gradle 属性
├── gradle/wrapper/                   # Gradle Wrapper
├── app/
│   ├── build.gradle.kts              # 模块级 Gradle (依赖、SDK 版本)
│   ├── proguard-rules.pro            # ProGuard 规则
│   └── src/main/
│       ├── AndroidManifest.xml       # 权限、竖屏、明文流量
│       ├── java/com/localhost/tavern/
│       │   └── MainActivity.kt       # 全部核心逻辑
│       └── res/
│           ├── layout/
│           │   └── activity_main.xml # 全屏 WebView 布局
│           ├── values/               # themes.xml, strings.xml, colors.xml
│           └── mipmap-anydpi-v26/    # 自适应图标 (API 26+)
│               ├── ic_launcher.xml
│               └── ic_launcher_round.xml
```

## 功能

- **双模式**：首次启动弹出对话框，可设置目标 URL 并选择 🌐 全屏模式（沉浸式隐藏系统栏）或 📱 普通模式
- **URL 持久化**：目标 URL 通过 SharedPreferences 保存，后续启动直接加载，无需重复设置
- **长按重置**：在 WebView 区域长按 5 秒可重新设置 URL 和切换模式
- **Blob 下载**：通过 JavaScript 桥拦截 `URL.createObjectURL` 实现角色卡等 blob 内容导出
- **HTTP 下载**：系统 DownloadManager，保存至 `内部存储/Download/`，无需存储权限
- **文件上传**：通过 `ActivityResultLauncher` 启动系统文件选择器，无需存储权限
- **权限极简**：仅 `INTERNET` + `ACCESS_NETWORK_STATE`，无任何存储权限

## 编译运行

1. 用 **Android Studio** 打开项目根目录
2. 等待 Gradle Sync 完成
3. 连接设备或启动模拟器（API 24+）
4. 点击 **Run** 或 `Shift+F10`

## 本地开发

应用默认加载 `http://127.0.0.1:8000`。开发时你需要在该地址运行本地 Web 服务：

```bash
# Python
python -m http.server 8000

# Node.js
npx serve -p 8000
```

模拟器默认支持 `localhost` 访问宿主机端口，真机需 USB 端口转发：

```bash
adb reverse tcp:8000 tcp:8000
```
