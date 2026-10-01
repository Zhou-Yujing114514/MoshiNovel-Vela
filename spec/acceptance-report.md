# 摩柿小说 · 手环9Pro QuickApp 工程独立验收报告

> 验收日期：2026-09-30
> 验收对象：`/home/user/Doubao/chats/38442278710203394/MoshiNovel-Vela/`
> 基准：`spec/architecture.md`（§3 桥接协议 / §10 实现级冻结 / §10.7 分工表）、`spec/vela-capability.md`、`spec/voidterminal-api.md`
> 职责说明：本报告只挑错、不改工程；所有问题交由主任务安排修复。

---

## 一、结论总览

| 验收项 | 结论 | 说明 |
|---|---|---|
| A. 文件清单完整性 | **PASS** | §10.7 + 骨架清单所列文件全部到位 |
| B. JS 语法与禁项 | **FAIL** | 语法全过、无真禁项；但 `common/reader.js` 两条相对 import 断链（构建级致命） |
| C. ux 结构 | **PASS** | 6 个页面三段式齐全、单根、无裸文本、无 `<button>`、CSS 仅类/标签选择器、import 均在 `<script>` 内 |
| D. 接口契约一致性 | **PASS**（1 处轻微） | store/api/bridge 方法名签名齐全；路由/features 匹配；仅 reader.ux 有一条未使用的默认导入（轻微） |
| E. 协议一致性（watch↔phone） | **PASS** | 信封/响应/推送/分片 4000/eof/progress/http 字段两端一致 |
| F. 凭据与敏感信息扫描 | **PASS** | 无 SSH 主机/端口/root/密码；无硬编码摩柿账号；README 仅自述 |
| G. 逻辑抽查 | **PASS** | 分页算法、首帧覆盖写、删除二次确认均与冻结规格一致 |

**总体结论：不通过（1 项致命 FAIL，集中在 B）。**
核心阅读链路 `reader.ux → reader.js` 因 `reader.js` 内部两条相对 import 路径错误而无法解析依赖，整包大概率编译失败、Reader 页不可用。其余各项达标。

---

## 二、致命问题（必须修复）

### 【B-F1】`common/reader.js` 相对 import 断链，构建无法解析依赖

- 文件：`watch-app/src/common/reader.js:15-16`
- 现状：
  ```js
  15  import store from '../store.js'
  16  import { utf8Decode } from '../util.js'
  ```
- 问题：`reader.js` 本身位于 `watch-app/src/common/`。按 ES 相对路径解析，`../store.js` → `watch-app/src/store.js`，`../util.js` → `watch-app/src/util.js`。经文件系统核实：
  - `watch-app/src/store.js` **不存在**（实际模块在 `watch-app/src/common/store.js`）
  - `watch-app/src/util.js` **不存在**（实际模块在 `watch-app/src/common/util.js`）
- 后果：与同目录的 `api.js`/`bridge.js`（均用 `./` 引用同级模块）对比可见，此处 `../` 系笔误。AIoT-IDE/webpack 打包时会报 `Module not found: Can't resolve '../store.js'`，Reader 页（及整包）无法出包；即便侥幸运行，`store` 与 `utf8Decode` 均为 undefined，分页解码与 `setProgress` 全部失效。
- 建议修复方向（仅记录，不动手）：改为同级引用 `./store.js` 与 `./util.js`。

---

## 三、轻微问题（不阻断，建议顺手清理）

### 【D-M1】`pages/Reader/reader.ux` 导入了一个不存在的默认导出，且从未使用

- 文件：`watch-app/src/pages/Reader/reader.ux:81`
- 现状：`import util from '../../common/util.js'`
- 问题：`common/util.js` 仅为**命名导出**（`BOOKS_DIR/CHUNK_MAX/utf8Decode/...`），**没有 `export default`**，故 `util` 取到 `undefined`；全文件 grep 确认 `util.` 从未被调用（死导入）。
- 影响：路径本身可解析到存在文件，不致编译失败；但属于冗余/错误导入，建议删除该行。

