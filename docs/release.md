# 发版与应用内更新

App 以 APK 分发、不上商店，更新靠 App 自己查清单、下载、校验、调系统安装器。本文是发版约定和步骤，以及更新链路的取舍。代码在 `app/lib/update/`（检查、下载、校验）、`app/lib/state/update_state.dart`（状态与节流）、`app/lib/ui/update/`（设置行、弹窗）、`app/android/.../update/`（PackageInstaller 安装）。

> **正式发版前必须先换成固定的发布密钥**（见「签名」）。存在 `app/android/key.properties` 时 release 使用其中的发布密钥；缺少配置时仅为本地构建回落到 debug key，不可用于正式发布。

## 本仓库的发版范围

本 App 以 **DSH Orb** 的名称独立发布：包名、签名、仓库和更新源都是自己的；上游 DSH Go 的发布链路（Cloudflare Worker、R2 下载桶、内测通道）不在本仓库。发版走 GitHub Release + 自托管清单：

| 项 | 值 |
| --- | --- |
| 包名 | `top.dshorb.app`；`-PtestAppId=true` 时为 `top.dshorb.app.dev`，可与正式包并排安装 |
| 发布仓库 | `mini-yifan/dsh-orb-android`（公开仓库） |
| 官网 | `https://www.dshorb.top`，直接提供 arm64 APK |
| 产物 | `dist/dsh-orb-<版本>-arm64.apk` + `SHA256SUMS` + `update.json` |
| 签名 | `app/android/key.properties` → `~/keys/dsh-orb-release.jks`，证书 `CN=DSH Orb` |

`tooling/release_assets.py` 的 `APP`、`REPO` 常量决定产物名和发布仓库；改品牌时先改它们。

### 公开仓库与更新检查

`mini-yifan/dsh-orb-android` 从 2026-11-22 起是**公开仓库**。App 的更新检查用匿名请求读 GitHub Releases，私有仓库一律 404；因此这个仓库必须保持公开，或者只保留自托管清单（见「发布到更新源」）。

## 资产约定

每个版本一个 GitHub Release，仓库 `mini-yifan/dsh-orb-android`。正式版统一提供 arm64 安装包。`update.json` 供更新清单回退读取，`SHA256SUMS` 供缺少 GitHub digest 时校验 APK，`*.dshdelta` 是从最近几个旧版升上来的增量补丁（`docs/design/delta-update.md`），都由 App 自己用，不要求用户手动下载。GitHub 自动生成的 Source code 压缩包是源码快照，不是安装包。

| 项 | 约定 | 例子 |
|---|---|---|
| 版本名 | `app/pubspec.yaml` 的 `version: X.Y.Z[-pre]`，即 versionName；后面的 `+1` 只是占位 | `0.1.2` |
| 构建号 | Android versionCode，由 `tooling/build-apk.sh` 按构建时间生成，见「构建号」 | `11202149` |
| tag | `v` + 版本名（不带 `+N`） | `v0.1.2` |
| APK | `dsh-orb-<版本名>-<flavor>.apk`，正式版 flavor 固定为 `arm64`；文件名不带 `+N`（GitHub 会改写 `+`） | `dsh-orb-0.1.2-arm64.apk` |
| 增量补丁 | `dsh-orb-<版本名>-from-<旧版本名>-arm64.dshdelta`，只发不到整包 60 % 的 | `dsh-orb-0.1.2-from-0.1.1-arm64.dshdelta` |
| 校验和 | `SHA256SUMS`，`sha256sum` 的标准输出，APK 和补丁都在里面 | `<hex>  dsh-orb-0.1.2-arm64.apk` |
| 清单 | `update.json`（schema 1），镜像和自托管源都读它 | 见下 |
| 更新说明 | Release 正文（Markdown），App 弹窗里原样渲染 | |

`update.json` 由脚本生成，不要手写：

