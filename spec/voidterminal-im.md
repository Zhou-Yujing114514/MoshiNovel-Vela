# 虚空终端 IM 客户端协议规格（字段级 · 手环/手机桥两端共用）

> 产出日期：2026-09-30
> 唯一事实来源：ESP8266 客户端源码 `VoidTerminal-ESP8266/src/chat.cpp`（1439 行，全文通读）+ `chat.h` + `input.cpp/input.h` + `app_state.cpp`。
> 基座：`voidterminal-api.md`（服务端路由/地址/TLS）。**凡本文与基座冲突，以本文（chat.cpp 实际行为）为准，冲突点已在 §11 标注。**
> 用途：小米手环 9 Pro（Vela QuickApp，无 WSS）经手机端桥接 App 持有 WSS 长连，手环经 `system.interconnect` 收发。两端按本文件同一份字段级规格实现。

---

## 0. 总览：一条连接的完整时序

```
[手环] --interconnect--> [手机桥] --WSS--> [buer.kdns.fr:443/ws]

1. 手环: action=login {username,password}
2. 手机桥: POST https://buer.kdns.fr/api/login  {username,password}
3. 服务端: 200 {ok:true, token:"<opaque>", user:{...}}
4. 手机桥: 存 token；连 wss://buer.kdns.fr/ws
5. 手机桥: 连接建立后立即发 {"type":"auth","token":"<token>","lite":true}
6. 服务端: {"type":"hello", self, friends[], groups[], globalMsgs[], ...}
7. 手机桥: 把 hello 归一化后经 interconnect 推给手环（会话列表初始化）
8. 服务端: 持续推 {"type":"global"|"dm"|"group", ...}
9. 手环: action=send {convId, convType, content}
10. 手机桥: 翻译成对应 WS 报文发出去；本地立即回显
11. 保活: 服务端发 WS ping → 手机桥必须回 pong；手机桥每 20s 发一次客户端心跳 ping
12. 断线: 5s 后重连 → 重连成功后自动重发 auth(lite:true)
```

---

## 1. 连接与 TLS（手机桥侧）

| 项 | 值 | 代码依据 |
|---|---|---|
| WSS 地址 | `wss://buer.kdns.fr:443/ws` | `connectWebSocket()` → `beginSSL(CHAT_SERVER, CHAT_PORT, "/ws", ...)`；`CHAT_SERVER="buer.kdns.fr"`, `CHAT_PORT=443` |
| Origin 头 | ESP 显式设 `Origin: https://buer.kdns.fr` | `setExtraHeaders("Origin: https://buer.kdns.fr")`。手机桥用原生 WS 客户端时**可带可不带**（基座实测：不带 Origin 服务端 `verifyClient` 直接放行）；带上更稳。 |
| TLS 校验 | **不要照抄 ESP 的硬证书指纹** | ESP pin 死 `1C:86:...:E1` 是因为它没有系统 CA 库；buer 走 Cloudflare，边缘证书会轮换。手机桥用系统 TLS 信任链正常校验域名即可。 |

---

## 2. HTTP 登录（手机桥侧，手环只传账号密码）

```
POST https://buer.kdns.fr/api/login
Content-Type: application/json

{"username":"<账号>","password":"<密码>"}
```

成功（200）：
```json
{ "ok": true, "token": "<opaque-token>", "user": { "id": "<uid>", "username": "<昵称>" } }
```

**ESP 实际只取 JSON body 里的 `token` 字段**（`resp["token"]`），不读 `Set-Cookie`。手机桥据此实现：

- 登录成功判定 = `httpCode==200 && body.token 非空`（`chat.cpp` login() L1104-1116）。
- 登录失败：ESP 只把 HTTP 状态码显示在诊断屏，**不区分 401/403/503**（L1125-1131 仅 `登录HTTP=<code>`）。手机桥应把状态码 + 响应体 `error` 文案回传手环上屏（401=用户名或密码错误，403=已被封禁）。
- token 长度：ESP 缓冲区 `_token[64]`，截断到 63。手机桥侧 token 按字符串存储，无硬长度假设。

