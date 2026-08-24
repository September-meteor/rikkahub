# Rikkahub

[English](README.md) | [简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md)

> 這是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的個人 fork。
> 原專案非常優秀，我只是在此基礎上修復了一些小 bug，或是針對我個人的痛點增加了一些小功能。
> 如果你喜歡這些改動，可以嘗試此專案；如果你追求原生與穩定，建議選擇原專案。

## 和上游的區別

### 新增功能與體驗優化

#### 工作區相關

1. **目錄導入與智能過濾**：
   - 支援一鍵導入整個資料夾到工作區，並提供導入進度顯示 UI 以及導入失敗彈窗提示。
   - 內建 `.gitignore` 遞迴解析功能（支援否定規則及錨定），並支援自訂排除模式配置。
   - **同名衝突處理**：
     - 上傳遇到同名檔案/目錄時，預設建立 `name (1).ext` 副本。
     - 可在導入設定開啟「上傳遇到同名檔案/目錄時覆寫」，覆寫前彈出「匯入預覽」確認後再執行。
     - 覆寫邏輯同 `rsync --delete` 語意 + 忽略排除檔案。

2. **目錄同步回傳**：
   - 支援將工作區檔案同步回原始 SAF 目錄。
   - 提供「快速（僅尺寸比對）」與「完整（CRC32 內容校驗）」兩種差異檢查模式。
   - 提供掃描（進度條 + 倒數計時）→檔案變動預覽→執行的引導彈窗。
   - 同步邏輯同 `rsync --delete` 語意 + 忽略排除檔案。

#### 訊息相關

1. **訊息底部變更檔案檢測**：
   - 增加 `workspace_shell` 變更檔案檢測，變更列表會自動附加到訊息元資料中。
   - 生成中途停止時，變更列表自動掛載到最後的 shell 工具訊息，不會因停止而遺失。

2. **訊息底部變更檔案展示**：
   - 新增路徑顯示切換開關，支援在「純檔名」與「完整相對路徑」間一鍵切換。
     - 若所有變更檔案同屬一個專案目錄，自動省略專案名前綴。
     - 全路徑模式下改為單列展示並支援橫向滾動，避免長路徑折行。
   - 新增摺疊按鈕，並且「純檔名」與「完整相對路徑」兩個模式共用展開/摺疊狀態。
   - 檔案標籤支援長按複製當前路徑到剪貼簿並伴隨 Toast 提示。

3. **工具呼叫提速**：減少了 `workspace_shell` 工具呼叫的固定消耗，加快工具回傳結果速度。

4. **Token 統計增強**：
   - 新增「顯示累計 Token 消耗」開關（偏好設定 → 訊息顯示）。
   - 開啟後將展示本次對話總消耗輸入/輸出/快取 Tokens（包括工具回傳結果伴隨的上下文消耗），與官方控制台統計一致。
   - 生成過程中訊息底部即時更新本條訊息本次消耗，生成結束後切換為截至該訊息的對話累計值。

5. **訊息資訊欄優化**：
   - 快取命中顯示佔比：`↑ xxx Tokens（xxx cached xx%）`。
   - Token 生成速度改為按純生成耗時計算，排除工具呼叫執行時間，避免了工具運行時間將模型生成速度壓至個位數的無意義數據。

6. **即時 Token 價格**：
   - 在「設定 → 提供商 → 模型進階設定」中新增「即時 Token 價格」配置，配置後聊天標題列將按當前時段展示對應價格提示。
   - 支援配置星期、時間區間、價格、單位、提示文字、背景顏色。
   - 提示文字支援模板變數一鍵插入；顏色使用十六進位，可以快速選擇常用顏色，也可在填寫時預覽。
   - 內建「其他時間」列，可配置所有未設定時間段的價格提示，也可單獨當做全時間段價格。

#### 擴展功能

1. **圖片壓縮**：
   - 發送圖片給 AI 前自動壓縮過大尺寸圖片。
   - 可在「偏好設定 → 實驗性功能 → 圖片壓縮」中開關及調整最大邊長，預設 2048px。

2. **思維鏈 (CoT) 增強**：
   - **一鍵複製**：AI 訊息頭像旁新增一鍵複製模型思維鏈按鈕。
   - **串流翻譯**：AI 訊息頭像旁新增翻譯與語言選擇按鈕。
     - 支援一鍵原文/譯文切換。
     - 譯文同原思維鏈串流生成。
     - 語言按鈕以國旗 emoji 顯示，預設跟隨系統語言。

### bug 修復

1. **Markdown / HTML 嵌套列表渲染錯誤**：
   - 修復了子有序列表被錯誤地當成外層有序列表渲染的問題。
   - 修復了列表項內文本與子列表穿插時，子列表被錯誤堆放到結尾的問題。
   - 優化了列表項內多段落/塊級元素的垂直排版，避免內容橫向擠壓或超出螢幕。

2. **Release 構建顯示亂碼**：修復了因 R8 混淆導致特定包下類名/屬性名被混淆，進而引發特定頁面選項顯示為亂碼的問題。

3. **工作區臨時檔案展示異常**：修復了 Agent 使用 `workspace_shell` 修改檔案時，UI 錯誤地將已經不存在的臨时檔案展示為變更檔案的問題。