```json
{
  "schema": 1,
  "version": "0.1.2+11202149",
  "tag": "v0.1.2",
  "title": "DSH Orb 0.1.2",
  "publishedAt": "2026-11-20T13:49:00Z",
  "notes": "## 新功能\n…",
  "page": "https://github.com/mini-yifan/dsh-orb-android/releases/tag/v0.1.2",
  "assets": {
    "arm64": { "name": "dsh-orb-0.1.2-arm64.apk", "size": 206620966, "sha256": "…" }
  },
  "patches": {
    "arm64": [
      { "from": "<0.1.1 APK 的 SHA-256>", "fromVersion": "0.1.1+11201957",
        "name": "dsh-orb-0.1.2-from-0.1.1-arm64.dshdelta", "size": 13278848, "sha256": "…" }
    ]
  }
}
```

`assets.*.url` 可省：省略时 App 用 tag 和文件名拼 GitHub 下载地址；自托管时写绝对地址或相对 `update.json` 的地址（脚本的 `--base-url`）。公开更新源的清单写绝对地址（`https://dsh-orb-release.oss-cn-beijing.aliyuncs.com/app/download/<版本>/<文件>`）。`patches` 没有补丁时不出现，条目不带 `url`：补丁在每条线路上都和 APK 在同一目录。旧版 App 不认识它，照常下整包。

版本比较按 semver：预发布版低于正式版（`1.3.0-beta.1 < 1.3.0`），`+N` 只在两边都有时才参与比较（GitHub API 只给 tag，不带 `+N`）。所以 **同一个版本名重新打包不会被当成新版本**，要发就升版本名。

## 构建号

所有 APK，不论开发包还是正式包，一律用 `tooling/build-apk.sh` 出：

```sh
tooling/build-apk.sh                 # release arm64（真机）
tooling/build-apk.sh -f x64          # 本地模拟器验证，不公开发布
tooling/build-apk.sh -f all          # 双架构本地验证；正式发版不用此选项
tooling/build-apk.sh --dry-run -f all   # 只打印要执行的命令
```

脚本把构建时间编成 versionCode，传给 `--build-number`，同时传 `--build-name` 和 `DSH_APP_VERSION`：

```text
versionCode = (年 - 2026) × 10^8 + MMDDhhmm      时钟固定按 UTC+8
2026-10-25 12:34 → 10251234    2027-01-02 03:04 → 101020304    2046-12-31 23:59 → 2012312359
```

- **一条序列**：开发包和正式包按同一个时钟编号，后打的包号一定更大，正式包总能覆盖之前装的开发包。
- **每次都变**：系统和厂商 ROM 常按「包名 + versionCode」决定要不要刷新缓存的图标。号一直不变时，换了图标资源，通知和状态栏也可能一直显示旧的（vivo OriginOS 上出现过）。
- **看得懂**：设置里显示 `1.0.0 (10251234)`，一眼知道是 10 月 25 日 12:34 打的包；`python3 tooling/build_number.py --decode N` 可反查。
- **上限**：Android 限 2,100,000,000，这套编码用到 2046 年底。
- **绕过脚本时**（`flutter run`、IDE）：`build.gradle.kts` 发现 versionCode 还是占位的 `1`，就按同一公式现算。否则装到已有新号的设备上会被当成降级，`flutter run` 会先卸载旧版再装，数据全清。
- 测试：`python3 -m unittest discover -s tooling -p 'test_*.py'`。

## 发版步骤

下面是一份**在 2026-11-20 实测跑通**的完整序列（0.1.1 → 0.1.2）。按顺序做，任何一步失败就停下来看报错，不要跳到下一步。

### 1. 升版本名

```sh
# app/pubspec.yaml: version: 0.1.2+1
```

`+1` 是占位，不要动——versionCode 由 `build-apk.sh` 按构建分钟生成，手动改它会让降级判断出错。

### 2. 同步使用说明（会拦住打包）

改版本名后 `guide_sync.py --check` **必然报错**，它要求 README 的版本行与 pubspec 一致：

```sh
# docs/guide/README.md: > dsh-go 0.1.2   ← 前缀固定，只有数字跟着 pubspec
python3 app/tool/guide_sync.py           # 重新生成内置技能 app/assets/skills/dsh-go-guide/
python3 app/tool/guide_sync.py --check   # 必须干净
```

它同时校验：**加粗文字必须是真实 UI 标签**（来自 `app/lib/l10n/parts/*_<locale>.arb` 或 Android string resources）、相对链接可解析、中文页不许出现「会话」「实验」。

