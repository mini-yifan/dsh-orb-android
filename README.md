<p align="center">
  <b>简体中文</b> · <a href="README.en.md">English</a>
</p>

<h1 align="center">DSH Orb</h1>

<p align="center">
  把 DeepSeek Harness 装进口袋：Android App + 桌面悬浮球插件 + 两端互联。
</p>

<p align="center">
  <a href="https://www.dshorb.top/"><img alt="官网" src="https://img.shields.io/badge/%E5%AE%98%E7%BD%91-www.dshorb.top-4B67B4?style=flat-square&labelColor=2B3245"></a>
  <a href="https://github.com/mini-yifan/dsh-orb-android/releases/latest"><img alt="Android 8+" src="https://img.shields.io/badge/Android-8%2B-4B67B4?style=flat-square&logo=android&logoColor=white&labelColor=2B3245"></a>
  <a href="LICENSE"><img alt="许可证 AGPL-3.0" src="https://img.shields.io/badge/%E8%AE%B8%E5%8F%AF%E8%AF%81-AGPL--3.0-4B67B4?style=flat-square&labelColor=2B3245"></a>
</p>

<br>

## 这是什么

DSH Orb 是一个围绕 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的小生态，由三部分组成：

| 部分 | 说明 |
| --- | --- |
| **手机 App**（本仓库） | Android 上直接运行 DSH 引擎，也能连接电脑或服务器上的 DSH；带悬浮球、语音、书签和后台模式 |
| **桌面悬浮球插件** | 给 DeepSeek Harness 桌面端的 `dsh-orb` 插件，在插件页输入 `dsh-orb` 即可安装（需要 dsh ≥ 0.1.7-rc.2） |
| **互联** | 手机和电脑连到同一个 `dshorb.top` 账号，互看设备、工作区和对话，可以把任务派到另一台设备上执行 |

本仓库是 Android App 的源码。桌面插件是独立项目，安装方式见下。

<br>

## 下载与安装