---

## 3. 客户端 → 服务端（出站）报文

### 3.1 auth（WS 握手成功后立即发，仅发一次）

```json
{ "type": "auth", "token": "<登录拿到的 token>", "lite": true }
```

- 代码 `sendWsMessage("auth", nullptr, nullptr)`（L941）。
- `sendWsMessage` 的通用构造（L889-901）：`type` 必填；`to`/`content` 仅在非空时带；**只要 `_token[0]` 非空就额外附 `token` 字段**；**仅当 `type=="auth"` 时附 `lite:true`**。
- 注意：`sendWsMessage` 在 ESP 里**只被 auth 调用**；聊天消息走 `sendChatMessage()`（见下），**不带 token、不带 lite**。

### 3.2 发送聊天消息（`sendChatMessage`，L903-919）

按会话类型分三种，**都不带 token**（连接已在 auth 时绑定 uid）：

| 会话类型 | 出站报文 |
|---|---|
| 大厅/公共（CONV_PUBLIC） | `{"type":"global","content":"<文本>"}` |
| 群聊（CONV_GROUP） | `{"type":"group","gid":"<会话id>","content":"<文本>"}` |
| 私聊（CONV_PRIVATE） | `{"type":"dm","to":"<对方uid>","content":"<文本>"}` |

- 群：`gid` = 会话 `id`（来自 hello.groups[].id）。
- 私聊：`to` = 对方 uid（来自 hello.friends[].id，或对方私聊消息里的对端 id）。
- 消息体上限见 §6。

---

## 4. 服务端 → 客户端（入站）报文 —— 字段级接收表

### 4.0 解析总行为（关键！）

ESP 用 ArduinoJson **filter** 只解析以下 8 个字段（L950-959），其余字段全部丢弃：

```
filter["type"], filter["self"], filter["friends"], filter["groups"],
filter["from"], filter["fromName"], filter["content"], filter["to"], filter["gid"]
```

> **`time` / 时间戳、`avatar`、`title`、`status`、`online`、`gname` 等一律不在 filter 内，ESP 从不读取。** 手机桥若要展示时间/头像需自行放宽解析，但**协议基线只承诺上表字段**；手环端不要假设收到 `time`。

分发逻辑（L966-978）：只认 `type == "hello" | "global" | "dm" | "group"` 四种；**其余 type 一律静默忽略（无 else 分支）**。

### 4.1 hello（auth 成功后服务端下发一次）

ESP 实际读取的字段（`handleHelloMessage` L985-1024）：

| 字段路径 | 类型 | ESP 读法 | 用途 |
|---|---|---|---|
| `self.id` | string | `self["id"]`，非空则存入 `_userId` | **判定"是不是我自己发的消息"的基准 uid** |
| `self.username` | string | `self["username"]`，非空存 `_userName` | 本端昵称，本地回显用 |
| `friends[]` | array | 遍历 | 初始化私聊会话列表 |
| `friends[].id` | string | `friendObj["id"]` | 私聊会话 id（=对方 uid） |
| `friends[].name` | string | `friendObj["name"]` | 私聊会话显示名 |
| `groups[]` | array | 遍历 | 初始化群聊会话列表 |
| `groups[].id` | string | `groupObj["id"]` | 群会话 id（=gid） |
| `groups[].name` | string | `groupObj["name"]` | 群会话显示名 |
| `globalMsgs[]` | array | 遍历（lite 模式下服务端截断为最近 10 条） | 大厅历史消息回填 |
| `globalMsgs[].from` | string | `msgObj["from"]` | 发送者 uid |
| `globalMsgs[].fromName` | string | `msgObj["fromName"] \|\| from` | 发送者昵称；缺省回退 from |
| `globalMsgs[].content` | string | `msgObj["content"]` | 文本内容 |

