# Rikkahub

[English](README.md) | [简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md)

> 這是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的個人 fork。
> 原專案非常優秀，我只是在此基礎上修復了一些小 bug，或是針對我個人的痛點增加了一些小功能。
> 如果你喜歡這些改動，可以嘗試此專案；如果你追求原生與穩定，建議選擇原專案。

## 與上游的差異

### 新增功能與體驗優化

#### 工作區

1. **檔案／目錄匯入功能**：
   - **新增目錄匯入功能**
     - `.gitignore` 遞迴解析功能與自訂排除模式設定，支援較完整的 `.gitignore` 語法
     - 新增匯入進度顯示 UI 與匯入設定卡片
   - **同名檔案／目錄衝突處理**
     - 工作區基本設定新增「同名內容處理」選項，預設行為為「建立副本」，可改為「覆寫合併」
     - 「建立副本」模式下，匯入時遇同名檔案／目錄會建立 `name (1).ext` / `name (1)`
     - 「覆寫合併」行為等同 `rsync --delete`，但會忽略 `.gitignore` 與自訂排除模式排除的檔案

2. **目錄同步回傳／匯出功能**：
   - **目錄同步回傳**
     - 提供快速（僅比對大小）與完整（CRC32 內容校驗）兩種差異檢查模式。
     - 支援根目錄與子目錄／檔案同步。
     - 基礎行為等同 `rsync --delete`，但會忽略 `.gitignore` 與自訂排除模式排除的檔案。
     - 掃描（預估時間）→ 預覽 → 執行的狀態彈窗。
   - **目錄匯出**：支援將目錄匯出到任意 SAF 資料夾。

3. **檔案開啟改為「盡力而為」模式**
   - 無副檔名或未知副檔名的檔案預設以內建編輯器開啟。
   - 偵測到二進位或超大檔案時，提供「用其他應用程式開啟」與「分享」做為備案。
   - 明確的非文字類型（影音、壓縮檔等）仍交由系統應用程式處理。

4. **檔案系統最佳化**：
   - **顯示統一**
     - 「系統／rootfs」根目錄在真實磁碟目錄之上疊加虛擬掛載目錄：/workspace、/skills、/upload、/tool_outputs，避免顯示與實際內容不符。
     - 「檔案」區路徑列的根目錄由 / 改為 /workspace，避免誤解。
     - 互動終端改用與檔案瀏覽同一份 bind mount 設定產生 -b 參數，補掛 /upload、/tool_outputs，消除終端與瀏覽掛載設定的差異。
     - rootfs 初始化標記遷移至 /var/lib/rikkahub，避免誤解。
   - **操作最佳化**：檔案瀏覽一律顯示返回按鈕；並依目錄保留捲動位置，返回時不會重設狀態

#### 訊息

1. **思維鏈複製功能**：AI 訊息頭像旁新增複製模型思維鏈的按鈕，可複製到剪貼簿。

2. **思維鏈翻譯功能**：
   - AI 訊息頭像旁新增翻譯與語言選擇按鈕：
     - 支援一鍵切換原文／譯文，譯文與原思維鏈一樣串流生成。
     - 語言按鈕以國旗 emoji 顯示，預設跟隨系統語言。
   - 針對多工具呼叫思維鏈的擴充最佳化：
     - 「設定 → 偏好設定 → 實驗性功能」新增「思維鏈翻譯」設定
     - 可設定：顯示方式（併入第一條／依卡片顯示）、傳送方式（打包／逐條）、打包使用的分隔符號、翻譯按鈕行為（自動展開第一條／全部）。

3. **工具呼叫最佳化**：
   - 降低 workspace_shell 工具呼叫的固定開銷：減少每次工具呼叫啟動的處理程序數。
   - 工具呼叫標題完整顯示，不再截斷。

4. **即時 Token 價格**：
   - 「設定 → 提供商 → 具體模型進階設定」中新增「即時 Token 價格」設定
   - 支援設定星期、時段、價格、單位、提示文字、高亮背景色。
   - 設定完成後，會在聊天標題列下方依目前時段顯示對應的價格提示。

