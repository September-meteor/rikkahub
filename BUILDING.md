## 环境配置

### 一、放置 google-services.json

1. 前往 [Firebase Console](https://console.firebase.google.com/)，可能需要科学上网。

2. 创建新项目 → 添加 Android 应用，项目名称可随意填。

3. 应用包名填 me.rerere.rikkahub，和 app/build.gradle 里的 `applicationId` 一致。

> 若你打算构建 debug 包或修改了 `applicationId`，你应该添加对应的应用包名，如 debug 包是 me.rerere.rikkahub.dug。

4. 下载 google-services.json，放到 app/ 目录下即可。

### 二、基础环境配置

1. 基础工具和 JDK-17（如果没有，JDK-21（`openjdk-21-jdk`）也能兼容）。
```bash
sudo apt update
sudo apt install -y openjdk-17-jdk wget unzip git
```

2. Node.js & pnpm（Node 20 + pnpm 10 疑似可以兼容，但推荐 Node 22 + pnpm 11）
```bash
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install nodejs -y
node -v    # 应显示 v22.x.x
mkdir -p ~/.local/bin
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
npm install -g pnpm@11 --prefix ~/.local
pnpm -v    # 应显示 11.x.x
```

### 三、Android SDK 配置

1. 目录和环境变量
```bash
mkdir -p ~/Android/Sdk
echo 'export ANDROID_HOME=$HOME/Android/Sdk' >> ~/.bashrc
echo 'export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH' >> ~/.bashrc
source ~/.bashrc
```

2. 下载 Android 命令行工具
```bash
cd /tmp
wget https://dl.google.com/android/repository/commandlinetools-linux-12266719_latest.zip
unzip commandlinetools-linux-*.zip -d $ANDROID_HOME/cmdline-tools/
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
```

3. 安装 SDK 平台
```bash
yes | sdkmanager --licenses
sdkmanager "platforms;android-37.0" "build-tools;37.0.0"
```

4. 创建软链接，防止 Gradle 去找 android-37 找不到
```bash
ln -s $ANDROID_HOME/platforms/android-37.0 $ANDROID_HOME/platforms/android-37
```