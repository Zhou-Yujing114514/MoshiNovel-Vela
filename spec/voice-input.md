# 小米手环 9 Pro + Vela QuickApp 语音转文字输入可行性调研

> 目标场景：手环 QuickApp（虚空终端聊天）里，用户期望「按住说话 → 录音 → 语音转文字 → 编辑/发送」。
> 目标平台：小米手环 9 Pro（Vela OS，QuickApp / 快应用 rpk，文档站 https://iot.mi.com/vela/quickapp/zh/ ）
> 调研时间：2026-09-30
> 既有能力矩阵：见同目录 `vela-capability.md`（本文补全其遗漏的 audio/record 行）。
> 原则：只记录文档明确写出的能力；文档没列的就写"文档未列"，不臆造不存在的 API。

---

## 0. 结论速览（一句话）

- **录音 API**：Vela QuickApp 确有录音接口 `@system.record`，但官方「支持明细」表**明确标注"小米手环 9 / 9 Pro = 不支持"**，全表仅 Xiaomi Watch S5 支持——**三方 QuickApp 在 9 Pro 上录不到音**。
- **语音识别 / 小爱开放度**：Vela QuickApp 文档站**没有任何 speech/ASR/语音转文字接口**；小爱同学对三方 rpk 应用**不开放"应用内调用麦克风做 STT 并回传文本"的能力**（它只是系统级语音助手 + 面向手机/音箱的"语音唤起技能"，社区实测此路不通）。
- **可行性**：手环端「按住说话 → 录音 → 转文字 → 发送」在 QuickApp 层 **不可行**（既录不到音，也无识别能力）。语音输入必须下沉到配对手机 App 完成，再经手机桥把文字透传回来。

---

## a) Vela QuickApp 录音 API

### a.1 接口是否存在：存在，但 9/9 Pro 被官方判为"不支持"

官方录音接口为 **`@system.record`**（manifest 需声明 `{ "name": "system.record" }`）。
文档页：https://iot.mi.com/vela/quickapp/zh/features/system/record.html

接口能力（文档原文）：
- `record.start({ duration, sampleRate, numberOfChannels, encodeBitRate, frameSize, format })`：开始录音。
  - `duration`：录音时长 ms，0 = 不自动定时、需手动 stop；
  - `sampleRate`：默认 8000，建议 8000/16000/32000/44100/48000；
  - `numberOfChannels`：1/2；
  - `format`：**pcm / opus / wav**，默认 pcm；
  - `frameSize`：填了之后走流式回调 `record.onframerecorded`（每帧 `Uint8Array`，最大 4096 字节/帧），此时 success 不再返回 uri。
- `record.stop()`：停止录音。
- success 回调返回录音文件 `uri`（落在应用缓存目录）；fail 错误码 200=空间不足 / 205=已在录音 / 202=参数错误。
- 打断规则：SCO 通话、铃声、闹钟、求救音、通知、另一路录音会抢夺音频焦点打断当前录音。

**设备支持明细（record.html 原文，逐行）：**

| 设备产品 | 是否支持录音 |
|---|---|
| 小米 S1 Pro 运动健康手表 | 不支持 |
| 小米手环 8 Pro | 不支持 |
| **小米手环 9 / 9 Pro** | **不支持** |
| Xiaomi Watch S3 | 不支持 |
| Redmi Watch 4 | 不支持 |
| 小米腕部心电血压记录仪 | 不支持 |
| 小米手环 10 | 不支持 |
| Xiaomi Watch S4 | 不支持 |
| REDMI Watch 5 | 不支持 |
| REDMI Watch 6 | 不支持 |
| **Xiaomi Watch S5** | **支持** |

> 即：录音接口本身是有的，但**小米手环 9/9 Pro 在官方表里就是"不支持"**。社区开发者实测也印证：在小米手环 9 上调用 `record.start` 直接报"接口不存在"，换到手表机型则正常（来源：米坛社区 https://www.bandbbs.cn/threads/21491/ ；另一报错贴 https://www.bandbbs.cn/threads/22044/ ）。

### a.2 其他音频类接口：都不能用来在 9 Pro 录音

- **`@system.audio`**（https://iot.mi.com/vela/quickapp/zh/features/other/audio.html ）：
  - 能力仅为**音频播放**：`play()/pause()/stop()/getPlayState()`，属性 `src/currentTime/duration/loop/volume/muted`，事件 play/pause/stop/ended/error。**没有任何录音方法**。
  - 属性 `streamType` 注释明确写："值为 music 时扬声器播放，voicecall 时听筒播放（**手表、手环设备不支持此配置**）"——旁证手环音频通路被系统通话占用。
  - 该页**没有设备支持明细表**，即 9 Pro 上能否播放文档未列；但即便能播，也与录音无关。
