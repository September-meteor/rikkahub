# Rikkahub

[English](README.md) | [简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md)

> This is a personal fork of [RikkaHub](https://github.com/rikkahub/rikkahub). 
> The original project is excellent; I merely fixed a few minor bugs, addressed some personal pain points, and added a couple of small features. 
> If you like these changes, feel free to try this fork. If you prefer the native and stable experience, you should stick to the original project.

## Differences from Upstream

### New Features & UX Improvements

#### Workspace

1. **File / Directory Import**:
   - **New: directory import**
     - Recursive `.gitignore` parsing and configurable custom exclusion patterns, with support for a fairly complete `.gitignore` syntax.
     - An import progress UI and an import-settings card.
   - **Same-name file / directory conflict handling**
     - A "Same-name items" option was added to the workspace basic settings. It defaults to "Create copies" and can be switched to "Merge & overwrite".
     - In "Create copies" mode, importing a file/directory whose name already exists creates `name (1).ext` / `name (1)`.
     - "Merge & overwrite" behaves like `rsync --delete`, except files excluded by `.gitignore` or custom exclusion patterns are left untouched.

2. **Directory Sync-back / Export**:
   - **Sync back to the original directory**
     - Two difference-checking modes: Quick (size only) and Full (CRC32 content verification).
     - Supports syncing the root directory as well as subdirectories/files.
     - Base behavior is the same as `rsync --delete`, except files excluded by `.gitignore` or custom exclusion patterns are left untouched.
     - Guided dialogs: Scan (with estimated time) → Preview → Execute.
   - **Directory export**: export a directory to any SAF folder.

3. **File opening is now "best-effort"**
   - Files with no extension or an unknown extension open in the built-in editor by default.
   - Binary or oversized files offer "Open with another app" and "Share" as fallbacks.
   - Explicitly non-text types (audio/video, archives, etc.) are still handed off to system apps.

4. **Filesystem refinements**:
   - **Consistent display**
     - The "System/rootfs" root now overlays virtual mount directories (`/workspace`, `/skills`, `/upload`, `/tool_outputs`) on top of the real disk tree, so the display matches the actual contents.
     - The path bar in the "Files" area now starts at `/workspace` instead of `/`, avoiding confusion.
     - The interactive terminal now builds its `-b` arguments from the same bind-mount configuration as the file browser, also mounting `/upload` and `/tool_outputs`, eliminating drift between the terminal and the browser's mount configuration.
     - The rootfs initialization marker was moved to `/var/lib/rikkahub` to avoid confusion.
   - **Interaction refinements**: the file browser always shows the back button and remembers the scroll position per directory, so going back no longer resets the state.

#### Messages

1. **Chain-of-thought copy**: A button next to the AI message avatar lets you copy the model's chain of thought to the clipboard.

2. **Chain-of-thought translation**:
   - Translation and language-selection buttons were added next to the AI message avatar:
     - One-tap toggle between the original text and the translation; the translation streams in just like the original chain of thought.
     - The language button shows a flag emoji and defaults to the system language.
   - Extended behavior for chains of thought spanning multiple tool calls:
     - A "Reasoning Translate" option was added under Settings → Preferences → Experimental Features.
     - Configurable: display mode (merge into the first card / keep separate cards), send mode (bundled / one by one), the separator used when sending bundled, and translate-button behavior (auto-expand the first card / expand all).

3. **Tool-call optimizations**:
   - Reduced the fixed overhead of `workspace_shell` tool calls: fewer processes are spawned per call.
   - Tool-call titles are now fully displayed instead of being truncated.

4. **Real-time Token price**:
   - A "Real-time Token Price" option was added under Settings → Providers → Advanced Settings of a specific model.
   - Supports configuring weekdays, time slots, price, unit, hint text, and a highlight background color.
   - Once configured, the matching price hint is shown below the chat title bar for the current time slot.

#### Messages – Misc

1. **Changed files below messages**:
   - **Improved change detection**: newly added and updated files are now shown based on actual workspace file changes.
   - **Path display toggle**: switch between file names and full relative paths.
   - A collapse toggle for changed files; file-name and full-relative-path modes share the expanded/collapsed state.
   - Long-pressing a file tag copies the currently displayed path to the clipboard and shows a Toast.

2. **Message info bar**:
   - A "Show Cumulative Token Usage" toggle was added under Settings → Preferences → UI Preferences:
     - When enabled, input/output/cached tokens are accumulated and displayed following the official console's accounting.
     - Every time a tool returns a result, the info bar updates this message's accumulated tokens in real time; after the message finishes, it shows the conversation's cumulative token count.
   - **Cache-hit ratio is now shown**: ↑ xxx Tokens (xxx cached xx%)
   - Token generation speed is now computed from pure generation time, excluding tool-call execution time.

3. **Conversation interaction refinements**
   - **Per-assistant folder memory and auto-archiving of new conversations**:
     - Each assistant remembers the folder it last used and restores it after you leave and come back, or when you switch assistants.
     - New conversations default to the currently selected folder instead of landing in Uncategorized.
   - **Conversation list scroll position**:
     - The scroll position of the drawer's conversation list is now remembered per "assistant + category", so switching assistants or categories resumes each view where you left off.
     - When the current conversation is out of view, a "Back to current conversation" icon appears in the top-right corner of the list; tapping it scrolls back to that conversation.

#### Global

1. **Image compression**:
   - Oversized images are automatically compressed before being sent to the AI.
   - Can be toggled and its maximum dimension adjusted under Preferences → Experimental Features → Image Compression (default 2048 px).

2. **Update improvements**:
   - The update card was narrowed to a single line and expands on tap to show details.
   - An option to pause updates permanently was added to the update display settings.

3. **Unified text input implementation**:
   - Input fields across messages, settings, editors, etc. now use a shared component whose base behavior aligns with the message input.
   - Consistent cursor stability, no dropped characters, intermediate states allowed, and no conflicting persistence.
   - Trade-off: real-time formatting and syntax highlighting were removed from JSON/script editors in favor of input stability; the platform's secure input fields are a special case and are excluded.

### Bug Fixes

1. Message-related
   - **Markdown / HTML nested-list rendering errors**:
     - Fixed child ordered lists being incorrectly rendered as outer ordered lists.
     - Fixed child lists being incorrectly moved to the end when text and sub-lists are interleaved within a list item.
     - Improved the vertical layout of multi-paragraph/block-level elements inside list items so content is no longer squeezed horizontally or overflows the screen.
   - Fixed an issue where, after collapsing the first long chain-of-thought card on some model messages, only the ending part remained.

2. Info display
   - Fixed the UI occasionally showing non-existent temporary files as changed files when an Agent modifies files via `workspace_shell`.
   - Fixed tokens always showing 0 during streaming through OpenAI-compatible relays.

3. Fixed garbled options on certain pages caused by R8 obfuscation renaming class/property names in specific packages.

4. Extensions (Workspace / Skill)
   - Fixed `/tmp` and `/var/tmp` always disappearing; they are now cleared in content only.
   - Fixed malformed Skills disappearing from the UI; they now show a warning and can be repaired.

### Engineering & Localization

1. Switched the Gradle software repositories to Aliyun mirrors to avoid download timeouts.

2. Added app/proguard-rules.pro with `-keepnames` rules for all classes and members under `me.rerere.ai.provider` and `me.rerere.ai.core`, preserving class and member names so obfuscation does not break functionality.

3. Removed the leftover root-level package.json and bun.lock from the i18n module.

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

> If you have your own ideas, refer to the [Building](BUILDING.en.md) section below to compile it yourself.

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
cd ~/rikkahub && printf "storeFile=$HOME/.android/debug.keystore\nstorePassword=android\nkeyAlias=androiddebugkey\nkeyPassword=android\n" >> local.properties
./gradlew assembleRelease
```

The compiled APKs are located in `~/rikkahub/app/build/outputs/apk/release/`: 
  - `app-arm64-v8a-release.apk`: For 64-bit ARM processors (the vast majority of phones post-2016).
  - `app-x86_64-release.apk`: For 64-bit x86 processors (mainly for Android emulators or rare Intel/AMD devices).
  - `app-universal-release.apk`: Universal package containing code for the above architectures.

## License

This project is a Derivative Work of [RikkaHub](https://github.com/rikkahub/rikkahub).

**Original Project License:**
> The original RikkaHub (developed by [re-ovo](https://github.com/re-ovo) and RikkaHub contributors) is licensed under the [GNU Affero General Public License v3.0 (AGPL-3.0)](https://www.gnu.org/licenses/agpl-3.0.html).

**Modifications in this Fork:**
> Copyright © 2026 September-meteor
> The newly added and modified code in this fork is also licensed under **AGPL-3.0**.

In accordance with the AGPL-3.0 requirements for "Modified Versions", all modifications made to the original code in this project are explicitly detailed in the **[Differences from Upstream](#differences-from-upstream)** section above and in the Git commit history.

For the full text of the AGPL-3.0 license, please refer to the [`LICENSE`](LICENSE) file in the root directory.

## Acknowledgments

Thanks to [@re-ovo](https://github.com/re-ovo) and the RikkaHub team for developing such an excellent client!

Upstream updates are incredibly fast and focused on core evolution. This fork is purely a set of personal patches to fix some UX bugs and satisfy minor personal needs (like reasoning translation and image compression). 99.9% of the code and architectural design credit goes to the original authors.