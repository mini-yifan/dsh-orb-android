# 发版与应用内更新

App 以 APK 分发、不上商店，更新靠 App 自己查 GitHub Releases、下载、校验、调系统安装器。本文是发版约定和步骤，以及更新链路的取舍。代码在 `app/lib/update/`（检查、下载、校验）、`app/lib/state/update_state.dart`（状态与节流）、`app/lib/ui/update/`（设置行、弹窗）、`app/android/.../update/`（PackageInstaller 安装）。

> **正式发版前必须先换成固定的发布密钥**（见「签名」）。存在 `app/android/key.properties` 时 release 使用其中的发布密钥；缺少配置时仅为本地构建回落到 debug key，不可用于正式发布。

## 本仓库的发版范围

本 App 已独立为 **DSH Orb**，与上游 dsh-go 没有关系。发版只走 GitHub Release：

| 项 | 值 |
| --- | --- |
| 包名 | `top.dshorb.app`；`-PtestAppId=true` 时为 `top.dshorb.app.dev`，可与正式包并排安装 |
| 发布仓库 | `mini-yifan/dsh-orb-android`（**当前为私有**，见下方「私有仓库与更新检查」） |
| 产物 | `dist/dsh-orb-<版本>-arm64.apk` + `SHA256SUMS` + `update.json` |
| 签名 | `app/android/key.properties` → `~/keys/dsh-orb-release.jks`，证书 `CN=DSH Orb` |

`tooling/release_assets.py` 的 `APP`、`REPO` 常量就是上表后两行；改品牌时先改它们。

> **上游的发布链路在本仓库不存在。** 本仓库没有 `services/share-worker`、没有 R2 / Cloudflare Worker、没有 `dl.dshtui.com` 与 `go.dshtui.com`、没有内测通道令牌。本文后半部分关于「三条线路」「内测通道」的旧内容是上游 DSH Go 的，**照着做会失败**。要加自托管下载源属于新工作。

### 私有仓库与更新检查

`mini-yifan/dsh-orb-android` 是私有仓库，而 App 的更新检查用匿名请求读 GitHub Releases——**匿名访问私有仓库一律 404**，App 只会显示「暂时检查不了」。

发版前必须明确走哪条：

1. **只手动分发**（当前做法）：打好的 APK 直接发，App 内「检查更新」不工作。适合小范围自用。
2. **转公开仓库**：`gh repo edit mini-yifan/dsh-orb-android --visibility public`，之后 `releases/latest` 才可用。转公开前按第 6.2 节做安全复查。

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

`assets.*.url` 可省：省略时 App 用 tag 和文件名拼 GitHub 下载地址；自托管时写绝对地址或相对 `update.json` 的地址（脚本的 `--base-url`）。公开下载桶的清单写绝对地址（`https://dl.dshtui.com/download/<版本>/<文件>`）。`patches` 没有补丁时不出现，条目不带 `url`：补丁在每条线路上都和 APK 在同一目录。旧版 App 不认识它，照常下整包。

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

- `--delta-from 0`：本仓库还没有历史 Release，增量补丁会去 GitHub 拉旧包而失败。**首次发版或没有历史 Release 时必须用它。**
- 产物在 `dist/`：`dsh-orb-<版本>-arm64.apk`、`SHA256SUMS`、`update.json`。
- 脚本会读 pubspec 的版本名 + 从 APK 里读 versionCode，**两者不匹配直接报错**（这是防止打错包的主要闸门）。
- 必须在 `tooling/` 目录下跑：`python3 -I tooling/release_assets.py` 会因为 `-I` 移除脚本目录而 `ModuleNotFoundError: apk_delta`。

### 6. 发布

```sh
gh release create v0.1.2 dist/* --repo mini-yifan/dsh-orb-android \
  --title 'DSH Orb 0.1.2' --notes-file /tmp/notes-0.1.2.md
```

