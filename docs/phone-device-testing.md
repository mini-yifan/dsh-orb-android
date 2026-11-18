# 手机真机测试手册

> 适用仓库：`dsh-orb-android`
> 记录时间：2026-11-13
> 最近验证：runtime `r15-dsh0.2.2-rc.1-...-presets4`，分支 `feat/mesh-runtime`，提交 `a53d51f`
>
> 目标：在真机上验证 DSH runtime、`orb-phone` / `phone-background` preset、悬浮球发送截图，以及手机与 Mac/Windows 的 Mesh 互联。

## 0. 速查清单

- [ ] `adb devices` 能看到手机，状态为 `device`
- [ ] 测试包使用独立 `applicationId`，不覆盖原 DSH Go
- [ ] 新 runtime 已打进 APK，安装后 `rootfs.version` 变成目标版本
- [ ] 新聊天里能看到「悬浮球」「后台模式」，且可点击
- [ ] 悬浮球「问一句」发送后，模型能描述当前屏幕
- [ ] Mac → 手机 `device_agent` 任务完成，文件回到 Mac 的 `.mesh-in/<taskId>/`
- [ ] 手机 → Mac/Windows `device_agent` 派发后本轮结束，完成通知随后到达，不 `sleep` 轮询
- [ ] 日志里没有 preset/plugin 加载失败
- [ ] 测完恢复原 DSH Go 与无障碍服务

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

### 1.3 测试包隔离

- 原 App：`io.github.ccch1mneyyy.dsh_app`，release 签名，数据不要动。
- 测试包：`io.github.ccch1mneyyy.dsh_app.mesh`，debug 签名，单独安装。
- 构建测试 APK 前临时把 `app/android/app/build.gradle.kts` 的 `applicationId` 改成 `.mesh`，**构建完成后改回**。
- `adb install -r` 保留测试包自己的 `/data`、runtime 和 Mesh 配对状态。

### 1.4 无障碍冲突

两个 App 的 `PhoneA11yService` 会互相把对方挤出 `enabled_accessibility_services`。测测试包时：

1. 在系统设置里只启用测试包的无障碍服务；
2. 把原 DSH Go force-stop（只停进程，不删数据），否则它的 keeper 会把服务列表改回去；
3. 测完恢复原 App 并重新启用它的无障碍。

`adb shell settings put secure ...` 不可用（shell 没有 `WRITE_SECURE_SETTINGS`），必须走系统设置 UI。

## 2. 测试包与 runtime 构建

### 2.1 为什么在 Mac 上是“改包”而不是从零构建

完整的 `runtime/build-runtime.sh` 需要 Linux x86_64 构建机。Mac 上的流程是：

1. 复用已有 arm64 rootfs；
2. 重新生成 overlay；
3. 替换 rootfs 里的 overlay 和 `runtime.json`；
4. 重打 `tar.xz`、更新 manifest；
5. 用 Flutter 构建测试 APK。

生产构建仍应在 Linux 构建机上跑完整脚本。

### 2.2 生成 overlay

用真实 Mesh 插件 tgz 跑插件检查，生成与 `assemble` 相同的 overlay：

```sh
cd dsh-orb-android

RUNTIME_PLUGIN_CHECK=1 \
DSH_PLUGIN_NAME='@dsh-mesh/plugin' \
DSH_PLUGIN_VERSION='0.1.0' \
DSH_PLUGIN_URL="file://$MESH_TGZ" \
DSH_PLUGIN_ENTRY='lib/index.mjs' \
DSH_PLUGIN_CONFIG='{"platform":"android","incomingPreset":"phone-background"}' \
bash runtime/build-runtime.sh arm64-v8a
```

产物：`runtime/.work/plugin-check/dsh-web.patch.yml`。

overlay 应包含：

- `preset-orb-phone`：悬浮球 preset；
- `preset-phone-background`：后台模式 preset（仅打包 Mesh 插件时出现）；
- `tool-device-agent`：派给已互联电脑；
- `tool-background-agent`：派给手机本机后台；
- `<mesh plugin>` 的绝对路径行。

### 2.3 重打 rootfs

用 `runtime/lib/repack-rootfs.py`（不要临时现写，历史上两次现写都踩了同样的坑）：

```sh
cd dsh-orb-android

NEW_VERSION="r15-dsh0.2.2-rc.1-node24.22.1-ubuntu24.04.5-mesh0.1.3-92f7469a7a7c-presets7"

python3 -I runtime/lib/repack-rootfs.py \
  --rootfs runtime/out/arm64-v8a/assets/runtime/rootfs-arm64-v8a.tar.xz \
  --plugin-dir /tmp/plugpkg/package \
  --overlay runtime/.work/plugin-check/dsh-web.patch.yml \
  --version "$NEW_VERSION" \
  --manifest runtime/out/arm64-v8a/assets/runtime/manifest.json \
  --plugin-name '@dsh-mesh/plugin' --plugin-version 0.1.3 \
  --plugin-sha256 <tgz 的 sha256> \
  --rootfs-path '/opt/dsh-go/plugins/@dsh-mesh/plugin' \
  --config '{"platform":"android","incomingPreset":"phone-background"}'
```

脚本做的事：

- 替换整个 `opt/dsh-go/plugins/@dsh-mesh/plugin/`（旧版的 chunk 文件必须一并消失，否则
  `index.mjs` 会 import 不存在的模块）；
- 替换 `etc/dsh-app/dsh-web.patch.yml`；
- 更新 `etc/dsh-app/runtime.json` 的 `runtimeVersion`（必须和外层 manifest 完全一致，
  否则 App 的兼容性检查会拒绝升级）；