### 【观察-1】`app.ux` 无 `<script>`/`<template>`/`<style>` 三段包裹

- 文件：`watch-app/src/app.ux`
- 现状：直接以裸 `export default { $def: {...}, onCreate(){...} }` 书写，无 `<script>` 标签。
- 说明：app.ux 为应用级入口，§10.7 明确其为主任务骨架、实现子任务只读不改；三段式规则（vela b.1）针对**页面** .ux，故不计入 C 项 FAIL。仅记录：若 AIoT-IDE 对 app.ux 也要求 `<script>` 包裹，需主任务确认骨架形态。

---

## 四、逐项明细

### A. 文件清单完整性 —— PASS

对照 §10.7 + 骨架清单逐项核对，全部存在：

- watch-app/src：`manifest.json`、`app.ux`、`app.css`、`common/{util,store,bridge,api,reader}.js`、`common/images/icon.png`、`pages/{Entry,Void,Login,Shelf,Library,Reader}/{…}.ux` —— 齐。
- watch-app：`package.json`、`sign/debug/README.md`、`sign/release/README.md` —— 齐。
- phone-bridge：`settings.gradle`、`build.gradle`、`gradle.properties`、`app/build.gradle`、`app/src/main/AndroidManifest.xml`、`app/src/main/java/com/moshinovel/{MoraxClient,BridgeService,WatchChannel,MainActivity}.java`、`README.md` —— 齐。

> 备注（不计 FAIL）：architecture §2 顶层曾提到仓库根 `README.md` 与 sign 目录下真实 `private.pem/certificate.pem`，本任务 A 清单未要求；当前 sign 目录仅 README 说明、无真实 pem（符合"占位+生成说明"定位）。

### B. JS 语法与禁项 —— FAIL

- `node --check`（.mjs 方式）：`util.js / store.js / api.js / bridge.js / reader.js` 五份 **全部 SYNTAX OK**。
- 禁项 grep（`require(` / `module.exports` / `process.` / `Buffer` / `__dirname`）：
  - 命中仅为 `reader.js` 注释与调用里的 `readArrayBuffer`、`ArrayBuffer`（Vela  sanctioned API，§10.4 明确使用 `file.readArrayBuffer`），**不是 node 全局 `Buffer`**，属误报。无 `require/module.exports/process./__dirname`。
- import 相对路径存在性：
  - `api.js → ./bridge.js / ./store.js`：均存在 ✓
  - `bridge.js → ./util.js`：存在 ✓
  - `reader.js → ../util.js / ../store.js`：**断链，见 B-F1** ✗
  - 页面 → `../../common/*.js`：均存在 ✓

### C. ux 结构 —— PASS

- 6 个页面（Entry/Login/Shelf/Library/Reader/Void）均**恰好各一对** `<template>/<style>/<script>`。
- 每个 `<template>` 单根节点（均为单个根 `<div class="page">`）。
- 无裸文本：可见文本均在 `<text>` 内（含 `{{text}}` 插值），注释为 HTML 注释。
- 全工程 **无 `<button>`**（按钮均为 `<input type="button">`）✓。
- CSS 选择器逐页核对：仅 `.cls` 类选择器，无后代 `.a .b`、子 `.a>.b`、伪类 `:hover`、通配 `*`、属性选择器。
- 所有 `import` 均位于各文件 `<script>` 内（行号均在 `<script>` 标签之后），未出现在 template/style 区。

### D. 接口契约一致性 —— PASS（含 D-M1）

- `store.js` 默认导出 11 个方法齐全且签名与 §10.3 一致：
  `getAccount/setAccount/getSession/setSession/getBookMeta/setBookMeta/delBookMeta/getProgress/setProgress/getActiveService/setActiveService`。
- `api.js` 导出 `SERVICES/login/fetchShelf/downloadBook/onDownloadProgress/pingBridge/httpRequest` 齐全：
  - login 成功后自动 `setSession` + `setActiveService`（`api.js:46-47`）✓
  - downloadBook 落盘命名 `bookKey = svc + '_' + taskId`（`api.js:81`）即 `{svc}_{taskId}` ✓