脚本最后会把这行打印出来。**私有仓库下 App 内检查更新不工作**（见「私有仓库与更新检查」），所以这一步主要意义是留档。

### 7. 真机验证

装到手机上，确认「设置 → 关于」的版本号是新的，再按 `docs/phone-device-testing.md` 跑功能测试。

## 内测通道（go.dshtui.com/beta）

> **代码在本仓库，服务端不在。** `app/lib/update/update_config.dart` 的 `UpdateChannel` 和 `tooling/build-apk.sh --beta` 都还在，但签发/校验它的 Worker、KV、`~/keys/dshgo-beta-*` 凭据属于上游，没有搬过来。没有那套服务，`--beta` 打出来的包只会一直「检查更新失败」。下面的运维命令同样属于上游，保留作参考。

内测包不公开：人凭**密钥**在 `https://go.dshtui.com/beta` 下载，已装的 App 凭构建时注入的**通道令牌**检查更新、App 内下载。两者分开：群里的密钥可以随时换、随时吊销，已装 App 照常收到更新。服务端是 `services/share-worker`（设计见 `docs/design/share-links.md` §14），凭据只以文件形式存在发版机器上，放在仓库之外：

| 文件（脚本的默认位置，权限 600） | 内容 | 用在 |
|---|---|---|
| `~/keys/dshgo-beta-secret` | Worker secret `BETA_SECRET`：密钥哈希的盐、下载链接的签名钥 | `beta-keys.sh` |
| `~/keys/dshgo-beta-channel.token` | 通道令牌（`dshc_…`） | `build-apk.sh --beta` 才编进内测包（公测包不带）；`publish-release.sh` 实测 |
| Cloudflare API token | 部署、写 R2 / KV | 各脚本的 `CF_TOKEN_FILE` |
| `~/keys/dshgo-cloudflare-account-id` | Cloudflare 账号 id（32 位十六进制）。公开仓库的 `wrangler.jsonc` 只有占位符（`tooling/oss-redact.txt`），R2 的地址和部署靠它 | 所有用到账号的脚本（`scripts/cloudflare.sh`）；也可以用 `CF_ACCOUNT_ID` 直接给 |
| `~/keys/dshgo-cloudflare-kv-quota-id` | KV 命名空间 `dshgo-share-quota` 的 id（32 位十六进制），`wrangler.jsonc` 里同样只有占位符；`provision.sh apply` 发现或新建 KV 时写这个文件 | `provision.sh`、`beta-keys.sh`；也可以用 `CF_KV_QUOTA_ID` 直接给 |

部署和管密钥（`provision.sh`、`beta-keys.sh`）在公开仓库里就能跑：两个 id 只写进 `wrangler.jsonc` 旁边的临时副本（`.wrangler-*.json`，`wrangler --config` 用它，退出即删），跟踪的 `wrangler.jsonc` 不改；读到的是占位符时，什么都不做就报错。`provision.sh` 还会核对：账号是不是 `dshtui.com` 所在的账号、KV id 是不是 `dshgo-share-quota` 的。

**一次性开通**（每台发版机器一次）：

```sh
cd services/share-worker
CF_TOKEN_FILE=<token 文件> scripts/beta-keys.sh init                 # 生成 BETA_SECRET 并设为 Worker secret
CF_TOKEN_FILE=<token 文件> scripts/beta-keys.sh channel-add app-1    # 生成通道令牌并登记
CF_TOKEN_FILE=<token 文件> scripts/beta-keys.sh add beta-1 --out <文件>   # 给人用的密钥，明文只写进 <文件>
```

**每次发内测版**：

