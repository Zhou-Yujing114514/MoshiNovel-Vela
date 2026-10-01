# 手环9Pro 网络层成熟实现参考报告（fetch 优先 + interconnect 兜底）

> 只读研究，基于三个社区仓库的实读代码提取，2026-09-30。
> 参考仓库（浅克隆，仅读网络层相关文件）：
> - A. `Searchstars/HyperBilibili`（手环端 B 站客户端，dev 包名 `com.searchstars.hyperbilibili.dev`，v2.5.0）
> - B. `Searchstars/HyperbiliInterconnect`（安卓端桥接 App，包名 `com.searchstars.hyperbilibili`）
> - C. `OnDriveLine/HyperBilibili_Band` 的 `band9` 分支（包名 `com.searchstars.hyperbilibili`，v2.9.9）

---

## 0. 一句话结论（先给答案）

**手环9Pro 上 `@system.fetch` 直连成立（实证）**：仓库 A 的 `manifest.json` 显式声明 `system.fetch`，`client.ts` 直接 `this.fetch = import from '@system.fetch'`，业务层 `await this.fetch.fetch({...})` 打 `https://api.bilibili.com` 等真实接口；仓库 C 的 `bilibiliclient.ts` 用同一套 `this.fetch.fetch({...})` 调用签名，构造器按 `interconnectMode` 布尔在「fetch 直连」与「interconnect 桥接」之间切换——这正是我们要的"fetch 优先 + interconnect 兜底"的成熟原型。**api.js 改造应采纳：① 统一 `fetch({url,method,data,header,responseType})` 抽象接口并按能力二选一注入实现；② Cookie 手动拼接 + 从 `response.headers['Set-Cookie']` 正则提取，不依赖自动 cookie jar；③ 启动时 `network.getType()` 探测，无网/无 fetch 能力时回退 interconnect 并提示"等待连接手机"。**

---

## 1. 各仓库实际形态确认

| 仓库 | 形态 | 包名 | 关键特征 |
|---|---|---|---|
| A. HyperBilibili | QuickApp/快应用（TS + ux），`src/manifest.json` + `src/bilibiliclient/` | `com.searchstars.hyperbilibili.dev` | manifest 声明了 `system.fetch`、`system.request`、`system.interconnect`、`system.network`、`system.storage`、`system.file`、`system.crypto`、`system.audio`、`system.vibrator`、`system.prompt`、`system.device` 全套；业务网络层 = `bilibiliclient/api/request.ts` |
| B. HyperbiliInterconnect | 原生 Android（Kotlin/Java Gradle 工程） | `com.searchstars.hyperbilibili` | 用小米穿戴 SDK `com.xiaomi.xms.wearable`；OkHttp 执行真实 HTTP；WebView + JSKit 做本地日志 UI |
| C. HyperBilibili_Band@band9 | QuickApp（TS + ux），与 A 同源但精简 | `com.searchstars.hyperbilibili` | **manifest 只声明 `system.router`**（其余靠运行时可用/未声明即用）；核心是 `src/bilibiliclient.ts`（单文件 575 行，含 fetch + interconnect 双模式）、`src/interconnecter.ts`、`src/interconnectfetch.ts`、`src/jumpcheck.ts` |

> 注意：C 仓库代码里 `import { interconnect, network, storage, fetch } from './tsimports'`，但 manifest 只列了 `system.router`。这说明 band9 部署包的 manifest 可能是"最小声明"（实际能力由设备/运行时决定），不代表这些 API 不可用。**我们自己的 manifest 仍应显式声明 `system.fetch`/`system.request`/`system.interconnect`/`system.network`/`system.storage`/`system.file`**，不要学 C 的精简。

---

## 2. a) fetch 调用最小可用写法

### 2.1 调用签名（A 与 C 完全一致，Promise 风格）

