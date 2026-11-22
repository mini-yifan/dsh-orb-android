# 手机真机测试手册

> 适用仓库：`dsh-orb-android`
> 更新时间：2026-11-20（对齐 DSH Orb 0.1.2 / mesh 插件 0.1.6）
> 最近验证：runtime `r15-dsh0.2.2-rc.1-node24.22.1-ubuntu24.04.5-mesh0.1.6-...-presets9`，分支 `feat/mesh-runtime`
>
> 目标：在真机上验证 DSH runtime、`orb-phone` / `phone-background` preset、悬浮球发送截图，以及手机与 Mac/Windows 的互联（会话续聊、新建会话、`device_agent` 派任务）。

> **与旧版文档的差异（2026-11-20 起）**：App 已独立为 DSH Orb，包名 `top.dshorb.app`（不再是 `io.github.ccch1mneyyy.dsh_app`），只有**一个**包，不再用 `.mesh` 测试包与 debug 签名。测试包的说法在本文件里保留在「历史做法」小节，仅作参考。

## 0. 速查清单

**装机**

- [ ] `adb devices` 能看到手机，状态为 `device`
- [ ] APK 的 `versionName` 是本轮目标版本，`versionCode` 比手机上已装的大
- [ ] APK 签名与已装版本一致（否则 `install -r` 会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）
- [ ] 新 runtime 已打进 APK：`unzip -p <apk> assets/runtime/manifest.json` 的 `runtimeVersion` 是目标值
- [ ] 安装并启动后，「设置 → 关于」显示新版本号
- [ ] 本地引擎（本机 → 运行中）起来了，`libproot.so` 与 `node` 进程存在

**互联（三端在线）**

- [ ] 互联页显示 Mac、Windows 均「在线」，顶部计数正确
- [ ] 展开某台电脑，工作区标题行右侧有「＋」，点它能新建会话并进入
- [ ] 点工作区标题能折叠/展开会话列表；切换设备时折叠状态复位
- [ ] 打开一个**普通**（非任务）远端会话，输入框可用，发一条消息能收到回复

**跨设备派任务**

- [ ] 电脑 → 手机：`device_agent_devices` 能列出这台手机及其工作区
- [ ] 电脑 → 手机：`device_agent` 派任务成功，文件回到电脑的 `.mesh-in/<taskId>/`
- [ ] 手机 → 电脑：`device_agent` 派发后本轮结束，完成通知随后到达，不 `sleep` 轮询
- [ ] 失败时（设备离线／无工作区）能看到具体原因，而不是只说「没发出去」

**收尾**

- [ ] 日志里没有 preset/plugin 加载失败
- [ ] 测完恢复无障碍服务与原来的 App 状态

## 1. 设备与环境

### 1.1 测试机

- OnePlus 7 Pro（GM1910），Android 12，arm64。
- USB 连接，`adb` 状态 `device`。
- 下文用环境变量代替具体值：

```sh
export PHONE_SERIAL=<手机序列号>
export DSH_APP=io.github.ccch1mneyyy.dsh_app
export DSH_TEST_APP=io.github.ccch1mneyyy.dsh_app.mesh
```

### 1.2 本机工具链

```sh
export ANDROID_SDK=$HOME/development/android-sdk
export FLUTTER=$HOME/development/flutter
export JDK17=$HOME/development/jdk17/Contents/Home
export PATH=$HOME/development/platform-tools:$PATH
```

- Flutter 3.47.9，JDK 17，Android SDK 36 / NDK 28。
- 构建 APK 时显式传 `JAVA_HOME`、`ANDROID_HOME`。

### 1.3 用哪个包（2026-11-20 起）

现在只有**一个** App：`top.dshorb.app`，release 签名。以前那套「`.mesh` 测试包 + debug 签名、与原 DSH Orb 并存」的做法已废弃——原因是 DSH Orb 已经与上游 dsh-go 分家，没有需要并存的「原 App」。

```sh
export DSH_APP=top.dshorb.app       # 正式包
export DSH_DEV=top.dshorb.app.dev   # -PtestAppId=true 的并排开发包，仅在需要并存时用
```