1. 改版本名（`app/pubspec.yaml`）并同步使用说明（同上「发版步骤」第 1 步，`guide_sync.py --check` 通过），在 `docs/changelog.md` 写好 `## X.Y.Z · 日期`，下面 `### 中文`、`### English` 两节——页面和 App 弹窗的更新说明从这里取，缺一节脚本就停。
2. 打包：`tooling/build-apk.sh`（arm64）。第一行要带 `· beta channel`，说明令牌已编进去；没有令牌文件、或 `--debug` / `--profile` 的包都不带，它们只查 GitHub。
3. 确认签名（同上第 3 步），生成资产：`python3 tooling/release_assets.py`，产物在 `dist/`。
4. 上传并实测：

   ```sh
   cd services/share-worker
   CF_TOKEN_FILE=<token 文件> BETA_KEY_FILE=<存着一把有效密钥的文件> scripts/publish-release.sh ../../dist --no-dl
   ```

   `--no-dl`：公开下载桶谁都能读，内测包不能放上去。

   脚本核对 `dist/` 的大小和 SHA-256，把 `SHA256SUMS` 里的文件和它本身传到 R2 `beta/<版本>/`（配了香港桶也传过去，见下一节），以 App 的身份（通道令牌）整包下载回来比对，全部一致后才传改写过的 `update.json`（下载地址指向 `/beta/<版本>/`，`page` 指向 `/beta`，`notes` 和 `notesByLocale` 来自更新日志，`routes` 记着文件在哪几条线路上），最后换 `beta/update.json`。之后再以人的身份（密钥 → 签名链接）下载一次。`--no-latest` 只传文件不换最新版；同一版本已上传且大小不同会被拒绝，修包要升版本名。
5. 手机上验证：装着上一版内测包的手机 设置 → 关于 → 检查更新。

**密钥**（`scripts/beta-keys.sh`，都要 `CF_TOKEN_FILE`）：

| 操作 | 命令 |
|---|---|
| 新建 | `add <标签> [--expires 2026-12-31] [--out 文件]`，格式 `dsh-` + 8 位（不含 0 1 i l o），不加 `--out` 时打印到终端 |
| 查看 | `list`：标签、创建、过期、状态，不显示密钥 |
| 吊销 | `revoke <标签>`；KV 全球生效要一分钟左右，已发出的下载链接最多再用 10 分钟 |
| 换掉所有密钥 | `init --rotate` 换 `BETA_SECRET`，旧密钥全部失效（通道令牌不受影响），再 `add` 新的 |
| 通道令牌 | `channel-add <标签>` / `channel-list` / `channel-revoke <标签>`。换令牌：新令牌另存一个文件，`BETA_TOKEN_FILE=<新文件> … channel-add app-2`，之后的包用新令牌（`DSH_BETA_TOKEN_FILE=<新文件> tooling/build-apk.sh`）；等旧包的人都升级了再吊销 `app-1`，否则他们就收不到更新了 |

**回滚**：把上一版的清单重新设为最新，新版文件留着不影响（在 `services/share-worker` 下，环境变量 `CLOUDFLARE_API_TOKEN` 取自 token 文件，`CLOUDFLARE_ACCOUNT_ID` 取自 `~/keys/dshgo-cloudflare-account-id`）。公开下载桶、GitHub 和香港桶见「发版到三条线路」的回滚表。

```sh
npx wrangler r2 object get dshgo-share/beta/<上一版>/update.json --file prev.json --remote
npx wrangler r2 object put dshgo-share/beta/update.json --file prev.json --content-type 'application/json; charset=utf-8' --remote
```

**限制**：令牌编在 APK 里，拿到 APK 的人能取出来，所以它挡的是「没有安装包的人」，不是装过的人；泄露了就按上表换令牌。没有令牌的旧包（包括 0.9.1）不会走内测通道，要用户在页面上手动下载一次带令牌的新包。

## 发布到更新源（阿里云 OSS，当前唯一分发口）

应用内更新读 `https://dsh-orb-release.oss-cn-beijing.aliyuncs.com/app/update.json`，下载
`…/app/download/<tag 去掉 v>/<文件>`（origin 编译在 `app/lib/update/update_config.dart` 的
`updateDownloadOrigin`；release 构建没有备用源）。