> 改 UI 文案后忘了同步 guide，`--check` 会报 `**xxx** is not a zh UI label`。反过来，把非标签的普通描述加粗也会报错——去掉加粗即可。

### 3. 打包

运行时先在 `runtime/out/` 里（`runtime/.gitignore` 忽略它，不入库）。**手机端 rootfs 变了才需要重跑 `runtime/build-runtime.sh`**；只改 Dart/Android 代码时不用动它：

```sh
tooling/build-apk.sh                      # release arm64，产物 → app/build/app/outputs/flutter-apk/app-arm64-release.apk
```

输出行形如 `dsh-orb 0.1.2 (11202149, 2026-11-20 21:49 UTC+8)`。

> **不要用 `flutter build apk` 直接构建发版包。** 它不走 `build-apk.sh` 的 versionCode 编码，且容易漏掉 `runtime/out` 里的新 rootfs——曾出现过 12:32 的 release 包里还带着上一版插件的情况。

### 4. 确认签名

```sh
$JAVA_HOME/bin/java -jar $ANDROID_HOME/build-tools/36.0.0/lib/apksigner.jar \
  verify -v --min-sdk-version 21 --print-certs \
  app/build/app/outputs/flutter-apk/app-arm64-release.apk
```

三条都要满足：v1/v2/v3 全 `true`、证书主题不含 `CN=Android Debug`、SHA-256 与上一版一致（`02:24:c1:2a:...`，`CN=DSH Orb`）。不满足就不要往下走。

### 5. 生成资产

```sh
cd tooling && python3 release_assets.py --notes /tmp/notes-0.1.2.md --delta-from 0
```

- `--delta-from 0`：`dist/` 里没有 `*.dshdelta` 时不生成增量补丁。本仓库目前还没启用增量补丁，直接用这个值；将来接上补丁链路再改成对应的旧版本。
- 产物在 `dist/`：`dsh-orb-<版本>-arm64.apk`、`SHA256SUMS`、`update.json`。
- 脚本会读 pubspec 的版本名 + 从 APK 里读 versionCode，**两者不匹配直接报错**（这是防止打错包的主要闸门）。
- 必须在 `tooling/` 目录下跑：`python3 -I tooling/release_assets.py` 会因为 `-I` 移除脚本目录而 `ModuleNotFoundError: apk_delta`。

### 6. 发布

```sh
cd tooling
python3 publish_oss.py --dry-run   # 校验并打印计划
python3 publish_oss.py             # OSS 清单 + 官网镜像 + GitHub Release
```

脚本会校验 `dist/update.json` 与 APK 的 sha256/size，把 APK 改写成 `.bin` 传 OSS，再用 `gh` 创建
`v<版本>` 的 Release（`.bin`、`.apk`、`update.json`），并在官网上放好 `.apk` 与 `latest` 软链。
所有 `gh` 命令必须显式带 `--repo mini-yifan/dsh-orb-android`（fork 的默认 remote 会被解析到上游）。
只有想手工建 Release 时才用 `gh release create`。

### 7. 真机验证

装到手机上，确认「设置 → 关于」的版本号是新的，再按 `docs/phone-device-testing.md` 跑功能测试。

## 内测通道（上游功能，本仓库不适用）

`app/lib/update/update_config.dart` 里还留着 `UpdateChannel`，`build-apk.sh --beta` 也能编入通道令牌，但签发和校验它的 Worker、KV、`~/keys/dshgo-beta-*` 凭据全部属于上游 DSH Go，没有搬到本仓库。没有那套服务，`--beta` 打出来的包只会一直「检查更新失败」。

本仓库只有一条公开更新通道：GitHub Releases + 自托管清单。要恢复内测通道属于新工作，不要在发版时使用 `--beta`。

## 发布到更新源（GitHub Releases + 加速镜像，OSS 兜底）

应用内更新的层次：

1. **清单**：`https://dsh-orb-release.oss-cn-beijing.aliyuncs.com/app/update.json`（OSS，约 1.6 KB，
   流量可忽略）。OSS 不可达时，会尝试 `releases/latest/download/update.json` 经内置加速镜像的副本。