`adb install -r` 会保留 `/data`、runtime 与互联配对状态。**前提是签名一致**：手机上装的是 debug key 签名、新包是 release key 时会被拒（见 `docs/release.md`「签名」里的坑）。

### 1.4 历史做法：`.mesh` 测试包（已废弃，仅供理解旧记录）

早期为把 DSH Orb 与上游 dsh-go 并存，曾用 `io.github.ccch1mneyyy.dsh_app.mesh` 这个独立包名 + debug 签名，并靠临时改 `build.gradle.kts` 的 `applicationId` 打出来。本文件里凡是提到 `.mesh`、`io.github.ccch1mneyyy.dsh_app`、`run-as` 的地方，都属于那个时期；现在应把包名换成 `top.dshorb.app`，且 release 包**不能 `run-as`**（不可调试），读 runtime 版本要改用别的方法（见 2.6）。

### 1.5 无障碍服务

只有一个 App，不再有「两个 App 互相把对方挤出 `enabled_accessibility_services`」的问题。仍需注意：

1. 首次安装后要在系统设置里手动启用「DSH 手机操控」无障碍服务；
2. 部分 ROM 需要额外允许「受限设置」才能开启；
3. 卸载重装后要重新启用。

`adb shell settings put secure ...` 不可用（shell 没有 `WRITE_SECURE_SETTINGS`），必须走系统设置 UI。

## 2. runtime 与 APK 构建

### 2.1 什么时候需要重打 runtime

`runtime/out/` 里是 Linux rootfs（PRoot + Node + DSH + 桥接 + mesh 插件），**被 `runtime/.gitignore` 忽略、不入库**。只有下列情况才需要重跑 `runtime/build-runtime.sh`：

| 变了什么 | 要重打 runtime？ |
|---|---|
| Dart / Kotlin 代码、UI、l10n | 不要 |
| `runtime/bridge/dsh-go-bridge/`（preset、绑定的工具） | 要 |
| mesh 插件 tgz 版本 | 要（插件版本计入 `runtimeVersion`） |
| DSH / Node 版本、`runtime/patches/*.diff` | 要 |

日常改 UI 只跑 `tooling/build-apk.sh` 就够了，它会直接用现有的 `runtime/out/`。

### 2.2 重打 runtime（需要时）

本机是 Mac、要产出 arm64 rootfs，做法是「改包」而不是从零构建——完整的 `build-runtime.sh` 需要 x86_64 Linux 主机。

```sh
cd dsh-orb-android

MESH_TGZ=../dsh-mesh/packages/mesh-plugin/dist-phone/dsh-mesh-plugin-0.1.6.tgz
MESH_SHA=$(shasum -a 256 "$MESH_TGZ" | cut -d' ' -f1)

# tgz 先解到临时目录，repack 的 --plugin-dir 指向 package/
rm -rf /tmp/plugpkg && mkdir -p /tmp/plugpkg && tar -xzf "$MESH_TGZ" -C /tmp/plugpkg

# 1) 生成 overlay（产物：runtime/.work/plugin-check/dsh-web.patch.yml）
RUNTIME_PLUGIN_CHECK=1 \
DSH_PLUGIN_NAME='@dsh-mesh/plugin' \
DSH_PLUGIN_VERSION='0.1.6' \
DSH_PLUGIN_URL="file://$MESH_TGZ" \
DSH_PLUGIN_ENTRY='lib/index.mjs' \
DSH_PLUGIN_CONFIG='{"platform":"android","incomingPreset":"phone-background","defaultServer":"https://dshorb.top"}' \
bash runtime/build-runtime.sh arm64-v8a

# 2) 重打 rootfs（用仓库脚本，不要现写——历史上两次现写都踩了同样的坑）
NEW_VERSION="r15-dsh0.2.2-rc.1-node24.22.1-ubuntu24.04.5-mesh0.1.6-${MESH_SHA:0:12}-presets9"

python3 -I runtime/lib/repack-rootfs.py \
  --rootfs runtime/out/arm64-v8a/assets/runtime/rootfs-arm64-v8a.tar.xz \
  --plugin-dir /tmp/plugpkg/package \
  --overlay runtime/.work/plugin-check/dsh-web.patch.yml \
  --version "$NEW_VERSION" \
  --manifest runtime/out/arm64-v8a/assets/runtime/manifest.json \
  --plugin-name '@dsh-mesh/plugin' --plugin-version 0.1.6 \
  --plugin-sha256 "$MESH_SHA" \
  --rootfs-path '/opt/dsh-go/plugins/@dsh-mesh/plugin' \
  --config '{"platform":"android","incomingPreset":"phone-background","defaultServer":"https://dshorb.top"}'
```