```js
// tsimports.js（A/C 共同）
import fetch from '@system.fetch'

// GET
const response = await fetch.fetch({
  url: 'https://api.bilibili.com/x/web-interface/nav',
  responseType: 'json',          // 可选；'json' 时返回已 parse 的对象
  header: this.getHeaders()      // 注意：QuickApp 字段是 header 不是 headers
});
// response.data  = 响应体（responseType:'json' 时是对象）
// response.headers = 响应头（Map/对象，含 Set-Cookie）

// POST
const response = await fetch.fetch({
  url: 'https://api.bilibili.com/x/v2/reply/add',
  method: 'POST',                // 不传默认 GET
  data: 'type=1&oid=...&csrf=...', // body，String（表单 urlencoded 或 JSON string）
  responseType: 'json',
  header: { ...this.getHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' }
});
```

**实证要点（全部来自代码）：**
- 字段名是 **`header`**（单数），不是浏览器 `headers`；`data` 是 **String**，不是对象。
- `responseType: 'json'` 时 Vela 自动 `JSON.parse`；不传则 `response.data` 是字符串。
- `await` 直接用——Vela 的 `@system.fetch.fetch()` 返回 Promise（而非 QuickApp 标准的 success/fail/complete 回调），代码全程 `await this.fetch.fetch(...)` 无回调包装。
- **错误处理**：`try { const r = await fetch.fetch(...) } catch(e) { console.error(e.code / e.message) }`。A 的 request.ts 捕获后返回 `undefined`；C 的 bilibiliclient.ts 捕获后 `throw error`（上层再 catch）。**没有看到显式 timeout 参数/计时器**（未实证：Vela 框架自身超时，业务层未包 setTimeout）。
- **https 约束**：所有实证 URL 都是 `https://`（api.bilibili.com / passport.bilibili.com / gitee.com / s.search.bilibili.com）；未出现 `http://`。**未发现域名白名单配置**（manifest/quickapp.config.js 无白名单段）——但这可能依赖小米运动健康 App 的全局代理出网。
- **UA / Origin / Referer 要求**：见下条 headers。

### 2.2 必带 headers（A 的 request.ts + C 的 bilibiliclient.ts 逐字一致）

```js
getHeaders() {
  return {
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/jxl,image/webp,image/png,image/svg+xml,*/*;q=0.8',
    'Accept-Encoding': '',          // 关键：置空，避免 gzip/br 无法解码
    'Accept-Language': 'zh-CN,zh;q=0.8',
    'Cookie': this.getCookieString(), // 手动拼接，见 §3
    'Referer': 'https://www.bilibili.com',
    'Origin':  'https://www.bilibili.com',
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0'
  };
}
```

- **必须模拟桌面浏览器 UA**（Firefox 129 串），否则 B 站风控会挡。对我们摩犀/虚空终端这类自研后端，UA 不强制，但带上无害。
- **`Accept-Encoding: ''` 置空**是关键实证——避免服务器返回 gzip，手环端无解压能力。
- `Origin`/`Referer` 对有 CORS/风控的后端必要；对摩犀这种无 CORS 的后端可省，但保留成本为零。

---

## 3. b) Cookie / Session 处理模式

**核心结论：`@system.fetch` 不提供自动 cookie jar，必须手动管理。**

### 3.1 从响应读 Set-Cookie（C 的 bilibiliclient.ts 实证）

```js
// 登录轮询成功后
this.extractCookiesFromResponse(response.headers['Set-Cookie']);

private extractCookiesFromResponse(setCookie) {
  if (typeof setCookie === 'string') setCookie = setCookie.split(', ');
  setCookie.forEach(cookie => {
    if (cookie.includes('SESSDATA')) this.sessData  = this.parseCookie(cookie, 'SESSDATA');
    else if (cookie.includes('bili_jct'))  this.biliJct  = this.parseCookie(cookie, 'bili_jct');
    else if (cookie.includes('DedeUserID') && !cookie.includes('DedeUserID__ckMd5'))
      this.dedeUserID = this.parseCookie(cookie, 'DedeUserID');
    else if (cookie.includes('sid')) this.sid = this.parseCookie(cookie, 'sid');
  });
}
private parseCookie(cookie, name) {
  const m = cookie.match(new RegExp(`${name}=([^;]+)`));
  return m ? m[1] : null;
}
```