- `bridge.js` 导出 `getState/onStateChange/request/onProgress` 齐全；`request` 透传 action，api 侧覆盖 login/shelf/download/ping/http 五种 ✓
- 页面逐文件核对 import 的导出名：
  - entry/login/shelf/library 引用的 api/store/bridge 方法名全部存在 ✓
  - reader.ux 引用 `Reader.createReader`（reader.js 默认导出含 `createReader`）✓；`util` 默认导出问题见 D-M1。
- `manifest.json`：
  - `router.pages` = Entry/Login/Shelf/Library/Reader/Void，与 `pages/` 目录实际文件一一对应 ✓
  - features 声明 vs 实际 `@system.*`：实际用到 fetch/file/storage/interconnect/prompt/app/router；其中 **fetch/file/storage/interconnect/prompt 均已声明**，app/router 未声明（符合"无需声明"）；额外声明的 `system.device`/`system.vibrator` 当前未被使用，属无害冗余。✓

### E. 协议一致性（watch `bridge.js` ↔ phone `BridgeService.java`）—— PASS

- 请求信封：watch 发 `{v:1,id,service,action,payload}`（`bridge.js:339-345`），phone 读取 `id/service/action/payload`（`BridgeService.java:112-116`）✓
- 响应：phone `respondOk/respondError` 组 `{v:1,id,ok,code,message,payload}`（`BridgeService.java:294-314`），与 watch `handleMessage` 校验 `v/id/ok` 对齐 ✓
- 推送：phone `pushFrame` 组 `{v:1,id:"push-N",kind,payload}`（`BridgeService.java:334-341`），与 watch 按 `msg.kind` 分发对齐 ✓
- 分片：`CHUNK_MAX=4000`（phone `BridgeService.java:54` == watch `util.js:10`）；末片 `eof:true`（phone `:209`）→ watch 收 `eof` resolve（`bridge.js:165-175`）；progress 帧字段 `bookKey/percent/bytes/total` 两端一致 ✓
- http 动作：watch 发 `{method,url,headers,body}`（`api.js:180-185`），phone 同名字段解析（`BridgeService.java:236-243`）；phone 回 `{status,headers,body,truncated}`（`:274-284`），`HTTP_BODY_MAX=64*1024`（`:60`）即 64KB 截断并置 `truncated:true`；watch `bridgeHttp` 读 `status/headers/body`（`api.js:188-194`）。字段名完全一致 ✓

### F. 凭据与敏感信息扫描 —— PASS

- 全项目 grep：`cn-fj-qz-1`、`54188`、`3fwVqNYFLTDo` **零命中**。
- `root` 仅命中 `MoraxClient.java:179-180` 的局部变量 `JsonObject root`（JSON 根对象），非 SSH 用户名。
- 手机桥接代码无硬编码摩柿账号密码：`MoraxClient.login(username,password)` 由手环 payload 传入（`MoraxClient.java:140-142`），类注释亦声明不硬编码。
- `phone-bridge/README.md:8` 仅为"本仓库不含任何 SSH/账号凭据"的自述说明（按任务规则自述文字不算违规），正文无凭据。
- 签名口令均从 `~/.gradle/gradle.properties` 读取，仓库内只留占位注释，无明文口令。

### G. 逻辑抽查 —— PASS

- **reader.js 分页算法（对照 §10.4）**：`file.get` 取 length（`reader.js:53`）→ `store.getProgress` 恢复偏移（reader.ux:110-111）→ `readArrayBuffer` 窗口 4096B（`reader.js:19,142-145`）→ `utf8Decode` 多字节边界回退（`util.js:65-68`）→ 按 `\n` 切 `linesPerPage=12` 行（`reader.js:168-175`）→ `pageOffsets` 前进 push/后退 pop（`reader.js:100,113`）→ 每页一次 `store.setProgress`（`reader.js:156,187`）。与冻结规格一致。
- **bridge 首帧覆盖写**：`file.writeText({append: dp.written})`，`written` 初值 `false`（`bridge.js:369`），首帧 `append:false` 覆盖旧文件，写成功后置 `true`、后续 `append:true` 追加（`bridge.js:159-164`）。✓
- **shelf 删除二次确认**：`onDelClick` 首击置 `confirmDelKey=bookKey`、按钮文案变 `确认？`、起 10s 超时恢复（`shelf.ux:300-310`）；再击才真正 `file.delete`（`shelf.ux:313-337`）。已下载判定用 `file.access`（`shelf.ux:197-205`）。✓

