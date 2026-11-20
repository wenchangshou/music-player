# 听音室 · Android 播放器手册

听音室 Android 原生客户端（包名 `com.tidalgui.player`）是一款专为高解析无损音乐与 Hi-Fi 播放器（如山灵 Shanling M7 等 Android 10+ 发烧 DAP）深度定制的音频播放与管理系统。

---

## 一、 本地音乐引擎与发烧音频链路

### 1. 深度标签解析与存储授权
* **SAF 框架支持**：在 **音乐库 → 导入** 中授权音乐目录，应用递归扫描所有子目录，音乐文件保持原位不移动。
* **无损元数据提取**：深度解析 FLAC、WAV、DSD（DSF/DFF）、APE、MP3、AAC 等格式的完整元数据：
  * 歌曲名、艺人、专辑、专辑艺人、流派、发行年份；
  * 分碟号（Disc Number）、曲目序号（Track Number）、音频采样率、位深与比特率；
  * 内嵌高清专辑封面无损提取与缓存。
* **增量高效索引**：点击 **更新音乐库** 时，根据文件修改时间与大小进行增量扫描，毫秒级跳过未修改文件。

### 2. 发烧级播放架构
* **Media3 原生核心**：基于 AndroidX Media3 音频架构，支持原生无损解码与点对点源码直通输出，有效避开常规安卓重采样（SRC）劣化。
* **全功能回放控制**：无缝播放（Gapless）、系统通知栏与锁屏媒体控制器、音频焦点自动管理、单曲循环、队列循环与随机播放。

---

## 二、 智能歌词补全系统 (`LyricsFill`)

发烧友本地 FLAC 曲库庞大，但大量音轨缺乏对应歌词。听音室内置了高度自动化的智能歌词匹配引擎：

### 1. 核心工作原理
* **同级 `.lrc` 文件持久化**：歌词抓取后自动写入歌曲同级目录下同名 `.lrc` 文件（如 `Song.flac` 对应 `Song.lrc`），完全适配山灵等 DAP 系统的原生音乐库扫描器。
* **双源交叉检索**：同时检索 **TIDAL** 与 **网易云音乐** 在线歌词库。
* **智能校准算法**：
  * **繁简互转**：内置繁体与简体自动互转（Android API 29+ 原生支持），完美匹配港台/海外发烧录音；
  * **时长容差对齐**：对比歌曲物理时长（±3 秒容差），杜绝错配不同版本（如 Live 版、Remix 版或电台剪辑版）；
  * **同专辑权重优先**：优先采用相同专辑名下的匹配结果。

### 2. 界面操作方式
* **一键全库补全**：在 **音乐库** 页面点击顶部的 **「补全歌词」** 按钮，系统后台启动多线程并发比对，并在进度对话框中实时反馈进度，支持随时安全中止。
* **单曲挑选与更换**：在单曲菜单中点击 **「下载歌词 / 更换歌词」**，弹出候选匹配列表，点击任意候选可实时滚动预览歌词文本，满意后点击“保存”即可落盘。

---

## 三、 网络存储与私有云串流 (SMB / WebDAV)

为了满足发烧友挂载家庭 NAS 的需求，客户端扩展了底层网络存储协议支持：

* **SMB 协议 (`jcifs-ng`)**：支持连接局域网内的 SMB / Windows 共享文件夹，直接索引并流式播放 NAS 上的无损曲库。
* **WebDAV 协议**：支持挂载主流私有云盘或自建 WebDAV 服务（如 Alist、Synology、Nextcloud），支持明文 HTTP 与安全 HTTPS 访问。

---

## 四、 局域网 Web 原生遥控与文件治理

当播放器放置于音响架或口袋中时，无需频繁操作 DAP 小屏幕，可通过局域网浏览器完整接管。

### 1. 6 位 PIN 码快速安全配对
1. 确保手机/DAP 与控制设备（电脑或手机）处于同一局域网；
2. 在播放器打开 **设置 → 设备与连接 → 局域网遥控**；
3. 界面会显示当前 DAP 的局域网 IP 与端口（默认 `37273`），以及一组 6 位随机配对码（10 分钟有效）；
4. 电脑浏览器访问该网址输入配对码（或直接通过 `#<配对码>` 锚点一键配对），即可自动持久化认证密钥。

### 2. 现代化 Web 遥控功能
* **聚合全网搜索**：Web 端支持歌曲、专辑、歌单、艺人 4 分类，一键在 TIDAL、网易云、本地曲库间切换。
* **手机下载管理看板**：实时显示当前 DAP 下载网速（MB/s）、剩余时间，支持按专辑折叠查看、批量重试/清理，以及展开查看音频格式与存储路径。
* **本地缺歌词筛选**：Web 端本地曲库专设“缺歌词”筛选过滤器，支持在网页端直接触发 DAP 补全歌词。
* **沉浸式播放控制**：全功能播放/暂停、快进快退、循环模式切换、音量无级调节与全屏动态歌词同步显示。

