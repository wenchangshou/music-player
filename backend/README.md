# 听音室 · 多端中继后端 (Relay Server)

听音室多端中继服务（位于 `backend/`）是连接 Web 遥控端、Android 播放器与 Linux 桌面端的安全异步通信桥梁。它负责状态同步、长轮询信令转发与远程下载任务分发，**不转发任何音频流**，具有极低的带宽与服务器资源占用。

---

## 一、 系统架构与核心机制

```
  [ Web 遥控前端 / 桌面端 ]
             |
             | HTTPS API / Web 静态资源
             v
     [ Caddy 反向代理 ]  --> (自动管理 Let's Encrypt 证书)
             |
             | HTTP (127.0.0.1:8765)
             v
   [ Python Relay Server (server.py) ]
             |
             | 设备长轮询通道 (/device/poll & /api/receipt)
             v
   [ 在线 Android 播放器 / DAP ]
```

### 1. 长轮询高效通道
* **指令长轮询 (`/device/poll`)**：客户端携带 `"wait": true` 发起连接，服务端保持挂起最长 2.5 秒；一旦有控制指令即刻返回，大幅降低高频轮询的能耗与网络开销。
* **回执长轮询 (`/api/receipt`)**：Web 控制端下发操作后，挂起最长 5 秒等待 DAP 执行回执，保证 UI 状态毫秒级同步。
* **单并发安全隔离**：每台在线设备占用独立通道，服务端并发上限设为 64，杜绝跨账号串流与指令混乱。

### 2. 借用登录模型 (`lend_login`)
* **远程下载常驻**：设备状态中始终广播 `features: ["downloads"]`，任何关联客户端均可将曲目或整张专辑直接推送至 DAP 进行后台下载。
* **TIDAL 登录借用**：在被遥控端（如 Android DAP）设置中由 `lend_login` 独立控制（默认开启）。开启时广播 `features: ["tidal", "downloads"]`；未登录 TIDAL 的客户端可透明借用该会话进行曲目浏览与检索。关闭后仅拦截 `tidal` 读取命令，远程下载不受影响。

### 3. 本地封面缓存传输
当 Android DAP 播放本地文件时，上报带有哈希特征的 `artwork_key`；服务端首次遇见时按需索取不超过 96 KB 的 JPEG 缩略图，通过不可猜测的 `/artwork/<token>.jpg` 临时链接提供给 Web 遥控端展示。

---

## 二、 核心控制信令规范

| 指令 (`op`) | 动作 (`action` / 参数) | 功能说明 |
| --- | --- | --- |
| `tidal` | `{method, path, form?}` | 借用 DAP 会话请求 TIDAL 官方 API，超 64KB 自动经 gzip 压缩传输 |
| `download` | `{album}` 或 `{ids, quality}` | 远程推送单曲或整张专辑至 DAP 进行高解析下载（支持 `BEST`/`HI_RES_LOSSLESS`） |
| `downloads` | `list` | 获取设备当前所有下载任务（支持分页 `limit`） |
| `downloads` | `info` | 查询已下载文件的物理路径、编码格式、采样率、位深、时长与大小 |
| `downloads` | `cancel` / `retry` / `remove` | 单个或批量（`ids` 最多 500 个）任务取消、重试或清理 |
| `downloads` | `parallel` | 动态调整 DAP 同时下载的最大并发数 (`count`) |
| `downloads` | `marks` | 返回设备已下载与本地已有曲目的哈希集合，用于遥控端曲目去重标记 |
| `library` | `local` / `groups` | 浏览 DAP 本地音乐目录、专辑、艺人与分类歌单 |
| `library` | `lyrics-status` / `lyrics-fill` | 查询歌词补全进度或启动本地全库后台批量补全 |
| `library` | `lyrics-candidates` / `lyrics-preview` | 查询某单曲的跨源匹配候选列表或预览歌词文本 |
| `library` | `lyrics-save` | 将选定歌词保存为与歌曲同级的同名 `.lrc` 文件 |

---

## 三、 服务器部署指引

后端采用 Python 3 标准库构建，无需安装复杂外部三方框架。

### 1. 部署准备
通常将服务部署在 `/opt/tidal-relay` 目录下：
```sh
# 自动化部署脚本（位于仓库 scripts 目录下）
bash scripts/deploy_relay.sh
```

### 2. 配置文件 (`/etc/tidal-relay/config.json`)
请直接由服务器 root 用户配置（严禁提交进代码仓库）：
```json
{
  "origin": "https://relay.example.com",
  "port": 8765,
  "admin_token": "YOUR_ADMIN_SECRET_TOKEN",
  "devices": {
    "m7_player": {
      "name": "Shanling M7",
      "platform": "android",
      "token": "DEVICE_SPECIFIC_TOKEN"
    }
  },
  "netease_api_url": "http://127.0.0.1:3000",
  "ai": {
    "endpoint": "https://api.deepseek.com",
    "model": "deepseek-chat",
    "api_key": "YOUR_AI_API_KEY"
  }
}
```

### 3. Caddyfile 配置示例
```caddy
relay.example.com {
    encode gzip zstd
    reverse_proxy 127.0.0.1:8765
}
```

### 4. Systemd 服务配置 (`tidal-relay.service`)
```ini
[Unit]
Description=Tidal GUI Multi-Device Relay Service
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/tidal-relay
ExecStart=/usr/bin/python3 /opt/tidal-relay/server.py
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

---

## 四、 接入网易云兼容接口 (可选)

若需在 Android 端启用网易云在线搜索与扫码登录：
1. 本地启动兼容网关服务（如基于 NeteaseCloudMusicApi 部署于 `127.0.0.1:3000`）；
2. 在 `/etc/tidal-relay/config.json` 中配置 `"netease_api_url": "http://127.0.0.1:3000"`；
3. 将 `backend/netease_gateway.py` 与 `server.py` 置于同级目录；
4. 重启 `tidal-relay` 服务后，Android 端配置本中继即可直接使用网易云音乐功能。

## 五、 AI 音乐助理 (可选)

多端遥控网页的 AI 功能（智能续播、唱片手札、AI 寻歌等）通过中继的 `/api/ai` 调用大模型：
1. 在 `/etc/tidal-relay/config.json` 中配置 `ai` 段（任意 OpenAI 兼容服务，`endpoint` 必须是 HTTPS），或使用环境变量 `TIDAL_AI_ENDPOINT` / `TIDAL_AI_MODEL` / `TIDAL_AI_KEY`；
2. 接口地址、模型和密钥只保存在服务器上：浏览器只发送对话内容，`/api/ai/config` 只返回是否已配置和模型名；
3. `/api/ai` 需要登录播放器账号或后台密钥，每个账号每小时最多 120 次、全服同时最多 4 个请求；
4. 未配置时网页的 AI 增强会静默隐藏，不影响播放与浏览。

局域网直连手机时不经过中继：AI 设置保存在手机的私有存储里，在网页的「AI 音乐助理设置」中填写即可。

