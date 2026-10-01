# 虚空终端（VoidTerminal）服务端 API 接入规格

> 调研日期：2026-09-30
> 结论来源：ESP8266 客户端源码（`VoidTerminal-ESP8266`）+ 服务端只读实测（进程 / nginx / cloudflared / node 服务源码 / 本机 curl）。未臆造任何端点。

---

## 0. 一句话核心结论

**虚空终端（`buer.kdns.fr`）没有"登录→书架→下载 TXT"的同构 HTTP API。**

它是一个 **实时聊天网站**（类微信：大厅/群聊/私聊/朋友圈/文件与图片上传/AI 对话），后端 = Express + `ws`。与摩柿（`morax.kdns.fr`）**只在"恰好都用 `POST /api/login` + JSON 用户名密码换 token"这一点上形似**；登录之后完全分叉——虚空终端**没有任何书架列表、`download_url`、`task_id`、TXT 下载端点**（实测 `GET /api/bookshelf` 返回 404）。ESP8266 固件本身也从不从服务端拉书或下载 TXT；固件里唯一的"TXT"是通过设备自身 AP 网页把 TXT **上传到 ESP 的本地 SD 卡**供墨水屏阅读。

要做手环小说阅读，**小说数据应继续走摩柿后端**；虚空终端只能作为"聊天"这一附加能力，不能当书源。

---

## 1. 服务端地址 / 端口 / TLS

| 项 | 值 | 说明 |
|---|---|---|
| 聊天站域名 | `buer.kdns.fr` | 公网入口，走 **Cloudflare Tunnel** |
| 对外协议/端口 | HTTPS 443（WSS 443） | 80 端口已对外关闭 |
| 隧道链路 | Cloudflare Tunnel → 本机 nginx:80 → node `server.js`(:3000) | cloudflared ingress 仅挂了 `buer.kdns.fr` |
| WebSocket | `wss://buer.kdns.fr:443/ws` | 经 nginx `/ws` location 升级代理到 :3000 |
| TLS 证书 SHA1 指纹（固件 pinning 用） | `1C:86:71:D8:C7:8C:C4:BA:58:43:B6:12:FF:36:4E:63:7E:51:FA:E1` | 这是 **Cloudflare 边缘证书**指纹 |

> ⚠️ 手环端不要照抄固件做硬指纹 pinning：固件 pin 死指纹是因为 ESP8266 没有系统 CA 库。buer 走 Cloudflare，边缘证书会轮换，硬 pin 会在证书更新后断连。手环应使用系统 TLS 信任链正常校验域名即可。

WS 的 `Origin` 校验（服务端 `verifyClient` 实测）：
- 请求**带** `Origin` 时，只允许 `https://buer.kdns.fr` / `http://buer.kdns.fr`，其余 403；
- 请求**不带** `Origin`（原生 WS 客户端）→ **直接放行**。所以手环裸连 WSS 不带 Origin 也能握手。

---

## 2. 认证（HTTP 登录）

### 2.1 登录

```
POST https://buer.kdns.fr/api/login
Content-Type: application/json

{"username":"<账号>","password":"<密码>"}
```

成功（200）：
```json
{ "ok": true, "token": "<opaque-token>", "user": { /* publicUser: id, username, ... */ } }
```
同时响应头下发会话 Cookie：
```
Set-Cookie: session=<token>; HttpOnly; Path=/; SameSite=Lax; Max-Age=604800
```

失败：
- 401 `{"error":"用户名或密码错误"}`
- 403 `{"error":"该账号已被封禁，无法登录"}`
- 维护模式下业务 API 返回 503，但 `/api/login`、`/api/register`、`/api/me`、`/api/site-status`、`/api/admin/*` 放行。

### 2.2 账号模型

- token 是**不透明串**，服务端内存+落盘（`data/sessions.json`）维护 `token -> userId` 映射，**有效期 7 天**，服务重启不丢登录态。
- 已认证的 HTTP 请求通过 `Cookie: session=<token>` 携带身份（服务端 `getTokenFromReq` 读 cookie）。
- 注册：`POST /api/register`，body `{"username","password","password2"}`，成功同样返回 `{ok, token, user}`。
- 登出：`POST /api/logout`（作废 token + 清 cookie）。
- 查当前用户：`POST /api/me`（带 session cookie）→ `{ok:true, user:{...}}`。

