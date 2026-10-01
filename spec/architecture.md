# 摩柿小说 · 手环9Pro QuickApp 总架构规格（草稿 v0.1）

> 状态：骨架已冻结；[TBD] 项待 vela-capability.md / voidterminal-api.md 落地后填充。
> 本文件是全部实现子任务的唯一基准：项目结构、页面流、桥接协议、存储键、阅读器设计必须与之一致。

## 1. 顶层结论（已确认）

- 目标设备：小米手环 9 Pro（Vela OS，QuickApp/快应用 JS 框架）。
- **关键限制：9/9Pro 上 system.fetch / system.request / system.uploadtask 均"不支持"**（来源：Vela 文档 features/network 支持明细；社区实证米坛 BandBBS 亦确认 band9 无 @system.fetch）。
- **联网通路（用户纠正后确认）：手环经 BLE 连接手机、以手机网络为网关——即 `system.interconnect` 与手机端桥接 App 通信**。官方文档 interconnect 页未列 9Pro 支持明细，但社区实证（米坛 BandBBS：band9 支持 interconnect，需配套安卓 App 透传；澎湃哔哩等三方应用"小米运动健康连接后即可联网"）佐证 9 系列可用。手机端完成 HTTPS 请求（摩柿 morax.kdns.fr / 虚空终端 buer.kdns.fr），再把结果回传手表。
- **备选通路（无手机，已获社区实证，2026-09-30 视频纪要核验）：ESP32 蓝牙网关**——ESP32 刷蓝牙网关固件（3 个固件烧录），配网页 192.168.4.1 填家庭 WiFi 密码 + 手环 MAC + KEY；手环经 BLE 连 ESP32，由 ESP32 转发网络请求到互联网（演示：未连手机时可加载图片、更新天气）。**该网关对 QuickApp 透明：应用仍用 fetch/请求，仅底层链路不同**（BLE 承载由系统运行层管理，应用无需 BLE 客户端 API）。两种网关（手机桥 / ESP32）配置说明均写入 README。
- [联网能力专项复查结论（定稿，2026-09-30）]：**9Pro Vela QuickApp 不能直接 fetch/HTTP，也无 socket/websocket/BLE GATT；一切联网只能由配对手机 App 当网关经 system.interconnect 透传**（社区旁证：手环 B站等三方应用需小米运动健康连接后才可联网）。interconnect 在 9Pro 官方未给支持表 → 须真机 POC，README 明示此风险与验证步骤。
- 手表本地能力：system.file（writeText append / readArrayBuffer position+length / list / delete / access）、system.storage（get/set/delete）、system.prompt、system.vibrator、system.router。
- 双服务并列：摩柿 + 虚空终端，同一套页面/协议，仅服务配置不同。

## 2. 仓库结构（冻结）

```
MoshiNovel-Vela/
├── README.md                     # 中文总说明：架构、限制、双服务、导入运行、构建签名
├── spec/
│   ├── architecture.md           # 本文件
│   ├── vela-capability.md        # [子任务A] Vela 文档能力/9Pro支持矩阵/ux语法
│   └── voidterminal-api.md       # [子任务B] 虚空终端 API 实测结论
├── watch-app/                    # QuickApp 工程（主交付）
│   ├── manifest.json             # package/name/features/router 声明
│   ├── app.ux                    # App 生命周期 + 全局状态
│   ├── app.css                   # 全局样式（主题、字号、安全区）
│   ├── common/
│   │   ├── api.js                # 双服务抽象 + 桥接请求封装（唯一网络入口）
│   │   ├── bridge.js             # interconnect 封装：连接管理/请求-响应/分片接收
│   │   ├── store.js              # storage 封装：session/账号/书籍元数据/阅读进度
│   │   ├── reader.js             # TXT 分页解码 + 进度计算（纯逻辑，无 UI 依赖）
│   │   └── util.js               # 常量、格式化、UTF-8 边界工具
│   ├── pages/
│   │   ├── entry/                # 首页：双服务入口 + 本地书库 + 设置入口
│   │   ├── login/                # 登录页（按服务参数化）
│   │   ├── shelf/                # 书架页（列表/下载/删除/进度）
│   │   └── reader/               # 阅读器页
│   └── sign/
│       ├── debug/                # private.pem + certificate.pem（占位+生成说明）
│       └── release/              # 同上
└── phone-bridge/                 # 手机端桥接（Android 参考工程源码 + 构建/签名/配对说明）
    └── README.md
```