---

## 五、修复优先级建议（供主任务排期）

1. **P0 阻断**：修复 `common/reader.js:15-16` 的 `../store.js` / `../util.js` → `./store.js` / `./util.js`，否则 Reader 页与整包无法出包/运行。
2. **P2 清理**：删除 `pages/Reader/reader.ux:81` 未使用且指向不存在默认导出的 `import util from '../../common/util.js'`。
3. **P3 确认**：主任务确认 `app.ux` 裸 `export default` 形态是否被 AIoT-IDE 接受（必要时补 `<script>` 包裹）。

---

## 六、拆分终验（2026-09-30，摩柿 / 虚空双工程独立交付）

> 背景：用户定案把原双服务工程拆成两个独立 QuickApp 工程分别交付——摩柿小说（本工程）与虚空终端（`VoidTerminal-Vela/`）。本节为拆分后的终验追加，**只验收、未改任何源码**。基准：本文件 §3（摩柿桥接协议）/ §11（vt_* 协议）、`voidterminal-im.md` 字段级规格。

### 6.1 两工程结论汇总

| 维度 | A 摩柿小说 (MoshiNovel-Vela) | B 虚空终端 (VoidTerminal-Vela) |
|---|---|---|
| watch-app 页面数 | **PASS** Entry/Login/Shelf/Library/Reader 恰好 5 页 | **PASS** Home/Login/VoidRooms/VoidChat 恰好 4 页 |
| common js | **PASS** util/store/bridge/api/reader 共 5 份，node --check 全过 | **PASS** util/store/bridge/api/phrases 共 5 份，node --check 全过 |
| manifest | **PASS** package=com.moshinovel / name=摩柿小说 / router=5 页 | **PASS** package=com.voidterminal / name=虚空终端 / features=storage·interconnect·prompt / router=4 页 / icon 已引用且存在 |
| 服务单一性 | **PASS** api.js 仅导出 login/fetchShelf/downloadBook/onDownloadProgress/pingBridge/httpRequest；bridge.js 仅 download_progress/download_chunk 两推送分支，无 vt_* | **PASS** api.js 仅 vt 系列（vtLogin/vtRooms/vtSend/vtQuit/vtAsr/onVtMessage/onVtStatus）；bridge.js 有 vt_message/vt_status 分流 |
| 跨工程残留 | **PASS** watch-app 无 Void/VoidRooms/VoidChat/phrases/vt_* 残留 | **PASS** watch-app grep moshi/morax/moshinovel 仅 1 处注释（见 N2） |
| ux 结构 | **PASS** 5 页三段式齐全、单根 `div.page`、文本均在 `<text>` 内、无 `<button>` | **PASS** 4 页同构、路由 component 与文件一一对应 |
| 协议一致性（§3/§11.2） | **PASS** 见 6.2 | **PASS** 见 6.2 |
| applicationId ↔ 手环 package | **PASS** phone-bridge applicationId=com.moshinovel == watch manifest | **PASS** phone-bridge applicationId=com.voidterminal == watch manifest |
| 凭据扫描 | **PASS** 无硬编码凭据（README 测试账号说明不计） | **PASS** 0 命中 |
| README 完整性 | **PASS** 单应用版，含功能表/联网通路/构建导入签名/配对/测试账号/限制/真机 POC | **PASS** 独立完整，含功能/联网通路/构建签名/配对/限制/真机 POC |

**总体结论：两工程均 PASS，可独立交付。** 上轮致命项【B-F1 reader.js 相对 import 断链】已修复（`common/reader.js:15-16` 现为 `./store.js` / `./util.js`）；上轮轻微项【D-M1 reader.ux 误导入 util】已删除（现 `pages/Reader/reader.ux:77-80` 仅 router/prompt/Reader/store）。无新增阻断问题。

