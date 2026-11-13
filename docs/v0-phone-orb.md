# 手机悬浮球与入网（v0 起点）

> 状态：Draft（2026-11-11 同步），从 dsh-orb-cordis 的计划文档摘出，供本仓库独立开发。
> 上游代码：https://github.com/ccch1mneyyy/dsh-Go （AGPL-3.0）。本仓库 `main` 是 `8f31b2ed` 的一份快照，不再包含那之前的 1250 次提交。`upstream` 远程还在，需要对照上游时再 `git fetch`。
> 快照里去掉了用不到的内容：界面回归截图（`app/test/**/goldens`）、官网（`site/home`）、插件市场（`market`）、分享页部署（`services`）、论坛主题（`infra`）、文档配图（`docs/assets`）。App、本机引擎、构建脚本、`packages/`、`sdk/`、`examples/` 都保留。
> 互联接口以 dsh-mesh 仓库的 `docs/contract-v1.md` 为准。
> 应用改名、换包名、换图标在对外分发前做；内部开发先沿用上游。
> 本文是 dsh-orb-cordis 仓库 `docs/17-implementation-plan.md` 第 5、6 节的同步副本；文中的 14–19 均指该仓库 `docs/` 下的同名文档，与 17 冲突时以 17 为准。

## 5. 工作流 A：手机端悬浮球（`dsh-orb-android` 仓库）

### 5.1 现状（fork 基线，已核实）

`/tmp/dshgo`（dsh-Go `1.0.14`）：

| 能力 | 文件 | 现状 |
|---|---|---|
| 手机操控工具 | `phone/PhoneGuest.kt:7-71`、`PhoneToolCatalog.kt`、`McpServer.kt` | 引擎启动时写入 `/opt/dsh-app/phone/phone.patch.yml`，以 `--patch` 在**根上**插入 MCP 服务 `phone`；**所有 preset 自动继承**，模型看到 `mcp__phone__{apps,open,intent,notify,clipboard,screen,collect,tap,type,scroll,key,tap_xy,handoff}`。前提：「手机操控」开关打开且不在安全模式 |
| 屏幕串行 | `phone/PhoneTaskSessions.kt:245-491`、`SerialWorker.kt`、`McpServer.kt:131` | 主屏独占；另一个会话要用屏幕时最多等 30 秒，然后返回「屏幕或应用资源正被另一个对话使用…」；后台槽默认 1、最多 3 |
| 内置 preset | `@deepseek-ai/dsh-web-app`；`runtime/bridge/dsh-go-bridge/cordis.patch.yml` | `standard`（标准模式）、`ptc`、`minimal`、`cordis`；dsh-Go 自加 `dsh-go-companion`、`dsh-go-companion-full`、`orb-phone`（悬浮球）；打包 mesh 插件时另有 `phone-background`（后台模式）。可用 overlay 插入新 preset |
| 工具包 | `runtime/locks/dsh-0.2.2-rc.1/package-lock.json` | `@deepseek-ai/dsh-tool-bash`、`dsh-tool-web`、`dsh-tool-ask-user` 都随 DSH 打包 |
| 悬浮窗 | `pet/PetOverlay.kt`、`OverlayWindowOwner.kt`、`EdgeSnap.kt` | **原生 Kotlin View**；`TYPE_ACCESSIBILITY_OVERLAY` 或 `TYPE_APPLICATION_OVERLAY`；拖拽、吸边、长按菜单、气泡 |
| 悬浮球骨架 | `phone/a11y/OverlayBall.kt`、`ControlOverlay.kt`、`OverlayCapsule.kt` | 48 dp 球 + 停靠 + 按住停止 |
| 问一句输入条 | `pet/AskBar.kt` | 按住说话已实现（滑出取消、最少 0.3 秒、静音阈值 400 ms）；原生 `EditText`，IME 已特殊处理 |
| 截图 | `pet/PetScreenshot.kt`、`phone/a11y/ScreenShooter.kt` | 全走无障碍；API 34+ `takeScreenshotOfWindow`；API 30–33 hide-for-capture + `takeScreenshot`；`hideForCaptureWithReceipt` 有引用计数 |
| 安全确认 | `phone/SafetyGate.kt`、`PhonePrompts.kt`、`ControlOverlay.kt` | 银行/支付拒绝表、微信逐次确认、`ask`/`task`/`always` 三档、`handoff`、确认卡 30 秒超时 |
| 语音 | `voice/WavRecorder.kt`、`VoicePlugin.kt`、`MicArbiter` | 16 kHz 单声道 PCM16；SenseVoice 优先，回退系统 ASR；录音只在原生侧 |
| 本机引擎 | `runtime/`、`runtime/build-runtime.sh:40-41` | PRoot + DSH `0.2.2-rc.1` + Node `24.22.1`；`dsh web` 在 `127.0.0.1:3080`；ABI 只有 arm64-v8a / x86_64 |
| 通知 | `notify/ChatNotifier.kt`、`state/notify_state.dart`、`runtime/DshService.kt` | 一轮结束、等审批、等回答都能发系统通知；App 不在前台时也能发（进程存活即可）；`DshService` 前台服务保活引擎 |
| 手机文件 | 设置「访问手机文件」 | 授权后内部存储挂到引擎的 `/sdcard`；默认关，切换会重启引擎 |
| 聊天 UI | `app/lib/ui/chat/*` | 在 Flutter Activity 里，不在悬浮窗里 |