## 3. 桥接协议（interconnect，冻结）

手机端与手表端通过 `system.interconnect` 通信（连接自动建立，watch 侧 `connect.onopen/onmessage/onclose` 管理）。

### 3.1 报文信封（watch → phone 请求）

```json
{
  "v": 1,
  "id": "req-000042",
  "service": "moshi",              // "moshi" | "void"
  "action": "login" | "shelf" | "download" | "ping",
  "payload": {}
}
```

- `id`：watch 侧自增唯一请求号（`req-${seq}`），用于配对响应。
- `download` 的 payload：`{ "title": "…", "downloadUrl": "…", "bookKey": "moshi_1001" }`，bookKey 决定落盘文件名。

### 3.2 报文信封（phone → watch 响应 / 推送）

```json
{ "v": 1, "id": "req-000042", "ok": true, "code": 0, "message": "", "payload": {} }
{ "v": 1, "id": "push-…", "kind": "download_progress", "payload": { "bookKey": "…", "percent": 42, "bytes": 123456, "total": 300000 } }
{ "v": 1, "id": "push-…", "kind": "download_chunk", "payload": { "bookKey": "…", "seq": 7, "chunk": "……", "eof": false } }
```

- `login` 响应 payload：`{ "session": "…", "username": "…" }`；失败时 ok=false + message。
- `shelf` 响应 payload：`{ "items": [ { "title": "…", "author": "…", "downloadUrl": "…", "taskId": 1001 } ] }`（字段与摩柿 /api/bookshelf 对齐；虚空终端若 API 不同由子任务B结论定，字段名保持 title/author/downloadUrl/taskId 的映射层在手机端完成）。
- `http`（fetch 等价通道，2026-09-30 新增）：watch→phone `{action:'http', payload:{method,url,headers,body}}`；phone 执行任意 HTTP(S) 后回 `{ok:true, payload:{status, headers, body}}`（body 文本；超大响应截断上限 64KB 并置 truncated:true，大文件仍走 download 动作）。用途：登录/书架等 JSON 小响应可经此通道直达（适配 FetchBridge 类网关），也用于运行时无 fetch 时的等价请求。
- 下载：phone 端先发 `download_progress`（percent 随字节数推进，便于手表显示进度），再按序发 `download_chunk`（分片大小 ≤ 4KB 文本），最后一帧 `eof:true`；随后发 `download_progress` percent=100 收尾。
- `ping`：手表启动/重连时探测桥接，响应 ok=true。

### 3.3 超时与错误

- watch 侧请求超时 20s（login/shelf），download 不超时（由 push 流驱动）。
- 失败统一 `{ok:false, code, message}`；message 中文，直接上屏提示。

## 4. 手表本地存储（冻结）

| 用途 | storage key | 内容 |
|---|---|---|
| 摩柿账号 | `account.moshi` | JSON `{username, password}`（明文，README 注明风险） |
| 摩柿会话 | `session.moshi` | session 字符串 |
| 虚空账号 | `account.void` | 同上 |
| 虚空会话 | `session.void` | 同上 |
| 活动服务 | `active_service` | "moshi" \| "void" |
| 书籍元数据 | `book.moshi.${taskId}` | JSON `{title, author, uri, size}` |
| 阅读进度 | `progress.${uri}` | 字节偏移 Number |

