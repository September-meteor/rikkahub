# Rikkahub

[English](README.md) | [简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md)

> 这是 [rikkahub](https://github.com/rikkahub/rikkahub) 的个人 fork。
> 原项目非常出色，我只是在此基础上修改了一点小 bug 或对我个人而言的痛点，增加了一点新功能。
> 如果改动里有你心仪的点，你可以尝试此项目；如果你追求原生和稳定，你应该选择原项目。

## 和上游的区别

### 新增功能与体验优化

#### 工作区相关

1. **目录导入与智能过滤**：
   - 支持一键导入整个文件夹到工作区，并提供导入进度显示 UI 以及导入失败弹窗提示。
   - 内置 `.gitignore` 递归解析功能（支持否定规则及锚定），并支持自定义排除模式配置。
   - **同名冲突处理**：
     - 上传遇到同名文件/目录时，默认创建 `name (1).ext` 副本。
     - 可在导入设置开启「上传遇到同名文件/目录时覆盖」，覆盖前弹「导入预览」确认后再执行。
     - 覆盖逻辑同 `rsync -delete` 语义 + 忽略排除文件。

2. **目录同步回传**：
   - 支持将工作区文件同步回原始 SAF 目录。
   - 提供「快速（仅尺寸比对）」与「完整（CRC32 内容校验）」两种差异检查模式。
   - 提供扫描（进度条 + 倒计时）→文件变动预览→执行的引导弹窗。
   - 同步逻辑同 `rsync -delete` 语义 + 忽略排除文件。

#### 消息相关

1. **消息底部变更文件检测**：
   - 增加 `workspace_shell` 变更文件检测，变更列表会自动附加到消息元数据中。
   - 生成中途停止时，变更列表自动挂载到最后的 shell 工具消息，不会因停止而丢失。

2. **消息底部变更文件展示**：
   - 新增路径显示切换开关，支持在“纯文件名”与“完整相对路径”间一键切换。
     - 若所有变更文件同属一个项目目录，自动省略项目名前缀。
     - 全路径模式下改为单列展示并支持横向滚动，避免长路径折行
   - 新增折叠按钮，并且“纯文件名”与“完整相对路径”两个模式共享展开/折叠状态。
   - 文件标签支持长按复制当前路径到剪贴板并伴随 Toast 提示。

3. **工具调用提速**：减少了 `workspace_shell` 工具调用的固定消耗，加快工具返回结果速度。

4. **Token 统计增强**：
   - 新增「显示累计 Token 消耗」开关（偏好设置 → 消息显示）。
   - 开启后将展示本次对话总消耗输入/输出/缓存 Tokens（包括工具返回结果伴随的上下文消耗），与官方控制台统计一致。
   - 生成过程中消息底部实时更新本条消息本次消耗，生成结束后切换为截至该消息的对话累计值。

5. **消息信息栏优化**：
   - 缓存命中显示占比：`↑ xxx Tokens（xxx cached xx%）`。
   - Token 生成速度改为按纯生成耗时计算，排除工具调用执行时间，避免了工具运行时间将模型生成速度压至个位数的无意义数据。

6. **实时 Token 价格**：
   - 在「设置 → 提供商 → 模型高级设置」中新增「实时 Token 价格」配置，配置后聊天标题栏将按当前时段展示对应价格提示。
   - 支持配置星期、时间区间、价格、单位、提示文字、背景颜色。
   - 提示文字支持模板变量一键插入；颜色使用十六进制，可以快捷选择常用颜色，也可在填写时预览。
   - 内置「其他时间」行，可配置所有未设置时间段的价格提示，也可单独当做全时间段价格。

#### 扩展功能

1. **图片压缩**：
   - 发送图片给 AI 前自动压缩过大尺寸图片。
   - 可在「偏好设置 → 实验性功能 → 图片压缩」中开关及调整最大边长，默认 2048px。

2. **思维链 (CoT) 增强**：
   - **一键复制**：AI 消息头像旁新增一键复制模型思维链按钮。
   - **流式翻译**：AI 消息头像旁新增翻译与语言选择按钮。
     - 支持一键原文/译文切换。
     - 译文同原思维链流式生成。
     - 语言按钮以国旗 emoji 显示，默认跟随系统语言。

### bug 修复

1. **Markdown / HTML 嵌套列表渲染错误**：
   - 修复了子有序列表被错误地当成外层有序列表渲染的问题。
   - 修复了列表项内文本与子列表穿插时，子列表被错误堆放到结尾的问题。
   - 优化了列表项内多段落/块级元素的垂直排版，避免内容横向挤压或超出屏幕。

2. **Release 构建显示乱码**：修复了因 R8 混淆导致特定包下类名/属性名被混淆，进而引发特定页面选项显示为乱码的问题。

3. **工作区临时文件展示异常**：修复了 Agent 使用 `workspace_shell` 修改文件时，UI 错误地将已经不存在的临时文件展示为变更文件的问题。

4. **Token 统计异常**：修复了 OpenAI 兼容中转流式生成过程中 Token 显示始终为 0 的问题。

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
cd ~/rikkahub
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