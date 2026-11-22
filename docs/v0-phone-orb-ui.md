# 手机悬浮球面板与交互规格（v0.1）

> 状态：Draft（2026-11-12）。
> 范围：Android 悬浮球（Orb）的手势、语音、长条显示框、后台/设备书签、与 dsh-Go 前台的可见性规则。
> 本文取代 `docs/v0-phone-orb.md` §5.5 的面板分阶段表；验收仍对应 `docs/v0-acceptance.md` 7.2 / 7.3。
> 书签规则以电脑端 `dsh-orb-cordis/docs/04-addenda.md` §16/17 与 `packages/helper/assets/shell.js` 的 `agentChip` / `renderAgentStrip` 为准，手机端镜像，不重新发明。
> 相关：`dsh-mesh/docs/contract-v1.md` §4.3 `/tasks`；`docs/design/pets.md`。

## 1. 目标与边界

**目标**：一个球就是入口。长按说话、单击打字；发出去以后，球边伸出一条长条显示 Agent 正在做什么、随时可停；后台 Agent 和跨设备任务用书签挂着，点一下进对应会话。

**做**：

- 本机引擎上的悬浮球 Agent（`orb-phone` preset）。
- 长按语音、单击输入、发送时截图。
- 长条：输入 / 运行 / 完成三态，终止按钮常驻。
- 书签：本机 `background_agent` 任务 + 其他设备的 `device_agent` 任务。
- dsh-Go 前台只留球；其他 App 前台按桌面的运行态逻辑展开。

**不做（v0.1）**：

- 浮层里跑完整聊天/Markdown/代码高亮；长内容一律「在 App 中打开」或点书签进会话。
- 浮层里放整棵 Mesh 树；树在 App 的互联页。
- 虚拟屏、接管整台手机。
- 远端 Host 的新面板：新流程只服务本机引擎；活动 Host 是 SSH/Relay 时仍走现有 AskBar/默认会话，不把 `orb-phone` 和截图发到远端。

## 2. 六个面与窗口

| 面 | 窗口角色（`OverlayWindowOwner.Role`） | 可触摸 | 说明 |
|---|---|---|---|
| 球 | `PET`（已有） | 是 | 宠物精灵，大小沿用 pack 的 small/medium |
| 长条 | `STRIP`（新增） | 是 | 状态/输入/停止；贴着球伸出 |
| 舌头 | `TONGUE`（新增） | **否** | 12dp 宽的接缝，压在球体边缘，`FLAG_NOT_TOUCHABLE` |
| 书签排 | `BOOKMARKS`（新增，一个窗口装多枚） | 是 | 长条上方或下方的整齐排 |
| 录音胶囊 | `VOICE`（新增） | 否 | 按住说话时的波形/提示，滑动取消由球的手势处理 |
| 审批气泡 | `BUBBLE`（已有） | 是 | 允许/拒绝 + 长按确认；App 前台时不显示 |

App 内的会话页、互联页不是浮层，不在本表。

## 3. 球

### 3.1 尺寸与位置

- 沿用当前 pack 的尺寸（`PetSize.small/medium`），不新增尺寸；不加底盘、不加标题。
- 触摸热区可以比精灵大，沿用 `PetView` 的 hit frame；不要为了面板把球做大。
- 拖动、吸边、拖到「×」隐藏沿用 `EdgeSnap` / `snoozeToday`。

### 3.2 手势

| 手势 | 其他 App 前台 | dsh-Go 前台 |
|---|---|---|
| 单击 | 空闲：开输入长条；运行/等待：打开该会话的 App（停止仍在长条 ■） | 球被隐藏，无手势 |
| 长按 | 350ms 起录音；松手发送；上滑 80dp 取消 | 球被隐藏，无手势 |
| 拖动 | 移动/吸边；长条先收回，落位后从新方向再伸出 | 同左 |
| 拖到「×」 | 今天不再显示，全部浮层收起 | 同左 |

- 长按阈值取 350ms（`ViewConfiguration` 默认 500ms 偏慢），保留现有 slop；按住不动才算录音，先移动算拖拽。
- 长按开始给一次 `HapticFeedbackConstants.LONG_PRESS`。
- 轮盘菜单（`PetMenuItem`）退役，七项去处：

| 原项 | 去处 |
|---|---|
| 问一句 | 单击长条输入 / 长按球录音 |
| 新对话 | 输入行左端 `＋` |
| 截图并问 | 发送时自动截图（输入行「带屏」开关） |
| 停止 | 长条右端 `■` |
| 切换 Host | 本机引擎专属；远端 Host 仍用现有 AskBar 的切换 |
| 今天不再显示 | 拖到「×」 |
| 设置 | App 设置；不在浮层里 |

### 3.3 状态外显

状态画在球上（环/角标），不靠气泡抢注意力：

| 状态 | 表现 |
|---|---|
| 空闲 | 宠物 idle |
| 录音 | 呼吸环 + 录音胶囊的波形/计时 |
| 转写 | 环缓慢流动 + 胶囊「转写中…」 |
| 执行 | 球顶一条旋转弧线 |
| 等确认 | 琥珀色小点 + 轻微脉冲；气泡同时出现 |
| 完成 | 绿勾 1.5s |
| 失败/离线 | 红点/灰点；点球看长条原因 |
| 有未读书签 | 右上角 6dp 绿点（不显示数字） |

`reduceMotion` 时只保留静态状态点，不做环/脉冲。

## 4. 语音输入（长按球）

