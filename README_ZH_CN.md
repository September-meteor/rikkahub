# Rikkahub

[English](README.md) | [简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md)

> 这是 [rikkahub](https://github.com/rikkahub/rikkahub) 的个人 fork。
> 原项目非常出色，我只是在此基础上修改了一点小 bug 或对我个人而言的痛点，增加了一点新功能。
> 如果改动里有你心仪的点，你可以尝试此项目；如果你追求原生和稳定，你应该选择原项目。

## 和上游的区别

### 问题修复
- **Markdown / HTML 嵌套列表渲染错误**：
  - 修复了子有序列表被错误地当成外层有序列表渲染的问题。
  - 修复了列表项内文本与子列表穿插时，一些子列表被错误地堆放到结尾的问题。
  - 优化了列表项内多段落/块级元素的垂直排版，避免内容横向挤压。
- **Release 构建兼容性问题**：修复 release 包中因类名/属性名被混淆可能导致的部分页面选项显示为乱码的问题。

### 新增功能
- **图片压缩**：发送图片前自动压缩尺寸过大的图片，可在「扩展管理 → 图片压缩」中开关、调整最大边长（默认 2048px）。
- **思维链复制**：AI 消息头像旁新增一键复制模型思维链按钮。
- **思维链翻译**：AI 消息头像旁新增翻译按钮与语言选择按钮：翻译支持原文/译文切换，译文流式生成；语言选择按钮样式为选择语言的国旗 emoji，默认选择系统语言。

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
> 关于编译要求的1、2点如何配置，可查看 [BUILDING.md](./BUILDING.md)

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

构建好的安装包位于 `~/rikkahub/app/build/outputs/apk/release/`。
app-arm64-v8a-release.apk 适配 ARM 64位处理器，适用2016年后的绝大多数手机。
app-x86_64-release.apk 适配 64位x86 架构的处理器，主要用于 Android 模拟器或极少数的 Intel/AMD 设备。
app-universal-release.apk 为通用包，包含以上架构的代码。

## 许可证 (License)

本项目是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的衍生作品 (Derivative Work)。

**原项目许可：**
> 原项目 RikkaHub (由 [rerere](https://github.com/rerere) 及 RikkaHub 贡献者开发) 采用 [GNU Affero General Public License v3.0 (AGPL-3.0)](https://www.gnu.org/licenses/agpl-3.0.html) 许可。

**本 Fork 修改部分：**
> Copyright © 2026 September-meteor
> 本 fork 中新增和修改的代码，同样遵循 **AGPL-3.0** 许可证。

根据 AGPL-3.0 协议关于“修改版本 (Modified Versions)”的要求，本项目已在上方 **[与上游的区别]** 章节中详细标明了所有对原项目的修改内容。

完整的 AGPL-3.0 许可证文本请查阅仓库根目录的 [`LICENSE`](LICENSE) 文件。

## 致谢

感谢 [@rerere](https://github.com/rerere) 和 RikkaHub 团队开发了如此优秀的客户端！

上游更新极快且专注核心演进，本 fork 纯属个人为了修补一些影响体验的 bug 和满足个人的细碎需求（如思维链翻译、图片压缩）而打的补丁。项目 99.9% 的代码和架构设计均归功于原作者。