**为什么不是 dshorb.top：** 这个域名没有 ICP 备案，国内不少网络会在 TLS 握手阶段直接重置到它的
443 连接；手机实测过「检查更新 → 暂时检查不了」。阿里云 OSS 的默认域名不需要备案，国内可直连。

**为什么文件叫 `.bin`：** OSS 默认域名禁止公开分发 `.apk`（`ApkDownloadForbidden`，要求改用已备案
的 CNAME 域名）。Android 按内容安装、不看扩展名（真机 `pm install x.bin` 验证过），所以更新清单里
的 asset name 用 `.bin`；官网给用户下载的是 `<版本>.zip`（内含 `.apk`），解压后安装。

发版最后一步：

```sh
python3 tooling/publish_oss.py --dry-run   # 本地校验并打印计划
python3 tooling/publish_oss.py             # 直传 OSS + 服务器生成 zip/镜像
```

依赖：`oss2`（`pip3 install oss2`）与本机 `~/.dsh-orb/private/oss-ram.csv`（RAM AccessKey，仅
`dsh-orb-release` 桶的读写）。脚本流程：

1. 校验 `dist/update.json` 与 APK 的 sha256/size 逐字一致；
2. 本机把 APK 复制成 `.bin`、改写清单里的 asset name，用 OSS SDK 分片直传（约 2 分钟 / 200MB）；
3. 服务器从 OSS 取回 `.bin`、生成 `.zip`、回传 OSS，并同步一份到 `dshorb.top` 的镜像目录与
   `/opt/dsh-orb-site/downloads/app/update.json`。

官网（OSS 根目录）与 `dshorb.top` 用**同一份构建**：站点里的更新源和下载链接都是相对路径
（`/app/update.json`、`/app/download/...`），各自读自己域名下的那份清单。

## 多线路分发（本仓库不适用）

上游 DSH Go 有「国内 / 海外 / GitHub 三条线路」的分发体系：`services/share-worker`（Cloudflare Worker）、公开下载桶 `dl.dshtui.com`（R2）、香港对象存储、以及 `go.dshtui.com` 下载页。

**这些在本仓库都不存在**，`services/`、`wrangler.jsonc`、相关脚本都没有搬过来。本仓库的发版就是一回事：`tooling/release_assets.py` 产出 `dist/`，然后 `gh release create` 传到 GitHub Release。

要恢复多线路分发，属于新工作：需要先决定用哪套存储、把 Worker 与脚本迁过来、再处理凭据。不要按上游文档直接执行——那些命令引用的路径在本仓库里不存在。

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

源列表集中在 `app/lib/update/update_config.dart`。元数据按下表从上到下问，下载按镜像顺序试。除了内测通道和 GitHub API，**上次成功的源排到最前**，连不上某个源的手机不会每次都先等它超时；GitHub API 哪怕上次成功也排最后。

| 源 | 用于 | 好处 | 坏处 |
|---|---|---|---|
| 内测通道 `go.dshtui.com/beta/update.json`（带通道令牌的包才有，永远排第一，不参与「上次成功排最前」） | 元数据 + APK（同一令牌） | 不公开；国内可达；发版即生效 | 令牌编在包里，可被提取；没令牌的包用不了 |
| 自有下载站 `dl.dshtui.com`（R2 公共桶，所有包默认都有） | 元数据 `/update.json`（最新公开版，资产带绝对地址）+ APK 和补丁 `/download/<版本>/<文件>`（下载也排第一） | 自己的，完全可控；不占 GitHub 配额；有补丁 | 流量和可达性要自己盯；国内速度看 Cloudflare 线路 |
| `github.com/…/releases/latest/download/update.json` | 元数据 + APK | 不占 API 配额；和 APK 同一个 CDN | 国内常慢或不通 |
| 公共加速镜像（默认 `ghfast.top`、`gh-proxy.com`） | 元数据 + APK | 零成本，国内大多能用 | 第三方：随时失效、换域名（几个月一换很常见），有的整段网络一律 403（`ghfast.top` 2026-11 起从我们机器出去全是 403，仍留在列表里，被拒只多一次请求）；能看到下载者 IP；大文件可能限速；理论上能篡改内容（见下） |
| GitHub API `releases/latest`（最后） | 元数据 | 官方；带 digest，不额外请求校验和 | 匿名 60 次/小时/IP，校园网、运营商 NAT 和代理出口下常常已经 403；回答里没有补丁；国内常超时 |

