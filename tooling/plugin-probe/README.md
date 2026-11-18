# plugin-probe：第三方 DSH 插件 tgz 安装链路的最小 fixture

这是 B0-4 风险验证用的私有插件（`@dsh-orb-android/plugin-probe`），只做一件事：证明

```
tgz → sha256 校验 → 解包到 PRoot rootfs 的 /opt/dsh-go/plugins/<包名>
    → rtbuild.py 在 /etc/dsh-app/dsh-web.patch.yml 按绝对路径注册
    → 本机引擎 `dsh web` 加载并 apply
```

插件没有工具、路由、preset 或界面。`apply()` 只写一行固定前缀的日志，并提供一个冻结的只读服务
`dshOrbPluginProbe`（`health: 'ok'`）。它不是 `@dsh-mesh/plugin`，不要把它当成真实入网包。

## 构建 tgz

```sh
bash tooling/build-plugin-probe.sh
# 输出（可覆盖 OUT=...）：
#   tooling/plugin-probe/dist/dsh-orb-android-plugin-probe-0.1.0.tgz
#   tgz=... sha256=...
```

tarball 是可复现的（固定 mtime/uid/gid、排序成员、gzip mtime=0），重复构建的 sha256 不变。
`dist/` 已被本目录的 `.gitignore` 忽略。

## 本机（macOS 也可）校验

```sh
bash runtime/tests/check-plugin-install.sh
```

它会依次检查：`bash -n`、构建两次 tgz 并比对 sha256、用 `rtbuild.py extract-plugin` 校验哈希并解包、
检查解包路径/package.json/纯 JS、渲染真实 overlay 并检查行序与路径、在 Node 里用 mock ctx 真正
`import` 并 `apply` 解包后的入口。需要 Node 24.9+、python3、tar。

如果本机已有 DSH 安装（macOS 也可以，不要求 Linux），额外跑真实引擎加载：

```sh
DSH_PREFIX=/path/to/dsh-prefix bash runtime/tests/check-plugin-install.sh --with-host
# DSH_PREFIX 里应有 node_modules/@deepseek-ai/dsh/lib/bin.js
```

脚本会用临时 `HOME`/`DSH_HOME` 启动 `dsh web --patch <渲染出的 overlay>`，在日志里等
`[plugin-probe] loaded`，然后停掉进程。这一步在 macOS 上证明 overlay → 引擎加载；PRoot 与
Android rootfs 仍需在 Linux/真机上验证。**桌面/macOS 的结果不能替代真机 PRoot 验证**（仓库
`AGENTS.md` 的硬规则）。

## Linux x86_64 构建机：构建并确认进入 rootfs

```sh
bash tooling/build-plugin-probe.sh
bash runtime/build-runtime.sh arm64-v8a     # 至少需要 curl、sha256sum、python3、tar、xz、gpgv

python3 -I -c 'import json; m=json.load(open("runtime/out/arm64-v8a/assets/runtime/manifest.json")); print(json.dumps(m["dshPlugins"], indent=2, ensure_ascii=False))'
# 期望 name/version/entry/path/sha256；sha256 应与 tooling/build-plugin-probe.sh 打印的一致

tar -xOf runtime/out/arm64-v8a/assets/runtime/rootfs-arm64-v8a.tar.xz \
  'opt/dsh-go/plugins/@dsh-orb-android/plugin-probe/lib/index.js' | head -5

tar -xOf runtime/out/arm64-v8a/assets/runtime/rootfs-arm64-v8a.tar.xz \
  etc/dsh-app/dsh-web.patch.yml | grep -A1 'app-plugin-'
# 期望：
#     - id: app-plugin-dsh-orb-android-plugin-probe
#       name: '/opt/dsh-go/plugins/@dsh-orb-android/plugin-probe/lib/index.js'
```

`runtime/build-runtime.sh` 把 tgz 的 sha256、包名、版本、entry 一起算进 `runtimeVersion` 的输入哈希：
换插件版本或内容会得到新的 `runtimeVersion`，App 因此会重装引擎并替换 `/opt/dsh-go` 下的插件。

在 Linux x86_64 上还可以用 bwrap 真正启动 rootfs（不用 PRoot）：

```sh
runtime/test-host.sh
# 其中 "route guard patch and dsh-go-bridge" 一段会读取 manifest.dshPlugins 并检查插件文件在 rootfs 内
```

## 真机（Android）确认加载

1. 用上述 `runtime/out` 构建并安装 APK（`app/android` 的 flavor 读取 `runtime/out/<abi>/assets/runtime`）。
2. 打开 App 的运行日志/引擎日志（`DshProcess` 捕获 `dsh web` 的 stdout）。
3. 搜索 `[plugin-probe] loaded`。它出现在 `dsh web: http://127.0.0.1:...` 之前（token 行在全部
   插件加载完之后打印）。