- **`system.media`（createAudioRecord / AudioRecorder）**：该接口出现在「手表快应用」文档站 https://watchdoc.quickapp.cn/doc/watch/api/system/media.html ，**不在** iot.mi.com/vela/quickapp 的官方接口目录里。Vela 官方「其他」分类下只有"音频 audio、弹窗 prompt"两项（https://iot.mi.com/vela/quickapp/zh/features/other/ ），「系统能力」分类下与音频相关的只有"录音 record"一项（https://iot.mi.com/vela/quickapp/zh/features/system/ ）。因此 `system.media` 不能当作手环 Vela rpk 的可用能力，**不要据此开发**。

### a.3 参数与限制小结

- 录音格式 pcm/opus/wav、采样率/码率见 a.1；流式帧上限 4096 字节；最长时长/后台时长**文档未给手环侧限制**——但因 9/9 Pro 整体"不支持"，这些参数对本目标设备无意义。

---

## b) 语音识别 / STT API 与小爱同学开放度

### b.1 QuickApp 层是否有任何识别能力：没有

- 穷举 Vela QuickApp 接口分类（https://iot.mi.com/vela/quickapp/zh/features/ ）：基本功能类 / 数据文件类 / 系统能力类 / 媒体类。
  - 「系统能力」目录仅有：网络信息、振动、屏幕亮度、录音、地理位置、传感器、事件、电量信息、系统音量、解压缩、蓝牙——**无 speech / asr / voice / 语音识别 / 语音输入任何一项**。
  - 「其他」目录仅有：音频（=播放）、弹窗。
- **结论：Vela QuickApp 文档中不存在任何把语音转成文字的 API。文档未列即视为没有。**

### b.2 小爱同学对三方 QuickApp 是否开放：不开放（仅系统层，不向 rpk 应用内输入开放）

- 小米小爱开放平台（https://developers.xiaoai.mi.com/ ）对外提供的是两类东西，**都不是"三方应用在自己界面里调麦克风做 STT 并拿到文本"**：
  1. **语音唤起技能**：用户说"小爱同学，打开 XX"，小爱经语义理解后下发 `LaunchQuickApp` / `LaunchApp` / `LaunchShortcut` 指令**打开应用**（来源：https://developers.xiaoai.mi.com/documents/Home?type=/api/doc/render_markdown/SkillAccess/skill/VoiceAssistantSkill/VoiceAssistantSkillMain ；指令清单 https://developers.xiaoai.mi.com/documents/Home?type=%2Fapi%2Fdoc%2Frender_markdown%2FVoiceserviceAccess%2FDevice%2Fdevelop%2FProtocolDocument%2FEndpoint ）。这是"语音→打开页面"的单向导航，识别文本（query）只在小爱云端做意图理解，**不会回传给被唤起的三方应用当输入框文本**，且该通道面向手机/音箱/电视等设备的技能形态，手环 rpk 不在此列。
  2. **设备端语音服务集成（SpeechRecognizer）**：面向**设备厂商**把小爱语音服务烧进自有设备固件（设备端上报 Recognize Event 到云端做识别），来源 https://developers.xiaoai.mi.com/documents/Home?type=/api/doc/render_markdown/VoiceserviceAccess/Device/develop/ProtocolDocument/EndpointAbility/EndpointAbilityVoiceInput 。这是固件/系统层接入，**三方 rpk 应用无权调用**。
- **社区实测旁证**（米坛社区，2026-01）：用户问"9pro 不支持第三方应用调用麦克风 api，是不是可以通过小爱同学实现语音转文字呢"，得到的答复是"**答案是否定的**"。来源：https://www.bandbbs.cn/threads/23948/

> 表述口径（写进产品文档时请照此措辞）：**小爱同学在手环 9 Pro 上属于系统级语音助手，Vela QuickApp 官方文档未向三方应用开放应用内语音识别/语音转写接口；三方 rpk 既不能自己录音，也不能借小爱把语音转成输入文本。** 不要在任何文档/UI 文案中声称"支持小爱语音输入"。

---

## c) 可行性结论

| 环节 | 能否在 9 Pro QuickApp 内完成 | 依据 |
|---|---|---|
| 按住说话 → 调用麦克风录音 | **不能** | `@system.record` 支持明细明确"小米手环 9/9 Pro = 不支持"（record.html）；`@system.audio` 仅播放；无其他录音接口 |
| 录音文件 → 语音转文字（STT） | **不能** | Vela QuickApp 文档无任何 ASR/STT 接口；手环本身又无联网（见 vela-capability.md k 节），无法本地或直连云端识别 |
| 借小爱同学在应用内转写 | **不能** | 小爱仅系统层 + 语音唤起技能，不向 rpk 应用内输入开放；社区实测否定 |
| 编辑/发送文本 | 可以 | 文本编辑、发送走既有 UI 与手机桥即可（与录音/识别无关） |

**总判定：手环端「录音 → 转文字 → 发送」在 QuickApp 层不可行。**
原因链：麦克风录音接口被官方在 9/9 Pro 上关闭（record.html 支持明细）→ 没有音频字节就无从谈起 STT；而 QuickApp 层本来就没有任何语音识别 API，小爱也不对三方应用内输入开放。硬件上 9 Pro 确实带麦克风（支持蓝牙通话，用户已确认；第三方参数页提及双 MIC 降噪 https://detail.zol.com.cn/ProductComp_param_2112071-1467064.html ），但该硬件通路被系统蓝牙通话/小爱占用，**不对三方 rpk 开放**——"有麦"≠"三方能录"。