```
长按球 350ms
  → 震动 + 球边冒出录音胶囊：波形 + 计时 + 「松开发送 · 上滑取消」
  → 上滑 80dp：胶囊变红「松开取消」，松手丢弃
  → 松手：
       ├─ 太短（<0.3s）或静音 → 「说话时间太短」/「没听清」，不发
       └─ 正常 → 转写 + 截图并行
            → 胶囊「转写中…」
            → 文字成功：胶囊短暂显示「已发送 · 已附当前屏」，长条从球里伸出进入运行态
            → 转写失败：胶囊显示原因，长条不出现，球回空闲
```

- 录音复用 `AskBar` 的 `WavRecorder` + `MicArbiter` + 静音检测；最长 60s（沿用）。
- 录音胶囊从球边冒出：球在屏幕上半部时向下、下半部时向上；带一个小尾巴指向球。
- 转写走现有 `transcribe` 通道（SenseVoice 优先，回退系统 ASR）。
- 发送带截图：先隐藏球、长条、舌头、书签、胶囊，约 120ms 后截图，再恢复；截图失败只发文字，并在长条/胶囊提示「当前屏不可截」。
- dsh-Go 前台时长按只发文字，不截图（截到的是 dsh-Go 自己，没有意义）。
- 录音被系统静音（后台麦克风限制）时**不自动打开 App**；胶囊提示「当前无法后台录音，点一下进 App 继续说」，由用户决定。是否补 `microphone` 前台服务由实验 E2 决定。

## 5. 长条

### 5.1 形态

| 形态 | 高度 | 内容 |
|---|---|---|
| 输入态 | 80dp（两行） | 汇总/状态行 + 输入行（`＋`、输入框、麦克风、发送） |
| 运行态 | 48dp | 状态点 + 一行状态 + `■`；点状态行展开输入行（插话） |
| 完成/失败 | 48dp | `✓ 完成` / `! 失败：…`；完成后 5s 自动收起，失败保留 |

- 宽度固定：`屏幕宽 − 球触摸框到屏幕边的距离 − 边距`，上限 320dp；不随文字伸缩，避免每句话都变宽。
- 圆角 24dp（高 48dp 时是胶囊）；近端不收圆角，被球/舌头盖住。
- 字体 13–14sp；状态行 1 行省略；不做 Markdown。

### 5.2 输入态

```
┌──────────────────────────────────┐
│ 悬浮球空闲 · 后台 1 · 设备 1      │
│ ＋  说点什么…              🎤 ➤ │
└──────────────────────────────────┘
```

- `＋`：新对话（清空当前悬浮球会话，下一次发送新建 `orb-phone` 会话）。
- 输入框：多行，最多 4 行；IME 动作是发送。
- 麦克风：按住说话，与长按球等价（长条存在时的第二条路径）。
- 发送：把文字 + 截图（「带屏」默认开）交给现有 `ask`；发送后长条转运行态。
- 键盘弹出时球、长条、舌头、书签一起上移，收键盘回停靠位；沿用 `AskPlace.lift` 的 overlap 计算。

### 5.3 运行态

- 状态行来源：悬浮球会话最新事件。常用映射：
  - 工具开始：`正在看屏幕…` / `正在点按「发送」…` / `正在输入…` / `正在滚动…` / `正在执行命令…`
  - 无工具：`思考中…`
  - 派发：`已派给后台 · 整理照片` / `已派给 书房 Mac · 改文档`
  - 等确认：`等待你确认`
- `■`：停当前这一轮（现有 `stop`）。运行中长条不提供收起，终止永远一步可达。
- 点状态行：v0.1 不展开插话输入；运行/等待中点球打开 App 会话，长条留在外面继续显示状态。

### 5.4 完成 / 失败 / 空闲

- 完成：`✓ 完成`，3s 后收起；有未读书签时球上留绿点（绿点尚未画）。
- 失败：`! 失败：<短原因>`，不自动收起，点状态行进 App 看详情。
- 等待确认：长条保持，气泡出现；回答后回到运行/完成。
- 空闲且有任务：`悬浮球空闲 · 后台 1 · 设备 2`；v0.1 这行不可点，书签是入口。
- 空闲且无任务：长条收起，只剩球。

## 6. 书签

### 6.1 规则（镜像电脑端）

| 场景 | 行为 |
|---|---|
| 长条收起 | 书签一起隐藏；数据不丢 |
| 运行中 | 转圈 + 耗时；点书签只跳转，**不消费**；没有 `×` |
| 完成 / 停止 / 失败 | `✓` / `■` / `!`；保持未读；点书签 = 跳转 + 移除；点 `×` = 只移除、不跳转 |
| 同一会话续做 | 清掉已读，重新变成未读；新一段结束仍是未读 |
| 远端任务 | 用不同颜色 + 设备名区分；跳转进互联会话页 |
| 过期 | 上一次引擎运行留下的已完成任务不再当书签（当作历史）；本次运行内的保留 |

- 「已读」按会话 id 记；运行中点击不写已读，完成/停止的点击才消费。
- 只有完成/停止/失败的才有 `×`；运行中的书签是任务唯一的痕迹，不能移除。
- `×` 移除后要记住这次「已读/丢弃」，下一次轮询不能又把它加回来；同一会话再次运行才清掉这个标记。

### 6.2 布局