- `response.headers['Set-Cookie']` **可能是 String 也可能是 Array**，两种都要兼容（代码里先 typeof 判断再 split）。
- 注意 `DedeUserID` 要排除 `DedeUserID__ckMd5`（同名前缀），这是实证踩坑。
- **Set-Cookie 可读**——实证成立（安卓端 InterconnectLogic 还专门把 `set-cookie` 键名规范化为 `Set-Cookie` 并保留多值 list，见 §4.2）。

### 3.2 下次请求手动带 Cookie 头

```js
private getCookieString() {
  let cookies = `buvid3=${this.buvid3}; buvid4=${this.buvid4}; `;
  if (this.sessData) {
    cookies += `SESSDATA=${this.sessData}; bili_jct=${this.biliJct}; DedeUserID=${this.dedeUserID}; sid=${this.sid}; `;
  }
  return cookies;
}
// 放进 getHeaders() 的 'Cookie' 字段
```

### 3.3 持久化（storage）

```js
// 存
storage.set({ key: 'bilibili_account', value: JSON.stringify({sessData, biliJct, dedeUserID, sid}),
  success:()=>{}, fail:(d,c)=>{} });
// 取（Promise 包装）
new Promise(resolve => storage.get({ key:'bilibili_account',
  success:(data)=>resolve(data?JSON.parse(data):null),
  fail:()=>resolve(null) }));
// 登出
storage.delete({ key:'bilibili_account' });
```

> 对摩犀：登录接口 `POST /api/login` 返回 `Set-Cookie: session=...`，手环端按上面正则提取 `session` 值，下次请求手动拼 `Cookie: session=xxx`。与我们 architecture.md §4 的 `session.moshi` storage key 设计完全兼容，只需在 api.js 里加"从响应头提取 session"这一步。

---

## 4. c) fetch 不可用时的 interconnect 兜底写法

### 4.1 手环端：与 fetch 同接口的包装（C 的 `interconnectfetch.ts`，仅 22 行）

```js
import { interconnecter } from './interconnecter';

export class fetch {
  constructor(interconnecter) { this.interconnecter = interconnecter; }

  async fetch(params) {
    // ① 把 fetch 参数整体作为 message 发出去
    const result = JSON.parse(
      await this.interconnecter.sendMessage(JSON.stringify({
        msgtype: 'FETCH',
        message: JSON.stringify(params)   // params = {url, method, data, header, responseType}
      }))
    );
    // ② responseType==='json' 时由 JS 端统一 parse（安卓端只回 string）
    if (params.responseType === 'json') result.data = JSON.parse(result.data);
    return { data: result };            // 形状与 @system.fetch 对齐：{data, headers?}
  }
}
```

**设计精髓：`new interconnectfetch.fetch(interconnecter)` 实现了与 `@system.fetch` 相同的 `.fetch({...})` 接口**，所以上层 `getRequest`/`postRequest` 一行不改就能切换模式。这就是"fetch 优先 + interconnect 兜底"的关键抽象点。

### 4.2 手环端：interconnecter 通道封装（C 的 `interconnecter.ts`，86 行）