> ESP **不读** `maxOnline / isAdmin / hallName / maintenance / announcement / groupAnnouncements / pendingRequests`（基座 §3.2 列了，但 ESP 不解析）。`self` 只读 `id`+`username`，其余（头像等）丢弃。

### 4.2 大厅推送 global（`handleGlobalMessage` L1026-1041）

| 字段 | 读法 | 说明 |
|---|---|---|
| `type` | 必须为 `"global"` | |
| `from` | `root["from"]` | 发送者 uid |
| `fromName` | `root["fromName"] \|\| from` | 昵称，缺省回退 from |
| `content` | `root["content"]` | 文本 |

- **大厅推送不带任何路由 id**（无 gid/to）——所有 global 消息一律归入硬编码的第 0 个会话（`id="public"`）。
- `isMe = (from == _userId)`（与 hello.self.id 比较）。

### 4.3 私聊推送 dm（`handleDmMessage` L1043-1058）

| 字段 | 读法 | 说明 |
|---|---|---|
| `type` | 必须为 `"dm"` | |
| `from` | `root["from"]` | 发送者 uid |
| `fromName` | `root["fromName"] \|\| from` | 昵称 |
| `content` | `root["content"]` | 文本 |
| `to` | `root["to"]` | 接收者 uid |

- **对端 id 判定**（L1052）：`peer = isMe ? to : from` —— 即"from/to 中不等于自己的那一个"。
- 路由到哪个私聊会话 = `peer`（拿它去匹配会话表 `conv.id`）。

### 4.4 群聊推送 group（`handleGroupMessage` L1060-1073）

| 字段 | 读法 | 说明 |
|---|---|---|
| `type` | 必须为 `"group"` | |
| `from` | `root["from"]` | 发送者 uid |
| `fromName` | `root["fromName"] \|\| from` | 昵称 |
| `content` | `root["content"]` | 文本 |
| **`gid`** | `root["gid"]` | **群标识，路由关键字段** |

### 4.5 群消息路由结论（手环按房间路由的依据）

> **群推送确实带 `gid` 字段**（L1064 `root["gid"]`）。ESP 用它匹配 `conv->id == gid` 决定归属哪个群会话。
>
> **但 ESP 的路由是"只在打开的那个群里才显示"**：`handleGroupMessage` 仅当"当前正在看的会话就是这个 gid 群"且处于聊天页时才 `addMessage` 上屏；**没打开的群消息 ESP 直接丢弃，既不缓存也不计未读**（L1066-1072 无 else 分支）。
>
> 对手桥两端的含义：
> - **手机桥必须自行维护"群 id → 群会话"映射**（来自 hello.groups[]），收到 group 推送按 `gid` 投递给对应会话，并**主动累计未读**（ESP 没做，手机桥要补齐，否则手环看不到未读提醒）。
> - 手环端会话表 key 就是 `gid`；私聊会话 key 是对端 uid（peer）；大厅固定 key `"public"`。

---

## 5. 会话列表模型（ESP 的视图组织，供手环复刻）

### 5.1 会话结构（chat.h L23-29）

```
Conversation {
  char id[32];     // 会话 id：大厅固定 "public"；群= gid；私聊=对方 uid
  char name[32];   // 显示名
  ConvType type;   // 0=CONV_PUBLIC 大厅 / 1=CONV_GROUP 群 / 2=CONV_PRIVATE 私聊
  int  unread;     // 未读数
  bool valid;
}
```

### 5.2 会话列表如何初始化（`init()` + `handleHelloMessage`）

1. `init()` 硬编码插入第 0 个会话：`{id:"public", name:"网站问题反馈区", CONV_PUBLIC}`（L469）。
2. 收到 hello 后：遍历 `friends[]` 追加私聊会话（`addConversation(fid, fname, CONV_PRIVATE)`）；遍历 `groups[]` 追加群会话（`addConversation(gid, gname, CONV_GROUP)`）。
3. `addConversation`（L490-507）：按 `id` 去重——已存在则只更新 `name`；不存在则追加，**上限 `MAX_CONVERSATIONS = 5`**（超过直接丢弃）。
4. 顺序：大厅永远在 index 0，其后是好友、群（按 hello 数组顺序）。

