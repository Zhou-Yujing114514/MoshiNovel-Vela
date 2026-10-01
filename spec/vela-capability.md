# 小米 Vela QuickApp（快应用）能力规格文档

> 目标平台：小米手环 9 Pro（Vela OS，QuickApp / 快应用 JS 框架）
> 应用设想：登录 → 书架 → 下载 TXT 到本地 → 离线阅读 → 删除，并通过手机端 App 桥接
> 调研时间：2026-09-30；文档站：https://iot.mi.com/vela/quickapp/zh/
> 说明：本文只记录文档中明确写出的能力，未在文档中出现的不做推断；每条关键结论后标注来源 URL。

---

## 0. 结论速览（针对本小说 App）

- **手表端没有任何出网能力**：`system.fetch`、`system.request(download)`、`system.uploadtask` 在「小米手环 9 / 9 Pro」上均为**不支持**（来源：fetch / request / uploadtask 文档「支持明细」表）。
  - 这意味着**不能在手环上直接请求小说 API / 直接下载 TXT**。所有「联网取书」必须由**手机端 App 完成**，再通过 `system.interconnect` 把数据透传到手环，最后用 `system.file` 落盘。
- **本地读写可用**：`system.file`（写文本/读文本/读写 ArrayBuffer/列目录/删除/建目录/移动/复制）与 `system.storage`（KV）可用。
- **手机桥接用 `system.interconnect`**：要求快应用 manifest 的 `package` 与手机三方 App 安卓包名一致、签名一致。**该接口文档页未给出设备支持明细表**，FAQ 也未点名手环 9 Pro 是否支持，故「9 Pro 上 interconnect 是否可用」标注为**文档未明示，须真机验证**。
- 手环 9 Pro 屏幕：**矩形 1.74 英寸，336×480 分辨率，336 PPI，DPR 2.1**（官方多屏设计表）。

---

## a) 标准项目结构与 manifest.json

### a.1 目录结构

完整工程根目录（来源：项目概览 https://iot.mi.com/vela/quickapp/zh/guide/start/project-overview.html ；项目结构 https://iot.mi.com/vela/quickapp/zh/guide/framework/project-structure.html ）：

```
├── README.md
├── package.json            # npm 配置与构建脚本
├── build/                  # 构建中间产物（IDE 自动生成）
├── dist/                   # 最终产物：.rpk 包（IDE 自动生成）
├── sign/                   # 签名目录
│   ├── certificate.pem     # 证书
│   └── private.pem         # 私钥
└── src/                    # 源码目录（目录名固定，不可改）
    ├── manifest.json       # 应用配置：包名/版本/接口声明/页面路由
    ├── app.ux              # 应用入口：全局生命周期/全局数据/全局方法
    ├── pages/              # 页面目录，每页一个子目录
    │   ├── index/index.ux
    │   └── detail/detail.ux
    ├── common/             # 跨页面共享资源
    │   ├── components/      # 公共组件（如 button.ux）
    │   ├── images/          # 图片资源
    │   └── scripts/         # 公共脚本
    └── i18n/               # 多语言：defaults.json / zh-CN.json / en-US.json
```

补充（interconnect 真机出包要求）：快应用需把 `private.pem` 与 `certificate.pem` 放到工程根的 **`/sign/debug` 与 `/sign/release`** 下出包测试（来源：interconnect 开发注意事项 https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html ）。

> 注：`src/` 下也可把单页拆成 `xxx.ux` + `xxx.css` + `xxx.js` 三个文件；拆分后 `.ux` 内不能再含 `<template>`（来源：项目结构）。

### a.2 manifest.json 字段

来源：项目配置 https://iot.mi.com/vela/quickapp/zh/guide/framework/manifest.html

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `package` | String | 是 | 应用包名，推荐 `com.company.module`（如 `com.example.demo`）。**做 interconnect 时必须等于手机三方 App 的安卓包名** |
| `name` | String | 是 | 应用名，**6 个汉字以内**，用于桌面图标/弹窗 |
| `icon` | String | 是 | 应用图标，提供 192×192 即可 |
| `versionName` | String | 否 | 版本名，如 `"1.0"` |
| `versionCode` | Integer | 是 | 版本号，从 1 自增，每次上传建议 +1 |
| `minAPILevel` | Integer | 否 | 最低兼容 API 标准版本，默认 1 |
| `features` | Array | 否 | 接口声明列表，格式 `[{ "name": "system.xxx" }]`；**绝大多数接口必须在此声明，否则不能调用** |
| `config` | Object | 是 | 系统配置：`logLevel`(off/error/warn/info/log/debug)、`designWidth`(设计基准宽度)、`background`(后台运行) |
| `router` | Object | 是 | 页面路由：`entry`(首页名) + `pages`(页面表) |
| `display` | Object | 否 | UI 显示，如 `backgroundColor`（默认 `#ffffff`） |
| `deviceTypeList` | Array | 否 | 可选 watch/tv/car/phone，**目前只支持 watch**，默认 `["watch"]` |
| `permissions` | Array | 否 | 权限，如 `[{ "name": "hapjs.permission.DEVICE_INFO" }]` |

