# 手机端偶发整屏红屏：`_elements.contains(element)` 断言失败

- **状态**：待抓取首次异常完整堆栈后定位修复
- **记录日期**：2026-11-20
- **影响端**：Android 手机端（`dsh-orb-android`）

---

## 1. 现象与报错

手机端使用过程中**经常**出现整屏红屏（铺满整个视口，包括状态栏与底部手势条区域，无 AppBar / 聊天栏残留），报错文本为：

```text
'package:flutter/src/widgets/framework.dart': Failed assertion: line 2170 pos 12:
'_elements.contains(element)': is not true.
See also: https://docs.flutter.dev/testing/errors
```

- **恢复方式**：划掉/彻底重启 App 进程后恢复正常；不重启则持续停留在红屏。
- **本次记录的发生时间**：2026-11-20 09:51（09:52 重启后恢复）。

---

## 2. 设备与构建环境

从实机 `adb` 与 `files/runtime/rootfs/root/.dsh/dsh-app/diagnostics.md` 采得：

- **设备**：OnePlus GM1910 · Android 12 (SDK 31) · arm64-v8a · 1440×3064 (560dpi, 3.5×)
- **包名 / 版本**：`io.github.ccch1mneyyy.dsh_app.mesh` · `versionName=1.0.15` · `versionCode=11180954`（更新于 `2026-11-18 09:55:08`）
- **构建模式**：**Debug 包**（`pkgFlags=[DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA]`；本地 `app/android/local.properties` 亦为 `flutter.buildMode=debug`）
- **Flutter SDK**：Flutter 3.47.9（framework revision `c197ca5199`）· Dart 3.13.7
- **关键运行配置**：
  - `theme=system`、`blur=true`、`fluidMotion=true`、`reduceMotion=false`、`lightweight=false`
  - 开启了 App 外置悬浮球（`DshPet`）与本机引擎（`r15-dsh0.2.2-rc.1-node24.22.1-ubuntu24.04.5-mesh0.1.3-92f7469a7a7c-presets7`）

---

## 3. 本次触发前操作

1. 直接打开手机端软件，停留在首页空态；
2. 点击首页中间的「标准模式」胶囊，弹出底部选择弹层（`showAppSheet` / `showModalBottomSheet`）；
3. 在弹层中点击切换为「悬浮球模式」（`orb-phone`），**刚点完就立刻整屏红屏**。
4. 重启软件后，再重复相同操作**不会**复现。

---

## 4. 机理与根因分析

### 4.1 Flutter 源码精确位置

在 Flutter 3.47.9 `packages/flutter/lib/src/widgets/framework.dart` 中：

- **Line 2170 (`_InactiveElements.remove`)**：
  ```dart
  void remove(Element element) {
    assert(!_locked);
    assert(_elements.contains(element)); // line 2170 pos 12
    assert(element._parent == null);
    _elements.remove(element);
    assert(element._lifecycleState == _ElementLifecycle.inactive);
  }
  ```
- **全框架唯一调用点（Line 4545，`Element._retakeInactiveElement`）**：
  在 `inflateWidget` 遇到带 `GlobalKey` 的组件时，尝试复用已注册在 `BuildOwner._globalKeyRegistry` 中的 `element`。

### 4.2 为什么会命中 2170 行（二次级联崩溃）

命中第 2170 行且通过了第 4544 行 `assert(element._parent == null)`，说明该 `GlobalKey` 对应的 `Element` 已经处于**“僵尸状态”**：`_parent == null`、仍残留在 `_globalKeyRegistry` 中，但并不在 `_inactiveElements._elements` 集合里。

这通常不是业务代码直接重复写 `GlobalKey` 引起，而是**前置发生了某次未捕获异常**把 Element 留成僵尸态，随后触发了二次报错：

1. **`dispose()` / `unmount()` 阶段抛异常（最常见）**：
   `BuildOwner.finalizeTree()` → `_InactiveElements._unmountAll()` 会**先清空 `_elements`**（`framework.dart:2126`），再逐个 `unmount()` 并注销 `GlobalKey`。只要中途某个子节点的 `dispose()` 抛异常，遍历中断，剩余节点的 `GlobalKey` 永远留在 `_globalKeyRegistry` 中。
2. **`mount()` / `initState()` / `didChangeDependencies()` 阶段抛异常**：
   `Element.mount()` 先注册了 `GlobalKey`（`framework.dart:4369`），若初始化抛异常，`inflateWidget` 捕获后走 `_deactivateFailedChildSilently`（`framework.dart:4607`），把 `_parent` 置空但既未注销 `GlobalKey` 也未加入 `_inactiveElements`。
3. **`deactivate()` 阶段抛异常**：
   `deactivateChild` 先将 `_parent` 置空，随后 `_deactivateRecursively` 抛错跳过 `_elements.add`。

### 4.3 为什么不重启进程就一直红屏