### 5.3 列表每行展示什么（`drawConversationList` L583-627）

- 行格式：`[公]/[群]/[私]` 图标 + 会话名 + （未读数 >0 时追加 `(n)`）。
- **不展示"最后一条消息预览"**（ESP 会话结构里根本没存 lastMessage）。
- 底部状态栏：`已连接 | <userName>` 或 `未连接`。

### 5.4 增量消息如何更新会话（重要限制）

- ESP **只有一个当前消息缓冲** `_messages[5]`（`MAX_MESSAGES_PER_CONV=5`），只存"正在看的那一个会话"的消息。
- 切换会话时 `clearCurrentMessages()`（`messageCount=0`）——**离开的会话历史不保留**，切回去是空的。
- 未读计数**只对大厅生效**：在列表页收到 global → `_conversations[0].unread++`（L1037）。
- **群/私聊在列表页收到消息时 ESP 完全不计未读、不缓存**（见 §4.5）。
- 消息缓冲满 5 条时，新消息顶掉最老一条（FIFO 滑动窗口，L517-522）。

> **手环/手机桥要做得比 ESP 好**：手机桥应给每个会话维护独立的最近 N 条消息缓冲 + 独立未读计数（尤其群和私聊），手环切会话时手机桥再把该会话的最近消息推过来。不要照搬 ESP"只存当前会话 5 条"的极简模型。

---

## 6. 发送：长度限制 / 空内容 / 本地回显 / 失败

| 项 | ESP 实际行为 | 代码依据 |
|---|---|---|
| 输入缓冲上限 | `_inputBuffer[64]`，实际可用到 **63 字节**（`confirmCandidate` 判 `len+candLen < 63`） | L91-92, L1230 |
| 单条消息存储 | `ChatMessage.content[64]`，入库截断到 63 字节 | L35, L527-528 |
| 拼音串上限 | `_pinyin[32]`，追加判 `<30` | L88, L1147 |
| 空内容 | **发送按钮在 `inputBufferLen==0` 时置灰不可点**；`sendChatMessage` 仅在 `_inputBufferLen>0` 时调用 → 空串发不出去 | L766, L1366 |
| 发送后本地回显 | **立即本地回显，不等服务端 ack**：先发 WS，再 `addMessage(_userId, _userName, _inputBuffer, isMe=true)` | L1369-1371 |
| 失败处理 | `sendChatMessage` 开头 `if(!_wsConnected) return;` 静默丢弃；**无重试、无失败提示、无重发队列** | L904 |

> 手环端建议：空内容禁止发送（同 ESP）；本地回显立即上屏；手机桥发送时若 WS 未连，应回 `{ok:false}` 给手环上屏提示"未连接"，不要像 ESP 那样静默吞掉。

---

## 7. 心跳 / 重连 / 重认证完整时序

| 行为 | ESP 实际值 | 代码依据 |
|---|---|---|
| 服务端 WS ping | 收到 `WStype_PING` 必须回 `sendPong(payload, length)`，否则被服务端踢 | L923-927 |
| 客户端主动心跳 | `enableHeartbeat(20000, 10000, 3)`：**每 20s 发一次 ping**，等 pong 超时 10s，连续 3 次失败判定断线 | L879 |
| 库自动重连 | `setReconnectInterval(5000)`：断线后 **5s** 自动重连 | L877 |
| 应用层兜底重连 | `update()` 里：若 `!_wsConnected` 且距 `_lastReconnectTime > 5000ms` → 再调 `connectWebSocket()` | L1425-1429 |
| 重连后重认证 | 收到 `WStype_CONNECTED` 事件 → 若 `_token[0]` 非空，立即 `sendWsMessage("auth",...)`（即重新发 `{type:auth, token, lite:true}`） | L934-946, L938-941 |
| 重连后无 token | 若 token 已失效/无账号，`connectWebSocket()` 会先尝试用 EEPROM 里存的账号重新 HTTP 登录拿新 token，再连 WS | L856-867 |