**两个硬约束**：

1. **悬浮窗是原生 Kotlin 视图，不是 Flutter。** 没有任何浮层能跑 Flutter 组件，`ui/chat` 的 transcript / 工具卡 / 审批卡都依赖 Activity。
2. **`PetScreenshot` 现在的用法不是「发送时截图」。** 菜单「截图并问」会隐藏浮层、截图、**打开 App**、填进输入框，**不自动发送**。Orb 需要「松手即截图并随消息发出，全程不切 App」的新回路。

### 5.2 Orb preset：精简工具集

截至 2026-11-12 已落地两个 preset（真机加载待下次 APK 验证）：

```yaml
# runtime/bridge/dsh-go-bridge/cordis.patch.yml（shared rows，所有桥接安装都会带上）
- insert:
    - id: preset-orb-phone
      name: '@deepseek-ai/dsh-agent-preset'
      config:
        id: orb-phone
        name: 悬浮球
        description: Computer Use：看屏幕、操作手机、网页检索与提问；悬浮球发消息时附带当前屏幕截图。
        order: 5
        plugins:
          - id: persona
            name: '@deepseek-ai/dsh-persona'
          - id: tool-bash
            name: '@deepseek-ai/dsh-tool-bash'
          - id: compaction
            name: cordis:group
          - id: tool-ask-user
            name: '@deepseek-ai/dsh-tool-ask-user'
          - id: tool-web
            name: '@deepseek-ai/dsh-tool-web'
        # mcp__phone__* 由根上的 MCP 服务继承，不写在这里。
```

- `orb-phone` 的工具就是桌面 `computer-use` 的非 GUI 部分：`bash`、网页检索/抓取、`ask_user_question`、压缩组；**没有**文件、todo、子代理、后台/设备调度工具。
- 悬浮球「问一句」新建会话时显式传 `agentPreset: orb-phone`；发消息时原生输入条先隐藏自己和宠物、收起键盘，再用 `PetScreenshot` 截当前屏，把路径随 `ask` 交给 Dart，作为一张内联图片附在 prompt 上。截图失败（安全页/无障碍未开）只发文字。`orb-phone` 会话的后续消息也走同一条路。
- 远端 host 不传这个 preset，也不截图；仍是 host 默认 preset。
- **手机操控工具不写在列表里**：`mcp__phone__*` 由根上的 MCP 服务提供，所有 preset 自动继承。Orb 首次引导必须打开无障碍与「手机操控」开关；没打开时 Orb 只能做文字问答，要明确提示。

后台模式（供其他设备派任务，不依赖 A4 的 `background_agent` 调度器）：

