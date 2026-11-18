# 多端后台

浏览器从后台选择在线设备，控制该设备的播放、播放进度和队列。Android 和 Linux 桌面端可以独立登录 TIDAL，也可以在单用户部署中同步 PC 的登录状态。后台不接收音频。

## 播放器账号

管理员在服务器上创建账号。系统没有公开注册入口。账号库保存在 systemd 的 `StateDirectory`，每次登录创建独立的设备会话。Android 在 **设置 → 设备与连接 → 播放器账号** 登录，Linux 桌面版在 **设置 → 播放器账号** 登录。

在 PC 登录 TIDAL 后，已登录相同播放器账号的 Android 会自动同步 TIDAL 会话。后台在内存中保留有效的访问令牌、到期时间、用户 ID 和地区。PC 负责续期，在线时会重新发布会话。Android 已有独立登录时继续使用原会话。

打开部署时配置的 HTTPS 地址并登录播放器账号，可查看和遥控自己的设备，也可移除设备。被移除设备需要重新登录。已有固定密钥的旧客户端仍能在旧的管理视图使用，其设备和会话与播放器账号隔离。

## 部署

`server.py` 用 Python 标准库，监听 `127.0.0.1:8765`。通过 Caddy 提供 HTTPS，参考随仓库的 `tidal-relay.caddy`，并为 Caddy 进程设置 `TIDAL_RELAY_HOST`（示例值 `relay.example.com`）。将服务和共用遥控网页部署到 `/opt/tidal-relay`，部署 systemd 配置 `tidal-relay.service`，并写入 root 专用的 `/etc/tidal-relay/config.json`。设备端需要 HTTPS，服务的回环地址不可直接作为设备配置。

后台配置包含 `origin`、`port`、`releases`、`admin_token` 和 `devices`。设备以标识为键，每项包含 `name`、`platform`（`android` 或 `desktop`）和独立的 `token`。管理和设备令牌均可用 `secrets.token_urlsafe(32)` 生成。改动配置后重启服务生效。

## 启用 Android 网易云默认接口

Android 配置中继地址后使用该中继的 `/netease` 路径。后台部署时，把 `netease_gateway.py` 和新版 `server.py` 放在同一目录。在仅由管理员写入的 `/etc/tidal-relay/config.json` 中添加 `netease_api_url`，填入已运行的网易云兼容 API 基础地址，例如 `http://127.0.0.1:3000`。后台支持本机回环 HTTP 或 HTTPS 上游。目录接口使用访客会话。自带上游支持扫码登录，授权后的会话仅用于该设备请求播放地址、个性推荐和自己的歌单。

示例部署在 `/opt/tidal-netease` 运行 `netease_upstream.cjs`，由 `tidal-netease.service` 管理。依赖固定为 `@neteasecloudmusicapienhanced/api@4.41.1` 和 `node-linux-x64@22.23.5`，锁文件位于部署目录。上游仅监听 `127.0.0.1:3000`。

重启 `tidal-relay` 后，通过 `/netease/cloudsearch?keywords=test&type=1&limit=1` 验证。现有 Caddy 代理已覆盖该路径。未配置上游时，路由返回 HTTP 503。

## 发布 Android 更新

提高 `versionCode`，并用与当前安装包匹配的签名密钥构建。发布脚本验证包名、签名和版本，并生成 SHA-256 清单。若在本地生成产物，先上传 APK，再原子替换服务器发布目录内的 `latest.json`。

```sh
python3 backend/publish_android.py android/app/build/outputs/apk/debug/app-debug.apk ./release-staging \
  --build-tools "$ANDROID_HOME/build-tools/35.0.0" --notes "更新说明"
```

Android 在 **设置 → 应用更新 → 检查更新** 获取发布。下载后核对 APK 的大小、哈希、版本、包名和当前安装签名。安装由系统界面确认。

更新客户端下载使用公开的只读 HTTPS 路径 `/updates/android/latest.json` 和 `/updates/android/apk`。多端遥控和设备状态仍通过原有配对接口访问。

## TIDAL 设备授权

Android 的 **音源 → 设备链接登录** 可以向后台申请设备码。管理员一次性在服务器的 root 专用配置 `/etc/tidal-relay/config.json` 增加 `tidal_device`，对象包含获准的 `client_id`；如果该客户端要求密钥，再配置 `client_secret`，然后重启 `tidal-relay`。请直接通过服务器的私有配置渠道填写。

后台将授权、轮询和续期请求转发到 TIDAL。应用凭据和上游设备码留在服务端。用户在 TIDAL 网页完成授权，客户端接收并保存自己的访问和续期令牌。后台在续期请求中转发令牌，会话令牌保存在客户端。

### Hi-Res 播放令牌

TIDAL 只向特定客户端配置提供 Hi-Res 立体声流。和 tiddl 一样，后台可以用设备登录得到的续期令牌换取该配置的访问令牌，Android 登录后自动调用 `/auth/tidal/hires`，无需 PC 同步。在 `/etc/tidal-relay/config.json` 增加 `tidal_hires` 对象，包含 `client_id` 和 `client_secret`，然后重启 `tidal-relay`。tiddl 使用的设备客户端和 Hi-Res 客户端可在本机已安装的 tiddl 中查到：

```sh
python3 -c 'import json;from tiddl.core.auth.client import CLIENT_ID as i,CLIENT_SECRET as s;from tiddl.core.auth.profiles import CLIENT_PROFILES as P;h=P["hires"];print(json.dumps({"tidal_device":{"client_id":i,"client_secret":s},"tidal_hires":{"client_id":h.client_id,"client_secret":h.client_secret}},indent=2))'
```

未配置 `tidal_hires` 时，接口返回 HTTP 503，Android 继续使用主会话播放无损。

只使用具有设备授权及本播放器所用曲目接口权限的 TIDAL 客户端。未配置时，登录接口会说明后台尚未启用，Android 仍可使用已有会话、本地音乐或手动配置的客户端。

## TIDAL 浏览器登录

在 TIDAL 应用配置中注册此回调地址：

```text
https://relay.example.com/auth/tidal/browser/callback
```

在 `/etc/tidal-relay/config.json` 添加 `tidal_browser` 对象，填入获准的 `client_id` 和该客户端被允许的 `scope`（用空格分隔），然后重启 `tidal-relay`。此流程使用 TIDAL 的 Authorization Code + PKCE。用户只在 TIDAL 网页登录，回调完成后返回 Android。

浏览器回调由一次性的 OAuth state 关联，Android 通过另一份独立的一次性票据领取登录结果。浏览器页面不接收会话令牌。用户授权结束后，后台会检查该应用是否能访问当前播放器用来读取账号的 TIDAL v1 会话接口。浏览器授权可用与否不等同于完整曲目播放权限，需要用具备权限的应用实测。