- 落盘文件：`internal://files/books/${bookKey}.txt`，bookKey=`${service}_${taskId}`。
- 删除：file.delete(uri) + storage.delete(对应 key)。

## 5. 页面流（冻结）

```
entry ──┬─ [摩柿] → login(service=moshi) → shelf(moshi) ──┬─ 点书 → reader
        ├─ [虚空终端] → login(service=void) → shelf(void) ──┤
        └─ [本地书库] → 已下载书籍列表（跨服务汇总）──────┘    （shelf 内可删除/标记进度）
```

- entry：两服务卡片（显示登录态/会话有效性）+ 本地书库入口；底部小字设置（退出登录/清理）。
- login：用户名/密码输入（input 组件，密码用密码键盘），登录按钮，失败 toast；成功写 storage 并跳 shelf。
- shelf：列表渲染书架（滚动），已下载项标 [已下载]，未下载项点击开始下载并显示进度（下载中可返回，进度在条目内更新）；条目提供删除操作（本地已下载时）；顶部标题含服务名与"退出登录"。
- reader：进入即读 `internal://files/books/...`，显示页码/百分比，触控左右翻页或滑动，离开时持久化进度；返回 shelf 时列表显示进度百分比。

## 6. 阅读器设计（冻结，实现细节交阅读器子任务）

- 大文件禁止整体 readText（手环内存小，文档明确警告）。
- 用 `file.readArrayBuffer({uri, position, length})` 按窗口读取（窗口 ~4096B 原始字节），解码 UTF-8（处理多字节边界：回退到合法码点边界再取文本）。
- 分页：按字节窗口 → 按显示行数切页（每页行数 = 屏幕可容纳，rpx 计算）；记录每页的起始字节偏移；进度 = 起始偏移/文件总长。
- 总长来自 file.get / 书架元数据 size（TXT 的 UTF-8 字节数）。
- 方向键/触摸翻页；进度写入 storage（节流：翻页即写）。
- [TBD] 组件能力（scroll-view/文本换行）待 vela-capability.md 定，实现时以文档为准，不硬造。

## 7. 双服务配置（冻结）

```js
// common/api.js
const SERVICES = {
  moshi: { key: 'moshi', label: '摩柿', host: 'morax.kdns.fr', backend: 'https' },
  void:  { key: 'void',  label: '虚空终端', host: 'buer.kdns.fr', backend: 'NONE' }
};
```

- 所有页面不感知具体服务：`api.login(service, u, p)` / `api.shelf(service)` / `api.download(service, book)`。
- 手机端桥接 App 内实现摩柿后端适配，向手表暴露同一协议。
- **虚空终端结论（子任务B实测，2026-09-30）：buer.kdns.fr 无 HTTP 书架/下载 TXT API**（`POST /api/login` 换 token + `wss://buer.kdns.fr/ws` 聊天 + `/api/status` 主机监控探针；实测 `GET /api/bookshelf` → 404；26 条路由无任何书架/下载端点；qgs 监控 = `/opt/server_monitor.py` 主机健康探针，与书无关）。
- 按用户预案：虚空终端 **不臆造 API**，交付形态 = 摩柿全功能闭环 + 虚空终端接入方案说明（README + 应用内"虚空终端"入口卡片点击后显示受限说明页：无公开书架 API；可选后续方案 = 手机端桥接长连 WSS 只读大厅）。
- 因此应用内仍保留双服务入口并列（用户要求的功能形态），虚空终端页为"受限说明 + 接入方案"静态内容，不发起虚构请求。

## 8. 手机端桥接（phone-bridge/，冻结交付形态）

