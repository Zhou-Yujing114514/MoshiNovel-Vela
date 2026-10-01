# 摩柿小说 · 小米手环9 Pro QuickApp（单应用版）

在小米手环 9 Pro（Vela OS · QuickApp/快应用 JS 框架）上运行的**摩柿小说**离线阅读应用：**登录摩柿账号 → 获取书架 → 选择下载 TXT 到手表 → 离线阅读 → 删除本地 TXT**。

> 交付日期：2026-09-30 ｜ 开发依据：Vela QuickApp 官方文档（iot.mi.com/vela/quickapp）+ 社区实证
> 独立工程：本包为摩柿单应用（虚空终端聊天为另一独立工程 VoidTerminal-Vela，已分开发布）。

---

## 一、功能清单（用户需求对照）

| # | 需求 | 实现 |
|---|---|---|
| 1 | 登录摩柿账号（用户名+密码） | ✅ 登录页（账密输入、记住账号、错误提示），会话持久化 |
| 2 | 获取摩柿书架列表并展示 | ✅ 书架页（书名/作者/已下载标记/阅读进度） |
| 3 | 选择书架上的书下载 TXT 到手表 | ✅ 点击下载，进度条反馈，落盘 `internal://files/books/` |
| 4 | 离线阅读已下载 TXT | ✅ 阅读器（分页、UTF-8 安全解码、记住进度、翻页手势/按钮） |
| 5 | 删除本地 TXT | ✅ 书架/本地书库二次确认删除 |
| + | 联网通路 | ✅ fetch 优先 + interconnect 桥接兜底（手机桥/ESP32 网关，见第三节） |

## 二、目录结构

```
MoshiNovel-Vela/
├── README.md                    # 本文件
├── spec/                        # 开发规格与调研结论
│   ├── architecture.md          # 总架构与桥接协议（§3 协议、§10 实现级冻结、§11 虚空聊天协议参考）
│   ├── vela-capability.md       # Vela 文档能力 + 9Pro 支持矩阵 + 联网专项复查
│   ├── voidterminal-im.md       # 虚空终端聊天协议（供 companion 参考，本工程不使用 vt_*）
│   ├── voice-input.md           # 语音输入可行性（供 companion 参考）
│   ├── network-reference.md     # 社区开源实现（HyperBilibili 系列）网络层模式
│   └── acceptance-report.md     # 独立验收报告（含拆分终验）
├── watch-app/                   # ★ 手环 QuickApp 工程（主交付）
│   ├── package.json
│   ├── sign/debug/  sign/release/   # 签名目录（README 含生成方法）
│   └── src/
│       ├── manifest.json        # 包名 com.moshinovel / 路由 Entry,Login,Shelf,Library,Reader
│       ├── app.ux               # 应用生命周期
│       ├── app.css              # 全局深色主题样式（designWidth 480）
│       ├── common/
│       │   ├── util.js          # 常量、UTF-8 安全解码（无 TextDecoder 依赖）
│       │   ├── store.js         # storage 封装（账号/会话/书籍元数据/进度，svc 参数化）
│       │   ├── bridge.js        # interconnect 封装（状态机/请求配对/分片落盘）
│       │   ├── api.js           # 网络层（fetch 优先 + 桥接兜底）
│       │   └── reader.js        # TXT 分页算法（readArrayBuffer 窗口 + 边界处理）
│       └── pages/
│           ├── Entry/entry.ux   # 首页：摩柿/本地书库 + 桥接状态
│           ├── Login/login.ux   # 登录页
│           ├── Shelf/shelf.ux   # 书架（下载/进度/删除/退出登录）
│           ├── Library/library.ux # 本地书库（已下载 TXT）
│           └── Reader/reader.ux # 阅读器
└── phone-bridge/                # ★ 手机端桥接 Android 参考工程（源码+构建说明）
    └── README.md
```

## 三、联网通路（核心设计，已按用户要求反复核验）