- 每排 2 枚，最多 2 排（4 格）；第 5 枚起最后一格变成 `+N`（5 枚时显示 3 个任务 + `+N`），点 `+N` v0.1 先打开 App。
- 长条在屏幕下半部 → 书签在长条上方；上半部 → 下方；贴着长条的外端对齐，夹在安全区内。
- 单枚书签：40dp 高、圆角 12dp、左边 3dp 状态色条；内容从左到右：状态图标、任务名（最多 6 字，省略）、耗时、`×`（仅完成）。
- 状态色：运行=强调色，完成=绿，停止=灰，失败=红；远端=紫并带设备名。
- 耗时：`<1m` / `Nm` / `NhMM`。
- 新书签整排 140ms 淡入（v0.1 不做逐枚 40ms 错峰）；完成只换图标，不重排（排序：运行中在前，已完成按结束时间倒序）。

### 6.3 来源

| 类型 | 来源 | 说明 |
|---|---|---|
| 本机后台 | `background_agent` 工具结果 + 本机会话状态 | `session_id` 来自工具返回；任务文案来自工具参数；运行/空闲看会话状态；停止看 cancel 事件 |
| 跨设备 | `GET /.dsh-mesh/v1/tasks`（`MeshTask`） | 过滤 `originNodeId == 本机 nodeId`；`status` 映射 running/waiting-input/done/failed/cancelled |

- `MeshTask` 已有 `taskId/targetNodeId/sessionId/prompt/callerSessionId/files/createdAt/updatedAt`，App 侧 `MeshApi` 需要新增 `tasks()` 和模型。
- 本机后台任务的会话 id 与 `background_agent` 返回一致；同一会话续做沿用同一个书签。
- 书签只存在内存：App/引擎重启后只重建运行中/等待中的任务；已完成未读不跨重启恢复（与桌面「上一次运行的历史不算未读」一致）。

## 7. 几何与「从球里伸出来」

### 7.1 锚点与方向

- 方向只由停靠边决定：右停靠 → 向左伸；左停靠 → 向右伸。球在中间拖动时先收回，落位后再伸。
- 视觉近端锚在球的**身体中心**；窗口本身从球的触摸框边缘开始，中间由 `TONGUE` 接上。
- 垂直中心 = 球身体中心；越界时夹到安全区。
- 近端内容留出 `触摸框半宽 + 12dp` 的内边距，状态文字不会被球/触摸框盖住；远端 8dp + 48dp 停止热区。
- 书签排与长条同向生长：贴长条外端，避免压住球。

```
右停靠：                    (球)
                    ━━━━(球)
            ━━━━━━━━━━━━(球)
   ● 正在点按「发送」… ━━━ ■(球)
```

### 7.2 动效

| 动作 | 时长/曲线 | 细节 |
|---|---|---|
| 伸出 | 260ms ease-out（可 1.02 轻微回弹） | 从球边 48dp 圆头开始，宽度长到满；远端圆角领先；内容 45% 后淡入 |
| 收回 | 200ms ease-in | 远端先缩回，内容先淡出；结束在球边 |
| 球反应 | 120ms | 沿伸出方向挤压 4% 再回弹；`reduceMotion` 不做 |
| 书签 | 到位后 40ms 错峰 | scale 0.92→1、位移 6dp、淡入 |
| 输入态增高 | 160ms | 从 48dp 到 80dp，中心不变 |
| 键盘 | 系统动画 | 球和长条一起上移，不做二次缩放 |

- 窗口宽度逐帧改（`updateViewLayout`），内容按最终宽度测量、按当前圆角矩形裁剪，避免重排。
- 节流：动画期间不响应状态文字变化；动画结束再刷新一行。
- 降级：`reduceMotion` 或动画掉帧时，直接显示/隐藏 + 150ms 淡入淡出。

### 7.3 接缝

- 首选 `TONGUE`：12dp 宽、圆角、压在球体边缘，`FLAG_NOT_TOUCHABLE`，视觉上从球里长出来；不挡球拖动。
- 备用：长条近端直接压到球体边缘内侧 12–16dp，放在球窗口下面一层。
- 用实验 E1 在至少 3 个 pack、左右两侧、深/浅色下选一个；选中的方案写进代码注释。

## 8. 可见性

| 前台 | 悬浮球 | 长条 | 书签 | 审批气泡 |
|---|---|---|---|---|
| 其他 App，空闲、未展开 | 只显示球 | 收起 | 收起 | 有审批时显示 |
| 其他 App，空闲、已展开 | 球 | 输入态 + 空闲汇总 | 显示 | 显示 |
| 其他 App，运行中 | 球 + 状态 | 运行态 + `■` | 显示 | 显示 |
| 其他 App，完成未收起 | 球 | 完成态 | 显示 | — |
| dsh-Go 前台 | 球（点=跳会话，长按=录音） | 隐藏 | 隐藏 | 隐藏（会话里有卡） |
| 锁屏 | 隐藏 | 隐藏 | 隐藏 | 隐藏 |

- 判定：Dart 的 `PetState.foreground`（App 在屏上）为主，原生 `foregroundPackage()` 交叉校验；`com.android.systemui`、输入法包名当作「在 App 内」，400ms 防抖，避免下拉通知/切输入法时闪一下。
- 录音胶囊是临时输入提示，不算状态面板；App 前台也显示，否则长按没有反馈。
- 所有新窗口（长条、舌头、书签、胶囊）纳入截图隐藏：`captureAskScreenshot` / CU 截图前一起 `INVISIBLE`，并在 `hideForCaptureWithReceipt` 的窗口 id 集合里能看到。

## 9. 数据与通道