2. **安装包**：清单里的 asset 没有写 `url`，App 会推导出 GitHub Release 的下载地址，然后按顺序尝试
   `gh-proxy.com` → `ghfast.top` → `ghproxy.net`（见 `update_config.dart` 的 `publicMirrors`），
   全部失败才回退到 OSS 的 `/app/download/<版本>/<文件>`。
3. **校验**：下载完成后校验清单里的 SHA-256，安装前再校验 APK 签名；加速镜像无法伪造包。

**为什么需要加速镜像**：手机实测 `github.com` 的 release 资源域名会被重置（40 秒 0 字节），
而三个加速镜像可用（实测 0.5–5 MB/s）。海外网络可以直连 GitHub，镜像同样可用。

**为什么更新包叫 `.bin`**：OSS 默认域名禁止公开分发 `.apk`（`ApkDownloadForbidden`），而 OSS 是兜底源，
所以 GitHub 资产与清单统一用 `.bin`（Android 按内容安装，真机 `pm install x.bin` 验证过）。
GitHub 上同时上传 `.apk`，方便人工下载与后续增量补丁生成。

**官网下载不经过 OSS**：官网由自己的服务器（Caddy）直接提供 `.apk`，
`/app/download/<版本>/dsh-orb-<版本>-arm64.apk`。OSS 上的 `.zip` 只是合规兜底副本，官网不引用它。

发版：

```sh
(cd tooling && python3 release_assets.py --notes notes.md --delta-from 0)
python3 tooling/publish_oss.py --dry-run   # 校验并打印计划
python3 tooling/publish_oss.py             # OSS + 服务器 zip/镜像 + GitHub Release
```

`publish_oss.py` 依赖 `oss2` 与 `~/.dsh-orb/private/oss-ram.csv`，并用 `gh` 创建/更新
`mini-yifan/dsh-orb-android` 的 Release。**`gh` 会把 fork 解析成上游仓库**，脚本里所有命令都显式带
`--repo mini-yifan/dsh-orb-android`；手动执行时也要带。



**官网镜像**：`publish_oss.py` 同时把 APK、`SHA256SUMS`、`update.json` 同步到服务器
`/opt/dsh-orb-site/downloads/app/`，并维护 `download/latest/dsh-orb-latest-arm64.apk` 软链。
官网容器的 Caddy 对 `/app/download/*.apk` 返回 `application/vnd.android.package-archive`，支持断点续传。
大文件不要用 scp 直接推这台 2C1.6G 的服务器（曾把它拖死）；脚本的路径是 Mac → OSS → 服务器内网取回。

上游 DSH Go 的「国内 / 海外 / GitHub 三条线路」（Cloudflare Worker、R2、香港对象存储）在本仓库不存在，
也不要按上游文档执行：那些服务和脚本都没有搬过来。

## 统计（本仓库不适用）

上游用 Cloudflare Analytics Engine 记下载、更新检查和匿名使用报告，代码在 `services/share-worker` 与 `wrangler.jsonc`——**两者都不在本仓库**。本仓库的 App 仍会按 `docs/design/share-links.md` 描述的行为上报，但因为没有对应的收集端，数据没有去处。

要用统计能力，需要先把 Worker 迁过来并配置数据集。

## 签名

Android 只接受和已装版本**同一签名**的覆盖安装。`build.gradle.kts` 从 `app/android/key.properties` 读取发布签名。

### 现状（2026-11-20）

`app/android/key.properties`（gitignore，不入库）已经指向 `~/keys/dsh-orb-release.jks`，别名 `dsh-orb`，证书 `CN=DSH Orb / O=DSH Orb / L=Beijing / C=CN`，SHA-256 指纹 `02:24:c1:2a:4d:1c:48:11:b0:f9:f2:d6:ca:75:0e:64:c7:13:9f:f6:65:92:bb:20:0a:e9:79:dd:dc:e9:7f:4b`。

**这把钥匙必须离线备份。** 丢了以后所有已安装用户都无法覆盖升级，只能卸载重装（数据全清）。

### 一个容易踩的坑：debug 构建也会用 release key

