## Environment Setup

[English](BUILDING.en.md) | [简体中文](BUILDING.md)

### I. Placing the google-services.json File

1. Go to the [Firebase Console](https://console.firebase.google.com/).

2. Create a new project → Add an Android app; you can enter any name for the project.

3. Enter `me.rerere.rikkahub` as the application package name, which should match the `applicationId` in `app/build.gradle`.

> If you plan to build a debug package or have modified the `applicationId`, you should enter the corresponding application package name—for example, `me.rerere.rikkahub.dug` for a debug package.

4. Download `google-services.json` and place it in the `app/` directory.

### II. Basic Environment Setup

1. Basic tools and JDK-17 (if unavailable, JDK-21, i.e., `openjdk-21-jdk`, is also compatible).
```bash
sudo apt update
sudo apt install -y openjdk-17-jdk wget unzip git
```

2. Node.js & pnpm (Node 20 + pnpm 10 appears to be compatible, but Node 22 + pnpm 11 is recommended)
```bash
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install nodejs -y
node -v    # Should display v22.x.x
mkdir -p ~/.local/bin
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
npm install -g pnpm@11 --prefix ~/.local
pnpm -v    # Should display 11.x.x
```

### III. Android SDK Configuration

1. Directories and Environment Variables
```bash
mkdir -p ~/Android/Sdk
echo 'export ANDROID_HOME=$HOME/Android/Sdk' >> ~/.bashrc
echo 'export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH' >> ~/.bashrc
source ~/.bashrc
```

2. Download the Android Command-Line Tools
```bash
cd /tmp
wget https://dl.google.com/android/repository/commandlinetools-linux-12266719_latest.zip
unzip commandlinetools-linux-*.zip -d $ANDROID_HOME/cmdline-tools/
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
```

3. Install the SDK platform
```bash
yes | sdkmanager --licenses
sdkmanager "platforms;android-37.0" "build-tools;37.0.0"
```

4. Create a symbolic link to prevent Gradle from failing to find `android-37`
```bash
ln -s $ANDROID_HOME/platforms/android-37.0 $ANDROID_HOME/platforms/android-37
```