> 与摩柿的差异：摩柿登录返回的是 **session Cookie**；虚空终端登录**同时**返回 JSON `token` 和 `session` Cookie（二选一即可用来后续鉴权，ESP 客户端取的是 JSON body 里的 `token` 再放进 WS 报文）。

---

## 3. WebSocket 报文协议（`wss://buer.kdns.fr/ws`）

这是虚空终端**唯一**的实时通道，全部为聊天语义。

### 3.1 握手后立即发 auth

```json
{ "type": "auth", "token": "<登录拿到的 token>", "lite": true }
```
- `lite:true` 为精简模式（ESP8266 内存小）：只下发必要字段，`globalMsgs` 截断为最近 10 条。
- 服务端用 token 反查 sessions；失败回 `{"type":"error","error":"认证失败"}` 并关闭连接；封号回 `{"type":"banned",...}`；维护中回 `{"type":"maintenance",...}`。

### 3.2 服务端 hello（auth 成功后）

```json
{
  "type": "hello",
  "self": { "id": "<uid>", "username": "<昵称>", ... },
  "friends":  [ { "id": "<uid>", "name": "<昵称>", ... } ],
  "groups":   [ { "id": "<gid>", "name": "<群名>", ... } ],
  "globalMsgs": [ { "from": "<uid>", "fromName": "<昵称>", "content": "<文本>" }, ... ],
  "maxOnline": 0,
  "isAdmin": false,
  "hallName": "问题反馈站",
  "maintenance": false
}
```
（非 lite 模式额外带 `announcement`、`groupAnnouncements`、`pendingRequests` 及更多历史消息。）

### 3.3 客户端发消息

| 用途 | 报文 |
|---|---|
| 大厅（公共）发言 | `{"type":"global","content":"<文本>"}` |
| 群聊发言 | `{"type":"group","gid":"<群id>","content":"<文本>"}` |
| 私聊发言 | `{"type":"dm","to":"<对方uid>","content":"<文本>"}` |

> 发聊天消息时**不需要**再带 token（连接已通过 auth 绑定 uid）。

### 3.4 服务端推送（收到的新消息）

```json
{ "type": "global" | "dm" | "group", "from": "<uid>", "fromName": "<昵称>", "content": "<文本>", ... }
```

### 3.5 心跳与重连

- 服务端会主动发 WS **ping**，客户端**必须回 pong**，否则被踢（固件行为）。
- 客户端侧 20s 间隔发心跳保活。
- 断线后自动 5s 重连；重连成功后重新发 `auth`。

---

## 4. 虚空终端完整 HTTP API 清单（实测枚举，无书架/下载）

以下为 node `server.js` 全部路由，按用途分组：

- 账号：`POST /api/register`、`POST /api/login`、`POST /api/me`、`POST /api/logout`、`POST /api/change-password`、`POST /api/change-username`、`POST /api/delete-account`
- 资料/状态：`POST /api/avatar`、`POST /api/group-avatar`、`POST /api/set-title`、`POST /api/set-group-title`、`POST /api/set-status`、`GET  /api/site-status`（公开、CORS:*，返回 `{ok,maintenance,maxOnline,hallName}`）
- 群/好友：`GET /api/search-groups`、`POST /api/group-apply-list`
- 管理：`GET /api/admin/users`、`POST /api/admin/ban`、`POST /api/admin/broadcast`、`POST /api/admin/delete-user`
- 上传/媒体：`POST /api/upload-msg-image`、`POST /api/upload_chunk`、`POST /api/merge_chunks`、`POST /api/upload-file`、`POST /api/upload-audio`
- 社交/AI：`POST /api/moment-post`（朋友圈）、`POST /api/ai-chat`
- 静态资源：`/`（前端）、`/avatars`、`/moments`、`/msgimg`、`/files`、`/audio`
- WebSocket：`/ws`

**没有** `/api/bookshelf`、没有任何 `download_url` / `task_id` / 小说列表 / TXT 下载端点。本机实测：

```
GET https://buer.kdns.fr/api/bookshelf  -> 404
```

> 旁证：聊天前端对"摩柿"唯一的引用是一个 `window.open('https://morax.kdns.fr')` 外链按钮——只是跳转到小说站，**不是 API 调用**。摩柿是另一套独立部署（不在本机 cloudflared/nginx 配置内）。