```yaml
# runtime/bridge/dsh-go-bridge/phone-background.patch.yml，仅在打包 @dsh-mesh/plugin 时
# 由 rtbuild.py 追加到 /etc/dsh-app/dsh-web.patch.yml
- insert:
    - id: preset-phone-background
      name: '@deepseek-ai/dsh-agent-preset'
      config:
        id: phone-background
        name: 后台模式
        description: 标准工具 + 手机操控 + 文件回传，供其他设备派来的后台任务使用。
        order: 21
        plugins:
          # 标准工具集去掉需要人在手机旁的部分：
          # plan mode / ask_user_question / 子代理 / present / plugin-manager / plugin-market
          # 加上：
          - id: tool-mesh-return-files
            name: '/opt/dsh-go/plugins/@dsh-mesh/plugin/lib/return-files.mjs'
```

- 传 `DSH_PLUGIN_CONFIG='{"platform":"android","incomingPreset":"phone-background"}'` 后，其他设备派来的任务就用这个 preset。文件回传工具与桌面 `dsh-orb/mesh/return-files` 是同一份实现（`@dsh-mesh/mesh/return-files`），任务结束后发起端下载 `.mesh-out/<sessionId>/`。
- 没有打包 `@dsh-mesh/plugin` 时，这个 preset 不会出现在 overlay，避免一个 import 失败的 preset 常驻列表。
- A4 的 `background_agent`（手机自己派本机后台 + 任务卡 + 完成通知）仍未实现；本阶段的后台模式是被动接收远程任务，由现有 `ChatNotifier` 负责完成/等审批通知。

| 阶段 | 模型可见工具 |
|---|---|
| A0–A3（当前 `orb-phone`） | `bash`、网页检索与抓取、`ask_user_question`、`mcp__phone__*` |
| A4（未做） | 上面 + `background_agent` / `background_agent_status` / `background_agent_stop` |
| A5 | 上面 + `device_agent` / `device_agent_status` / `device_agent_stop` |
| 后台模式（当前 `phone-background`） | 标准工具（去 plan/ask_user/子代理/present/plugin 管理）+ `mesh_return_files` + `mcp__phone__*` |

### 5.3 后台：dsh-Go 标准模式

> 2026-11-12：本节是 A4 的 `background_agent` 调度器设计，**尚未实现**。本阶段已落地的是 5.2 的 `phone-background` preset（被动接收其他设备的任务）。

- 工具名 `background_agent`（另有 `_status`、`_stop`），由手机仓库自己的小包 `@dsh-orb-android/background-agent` 提供，不依赖dsh-orb-cordis 仓库。写法参照dsh-orb-cordis 仓库 `code_agent` 的派发与完成通知逻辑（`packages/computer-use/src/code-agent.ts`、`code-agent-completion.ts`）。可以照着重写，也可以拷贝后保留 MIT 声明。区别有两点：目标 preset = `phone-background`（标准工具 + 手机操控 + `mesh_return_files`）；**不挂 `attachUnattendedCodeAgent`**（`code_agent` 写死目标 `desktop` 并自动放行，见 `code-agent.ts:353`）。dsh-orb-cordis 仓库的 `code_agent` 不动。
- 不拷贝 `computer-use` 包本身：它带 macOS/Windows 原生后端，在 PRoot 里加载不了。只拷派发、完成通知这两块纯 JS 逻辑。
- 后台会话的审批与提问不自动处理：dsh-Go 已有「等审批/等回答」系统通知（`ChatNotifier`）；Orb 面板的任务卡显示「等待确认」，点开可在面板里答（5.5）。
- **屏幕共用**：标准模式也继承了 `mcp__phone__*`。派发时在任务正文写明「后台任务，除非任务本身需要，不要操作手机屏幕」。即使后台去操作，dsh-Go 也保证同一时间只有一个会话占主屏，另一方等 30 秒后拿到可读错误，Orb 把这句原因告诉用户。
- A0 验证：`@dsh-orb-android/background-agent` 在 PRoot Node 下能加载，能以 `phone-background` 建后台会话。
- 完成后：Orb 会话收到 followup → 面板更新；App 不在前台时由 `ChatNotifier` 发「后台任务完成」。

### 5.4 要串起来的流程

> 2026-11-12：完整的「长按球录音 → 松手发送」尚未做；已实现的是现有 AskBar「问一句」发送时隐藏输入条和宠物、收起键盘并截图附在消息里。下面的原生球手势、波形窗、面板仍是 A0–A3 的目标。

现有零件是分开的，Orb 要做一条原子流程：