### 9.1 Dart → 原生（`dsh/pet_overlay` 的 `push`）

在现有 `PetState.toMap()` 之外新增两个块，由新的 `OrbPanelReducer` 计算，不混进宠物心情：

```json
{
  "strip": {
    "expanded": true,
    "mode": "idle|input|running|waiting|done|error",
    "line": "正在点按「发送」…",
    "summary": "后台 1 · 设备 2",
    "canStop": true,
    "unread": 2,
    "sessionId": "…"
  },
  "bookmarks": [
    {
      "id": "bg:…|dev:<nodeId>:<sessionId>",
      "kind": "background|device",
      "sessionId": "…",
      "nodeId": "…",
      "title": "整理照片",
      "status": "running|waiting|completed|stopped|ended",
      "startedAt": 0,
      "endedAt": 0,
      "unread": true,
      "deviceName": "书房 Mac"
    }
  ]
}
```

- `foreground`、`reduceMotion` 沿用现有字段。
- 长条只为**悬浮球自己的会话**工作；`target` 仍可指向别的会话给气泡用，但长条不跟着 `target` 走。

### 9.2 原生 → Dart

| 方法 | 用途 |
|---|---|
| `askTarget` / `ask` | 发送（复用现有；`screenshot` 传路径，`fresh` 区分新对话） |
| `stop` | 停当前这一轮 |
| `bookmarkOpen` | 打开书签会话：本机 `sessionId` → 本机会话页；远端 → 互联会话页 |
| `bookmarkDismiss` | `×` 移除已完成书签 |
| `openSession` / `openApp` | 在 App 打开悬浮球会话 |

原生本地处理：展开/收起、输入框、键盘、录音胶囊、状态渲染、书签布局。

### 9.3 书签状态机（Dart）

- `read: Set<key>`、`dismissed: Set<key>`；`key = bg:<sessionId>` 或 `dev:<nodeId>:<sessionId>`。
- 每次组包：`running` 的书签清掉该 key 的 `read`；`finished && !read` 展示为未读；`read || dismissed` 不展示。
- 点击完成书签：写 `read` + 发 `bookmarkOpen`；点击 `×`：写 `dismissed`（不发 open）。
- 运行中点击：只发 `bookmarkOpen`，不写 `read`。
- 排序：运行中（按开始时间）→ 等待 → 已完成（按结束时间倒序）。

## 10. 文案

| key | 中文 | 英文 |
|---|---|---|
| orb_hold_hint | 长按说话 · 单击打字 | Hold to talk · Tap to type |
| orb_recording | 松开发送 · 上滑取消 | Release to send · Slide up to cancel |
| orb_cancel | 松开取消 | Release to cancel |
| orb_transcribing | 转写中… | Transcribing… |
| orb_too_short | 说话时间太短 | Too short |
| orb_not_heard | 没听清，再说一次 | Didn't catch that |
| orb_sent_shot | 已发送 · 已附当前屏 | Sent · screen attached |
| orb_no_shot | 当前屏不可截 | Screen can't be captured |
| orb_working | 思考中… | Working… |
| orb_waiting | 等待你确认 | Waiting for you |
| orb_done | 完成 | Done |
| orb_failed | 失败 | Failed |
| orb_idle | 悬浮球空闲 | Orb idle |
| orb_background | 后台 | Background |
| orb_devices | 设备 | Devices |
| orb_stop | 停止 | Stop |
| orb_open_app | 在 App 中打开 | Open in app |
| orb_new_chat | 新对话 | New chat |
| orb_unread | 待查看 | Unread |
| orb_voice_off | 当前无法后台录音，点一下进 App 继续说 | Can't record in the background; open the app to continue |

中文先落 `pet_actions_zh.arb` / `mesh_zh.arb` 同级的 `orb_zh.arb`（或并入 `pet_actions_zh.arb`），英文同步。

## 11. 实施拆分

| 阶段 | 内容 | 依赖 |
|---|---|---|
| P0 预验 | E1/E2/E3/E5 四个实验，出结论 | 测试包 |
| P1 球 | 350ms 长按录音、上滑取消、震动、状态环、轮盘退役 | `AskBar` 录音逻辑、`PetView` |
| P2 长条 | 三态、伸出/收回、输入/停止、截图、可见性 | P1、`OverlayCapsule` 视觉 |
| P3 书签 | 本地/远端任务存储、未读/消费/移除、布局翻转、`+N` | `MeshApi.tasks()`、会话状态 |
| P4 收口 | 审批气泡联动、通知深链、在 App 打开、新对话/历史入口 | P2/P3 |
| P5 打磨 | 接缝定稿、reduce motion、中英文、TalkBack、多 pack | E1/E10 |

### 11.1 实施进度（2026-11-12）

| 阶段 | 状态 | 说明 |
|---|---|---|
| P0 | 完成 | 长按录音、录音胶囊、长条、E1/E5 探针、`orbStop`、日志；测试与装机 |
| P1 | 基本完成 | 350ms 长按录音、上滑取消、录音胶囊、轮盘退役；球的「状态环/角标」尚未画，状态目前靠长条与宠物表情 |
| P2 | 代码完成 | 单击球开输入长条（复用 `AskBar`，24dp 胶囊、从球侧展开+舌头）；发送后长条接会话事件显示 `正在处理…` / `等待你确认` / `等待你回答` / `完成`，停止键常驻；悬浮球固定本机，冷启动自动起引擎；停止后 5 秒内忽略迟到 running。输入条发送沿用现有 AskBar 链路，合成点击受桌面图标干扰，待手指验收 |
| P3 | 首版完成 | 本机 `background_agent` 书签 + Mesh `GET /tasks` 设备书签；2×2 排布、第 5 个起 `+N`（当前是 3 个任务 + `+N` 占第 4 格）；状态点/耗时/×；未读按 stretch，运行中点击不消费，完成点击消费，续做重新未读；本机书签开本机会话，设备书签经 `EntryActivity.openMesh` 开互联会话；书签窗口跟随长条/输入条，键盘/截屏时隐藏 |