版本号约定：`r15-dsh<DSH版本>-node<Node版本>-<发行版>-mesh<插件版本>-<sha前12>-presets<N>`。**每改一次 overlay 就递增 `presetsN`**，否则 App 不会触发 runtime 升级。

`repack-rootfs.py` 已处理两个必须知道的约束（改脚本时不要破坏）：

1. **不能先解包到磁盘**：`opt/python/share/terminfo` 里有解析到自身的符号链接环，`tarfile.extract()` 会 ELOOP。
2. **tar.xz 流只能读一次**：为取 `runtime.json` 调了 `getmember()` 之后，后续 `for member in archive` 会静默少产出 99% 的成员。脚本用独立句柄读 `runtime.json`。

单次重打包约 6 分钟（33800 个成员、约 800 MB 未压缩），同一输入两次产出字节一致。

### 2.3 校验重打结果

```sh
python3 -I -c "
import json
m = json.load(open('runtime/out/arm64-v8a/assets/runtime/manifest.json'))
print('runtimeVersion:', m['runtimeVersion'])
for p in m.get('dshPlugins') or []:
    print('plugin:', p['name'], p['version'], p['sha256'][:16])
"
```

`dshPlugins[0].sha256` 必须与 `shasum -a 256 "$MESH_TGZ"` 一致。

### 2.4 构建 APK

```sh
export JAVA_HOME=$HOME/development/jdk17/Contents/Home
export ANDROID_HOME=$HOME/development/android-sdk
export PATH=$HOME/development/flutter/bin:$PATH

tooling/build-apk.sh -f arm64        # 正式 release，用 key.properties 的 release key
```

产物：`app/build/app/outputs/flutter-apk/app-arm64-release.apk`。

> **不要用 `flutter build apk` 直接发包**：它不走 `build-apk.sh` 的 versionCode 编码，也容易漏掉 `runtime/out` 里的新 rootfs。

需要与正式包并排安装时，用 Gradle 属性拿 dev 包名：

```sh
cd app/android && ./gradlew :app:assembleArm64Debug -PtestAppId=true
```

### 2.5 校验 APK

```sh
APK=app/build/app/outputs/flutter-apk/app-arm64-release.apk

# 包名 / 版本
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging "$APK" | head -1

# 内置 runtime
unzip -p "$APK" assets/runtime/manifest.json | python3 -m json.tool | head -20

# 签名（v1/v2/v3 都要 true，证书 CN=DSH Orb）
$JAVA_HOME/bin/java -jar $ANDROID_HOME/build-tools/36.0.0/lib/apksigner.jar \
  verify -v --min-sdk-version 21 --print-certs "$APK" | grep -E "Verified using|DN:|SHA-256 digest"
```

### 2.6 安装与 runtime 升级

```sh
adb -s "$PHONE_SERIAL" install -r "$APK"
adb -s "$PHONE_SERIAL" shell monkey -p top.dshorb.app -c android.intent.category.LAUNCHER 1
```

App 自己走 runtime upgrade：解压新 rootfs 到 `files/runtime/staging` → 校验 `runtime.json` 与 manifest → 检查 base identity → 切换。

**确认升级完成**：启动后看「设置 → 关于」的版本号，以及本机引擎是否「运行中」。

> release 包**不能 `run-as`**（`package not debuggable`），所以旧文档里的
> `adb shell run-as <pkg> cat files/runtime/rootfs.version` 对正式包不适用。
> 需要核对 runtime 版本时，直接读 APK 内的 manifest（2.5），或看 App 内的引擎信息。

