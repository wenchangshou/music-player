# Tidal Web Remote 结构化模块架构文档

本项目前端已从 6,240+ 行超大单体 `app.js` 彻底重构成高内聚、低耦合的模块化设计。

## 目录结构与模块说明 (`src/`)

```
src/
├── 01-state.js              # 核心基座：全局状态、会话凭证(Local/SessionStorage)、DOM选择器、版本计数
├── 02-toast-modal.js        # 交互反馈：全局 Toast 浮动通知、自定义模态对话框 (Alert / Confirm / Prompt)
├── 03-net-api.js            # 网络通信：RPC 调用封装 (call)、带鉴权请求与重试机制
├── 04-router.js             # 路由系统：Hash 路由状态机、浏览器前进后退监听、视图持久化切换 (switchView)
├── 05-stage-player-modal.js # 舞台模式：封面舞台 vs 同步歌词舞台切换、全屏播放器弹窗控制
├── 06-utils.js              # 工具函数：时间/码率/文件大小格式化、HTML转义、文本相似度 (Levenshtein)
├── 07-lyrics.js             # 歌词引擎：LRC 解析器、双语同步、高帧率逐行平滑滚动渲染引擎
├── 08-playback-controls.js  # 播放控制：播放状态同步、进度条拖拽与时间平滑动画、音量滑动防抖、控制栏事件
├── 09-queue.js              # 播放队列：播放队列列表渲染、拖拽重排、曲目移除、侧边栏 Tab 切换
├── 10-library.js            # 本地曲库：音轨/唱片/艺人/流派/文件夹树状浏览、详情栈下钻、播放列表管理
├── 11-search.js             # 全局寻歌：TIDAL / 网易云 / 本地并行多源搜索、结果列表呈现、快速试听与入队
├── 12-lyrics-manage.js      # 歌词管理：本地曲库缺失歌词自动补全扫描、手动搜索在线歌词与内嵌绑定
├── 13-downloads.js          # 下载引擎：离线下载任务列表监控、任务状态机（暂停/恢复/重试/清理）、音质偏好
├── 14-files.js              # 文件管理：机身存储与 SD 卡目录浏览、文件管理、多目录局域网高速并发直传
├── 15-polling.js            # 链路同步：局域网配对流程 (pair)、心跳轮询状态轮询 (pollLoop)、断线自动重连
├── 16-ai-core.js            # AI 大脑基座：火山方舟/通义千问配置面板、callAiChat 流式降时延调度、JSON提取解析
├── 17-ai-curator.js         # 特性 2：深度唱片手札与发烧乐评（按需点击生成、本地持久化缓存、毫秒秒开）
├── 18-ai-mood-radio.js      # 特性 1：AI 发烧心境电台（8款心境胶囊、本地曲库 FLAC 0.1s 深度唤醒秒播）
├── 19-ai-matcher.js         # 匹配引擎：高置信度声学匹配、艺人门禁过滤（防张冠李戴）、跨源并行打分
├── 20-ai-cards.js           # 卡片组件：发烧唱片卡片安全渲染（防空指针）、多选 Checkbox、批量操作栏
├── 21-ai-recommend.js       # 特性 3：本地曲库流派智能归类 & 猜你喜欢（听音品味深度画像、个性化推荐）
├── 23-ai-library-search.js   # 曲库自然语言搜索：专辑索引 + AI 意图理解，结果优先播放机内文件
└── 99-main.js               # 启动入口：DOMContentLoaded 挂载、认证初始化、启动平滑时钟循环
```

## 构建与运行

在 `android/app/src/main/assets/remote/` 目录下：

- **单次构建**：
  ```bash
  node build.js
  # 或
  npm run build
  ```
  执行耗时 < 100ms，自动将 `src/` 中 22 个模块拼装并校验语法，输出至标准 `app.js`。

- **监听热构建**：
  ```bash
  node build.js --watch
  # 或
  npm run watch
  ```

- **部署到云端 Relay**：
  运行 `scripts/deploy_relay.sh` 会在上传前自动触发 `node build.js`。