**推荐从官网下载**：[www.dshorb.top](https://www.dshorb.top/) 首页的「下载安装包」直接给出最新的 APK，和 App 内「检查更新」读取同一份清单，发版后自动跟进。

备用入口是 [GitHub Releases](https://github.com/mini-yifan/dsh-orb-android/releases/latest)，下载 `dsh-orb-<版本>-arm64.apk`。

- 当前**只有 Android 版本**：Android 8.0（API 26）及以上，官方发布只提供 arm64 包。iOS 和鸿蒙版本在计划中。
- 官网页面列出安装包的 SHA-256；应用内更新下载完会与清单核对，安装由系统 PackageInstaller 完成。
- 如果手机上装过签名不同的早期测试包，不能直接覆盖安装，需要先卸载旧版；卸载会清空对话、Linux 环境和设置。
- 首次在本机运行要先下载运行环境，按 App 提示预留空间；只连电脑或服务器上的 DSH 可以不装。

### 桌面插件

在 DeepSeek Harness 桌面端的**插件页输入 `dsh-orb`**，按提示安装即可。要求 dsh ≥ 0.1.7-rc.2，支持 macOS（Apple Silicon / Intel）与 Windows x64；装好后桌面会出现悬浮球，用来唤起对话、语音和快捷键。插件源码在 [dsh-orb-cordis](https://github.com/mini-yifan/dsh-orb-cordis)。

### 应用内更新

App 启动时每天检查一次，也可以在设置里手动检查。更新清单由本项目自托管；安装包优先从 GitHub Releases 经加速镜像下载，镜像不可用时回退到自托管源，全部下载完成并校验 SHA-256 后才交给系统安装。

<br>

## 可以做什么

- **本机运行**：在 App 内安装 Ubuntu、Node.js 与 DSH 运行环境，无需 Root 或另装 Termux。
- **互联与远程**：通过 `dshorb.top` 互联多台设备，或用 SSH / [DSHarness Remote](https://github.com/liguobao/ds-harness-remote) 连接电脑、服务器上的 DSH。
- **悬浮球**：长按说话、单击打字，长条常驻显示状态，书签镜像桌面插件的快捷入口；可以跟随到其他 App 上方。
- **后台模式**：派到手机上的任务在后台执行，完成后用通知把结果带回来。
- **手机操控**：在你开启权限后，让本机引擎读取屏幕、点按和输入；确认方式三档可选，随时可以停止。
- **聊天与工具**：切换模型，发送图片和文件，使用语音输入，处理工具审批和提问；生成中可以插话或排队；支持聊天界面与网页模式切换。
- **插件界面**：使用适配 DSH Orb 的原生工具卡、表单和面板；仓库提供 SDK、教程和示例。
- **分享与外观**：分享、导出和回放对话，调整主题、图标和桌宠。

功能随主机和连接方式不同。DSHarness Remote 当前只支持图片附件，不能在 App 内打开网页版，插件管理限于开关，且不支持 DS Bot 和对话日志导出。详见[使用说明](docs/guide/README.md)。

<br>

## 数据与权限

模型请求会发送给你配置的模型提供方。开启手机操控后，任务需要的屏幕文字可能发送给模型，支持看图的模型也可能收到截图；请先阅读 App 的权限说明。

「发送匿名使用统计」默认开启，可在首次引导或「设置 → 隐私与分享」关闭。采集范围和关闭后的处理见[常见问题](docs/guide/zh/11-faq.md)。分享内容前请检查预览；收到链接或文件的人可以保存其中的内容。

## 风险提示

DSH Orb 是社区开发的非官方项目，与 DeepSeek 无关联，也不代表 DeepSeek。

- **AI 并非万无一失**：回答、代码和操作可能出错，也可能被网页、消息或文件中的恶意指令误导。请核验重要结果；医疗、法律、投资等高风险决策不应仅依赖 AI 输出。
- **工具会产生真实影响**：本机或远程主机上的命令、文件修改及手机操控可能导致数据丢失、隐私泄露、账号、资金或设备损失。免确认、放宽应用规则或解除限制会减少保护；建议保留默认保护，只授予必要权限。
- **第三方服务与费用**：模型、远程主机和插件等按各自条款运行；相关数据处理、服务可用性和费用取决于你选择的服务与配置。本机运行不等于模型离线。
- **无担保与责任范围**：在适用法律允许的范围内，本软件按「现状」提供，不保证准确性、持续可用性或满足特定用途；相关无担保及责任限制见 [LICENSE](LICENSE) 第 15–17 条。

<br>

## 从源码构建

以下流程面向 **Linux x86_64**。工具链脚本默认安装 JDK 17、Flutter 3.47.7 和 Android SDK/NDK；App 要求 Dart `^3.13.5`。准备 Bash、Git、curl、tar、unzip、xz、Python 3、zstd、file 和常见 GNU 命令；重新解析 Ubuntu 软件包锁时还需要 gpgv。

```bash
git clone https://github.com/mini-yifan/dsh-orb-android.git
cd dsh-orb-android

bash tooling/setup-toolchain.sh
source tooling/env.sh
flutter --version
java -version

bash runtime/build-runtime.sh arm64-v8a
DSH_BETA_TOKEN_FILE= bash tooling/build-apk.sh -f arm64 --keep-daemon
```

产物为 `app/build/app/outputs/flutter-apk/app-arm64-release.apk`。运行时构建使用仓库固定的版本与校验信息，并需要联网下载依赖。`--keep-daemon` 保留 Gradle daemon，适合同时开发其他 Android 项目的环境。

构建 x86_64 包时，先生成对应运行时：

```bash
bash runtime/build-runtime.sh x86_64
DSH_BETA_TOKEN_FILE= bash tooling/build-apk.sh -f x64 --keep-daemon
```

未配置 `app/android/key.properties` 时，本地 release 构建使用 debug 签名，不能覆盖不同签名的官方安装包。发布签名与密钥文件不要提交到仓库。

只查看 APK 构建命令，可运行：

```bash
DSH_BETA_TOKEN_FILE= bash tooling/build-apk.sh --dry-run -f all
```

发版约定、签名和更新清单见 [docs/release.md](docs/release.md)。

## 目录

| 路径 | 内容 |
| --- | --- |
| `app/` | Flutter 界面与 Android Kotlin 平台实现 |
| `packages/dsh_client/` | DSH Web API 的 Dart 客户端 |
| `packages/dsh_remote/` | DSH Remote 的 Dart 客户端 |
| `runtime/` | Linux 运行时打包、引擎补丁与 App 桥接 |
| `sdk/plugin-kit/` | 原生插件作者工具包；在仓库中使用，未发布到 npm |
| `examples/plugins/` | 插件示例 |
| `docs/guide/`、`docs/plugins/` | 用户指南与插件开发文档 |
| `tooling/` | 工具链、APK 构建、发布与诊断脚本 |
| `site/share/` | 对话分享页的浏览器端 |

## 参与贡献

问题反馈和改进建议请提交到本仓库的 [Issues](https://github.com/mini-yifan/dsh-orb-android/issues)。本仓库**暂不接受外部 Pull Request**，细节见 [CONTRIBUTING.md](CONTRIBUTING.md)。插件作者可以从[插件教程](docs/plugins/README.md)和 [SDK](sdk/plugin-kit/README.md) 开始。

安全问题请按 [SECURITY.md](SECURITY.md) 私密报告，不要在公开 Issue 中提交漏洞细节或凭据。

## 许可证与致谢

本仓库以 [AGPL-3.0](LICENSE) 发布；`sdk/` 和 `examples/` 为 **MIT**。第三方代码和素材以各自声明为准，具体来源和条款见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)：DSH Orb 运行和连接的就是它。
- [DSH Go](https://github.com/ccch1mneyyy/dsh-go)（作者 [ccch1mneyyy](https://github.com/ccch1mneyyy)）：本 App 的上游项目，AGPL-3.0 衍生自它。上游的名称、标识和 Deepy、鲸娘素材不在 AGPL 授权范围内，见 [ASSETS.md](ASSETS.md)。
- [DSH-TUI](https://github.com/ccch1mneyyy/dsh-TUI)：吉祥物 Deepy 小鲸鱼和鲸娘来自这里，随 App 分发已获授权。
- [ds-harness-remote](https://github.com/liguobao/ds-harness-remote)：远程连接参考了它的项目和服务器，感谢作者李国宝。

DSH Orb 使用自己的名称；App 当前的启动图标与桌宠形象来自上游 DSH Go / DSH-TUI 的素材，不在 AGPL 授权范围内，授权条件见 [ASSETS.md](ASSETS.md)。

<p align="center"><sub>DSH Orb 是社区开发的非官方客户端，与 DeepSeek 没有关联，也不代表 DeepSeek。DeepSeek 名称与标识归其所有者所有。</sub></p>
