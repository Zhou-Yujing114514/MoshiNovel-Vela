# 摩柿小说 · 手机端桥接（Android 参考工程）

> 本工程是手环 9 Pro 快应用 **MoshiNovel-Vela** 的手机对端：手环没有直连网络，
> 由本 App 作为「BLE → 手机网络」网关，完成全部 HTTPS 请求，再把结果经
> `system.interconnect` 分片透传回手环。
>
> - 包名（applicationId）：`com.moshinovel` —— 与手环 `watch-app/src/manifest.json` 的 `package` 完全一致。
> - 本仓库**不含任何 SSH/账号凭据**；用户账密由手环端输入后随协议 payload 传入，不落盘。

---

## 1. 联网通路与安装前提（务必先读）

手环 9 / 9 Pro 在 Vela 官方支持表里，`system.fetch` / `system.request` / `system.uploadtask` /
`system.network` 全部标注「不支持」，手环本身也没有 Wi-Fi。因此手环侧联网只有两条路：

1. **本桥接 App（推荐，功能完整）**：手机装本工程编译出的 APK 并保持前台服务运行；
   手环快应用经 `@system.interconnect` 与本 App 通信，由本 App 完成
   登录（`login`）、书架（`shelf`）、TXT 分片下载（`download`）以及通用 HTTP 代理（`http`）。
2. **社区插件 FetchBridge（fetch 等价通道，备选）**：用户已核实社区插件
   **「网桥 FetchBridge」（作者 Searchstars，v1.3.2）** 通过 interconnect 为不支持 fetch 的设备
   提供 fetch 等价能力。若手环侧改用该插件联网，其请求需按插件作者约定的协议发起；
   本工程**已预留通用 `action:'http'` 兼容动作**（见 §4.2），FetchBridge 类插件只需把
   `{method,url,headers,body}` 映射到该动作即可复用本 App 的出网能力。

> **共同前提**：无论走哪条路，手机上都必须安装并登录「**小米运动健康**」，并保持它与手环的蓝牙连接。
> 手环的通信链路是「手环 ↔ 小米运动健康 ↔（开放 SDK）↔ 本桥接 App」，小米运动健康断开即全链路不通。

> 已知不确定点（来自 vela-capability.md h/i/k 节）：官方 interconnect 文档页**未给出手环 9 Pro
> 的支持明细表**，是否可用必须真机 POC。先按 §6 完成配对，再做一次 `ping` 联调。

---

## 2. 架构

```
手环 9Pro (Vela QuickApp)                         手机 (Android, 本工程)
┌──────────────────────┐   BLE/interconnect    ┌─────────────────────────────┐
│ watch-app/*.ux       │  ──── JSON 帧 ────▶  │ BridgeService (前台服务)     │
│  common/bridge.js    │ ◀── progress/chunk ── │  ├─ WatchChannel (SDK 适配)  │
│  common/api.js       │                       │  ├─ MoraxClient (HTTPS, 指纹)│
└──────────────────────┘                       │  └─ 协议主循环 / 4KB 分片    │
                                               └──────────────┬──────────────┘
                                                              │ HTTPS
                                                              ▼
                                                    https://morax.kdns.fr
                                                    /api/login · /api/bookshelf
```

分层（业务层与 SDK 层解耦）：

| 文件 | 职责 |
|---|---|
| `MoraxClient.java` | 摩柿 HTTPS 客户端：登录取 session cookie、书架解析、TXT 下载流；内置证书 SHA-1 指纹固定。 |
| `BridgeService.java` | 前台服务 + 协议主循环：收请求 → 分发 → 响应；下载按 4KB 文本分片 + 进度帧回传；含通用 `http` 代理。 |
| `WatchChannel.java` | SDK 适配层（唯一 TODO 集成点）：`sendToWatch(json)` / `setWatchMessageListener`，当前为日志回退实现。 |
| `MainActivity.java` | 最小启动页，拉起前台服务。 |

---

## 3. 构建 APK