`key.properties` 一旦存在，`build.gradle.kts` 里 `debug` buildType 也会继承 release 签名（见文件里的 `debug { signingConfig = ... }`）。后果是：

- 用 `tooling/build-apk.sh --debug` 打出来的包**不能**覆盖安装到早先用系统 debug key（`54:9f:d6:...`）装的版本上，报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；
- 想保留数据原地升级，要么统一用 release key，要么临时把 `key.properties` 移走再打 debug 包。

判断手机上是哪把 key：把已装的 APK 拉下来 `apksigner verify --print-certs` 比对指纹。

### 首次生成发布密钥（新机器）


做法：生成一把发布密钥，只在发版机器上用，并离线备份（丢了等于以后再也发不了更新，所有用户都得卸载重装）。

```sh
keytool -genkeypair -v -keystore ~/keys/dsh-orb-release.jks -keyalg RSA -keysize 4096 -validity 36500 \
  -alias dsh-orb -dname 'CN=DSH Orb, O=DSH Orb, L=Beijing, C=CN'
```

`app/android/key.properties`（**加进 .gitignore，不要提交**）：

```properties
storeFile=/Users/<user>/keys/dsh-orb-release.jks
storePassword=…
keyAlias=dsh-orb
keyPassword=…
```

`app/android/app/build.gradle.kts`：

```kotlin
import java.util.Properties

val keyProps = Properties().apply {
    rootProject.file("key.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    signingConfigs {
        create("release") {
            storeFile = keyProps.getProperty("storeFile")?.let { file(it) }
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(if (keyProps.isEmpty) "debug" else "release")
        }
    }
}
```

**换钥匙的代价**：已经装了 debug 签名版的人，第一次更新会被系统拒绝。App 会在安装前比对签名，提示「签名不一致，不能直接覆盖」，引导去发布页下载；他们得**卸载旧版再装新版一次**，卸载会清空本地数据（对话、Linux 环境、设置、Keystore 里的 API Key）。之后的更新都正常。

想让老用户免卸载，有两条路，各有代价：

| 做法 | 好处 | 代价 |
|---|---|---|
| 把现在那台机器的 `debug.keystore` 当发布密钥长期用 | 老用户无感 | 证书是「Android Debug」，口令是公开的 `android`，安全全靠文件不外泄；以后想换还是要走一次这个流程 |
| APK 签名 v3 密钥轮换（`apksigner rotate` 生成 lineage，用 `apksigner sign --lineage` 重签） | Android 9+ 的老用户直接覆盖更新 | AGP 不直接支持，要在构建后手工重签；Android 8.x（minSdk 26）不认 lineage，仍需重装 |

App 的签名预检认 lineage：新包的签名历史里含已装版本的签名，就当作一致。

## App 怎么更新

```text
启动 6 秒后（每天最多一次，设置里可关）或 设置 → 关于 → 检查更新
  → 依次问元数据源（见「镜像与回落」），第一个给出可用版本的为准
  → 比已装版本新、且没被「跳过」→ 弹窗：版本、下载大小（有补丁可用时是补丁的大小）、更新说明
  → 立即更新：有补丁就下补丁、用已装 APK 拼出新包，否则下整包；都在 cache/updates/，断点续传，可取消，关掉弹窗也继续
  → SHA-256 校验（不一致删掉、换下一个源从头下）
  → 没有「安装未知应用」权限 → 引导到系统设置，回来自动继续
  → 预检：包名、签名和已装版本一致
  → PackageInstaller 会话安装，系统弹确认框 → App 被替换并退出
```

- **节流**：成功检查后 24 小时内不再自动查；失败后 4 小时内不重试；时钟被往回拨视为到期。手动检查不受限。
- **跳过这个版本**：这个版本不再自动弹，有更新的版本照常提醒；「关于」里显示「已跳过」。手动检查仍会打开弹窗。
- **稍后**：只关弹窗，下次自动检查（次日）还会提醒。
- **上次的结果会缓存**：重启后不联网也能看到「新版本」标记；装好新版后自动清掉缓存和残留安装包。
- **磁盘**：开始下载前要求可用空间 ≥ 安装包 × 2 + 64 MB（下载 + 安装暂存）。走补丁也是这个门槛：补丁拼完就删，峰值仍是安装暂存那一刻。
- 安装用 PackageInstaller 会话（把文件流写进会话），不用 FileProvider，也不需要 `content://`。