```
长按球
  → 录音开始（原生 WavRecorder，走 AskBar 那条路径，不走 VoiceInputController）
  → 球旁出现独立的小波形窗（不改球窗口尺寸）
  → 松手
      ├─ 上滑取消 → 丢弃录音，回 idle
      └─ 正常 → 转写与截图并行：
           1. ASR 转写（SenseVoice 优先，回退系统 ASR）
           2. hideForCapture(true) 隐藏球 + 面板，等约 120 ms
           3. 截图（API 34+ 窗口截图；30–33 显示器截图）
           4. hideForCapture(false) 恢复
  → 组装消息：{ text: 转写, image: 截图路径 }
  → 投递到 Orb 自己的会话（orb-phone preset）
  → 面板展开，显示「已收到 + 截图缩略图」
  → Agent 看截图 → 调 mcp__phone__* 操作当前屏，或调度工具派后台
```

> 三条边界：
> 1. **必须先隐藏再截图**，用 `hideForCaptureWithReceipt()` 拿到被隐藏的窗口 id，事后可断言「球确实被隐藏」。
> 2. **截图失败不阻断发送**：`SECURE` / `ACCESSIBILITY_REQUIRED` / `UNSUPPORTED` 时只发文字，并加一句「当前屏不可截」。现有 `PetScreenshot.Error` 已覆盖。
> 3. **全程不打开 Activity**。

**必须新建/改的部分**：

| 项 | 现状 | 要做 |
|---|---|---|
| 长按 → 录音 → 松手发送 | `AskBar` 有，`OverlayBall` 的「按住」是**停止** | 把 AskBar 的触摸语义搬到球上 |
| 录音路径 | `VoiceInputController` 在 `paused`/`hidden` 时主动取消 | Orb 必须走原生 `WavRecorder` |
| 发送时截图 | 「截图并问」会跳 App、不自动发送 | 新写 `OrbScreenshot.kt` |
| 截图里没有球 | `hideForCaptureWithReceipt()` 返回被隐藏的窗口 id | Orb 窗口纳入 `CaptureVisibility` 引用计数 |
| 面板 | 浮层只有输入条 | 原生面板（5.5），A0 就有最小版 |
| 审批/问答卡 | Flutter `ApprovalCard` 在 Activity 内 | 面板里做原生卡（允许本次 / 拒绝 / 选项回答） |
| preset | 无 | `orb-phone`（5.2） |
| 完成后回到 Orb | 有 `ChatNotifier` / 气泡，不是 Orb 形状 | 面板状态更新 + 通知 |

### 5.5 面板

> 2026-11-12：面板的具体交互、长条三态、书签规则与实验方法见 [`v0-phone-orb-ui.md`](v0-phone-orb-ui.md)。下面的「A0–A4 分阶段内容」已被该文档取代：v0.1 的面板是一条从球里伸出的长条（输入 / 运行 / 完成三态 + 停止），不是浮层里的完整 transcript。

**宿主选型：原生面板**（Kotlin 精简 transcript），不在浮层里跑 Flutter（需要第二个 `FlutterEngine`，IME、焦点、生命周期都没验证过），也不点球进 Activity（违背「不离开当前 App」）。

**分阶段的内容**（与 16 第二段第 5 条对齐）：

| 阶段 | 面板内容 |
|---|---|
| A0 | 最近若干条消息（纯文本）+ 状态行 + 停止 |
| A1 | 截图缩略图 |
| A2 | 工具状态行（「正在点击…」「等待确认」） |
| A3 | 历史（Orb 会话列表，纯文本）、新建、审批/提问卡 |
| A4 | 后台任务卡（运行中/等待确认/完成/失败，点开看最后结果） |

不做 Markdown/代码高亮；需要完整阅读时提供「在 App 中打开」。输入沿用 AskBar 的实现，不新造。

### 5.6 关键风险与拆解

