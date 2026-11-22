<p align="center">
  <a href="README.md">简体中文</a> · <b>English</b>
</p>

<h1 align="center">DSH Orb</h1>

<p align="center">
  DeepSeek Harness in your pocket: an Android app, a desktop floating orb, and a link between the two.
</p>

<p align="center">
  <a href="https://www.dshorb.top/"><img alt="Website" src="https://img.shields.io/badge/website-www.dshorb.top-4B67B4?style=flat-square&labelColor=2B3245"></a>
  <a href="https://github.com/mini-yifan/dsh-orb-android/releases/latest"><img alt="Android 8+" src="https://img.shields.io/badge/Android-8%2B-4B67B4?style=flat-square&logo=android&logoColor=white&labelColor=2B3245"></a>
  <a href="LICENSE"><img alt="License AGPL-3.0" src="https://img.shields.io/badge/license-AGPL--3.0-4B67B4?style=flat-square&labelColor=2B3245"></a>
</p>

<br>

## What this is

DSH Orb is a small ecosystem around [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness), made of three parts:

| Part | What it does |
| --- | --- |
| **Android app** (this repository) | Runs the DSH engine on the phone, or connects to DSH on a computer or server; adds a floating orb, voice, bookmarks and a background mode |
| **Desktop orb plugin** | A `dsh-orb` plugin for DeepSeek Harness desktop; type `dsh-orb` on the plugin page to install it (requires dsh ≥ 0.1.7-rc.2) |
| **Interconnect** | Phone and computer join the same `dshorb.top` account, see each other's devices, workspaces and chats, and can dispatch a task to the other device |

This repository holds the Android app. The desktop plugin is a separate project; see below for how to install it.

<br>

## Download and install