> **沙箱说明**：本交付环境无 Android SDK，也未联网拉取 Gradle 依赖，
> 因此**仅交付源码 + 精确构建步骤，未做编译验证**（不在此伪造编译结果）。

本机构建要求：

- JDK 17；
- Android SDK Platform 34、Build-Tools 34.x；
- Gradle 8.5+（或用工程自带 wrapper，本交付未附带 wrapper jar，可在有网机器上 `gradle wrapper` 生成）。

命令行构建（在 `phone-bridge/` 目录下）：

```bash
# 调试包
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 发布包（需先配好签名，见 §5）
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release-unsigned.apk（签名后）
```

依赖（已在 `app/build.gradle` 声明）：OkHttp 4.12.0 + Gson 2.11.0，首次构建需联网拉取。

---

## 4. 桥接协议字段表（与 architecture.md §3 对齐）

### 4.1 watch → phone 请求

| 字段 | 类型 | 说明 |
|---|---|---|
| `v` | int | 协议版本，固定 `1` |
| `id` | string | watch 自增请求号 `req-N`，配对响应 |
| `service` | string | `"moshi"` / `"void"` |
| `action` | string | `"login"` / `"shelf"` / `"download"` / `"ping"` / **`"http"`（新增通用代理）** |
| `payload` | object | 见下表 |

| action | payload |
|---|---|
| `login` | `{username, password}` |
| `shelf` | `{}` |
| `download` | `{title, downloadUrl, bookKey}` |
| `ping` | `{}` |
| **`http`** | `{method, url, headers?, body?}`（FetchBridge 等 fetch 等价通道用） |

### 4.2 phone → watch 响应

```json
{ "v":1, "id":"req-00042", "ok":true, "code":0, "message":"", "payload":{...} }
```

| action | 成功 payload |
|---|---|
| `login` | `{session, username}` |
| `shelf` | `{items:[{title, author, downloadUrl, taskId}]}` |
| `download` | `{bookKey, bytes}`（分片流结束后） |
| `ping` | `{pong:true}` |
| **`http`** | `{status, headers:{}, body, truncated}` —— body 文本；超 64KiB 截断并 `truncated:true`，超大响应请改用 `download` 分片 |

失败统一：`{ok:false, code, message}`，`message` 中文直接上屏。错误码：`0` 成功、`1` 参数/内部错误、
`2` 未知 action、`10` 服务未接入、`20` 下载失败、`30` HTTP 代理失败、`11/12` 登录/书架失败。

### 4.3 phone → watch 推送帧（下载流）

```json
{ "v":1, "id":"push-N", "kind":"download_progress",
  "payload":{ "bookKey":"…", "percent":42, "bytes":123456, "total":300000 } }
{ "v":1, "id":"push-N", "kind":"download_chunk",
  "payload":{ "bookKey":"…", "seq":7, "chunk":"……", "eof":false } }
```

- `download_chunk` 每片 ≤ **4000 字符**（`CHUNK_MAX`，与手环 `common/util.js` 一致）；
- 末片 `eof:true`，随后补发 `download_progress` `percent:100` 收尾。

---

## 5. 签名对齐（interconnect 通信的硬性前提）

手环 rpk 与本 App 必须**同包名 + 同签名证书**，否则通信被系统拒绝（FAQ「签名不正确」）。

约定流程（jks → p12 → pem）：

```bash
# 1. 安卓 jks 转 pkcs12
keytool -importkeystore -srckeystore moshi-bridge.jks \
  -destkeystore moshi-bridge.p12 -srcstoretype jks -deststoretype pkcs12
# 2. p12 导出 pem（含私钥+证书）
openssl pkcs12 -nodes -in moshi-bridge.p12 -out moshi-bridge.pem
# 3. 拆分：
#    -----BEGIN PRIVATE KEY-----…END PRIVATE KEY-----  →  watch-app/sign/debug/private.pem
#    -----BEGIN CERTIFICATE-----…END CERTIFICATE-----    →  watch-app/sign/debug/certificate.pem
#    同样两份 →  watch-app/sign/release/（debug/release 用同一证书）
```