探针命令（debug build）：

```bash
# 发一条文字，走和输入条/语音相同的本地 orb 发送（不经 UI）
adb shell am broadcast -a io.github.ccch1mneyyy.dsh_app.PET_PROBE \
  -p io.github.ccch1mneyyy.dsh_app.mesh --es cmd say --es text "hello"
# 停当前长条会话
adb shell am broadcast ... --es cmd stop
# 假书签：长条 + 2×2 排布视觉
adb shell am broadcast ... --es cmd bookmarks --ez show true
# 收起假书签
adb shell am broadcast ... --es cmd bookmarks --ez show false
```

验证记录：

- 本机引擎：`probe say` → `sent session=… shot=false orb=true`；长条 `running line=正在处理… stop=true`；停止探针 `stop result=true`；随后 `done line=完成`。
- 真实语音（Mac TTS 当音源）：`asr 3633ms text=19`、`sent … shot=true orb=true`；用户手测语音正常。
- 书签视觉：`cmd bookmarks` 在长条上方显示 `◔ 整理照片 <1m` / `✓ 查资料 5m ×` / `✓ 书房 Mac · … 1m ×` / `＋2`；截图 `/tmp/orb-bookmarks2.png`。
- 单测：`OrbStripGeometryTest` 9 项、`OrbBookmarksGeometryTest` 6 项、`orb_bookmarks_test.dart` 5 项、`pet_actions_test.dart` 17 项全过；`flutter analyze lib` 无问题。

未做/待办：

- 球的「状态环/角标」未画。
- P4：审批/提问气泡与长条联动、通知深链、新对话/历史入口。
- 书签真实设备链路需要 mesh 已配对；本机后台书签已接 `background_agent` 结果解析，agent 是否真的调用该工具取决于模型。
- 手机控制的 CU 截图（`hideForCaptureWithReceipt`）还没有通知宠物浮层；目前只在自己的发送截图里隐藏长条/书签，P5 要把宠物/长条/书签接入。
- E5 帧率、E1 左停靠/多 pack、E2 真人口音 3 次、书签真实设备链路待补。
- 书签 `+N` 当前是 3 个任务 + `+N`；若希望 4 个任务 + `+1`，需要第 3 排或把 `+N` 做成非 chip 的小字。

### 11.2 「停止后再发，任务直接停下」修复（2026-11-13）

**现象**：在悬浮球里停止过一次任务后，再输入内容让它操作手机，agent 第一次调用手机工具就收到
`用户已停止本次手机操作。不要再调用手机工具，直接告诉用户已经停下`，随即结束，没有执行。

**两个原因**：

1. 手机任务的「停止」是持久的：`PhoneTasks` 在 `PhoneTaskSessions.admit` 里，只要 `stoppedSessions`
   含有该 sessionId，或 `endedRuns` 命中 `(sessionId, runId)`，就直接回 `STOPPED_MESSAGE`。
   新一轮对话是新的 run，但 `stoppedSessions` 按 sessionId 一直在，发送消息本身不会清掉它。
2. 旧的 `phoneStopWatcherProvider`：手机状态出现「停止」边沿时，它会取消**所有**本机会话正在进行的轮次。
   悬浮球刚发出的新消息因此被立刻取消，界面约 1.9s 后显示「已停止」。

**修复**：

- `phone_state.dart`：移除 watcher 的 cancel-all（provider 保留为空实现并注明原因）。停止是会话级的，
  由被停止的任务自己拒绝工具调用，别的本地会话不陪葬。
- `PetOverlayActions`（悬浮球发送）：`_resumeOrbPhone` 在把新消息交给会话前，最多 8 次读取原生状态；
  找到同一 sessionId 且 `stopped` 的任务就 `resumeTask(id)`。仍停着就不发送，返回「无法恢复手机任务」。
- `ChatController.send`（App 内对话同样会遇到）：新消息不是接在进行中的轮次上时，`_resumePhoneTask`
  用当前快照里 stopped 的任务调 `resumeTask`。只有原生状态已就绪（`status.ready`）才去读状态，
  没有手机模块时不等待、不阻塞发送；状态读取 300ms 上限、最多 4 次。

**真机验证**（OnePlus 7 Pro，2026-11-13）：修复后重发「继续」，任务没有被取消，agent 追问确认；
选择「继续 B 站一键三连（中国石油大学华东 最新视频）」提交后，`dumpsys activity activities` 显示
`mResumedActivity: tv.danmaku.bili/.MainActivityV2`（09:44:55），说明手机任务已恢复且 `open` 工具真的执行。

**回归**：`send_controller_test.dart` 78 项、`pet_actions_test.dart` 17 项、`orb_bookmarks_test.dart` 5 项通过；
`flutter analyze lib` 无问题。

## 12. 实验方法（本轮由实现方确定）