**router.pages 写法**（key 为页面名，对应 pages 下目录名）：

```json
"router": {
  "entry": "Home",
  "pages": {
    "Home":    { "component": "home",    "path": "/" },
    "Bookshelf": { "component": "bookshelf" },
    "Reader":  { "component": "reader",  "launchMode": "standard" }
  }
}
```

- `component`：与 ux 文件名一致（`reader` → `reader.ux`）。
- `path`：默认 `/<页面名>`，须唯一。
- `launchMode`：`standard`（每次新开实例，默认）或 `singleTask`（单实例，重开时回调 `onRefresh`）。

**features 声明写法**（本应用需声明的接口）：

```json
"features": [
  { "name": "system.file" },
  { "name": "system.storage" },
  { "name": "system.interconnect" },
  { "name": "system.device" },
  { "name": "system.vibrator" },
  { "name": "system.prompt" }
]
```

> 注意：`@system.router`、`@system.app`、`@system.configuration` 文档页均标注「**接口声明：无需声明**」，可不写入 features（来源：router/app/configuration 各页）。

---

## b) ux 文件语法

来源：项目结构 https://iot.mi.com/vela/quickapp/zh/guide/framework/project-structure.html ；编写页面 UI https://iot.mi.com/vela/quickapp/zh/guide/start/user-interface.html ；script 脚本 https://iot.mi.com/vela/quickapp/zh/guide/framework/script/ ；生命周期 https://iot.mi.com/vela/quickapp/zh/guide/framework/script/lifecycle.html

### b.1 三段式结构

一个页面 ux 文件由 `<template>` / `<style>` / `<script>` 三段组成：

- `<template>` 只能有**一个根节点**；
- **文本必须放在 `<text>` 组件内**，裸文本不会显示；
- 支持 ES5/ES6；模块用 `import xxx from '@system.yyy'` 或 `'../common/utils.js'`；**不是 node 环境，不能 `import fs from 'fs'`**。

```html
<template>
  <div class="page">
    <text class="title">欢迎 {{title}}</text>
    <input type="button" value="去书架" onclick="goShelf" />
  </div>
</template>
<style>
  .title { font-size: 40px; color: #fff; }
</style>
<script>
  export default {
    private: { title: '示例' },
    goShelf() { /* ... */ }
  }
</script>
```

### b.2 数据绑定 / 列表渲染 / 条件渲染

- 数据定义在 `private: {}`（页面内私有，外部传入不可覆盖）；对外/跨页传参用 `public` / `protected`。
- 插值：`{{ name }}`、`{{ weather.temp }}`。
- 列表渲染：`<div for="{{list}}"><text>{{$item.name}}</text></div>`（item 变量默认 `$item`）。
- 条件渲染：`if` 指令（详见文档「条件指令」）。
- 列表闪烁：`for` 渲染的数据更新闪烁时，给列表项加 `tid` 字段解决（来源：FAQ https://iot.mi.com/vela/quickapp/zh/guide/other/faq.html ）。

### b.3 样式选择器限制

**只支持**：类选择器 `.cls`、ID 选择器 `#id`、分组选择器 `,`、标签选择器。
**不支持**：后代选择器、属性选择器、通用选择器 `*`、兄弟选择器 `+`、直接子选择器 `>`、伪类、继承（来源：编写页面 UI）。布局为 **flex**。

### b.4 事件绑定

写法：`onclick="fn"` 或 `@click="fn"`，事件回调里 `{{}}` 可省略（如 `onclick="goShelf(item.id)"`）。
通用事件（来源：通用事件 https://iot.mi.com/vela/quickapp/zh/components/general/events.html ）：

| 事件 | 触发 | 冒泡 |
|---|---|---|
| `touchstart` | 手指按下 | 支持 |
| `touchmove` | 手指移动 | 支持 |
| `touchend` | 手指抬起 | 支持 |
| `click` | 点击 | 支持 |
| `longpress` | 长按 | 支持 |
| `swipe` | 快速滑动，回调 `{direction: left/right/up/down}`（有滚动条时不触发） | 不支持 |

TouchEvent 提供 `touches/changedTouches`，含 `clientX/Y`、`pageX/Y`、`offsetX/Y`、`identifier`。