- Android 工程（Android Studio，Java/Kotlin 均可），**包名 = manifest.json 的 package，签名 = rpk 同一签名**（jks→p12→pem 流程按 Vela 文档）。
- 功能：监听 interconnect（对端 SDK 接入方式 [TBD-子任务A i 项]），实现 HTTPS 客户端（morax.kdns.fr：POST /api/login 取 Set-Cookie session、GET /api/bookshelf、GET download_url；虚空终端按子任务B结论），分片回传手表。
- 附 README：构建 APK、签名对齐、手机安装、与手环配对、排障（adb logcat、手表端日志）。
- 交付：源码 + 构建说明；环境不允许则给出精确步骤。（README 中不得出现任何 SSH 凭据。）

## 10. 实现级冻结（v0.2，供实现子任务遵守，不得偏离）

> 依据：vela-capability.md（Vela 文档结论）+ voidterminal-api.md（虚空终端无书架 API）。
> 所有实现子任务开工前必须先读本文件与 vela-capability.md 全文。

### 10.1 manifest.json 全文（冻结，写入 watch-app/src/manifest.json）

```json
{
  "package": "com.moshinovel",
  "name": "摩柿小说",
  "icon": "/common/images/icon.png",
  "versionName": "1.0.0",
  "versionCode": 1,
  "minAPILevel": 1,
  "features": [
    { "name": "system.file" },
    { "name": "system.storage" },
    { "name": "system.interconnect" },
    { "name": "system.device" },
    { "name": "system.vibrator" },
    { "name": "system.prompt" }
  ],
  "config": { "logLevel": "info", "designWidth": 480 },
  "router": {
    "entry": "Entry",
    "pages": {
      "Entry":   { "component": "entry", "path": "/" },
      "Login":   { "component": "login" },
      "Shelf":   { "component": "shelf" },
      "Library": { "component": "library" },
      "Reader":  { "component": "reader", "launchMode": "singleTask" },
      "Void":    { "component": "void" }
    }
  },
  "display": { "backgroundColor": "#111318" },
  "deviceTypeList": ["watch"]
}
```

页面文件路径：watch-app/src/pages/{Entry/entry.ux, Login/login.ux, Shelf/shelf.ux, Library/library.ux, Reader/reader.ux, Void/void.ux}。

### 10.2 路由与页面参数（冻结）

| 跳转 | 写法 | 目标页 public 声明 |
|---|---|---|
| 入口→登录 | router.push({uri:'Login', params:{svc:'moshi'}}) | `public:{svc:''}`，读取 `this.svc` |
| 登录→书架 | router.replace({uri:'Shelf', params:{svc:'moshi'}}) | 同上 |
| 书架→阅读 | router.push({uri:'Reader', params:{uri:'internal://files/books/moshi_1001.txt', title:'书名', svc:'moshi'}}) | `public:{uri:'',title:'',svc:''}` |
| 入口→本地书库 | router.push({uri:'Library'}) | 无参 |
| 入口→虚空终端 | router.push({uri:'Void'}) | 无参 |

### 10.3 common 模块接口契约（冻结）

`common/store.js`（全部 Promise，内部包装 @system.storage）：
- `getAccount(svc)` → Promise<{username,password}|null>；`setAccount(svc, acc|null)`
- `getSession(svc)` → Promise<string>；`setSession(svc, s)`
- `getBookMeta(svc, taskId)` → {title,author,uri,size}|null；`setBookMeta(svc, taskId, meta)`；`delBookMeta(svc, taskId)`
- `getProgress(uri)` → number；`setProgress(uri, offset)`
- `getActiveService()` / `setActiveService(svc)`

`common/api.js`：
- `const SERVICES = { moshi:{key:'moshi',label:'摩柿'}, void:{key:'void',label:'虚空终端'} }`
- `login(svc, username, password)` → Promise<{ok, session, message}>
- `fetchShelf(svc)` → Promise<{ok, items:[{title,author,downloadUrl,taskId}], message}>
- `downloadBook(svc, book)` → Promise<{ok, uri, message}>（内部走 bridge 分片落盘，书落在 `internal://files/books/{svc}_{taskId}.txt`）
- `onDownloadProgress(cb)` → cb({bookKey, percent, bytes, total})
- `pingBridge()` → Promise<{ok, message}>