4. 如果只有 token 行：先确认 rootfs 内
   `/opt/dsh-go/plugins/@dsh-orb-android/plugin-probe/lib/index.js` 存在，再确认
   `/etc/dsh-app/dsh-web.patch.yml` 有 `app-plugin-...` 行且路径一致，最后看日志里的
   `Cannot find module` / `ERR_MODULE_NOT_FOUND`。

安全模式（`SafeMode`）不会加载 App 的 overlay，所以插件也不会加载；这是预期行为。
真机验证至少覆盖：首次安装、引擎升级（`/opt/dsh-go` 被替换后插件仍在）、安全模式启动（插件
不加载、引擎仍能起）、以及上面第 3 步的日志。

## 换成真实的 `@dsh-mesh/plugin`

`dsh-mesh` 已经把自包含 tgz（`bundle/*.mjs` + `package.json` + `cordis.patch.yml` + `dsh-plugin.json`）
**入库**到 `packages/mesh-plugin/dist-phone/`，直接取用即可，不必先在本地打包：

```sh
cd ../dsh-mesh
ls packages/mesh-plugin/dist-phone/
# dsh-mesh-plugin-0.1.3.tgz
shasum -a 256 packages/mesh-plugin/dist-phone/dsh-mesh-plugin-0.1.3.tgz
```

只有改了 mesh 代码才需要重新打包。`pack-phone.py` 可复现：同一 `bundle/` 两次输出字节相同，
所以 sha256 可以钉住。

```sh
python3 packages/mesh-plugin/scripts/pack-phone.py
# -> packages/mesh-plugin/dist-phone/dsh-mesh-plugin-<version>.tgz
```

然后用这个 tarball 构建手机运行时：

```sh
DSH_PLUGIN_NAME='@dsh-mesh/plugin' \
DSH_PLUGIN_VERSION='0.1.3' \
DSH_PLUGIN_URL='file:///absolute/path/dsh-mesh-plugin-0.1.3.tgz' \
DSH_PLUGIN_ENTRY='lib/index.mjs' \
DSH_PLUGIN_CONFIG='{"platform":"android","incomingPreset":"phone-background"}' \
bash runtime/build-runtime.sh arm64-v8a
```

- `platform: android` 让插件按手机默认值处理 `remoteToolGuard`（`mcp__phone__*`），并让其他设备看到的节点平台是 android。
- `incomingPreset: phone-background` 让其他设备派来的任务跑「后台模式」preset：标准工具 + 手机操控 + `mesh_return_files` 文件回传。这个 preset 由 `runtime/bridge/dsh-go-bridge/phone-background.patch.yml` 定义，只在构建时打包了 `@dsh-mesh/plugin` 才会写进应用 overlay；没有插件时它不会出现，避免一个加载不了的 preset。
- 真实 tgz 不含 `node_modules`；bundle 里的 werift 是 Relay-only 的纯 JS 代码，v0 不启用 WebRTC 就不会加载原生后端。
- 包名和入口以 tarball 里的 `package.json` 为准；`DSH_PLUGIN_VERSION` 必须和 `package.json.version` 完全一致，否则 `extract-plugin` 会拒绝解包。

注意：

- `DSH_PLUGIN_SHA256` 对 `https://` 是必填；`file://` 开发模式可以不填，脚本会按本地文件计算。
- `DSH_PLUGIN_PREFIX` 默认 `/opt/dsh-go/plugins`，只允许留在 `/opt/dsh-go/` 这个引擎自有目录内，
  这样引擎升级会整体替换插件，不会和用户数据混在一起。
- 运行时按绝对路径 `import` 解包后的入口，**不会执行 npm install**。真实包的依赖必须已经打进
  tarball 的 `node_modules`，否则加载会失败；`extract-plugin` 会在 `dependencies` 非空且没有
  `node_modules` 时打印警告。
- 构建会在 rootfs 里加一个 `opt/dsh-go/plugins/node_modules -> ../../dsh/node_modules` 符号链接，
  让按路径加载的插件能像 profile 包一样解析 `@deepseek-ai/cordis`、`@deepseek-ai/dsh-*` 等 peer
  依赖。插件自带的 `node_modules` 仍然优先。真实包如果 import 的 peer 不在 DSH 树里，就要自己打进
  tarball。
- tarball 必须纯 JS/TS：`.node`/`.so`/`.dylib`/`.dll`/`.exe`/`.a`/`.wasm`、`prebuilds/`、
  `binding.gyp` 和任何 ELF 都会被拒绝。
- 想完全不打包插件：`DSH_PLUGIN_URL=`（显式置空）后构建。
- 一个包名对应一行 overlay；真实包不要同时用 `dsh plugin add` 装进 profile，否则同一模块会被
  加载两次（`dshOrbPluginProbe` 这类服务会重复 provide）。