**Get it from the website**: the "download" button on [www.dshorb.top](https://www.dshorb.top/) serves the latest APK directly. It reads the same manifest as the in-app update check, so it follows every release.

Alternatively, use [GitHub Releases](https://github.com/mini-yifan/dsh-orb-android/releases/latest) and download `dsh-orb-<version>-arm64.apk`.

- **Android only for now**: Android 8.0 (API 26) or later, arm64 builds only. iOS and HarmonyOS versions are planned.
- The website lists the APK's SHA-256; the in-app updater verifies the download against the manifest before installation goes through Android's PackageInstaller.
- If an earlier test build with a different signature is installed, it cannot be updated in place: uninstall it first, which erases chats, the Linux environment and settings.
- Running DSH on the phone needs a one-time runtime download; keep the free space the app asks for. If you only connect to a remote host, you can skip it.

### Desktop plugin

On DeepSeek Harness desktop, open the **plugin page and type `dsh-orb`**, then follow the prompt. It requires dsh ≥ 0.1.7-rc.2 and runs on macOS (Apple Silicon / Intel) and Windows x64; the desktop orb appears once installed and gives you quick chat, voice and shortcuts. The plugin source lives in [dsh-orb-cordis](https://github.com/mini-yifan/dsh-orb-cordis).

### In-app updates

The app checks once a day on startup, and you can check manually in Settings. The manifest is self-hosted; the APK is downloaded from GitHub Releases through accelerator mirrors first, falls back to the self-hosted copy when every mirror fails, and is only handed to the system installer after SHA-256 verification.

<br>

## What it can do

- **Run locally**: install Ubuntu, Node.js and the DSH runtime inside the app — no root and no separate Termux.
- **Interconnect and remote**: link several devices through `dshorb.top`, or use SSH / [DSHarness Remote](https://github.com/liguobao/ds-harness-remote) to reach DSH on a computer or server.
- **Floating orb**: hold to talk, tap to type, keep a status strip on screen, and mirror desktop-plugin shortcuts as bookmarks; it can follow you above other apps.
- **Background mode**: tasks dispatched to the phone run in the background and report back through notifications.
- **Phone control**: with permissions you grant, the local engine can read the screen, tap and type; three confirmation modes, and you can stop it at any time.
- **Chat and tools**: switch models, send images and files, use voice input, handle tool approvals and questions; steer or queue messages while a reply is being generated; switch between the native chat and web mode.
- **Plugin UI**: native tool cards, forms and panels from plugins adapted for DSH Orb; the repository ships an SDK, guides and examples.
- **Sharing and appearance**: share, export and replay chats; adjust themes, icons and pets.

Features depend on the host and connection type. DSHarness Remote supports image attachments only, cannot open Web mode inside the app, limits plugin management to on/off, and does not support DS Bot or chat-log export. See the [user guide](docs/guide/README.md).

<br>

## Data and permissions

Model requests go to the provider you configure. With phone control on, screen text needed by a task may be sent to the model, and vision-capable models may receive screenshots; read the app's permission notes first.

Anonymous usage statistics are on by default and can be turned off during onboarding or under Settings → Privacy & sharing. See the [FAQ](docs/guide/en/11-faq.md) for what is collected and what happens when it is off. Preview anything you share: whoever receives a link or file can save its contents.

## Risks

DSH Orb is an unofficial community project. It is not affiliated with, endorsed by, or a representative of DeepSeek.

- **AI is not infallible**: answers, code and actions can be wrong, and can be misled by malicious instructions in pages, messages or files. Verify important results; do not rely on AI output alone for medical, legal or financial decisions.
- **Tools have real effects**: commands, file changes and phone control on a local or remote host can cause data loss, privacy leaks, or loss of accounts, money or devices. Turning off confirmations, loosening app rules or lifting limits removes protection; keep the defaults and grant only the permissions you need.
- **Third-party services and cost**: models, remote hosts and plugins run under their own terms; data handling, availability and cost depend on what you choose. Running locally does not mean the model is offline.
- **No warranty**: to the extent permitted by law the software is provided "as is", without warranty of accuracy, availability or fitness for a particular purpose; see sections 15–17 of [LICENSE](LICENSE).

<br>

## Build from source

The flow below targets **Linux x86_64**. The toolchain script installs JDK 17, Flutter 3.47.7 and the Android SDK/NDK; the app requires Dart `^3.13.5`. You need Bash, Git, curl, tar, unzip, xz, Python 3, zstd, file and the usual GNU tools; re-resolving the Ubuntu package lock also needs gpgv.

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

The output is `app/build/app/outputs/flutter-apk/app-arm64-release.apk`. The runtime build uses the versions and checksums pinned in the repository and needs network access for dependencies. `--keep-daemon` keeps the Gradle daemon around, which suits environments that also build other Android projects.

To build x86_64, generate the matching runtime first:

```bash
bash runtime/build-runtime.sh x86_64
DSH_BETA_TOKEN_FILE= bash tooling/build-apk.sh -f x64 --keep-daemon
```

Without `app/android/key.properties`, a local release build falls back to the debug key and cannot overwrite an official APK with a different signature. Never commit release keys or signing files.

To print the APK build commands without running them:

```bash
DSH_BETA_TOKEN_FILE= bash tooling/build-apk.sh --dry-run -f all
```

Release conventions, signing and the update manifest are documented in [docs/release.md](docs/release.md).

## Layout

| Path | Contents |
| --- | --- |
| `app/` | Flutter UI and the Android Kotlin platform layer |
| `packages/dsh_client/` | Pure Dart client for the DSH Web API |
| `packages/dsh_remote/` | Pure Dart client for DSH Remote |
| `runtime/` | Linux runtime packaging, engine patches and the app bridge |
| `sdk/plugin-kit/` | Native plugin author kit; used in-repo, not published to npm |
| `examples/plugins/` | Example plugins |
| `docs/guide/`, `docs/plugins/` | User guide and plugin development docs |
| `tooling/` | Toolchain, APK build, release and diagnostics scripts |
| `site/share/` | Browser side of the chat share page |

## Contributing

Report bugs and suggest improvements in this repository's [Issues](https://github.com/mini-yifan/dsh-orb-android/issues). This repository **does not accept external pull requests**; see [CONTRIBUTING.en.md](CONTRIBUTING.en.md). Plugin authors can start with the [plugin guides](docs/plugins/en/README.md) and the [SDK](sdk/plugin-kit/README.en.md).

Report security problems privately as described in [SECURITY.en.md](SECURITY.en.md), never in a public issue.

## License and credits

This repository is released under [AGPL-3.0](LICENSE); `sdk/` and `examples/` are **MIT**. Third-party code and assets keep their own terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness): what DSH Orb runs and connects to.
- [DSH Go](https://github.com/ccch1mneyyy/dsh-go) by [ccch1mneyyy](https://github.com/ccch1mneyyy): the upstream project this app derives from under AGPL-3.0. Its name, logo, and the Deepy and Whale girl mascots are not covered by the AGPL; see [ASSETS.md](ASSETS.md).
- [DSH-TUI](https://github.com/ccch1mneyyy/dsh-TUI): where the Deepy and Whale girl mascots come from; shipping them in the app is authorized.
- [ds-harness-remote](https://github.com/liguobao/ds-harness-remote): the remote connection builds on its project and servers, with thanks to its author.

DSH Orb uses its own name; the current launcher icon and pet art come from the upstream DSH Go / DSH-TUI material, which is not covered by the AGPL — see [ASSETS.md](ASSETS.md) for its terms.

<p align="center"><sub>DSH Orb is an unofficial client made by the community. It is not affiliated with or endorsed by DeepSeek. DeepSeek names and marks belong to their owners.</sub></p>