- 其余成员保持原顺序、mtime、uid/gid、mode；
- 写回 `manifest.json` 的 `runtimeVersion` / `rootfsSha256` / `rootfsBytes` /
  `tarSha256` / `tarBytes` / `uncompressedBytes` / `entries` / `dshPlugins`。

**两个必须知道的约束**（脚本已处理，改动时不要破坏）：

1. **不能先解包到磁盘**。rootfs 的 `opt/python/share/terminfo` 里有解析到自身的符号链接
   环，`tarfile.extract()` 会直接 ELOOP 失败。
2. **tar.xz 流只能读一次**。`tarfile` 在 xz 上单遍读取，一旦为取 `runtime.json` 调了
   `getmember()`，后续 `for member in archive` 只会悄悄产出几百个成员——归档看着"成功"，
   实际丢了 99% 的内容。脚本因此用独立句柄读 `runtime.json`。

版本号约定：

```text
r15-dsh0.2.2-rc.1-node24.22.1-ubuntu24.04.5-mesh0.1.3-<inputs>-presetsN
```

`mesh<版本>` 跟插件 tgz 的 `package.json.version`；每改一次 overlay 就递增 `presetsN`，
App 才会触发 runtime 升级。

单次重打包约 6 分钟（33800 个成员、800 MB 未压缩）。同一份输入跑两次产出字节一致的
`tar.xz`，便于核对。

### 2.4 校验重打结果

脚本已写回 manifest，但仍应确认归档内容（目录项数量应与原归档一致）：

```sh
python3 - <<'EOF'
import tarfile
with tarfile.open('runtime/out/arm64-v8a/assets/runtime/rootfs-arm64-v8a.tar.xz','r:xz') as t:
    n = 0
    plugin = []
    rt = None
    for m in t:
        n += 1
        if 'dsh-mesh/plugin' in m.name: plugin.append(m.name)
        if m.name == 'etc/dsh-app/runtime.json':
            import json; rt = json.loads(t.extractfile(m).read())
    print('members:', n)                 # 应与原归档同一量级（约 33800）
    print('plugin entries:', len(plugin)) # 应为 9（目录 2 + 文件 7）
    print('runtime.json version:', rt['runtimeVersion'])
EOF
```

再确认插件真的带着修复（不是又一次打进旧包）：

```sh
python3 - <<'EOF'
import tarfile
with tarfile.open('runtime/out/arm64-v8a/assets/runtime/rootfs-arm64-v8a.tar.xz','r:xz') as t:
    for m in t:
        if m.name.endswith('dsh-mesh/plugin/lib/index.mjs'):
            s = t.extractfile(m).read().decode()
            for label, pat in [('host reconnect', 'initialDelayMs: 4e3'),
                               ('empty fp guard', 'trim() === ""'),
                               ('host watchdog', 'hostNudgedAt')]:
                print('OK  ' if pat in s else 'MISS', label)
            break
EOF
```

并把新 `rootfs-arm64-v8a.tar.xz` 复制到同一目录。

注意：rootfs 内 `etc/dsh-app/runtime.json` 的 `runtimeVersion` 必须和外层 manifest 完全一致，否则 App 的兼容性检查会拒绝升级。

### 2.5 构建 APK

临时把测试包 `applicationId` 设为 `.mesh`：

```sh
cd app

JAVA_HOME=$JDK17 \
ANDROID_HOME=$ANDROID_SDK \
$FLUTTER/bin/flutter build apk --debug --flavor arm64 --target-platform android-arm64
```

产物：

```text
app/build/app/outputs/flutter-apk/app-arm64-debug.apk
```

校验：

```sh
$ANDROID_SDK/build-tools/36.0.0/aapt2 dump badging \
  app/build/app/outputs/flutter-apk/app-arm64-debug.apk | head -3

unzip -p app/build/app/outputs/flutter-apk/app-arm64-debug.apk \
  assets/runtime/manifest.json | python3 -m json.tool | head
```

确认包名、`runtimeVersion`、`dshPlugins[0].config.incomingPreset`。

构建完成后把 `applicationId` 改回 `io.github.ccch1mneyyy.dsh_app`，避免误覆盖原 App。

### 2.6 安装与 runtime 升级

```sh
adb -s "$PHONE_SERIAL" install -r \
  app/build/app/outputs/flutter-apk/app-arm64-debug.apk

adb -s "$PHONE_SERIAL" shell monkey \
  -p "$DSH_TEST_APP" -c android.intent.category.LAUNCHER 1
```

App 会自己走 runtime upgrade：

1. 从 APK assets 解压新 rootfs 到 `files/runtime/staging`；
2. 校验 `staging/etc/dsh-app/runtime.json` 与 manifest；
3. 检查 base identity；
4. 切换到新 rootfs。

用版本文件确认：

```sh
adb -s "$PHONE_SERIAL" shell run-as "$DSH_TEST_APP" \
  cat files/runtime/rootfs.version
```

升级页会显示类似 `94% / 170 / 179 MB` 的进度。看到目标版本后再继续。

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

### 4.6 手机侧 Mesh 页面

设置 → 远程主机 → 互联：

- 手机自己的节点和连接数；
- 对端设备（Mac / Windows）是否在线；
- 工作区和会话列表；
- 远端会话的实时 transcript；
- 手机自己创建的任务会话可输入/停止，电脑创建的任务会话只读。

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

恢复原 DSH Go：

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

- 分支：`feat/mesh-runtime`
- 最新提交：`a53d51f`（`fix(phone): stop the orb polling dispatched tasks`）
- 真机 runtime：`...-presets4`
- 测试包：`io.github.ccch1mneyyy.dsh_app.mesh`，仍在手机上
- 原 DSH Go：已被 force-stop，数据未动；测完需恢复