| 风险 | 说明 | 拆解 |
|---|---|---|
| **后台录音被静音** | 现有两个前台服务都是 `specialUse`，没有 `microphone` 类型；API 29+ 有 `isClientSilenced`；各厂商策略不同 | **A0 第一件事**：另一个 App 在前台时按住球录音，量是否静音、拿到多少字节；不成立就补 `microphone` 类型前台服务 |
| 发送时截图的时序 | hide 没有合成器确认，靠 120 ms 估计；`FLAG_SECURE` 页全黑 | A1 单独验证：微信/银行/游戏各截一张；API 30 与 34 两条路径 |
| 前后台抢屏幕 | 标准模式也有手机工具 | dsh-Go 的主屏独占 + 30 秒等待；Orb 转述错误 |
| 厂商悬浮窗/无障碍限制 | `canDrawOverlays` 会撒谎；无障碍会被杀 | 沿用 1×1 窗口探测；优先无障碍 overlay |
| 引擎在后台被回收 | PRoot + Node 重；电池优化 | A4 验证：派长任务后离开 App 10 分钟，看是否跑完并通知 |
| ASR 延迟 | PRD 要求 3 秒内看到消息 | 先显示「转写中」；SenseVoice 优先 |
| **AGPL-3.0** | `app/` 是 AGPL；品牌/素材不在 AGPL 内 | 先内部自用；对外分发前做许可评估；换自己的名字与美术 |

### 5.7 Android 兼容基线

- 继承 dsh-Go：**minSdk 26、targetSdk 28、只出 arm64-v8a 与 x86_64**（`app/android/app/build.gradle.kts:45-65`）。
- 无障碍是硬依赖（截图 + CU）。
- 截图：API 34+ 窗口级；API 30–33 hide-for-capture；低于 API 30 不支持截图。
- 构建链：Linux x86_64、JDK 17、Flutter 3.47.7、AGP 9.1.0、Kotlin 2.4.0。

### 5.8 退出标准

对应 `16-v0-plan.md` 7.2 的 7 条；额外加：

- `orb-phone` 会话的工具清单与 5.2 表中 A4 一行一致。
- 截图里不出现球和面板（窗口 id 集合 + 像素比对）。
- 银行/密码页只发文字并提示。
- 长按不说话（< 0.3 秒或静音）不会发空消息。
- **另一个 App 在前台时，长按球说话能正常录音并发送。**

---

## 6. 工作流 C：手机入网（A5，`dsh-orb-android` 仓库）

### 6.1 为什么用同一套 Node 插件

已核实：

- dsh-Go 的 Dart 远程客户端 `packages/dsh_remote` 是**单活动 Host**（`README.md:100`：一台设备只允许一条 Host 连接，连另一台会断开上一台），满足不了 3–5 台互联。
- dsh-Go 的本机引擎里**没有** ds-harness-remote 的 Host 插件（runtime lock 里没有），手机现在不能被别的设备连接。
- 本机引擎就是 PRoot 里的 Node 24 + DSH `0.2.2-rc.1`，和电脑上的 DSH 是同一个运行时。

所以手机入网 = 在本机引擎里加载与电脑相同的 `@dsh-mesh/plugin`。协议、证书固定、多对端连接、`device_agent`、文件回传都直接复用，手机与电脑的行为一致。

### 6.2 接入方式

- 打包：dsh-Go 现在没有安装额外插件 tgz 的机制，要新增（18 第 2 节）：在 `build-runtime.sh` 里用现有带 sha256 校验的 `fetch` 下载 `dsh-mesh` release 里的 `@dsh-mesh/plugin` tgz，解到 `/opt/dsh-go/plugins/dsh-mesh`，哈希加进构建输入哈希；由 `dsh-go-bridge` 的 overlay 按路径注册为应用级插件。配置按 18 第 4.2 节：`platform: android`、`incomingPreset: phone-background`、`remoteToolGuard: ['mcp__phone__*']`、`receiveDir`。`remote_*` 工具与远程终端由插件内部关掉，手机仓库不用管。
- 只用 Relay：`@dsh-mesh/plugin` 不随包发布 WebRTC 原生库，只用 Relay 时也不会加载（18 第 1 节）。
- 身份与配对状态在 PRoot 的 `/root/.dsh/dsh-mesh/`，随 App 数据保存；App 升级、引擎重装都不能丢。
- App 内 Flutter「互联」页通过本机引擎的 `/.dsh-mesh/v1/*` 接口（`127.0.0.1:3080`）读写，接口清单见 18 第 4.3 节：
  - 未配对：粘贴配对串或扫管理页二维码（复用 dsh-Go 现有 ML Kit 扫码），点「连接」只调一次 `POST /pair`，原样显示返回的 `message`；
  - 已配对：本机名、设备树、远程会话（看/输入/停止/审批）、退出互联。远程会话的 transcript 用 `ui/chat` 组件渲染（在 Activity 内），帧格式就是 DSH 原始事件。