- 报错铺满整屏，说明变成僵尸态的是页面级或根级 `GlobalKey`（如 `ModalRoute._subtreeKey`、`Navigator._overlayKey`、`OverlayEntry._key`、`ThemeRevealHost._boundary`、`PetStage._content` 等）。
- `MainActivity.kt` 使用 `FlutterEngineCache` 且 `shouldDestroyEngineWithHost() = false`，退后台或关闭 Activity 不会销毁 `FlutterEngine`，被污染的 `_globalKeyRegistry` 会一直保留；只有彻底杀进程重启、重建 `FlutterEngine` 才会恢复。
- 这也解释了为什么重启后再做“标准模式 → 悬浮球模式”不会复现：**该操作只是撞上了此前残留的异常状态，而非直接诱因**。

> 注意：换成 Release 包只会移除 `assert` 和红屏 UI，但无法消除底层 Element 树损坏（Release 下可能表现为灰屏、UI 卡死或后续状态异常），仍需抓到第一现场堆栈后修复根因。

---

## 5. 已排查范围与重点嫌疑点

### 已排查（未发现直接 `GlobalKey` 误用）
- 首页模式切换链路：`app/lib/ui/chat/new_chat_options.dart`、`app/lib/ui/chat/empty_state.dart`、`app/lib/ui/chat/empty_actions.dart`、`app/lib/ui/common/app_sheet.dart`（`_SheetGlass`、`_HeldSheetDrag`）、`app/lib/ui/common/list_page_row.dart`、`app/lib/ui/theme/glass.dart`、`app/lib/state/new_chat_modes.dart`
- 设置 / 全部功能预设链路：`app/lib/ui/features/agent_presets_page.dart`、`app/lib/ui/features/all_features_page.dart`、`app/lib/ui/common/tile_grid.dart`
- 项目全部 20 处自定义 `GlobalKey` 声明均位于 `State` 字段中，未见 `build()` 内动态创建。

### 重点嫌疑点（待与现场堆栈对照）
1. **自定义路由转场与 `GlobalKey` 重挂载**：`app/lib/ui/theme/page_transitions.dart` 中 `AppPageTransitionsBuilder.delegatedTransition` 每次访问返回新闭包；在 `MaterialPageRoute` 与 `PageRouteBuilder` 切换时会触发底层页面 `RepaintBoundary(key: route._subtreeKey)` 重挂载，且 `_CardMorph` 将页面包在 `LayoutBuilder` 中。
2. **延后 `dispose()` 可能导致 `Ticker` 活跃时卸载抛错**：`app/lib/ui/session/session_detail_page.dart`（`_syncPluginsTab`）将旧 `TabController` 延后至 `addPostFrameCallback` 才 `dispose()`；若页面在此前卸载且动画仍在运行，`TickerProviderStateMixin.dispose()` 会在 `_unmountAll()` 中抛异常。
3. **根级 / 全屏级 `GlobalKey` 容器**：
   - `app/lib/ui/theme/theme_reveal.dart`（`_boundary`，位于 `MaterialApp.builder` 最外层）
   - `app/lib/ui/pets/pet_stage.dart`（`_content`，包裹整个 `ChatScreen` 主体）
4. **跨 Activity 复用的长生命周期引擎**：`app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/MainActivity.kt`（`FlutterEngineCache` + `shouldDestroyEngineWithHost() = false`），叠加外置悬浮球（`EntryActivity` → `MainActivity`）长时间后台运行与反复恢复。

---

## 6. 下次复现时的定位方案

本次（2026-11-20 09:51）未能拿到堆栈的原因：
- 重启进程后内存中的 `appErrorLog`（`app/lib/diagnostics/app_error_log.dart`）已清空；
- 手机 `logcat main` 缓冲区被其他后台进程持续刷屏（`NoSuchFieldException: mLastProcessState`）快速顶掉，重启一小时后再连电脑时已无 09:51 记录。

### 方案 A：复现时立刻抓 logcat（不要等太久，也尽量在重启前抓）

连上电脑后执行：

```bash
ADB=/Users/gaoyifan/development/android-sdk/platform-tools/adb
$ADB logcat -d -b main -b crash | grep -E "flutter|EXCEPTION CAUGHT|framework\.dart|AssertionError|Dsh" | tail -n 250
```

重点保留**`_elements.contains(element)` 之前紧邻的第一条 `EXCEPTION CAUGHT BY ...` 异常及完整堆栈**。

### 方案 B：改造 `app_error_log.dart` 支持本地落盘（推荐）

后续准备彻底修此问题前，可先在 `app/lib/diagnostics/app_error_log.dart` 中把 `AppErrorLog.record(...)` 同步追加写入 App 私有目录的持久日志文件（带大小上限轮转，重启不清空），并纳入诊断导出。这样即使在户外遇到红屏、顺手重启了 App，回来连电脑执行：

```bash
$ADB shell "run-as io.github.ccch1mneyyy.dsh_app.mesh cat files/<error-log-file>"
```

即可直接拿到第一现场完整堆栈。