---

## 5. qgs 服务器监控接口（ESP"服务器监控"屏幕用）

ESP 固件里"聊天站 / 小说站"两个监控位，轮询的是**主机健康探针**，**与书无关**。

- 实现：`python3 /opt/server_monitor.py`，监听 `0.0.0.0:18080`。
- 端点：`GET /api/status`（与 `/monitor` 同 handler）。
- 经 nginx 对外：
  - `GET https://buer.kdns.fr/api/status` —— **无认证**，给 ESP 轮询；
  - `GET https://buer.kdns.fr/monitor` —— 带 HTTP Basic Auth（人工看的仪表盘）。
- 固件侧 URL 拼法：`http://<配网页填入的主机>:<端口>/api/status`（明文 HTTP，主机:端口在配网页运行时写入 EEPROM，出厂为空）。

响应 JSON（本机实测样例字段）：
```json
{
  "cpu": 0.0, "cpu_usage": 0.0,
  "memory": 17.7, "memory_total": 1.92, "memory_used": 0.34,
  "disk": 17.0, "disk_total": 29.4, "disk_used": 5.0,
  "network_in": 0.0, "network_out": 0.0,
  "processes": 88, "uptime_hours": 825,
  "time": "19:13:49", "status": "running"
}
```
（另有 `mem/memory_usage/mem_usage`、`disk_usage`、`net_in/net_out`、`process_count/uptime`、`mem_detail`、`disk_io` 等兼容别名。）

"小说站"那个监控位，只是把上面这套主机探针指向摩柿所在服务器的同一类 `server_monitor.py` 实例——监控的是**那台机器的 CPU/内存**，不是书架。

---

## 6. 接入手环（小米手环 9 Pro / Vela QuickApp）的可行路径建议

### 6.1 小说阅读（核心诉求）
- **不要用虚空终端当书源**。它没有书架/下载 API。
- 继续用已验证的摩柿后端：`POST https://morax.kdns.fr/api/login`（取 session Cookie）→ `GET /api/bookshelf`（`items[].title/author/download_url/task_id]`）→ `GET download_url`（TXT）。QuickApp 用 HTTPS 请求即可，与虚空终端无关。

### 6.2 如果还想在手环上加"虚空终端聊天"
前提是 QuickApp(Vela) 能发起 **WSS + 原生 TCP/TLS**。分两种情况：

1. **Vela 支持 WebSocket**：可直连。流程 = `POST /api/login` 拿 token → 连 `wss://buer.kdns.fr/ws`（不带 Origin 也放行）→ 发 `{"type":"auth","token":...,"lite":true}` → 收 `hello` 与 `global/dm/group` 推送 → 回 pong。注意：
   - 不要硬 pin TLS 指纹（Cloudflare 证书会轮换）；
   - 手环屏幕小、实时双向聊天体验差，建议只做"大厅最近消息只读 + 手动刷新"这类轻量展示，而不是完整 IM。
2. **Vela 不支持裸 WebSocket**：做**手机端桥接**。在手机上跑一个Companion/本地代理，由它持有 WSS 长连与 auth，对外暴露简单 HTTP 轮询接口（如 `/latest` 返回最近 N 条大厅消息）给手环拉取。前提：手机 App 需要常驻运行、与手环同局域网/或走公网中转。

### 6.3 不要做的事
- 不要指望从 buer 拉小说——端点不存在，实测 404。
- 不要把 ESP 固件里 pin 的 Cloudflare 证书指纹照搬到手环做固定校验。
- 监控探针 `/api/status` 是明文、无认证的主机健康数据，不能、也不需要用于手环业务。

---

## 附：调研方法与可信度
- 客户端：通读 `src/config.h / chat.cpp / chat.h / monitor.cpp / wifi_config.cpp / app_state.cpp / README.md`。
- 服务端：只读侦查——`ss -tlnp`、`ps aux`、nginx vhost（`/etc/nginx/sites-available/chat-app`）、cloudflared ingress（`/etc/cloudflared/config.yml`）、node 服务源码 `/opt/chat-app/server.js` 路由全量枚举、监控脚本 `/opt/server_monitor.py`、本机 `curl` 实测 `/api/site-status`、`/api/status`、`/api/bookshelf`。
- 全程未修改服务端任何状态、未创建账号、未下载书籍内容。
