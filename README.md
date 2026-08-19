# Rikkahub

[English](README.md) | [简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md)

> This is a personal fork of [RikkaHub](https://github.com/rikkahub/rikkahub). 
> The original project is excellent; I merely fixed a few minor bugs, addressed some personal pain points, and added a couple of small features. 
> If you like these changes, feel free to try this fork. If you prefer the native and stable experience, you should stick to the original project.

## Differences from Upstream

### Bug Fixes
- **Markdown / HTML Nested List Rendering Issues**:
  - Fixed an issue where child ordered lists were incorrectly rendered as outer ordered lists.
  - Fixed an issue where child lists were incorrectly appended to the end when interleaved with text within a list item.
  - Optimized the vertical layout of multi-paragraph/block-level elements within list items to prevent horizontal squeezing or overflow.
- **Garbled Text in Release Builds**: Fixed an issue where class/property names in specific packages were obfuscated by R8, causing garbled text in options on certain pages.
- **Workspace Temporary File Display Anomaly**: Fixed an issue where the UI incorrectly displayed non-existent temporary files as changed files when the Agent modified files using `workspace_shell`.

### New Features
- **Workspace Directory Import & Smart Filtering**:
  - Supports one-click import of entire folders into the workspace, complete with an import progress UI.
  - Built-in recursive `.gitignore` parsing (supports negation rules and anchoring), along with support for custom exclusion pattern configurations.
- **Workspace Directory Sync Back**:
  - Supports syncing workspace files back to the original SAF (Storage Access Framework) directory.
  - Provides two difference checking modes: "Quick (size comparison only)" and "Full (CRC32 content verification)".
  - Automatically generates an initial snapshot after import to optimize subsequent comparisons. The UI now includes a state-machine guided popup: Scan → Preview → Execute.
- **Enhanced Workspace Change Display & Interaction**:
  - Enhanced `workspace_shell` change detection; the list of changed files is automatically appended to the message metadata.
  - Added a path display toggle switch, supporting seamless switching between "filename only" and "full relative path" (sharing the expanded/collapsed state).
  - **Smart Trimming**: If all changed files belong to the same project directory, the project name prefix is automatically omitted to keep the interface clean.
  - **UI Adaptation**: In full path mode, items are displayed in a single column with horizontal scrolling support to prevent long paths from wrapping. File tags support long-press to copy the current path to the clipboard, accompanied by a Toast notification.
- **Image Compression**: Automatically compresses oversized images before sending them to the AI. Can be toggled and configured (max edge length, default 2048px) in `Preferences → Experimental Features → Image Compression` (chat preview remains in original resolution).
- **Chain of Thought (CoT) Enhancements**:
  - **One-Click Copy**: Added a one-click button next to the AI message avatar to copy the model's Chain of Thought.
  - **Streaming Translation**: Added translation and language selection buttons next to the AI message avatar.
    - Supports one-click toggling between original text and translated text.
    - Translated text is generated in a streaming manner, just like the original CoT.
    - The language button displays as a flag emoji, defaulting to the system language.

## Download & Installation

[![Release](https://img.shields.io/github/v/release/September-meteor/rikkahub?label=Latest)](https://github.com/September-meteor/rikkahub/releases/latest)

- You can also find historical APKs on the [Releases](https://github.com/September-meteor/rikkahub/releases) page.
- System Requirement: Android 8.0 (API 26) and above.

### ⚠️ Read Before Installing

1. **Signature Conflict**: This fork is signed with my personal key, which differs from the official RikkaHub signature. **It cannot be installed over the official version.**
   - You must uninstall the official version before installing this one;
   - Or use workspace apps (like Island/Shelter) to dual-open;
   - Or modify the `applicationId` and recompile (which may cause data migration and `google-services.json` issues).
2. **Data Migration**: Since the release package name matches the original, you can import backups exported from the official app. **Please export your local data backup in the official app's settings before uninstalling**, and import it into this fork afterward.
3. **Use at Your Own Risk**: This is a personal DIY version and may contain undiscovered bugs. Please be cautious when using it in production environments or with critical data.

### Update Frequency

- Upstream updates are extremely frequent. I **do not guarantee** syncing every single upstream release in real-time.
- However, I will try my best to include upstream updates when I make my own changes.

> If you have your own ideas, refer to the [Building](#building) section below to compile it yourself.

## Building

If you prefer GUIs, you can try Android Studio. This section mainly introduces the pure CLI building method.

Environment: GNU/Linux (Debian-based) or similar terminal environments (like WSL on Windows).

### Requirements

1. Basic Runtime
  - **JDK**: `openjdk-17-jdk`
  - **Node.js & pnpm**: Node 22 + pnpm 11

2. App Compilation
- **Android SDK**:
  - `platform-tools`
  - `build-tools;37.0.0`
  - `platforms;android-37`
- **Gradle Sync & Config**: Ensure a configured `google-services.json` exists in the `app/` directory.

3. Frontend Web Stack
- **RTV Stack**: React Router v7 + Tailwind Oxide + Vite
- **Google Material Color Utilities** library

> Due to length constraints, only the build commands are listed here.
> For environment setup (Requirements 1 & 2), please refer to [BUILDING.md](./BUILDING.md).

### I. Prepare Dependencies & Clone

1. Clone the repository with submodules (Material Color Utilities):
```bash
git clone --recursive https://github.com/September-meteor/rikkahub.git
```

2. Install frontend dependencies:
```bash
cd ~/rikkahub/web-ui
pnpm install
```

### II. Start Building

Configuring a release signing key is tedious, so we will borrow the Debug keystore here. **This is strictly for personal use**; otherwise, a proper release signature should be configured.
```bash
cd ~/rikkahub
cd ~/rikkahub && printf "storeFile=$HOME/.android/debug.keystore\nstorePassword=android\nkeyAlias=androiddebugkey\nkeyPassword=android\n" >> local.properties
./gradlew assembleRelease
```

The compiled APKs are located in `~/rikkahub/app/build/outputs/apk/release/`.
- `app-arm64-v8a-release.apk`: For 64-bit ARM processors (the vast majority of phones post-2016).
- `app-x86_64-release.apk`: For 64-bit x86 processors (mainly for Android emulators or rare Intel/AMD devices).
- `app-universal-release.apk`: Universal package containing code for the above architectures.

## License

This project is a Derivative Work of [RikkaHub](https://github.com/rikkahub/rikkahub).

**Original Project License:**
> The original RikkaHub (developed by [rerere](https://github.com/rerere) and RikkaHub contributors) is licensed under the [GNU Affero General Public License v3.0 (AGPL-3.0)](https://www.gnu.org/licenses/agpl-3.0.html).

**Modifications in this Fork:**
> Copyright © 2026 September-meteor
> The newly added and modified code in this fork is also licensed under **AGPL-3.0**.

In accordance with the AGPL-3.0 requirements for "Modified Versions", all modifications made to the original code in this project are explicitly detailed in the **[Differences from Upstream](#differences-from-upstream)** section above and in the Git commit history.

For the full text of the AGPL-3.0 license, please refer to the [`LICENSE`](LICENSE) file in the root directory.

## Acknowledgments

Thanks to [@rerere](https://github.com/rerere) and the RikkaHub team for developing such an excellent client!

Upstream updates are incredibly fast and focused on core evolution. This fork is purely a set of personal patches to fix some UX bugs and satisfy minor personal needs (like reasoning translation and image compression). 99.9% of the code and architectural design credit goes to the original authors.