#### 訊息-雜項

1. **訊息底部變更檔案**：
   - **完善變更檔案偵測**：根據工作區實際檔案變動顯示新增與更新的檔案。
   - **路徑顯示切換開關**：支援在檔名與完整相對路徑之間切換。
   - 變更檔案摺疊開關，檔名與完整相對路徑兩種模式共用展開／摺疊狀態。
   - 檔案標籤支援長按複製目前顯示的路徑到剪貼簿，並顯示 Toast 提示。

2. **訊息底部資訊欄**：
   - 「設定 → 偏好設定 → UI 偏好設定」新增「顯示累計Token消耗」開關
     - 開啟後依官方控制台口徑，累加顯示輸入／輸出／快取 Token。
     - 每當工具呼叫回傳一次，訊息底部即時更新本條訊息累計 Token 數；訊息結束後顯示本次對話累計 Token 數。
   - **快取命中顯示增加佔比**：↑ xxx Tokens（xxx cached xx%）
   - Token 生成速度統計改為僅依純生成耗時計算，排除工具呼叫執行時間。

3. **會話操作最佳化**
   - **會話資料夾記憶與新對話自動歸檔**：
     - 每個助手記住自己最後選中的資料夾，退出重進或切換助手後自動恢復。
     - 新對話預設進入目前選中的資料夾，不再預設落在未分類。
   - **會話列表捲動位置**：
     - 抽屜會話列表的捲動位置改為依「助手＋分類」分別記憶，切換助手或分類後會從各自上次停留的位置繼續。
     - 目前對話不在可視區域內時，列表右上角顯示「回到目前對話」圖示，點擊後捲回該對話。

#### 全域

1. **圖片壓縮**：
   - 傳送圖片給 AI 前自動壓縮過大的圖片。
   - 可在「偏好設定 → 實驗性功能 → 圖片壓縮」中開關及調整最大邊長，預設 2048px。

2. **更新最佳化**：
   - 更新卡片收窄為一行，點擊展開詳情。
   - 顯示更新中新增永久暫停更新選項。

3. **統一文字輸入框實作**：
   - 訊息、設定、編輯器等輸入框改接統一的元件，基礎行為向訊息輸入靠齊
   - 達成一致的光標穩定、不丟字、允許中間狀態、持久化互不衝突。
   - 取捨：JSON／腳本編輯為了輸入穩定移除即時格式化與語法高亮；官方安全輸入框屬特例，不在此範圍。

### Bug 修復

1. 訊息相關
   - **Markdown / HTML 巢狀列表渲染錯誤**：
     - 修復子有序列表被錯誤地當成外層有序列表渲染的問題。
     - 修復列表項內文字與子列表穿插時，子列表被錯誤堆放到結尾的問題。
     - 最佳化列表項內多段落／區塊級元素的垂直排版，避免內容橫向擠壓或超出畫面。
   - 修復部分模型訊息的第一個長思維鏈卡片收起後只剩結尾部分的問題。

2. 資訊顯示
   - 修復 Agent 使用 workspace_shell 修改檔案時，UI 可能錯誤地將不存在的暫存檔案顯示為變更檔案的問題。
   - 修復 OpenAI 相容中轉串流生成過程中 Token 始終顯示為 0 的問題。

3. 修復因 R8 混淆導致特定套件下類別名稱／屬性名稱被混淆，進而引發特定頁面選項顯示亂碼的問題。

4. 擴充（工作區／ Skill）
   - 修復 /tmp 與 /var/tmp 總是消失的問題，現在改為只清除目錄內容。
   - 修復格式錯誤的 Skill 在 UI 層面消失的問題，現在改為顯示警告並支援修復。

### 工程與本地化

1. Gradle 軟體來源改為阿里雲鏡像，避免下載逾時。

2. 新增 app/proguard-rules.pro，為 me.rerere.ai.provider 與 me.rerere.ai.core 下的所有類別及成員新增 -keepnames 規則，保留類別名稱與成員名稱，避免混淆影響正常功能。

3. 移除 i18n 模組遺留的根目錄 package.json 與 bun.lock。

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