## 3. 设备操作与观测

### 3.1 截图

```sh
adb -s "$PHONE_SERIAL" exec-out screencap -p > /tmp/phone.png
```

截图是 1440×3120（本机），直接看即可。

### 3.2 UI 树

```sh
adb -s "$PHONE_SERIAL" shell uiautomator dump /sdcard/window.xml
adb -s "$PHONE_SERIAL" shell cat /sdcard/window.xml
```

Flutter 页面主要看 `content-desc` 和 `bounds`。常见入口：

- 抽屉：「对话列表」
- 设置：「设置」→「远程主机」→「互联」
- 新聊天预设：「Agent 预设: 标准模式」
- 工作区：「工作区：全部对话。切换工作区」

### 3.3 点击/返回

```sh
adb -s "$PHONE_SERIAL" shell input tap 720 1234
adb -s "$PHONE_SERIAL" shell input keyevent 4   # back
adb -s "$PHONE_SERIAL" shell input keyevent 3   # home
```

坐标使用手机逻辑坐标 1440×3120。原生悬浮窗（宠物球、AskBar）不在 `uiautomator` 的普通页面树里，长按/拖动等手势用 adb 注入不稳定，建议手动操作。

### 3.4 日志

原生侧：

```sh
adb -s "$PHONE_SERIAL" logcat -d | grep -E 'DshPet|DshA11y|DshLaunch'
```

引擎侧：

```sh
adb -s "$PHONE_SERIAL" shell run-as "$DSH_TEST_APP" \
  cat files/runtime/logs/dsh.log
```

重点看：

- `mesh peer connected`
- `device_agent`
- `background_agent`
- `preset` / `plugin` 加载失败
- `ERR_MODULE_NOT_FOUND`

Mesh 状态：

```sh
adb -s "$PHONE_SERIAL" shell run-as "$DSH_TEST_APP" \
  cat files/runtime/rootfs/root/.dsh/dsh-mesh/state.json
```

### 3.5 无障碍

系统设置 → 无障碍 → 找到测试包的「DSH 手机操控」→ 打开 → 系统确认「允许完全控制」。

打开后看：

```sh
adb -s "$PHONE_SERIAL" shell dumpsys accessibility | grep 'Enabled services'
```

应只看到测试包的服务（原 App 已 force-stop）。

### 3.6 创建工作区

新聊天页 → 工作区选择 → 「添加工作区」→「默认工作目录 ~/workspace」→「添加「workspace」」。

工作区注册后，电脑端 `device_agent` 才能把它作为目标工作区。

## 4. 功能测试

### 4.1 Preset 是否加载

新聊天 → 点「Agent 预设」→ 检查：

- 「悬浮球」：Computer Use，可点击，不显示 broken；
- 「后台模式」：标准工具 + 手机操控 + 文件回传。

如果「悬浮球」不可点或显示加载失败，优先看 `dsh.log` 里的模块解析错误。

### 4.2 悬浮球发送截图

手动操作：

1. 切到任意 App（桌面、浏览器、设置均可）；
2. 长按悬浮球 →「问一句」；
3. 输入「我现在屏幕上是什么？」并发送；
4. 确认回复描述的是当前屏幕，而不是空泛回答。

期望：

- 原生 AskBar 隐藏自己/宠物、收起键盘；
- `PetScreenshot` 截当前屏；
- Dart 把截图作为内联图片附在 prompt；
- 安全页/无障碍未开时只发文字。

### 4.3 Mac → 手机：后台任务 + 文件回传

在 Mac 的 Orb 面板里：

> 用 device_agent 让 android-node 在 workspace 创建 `phone-bg-test.txt`，内容写 `hello from mac via phone-background`，然后调用 mesh_return_files 回传给我。

期望：

- Mac agent 调 `device_agent`；
- 手机创建 `phone-background` 会话，出现在手机 workspace；
- 手机调用 `mesh_return_files`；
- Mac 在 `.mesh-in/<taskId>/` 收到文件。

验证：