```js
import interconnect from '@system.interconnect';

const conn = interconnect.instance();
export let CONNECTION_OPEN = false;

conn.onopen = () => {
  setTimeout(async () => {
    await sendMessage(JSON.stringify({ msgtype: 'HELLO', message: '' })); // 握手
  }, 500);
  CONNECTION_OPEN = true;
};
conn.onclose = (data) => { CONNECTION_OPEN = false; /* 日志 */ };
conn.onerror = (data) => { /* 日志 */ };
conn.onmessage = (data) => {
  const { id, response } = JSON.parse(data.data);   // 外层信封 {id, response}
  if (id && pendingRequests[id]) {
    pendingRequests[id].resolve(response);
    delete pendingRequests[id];
  }
};

// 请求-响应配对
const pendingRequests = {};
function generateUniqueId() { return Math.random().toString(36).substr(2, 9); }

export function sendMessage(message) {
  return new Promise((resolve, reject) => {
    const id = generateUniqueId();
    pendingRequests[id] = { resolve, reject };
    conn.send({
      data: { id, message },              // 外层信封 {id, message}
      success: () => {},
      fail: (data) => {
        prompt.showToast({ message: 'interconnect通信失败：' + data.data + ' 错误代码：' + data.code, duration: 5000 });
        reject(new Error('errCode=' + data.code));
        delete pendingRequests[id];
      }
    });
  });
}
```

**消息信封（实证）：**
- watch→phone：`conn.send({data: { id, message: <string> }})`，其中 `message` 是 `JSON.stringify({msgtype, message})`。
- phone→watch：`{id, response: <string>}`，手环端按 `id` 配对 pendingRequests。
- **HELLO 握手**：onopen 后 500ms 发一次 `{msgtype:'HELLO', message:''}`，安卓端回 `{"content":"OK"}`。
- **msgtype 复用**：`FETCH`（HTTP 请求）、`HELLO`（握手）、`SHOWQR`（把二维码 URL 推到手机端显示）。
- **无超时**：pendingRequests 只在 onmessage 配对或 send fail 时清除；onclose 不 reject pending（**未实证：断线时 pending 会挂起，实际可能需要在 onclose 里 reject 全部 pending**）。

### 4.3 安卓端：桥接 App（B 的 `InterconnectLogic.java` + `MainActivity.java`）

- **SDK**：`com.xiaomi.xms.wearable`（小米穿戴 SDK）。
  - `Wearable.getNodeApi(ctx).getConnectedNodes()` → 拿到已配对手环 nodeId。
  - `authApi.checkPermission(nodeId, Permission.DEVICE_MANAGER)` → 检查；`authApi.requestPermission(nodeId, Permission.DEVICE_MANAGER, Permission.NOTIFY)` → 申请。
  - `messageApi.addListener(nodeId, onMessageReceivedListener)` 监听；`messageApi.sendMessage(nodeId, bytes)` 回发。
- **包名/签名**：安卓端包名 = `com.searchstars.hyperbilibili`，**与手环端 manifest 的 `package` 一致**（这是 interconnect 配对的前提）。安卓端自身只需 `android.permission.INTERNET`。
- **消息处理**：
  - 收：`new String(bytes)` → `InterconnectPacketIn{id, message}` → 再 `gson.fromJson(packet.message, InterconnectMessage.class)` 得 `{msgtype, message}`。
  - `FETCH`：用 OkHttp 真实发 HTTP，`buildRequest` 时 `Headers.of(params.header)` 透传手环端传来的全部 header（含 Cookie），POST 时 `RequestBody.create(params.data.getBytes(UTF_8))`。
  - 回：`InterconnectFetchResponse{code, data: <body string>, headers: Map<String,List<String>>}`，包成 `InterconnectPacketOut{id, response: jsonString}`。
  - **Set-Cookie 多值保留**：`headersToMap` 专门把 `set-cookie` 键名规范化为 `Set-Cookie`，并 `headers.values(name)` 收集多值（不只取第一个）。
  - **500ms 限速队列**：安卓端用 `ScheduledExecutorService` 每 500ms 发一条消息（避免 BLE 通道拥塞）。

---

## 5. d) 网络状态提示与重试模式

### 5.1 启动时网络探测（C 的 `jumpcheck.ts`）

```js
import network from '@system.network';

export function NetworkCheck() {
  return new Promise(resolve => {
    network.getType({
      success: (data) => {
        if (!data.type)      { GoOpenInterconnectPage(); resolve(false); } // 无类型 → 引导互连
        else if (data.type === 'none') resolve(false);                     // 无网
        else resolve(true);                                                // wifi/2g/3g/4g 等
      },
      fail: () => { GoOpenInterconnectPage(); resolve(false); }            // 权限失败也引导互连
    });
  });
}
```