- 安卓 release 签名口令**不要写进仓库**：在 `~/.gradle/gradle.properties` 配置
  `MOSHI_STORE_FILE / MOSHI_STORE_PASSWORD / MOSHI_KEY_ALIAS / MOSHI_KEY_PASSWORD`，
  `app/build.gradle` 的 `release` signingConfig 已留好读取位（当前注释状态，按需取消注释）。
- 也可用官方「在线签名生成工具」（上传 p12 浏览器内生成 pem）。
- **release 每次必须用同一证书**；debug 与 release 也建议同源，避免调试期通信失败。

---

## 6. 手机安装、配对与 rpk 安装

1. 手机安装 `app-debug.apk`（或 release 包）；首次打开授予通知权限（前台服务需要）。
2. 手机装「小米运动健康」并登录，按官方流程把手环 9 Pro 配对连接（三方应用能力目前需商务拉群开通，
   对接邮箱 changjian@xiaomi.com）。
3. 手环 rpk 安装（FAQ g.5 流程）：
   小米运动健康 → 我的 → 关于 → 连续点「关于」进 **Debug** → 第三方应用 →
   输入包名 `com.moshinovel` → **Install third app** → 选本地 `.rpk` 安装，成功有 Toast。
   建议先按包名卸载旧包再装，避免图标/签名残留。
4. 打开手环快应用，先做一次 `ping`：状态栏/日志里看到桥接回 `{ok:true,pong:true}` 即链路通。

---

## 7. 排障

- **手机端日志**：`adb logcat -s BridgeService MoraxClient WatchChannel`。
  - `WatchChannel: phone -> watch (LOG-ONLY…)` 说明当前仍是日志回退实现、未接官方 SDK（见 `WatchChannel.java` 顶部 TODO）。
- **手环端日志**：小米运动健康 → 关于 → Debug → 拉取固件日志，
  保存在手机 `/sdcard/Android/data/com.mi.health/files/log`。
- **常见问题**：
  - 报「未连接手机桥接」→ 小米运动健康与手环蓝牙断开；或本 App 前台服务被杀（把本 App 加入后台白名单/自启）。
  - 报「签名不正确」→ 本 App 证书与 rpk 的 pem 不同源，回到 §5 对齐。
  - 摩柿登录失败 → 先确认手机本身能访问 `https://morax.kdns.fr`；证书指纹过期会在 `MoraxClient` 日志报
    「证书指纹不匹配」，需更新 `PINNED_SHA1_FINGERPRINT`。

---

## 8. 虚空终端聊天（buer.kdns.fr，WSS 由本 App 持有）

虚空终端是聊天 IM 服务。Vela QuickApp **没有 WebSocket API**，因此 `wss://buer.kdns.fr/ws`
长连由本手机桥接 App 持有（`VoidTerminalClient.java`），手环只经 interconnect 收发 JSON。
无桥接 App 时聊天不可用，手环页面应给中文提示「请连接手机桥接」。

### 8.1 连接链路（本 App 内部自动完成）

1. 手环 `vt_login {username,password}` → 本 App `POST https://buer.kdns.fr/api/login`
   （JSON body）→ 取响应体 `token`（**不读 Set-Cookie**）；
2. 本 App 连 `wss://buer.kdns.fr/ws`（不带 Origin 服务端放行；TLS 走系统信任链，
   buer 在 Cloudflare 后面、边缘证书轮换，**不硬 pin**）；
3. 握手成功立即发 `{"type":"auth","token":"<token>","lite":true}`；
4. 收服务端 `hello`：`self.id/username`、`friends[].id/.name`、`groups[].id/.name`、
   `globalMsgs[]`（lite 仅最近 10 条大厅消息）→ 初始化会话表；
5. 保活：服务端 WS ping 必须回 pong；本 App 每 20s 发一次 WS ping；
   断线 5s 自动重连，重连成功后自动重发 auth（无需手环干预）。