```sh
python3 - <<'PY'
import json, os
d = json.load(open(os.path.expanduser('~/.dsh/dsh-mesh/state.json')))
for t in d.get('tasks', []):
    if t.get('prompt', '').startswith('这是一次手机后台任务'):
        print(t['status'], t['sessionId'], t.get('files'))
PY

cat ~/.dsh/dsh_orb/.mesh-in/<taskId>/phone-bg-test.txt
```

### 4.4 手机 → Mac/Windows：device_agent / background_agent

**必须开新聊天**。旧会话保留创建时的工具集，不会自动获得新工具。

测试 prompt：

> 告诉我 Mac 和 Windows 桌面上都有什么文件。

或分别指定：

> 用 device_agent 让 Mac 在 dsh_orb 工作区创建 `phone-to-mac.txt`，内容写 `hello from phone`，然后回传给我。

> 用 background_agent 在 workspace 创建 `phone-bg-local.txt`，内容写 `hello from background`，完成后告诉我路径。

期望流程（与电脑端 Computer Use 一致）：

1. `device_agent` / `background_agent` 入队后立即返回；
2. 悬浮球说一句“任务在跑”，**结束本轮**；
3. 完成通知在任务空闲且调用方空闲后到达；
4. 悬浮球读取通知和回传路径，给出简短结论。

**不要**出现：

- `bash sleep 30`、`wait` 等轮询；
- 同一个任务重复派发；
- 为了拿结果再发一次 `device_agent` 要文件；
- 一轮对话里一直不停，直到结束才收到前面几次的结果。

如果出现上述行为，先确认是不是旧会话；旧会话的 persona 没有这条规则。

### 4.5 Windows 与 relay 诊断

Windows 上通过 SSH 检查：

```sh
ssh -i "$DSH_MESH_SSH_KEY" <windows-user>@<windows-hotspot-gateway>
```

抓 host 输出：

```powershell
Start-Process -FilePath 'C:\...\DeepSeek Harness.exe' `
  -RedirectStandardOutput 'C:\Users\<user>\dsh-host-out.log' `
  -RedirectStandardError  'C:\Users\<user>\dsh-host-err.log'
```

日志里重点看：

- `host identity ready`
- `server control connection online`
- `mesh peer connected`
- `closedBy: server`

如果出现 `closedBy: server`，说明是 relay 侧关闭了连接，不一定是 Windows host 崩了。再到 relay 服务器看：

```sh
ssh <relay-host> 'docker logs --since 10m deploy-server-1'
```

关注 `ws_hello_authenticated` / `ws_peer_disconnected` / `CONNECTION_FAILED`。

### 4.6 手机侧互联页

入口：抽屉 →「互联」（`AppIcons.link` 那个圆形按钮）。

应看到：

- 顶部：本机名、服务器地址、「已连接 N/N」、已加入互联；
- 「修改本机名称」独立成段；
- 下方每台对端设备一行，**默认全部折叠**，行尾是向下箭头；展开后行尾变向上箭头，且**同时只展开一台**；
- 展开后每个工作区一行：文件夹图标 + 名字 + 折叠箭头，在线设备的行尾还有「＋」（新建对话）；
- 底部：「退出互联」。

逐项验证：

1. **折叠**：点工作区标题，它的会话列表收起；再点展开回来。切换设备时折叠状态复位。
2. **新建会话**：点工作区行尾的「＋」→ 应在该电脑的这个工作区建一个会话并直接进入，标题「新建对话」，副标题是「<设备名> · <工作区名>」，正文显示「这个会话还没有记录。」
3. **普通会话可输入**：打开任意非任务会话，输入框占位是「输入消息，继续这段远端会话…」，可编辑、可发送，能收到回复。
4. **任务会话标记**：由 `device_agent` 创建的会话仍带 `taskId`，「停止全部」只停这类会话。

> 会话行图标是聊天气泡＝可输入；锁＝只读（设备离线或后端不支持）。


## 5. 测试层次

### 5.1 主机侧自动测试