`common/bridge.js`（单例，@system.interconnect 封装）：
- 模块加载即注册 `connect.onopen/onmessage/onclose/onerror`；维护状态 'connecting'|'connected'|'disconnected'
- `request(action, payload, {timeoutMs})` → Promise<{ok, code, message, payload}>（id 自增、pendingMap 配对；未连接时先诊断/等待 onopen，超时 20s 报"未连接手机桥接"）
- 下载分片接收：`download_chunk` 帧按 seq 顺序 `file.writeText({uri, text:chunk, append:true})` 落盘；收 `download_progress` 帧对外派发进度；收 `eof` 后校验并 resolve
- 桥接不可用（interconnect 在 9Pro 文档未明示）→ 所有请求返回 `{ok:false, message:'未连接手机桥接 App，请打开手机端桥接并保持蓝牙连接'}`，UI 照常提示

`common/util.js`：`BOOKS_DIR='internal://files/books/'`；`pad(n)`；`formatSize(bytes)`；`utf8Decode(u8, start, end)`（不依赖 TextDecoder 的 UTF-8 安全解码，用于 readArrayBuffer 窗口）；`CHUNK_MAX=4000`

### 10.4 阅读器算法（冻结，reader.ux + common/reader.js 实现）

- 进入：`file.get({uri})` 取 length（若失败用 0）；从 `store.getProgress(uri)` 恢复字节偏移；`pageOffsets=[]` 记录已翻页偏移（向前推进时 push，向后用历史）。
- 翻页（下一页）：`file.readArrayBuffer({uri, position:off, length:4096})` → utf8Decode → 按 `\n` 切行，取前 `linesPerPage` 行 → 消费的字节数 = 该段原始字节长（解码前按边界修正）→ 新 off；记录 pageOffsets。上一页：pageOffsets 弹栈回退。
- 展示：`<scroll scroll-y>`（定高）+ `<text>` 渲染该页文本；底部显示 `{页进度%}`；swipe left/right 与左右触控区翻页。
- 常量（可在实现时微调并回填）：font-size 34px、linesPerPage 12、窗口 4096B。
- 持久化：翻页即 `store.setProgress(uri, off)`（节流：每次翻页一次）。
- 返回：onBackPress 不拦截（默认返回书架）；书架从 getProgress 展示百分比。

### 10.5 桥接降级策略（冻结）

- entry 页显示桥接状态（connected/disconnected，来自 bridge.getState()）。
- 登录/书架在桥接未连接时：点击即 toast 中文提示"未连接手机桥接"，不发起请求。

### 10.6 图标与资源

- `/common/images/icon.png` 192×192（已由主任务生成）。
- 不依赖网络图片；所有 UI 纯组件绘制。

### 10.7 实现分工（冻结，文件互不重叠）

| 子任务 | 负责文件 |
|---|---|
| B1 桥接层 | watch-app/src/common/{bridge.js, api.js, store.js} |
| B2 入口 | watch-app/src/pages/{Entry/entry.ux, Void/void.ux} |
| B3 登录+书架+书库 | watch-app/src/pages/{Login/login.ux, Shelf/shelf.ux, Library/library.ux} |
| B4 阅读器 | watch-app/src/pages/Reader/reader.ux + watch-app/src/common/reader.js |
| B5 手机桥接 | phone-bridge/（Android 参考工程 + README） |

骨架文件（manifest.json/package.json/app.ux/app.css/common/util.js/sign 说明）由主任务已写入，实现子任务只读不改（B1 可扩展 util 但不得改 manifest）。

## 11. 虚空终端聊天集成（v0.3，2026-09-30 新增；实现基准，子任务须遵守）