### 3. 局域网文件管理
在设置中开启“允许管理音乐文件”后，配对浏览器可在“文件”页浏览已授权目录，支持远程上传无损音频（上限 200MB）、重命名以及删除文件。

---

## 五、 在线音源支持

### 1. TIDAL 无损与 Hi-Res
* **设备码授权登录**：支持在应用内生成授权直达链接或 8 位设备码，在任意浏览器完成 TIDAL 官方授权。
* **会话导出与导入**：支持直接从桌面版 `tiddl` 导出会话并在 Android 端导入（`scripts/playback_token.py`）。
* **最高音质直链**：支持获取并直连播放 TIDAL Max (24bit/192kHz Hi-Res FLAC) 与 Lossless 码流。

### 2. 网易云音乐
* **扫码登录**：在应用内生成登录二维码，使用网易云音乐 App 扫码登录。
* **云端生态**：支持每日推荐、个性化歌单、歌单广场、百万热评与红心收藏。
* **多音源混排**：网易云曲目可与本地音乐、TIDAL 自由混排进入同一播放队列。

---

## 六、 跨设备协同与借用登录 (`lend_login`)

* **远程整盘下载**：其他设备（如桌面端或 Web 遥控端）可直接将 TIDAL 专辑推送至当前 Android 播放器进行后台高解析下载。
* **借用登录解耦**：
  * 在 **设置 › 播放器账号** 中提供 **“允许我的其他设备借用本机的 TIDAL 登录”** 开关（默认开启）；
  * 开启时：未登录 TIDAL 的其他客户端可借用本机的高级会员权限浏览、搜索 TIDAL；
  * 关闭时：仅关闭借用浏览权限，**远程下载不受影响**，其他设备仍可正常推送下载任务至当前播放器。

---

## 七、 编译、安装与调试指南

### 1. 开发环境要求
* JDK 17（推荐 OpenJDK 17）
* Android SDK Platform 36
* Android Build Tools 35.0.0
* 已连接的 Android 调试设备（USB 或 Wi-Fi ADB）

### 2. 常用构建命令
```sh
cd android

# 1. 运行本地单元测试（包含 SMB / WebDAV 测试）
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk \
  ./gradlew testDebugUnitTest -q

# 2. 构建并安装 Debug APK 至山灵 M7（通过 -PtidalRelayOrigin 动态注入脱敏服务器地址）
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  ./gradlew testDebugUnitTest installDebug -PtidalRelayOrigin=https://relay.example.com -q

# 3. 启动应用
adb shell am start -n com.tidalgui.player/.MainActivity
```

### 3. Release 构建
```sh
./gradlew :app:assembleRelease :app:lintRelease
```
产物位于 `app/build/outputs/apk/release/`。

## 代码结构

界面全部用代码构建（没有布局 XML），按功能拆成多个文件，都在 `com.tidalgui.player` 包内：

| 文件 | 职责 |
| --- | --- |
| `MainActivity` | 生命周期、页面导航与 `render()`、搜索栏、页面状态字段 |
| `UiKit` | `MainActivity` 的父类：文字、按钮、面板、卡片等视图构建工具 |
| `HomeScreens` / `LocalLibraryScreens` / `CatalogueScreens` | 首页、本地音乐库、TIDAL/网易云目录与曲目列表 |
| `NowPlayingScreen` / `TrackExtras` | 正在播放、迷你播放条、播放队列；歌词、曲目菜单、歌曲电台 |
| `DownloadScreens` / `ImportExport` | 下载管理与离线；导入音频、文件夹与 M3U |
| `RemoteScreens` / `SettingsScreens` / `LoginFlow` | 多端遥控；设置与更新；TIDAL 登录 |
| `NeteaseScreens` / `WebDavScreens` / `SmbScreens` | 网易云歌单与登录；WebDAV、SMB 网络存储 |
| `AiScreens` | AI 设置、唱片手札、专辑 AI 鉴赏卡片 |

各功能类通过 `ui` 字段访问 `MainActivity` 的共享状态与其他功能类（例如 `ui.catalogueScreens.playList(...)`）。
新增页面：在 `Page` 加枚举值，在对应功能类写渲染方法，再在 `MainActivity.render()` 中挂上。

不依赖界面的逻辑放在独立类中，便于单元测试：
- `AutoDj`：智能无尽续播（候选打分、AI 挑选规则、无 AI 时的回退），由 `PlaybackService` 在队列快播完时调用；
- `AiProxy`：OpenAI 兼容接口代理，配置与密钥只保存在本机；
- `AiNotes`：唱片手札与专辑鉴赏的生成和本地缓存。