**完整时序**：断线(`WStype_DISCONNECTED` → `_wsConnected=false,_loggedIn=false`) → 5s 后 `connectWebSocket()` → TLS+WS 握手 → `WStype_CONNECTED` → 若有 token 立刻 auth(lite:true) → 服务端回 hello → 会话列表重建。**全程无需手环手动干预**，手机桥应照此自动完成。

---

## 8. auth 异常（error / banned / maintenance）

> ⚠️ **与基座规格的重要差异（以 chat.cpp 为准）**：
> 基座 §3.1 说服务端认证失败会回 `{"type":"error","error":"认证失败"}`、封号回 `{"type":"banned"}`、维护回 `{"type":"maintenance"}`。**但 ESP 客户端在 `handleWsEvent` 的 TEXT 分支只分发 hello/global/dm/group 四种，对 error/banned/maintenance 没有任何分支——这些报文被静默忽略**（L968-978 无 else）。
>
> 也就是说：ESP 端遇到 token 失效被服务端踢，表现只是"WS 断开"，靠 §7 的 5s 重连+重新 HTTP 登录恢复，**并不解析这些错误体**。

手机桥/手环实现建议（ESP 没做但应该做）：

| 服务端报文 | 含义 | 手机桥应处理 |
|---|---|---|
| `{"type":"error","error":"..."}` | token 无效/认证失败 | 清除本地 token，回手环 `{ok:false, code:"AUTH_FAIL", message:error}`，触发重新登录 |
| `{"type":"banned", ...}` | 账号被封 | 停止重连，回手环提示"账号已被封禁" |
| `{"type":"maintenance", ...}` | 服务维护中 | 回手环提示"维护中"，延长重连间隔 |

HTTP 登录层的异常 ESP 是认的：401=用户名密码错、403=封号、503=维护（见 §2）。

---

## 9. 除 global/group/dm 外客户端处理的推送类型

**结论：ESP 客户端一个都不处理。**

- 入站分发只认 `hello / global / dm / group` 四种（L968-978）。
- filter 里也没有 `system / avatar / title / status / groupApply / online / announcement` 等字段。
- 因此：系统广播、头像更新、头衔/称号更新、入群申请、在线人数变动、朋友圈、AI 对话结果等推送，**ESP 全部丢弃，字段未知**。

> 手机桥 v1 也无需处理这些；若未来要做，需另读服务端 `server.js` 确认字段，不要从本规格臆造。

---

## 10. ESP 输入法设计（手环输入方案参考）

> 注：真正的中文拼音输入法逻辑全部在 `chat.cpp` 里（`input.cpp/input.h` 只是物理按键消抖/长短按状态机）。

### 10.1 按键模型（input.cpp）
- 三个物理键：Menu(键1)/Up(键2)/Down(键3)。
- 事件：短按 / 长按(>800ms) / 双击(300ms 窗口内再按)。短按延迟确认（等双击窗口），长按立即触发。
- 常量：`KEY_DEBOUNCE_MS=50`，`KEY_LONGPRESS_MS=800`，`KEY_DOUBLECLICK_MS=300`（config.h）。

### 10.2 十二宫格键盘（chat.cpp L13-27, L778-818）
- 9 个字母格：`[.,!?] abc def ghi jkl mno pqrs tuv wxyz`（对应数字 1-9）。
- 第 10 格=删，第 11 格=空格，第 12 格=发送。

