<div align="center">

# [MeiloX](https://github.com/NEORUAA/MeiloX)

<img src="./screenshot/logo.png" width="120px" style="border-radius:12px"/>

### 一个基于 Mei 改造的高仿 Apple Music 风格网易云音乐 Android 客户端

![Android](https://img.shields.io/badge/Platform-Android-green?logo=android)
![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue)
![License](https://img.shields.io/badge/License-GPLv3-orange)
![Status](https://img.shields.io/badge/Development-In%20Progress-yellow)

</div>

---

## 项目简介

MeiloX 是一个基于 [Mei](https://github.com/ljyh223/Mei) 改造的第三方网易云音乐客户端，使用 **Jetpack Compose** 构建，尝试把 iOS 27 的层次感、液态玻璃和沉浸式播放器体验带到 Android 上。

项目重点放在三个方向：

- 用 iOS 风格的页面结构重新组织网易云音乐内容；
- 用 Liquid Glass、动态背景和专辑封面让播放过程更有沉浸感；
- 保留网易云音乐的搜索、歌单、歌词和个人资料等使用习惯。

> MeiloX 是非官方的个人开源项目，不隶属于网易云音乐或 Apple；项目本身不提供音乐资源，音乐内容及相关版权归其权利人所有。

## 功能概览

- 网易云音乐 Cookie 登录与账号信息展示；
- 搜索歌曲、专辑、歌手和歌单；
- 播放队列、随机播放、循环播放和定时停止；
- 播放历史、喜欢的音乐、歌单管理和本地收藏；
- 专辑详情、歌手主页、每日推荐及 FM 播放；
- 逐字歌词与多语言歌词展示；
- Apple Music 风格全屏播放器、迷你播放器和歌词浮窗；
- 浅色/深色主题、动态专辑背景和 Liquid Glass 交互效果。

功能仍在持续调整中，实际可用范围会随版本和网易云音乐接口状态变化。

## Build from source

Use JDK 21 and Android SDK 37.0. Until the first Glasmio release is published, build with the same source revision pinned in CI:

```sh
git clone https://github.com/NEORUAA/Glasmio.git ../Glasmio
git -C ../Glasmio checkout cf57af9c63b7e7a27ee9c12b1602dc3d59df6b54
./gradlew -Pglasmio.source=true :app:assembleDebug
```

## Android Auto 开发与测试

MeiloX 在 Android Auto 上是媒体类应用。车机界面由系统统一绘制，App 只通过 `MusicService`（Media3 `MediaLibraryService`）提供浏览树、搜索和播放控制，不需要也不能自定义车机布局。

### 环境

- 按上面的「Build from source」装好 JDK 21、Android SDK 37.0 和 Glasmio 源码。
- 在 Android Studio 的 SDK Manager → SDK Tools 里勾选 **Android Auto Desktop Head Unit Emulator**（DHU）。装好后在 `$ANDROID_HOME/extras/google/auto/` 下。
- 测试手机：Android 13 及以上（`minSdk = 33`），arm64 真机。App 只打 `arm64-v8a`，x86_64 模拟器装不上。
- 手机上装好 Android Auto（Android 10 以后系统自带，在「设置 → 已连接的设备 → Android Auto」里）。

### 打开 Android Auto 开发者模式

1. 在 Android Auto 设置页连续点 **版本** 10 次，确认开启开发者模式。
2. 右上角菜单 → **开发者设置**，打开 **未知来源**。不开的话，不是从 Play 商店装的调试包不会出现在车机上。
3. 右上角菜单 → **启动车机服务器**（Start head unit server）。

### 用 DHU 测试

```sh
./gradlew -Pglasmio.source=true :app:installDebug
adb forward tcp:5277 tcp:5277
cd "$ANDROID_HOME/extras/google/auto"
./desktop-head-unit
```

不想自己编译的话，可以直接装 CI 打好的调试包：在 [Actions](https://github.com/PandaQuQ/MeiloX/actions/workflows/ci.yml) 里打开**要测的那张 PR 的分支**（比如浏览树在 `feature/aa-browse-tree`，不是 `feature/android-auto`）最新一次绿色的 **CI**，下载构件 `meilox-debug-<编号>`，解压后运行 `adb install -r <apk文件>`。构建号要和那张 PR 的最新绿色 CI 对上。`feature/android-auto` 分支上的构件（比如 `meilox-debug-12`）没有合进车机代码，包名也还是正式版的 `com.neoruaa.meilox`，装的时候会报和正式版冲突，装上了车机上也看不到。只有 `6cf9434` 及以后的构建才带 `.debug` 后缀，安装页显示的包名不是 `com.neoruaa.meilox.debug` 就是下错了。调试包的包名是 `com.neoruaa.meilox.debug`，桌面和车机上显示为「MeiloX Dev」，可以和正式版同时装，不用卸载正式版。调试包要单独登录一次，它和正式版的登录状态、下载、缓存是分开的。

截图给 UI 验收：在运行 DHU 的那个终端里输入 `day` 或 `night` 切换白天和夜间模式，再输入 `screenshot day.png` 或 `screenshot night.png` 存图。首页、媒体库里的歌单网格、一个歌单里的歌曲列表、正在播放页，这 4 个界面白天和夜间各截一张。

DHU 窗口打开后，在车机的应用列表里找到「MeiloX Dev」。每次改完代码重新 `installDebug`，然后在 DHU 里重新打开「MeiloX Dev」；如果车机上还是旧内容，就关掉 DHU 重开。

每次提交前至少走一遍：

- 首页分类能打开，每层都有封面和标题，驾驶状态下列表最多显示 6 行。
- 搜索（语音或键盘）能搜到歌曲，点结果直接开始播放。
- 播放、暂停、上一首、下一首、进度条都和手机端同步。
- 手机端没登录时，车机上给出提示，而不是一片空白或者崩溃。

车机上找不到「MeiloX Dev」时按顺序查：

1. 手机桌面上有「MeiloX Dev」吗？没有，或者安装时报和正式版冲突，就是装错了包，要装 PR 分支上的调试包。
2. Android Auto 开发者设置里的「未知来源」开了吗？
3. Android Auto 设置 →「自定义启动器」里，MeiloX Dev 勾上了吗？
4. 还是没有，就把安装来源标成 Play 商店重装：有电脑用 `adb install -r -i com.android.vending <apk文件>`；没电脑用 InstallerX Revived（授权选 Shizuku 或 Root），把安装来源设成 `com.android.vending` 再装。新版 Android Auto 对侧载应用限制更严，只开「未知来源」不一定够。
5. 断开重连，或者强行停止 Android Auto 再进，DHU 的话就重启它。

App 日志用 `adb logcat --pid=$(adb shell pidof com.neoruaa.meilox.debug)` 看，DHU 自己的输出看运行它的那个终端。

## 歌词共享

已接入 [Lyricon Provider](https://tomakino.github.io/lyricon/zh-cn/developer/provider/)、[SuperLyricApi](https://github.com/HChenX/SuperLyricApi) 和 [ColorOS Live Lyrics Bridge](https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Bridge) 接口，用于共享歌词、逐字时间、翻译及播放状态。

「设置 → 歌词设置 → 歌词共享」中的「启用歌词共享」默认开启。

## 开源致谢

MeiloX 的界面、歌词和底层能力受以下项目启发或直接使用其开源组件，感谢所有贡献者：

- [Mei](https://github.com/ljyh223/Mei)：上游 Android 项目与基础能力；
- [MeloX](https://github.com/youshen2/MeloX)：iOS 版参考实现与产品方向；
- [Glasmio](https://github.com/NEORUAA/Glasmio)：共享的 iOS 风格组件与液态玻璃界面库；
- [amll-ttml-db](https://github.com/Steve-xmh/amll-ttml-db)：高质量逐字歌词数据；
- [accompanist-lyrics-ui](https://github.com/6xingyv/accompanist-lyrics-ui)：歌词模型与渲染组件；
- [Lyricon](https://github.com/tomakino/lyricon)：词幕 Provider SDK；
- [SuperLyricApi](https://github.com/HChenX/SuperLyricApi)：歌词共享 SDK；
- [ColorOS Live Lyrics Bridge](https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Bridge)：ColorOS 锁屏歌词接口；
- [AndroidLiquidGlass / Backdrop](https://github.com/Kyant0/AndroidLiquidGlass)：Compose Multiplatform 流体玻璃效果。

## 许可证与第三方声明

MeiloX 主体代码以 [GNU General Public License v3](LICENSE) 发布。MeiloX 是 Mei 的衍生项目，相关上游代码和参考实现仍受其原始版权与许可证约束；第三方库、模型、脚本、字体和视觉素材不因 MeiloX 使用 GPLv3 就自动转为 GPLv3。

重点声明如下：

- [Mei](https://github.com/ljyh223/Mei) 的上游代码遵循 Apache License 2.0；
- [MeloX](https://github.com/youshen2/MeloX) 的相关参考代码遵循其项目的 GPLv3 条款；
- [AndroidLiquidGlass / Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) 相关依赖遵循 Apache License 2.0；
- [amll-ttml-db](https://github.com/Steve-xmh/amll-ttml-db) 与 [accompanist-lyrics-ui](https://github.com/6xingyv/accompanist-lyrics-ui) 遵循各自仓库公布的许可证和署名要求；
- BeatNet 模型、音频指纹运行时、ONNX Runtime、PV Tool 衍生内容及其它随项目分发的组件，遵循其各自的许可证、版权声明和再分发限制。

完整的来源、版权归属、许可证链接和特殊再分发要求请阅读 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES)。其中 `res/font/sf_pro.ttf` 仅作为个人项目资源使用；请勿从构建产物中单独提取或再分发，公开发布构建版本前请重新核对 Apple 的字体许可条款。

## 软件界面预览

以下预览来自 [`screenshot/2026-08-22`](./screenshot/2026-08-22/)，同时展示浅色、深色和播放器界面。

### 浅色界面

<table>
  <tr>
    <td><img src="./screenshot/2026-08-22/light_Screenshot_2026-08-22-09-47-44-801_com.neoruaa.meilox.jpg" width="200" alt="Light mode 01"></td>
    <td><img src="./screenshot/2026-08-22/light_Screenshot_2026-08-22-10-31-11-383_com.neoruaa.meilox.jpg" width="200" alt="Light mode 07"></td>
    <td><img src="./screenshot/2026-08-22/light_Screenshot_2026-08-22-10-06-44-461_com.neoruaa.meilox.jpg" width="200" alt="Light mode 04"></td>
    <td><img src="./screenshot/2026-08-22/light_Screenshot_2026-08-22-10-05-43-651_com.neoruaa.meilox.jpg" width="200" alt="Light mode 02"></td>
  </tr>
  <tr>
    <td><img src="./screenshot/2026-08-22/light_Screenshot_2026-08-22-10-13-55-779_com.neoruaa.meilox.jpg" width="200" alt="Light mode 05"></td>
    <td><img src="./screenshot/2026-08-22/light_Screenshot_2026-08-22-10-14-22-986_com.neoruaa.meilox.jpg" width="200" alt="Light mode 06"></td>
    <td><img src="./screenshot/2026-08-22/light_Screenshot_2026-08-22-10-06-04-617_com.neoruaa.meilox.jpg" width="200" alt="Light mode 03"></td>
    <td><img src="./screenshot/2026-08-22/light_Screenshot_2026-08-22-10-33-30-925_com.neoruaa.meilox.jpg" width="200" alt="Light mode 08"></td>
  </tr>
</table>

### 深色界面

<table>
  <tr>
    <td><img src="./screenshot/2026-08-22/dark_Screenshot_2026-08-22-10-17-19-250_com.neoruaa.meilox.jpg" width="200" alt="Dark mode 02"></td>
    <td><img src="./screenshot/2026-08-22/dark_Screenshot_2026-08-22-10-28-56-966_com.neoruaa.meilox.jpg" width="200" alt="Dark mode 07"></td>
    <td><img src="./screenshot/2026-08-22/dark_Screenshot_2026-08-22-10-19-07-140_com.neoruaa.meilox.jpg" width="200" alt="Dark mode 06"></td>
    <td><img src="./screenshot/2026-08-22/dark_Screenshot_2026-08-22-10-17-04-531_com.neoruaa.meilox.jpg" width="200" alt="Dark mode 01"></td>
  </tr>
  <tr>
    <td><img src="./screenshot/2026-08-22/dark_Screenshot_2026-08-22-10-17-59-681_com.neoruaa.meilox.jpg" width="200" alt="Dark mode 04"></td>
    <td><img src="./screenshot/2026-08-22/dark_Screenshot_2026-08-22-10-34-42-884_com.neoruaa.meilox.jpg" width="200" alt="Dark mode 08"></td>
    <td><img src="./screenshot/2026-08-22/dark_Screenshot_2026-08-22-10-17-30-088_com.neoruaa.meilox.jpg" width="200" alt="Dark mode 03"></td>
    <td><img src="./screenshot/2026-08-22/dark_Screenshot_2026-08-22-10-18-09-075_com.neoruaa.meilox.jpg" width="200" alt="Dark mode 05"></td>
  </tr>
</table>

### 播放器与歌词

<table>
  <tr>
    <td><img src="./screenshot/2026-08-22/player_Screenshot_2026-08-22-10-12-05-012_com.neoruaa.meilox.jpg" width="200" alt="Player 02"></td>
    <td><img src="./screenshot/2026-08-22/player_Screenshot_2026-08-22-10-11-20-597_com.neoruaa.meilox.jpg" width="200" alt="Player 01"></td>
    <td><img src="./screenshot/2026-08-22/player_Screenshot_2026-08-22-10-16-34-746_com.neoruaa.meilox.jpg" width="200" alt="Player 03"></td>
    <td><img src="./screenshot/2026-08-22/player_Screenshot_2026-08-22-10-16-52-289_com.neoruaa.meilox.jpg" width="200" alt="Player 04"></td>
    <td><img src="./screenshot/2026-08-22/player_Screenshot_2026-08-22-10-21-10-403_com.neoruaa.meilox.jpg" width="200" alt="Player 05"></td>
  </tr>
</table>

## 免责声明

本项目仅用于学习、研究和个人使用。使用本项目访问网易云音乐服务时，请遵守当地法律法规、网易云音乐服务条款及相关版权要求。项目维护者不对第三方服务的可用性、账号状态或音乐内容承担责任。

如果你发现侵权内容、许可证遗漏或不准确的第三方声明，欢迎通过 [Issues](https://github.com/NEORUAA/MeiloX/issues) 联系维护者。

<div align="center">

Made with Jetpack Compose · MeiloX

</div>