> 原则：每个实验只回答一个会影响实现的问题；有明确的通过标准和「不通过怎么办」。
> 环境统一用测试包 `io.github.ccch1mneyyy.dsh_app.mesh`（不覆盖原 DSH Orb），OnePlus 7 Pro / Android 12 / 90Hz；授予悬浮窗、无障碍、麦克风、通知；语音模型就绪；mesh 已配好 Mac + win32-asus。截图存 `docs/phone-eval/orb-ui/<实验>/`。每次实验前 `adb logcat -c`。

### E0 基线

- 构建测试包（不覆盖原 App）：`./gradlew :app:assembleArm64Debug -PtestAppId=true`，产物 `app/build/app/outputs/apk/arm64/debug/app-arm64-debug.apk` 的包名是 `io.github.ccch1mneyyy.dsh_app.mesh`；`adb install -r` 覆盖安装测试包，原 DSH Orb 不动。
- 安装测试包并确认：presets3、`orb-phone`、`device_agent`、`background_agent` 都在；悬浮球能显示。
- 记录原 DSH Orb 的服务状态；实验结束恢复（原 App 之前被 force-stop 过）。
- 通过：以上全绿；否则先修环境，不进实验。
- 已知坑：APK 更新后无障碍 overlay 可能报 `BadTokenException`。当前实现会在失败后立刻改用应用层 overlay（`Settings.canDrawOverlays` 允许时），并在同一无障碍实例上 30 分钟内不再重试无障碍层；日志 `overlay add failed (accessibility): BadTokenException` 之后应能看到 `host=application`。

### E1 接缝：舌头 vs 压在球下

- **目的**：选一个在多种 pet pack 上都像「从球里长出来」的方案。
- **做法**：debug-only 探针，用 `PET_PROBE` 广播切变体，在球边显示长条：
  - V0 贴边、无舌头（对照）；
  - V1 长条压在球下 28dp（`tuck28`）；
  - V2 长条压在球下 12dp（`tuck12`）；
  - V3 12dp 非触摸舌头（`tongue`，默认）。
- **命令**：
  ```bash
  # 单个变体（tongue / edge / tuck12 / tuck28）
  adb shell am broadcast -a io.github.ccch1mneyyy.dsh_app.PET_PROBE \
    -p io.github.ccch1mneyyy.dsh_app.mesh \
    --es cmd strip --es variant tongue --ez show true --ez animate true
  # 依次轮播四个变体，各 2 秒
  adb shell am broadcast -a io.github.ccch1mneyyy.dsh_app.PET_PROBE \
    -p io.github.ccch1mneyyy.dsh_app.mesh --es cmd strip --ez cycle true
  # 收起
  adb shell am broadcast -a io.github.ccch1mneyyy.dsh_app.PET_PROBE \
    -p io.github.ccch1mneyyy.dsh_app.mesh --es cmd strip --ez show false
  ```
  日志 `DshPet` 的 `strip probe: variant=…` 与 `strip add …` 给出实际窗口矩形；截图用 `adb exec-out screencap -p`。
- **矩阵**：3 个 pack（当前默认、一个宽体、一个细长人形）× 左/右停靠 × 深/浅色 × small/medium。
- **步骤**：每个组合展开 3 次、收起 3 次；截屏；在接缝处上下拖动球 3 次。
- **通过**：没有一条明显的长条色块从精灵的透明缝隙里透出来；接缝没有 >2dp 的断口；拖球不误触长条；长条内容不被球的触摸框挡住。
- **不通过**：V3 若盖住精灵太多，舌头缩到 8dp/24dp；V1/V2 若出现「在球后面」的观感，直接用 V3。最终只保留一个方案，写进代码注释。
- **初测（2026-11-12，OnePlus 7 Pro / deepy-whale medium / 右停靠）**：
  - `tongue`：长条从球边伸出，舌头把圆点和精灵连上，接缝连续，选中。
  - `edge`：圆点与精灵之间有一道明显空隙，看起来像浮在旁边，不选。
  - `tuck12`：APK 更新后无障碍层坏掉，宠物落在应用层 overlay，长条只能在宠物之上，压住了 whale 的左侧；在这个 host 上无法验证「压在球下」，不选。
  - 结论：默认 `tongue`；tuck 只在无障碍 host 可用时才值得再测。左停靠与多 pack 仍待补测（几何单测已覆盖左右对称）。

### E2 后台录音是否被静音（go/no-go）

- **目的**：确认在其他 App 前台长按球能拿到真实音频；决定是否加 `microphone` 前台服务。
- **前置**：微信在前台；`DshService` 运行；关闭电池优化。
- **步骤**：
  1. 长按球 5s 说「帮我看一下这条消息」，重复 5 次；
  2. 看 logcat `pet mic:` 的 `silenced`、WAV 字节数、转写结果、松手到出字的时间；
  3. `DshService` 运行/不运行各 3 次；
  4. 锁屏后 1 次（只记录，不作 v0 门槛）。
- **通过**：≥4/5 次转写非空且 3s 内出字；`silenced=false`；WAV 字节数与 5s 录音相符。
- **不通过**：给两个前台服务加 `microphone` 类型与 `FOREGROUND_SERVICE_MICROPHONE` 权限，重测；仍失败就改成「当前无法后台录音，点一下进 App 继续说」，并移除 `foregroundVoice` 的自动跳转。此实验不过，P1 不进入联调。
- **初测（2026-11-12，桌面 + 手机在桌面）**：合成按住 1.87s，日志 `orb voice mic: configs=1 silenced=false dshService=true`、`bytes=53164`；麦克风没有被静音，DshService 在跑。因为没有真人说话，ASR 返回空文本（`asr 2973ms text=0` → 「没听清」），符合预期。
- **后续（2026-11-12，Mac Tingting TTS 当音源）**：两次成功——`asr 3633ms text=19` + `sent … shot=true orb=true`，以及更早一次 `text=8`；用户也手测确认语音正常。麦克风静音这一关已过；真人口音再补 3 次统计，E11 的 3 秒时延单独看（7.5 秒长句的 ASR 为 3.6 秒）。