```sh
python3 -I runtime/tests/test_phone_presets.py
bash runtime/tests/check-plugin-install.sh
node --test runtime/tests/bridge-package.test.mjs

cd app
$FLUTTER/bin/flutter analyze
DSH_TEST_FONT_DIR=/tmp/dsh-test-fonts \
  $FLUTTER/bin/flutter test test/pets/overlay/pet_actions_test.dart
```

Kotlin：

```sh
cd app/android
JAVA_HOME=$JDK17 ANDROID_HOME=$ANDROID_SDK \
  ./gradlew :app:testArm64DebugUnitTest \
  --tests 'io.github.ccch1mneyyy.dsh_app.pet.PetActionsTest'
```

已知失败：

- Flutter golden 测试因为本快照缺少 golden 文件会失败，功能测试通过；
- 全量 Android 单测里 `ProotTempFilesTest` / `L2sMigrationTest` 在 macOS 因 `/proc` 缺失失败，与本次改动无关。

### 5.2 真机检查

- `rootfs.version` 是目标版本；
- preset 列表出现「悬浮球」「后台模式」且可点；
- `dsh.log` 里 Mac / Windows peer 在线；
- 悬浮球发送截图（手动）；
- Mac → 手机 `device_agent` + `mesh_return_files`；
- 手机 → 电脑 `device_agent` / `background_agent` 的“派发即结束、通知后总结”流程。

## 6. 已知限制与收尾

### 6.1 限制

- Mac 上的 runtime 是“改包”复用，不是从零构建；正式版本在 Linux x86_64 构建机跑完整脚本。
- `uiautomator` / `input` 对原生悬浮窗不稳定；长按、拖动、语音等手势建议手动。
- 测试包与原 App 的无障碍服务互斥，需要 force-stop 原 App。
- Windows 节点的 relay 连接可能被服务器关闭重复 peer，表现为短暂断线；先看 `dsh-host-out.log` 和 relay 日志，不要直接判 Windows 崩溃。
- 手机无障碍设置不能用 adb 直接改，必须走系统设置。

### 6.2 收尾

恢复原 DSH Orb：

1. 打开原 App；
2. 在系统设置里重新启用它的「DSH 手机操控」；
3. 确认 `settings get secure enabled_accessibility_services` 包含原 App 的组件；
4. 测试包可以保留继续联调，也可以 `adb uninstall "$DSH_TEST_APP"` 删除。

提交前安全检查：

```sh
# 把 <relay-host-regex> 换成本地真实地址的转义正则；不要把真实值写进文档或提交历史
git grep -n -I -E \
  '<relay-host-regex>|BEGIN (RSA|OPENSSH|EC|PRIVATE) PRIVATE KEY|ghp_|github_pat_|sk-[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|/Users/<user>' \
  -- . ':!runtime/out'
```

确认没有真实服务器地址、私钥、token、个人路径。只推目标分支，不要 `push --all` / `--mirror`，避免把本地 backup refs 推上去。

## 附：常用测试 prompt

```text
告诉我 Mac 和 Windows 桌面上都有什么文件。

用 device_agent 让 Mac 在 dsh_orb 工作区创建 phone-to-mac.txt，
内容写 hello from phone，然后调用 mesh_return_files 回传给我。

用 device_agent 让 win32-asus 在桌面创建 phone-to-windows.txt，
内容写 hello from phone，然后回传给我。

用 background_agent 在 workspace 创建 phone-bg-local.txt，
内容写 hello from background，完成后告诉我路径。
```

## 附：当前状态

> 本节只记「写这份文档时」的快照，用来判断文档是否过期。具体 SHA 以 `git log` 为准。

- 分支：`feat/mesh-runtime`
- App 版本：`0.1.2`（versionName），包名 `top.dshorb.app`
- mesh 插件：`0.1.6`
- 真机 runtime：`...-mesh0.1.6-f3d905d2e731-presets9`
- 只装一个 App，没有并存的测试包；没有需要恢复的「原 DSH Orb」

配套文档：

- `docs/release.md`——打包与发版（版本号、签名、`release_assets.py`）
- `dsh-orb-cordis/docs/21-packaging-and-deploy.md`——跨仓库的完整打包与三端部署手册