### 5.2 启动分流（C 的 `splash.ux` 实际生效版）

```js
// 1000ms 开屏动画 → 500ms 后
if (await NetworkCheck() || SETTINGS.enableInterconnectMode) {
  if (SETTINGS.enableInterconnectMode) router.replace({ uri: 'pages/interconnecthelper' });
  else                                 jumpcheck.Jump();   // 有网且不开互连 → 正常进 App
}
// NetworkCheck()=false 且未开互连 → jumpcheck 内部已跳 interconnecthelper
```

### 5.3 等待连接页（C 的 `interconnecthelper.ux`）

- 页面只显示"等待连接手机"。
- `onInit` 里 `setInterval(() => { if (interconnecter.CONNECTION_OPEN) jumpcheck.Jump(); }, 1000)`——每秒轮询连接标志，连上即跳主流程。
- `onDestroy` 清定时器。

### 5.4 重试/失败提示

- interconnect send 失败：`prompt.showToast({message:'interconnect通信失败：...', duration:5000})`。
- fetch 失败：业务层 catch 后 console.error，上层页面自己 toast（代码里未统一封装重试，**未实证自动重试**）。
- 网络错误页：A 的 manifest 注册了 `pages/error/networkerror` 路由（独立页面），C 用 interconnecthelper 兜底。

> **对我们的启示**：api.js 应在请求前先 `network.getType()`，`none` 时直接 reject `{ok:false, message:'无网络，请确认手机已联网并保持小米运动健康连接'}`；interconnect 模式下用轮询 `CONNECTION_OPEN` 的方式等连接。

---

## 6. e) 运行前提（实证）

1. **手环必须经 BLE 连接手机，且手机上"小米运动健康"App 在运行/已配对**。fetch 直连并非手环自己联网，而是 Vela 框架把 HTTP 调用经手机网络出网（社区实证：未连接小米运动健康时 fetch 失败）。
2. **manifest 必须显式声明** `system.fetch`、`system.request`、`system.interconnect`、`system.network`、`system.storage`、`system.file`（A 仓库全套声明；C 仓库精简但那是 band9 特例，我们不学）。
3. **interconnect 兜底时**：安卓桥接 App 包名 = 手环 manifest package，需申请 `Permission.DEVICE_MANAGER` + `NOTIFY`，安卓端只需 INTERNET 权限。
4. **图片/大文件下载**用 `@system.request` 的 download（不是 fetch）：
   ```js
   request.download({ url, success: (data) =>
     request.onDownloadComplete({ token: data.token, success: (data) => {
       this.localsrc = data.uri;   // 本地文件 uri，<image src> 直接用
     }})
   });
   // 组件销毁时 file.delete({uri: localsrc}) 清理缓存
   ```
   实证来自 A 的 `BetterOnlineImage.ux`。

---

## 7. f) 与我们 spec/architecture.md §3 的差异与可借鉴点