> 需求更正：虚空终端（buer.kdns.fr）是**聊天（IM）服务**，摩柿才是书架。虚空终端模块从"受限说明页"升级为**完整聊天**：登录 → 房间/会话列表 → 收发消息。协议细节以 spec/voidterminal-im.md（R1 深挖）为准。

### 11.1 拓扑与前提（冻结）

- Vela QuickApp **无 WebSocket API**（网络分类仅 fetch/interconnect/request/uploadtask）→ **WSS 长连由手机端桥接 App 持有**，手环经 interconnect 收发聊天报文。无桥接时聊天不可用（页面给中文提示"请连接手机桥接"）；摩柿小说不受影响（可走 fetch 直连）。
- 手机桥接内实现虚空终端客户端：`POST /api/login` 换 token → 连 `wss://buer.kdns.fr/ws`（不带 Origin 放行）→ 发 `{type:'auth',token,lite:true}` → 收 hello 与 global/dm/group 推送 → 回 pong、20s 心跳、断线 5s 重连 + 重新 auth。TLS 走系统信任链，不硬 pin（Cloudflare 证书轮换）。
- 会话历史：lite 模式 hello 仅最近 10 条 globalMsgs；群/私聊历史仅实时——v1 接受此限制，README 记录。

### 11.2 桥接协议扩展（在 §3 基础上追加，冻结）

请求动作（service='void'）：
- `vt_login` payload `{username, password}` → 响应 `{ok, token}`（phone 完成 HTTP 登录 + 建立/保持 WSS，token 为登录 JSON body 的 token）；失败 ok=false + message。
- `vt_rooms` payload {} → 响应 `{ok, self:{id,username}, rooms:[{key,type,name,lastMsg,unread}]}`；key ∈ `global` | `group:<gid>` | `dm:<uid>`（**大厅 key='global'**，对应服务端内部会话 id `"public"`，phone 负责映射），type ∈ global|group|dm，name 为大厅名/群名/好友名，lastMsg 为最后一条内容（可空），unread 为 phone 侧未读计数（手环端亦可自行累计 vt_message 未读，展示时取两者较大）。**self 必回**：dm 消息路由需要 isMe 判定（peer = from==self.id ? to : from）。
- `vt_send` payload `{type, id, content}`（type=global 时 id 可省；group/dm 时 id=gid/uid）→ 响应 `{ok}`；失败 ok=false + message。
- `vt_quit` payload {} → phone 断开该服务会话（保留 WSS 或按实现）。
- `vt_asr` payload {} → 响应 `{ok, text}`（phone 调系统 ASR 录音转写一次，返回文本；失败 ok=false + 中文 message；权限/超时/无网络按失败处理）。用于语音输入主方案（见 11.5）。

推送（kind 新值）：
- `kind='vt_message'` payload `{type, roomKey, from, fromName, content}` —— 新消息（global/group/dm 统一，roomKey 按 11.3 规则；无 time 字段）。
- `kind='vt_status'` payload `{state:'connected'|'reconnecting'|'offline'}` —— WSS 连接状态变化。

### 11.3 会话模型（冻结）

- roomKey 规则：`global`（=服务端会话 id `"public"`）、`group:<gid>`、`dm:<uid>`（peer uid，唯一，供路由）。
- 手环本地会话列表：vt_rooms 初始化 + vt_message 增量更新（lastMsg、未读计数）。
- 会话排序：大厅置顶，其后群聊/私聊按最近消息时间倒序（实现可用插入序近似）。

### 11.4 页面流（冻结）

```
entry → Void(hub: 桥接状态/登录态/入口) → login(svc=void)（复用登录页，成功后按 svc 分支：
  moshi→Shelf，void→VoidRooms）→ VoidRooms（会话列表：大厅/群聊/私聊）→ VoidChat（消息列表+输入条）
```