### 10.3 拼音→候选→上屏流程（L1146-1241）
1. 选字母格 → 进入字母选择器（该格多字母左右选）→ 长按确认把字母追加到 `_pinyin`。
2. 每加一个字母触发 `loadCandidates()`：
   - **先全拼精确匹配** `candidateTable`（如 `zhong`→中/重/种…），命中取 8 个候选；
   - 没命中或只输了 1 个字母 → **首字母前缀匹配**（如输入 `z` → 所有 z 开头拼音的首字），去重后凑 8 个；
   - 仍无 → 候选就显示拼音串本身。
3. 长按切换候选 `[1] 2 3...`，选中后 `confirmCandidate()` 把字追加到 `_inputBuffer`（上限 63 字节），清空拼音继续。
4. 删字：优先删拼音；拼音空了才删已上屏文字，**UTF-8 安全删**（中文 3 字节整体删，L1243-1261）。

### 10.4 词库
- `candidateTable`（L35-431）约 **300 组全拼单字**，每组 8 个候选，**全是单字，没有词组**。
- **没有任何硬编码的快捷短语/预设回复/常用语句列表。**

### 10.5 对手环输入方案的建议
手环屏幕比 ESP 还小、且是触控，不建议照搬"十二宫格+拼音"（输入成本极高）。推荐：
1. **快捷回复/预设短语优先**：内置一组高频短句（如"收到""好的""在忙，稍后回""哈哈""谢谢"），一键发送——这是 ESP 没做但最适合手环的。
2. **手机代输、手环只收发**：长文本在手机桥 App 里编辑好，手环点发送；手环端只做"选会话+点快捷短语+看消息"。
3. 若确需手环自己打字，用系统 `system.prompt` 弹窗输入或拼音候选，**候选词改为"单字+高频词组"两级**，不要照抄 ESP 的 300 组单字表。

---

## 11. 与基座 `voidterminal-api.md` 的差异标注

| # | 基座说法 | chat.cpp 实际 | 采信 |
|---|---|---|---|
| 1 | 服务端会回 error/banned/maintenance 并需处理 | ESP 不解析这三种 type，静默忽略，靠断线重连+重新登录兜底 | **以 chat.cpp 为准**；手机桥可额外做（§8） |
| 2 | hello 含 maxOnline/isAdmin/hallName/maintenance 等 | ESP 只读 self.id/username、friends、groups、globalMsgs | 字段可能存在但客户端不消费；手机桥勿依赖 |
| 3 | 推送 global/dm/group 含 `...`（省略号） | group 多带 `gid`、dm 多带 `to`，这两个是路由关键字段；`time` 客户端不读 | 补全路由字段（§4） |
| 4 | 客户端 20s 心跳、断线 5s 重连、重连重 auth | 全部证实（`enableHeartbeat(20000,10000,3)` / `setReconnectInterval(5000)` / CONNECTED 即 auth） | 一致 |
| 5 | lite:true 截断 globalMsgs 为最近 10 条 | 证实（L897 仅 auth 带 lite；hello.globalMsgs 回填） | 一致 |

---

## 12. 推荐 v1 手环实现子集

### 12.1 必做（最小可用闭环）
1. 登录：手环传 `{username,password}` → 手机桥 POST `/api/login` 拿 token → 存 `session.void`。
2. 建连：手机桥连 WSS → 发 `{type:auth, token, lite:true}` → 收 hello。
3. 会话列表：手机桥按 hello 生成 `public` + friends(私聊) + groups(群) 列表推给手环。
4. 大厅收发：发 `{type:global,content}`；收 global 归到 `public` 会话。
5. 群收发：发 `{type:group,gid,content}`；收 group 按 `gid` 路由。
6. 私聊收发：发 `{type:dm,to,content}`；收 dm 按 `peer=isMe?to:from` 路由。
7. 保活：回 pong；20s 心跳；断线 5s 重连；重连自动重 auth。
8. 本地回显 + 空内容禁发（§6）。