**结论：手环 9 Pro 的 Vela QuickApp 官方支持表显示 fetch/request/uploadtask/network 均"不支持"，但社区实证（HyperBilibili 开源实现 + 米坛实测）确认：手环经 BLE 连接网关后，`@system.fetch` 可正常出网。** 因此网络层按"**fetch 优先 + interconnect 桥接兜底**"实现，底层链路对应用透明。

### 3.1 三种网关形态（配置方式）

| 路径 | 形态 | 前提 | 说明 |
|---|---|---|---|
| ① 手机桥（主路径） | 手机装【摩柿桥接 App】（本项目 phone-bridge/ 源码）或小米运动健康 | 手机与手环蓝牙连接 | 完整功能：登录/书架/分片下载；自定义协议动作走 interconnect |
| ② ESP32 蓝牙网关（无手机备选） | ESP32 刷蓝牙网关固件 | 无手机、有 WiFi | 手环 BLE 直连 ESP32 网关，fetch 照常使用；QuickApp 无需改动 |
| ③ FetchBridge 插件（可选增强） | 手环安装社区插件"网桥 FetchBridge"（Searchstars v1.3.2） | 手环插件市场安装 | 为不支持 fetch 的设备经 interconnect 恢复 fetch 等价能力；本工程协议已预留 `action:'http'` 兼容 |

- 应用启动时：`app.canIUse('@system.fetch')` 探测 + `network.getType()` 检查 → **有 fetch 直连能力则直连摩柿 API**（登录/书架/下载），失败自动回退 interconnect 桥接，断网时中文提示。
- ESP32 网关配置（社区视频纪要，2026-09-30 核验）：烧录 3 个固件（参数数字须一致）→ 浏览器访问 `192.168.4.1` → 填 WiFi 密码 + 手环 MAC + KEY → 保存。资源需解压后使用。

### 3.2 桥接协议速查（详见 spec/architecture.md §3）

- 请求：`{v:1, id, service:'moshi', action, payload}`，action ∈ `login | shelf | download | http | ping`
- 响应：`{v:1, id, ok, code, message, payload}`
- 下载推送：`download_progress`（percent/bytes/total）+ `download_chunk`（≤4KB 文本、`eof` 收尾），手表端 `file.writeText append` 落盘
- 会话 Cookie 手动管理：登录响应 `Set-Cookie` 提取 `session=…` 存 storage，后续请求手动带 `Cookie` 头

## 四、构建 / 导入 / 运行（AIoT-IDE）

1. 下载安装 AIoT-IDE（macOS 14+ / Win10+ / Ubuntu 20.04+），来源：Vela 文档"使用 AIoT-IDE"页。
2. **导入工程**：文件 > 打开文件夹，选择 `watch-app/`（含 `src/`、`package.json`）。首次打开按右侧开发向导装依赖；npm 失败时在 `watch-app/` 建 `.npmrc` 写 `registry="https://registry.npmmirror.com/"` 后重试。
3. **选设备**：banner"模拟器/设备管理 > 新建"，选镜像（如 `vela-mirae-watch-5.0`）与屏幕（手环 9 Pro 矩形 336×480）建模拟器。
4. **调试**：点"调试"看 DOM/Console/Network；顶部"运行"热更新预览。
5. **打包**：点"打包"→ `dist/*.debug.rpk`；"发布"→ 生成签名后出 `dist/*.release.rpk`。
6. **真机安装**（FAQ 流程）：手机装小米运动健康 → 我的 > 关于 > Debug > 第三方应用 → 输入包名 `com.moshinovel` → Install third app → 选 `.rpk`；三方应用能力开通对接邮箱 changjian@xiaomi.com。
7. **签名要求**：涉及 interconnect 通信时，快应用证书必须与手机桥接 App 安卓签名**同源一致**（jks→p12→pem 或在线签名工具），`private.pem`/`certificate.pem` 放 `watch-app/sign/debug` 与 `sign/release`。详见 `watch-app/sign/*/README.md`。