- 页面文件：pages/Void/void.ux 改为聊天 hub；新增 pages/VoidRooms/voidrooms.ux、pages/VoidChat/voidchat.ux；manifest router 补 VoidRooms/VoidChat（component 名 voidrooms/voidchat）。
- 登录页 login.ux 微调：登录成功跳转按 this.svc 分支（moshi→Shelf、void→VoidRooms）。

### 11.5 手环输入方案（冻结，按 Vela 实际能力）

- **快捷回复 chips**：聊天页底部常驻 3-4 个快捷短语按钮（如"好的""收到""哈哈哈""在吗"），点击即发送——最快路径。
- **预设短语库**：一个"短语"按钮打开短语选择页/弹层，两级（分类→短语），约 24 条常用语料（语料见 voidterminal-im.md g 节 ESP 参考 + 通用补充）；选择后直接发送或进入待发。
- **可选文本输入**：若 Vela 手表端 `<input type="text">` 有系统键盘则启用（实现时查文档确认；无键盘或未确认则默认关闭并 README 说明）。不实现拼音候选输入（ESP 方案为墨水屏特化，手环触屏用 chips+短语更合适）。
- 发送流程：点发送 → api.vtSend → 本地乐观回显（置"发送中"，失败改"失败"并 toast）。
- **语音输入（v0.4 定案，依据 spec/voice-input.md）**：手环端**不能录音/识别**——`@system.record` 支持明细表明确「小米手环 9/9Pro = 不支持」（全表仅 Watch S5），`@system.audio` 仅播放，Vela 无 STT/ASR 接口，小爱同学仅系统层（"语音唤起应用"技能，不对三方 rpk 开放应用内转写）。故主方案 = 聊天输入条「语音」按钮（仅手机桥已连接时可用）→ bridge action `vt_asr` → 手机 App 调系统 ASR 转写 → 回传文本 → 手环显示草稿（三个按钮：发送→vt_send / 重录→再次 vt_asr / 取消→清草稿）。备选 1 = 快捷回复 chips + 预设短语库（已实现，零语音最稳）；备选 2 = 「去手机回复」兜底（长文本在手机原生键盘输入后透回）。依赖 interconnect 在 9Pro 可用（须先 POC）。

### 11.6 增量接收与未读（冻结）

- bridge 收到 kind='vt_message' → 若当前在对应 roomKey 的 VoidChat 页 → 追加消息列表并滚动到底；否则更新会话列表 lastMsg + 未读计数（内存态即可，v1 不持久化未读）。
- VoidChat 进入时请求该房间最近消息（内存缓存 + 实时推送；历史仅 global 最近 10 条，见 11.1）。

### 11.7 限制（冻结，README 同步）

- 无手机桥接 → 聊天不可用（提示）；摩柿小说可离线/直连不受影响。
- 群/私聊历史仅实时（hello lite 只带 globalMsgs 最近 10 条）。
- 消息长度、空内容等以 voidterminal-im.md 服务端/客户端实测为准。
- 聊天 UI 按 336×480 小屏设计，消息列表 scroll 定高、输入区底部固定。

## 12. 验收清单（冻结，含聊天版）

1. 手环端 manifest 合法，features 含 file/storage/interconnect 等；AIoT-IDE 可导入。
2. 双服务入口并列；登录（账密→session 持久化）、书架获取、下载（进度反馈）、离线阅读（分页+进度记忆）、删除本地 TXT 五项功能在摩柿上完整闭环。
3. 虚空终端聊天：登录 → 房间/会话列表（大厅/群聊/私聊）→ 收发消息完整闭环；无桥接时中文提示；预设短语/快捷回复输入可用。
4. 断网/登录失败/下载失败/发送失败均有中文错误提示。
5. README 中文完整：架构图、联网通路（手机桥/ESP32 网关/FetchBridge）、AIoT-IDE 导入/打包/签名步骤、手机桥接构建与配对、虚空终端聊天说明与限制、测试账号标注（摩柿 Admin/xzmlwjh1，用户可改）。