### b.5 页面生命周期

来源：生命周期 https://iot.mi.com/vela/quickapp/zh/guide/framework/script/lifecycle.html

| 钩子 | 时机 |
|---|---|
| `onInit()` | ViewModel 数据已就绪，可读写页面数据 |
| `onReady()` | 模板编译完成，可通过 `this.$element(id)` 获取 DOM 节点 |
| `onShow()` | 页面切到前台（息屏重新亮屏会再次触发，注意不要在此无脑重复请求） |
| `onHide()` | 页面被切到后台 |
| `onDestroy()` | 页面销毁，应释放资源（`setTimeout` 绑定在页面上，销毁后不再执行；用 `this.$valid` 判断） |
| `onBackPress()` | 右滑返回/实体返回键触发；**`return true` 表示自己拦截返回逻辑**，否则默认返回上一页 |
| `onRefresh(query)` | singleTask 页面被再次打开时 |
| `onConfigurationChanged(event)` | 系统配置变化（如语言 locale） |

### b.6 App（app.ux）生命周期

`onCreate` / `onShow` / `onHide` / `onDestroy` / `onError(e)`。
在 `app.ux` 里定义的方法与数据，页面内通过 `this.$app.$def.method1()` / `this.$app.$def.data1` 访问。

---

## c) 内置组件目录

来源：组件索引
- 容器组件：https://iot.mi.com/vela/quickapp/zh/components/container/ （div、list、list-item、scroll、stack、swiper）
- 基础组件：https://iot.mi.com/vela/quickapp/zh/components/basic/ （text、span、a、image、image-animator、progress、marquee、chart、qrcode、barcode）
- 表单组件：https://iot.mi.com/vela/quickapp/zh/components/form/ （input、picker、switch、slider）