### 增量更新

`update.json` 可以带 `patches`，按 flavor 列出从某个旧 APK 到这一版 APK 的补丁：

```json
"patches": {
  "arm64": [
    { "from": "<旧 APK 的 SHA-256>", "fromVersion": "1.0.7", "name": "<补丁文件名>", "size": 12345678, "sha256": "<补丁文件的 SHA-256>" }
  ]
}
```

补丁和 APK 放在同一个版本、同样的镜像上，地址照 APK 的推：APK 地址的最后一段换成 `name`，然后是 GitHub 这一版的同名附件经各个镜像。缺字段的补丁条目直接忽略；APK 和补丁的 `name` 会成为下载目录里的文件名，必须是普通文件名（字母或数字开头，只含字母、数字和 `._+-`），否则那一项也忽略。没有 `patches` 的旧清单行为不变。

补丁格式 `DSHDLT01`，整数一律大端：

| 部分 | 内容 |
|---|---|
| 头（80 字节） | 8 字节 `DSHDLT01` · 32 字节旧 APK 的 SHA-256 · 32 字节新 APK 的 SHA-256 · u64 新 APK 长度 |
| `0x01` COPY | u64 旧 APK 里的偏移 · u64 长度：照抄旧 APK 的这一段 |
| `0x02` DATA | u64 长度 · 这么多字节：原样写出 |
| `0x00` END | 结束；写出的必须正好是头里的长度，SHA-256 也要对上 |

App 这边（`app/lib/update/delta.dart`、`app/lib/state/update_state.dart`）：

1. **已装 APK 的哈希**：清单里有本机 flavor 的补丁时，才去算 `applicationInfo.sourceDir` 的 SHA-256，在后台 isolate 里算，按 versionCode + 安装时间缓存（`update_installed_sha256`），每个装上的包只算一次。检查要等它算完才算结束，所以弹窗一出来就是对的大小。
2. **用补丁**：某条补丁的 `from` 等于这个哈希 → 下载补丁（续传、换源、超时都和 APK 一样）→ 校验补丁的 SHA-256 → 头里的旧 APK 哈希、新 APK 哈希和长度都要对上 → 从已装 APK 流式拼到整包下载会用的同一个文件（先写 `.part`，对上了才改名）→ 长度和 SHA-256 等于清单里 APK 的 → 删掉补丁，后面的签名预检和 PackageInstaller 安装不变。
3. **回落**：上面任何一步失败 → 不提示，当场改下整包，并记住这一版不再试补丁（`update_patch_failed`）。没有 `from` 对得上的补丁不算失败，直接下整包。取消下载不算失败，下次接着下补丁。
4. **内存**：补丁顺序读、旧 APK 按偏移读，各一个 64 KiB 缓冲，边写边算哈希。180 MB、约 42 万个操作的合成补丁，桌面上（AOT）2.1 秒、常驻内存 8 MB。

补丁不改变信任链：它只是拿到同一个 APK 的另一种办法，最后核对的仍是清单里 APK 的 SHA-256，安装前照旧过签名预检。

## 镜像与回落

源列表集中在 `app/lib/update/update_config.dart`。元数据按下表从上到下问，下载按镜像顺序试；**上次成功的源排到最前**，连不上某个源的手机不会每次都先等它超时。

| 源 | 用于 | 好处 | 坏处 |
|---|---|---|---|
| 自托管清单 `https://dsh-orb-release.oss-cn-beijing.aliyuncs.com/app/update.json`（默认） | 元数据 | 国内可直连；约 1.6 KB，流量可忽略 | 依赖 OSS；清单里只有 arm64 一项 |
| 公共加速镜像 `gh-proxy.com` → `ghfast.top` → `ghproxy.net` | APK 和补丁（排在最前） | 零成本，国内大多能用；下载不产生 OSS 流量 | 第三方：随时失效、换域名、限速、能看到下载者 IP；完整性靠清单 SHA-256 + APK 签名 |
| GitHub Release 的 `update.json` 经加速镜像 | 元数据（OSS 不可达时） | 和安装包同一个 CDN | 第三方镜像可能失效 |
| 自托管文件 `/app/download/<版本>/<文件>`（OSS） | APK 和补丁（最后兜底） | 自己的，完全可控 | 产生 OSS 流量费，只应在所有镜像失败时用到 |
| GitHub API `releases/latest` | 元数据（默认关闭，`useGithubApi: false`） | 官方，带 digest | 匿名 60 次/小时/IP；国内常超时 |