---

## d) 推荐输入流程（1 主 + 2 备）

> 前提沿用 vela-capability.md：手环 9 Pro 无出网，一切联网与重活都在配对手机 App 完成，经 `system.interconnect` 透传；而 interconnect 在 9 Pro 上"文档未明示、须真机 POC"。下述主方案同样依赖 interconnect，落地前先过该 POC。

### 主方案：手机桥语音输入（推荐）

手环端**不录音**，把"听"这一步整体搬到手机：

1. 聊天页放一个「语音输入」按钮（小屏矩形屏 336×480，用 `<input type="button">`）。
2. 用户点击 → 手环经 `interconnect.send` 通知手机端 App："请拉起语音输入"。
3. 手机 App 调起系统语音输入（Android `SpeechRecognizer` / 输入法语音键 / 手机端任意 ASR），用户在手机上说话转成文字。
4. 识别得到的**文本**经 `interconnect` 回传手环（文本短，单条即可，无需分片）。
5. 手环聊天输入框展示该文本，用户可在手环上做简单编辑/删除（或直接发送）→ 发送到虚空终端。

- 优点：绕开"手环不能录音/不能识别"两个硬限制；手机麦克风与 ASR 质量远好于手环。
- 代价：语音输入时要看一眼手机，交互不如"手环上按住说话"顺滑；依赖 interconnect 可用（待 POC）。

### 备选 1：预设短语 / 快捷回复（零语音、最稳）

- 在手环端维护一组高频短句（"在""嗯，说""等一下""收到""这个怎么弄"……），聊天页做成可点选的短语 chips 列表，一点即插入/发送。
- 完全不依赖录音、STT、interconnect 语音通路，断网/手机不在身边也能用；适合手环小屏快速回消息。

### 备选 2：极简文本输入 + "去手机上聊"兜底

- 手环端提供受限输入：常用字/拼音首字母选词、或从历史消息里重发；输入框旁放「去手机回复」按钮，点击经 interconnect 把当前会话透到手机 App，用户在手机原生键盘上打完字再透回手环。
- 不追求语音，用"手机补全长文本"解决手环打字难的问题。

> 关于小爱：主/备方案里**都不调用小爱**。文档与 UI 中统一表述为"语音输入由手机端完成"，不要写"小爱语音输入"，避免用户误以为可在手环上直接呼麦。

---

## e) 来源清单

- 录音 record（含 9/9 Pro 不支持的支持明细）：https://iot.mi.com/vela/quickapp/zh/features/system/record.html
- 音频 audio（仅播放，无录音；voicecall 标注手环不支持）：https://iot.mi.com/vela/quickapp/zh/features/other/audio.html
- 接口总览（基本/数据/系统能力/媒体四类）：https://iot.mi.com/vela/quickapp/zh/features/
- 系统能力目录（无 speech/asr 项）：https://iot.mi.com/vela/quickapp/zh/features/system/
- 其他目录（仅 audio 播放 + prompt）：https://iot.mi.com/vela/quickapp/zh/features/other/
- 手表快应用 system.media（另一文档站，非 Vela 手环官方目录，勿误用）：https://watchdoc.quickapp.cn/doc/watch/api/system/media.html
- 小爱开放平台首页（ASR 能力面向技能/设备厂商）：https://developers.xiaoai.mi.com/
- 小爱语音唤起技能（LaunchQuickApp/LaunchApp，语音→打开应用，非应用内输入）：https://developers.xiaoai.mi.com/documents/Home?type=/api/doc/render_markdown/SkillAccess/skill/VoiceAssistantSkill/VoiceAssistantSkillMain
- 小爱指令清单（Endpoint）：https://developers.xiaoai.mi.com/documents/Home?type=%2Fapi%2Fdoc%2Frender_markdown%2FVoiceserviceAccess%2FDevice%2Fdevelop%2FProtocolDocument%2FEndpoint
- 小爱 SpeechRecognizer（设备厂商固件层接入，非三方应用调用）：https://developers.xiaoai.mi.com/documents/Home?type=/api/doc/render_markdown/VoiceserviceAccess/Device/develop/ProtocolDocument/EndpointAbility/EndpointAbilityVoiceInput
- 社区实测：手环 9 调 record 报接口不存在：https://www.bandbbs.cn/threads/21491/
- 社区实测：9 Pro 不支持三方调麦克风、经小爱转写"答案是否定的"：https://www.bandbbs.cn/threads/23948/
- 硬件旁证（9 Pro 带麦克风/双 MIC，支持蓝牙通话，用户已确认）：https://detail.zol.com.cn/ProductComp_param_2112071-1467064.html
- 既有能力矩阵（联网/文件/interconnect 等）：同目录 `vela-capability.md`