| 组件 | 分类 | 关键属性 / 事件 / 方法 | 小屏手环触控适用性 |
|---|---|---|---|
| `div` | 容器 | 通用属性/样式/事件；flex 布局根节点 | 页面根与分块必用 |
| `text` | 基础 | `lines`(-1不限行)、`color`(默认 rgba(0,0,0,.54))、`font-size`(默认30px)、`font-style`、`font-weight`(仅 normal/bold) | **阅读正文核心组件**（来源：text.html） |
| `span` / `a` | 基础 | 行内文本 / 链接 | 行内富文本 |
| `image` | 基础 | `src`（相对/绝对路径，或 `internal://` uri） | 封面/图标 |
| `image-animator` | 基础 | 帧动画 | 加载动画 |
| `progress` | 基础 | `percent`、`color`(#33b4ff)、`stroke-width`(32px)、`layer-color`；`type` 可按屏幕形状选弧形/直线 | 下载/加载进度（来源：progress.html） |
| `marquee` / `chart` / `qrcode` / `barcode` | 基础 | 跑马灯 / 图表 / 二维码 / 条形码 | 书架提示、登录码 |
| `list` + `list-item` | 容器 | list 仅能含 list-item；属性 `bounces`；事件 `scroll/scrolltop/scrollbottom/scrollend/scrolltouchup`；方法 `scrollTo({index,behavior})`、`scrollBy({left,top,behavior})`；**需显式设高度**；list-item 需 `type`，同 type 必须 DOM 结构一致，内部慎用 if/for | **书架列表首选**（来源：list.html、list-item.html） |
| `scroll` | 容器 | `scroll-x/scroll-y`、`scroll-top/scroll-bottom/scroll-left/scroll-right`、`bounces`；事件 `scroll/scrolltop/scrollbottom`；方法（经 `this.$element(id)`）`getScrollRect/scrollTo({top,left,behavior})/scrollBy()`；竖向滚动需定高；标注 API `2+` | **长文阅读页核心**（来源：scroll.html） |
| `stack` | 容器 | 层叠布局（子组件堆叠） | 封面+进度叠放 |
| `swiper` | 容器 | `indicator`、`loop`、`vertical`、`enableswipe` | 多页轮播 |
| `input` | 表单 | `type`(button/checkbox/radio/text 等)、`value`、`checked`；事件 `change`（button 型无 change） | **登录按钮/开关/输入框**；按钮用 `<input type="button">`（来源：input.html） |
| `picker` / `switch` / `slider` | 表单 | 选择器 / 开关 / 滑条 | 阅读设置（字号/翻页） |

> 注意：文档组件目录中**没有独立的 `<button>` 组件**，按钮用 `<input type="button">` 实现（来源：input.html）。

---

## d) 页面导航 router

来源：页面路由 router https://iot.mi.com/vela/quickapp/zh/features/basic/router.html ；添加交互 https://iot.mi.com/vela/quickapp/zh/guide/start/add-interactivity.html

```js
import router from '@system.router'
```

- **`router.push({ uri, params })`**：跳转到应用内页面。`uri` 可以是 `/pages/detail`（路径）、`Detail`（页面名）或 `/`（首页）；`params` 为传参对象。
- **`router.replace({ uri, params })`**：替换当前页并销毁被替换页。
- **`router.back({ path })`**：不传参返回上一页；传 `/path` 返回栈内指定页（匹配不到则回上一页）。
- `router.clear()`：清空页面栈仅留当前页。
- `router.getLength()`：栈页数；`router.getState()`：当前页 index/name/path；`router.getPages()`：栈列表。

**传参接收**：目标页通过 `this.param1` 读取（值统一转 String），且需在目标页 `public`/`protected` 中声明同名 key。
特殊参数 `params: { ___PARAM_LAUNCH_FLAG___: 'clearTask' }` 可清栈。
`router` 文档页标注「接口声明：无需声明」。

---

## e) 系统接口一览（声明方式 + 9 Pro 支持）

| 接口 | import | manifest features 声明 | 关键能力 | 9 Pro 支持 |
|---|---|---|---|---|
| 网络请求 fetch | `@system.fetch` | 需声明 `system.fetch` | GET/POST，responseType text/json/file/arraybuffer | **不支持**（fetch 支持明细） |
| 下载 request | `@system.request` | 需声明 `system.request` | `download` + `onDownloadComplete`，文件落缓存目录 | **不支持**（request 支持明细） |
| 上传 uploadtask | `@system.uploadtask` | 需声明 `system.uploadtask` | uploadFile + 进度监听/中断 | **不支持**（uploadtask 支持明细） |
| 网络状态 network | `@system.network` | 需声明 `system.network` | getType/subscribe（2g/3g/4g/wifi/none/5g/bluetooth） | **不支持**（network 支持明细） |
| BLE GATT 客户端 bluetooth.ble | `@system.bluetooth.ble` | 需声明 `system.bluetooth.ble` | 扫描/连外设/读写特征/notify/MTU 协商 | **不支持**（9/9Pro；仅 Watch S5 支持，bluetooth 支持明细） |
| 手机桥接 interconnect | `@system.interconnect` | 需声明 `system.interconnect` | 与手机 App 双向收发（见 i 节） | **文档未明示**（无支持明细表，见 h 节） |
| 文件 file | `@system.file` | 需声明 `system.file` | 见 g/下方 file 方法 | 可用（用户已确认；该页无支持表） |
| KV storage | `@system.storage` | 需声明 `system.storage` | get/set/delete/clear | 可用（用户已确认；该页无支持表） |
| 页面路由 router | `@system.router` | 无需声明 | 见 d 节 | 核心导航，默认可用（该页无支持表） |
| 应用上下文 app | `@system.app` | 无需声明 | `getInfo()`(包名/版本/来源)、`terminate()`、`canIUse('@system.xxx')`(API 3+) | 默认可用；`canIUse` 可运行时探测能力（app.html） |
| 设备信息 device | `@system.device` | 需声明 `system.device` | `getInfo()`(brand/model/screenWidth/screenHeight/screenShape[rect/circle/pill-shaped]/deviceType[watch/band/smartspeaker])、`getTotalStorage/getAvailableStorage`；`getDeviceId/getSerial` 需权限 `hapjs.permission.DEVICE_INFO` | 默认可用；存储/屏幕信息对阅读 App 有用（device.html） |
| 震动 vibrator | `@system.vibrator` | 需声明 `system.vibrator` | `vibrate({mode:long/short})` | **`vibrate` 支持**；`start/stop/getSystemDefaultMode` **不支持**（vibrator 支持明细） |
| 提示 prompt | `@system.prompt` | 需声明 `system.prompt` | `showToast({message, duration=1500ms})` | 默认可用（该页无支持表；prompt.html） |
| 应用配置 configuration | `@system.configuration` | 无需声明 | `getLocale()` 返回 {language, countryOrRegion} | 默认可用（configuration.html） |

> 来源：fetch/request/uploadtask/network/vibrator 各页「支持明细」；file/storage/router/app/device/prompt/configuration 各对应文档页。

---

## f) 多屏适配与手环 9 Pro 屏幕规格

### f.1 尺寸单位机制

- 与 `rpx` 不同：Vela 用 **`px` 自动缩放**。所有尺寸相关样式（width/font-size…）以**基准宽度（默认 480px）**为基础，按实际屏幕宽度等比缩放；如 `width:100px` 在 960px 宽屏上实际渲染为 200px（来源：编写页面 UI https://iot.mi.com/vela/quickapp/zh/guide/start/user-interface.html ）。
- 设计稿按 480px 宽做即可；若基准不同，在 manifest `config.designWidth` 配置（如手环矩形屏可设 `designWidth: 336`）（来源：manifest.html；FAQ 举例设计稿 466×466 设 `designWidth:466`）。
- 适配时可用 `device.getInfo()` 拿到 `screenShape` / `screenWidth/Height` 做分支（来源：适配规范 https://iot.mi.com/vela/quickapp/zh/guide/multi-screens/specs.html ）。

### f.2 已发布 Vela 穿戴设备屏幕数据（官方表）

来源：多屏设计 https://iot.mi.com/vela/quickapp/zh/guide/design/multi-screens.html

| 设备 | 形状 | 尺寸 | 分辨率 | PPI | DPR |
|---|---|---|---|---|---|
| 小米手环 9 | 胶囊形 | 1.62" | 192×490 | 325 | 2.0 |
| **小米手环 9 Pro** | **矩形** | **1.74"** | **336×480** | **336** | **2.1** |
| 小米手环 8 Pro | 矩形 | 1.74" | 336×480 | 336 | 2.1 |
| 小米手环 10 | 胶囊形 | 1.725" | 212×520 | 326 | 2.0 |
| Xiaomi Watch S3/S4/H1 | 圆形 | 1.43" | 466×466 | 326 | 2.0 |
| REDMI Watch 5 | 矩形 | 2.07" | 432×514 | 324 | 2.0 |

- 三类屏推荐比例：圆屏 1:1（466×466）、矩形 0.7（336×480）、胶囊 0.39（192×490）。
- **安全区**：圆形/胶囊屏边缘弧形会裁切，主体内容（文本、列表）须落在安全区内；矩形屏（手环 9 Pro）弧形裁切问题小，但仍需留边距。

> 手环 9 Pro 分辨率 **336×480** 即来自上述官方文档表，非外部推测。

---

## g) 打包、签名与真机安装

来源：使用 AIoT-IDE https://iot.mi.com/vela/quickapp/zh/guide/start/use-ide.html ；interconnect 页；FAQ https://iot.mi.com/vela/quickapp/zh/guide/other/faq.html

### g.1 环境与新建/导入工程

- AIoT-IDE 基于 VS Code，支持 macOS 14+ / Windows 10+ / Ubuntu 20.04+。
- 新建：`文件 > 新建项目` → 左侧选 **watch** → 选模板（常规/日历/图表/任务清单/播放器/开发示例）→ 填项目名与路径 → 创建。
- 依赖：首次打开按右侧「开发向导」装依赖；npm 失败时在工程根建 `.npmrc` 写 `registry="https://registry.npmmirror.com/"` 后重试 `npm i`。
- 选设备：banner「模拟器/设备管理 > 新建」选镜像（如 `vela-mirae-watch-5.0`）与屏幕尺寸创建模拟器实例。

### g.2 调试与运行

- 选好模拟器后点「调试」，IDE 底部弹出调试面板（Elements / Console / Sources / Network），支持查看 DOM 树、Console、断点调试。
- 顶部「运行」支持文件改动热更新预览。

### g.3 打包 rpk

- **开发包**：点 banner「打包」→ `dist/` 下生成 `*.debug.rpk`，`build/` 为编译后 js。
- **发布包**：点「发布」→ 填信息 → 自动在 `sign/` 生成 `private.pem` + `certificate.pem` → 再点「发布」出 `dist/*.release.rpk`。自动生成需系统装 openssl。
- 手动生成签名（需 openssl）：
  ```
  openssl req -newkey rsa:2048 -nodes -keyout private.pem -x509 -days 3650 -out certificate.pem
  ```

### g.4 签名要求（interconnect 关键）

来源：interconnect 开发注意事项 https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html ；FAQ

- 通信要求**快应用 manifest `package` = 手机三方 App 安卓包名**，且**签名一致**。
- 从手机端安卓 `jks` 提取证书/私钥步骤：
  1. `keytool -importkeystore -srckeystore keystore.jks -destkeystore keystore.p12 -srcstoretype jks -deststoretype pkcs12`
  2. `openssl pkcs12 -nodes -in keystore.p12 -out keystore.pem`
  3. 从 pem 中复制 `BEGIN PRIVATE KEY`…`END PRIVATE KEY` 到 `private.pem`；`BEGIN CERTIFICATE`…`END CERTIFICATE` 到 `certificate.pem`。
- 也可用官方**「在线签名生成工具」**（WebAssembly，浏览器内完成，不上传密码）：上传 p12 → 输入密码 → 生成 → 下载 pem。
- 生成的 `private.pem`/`certificate.pem` 放到工程根 **`/sign/debug` 与 `/sign/release`** 出包测试。
- release 包务必**每次用同一证书**，否则可能无法上架；涉及通信时证书必须与手机 App 一致。

### g.5 真机安装与看日志（FAQ）

1. 手机装「小米运动健康」（三方应用能力目前通过**商务拉群**开通，开发对接邮箱：changjian@xiaomi.com）。
2. 小米运动健康 → 我的 → 关于 → **Debug** → 第三方应用 → 输入包名 → **Install third app** → 选本地 `.rpk` 安装，成功有 Toast。
3. 真机日志：小米运动健康 → 关于 → Debug → 拉取固件日志，保存在 `/sdcard/Android/data/com.mi.health/files/log`。
4. 模拟器与手机通信需外接蓝牙适配器、配置复杂，**建议直接真机调试**。

---

## h) 设备支持矩阵：小米手环 9 / 9 Pro

来源：各接口页「支持明细」表；无表者标注「该页未列明细」。

| 接口 | 9 / 9 Pro 支持情况 | 依据 |
|---|---|---|
| `system.fetch` | **不支持** | fetch.html 支持明细 |
| `system.request`（download） | **不支持** | request.html 支持明细 |
| `system.uploadtask` | **不支持** | uploadtask.html 支持明细 |
| `system.network`（getType/subscribe） | **不支持** | network.html 支持明细 |
| `system.bluetooth.ble`（BLE GATT Client） | **不支持**（全设备表中仅 Xiaomi Watch S5 支持；且禁止后台运行） | bluetooth.html 支持明细 |
| `system.vibrator.vibrate` | **支持** | vibrator.html 支持明细 |
| `system.vibrator.start/stop/getSystemDefaultMode` | **不支持** | vibrator.html 支持明细 |
| `system.interconnect`（手机桥接） | **文档未明示**：该接口页**无设备支持明细表**；FAQ 仅泛讲通信调试/签名，未点名手环 9 Pro | interconnect.html（无表）；faq.html |
| `system.file` | 可用（用户已确认）；该页无支持表 | file.html |
| `system.storage` | 可用（用户已确认）；该页无支持表 | storage.html |
| `system.router` | 默认可用（导航核心）；该页标注「无需声明」，无支持表 | router.html |
| `system.app`（getInfo/terminate/canIUse） | 默认可用；无支持表 | app.html |
| `system.device`（getInfo/存储） | 默认可用；getDeviceId/getSerial 需 DEVICE_INFO 权限；无支持表 | device.html |
| `system.prompt.showToast` | 默认可用；无支持表 | prompt.html |
| `system.configuration.getLocale` | 默认可用；无支持表 | configuration.html |
| `media` / `sensor` | 文档列有此类接口，但本次**未查到其在 9/9 Pro 的支持明细**，**不要在未验证前依赖** | — |

> 关于 interconnect：文档中 fetch/request/uploadtask/network/vibrator 都有逐设备「支持明细」表，唯独 interconnect 页**没有**该表，FAQ 也只描述「手表↔手机」通信的通用调试方法而未列出支持机型。因此 **interconnect 在 9 Pro 上是否可用，官方文档未明示，必须用真机 + 配套手机 App 验证**。这是本方案最大的不确定点。

---

## i) interconnect 对端（手机 App）开发要求

来源：设备通信 interconnect https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html ；FAQ

### i.1 手表端 API

```js
import interconnect from '@system.interconnect'
const connect = interconnect.instance()   // 单例，后续收发都基于它
```

- **连接状态**：`connect.getReadyState({success})` → `data.status`：1 连接成功 / 2 断开；错误码 1006=连接断开。
- **诊断**：`connect.diagnosis({timeout=10000, success})` → `status`：0 OK / 204 连接超时 / 1001 对端 App 未安装 / 1000 其他。
- **发送到手机**：`connect.send({ data: {str, num}, success, fail })`；fail 错误码 204/1006。
- **事件回调**：
  - `connect.onmessage = (data) => {}` —— 收手机发来的数据（`data.data` 为 String）。
  - `connect.onopen = (data) => {}`（`data.isReconnected` 是否重连）
  - `connect.onclose = (data) => {}`（`data.code/data.data`）
  - `connect.onerror = (data) => {}`（1000 未知 / 1001 手机 App 未安装 / 1006 断开）
- 连接由系统自动建立/维护，开发者只需注册回调；**进入页面立即取状态常拿到 DISCONNECTED，需轮询/等 onopen**（FAQ）。

### i.2 对端（手机三方 App）硬性要求

- **包名一致**：快应用 manifest `package` == 手机 App 安卓包名。
- **签名一致**：快应用 rpk 用的证书（`private.pem`/`certificate.pem`）必须与手机 App 安卓签名证书同源（从 jks 提取，见 g.4）；否则通信被拒（FAQ「签名不正确」）。
- 真机测试建议先按包名卸载旧包再装新包，确保图标替换干净。

### i.3 文档与 Demo 获取

interconnect 页底部「参考附录」提供两个下载：
1. **《小米穿戴第三方 APP 能力开放接口文档》**（手机对端开发接口说明）——下载入口位于 interconnect 页底部「参考附录 > 点击下载」：https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html
2. **interconnect 开发测试 demo**——同页「点击下载」。

> 消息大小限制：interconnect 文档**未给出单条消息大小上限**，本规格不做臆测；实际传 TXT 时建议**分片/分块发送**，在手机端重组。

---

## j) 对本小说 App 的落地提示（基于上述能力）

1. **网络路径**：手环无 fetch/download，「搜索/下载 TXT」必须由手机端 App 完成，再经 interconnect 透传到手环；手环端用 `system.file.writeText({uri:'internal://files/books/xxx.txt', text, append:true})` 落盘到 Files 分区，书架元信息（书名/进度）存 `system.storage`。
2. **阅读页**：用 `<scroll scroll-y>`（定高）+ `<text>` 渲染；`scrolltop/scrollbottom` 记录阅读进度，`onBackPress` 拦截返回时先存进度。
3. **删除**：`file.delete({uri})` 删 TXT + `storage.delete` 清元信息。
4. **小屏触控**：矩形屏 336×480，按钮用 `<input type="button">`，翻页/滑动用 `swipe` 事件与 `scroll` 组件；操作反馈用 `prompt.showToast` + `vibrator.vibrate({mode:'short'})`。
5. **最大风险**：interconnect 在 9 Pro 上是否可用文档未明示，应优先做一次「手机 App ↔ 手环 9 Pro」联通 POC，再决定是否全量开发。

---

## k) 联网能力专项复查（2026-09-30 补充）

> 起因：确认手环 9 Pro 能否不走手机、由 QuickApp 直接联网，以及是否存在 BLE/socket/websocket 通路。结论先行见 k.5。

### k.1 Vela QuickApp 全部联网相关接口（穷举）

「网络访问」分类（https://iot.mi.com/vela/quickapp/zh/features/network/ ）官方仅列 4 个 feature，**没有 socket / websocket / TCP / UDP / MQTT / 数据通道类接口**：

| feature | 作用 | 9 / 9 Pro 支持 | 来源 |
|---|---|---|---|
| `system.fetch` | HTTP 请求（text/json/file/arraybuffer） | **不支持** | https://iot.mi.com/vela/quickapp/zh/features/network/fetch.html |
| `system.request` | 文件下载 download + onDownloadComplete | **不支持** | https://iot.mi.com/vela/quickapp/zh/features/network/request.html |
| `system.uploadtask` | 文件上传 + 进度 | **不支持** | https://iot.mi.com/vela/quickapp/zh/features/network/uploadtask.html |
| `system.interconnect` | 与配对手机 App 双向收发数据 | **文档未明示**（无支持明细表） | https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html |
| `system.network` | 查询网络类型 getType/subscribe | **不支持** | https://iot.mi.com/vela/quickapp/zh/features/system/network.html |

另在「系统能力」分类下有一个 BLE 接口：

| feature | 作用 | 9 / 9 Pro 支持 | 来源 |
|---|---|---|---|
| `system.bluetooth.ble` | **BLE GATT Client**：createScanner 扫描、createGattClientDevice 连外设、connect/disconnect、getServices、read/writeCharacteristicValue、setNotifyCharacteristicChanged、onBLECharacteristicChange、setBLEMtuSize(22~512) 等 | **不支持**。其支持明细表 11 款设备里**仅 Xiaomi Watch S5 支持**，手环 8 Pro / 9 / 9 Pro / 10、各 Watch S3/S4、REDMI Watch 均「不支持」；且明确「后台运行限制：禁止使用」 | https://iot.mi.com/vela/quickapp/zh/features/system/bluetooth.html |

> 即：QuickApp 层**既无 HTTP，也无原始 socket/websocket，连主动连外设 BLE GATT Client 在 9/9 Pro 上也是关闭的**。

### k.2 澎湃OS开发者平台（dev.mi.com）是否有新网络能力？

- dev.mi.com/xiaomihyperos 平台**确有 WebSocket 接口**（`ws.send/close`，ws/wss，2026-08 更新），但其前提写明：运行环境为 **webview / cocos runtime / unity runtime**（富应用运行时）。来源：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2173
- 这是**另一套运行时**：面向跑澎湃OS 富应用的设备（如 Xiaomi Watch S5 系列可装 cocos/unity 富应用），**不是手环 9 Pro 所用的 Vela 轻量快应用（ux/rpk）运行时**。手环 9 Pro 跑的是 Vela OS（iot.mi.com/vela/quickapp 这套），因此**该 WebSocket 不能用于本手环快应用**。两边的支持明细表以 iot.mi.com/vela/quickapp 为准，未发现对 9Pro 新增 HTTP 能力的说明。

### k.3 手环三方应用"如何联网"的官方/社区口径

- 官方文档唯一的出数通道就是 **interconnect：手表与配对手机 App 通信，连接由系统自动建立**，手机是天然网络网关（interconnect.html 首句"用于和搭配使用的手机 app 进行通信，收发手机 app 数据"）。
- 手机对端（你的安卓 App）通过官方 **《小米穿戴第三方 APP 能力开放接口文档》**（interconnect 页底部"参考附录"提供下载）实现与手环的对接；该 SDK 即"穿戴开放能力"，透传上网的 HTTP 由手机端完成。
- 社区旁证：B站等三方 App 在手环 9/9 Pro 上首次打开提示"未联网"，需在手机「小米运动健康」里同步后才连通，登录靠手机端 App 扫码授权——与"手机网关 + 手机侧鉴权 + 手环透传"模型一致（来源：抖音实测视频 https://www.iesdouyin.com/share/video/7516110823178472719 ；商品页也写明手环 9 Pro"开放三方应用、支持个人开发者生态" http://www.mi.com/prod/xiaomi-shouhuan-9-pro ）。
- 手环本身无 Wi-Fi，健康数据也要"必须定期连接手机才能同步"（小米官方 FAQ http://www.mi.com/hk/support/faq/details/KA-516143/ ）——硬件上就没有独立联网能力。

### k.4 关于"ESP32 经 BLE GATT 给手环供网"的社区先例

- **在 QuickApp（rpk）层此路在 9/9 Pro 上不通**：因为 `system.bluetooth.ble`（手环作为 GATT Central 去连 ESP32 外设）在 9/9 Pro 上被官方支持表判为"不支持"，仅 Xiaomi Watch S5 可用。
- 因此该社区先例**大概率发生在支持 BLE Client 的机型（如 Watch S5），或走原生固件/非快应用手段**，不能直接套用到本手环 9 Pro 快应用。如需复用，须先在 9 Pro 真机验证 bluetooth.ble 是否真的被禁（文档如此标注，应视为不可用）。

### k.5 结论（一句话）

**9 Pro 的 Vela QuickApp 不能直接 fetch/HTTP，也没有 socket/websocket/可用的 BLE Client；手环上一切联网都必须由配对手机 App 当网关、经 `system.interconnect` 透传，手环端再用 `system.file` 落盘——这是文档支持的唯一通路。**

可行通路清单（标注依据强度）：

| 通路 | 能否在 9 Pro QuickApp 实现 | 前提 / 限制 | 依据强度 |
|---|---|---|---|
| 手机 App 网关 + `system.interconnect` 透传 | **理论唯一可行**，但**9Pro 是否支持 interconnect 文档未明示，须真机 POC** | 手机安卓包名=manifest package、签名同源；HTTP/TXT 下载与登录鉴权都在手机端做，分片透传；消息大小上限文档未给 | 文档支持（API 齐全）＋ 9Pro 可用性=**文档未明示** |
| 手环直连 ESP32 走 BLE GATT 供网 | **QuickApp 层不可行** | `system.bluetooth.ble` 在 9/9 Pro「不支持」，仅 Watch S5 支持；禁后台 | 文档明确（bluetooth.html 支持明细） |
| 手环直连 HTTP/fetch/websocket | **不可行** | fetch/request/uploadtask/network 在 9/9 Pro 全"不支持"；Vela 无 socket/websocket 接口 | 文档明确 |
| 原生层扩展（写 native lib 经 `app.loadLibrary`） | **理论可探索，门槛极高** | `app.loadLibrary` 需"与厂商合作"；非通用开放能力，且手环 9 Pro 是否开放未知 | 文档提一句、可行性=**推测/未验证** |

> 落地建议：保留联网功能，把它重构为"**手机端 App 负责全部联网与登录鉴权 → interconnect 分片下发 TXT → 手环离线阅读/删除**"；手环端不做任何 HTTP。立项前第一件事是用 POC 验证 9 Pro 上 interconnect 是否真的能与配对手机 App 收发（这是整条链路唯一未被文档钉死的环节）。