## 五、手机桥接 App（phone-bridge/）

- Android 工程（Gradle，applicationId `com.moshinovel`，与手环 manifest package 一致——interconnect 配对要求）。
- 组件：`MoraxClient.java`（摩柿 HTTPS 客户端：登录取 session Cookie、书架、流式下载）、`BridgeService.java`（前台服务：协议主循环 + http 代理 + 分片回传）、`WatchChannel.java`（小米穿戴 SDK 集成点，按《小米穿戴第三方APP能力开放接口文档》接入）、`MainActivity.java`（最小入口）。
- 说明：本 companion 源码同时含虚空终端聊天客户端（VoidTerminalClient.java 与 vt_* 动作），供另一独立工程 VoidTerminal-Vela 使用；**摩柿工程只使用 login/shelf/download/http/ping 动作**，vt_* 分支未启用不影响本应用。
- 构建：需要 Android SDK；本交付为源码 + 精确步骤（见 phone-bridge/README.md），沙箱未编译验证。
- 摩柿后端参数（复用已验证 ESP8266 客户端细节）：`morax.kdns.fr` HTTPS，SSL 指纹 `B2:C3:C9:FC:EA:DF:2D:51:9F:DA:57:54:23:FE:BB:D7:22:17:2C:07`。

## 六、摩柿测试账号

- 测试账号：`Admin` / `xzmlwjh1`（登录用；交付后用户可自行修改）。
- 提示：账号密码以明文存于手环 storage（手环无加密存储 API），README 明示风险；不放心可每次手动输入。

## 七、登录输入方案与键盘可用性

- 输入组件：登录页使用 Vela 系统表单组件 `<input type="text">`（账号）/ `<input type="password">`（密码），**未实现自定义虚拟键盘/输入法**——依赖 Vela 运行时聚焦输入框时是否唤起系统软键盘。
- 便利化：登录成功且"记住账号"开关开启时，账号+密码一并存入本地 storage；**下次进入登录页自动预填**，直接点"登录"即可，键盘依赖仅在首次输入时出现。
- ⚠️ **真机验证状态：未验证**。本项目未在真机编译运行（需 AIoT-IDE + 真机，见第八节限制）；手环 9 Pro 上 `<input>` 聚焦时是否弹出可用软键盘**未经真机确认**。若真机无键盘，降级路径：① 手机桥接 App 内输入转发（后续版）；② 登录页增加"快速填入测试账号"按钮（一行改动）。该风险待真机 POC 排期。

## 八、已知限制与风险

1. **interconnect 在 9 Pro 的官方支持表未明示**——社区实证（米坛 BandBBS、HyperBilibili、FetchBridge 生态）表明 9 系列可用，但**仍需真机 POC**：装好桥接后先跑"登录"验证链路。
2. fetch 直连依赖网关在线（手机/ESP32）；无网关时所有联网操作给出中文错误提示，本地书库/阅读不受影响（离线可用）。
3. 大 TXT 直连下载走 512KB 上限内分片写盘；更大文件自动切到桥接分片流（低内存）。
4. 本项目未在真机编译运行（需 AIoT-IDE + 真机）；已通过语法/结构/接口一致性独立验收（spec/acceptance-report.md）。
5. 后续路线（记录）：ESP32 网关自研固件（openvela frameworks_bluetooth GATT client）、阅读器字号/行距设置。

## 九、验收对照（spec/acceptance-report.md 摘要）

文件清单完整性 PASS；JS 语法 PASS（修复 reader.js 相对导入断链后）；ux 结构 PASS（三段式/单根/无裸文本/无 button/样式仅类选择器）；接口契约 PASS（store 11 方法、api 导出、bridge 导出、manifest 路由与 features 匹配）；协议一致性 PASS（watch↔phone 字段逐项对齐）；凭据扫描 PASS（无 SSH 凭据/硬编码账密）。

---

*开发依据文档：https://iot.mi.com/vela/quickapp/zh/ （Vela JS 应用官方文档站）*