4. **Token 統計異常**：修復了 OpenAI 相容中轉串流生成過程中 Token 顯示始終為 0 的問題。

## 下載與安裝

[![Release](https://img.shields.io/github/v/release/September-meteor/rikkahub?label=最新版本)](https://github.com/September-meteor/rikkahub/releases/latest)

- 也可在 [Releases](https://github.com/September-meteor/rikkahub/releases) 頁面查看歷史版本 APK。
- 系統要求：Android 8.0（API 26）及以上。

### 安裝前必讀

1. **簽章衝突**：本 fork 使用我個人的簽章打包，與官方 RikkaHub 簽章不一致。**無法直接覆蓋安裝**。
   - 你需要先解除安裝官方版，再安裝此版本；
   - 或者使用一些工作空間軟體進行雙開；
   - 或者自己修改 `applicationId` 後重新編譯（但可能有資料遷移和 google-services.json 設定問題）。
2. **資料遷移**：本版本 release 包和原版包名一致，因此可以匯入並套用原版軟體匯出的備份。請**在解除安裝原版軟體前務必在設定-資料備份-本地將應用程式資料匯出為檔案**，並在安裝此版本後在相同頁面匯入備份檔案。
3. **風險自負**：個人 DIY 版本，難免有未發現的 bug。請謹慎在生產環境或重要資料上使用。

### 關於更新頻率

- 上游更新非常頻繁，我**不保證**會即時同步上游的每一個版本。
- 但會盡量在更新時一併同步上游的更新。

> 如果你有自己的想法，可以參考下方【編譯】章節自行建置。

## 編譯

喜歡圖形介面的可嘗試 Android Studio，本章節主要介紹純命令列 (CLI) 的編譯方法。

編譯環境：GNU/Linux（Debian 系）或類似終端機環境（如 Windows 的 WSL）。

### 編譯要求

1. 基礎執行環境
   - **JDK**：`openjdk-17-jdk`
   - **Node.js & pnpm**: Node 22 + pnpm 11

2. APP 編譯
   - **Android SDK**：
     - `platform-tools`
     - `build-tools;37.0.0`
     - `platforms;android-37`
   - **gradlew 同步 & 設定檔**：確保 app/ 目錄下存在設定好的 google-services.json

3. 前端 Web 全端
   - **RTV Stack**：React Router v7 + Tailwind Oxide + Vite
   - **Google Material Color Utilities 函式庫**

> 由於篇幅原因，這裡只說建置方法。
> 關於編譯要求的 1、2 點如何設定，可查看 [BUILDING.md](BUILDING.en.md)

### 一、準備相依性與複製

1. 複製同時拉取 Material Color Utilities 原始碼
```bash
git clone --recursive https://github.com/September-meteor/rikkahub.git
```

2. 安裝前端相依性
```bash
cd ~/rikkahub/web-ui
pnpm install
```

### 二、開始建置

由於設定簽章金鑰很麻煩，所以我們這裡直接借用 Debug 金鑰。這裡**僅限個人使用**這麼做，否則應該設定 Release 簽章。
```bash
cd ~/rikkahub
cd ~/rikkahub && printf "storeFile=$HOME/.android/debug.keystore\nstorePassword=android\nkeyAlias=androiddebugkey\nkeyPassword=android\n" >> local.properties
./gradlew assembleRelease
```

建置好的安裝包位於 `~/rikkahub/app/build/outputs/apk/release/`：
  - app-arm64-v8a-release.apk 適配 ARM 64位元處理器，適用2016年後的絕大多數手機。
  - app-x86_64-release.apk 適配 64位元 x86 架構的處理器，主要用於 Android 模擬器或極少數的 Intel/AMD 裝置。
  - app-universal-release.apk 為通用包，包含以上架構的程式碼。

## 授權條款 (License)

本專案是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的衍生作品 (Derivative Work)。

**原專案授權：**
> 原專案 RikkaHub (由 [re-ovo](https://github.com/re-ovo) 及 RikkaHub 貢獻者開發) 採用 [GNU Affero General Public License v3.0 (AGPL-3.0)](https://www.gnu.org/licenses/agpl-3.0.html) 授權。

**本 Fork 修改部分：**
> Copyright © 2026 September-meteor
> 本 fork 中新增和修改的程式碼，同樣遵循 **AGPL-3.0** 授權條款。

根據 AGPL-3.0 協議關於「修改版本 (Modified Versions)」的要求，本專案已在上方 **[與上游的差異]** 章節中詳細標明了所有對原專案的修改內容。

完整的 AGPL-3.0 授權條款文本請查閱儲存庫根目錄的 [`LICENSE`](LICENSE) 檔案。

## 致謝

感謝 [@re-ovo](https://github.com/re-ovo) 和 RikkaHub 團隊開發瞭如此優秀的客戶端！

上游更新極快且專注核心演進，本 fork 純屬個人為了修補一些影響體驗的 bug 和滿足個人的細碎需求（如思維鏈翻譯、圖片壓縮）而打的補丁。專案 99.9% 的程式碼和架構設計均歸功於原作者。