### E3 长按 vs 拖动仲裁

- **目的**：定长按时长，保证录音不误触拖动、拖动不误触录音。
- **步骤**：各 20 次：
  1. 按住不动 0.5s（应录音）；
  2. 按住后 0.2s 内移动 20dp（应拖动）；
  3. 按住后缓慢移动 5dp（应在 slop 内仍算按住）；
  4. 按住 0.2s 松手（应提示太短，不发送）；
  5. 录音中上滑 80dp 松手（应取消）；
  6. 拖动中上滑（不应取消录音——因为没在录音）。
- **通过**：录音误触 0 次、拖动误触 0 次、取消 5/5 生效；录音启动延迟主观「一按就有」。
- **不通过**：把阈值在 300/350/400ms 之间调；仍不行就改成「长按后 150ms 内不移动才进入录音」的两段判定。默认 350ms。
- **初测（2026-11-12）**：合成按住 1.5s/2.2s，日志 `hold start` → `release elapsed=990/1763ms cancel=false`，长按启动、没有误触拖动；录音胶囊在球上方显示波形、计时与「松开发送 · 上滑取消」。
- **滑动取消 5/5**：`input motionevent DOWN`（按住 0.7s）→ `MOVE` 上移 300px → `UP`，五次都是 `slide dy=300 cancel=true` → `release … cancel=true` → `orb voice cancel`。跨进程 `motionevent` 能拼出连续手势。

### E4 键盘与输入态

- **目的**：确认 80dp 输入态 + 球/长条上移在真实 IME 下可用。
- **步骤**：球停右下、左中、上右各一次：
  1. 点球展开输入，观察键盘、焦点、长条位置；
  2. 输入 20 字，回车发送；
  3. 键盘弹出时点书签；
  4. 旋转屏幕后再输入；
  5. 返回键收键盘，再点球。
- **通过**：输入框不被键盘盖住；球和长条一起上移，收键盘回原位；无窗口跳动/残影；光标和选择正常；旋转后方向与位置正确。
- **不通过**：只上移长条、球留在原地（视觉可接受）；或输入态改为底部固定条（但仍从球的方向展开）。动画与 IME 抢帧时，改为键盘完全弹出后再长高。

### E5 伸出动画性能

- **目的**：确认逐帧改窗口宽度不卡；定「动画 vs 降级」。
- **步骤**：
  1. `adb shell dumpsys gfxinfo io.github.ccch1mneyyy.dsh_app.mesh reset`；
  2. 展开/收起 20 次（含 4 枚书签、含键盘各 10 次）；
  3. `adb shell dumpsys gfxinfo … framestats`，看 janky frames 与 p95；
  4. 同时看 `dumpsys window` 的窗口数与内存。
- **通过**：janky < 5%；没有超过 2 倍刷新间隔的帧；窗口数在收起后回到基线；无 WindowLeaked。
- **不通过**：改用「全宽透明窗口 + clipRect + 触摸区域」方案（API 31+ 用 `setTouchableRegion`，低版本退回窗口改宽）；再不行就降级成 150ms 淡入 + 轻微缩放，不做宽度动画。

### E6 截图排除

- **目的**：确认球/长条/舌头/书签/胶囊都不进截图。
- **步骤**：
  1. 微信前台，长按球说话松手，取回附件图；
  2. 长条运行中，用 `mcp__phone__screen` 再截一张；
  3. 银行/密码页各一次；
  4. 检查 `hideForCaptureWithReceipt` 返回的窗口 id 是否含新角色。
- **通过**：附件图与 CU 截图里都没有任何浮层像素；API 30–33 与 34+ 两条路径都成功；安全页只发文字并提示。
- **不通过**：把 `STRIP/TONGUE/BOOKMARKS/VOICE` 加进隐藏集合；舌头若无法隐藏，把它并进长条窗口一起隐藏。

### E7 前台判定与防抖

- **目的**：确认「dsh-Go 前台只留球」不闪、不延迟。
- **步骤**：dsh-Go → 微信 → 桌面 → 下拉通知 → 输入法 → 权限弹窗 → 回 dsh-Go，各 10 次；记录 `foreground` 推送与窗口显隐时间戳。
- **通过**：进 dsh-Go 300ms 内长条/书签消失；离开 300ms 内按状态恢复；系统 UI/输入法切换 0 次闪烁。
- **不通过**：把 systemui/IME 包名并入「App 内」；防抖 400ms 调到 600ms；仍闪就只用 Dart `foreground`，去掉原生包名判定。

### E8 书签状态机

- **目的**：锁死桌面语义，防止「一完成就消失」或「永远挂着」。
- **单测**（Dart reducer）：
  1. 运行中点击不消费；
  2. 完成点击消费；
  3. `×` 只移除不打开；
  4. 同一会话续做清已读、再次完成仍未读；
  5. 耗时格式 `<1m/Nm/NhMM`；
  6. 远端与本地 key 不冲突。