构建时可以改（`--dart-define`，不用改代码）：

| 变量 | 作用 |
|---|---|
| `DSH_UPDATE_MIRROR=https://…` | 换掉自托管源（同样的 `/update.json` + `/download/<版本>/<文件>` 布局）；设成空串就只剩 GitHub 和加速镜像 |
| `DSH_UPDATE_FEED=https://…/update.json` | 只换要问的清单，下载仍先试加速镜像和自托管源；空串 = 不问清单 |
| `DSH_UPDATE_MIRRORS=a,b` | 换掉公共加速镜像列表（前缀 + 完整 GitHub 地址）；空串 = 不用 |
| `DSH_BETA_CHANNEL_TOKEN` / `DSH_BETA_CHANNEL_ORIGIN` | 内测通道令牌与域名；本仓库没有对应服务，不要使用 |

公共镜像要定期确认还活着，失效的直接从列表删。

下载的超时策略：连接 15 秒、30 秒没收到数据算断线；同一个地址断线续传最多再试 2 次，连不上或返回错误就换下一个源。补丁和 APK 用同一套。

## 安全

- **SHA-256**：来自元数据（API 的 `digest`、`update.json` 的 `sha256`，都没有时读 `SHA256SUMS`）。缺校验和的版本 App 不装，只给发布页链接。它防的是传输损坏，以及只篡改 APK 的镜像。
- **签名**才是最后一道防线：镜像如果同时篡改清单和 APK，校验和也能对上，但它签不出我们的签名，系统和 App 的预检都会拒绝覆盖安装。所以发布密钥必须固定且保密，绝不能用一把谁都拿得到的钥匙。
- **降级**：系统拒绝 versionCode 更低的包；App 也只在版本更新时提示。正式包要在升完版本名之后、用 `build-apk.sh` 现打，不要拿早先打好的包去发：手机上若装着更晚打的旧版本名开发包，App 会提示更新，系统却会按降级拒装。

## 出问题时 App 的表现

| 情况 | 表现 |
|---|---|
| 还没有 release、限流、断网（仓库必须公开） | 「关于」里显示「暂时检查不了」；手动检查弹一条提示，不弹错误对话框；自动检查静默 |
| 新版没有本机 flavor 的 APK | 弹窗说明，按钮改为「打开发布页」 |
| 下载断了 | 保留已下载部分，「重试」接着下 |
| 校验不通过 | 删除文件，换源重下；都不通过就报错 |
| 签名不一致 | 安装前拦下，说明要卸载重装，按钮「打开发布页」 |
| 用户在系统确认框里取消 | 回到「安装包已下载」，可再装 |

## 真机验证清单

**已在真机上跑通**（0.1.7 → 0.1.8，2026-11-22）：检查更新发现新版本 → 经加速镜像下载 197 MB → SHA-256 校验 → PackageInstaller 拉起系统安装器 → ColorOS 的「继续安装」「已知悉应用风险」两道确认 → 升级后显示「已是最新」。公共镜像可用，全部镜像失败时能回落到自托管源。

以下项目仍未逐一验证：

- [ ] 首次安装：「安装未知应用」引导页能打开，授权后回到 App 自动继续安装
- [ ] 签名不同的包：安装前提示「签名不一致」，不出现系统报错
- [ ] 系统确认框按返回：回到「已取消安装」，可以再装
- [ ] 下载中切到后台、断网再恢复、杀进程后重进：都能续传
- [ ] 国产 ROM（vivo / 小米 / 华为）：自带的安装拦截页、「安全安装」流程不会让 App 卡在「安装中」（App 回到前台 3 秒后会自动放开）
- [ ] 增量补丁：有可用 `.dshdelta` 时走补丁而不是整包
