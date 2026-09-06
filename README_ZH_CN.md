# Rikkahub

[English](README.md) | [简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md)

> 这是 [rikkahub](https://github.com/rikkahub/rikkahub) 的个人 fork。
> 原项目非常出色，我只是在此基础上修改了一点小 bug 或对我个人而言的痛点，增加了一点新功能。
> 如果改动里有你心仪的点，你可以尝试此项目；如果你追求原生和稳定，你应该选择原项目。

## 和上游的区别

### 新增功能与体验优化

#### 工作区

1. **文件/目录导入功能**：
   - **新增目录导入功能**
     - .gitignore 递归解析功能与自定义排除模式配置，支持较为完善的 .gitignore 语法
     - 添加导入进度显示 UI 及导入设置卡片
   - **同名文件/目录冲突处理**
     - 工作区基本设置新增「同名内容处理」开关，默认行为为「创建副本」，可改为「覆盖合并」
     - 「创建副本」模式下，导入时遇同名文件/目录会创建 `name (1).ext` / `name (1)`
     - 「覆盖合并」行为同 `rsync --delete`，但忽略 .gitignore 与自定义排除模式排除文件

2. **目录同步回传/导出功能**：
   - **目录同步回传**
     - 提供快速（仅尺寸）与完整（CRC32 内容校验）两种差异检查模式。
     - 支持根目录和子目录/文件同步。
     - 基础行为同 `rsync --delete`，但忽略 .gitignore 与自定义排除模式排除文件。
     - 扫描（预估时间） → 预览 → 执行状态弹窗。
   - **目录导出**：支持将目录导出到任意 SAF 文件夹。

3. **文件打开改为「尽力而为」模式**
   - 无扩展名或未知扩展名的文件默认在内置编辑器中打开。
   - 检测到二进制或超大文件时提供「用其它应用打开」与「分享」兜底。
   - 明确非文本类型（音视频、压缩包等）仍交给系统应用。

4. **文件系统优化**：
   - **显示统一**
     - 「系统/rootfs」根目录在真实磁盘目录之上叠加虚拟挂载目录：/workspace、/skills、/upload、/tool_outputs，避免显示与实际内容不符。
     - 「文件」区路径栏根目录由 / 改为 /workspace，避免误解。
     - 交互终端改用与文件浏览同一份 bind mount 配置生成 -b 参数，补挂 /upload、/tool_outputs，消除终端与浏览挂载配置漂移。
     - rootfs 初始化标记迁移至 /var/lib/rikkahub，避免误解。
   - **操作优化**：文件浏览始终显示返回按钮；并按目录保留滚动位置，返回不再重置状态

#### 消息

1. **思维链复制功能**：AI 消息头像旁新增复制模型思维链按钮，支持复制到剪切板。

2. **思维链翻译功能**：
   - AI 消息头像旁新增翻译与语言选择按钮：
     - 支持一键原文/译文切换，译文同原思维链流式生成。
     - 语言按钮以国旗 emoji 显示，默认跟随系统语言。
   - 对于多工具调用思维链的扩展优化：
     - 「设置 → 偏好设置 → 实验性功能」新增「思维链翻译」配置
     - 可配置：显示方式（并入第一条/按卡片显示）、发送方式（打包/逐条）、打包使用的分隔符、翻译按钮行为（自动展开第一条/所有）。

3. **工具调用优化**：
   - 降低 workspace_shell 工具调用的固定开销：减少每次工具调用启用的进程数。
   - 工具调用标题完整显示，不再截断。

4. **实时 Token 价格**：
   - 「设置 → 提供商 → 具体模型高级设置」中新增「实时 Token 价格配置」
   - 支持配置星期、时段、价格、单位、提示文字、高亮背景色。
   - 配置完成后在聊天标题栏下方按当前时段展示对应价格提示。

#### 消息-杂项

1. **消息底部变更文件**：
   - **完善变更文件检测**：根据工作区实际文件变动展示新增和更新的文件。
   - **路径显示切换开关**：支持在文件名与完整相对路径间切换。
   - 变更文件折叠开关，文件名与完整相对路径两种模式共享展开/折叠状态。
   - 文件标签支持长按复制当前显示的路径到剪贴板，并增加 Toast 提示。

2. **消息底部信息栏**：
   - 「设置 → 偏好设置 → 界面偏好设置」新增「显示累计 Token 消耗」开关
     - 开启后按官方控制台口径，累加显示输入/输出/缓存 Token。
     - 工具每调用返回一次，消息底部实时更新本次消息累计 Token 数；消息结束后显示本次对话累计 Token 数。
   - **缓存命中显示增加占比**：↑ xxx Tokens（xxx cached xx%）
   - Token 生成速度统计改为按纯生成耗时计算，排除工具调用执行时间。

3. **会话操作优化**
   - **会话文件夹记忆与新对话自动归档**：
     - 每个助手记住自己最后选中的文件夹，退出重进或切换助手后自动恢复。
     - 新建对话默认进入当前选中的文件夹，不再默认落在未分类。
   - **会话列表滚动位置**：
     - 抽屉会话列表的滚动位置改为按「助手 + 分类」分别记忆，切换助手或分类后从各自上次停留的位置继续。
     - 当前会话不在可视区域内时，列表右上角展示「回到当前会话」图标，点击后滚回该会话。

#### 全局

1. **图片压缩**：
   - 发送图片给 AI 前自动压缩过大尺寸图片。
   - 可在「偏好设置 → 实验性功能 → 图片压缩」中开关及调整最大边长，默认 2048px。

2. **更新优化**：
   - 更新卡片收窄为一行，点击展开详情。
   - 显示更新中新增永久暂停更新选项。

3. **统一文本输入框实现**：
   - 消息、配置、编辑器等输入框接入统一组件，基础行为向消息输入靠齐 
   - 实现一致的光标稳定、不丢字、允许中间态、持久化不互相冲突。
   - 取舍：JSON/脚本编辑为输入稳定移除实时格式化与语法高亮；官方安全输入框情况特殊除外。

### bug 修复

1. 消息相关
   - **Markdown / HTML 嵌套列表渲染错误**：
     - 修复了子有序列表被错误地当成外层有序列表渲染的问题。
     - 修复了列表项内文本与子列表穿插时，子列表被错误堆放到结尾的问题。
     - 优化了列表项内多段落/块级元素的垂直排版，避免内容横向挤压或超出屏幕。
   - 修复了一些模型消息的第一个长思维链卡片收起后只剩结尾部分的问题。

2. 信息展示
   - 修复了 Agent 使用 workspace_shell 修改文件时，UI 可能错误地展示不存在的临时文件为变更文件的问题。
   - 修复了 OpenAI 兼容中转流式生成过程中 Token 始终显示为 0 的问题。

3. 修复了因 R8 混淆导致特定包下类名/属性名被混淆，进而引发特定页面选项显示为乱码的问题。

4. 扩展（工作区/ Skill）
   - 修复了 /tmp 和 /var/tmp 总是消失的问题，现在改为只清除目录内容。
   - 修复了格式错误的 Skill 在 UI 层面消失的问题，现在改为显示警告并支持修复。

### 工程与本地化

1. Gradle 软件源改为阿里云镜像，避免下载超时。

2. 新增 app/proguard-rules.pro，为 `me.rerere.ai.provider` 和 `me.rerere.ai.core` 下的所有类及成员添加 `-keepnames` 规则，保留类名和成员名，避免混淆影响正常功能。

3. 移除 i18n 模块遗留的根目录 package.json 与 bun.lock。

## 下载与安装

[![Release](https://img.shields.io/github/v/release/September-meteor/rikkahub?label=最新版本)](https://github.com/September-meteor/rikkahub/releases/latest)

- 也可在 [Releases](https://github.com/September-meteor/rikkahub/releases) 页面查看历史版本 APK。
- 系统要求：Android 8.0（API 26）及以上。

### 安装前必读

1. **签名冲突**：本 fork 使用我个人的签名打包，与官方 RikkaHub 签名不一致。**无法直接覆盖安装**。
   - 你需要先卸载官方版，再安装此版本；
   - 或者使用一些工作空间软件进行双开；
   - 或者自己修改 `applicationId` 后重新编译（但可能有数据迁移和 google-services.json 配置问题）。
2. **数据迁移**：本版本 release 包和原版包名一致，因此可以导入并应用原版软件导出的备份。请**在卸载原版软件前务必在设置-数据备份-本地将应用数据导出为文件**，并在安装此版本后在相同页面导入备份文件。
3. **风险自担**：个人 DIY 版本，难免有未发现的 bug。请谨慎在生产环境或重要数据上使用。

### 关于更新频率

- 上游更新非常频繁，我**不保证**会实时同步上游的每一个版本。
- 但会尽量在更新时一并同步上游的更新。

> 如果你有自己的想法，可以参考下方【编译】章节自行构建。

## 编译

喜欢图形界面的可尝试 Android Studio，本章节主要介绍纯命令行的编译方法。

编译环境：GNU/Linux（Debian 系）或类似终端环境（如 Windows 的 WSL）。

### 编译要求

1. 基础运行环境
   - **JDK**：`openjdk-17-jdk`
   - **Node.js & pnpm**: Node 22 + pnpm 11

2. APP 编译
   - **Android SDK**：
     - `platform-tools`
     - `build-tools;37.0.0`
     - `platforms;android-37`
   - **gradlew 同步 & 配置文件**：确保 app/ 目录下存在配置好的 google-services.json

3. 前端 Web 全栈
   - **RTV Stack**：React Router v7 + Tailwind Oxide + Vite
   - **Google Material Color Utilities 库**

> 由于篇幅原因，这里只说构建方法。
> 关于编译要求的1、2点如何配置，可查看 [BUILDING.md](BUILDING.md)

### 一、准备依赖和克隆

1. 克隆同时拉取 Material Color Utilities 源码
```bash
git clone --recursive https://github.com/September-meteor/rikkahub.git
```

2. 安装前端依赖
```bash
cd ~/rikkahub/web-ui
pnpm install
```

### 二、开始构建

由于配置签名密钥很麻烦，所以我们这里直接借用 Debug 密钥。这里**仅限个人使用**这么做，否则应该配置 Release 签名。
```bash
cd ~/rikkahub && printf "storeFile=$HOME/.android/debug.keystore\nstorePassword=android\nkeyAlias=androiddebugkey\nkeyPassword=android\n" >> local.properties
./gradlew assembleRelease
```

构建好的安装包位于 `~/rikkahub/app/build/outputs/apk/release/`：
  - app-arm64-v8a-release.apk 适配 ARM 64位处理器，适用2016年后的绝大多数手机。
  - app-x86_64-release.apk 适配 64位x86 架构的处理器，主要用于 Android 模拟器或极少数的 Intel/AMD 设备。
  - app-universal-release.apk 为通用包，包含以上架构的代码。

## 许可证 (License)

本项目是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的衍生作品 (Derivative Work)。

**原项目许可：**
> 原项目 RikkaHub (由 [re-ovo](https://github.com/re-ovo) 及 RikkaHub 贡献者开发) 采用 [GNU Affero General Public License v3.0 (AGPL-3.0)](https://www.gnu.org/licenses/agpl-3.0.html) 许可。

**本 Fork 修改部分：**
> Copyright © 2026 September-meteor
> 本 fork 中新增和修改的代码，同样遵循 **AGPL-3.0** 许可证。

根据 AGPL-3.0 协议关于“修改版本 (Modified Versions)”的要求，本项目已在上方 **[与上游的区别]** 章节中详细标明了所有对原项目的修改内容。

完整的 AGPL-3.0 许可证文本请查阅仓库根目录的 [`LICENSE`](LICENSE) 文件。

## 致谢

感谢 [@re-ovo](https://github.com/re-ovo) 和 RikkaHub 团队开发了如此优秀的客户端！

上游更新极快且专注核心演进，本 fork 纯属个人为了修补一些影响体验的 bug 和满足个人的细碎需求（如思维链翻译、图片压缩）而打的补丁。项目 99.9% 的代码和架构设计均归功于原作者。