- **手测**：
  1. 派本机后台任务 → 运行中书签、无 `×`；点书签进会话、书签还在；
  2. 任务完成 → `✓` 未读；收起/展开长条仍在；点书签进会话且书签消失；
  3. 再派一个 → 完成后点 `×`，不跳转、消失；
  4. 同一会话续做 → 重新以运行中/未读出现；
  5. 设备任务重复 1–4；
  6. App 前台时书签隐藏，离开后恢复；
  7. 杀 App 重启：运行中任务重建，已完成未读不恢复（预期）。
- **通过**：全部符合；没有任何「完成瞬间自己消失」。
- **不通过**：先修 reducer，不进入 P4；本机会话状态拿不到就加一个 bridge 的任务查询接口。

### E9 书签布局与翻转

- **目的**：确认多书签不压球、不越界、会翻转。
- **矩阵**：球在 6 个位置（左上/左中/左下/右上/右中/右下）× 1/2/4/6 枚书签 × 键盘开/关。
- **通过**：书签全部在屏内；不压球、不压长条；长条在下半部时书签在上方，反之亦然；`+N` 数字正确；键盘弹出后仍成立。
- **不通过**：最多排数从 2 降到 1；或把 `+N` 提前到第 3 枚。默认 2×2。

### E10 无障碍与减少动画

- **目的**：确认可用性与系统设置兼容。
- **步骤**：TalkBack 依次聚焦球、长按、长条状态、`■`、书签、`×`；开「减少动画」；字体 1.3×；深色模式。
- **通过**：每个控件有可读标签与动作；减少动画时无位移动画；字体放大不截断关键按钮；深色下对比度足够。
- **不通过**：补 `contentDescription`；把状态文字设为 `LiveRegion`；必要时把书签 `×` 的触摸热区加到 32dp。

### E11 语音时延

- **目的**：对齐验收「松手 1s 内转写中、3s 内出文字」。
- **步骤**：10 条 3–15 秒的中文语音，记录松手→「转写中」、松手→消息出现在长条/会话、截图完成时间。
- **通过**：10/10 在 1s 内出现「转写中」；≥9/10 在 3s 内出文字；截图失败不阻断发送。
- **不通过**：先出「转写中」再异步补文字；SenseVoice 不可用时明确降级到系统 ASR；仍慢就把截图放到发送后异步补附件（需协议支持，v0.1 先不做）。

## 13. 验收映射

| 验收 | 对应 |
|---|---|
| 7.2.1 悬浮球全局/拖动/停靠 | P1、E3 |
| 7.2.2 长按录音/松手发送/上滑取消/其他 App 前台 | P1、E2、E3、E11 |
| 7.2.3 发送截图/无球/安全页/缩略图 | P2、E6 |
| 7.2.4 精简工具 + 手机 CU + 停止 | 已有 preset；P2 停止 |
| 7.2.5 面板：对话/状态/停止/历史/新建 | P2/P3；历史/新建在 App，长条只做输入/状态/停止 |
| 7.2.6 后台 Agent 完成回到 Orb/通知 | P3/P4 |
| 7.2.7 兼容 | E10 |
| 7.3.3 手机派电脑、结果回手机 | P3（书签 + 互联会话页） |

## 14. 决策记录（本轮已定）

| # | 决策 |
|---|---|
| D1 | 球沿用当前宠物尺寸，不放大、不加底盘 |
| D2 | 单击：外侧开/收长条；App 前台跳悬浮球会话 |
| D3 | 长按 350ms 录音，松手发送，上滑 80dp 取消，<0.3s/静音不发 |
| D4 | 轮盘退役，七项按 §3.2 分配 |
| D5 | 长条从球里水平伸出，方向由停靠边决定；宽度固定 |
| D6 | 拖动球时先收回，落位后从新方向再伸出 |
| D7 | 球伸出/收回时 4% 挤压；reduce motion 不做 |
| D8 | 输入态两行 80dp；运行/完成 48dp |
| D9 | 完成后 5s 自动收起；失败/等确认不自动收 |
| D10 | 书签镜像桌面：运行中点击不消费、完成点击消费、`×` 只移除、续做重新未读 |
| D11 | 书签 2/排、最多 2 排、`+N`；运行中无 `×` |
| D12 | 审批用气泡卡；App 前台不显示 |
| D13 | App 前台只留球；长按仍可录音；录音胶囊保留 |
| D14 | 外侧发送带截图；App 前台长按只发文字 |
| D15 | 空闲长条显示汇总，v0.1 不可点 |
| D16 | 任务来源：本机会话 + mesh `/tasks`；书签内存态，重启只重建运行中 |
| D17 | 远端 Host 不走新面板（v0.1） |
| D18 | 接缝默认 12dp 非触摸舌头，E1 定稿 |

## 15. 风险

| 风险 | 处理 |
|---|---|
| 后台麦克风被静音 | E2；补 `microphone` 前台服务或降级提示 |
| 厂商悬浮窗/后台启动限制 | 沿用 `canDrawOverlays` 探测与 `openIntent` 的通知回退；E7 |
| 逐帧改窗口卡顿 | E5；全宽 clip 或淡入降级 |
| 精灵透明缝隙让舌头/长条穿帮 | E1；只保留一个方案 |
| 本机会话状态拿不到 | E8；加 bridge 任务查询 |
| 长条遮挡 CU 目标 | 长条贴球、48dp；必要时运行中自动降低透明度（仅 E5 不通过时） |
| AGPL | 先内部自用；对外分发前做许可评估与换名/换美术 |