| 维度 | 我们当前 §3 设计 | 成熟实现（A/C/B） | 差异 / 可借鉴 |
|---|---|---|---|
| 联网主路径 | **全走 interconnect**（§1/§3 假设 fetch 不支持） | **fetch 直连优先**（连接小米运动健康时），interconnect 兜底 | **需修正**：9Pro 连小米运动健康后 fetch 可用。api.js 应做成"fetch 注入优先，失败回退 interconnect" |
| Cookie 管理 | 手机端桥接 App 处理（§8），手环不感知 | **手环端手动管理**：`response.headers['Set-Cookie']` 正则提取 → 拼 `Cookie` 头 → storage 持久化 | fetch 直连时手环必须自己管 cookie。我们 `session.moshi` storage 已就位，补"提取 + 拼头"两步即可 |
| 报文信封 | `{v, id, service, action, payload}` 单层，action 枚举 | 外层 `{id, message: string}`，内层 `{msgtype, message: string}`；msgtype=`FETCH/HELLO/SHOWQR` | 我们的信封更清晰（有 v/service/action），可保留；但 **interconnect 模式下应支持通用 `FETCH` action 透传任意 HTTP**（我们 §3.2 的 `http` action 已设计，对齐即可） |
| http 通道 | §3.2 已加 `action:'http'`（phone 执行 HTTP 回 status/headers/body） | B 仓库 `FETCH` msgtype 等价物，回 `{code, data, headers}` | 我们的 `http` action 与成熟实现语义一致，**可直接复用为兜底通道** |
| 超时 | §3.3 规定 watch 侧 20s 超时 | 成熟代码**未显式超时**（依赖框架默认） | 我们的 20s 设计更稳，保留；但 interconnect onclose 时应 reject 全部 pending（成熟实现缺这一步，我们补上） |
| 网络探测 | 未设计 | `network.getType()` 启动分流 + interconnecthelper 轮询页 | **补进 api.js / entry 页** |
| UA/Accept-Encoding | 未规定 | 必须桌面 Firefox UA + `Accept-Encoding:''` | 摩犀后端不风控可省，但 `Accept-Encoding:''` 建议带上（防 gzip） |
| 图片下载 | 未设计 | `@system.request.download` + onDownloadComplete | 我们 app 不依赖网络图片（§10.6），暂不需要；但下载 TXT 大文件可参考此模式（不过我们已设计 download_chunk 分片落盘，对 TXT 更合适） |
| 下载分片 | `download_progress` + `download_chunk`（≤4KB 文本） | B 仓库无分片（OkHttp 一次回 body，由 JS 解析） | 我们的分片设计更适合手环内存约束，保留 |
| 握手 | §3.2 `ping` action | C 用 `HELLO` msgtype，onopen 后 500ms 发 | 语义等价；我们 `ping` 可复用 |

---

## 8. api.js 改造建议（可直接落地的几条）

1. **抽象 fetch 接口**（照搬 C 的双模式注入）：
   ```js
   // api.js 构造时探测能力
   let impl;
   try {
     const f = require('@system.fetch');       // 可用则直连
     impl = f;
   } catch {
     impl = new InterconnectFetchBridge(bridge); // 兜底，与 @system.fetch 同接口
   }
   // 之后统一 impl.fetch({url, method, data, header, responseType})
   ```
2. **headers 构造**：带 `Cookie`（从 storage 读 session 拼）、`Accept-Encoding:''`、`User-Agent`（可简）。
3. **登录响应处理**：`POST /api/login` 成功后从 `response.headers['Set-Cookie']` 正则提取 `session=([^;]+)`，写 storage `session.moshi`。
4. **网络前置检查**：每个请求前 `network.getType()`，`none` 直接 reject 中文文案；interconnect 模式下 `await bridge.waitReady()`（轮询 CONNECTION_OPEN，参考 interconnecthelper）。
5. **超时**：保留 20s 竞态计时器；interconnect onclose 时 reject 全部 pending（补成熟实现的缺口）。
6. **manifest 补声明**：`system.fetch`、`system.request`、`system.network` 加入 features 数组（当前 §10.1 缺这三个）。

---

## 附：未实证 / 需真机 POC 的点

- Vela `@system.fetch` 的具体超时时长、并发限制、https 证书校验行为——代码未体现，需真机测。
- `response.headers['Set-Cookie']` 在不同设备上是 String 还是 Array——代码两种都处理了，实测确认。
- fetch 在 9Pro 上是否要求"小米运动健康"前台运行还是后台即可——社区说法是连接即可，未实证。
- interconnect onclose 时 pending 请求是否真挂起——成熟实现未处理，我们自己实现时需 reject。
- 域名白名单：manifest/quickapp.config 未发现，但不排除 Vela 框架层有隐藏白名单——需真机测一个自定义域名（morax.kdns.fr）确认可达。