构建时可以改（`--dart-define`，不用改代码）：

| 变量 | 作用 |
|---|---|
| `DSH_UPDATE_MIRROR=https://…` | 换掉 `dl.dshtui.com`（同样的 `/update.json` + `/download/<版本>/<文件>` 布局）；设成空串就不用自有站，只剩 GitHub 和加速镜像 |
| `DSH_UPDATE_FEED=https://…/update.json` | 只换要问的清单，下载仍先试自有站；空串 = 不问清单 |
| `DSH_UPDATE_MIRRORS=a,b` | 换掉公共加速镜像列表（前缀 + 完整 GitHub 地址）；空串 = 不用 |
| `DSH_BETA_CHANNEL_ORIGIN` | 换内测通道的域名 |

公共镜像要定期确认还活着，失效的直接从列表删。

下载的超时策略：连接 15 秒、30 秒没收到数据算断线；同一个地址断线续传最多再试 2 次，连不上或返回错误就换下一个源。补丁和 APK 用同一套。

## 安全

- **SHA-256**：来自元数据（API 的 `digest`、`update.json` 的 `sha256`，都没有时读 `SHA256SUMS`）。缺校验和的版本 App 不装，只给发布页链接。它防的是传输损坏，以及只篡改 APK 的镜像。
- **签名**才是最后一道防线：镜像如果同时篡改清单和 APK，校验和也能对上，但它签不出我们的签名，系统和 App 的预检都会拒绝覆盖安装。所以发布密钥必须固定且保密，绝不能用一把谁都拿得到的钥匙。
- **降级**：系统拒绝 versionCode 更低的包；App 也只在版本更新时提示。正式包要在升完版本名之后、用 `build-apk.sh` 现打，不要拿早先打好的包去发：手机上若装着更晚打的旧版本名开发包，App 会提示更新，系统却会按降级拒装。

## 出问题时 App 的表现

| 情况 | 表现 |
|---|---|
| 还没有 release、仓库私有、限流、断网 | 「关于」里显示「暂时检查不了」；手动检查弹一条提示，不弹错误对话框；自动检查静默 |
| 新版没有本机 flavor 的 APK | 弹窗说明，按钮改为「打开发布页」 |
| 下载断了 | 保留已下载部分，「重试」接着下 |
| 校验不通过 | 删除文件，换源重下；都不通过就报错 |
| 签名不一致 | 安装前拦下，说明要卸载重装，按钮「打开发布页」 |
| 用户在系统确认框里取消 | 回到「安装包已下载」，可再装 |

## 真机验证清单（尚未在真机上跑过）

- [ ] 首次安装：「安装未知应用」引导页能打开，授权后回到 App 自动继续安装
- [ ] 同签名覆盖：确认后 App 退出，重开后版本号变化、对话和设置还在，运行时按新版本升级
- [ ] 签名不同的包：安装前提示「签名不一致」，不出现系统报错
- [ ] 系统确认框按返回：回到「已取消安装」，可以再装
- [ ] 下载中切到后台、断网再恢复、杀进程后重进：都能续传
- [ ] 国产 ROM（vivo / 小米 / 华为）：自带的安装拦截页、「安全安装」流程不会让 App 卡在「安装中」（App 回到前台 3 秒后会自动放开）
- [ ] 公共镜像实测可用；GitHub 不通时能回落