### 8.2 桥接动作（service='void'，与 architecture.md §11.2 一致）

| watch→phone action | payload | phone→watch 响应 payload |
|---|---|---|
| `vt_login` | `{username,password}` | `{token}`（失败 ok=false + 中文 message） |
| `vt_rooms` | `{}` | `{self:{id,username}, rooms:[{key,type,name,lastMsg,unread}]}` |
| `vt_send` | `{type,id?,content}`（type=global 时 id 可省；group 时 id=gid；dm 时 id=对方 uid） | `{}`（失败 ok=false + message） |
| `vt_quit` | `{}` | `{}`（断开长连，抑制自动重连） |
| `vt_asr` | `{}` | `{text:"…"}`（手机侧调系统 SpeechRecognizer 转写一次；失败 ok=false + 中文 message） |

roomKey 规则（两端一致）：大厅固定 `global`（对应服务端内部会话 id `"public"`）；
群聊 `group:<gid>`；私聊 `dm:<对方uid>`。

### 8.3 phone→watch 推送帧

```json
{ "v":1, "id":"push-N", "kind":"vt_message",
  "payload":{ "type":"global|group|dm", "roomKey":"global|group:..|dm:..",
              "from":"<uid>", "fromName":"<昵称>", "content":"<文本>" } }
{ "v":1, "id":"push-N", "kind":"vt_status", "payload":{ "state":"connected|reconnecting|offline" } }
```

- 私聊对端路由：`peer = (from == self.id) ? to : from`；`fromName` 缺省回退 `from`。
- 本 App 侧维护每会话 `lastMsg/unread`；手环收到 `vt_message` 时若正停在该 roomKey 的聊天页则追加消息，
  否则更新会话列表的 lastMsg 与未读（v1 未读仅内存态）。

### 8.4 已知限制（与 architecture.md §11.7 一致）

- lite 模式大厅只回填最近 10 条；群/私聊历史仅"上线后实时消息"，无翻页。
- 空消息禁止发送（本 App 直接回 `{ok:false}`）；WS 未连时发送同样回错，不静默吞。
- 服务端 `error/banned/maintenance` 报文本版仅记录日志，靠断线重连兜底；401/403/503 登录层错误文案已中文化回传。
- **语音输入 `vt_asr`**：手环 9Pro 不能录音（`@system.record` 官方支持表判不支持），转写由手机系统 `SpeechRecognizer` 完成（中文 `zh-CN`，一次一句）。
  需 `RECORD_AUDIO` 运行时权限：首次打开本 App 会弹窗申请；未授权/手机无识别服务/超时/识别空，均回中文错误。
  同时只跑一路识别，并发请求直接拒绝；识别在前台服务 worker 线程进行，`onDestroy` 时 `destroy()` 防泄漏。
  个别 ROM 的系统语音服务要求前台 Activity，若服务内起识别失败，用户可在手机上直接点系统语音输入法兜底。

---

## 9. SDK 接入 TODO 清单

唯一需要按官方文档填充的位置是 `WatchChannel.java`：

- 文档：《小米穿戴第三方 APP 能力开放接口文档》
- 获取页：https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html
  （页面底部「参考附录 > 点击下载」取 PDF 与 interconnect 测试 demo）。
- 接入后：SDK 上行回调 → `dispatchToService(json)`；`sendToWatch(json)` → SDK 下发接口。
  业务层（`BridgeService`/`MoraxClient`）零改动。

---

## 10. 未实现 / 边界说明

- **虚空终端无书架 API**：`GET /api/bookshelf` → 404，故 `service:"void"` 走 `login/shelf/download`
  这些书源动作仍回 `{ok:false,"虚空终端暂未接入书源…"}`；虚空的完整能力是聊天（`vt_*` 动作，见 §8）。
- **通用 `http` 代理**仅做文本响应、不锁目标证书；用于 FetchBridge 类 fetch 等价通道，不要把它当大文件下载通道（大文件走 `download` 分片）。