- 互联页先接插件的假数据模式（`mock: true`）做完，A5 时换真实模式。
- 互联页的开关：「允许被其他设备调用」（默认开）、「允许其他设备操作本机屏幕」（默认关，6.4）、「访问手机文件」入口（跳到 dsh-Go 现有设置；切换会重启引擎，要提示）。
- 悬浮窗里的 Orb 面板只显示悬浮球派出的任务卡与审批/提问卡，不放整棵树。

### 6.3 手机作为发起端

- `orb-phone` preset 加入 `tool-device-agent`；persona 加一段：要写到别的设备上的文件、或只有电脑能做的事，用 `device_agent`。
- 结果回到手机：完成通知进 Orb 会话，面板更新；结果文件落到 `/sdcard/Download/DSH-Go/mesh/<设备名>/<taskId>/`（需开「访问手机文件」），否则落在引擎内目录并提示。

### 6.4 手机作为被调用端

- 其他设备派来的会话跑 dsh-Go 的 `phone-background` preset。它会继承 `mcp__phone__*`，所以要拦截。
- **屏幕操作拦截（D-12）**：`dsh-mesh` 的 `packages/mesh/guard.ts` 在应用级挂 `ctx.tools.guard`（dsh-orb-cordis 仓库 `code-agent-registry.ts:122` 用的是同一机制）。Host 侧 bridge 记录由远程 peer `session/create` 或 `session/prompt` 触发的会话；这些会话调用配置 `remoteToolGuard` 里的工具（手机填 `mcp__phone__*`）时，如果偏好 `allowGuardedTools` 关着，就拒绝并返回「这台手机不允许其他设备操作屏幕」。手机互联页把 `allowGuardedTools` 显示为「允许其他设备操作本机屏幕」。D-5 之后远程本来就只能 prompt 自己创建的会话，所以「按会话创建者记录」这条判定是充分的，不需要按轮次再判一次。
- 开关打开时：dsh-Go 的控制条照常显示，并加一行「来自 <设备名> 的任务正在操作手机」；`SafetyGate` 不变，敏感操作仍在手机上确认。
- **手机文件作为附件**：打开「访问手机文件」后，后台 Agent 可用 Shell 在 `/sdcard/DCIM/…` 找照片，拷到 `.mesh-out/<taskId>/`，由发起端按 4.5 的流程取回。全程不操作手机界面。
- **保活**：引擎靠 `DshService` 前台服务；被系统回收时手机就是离线，各端 30 秒内显示。A5 验证锁屏 10 分钟后仍在线。

### 6.5 A5 前置验证（可以提早做）

0. **App 能调本机接口**：Flutter 侧用运行令牌 `GET /?token=…` 换会话 cookie，带着 cookie 调 `GET /.dsh-mesh/v1/status` 成功；引擎重启后能自动重新换（18 第 4.3 节）。R0 起就能做。
1. 在手机引擎里手工装 `@dsh-mesh/plugin`（R0 起就能验证加载；R1 起能真连），用配对串连上服务器，电脑能看到手机这个节点。
2. 电脑对手机 `session/follow`（R2 起）、`session/prompt` / `session/cancel`（R3 起）正常。
3. 手机作为 Client 同时连两台电脑。
4. 拦截生效：电脑派来的会话调用 `mcp__phone__screen` 被拒；本机 Orb 会话不受影响。确认工具执行上下文能拿到会话 id；拿不到就改在 Host bridge 层拦截。
5. 10 秒心跳下的耗电与流量，锁屏 10 分钟是否掉线。

### 6.6 退出标准

对应 `16-v0-plan.md` 7.3 的 6 条，并在 5 台同时在线时回归 7.1.2。另加：全新安装的手机只在互联页粘贴配对串或扫码、点连接，不重启引擎，15 秒内显示已连接和其他设备。