### 6.2 协议一致性两端核对（手机桥 ↔ 手环）

- **摩柿动作（§3）**：手机桥 `BridgeService.handleRequest` 分支 `ping/login/shelf/download/http` 与手环 `bridge.js` 信封 `{v:1,id,service,action,payload}` 对齐；login 回 `{session,username}`、shelf 回 `{items:[{title,author,downloadUrl,taskId}]}`、download 走 `download_progress{bookKey,percent,bytes,total}` + `download_chunk{bookKey,seq,chunk,eof}`、http 回 `{status,headers,body,truncated}`（64KiB 截断）。帧封装 `respondOk/respondError/pushFrame` 字段两端一致。✓
- **虚空动作（§11.2）**：`vt_login`→回 `{token}`；`vt_rooms`→`roomsPayload()` 精确输出 `{self:{id,username}, rooms:[{key,type,name,lastMsg,unread}]}`；`vt_send`→`{ok}`；`vt_quit`→`{ok}`；`vt_asr`→`{text}`（系统 SpeechRecognizer，失败回中文 message）。推送 `vt_message{type,roomKey,from,fromName,content}`、`vt_status{state}`。roomKey 映射正确：大厅 `global`（服务端 `public` 由 phone 映射）、私聊 `dm:<peer>`、群 `group:<gid>`；dm 对端 `peer = (from==selfId)?to:from` 判定正确。出站 WSS 报文 global/group/dm 不带 token/lite，auth 带 `lite:true`，20s 心跳/5s 重连/重开即重 auth 均符合 im 规格 §3/§7。✓
- **两工程手机桥 Java 字节级一致**（`diff BridgeService/VoidTerminalClient/MoraxClient` 均 IDENTICAL），差异仅在各工程 `app/build.gradle` 的 `applicationId`。

### 6.3 非阻断问题清单（建议顺手清理，不影响交付）

| 编号 | 文件:行号 | 现状 | 修复建议 |
|---|---|---|---|
| N1 | `phone-bridge/.../BridgeService.java:537,544-546,550-552` 及两工程 `AndroidManifest.xml:26` | 因 companion 源码从摩柿拷贝，前台通知文案/通知渠道名/`android:label` 仍写死「摩柿桥接 / 摩柿小说桥接运行中」。虚空工程 companion 在手机上会显示成"摩柿"。**applicationId 配对不受影响**。 | 按 `applicationId` 分支或抽字符串资源：虚空 companion 显示「虚空终端桥接」。两工程各自改本工程副本。 |
| N2 | `VoidTerminal-Vela/watch-app/src/common/bridge.js:12` | 注释「…已删除摩柿 download 分片落盘特判」是虚空 watch-app 内唯一 "moshi" 字样（非功能代码）。 | 删除该历史注释，可严格满足"grep moshi=0"。 |
| N3 | `MoshiNovel-Vela/watch-app/src/common/store.js:77` | JSDoc 写 `svc 服务 key（moshi / void）`。摩柿单应用下 `void` 为死参数域，仅注释残留。 | 注释改为 `svc 服务 key（本应用固定 'moshi'）`。 |
| N4 | `VoidTerminal-Vela/phone-bridge/app/build.gradle:10` 及 `java/com/moshinovel/` 目录 | 虚空 companion 的 `namespace`/Java 包名仍为 `com.moshinovel`（applicationId=com.voidterminal 才是配对键，已正确；README §6.5 已说明）。 | 可选：把 Java 源目录改包为 `com/voidterminal/` 并同步 namespace，仅整洁性收益。 |
| N5 | `VoidTerminal-Vela/watch-app/src/common/images/icon.png` | 911 字节，疑似占位图。任务只要求"存在且被引用"（已满足）。 | release 前替换为正式 192×192 图标。 |

> 说明：N1–N5 均为 cosmetic/整洁性项，无一项破坏编译、协议或配对；本次终验据此判两工程 PASS。