### 12.2 可裁剪 / v2 再做
- 群/私聊的未读角标（ESP 都没做，手机桥自补）。
- 每会话独立历史缓存（ESP 只留当前会话 5 条）。
- error/banned/maintenance 的专门 UI（§8）。
- 头像、头衔、在线人数、系统广播、入群申请、朋友圈、图片/文件/语音（ESP 一律不支持）。
- 历史消息翻页（lite 只给最近 10 条大厅，无分页协议）。

### 12.3 lite 历史消息的确认与影响
- `lite:true` 时 `globalMsgs` = **最近 10 条**（基座+代码注释 L896 确认）。
- 影响：
  - 手环打开大厅只能看到最近 10 条，**没有更早历史、没有上拉加载**。
  - **群和私聊在 lite hello 里不带任何历史消息**（ESP 的 hello 解析里 friends/groups 只有 id+name，没有群/私聊历史）→ 手环进群/私聊只能看"上线之后的新消息"。
  - ESP 还有个限制：这 10 条大厅历史**只在 hello 时刻你正停在 public 会话时才灌进缓冲**（L1019 判断 `_selectedConv==0`）；手机桥应无条件把这 10 条作为 public 会话的初始历史推给手环，不要复刻这个时机判断。

---

## 13. 与摩柿（MoshiNovel）架构的复用点

### 13.1 存储 key（沿用 architecture.md §4 已冻结键）

| 用途 | storage key | 内容 | 备注 |
|---|---|---|---|
| 虚空账号 | `account.void` | JSON `{username, password}` | 已冻结，直接复用 |
| 虚空会话 | `session.void` | **存登录返回的 JSON `token` 字符串** | 注意：ESP 取的是 body.token，不是 Set-Cookie 的 session。手机桥拿这个 token 去连 WSS 发 auth。 |
| 活动服务 | `active_service` | `"moshi" \| "void"` | 已冻结 |

> 与摩柿的差别：摩柿登录用 session Cookie 做后续 HTTP 鉴权；虚空终端的 token 是喂给 **WSS auth 报文**用的（HTTP 业务 API 才用 Cookie）。手机桥长连持有这个 token 即可，手环侧不直接碰 token。

### 13.2 与现有 store/api 的衔接
- `common/api.js` 的 `SERVICES.void`（architecture.md §7）目前 `backend:'NONE'`；本次把它落地为：所有虚空聊天动作走 `action:'http'`（登录）+ 新增 `action:'chat'`（手机桥持 WSS）两类，service 字段仍传 `"void"`。
- 建议在 interconnect 信封（architecture.md §3）新增聊天类动作：
  - watch→phone：`{action:'chat', payload:{op:'login'|'open'|'send'|'close', convId?, convType?, content?}}`
  - phone→watch 推送：`{kind:'chat_event', payload:{event:'hello'|'msg', convId, convType, from, fromName, content, isMe, unread?}}`
- `common/store.js` 已冻结的 `getAccount/setAccount(svc)` 直接用；新增会话/消息缓存建议落在手机桥侧，手环侧只缓存"当前会话最近 N 条"。
- manifest 已声明 `system.interconnect / system.storage / system.vibrator / system.prompt`，聊天页可复用，无需新增权限。

---

## 附：关键代码定位索引（供两端实现对照）

| 行为 | chat.cpp 位置 |
|---|---|
| auth 报文构造 | L889-901 `sendWsMessage` |
| 发 global/group/dm | L903-919 `sendChatMessage` |
| ping→pong | L923-927 |
| CONNECTED→发 auth | L934-946 |
| 入站 filter 字段 | L950-959 |
| type 分发（仅4种） | L966-978 |
| hello 解析 | L985-1024 |
| global 路由 | L1026-1041 |
| dm 路由（peer 判定） | L1043-1058 |
| group 路由（gid 判定） | L1060-1073 |
| HTTP 登录取 token | L1075-1134 |
| 心跳/重连参数 | L877-879, L1425-1429 |
| 会话初始化（public 硬编码） | L469 |
| 发送后本地回显 | L1369-1371 |
