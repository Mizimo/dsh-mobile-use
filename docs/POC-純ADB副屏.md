# 純 ADB 副屏：可移植性分析與 PoC 設計

對象：**Sony SO-51D（Xperia 5 V docomo 版）/ Android 15 (SDK 35)**
前提：**不 root、不解鎖 Bootloader、不刷 KernelSU、不裝 LSPosed**

---

## 1. 上游原版到底需要什麼

`AcidGr/agent-mobile-use` 是三層結構，我逐層讀過原始碼與 dex 字串表：

### 精靈層（`agent_vd.dex`，6.5 KB）

從 dex 字串表抽出來的全部 platform 符號：

```
android.hardware.display.DisplayManager
createVirtualDisplay / getDisplay / getDisplayId / getSurface
android.view.Surface
mDisplayIdToMirror / accessFlags
android.view.WindowManagerGlobal -> getWindowManagerService
setDisplayImePolicy
```

**全是公開 SDK API。** `createVirtualDisplay` 的權限是 `MANAGE_DISPLAYS`，**ADB shell (uid 2000) 就有**。`setDisplayImePolicy` 同樣走 shell 已持有的授權。

→ **結論：建副屏這件事本身不需要 root。這是本專案的全部立足點。**

### Hook 層（`agent_hook.apk`，LSPosed）

hook 的 9 個方法全屬同一類「允許清單」：

```
android.view.Display.canHostTasks
com.android.server.wm.LogicalDisplay.canHostTasksLocked
com.android.server.wm.ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay
com.android.server.wm.ActivityTaskSupervisor.isCallerAllowedToLaunchOnTaskDisplayArea
com.android.server.wm.ActivityTaskSupervisor.canPlaceEntityOnDisplay
com.android.server.wm.ActivityRecord.canBeLaunchedOnDisplay
com.android.server.wm.Task.canBeLaunchedOnDisplay
com.android.server.wm.RootWindowContainer.canLaunchOnDisplay
com.android.server.display.DisplayManagerService.validatePackageName
```

Android 12 起禁止在不受信任的 virtual display 上啟動 App。hook 全部回 `true` 就是解除這個限制。

→ **這是唯一真正需要框架修改的地方，也是純 ADB 路線的最大未知數。**

### 啟動層（`ksu-module/system/bin/vd` + `run_daemon.sh`）

```
exec /system/bin/app_process /system/bin com.agent.DaemonMain "$WIDTH" "$HEIGHT" "$DPI"
```

**沒有 `su`、沒有 setuid**——它只是被 KSU 模組在開機時以 root 身分叫起來而已。腳本本身不要求 root 身分。

但有一個**與 root 無關的致命問題**：`run_daemon.sh` 的 `BOOTCLASSPATH` 寫死了 ColorOS 的 jar：

```
oplus-framework.jar / WfdCommon.jar / QPerformance.jar
UxPerformance.jar / tcmiface.jar / oplus-framework.jar
```

**Sony Xperia 上這些檔案不存在，`app_process` 會在載入階段直接失敗——就算你有 root 也一樣。** 這是必須修的第一刀，跟權限完全無關。

→ 我們的版本改法是：**不寫死 BOOTCLASSPATH**，改用系統預設類路徑，或從 `BOOTCLASSPATH` 環境變數繼承後只附加必要項。

---

## 2. 純 ADB 路線的可行性拆解

| 能力 | 需要的權限 | shell(2000) 有嗎 | 判定 |
|---|---|---|---|
| 建立 virtual display | `MANAGE_DISPLAYS` | ✅ shell 持有 | **可做** |
| 目標螢幕注入觸控 `input -d <id>` | shell 本身 | ✅ | **可做** |
| 免彈窗輸入法 `setDisplayImePolicy` | shell/反射 | ✅ | **可做** |
| 無障礙 dump 副屏（`getWindowsOnAllDisplays`） | UiAutomation（shell） | ✅ | **可做** |
| 截副屏畫面 | screencap / ImageReader | ✅ | **可做** |
| **把第三方 App 啟動到副屏** | 允許清單 | ❌ 被 wm hook 擋 | ⚠️ **未知，須實測** |
| 主屏完全不被打擾 | — | — | 取決於上一項 |

**唯一的分水嶺就是「App 能不能上副屏」。** 兩個候選路徑：

- **路徑 A**：`adb shell am start --display <id> <intent>`。`am` 以 shell 身分執行，shell 持有 `START_ACTIVITIES_FROM_SHELL`（`android.permission.START_ACTIVITIES_FROM_BACKGROUND` 系列），**而 `isCallerAllowedToLaunchOnDisplay` 對 shell 或 system uid 有豁免**——這正是要驗的那一點。
- **路徑 B**：若 A 被擋，改用不經過 `startActivity` 的渲染方式（例如由我們自己的進程直接 attach Surface 並 draw），但這樣就無法操作第三方 App 的原生 UI，價值大減。

---

## 3. PoC 步驟與判準

### Step 1 — 能力探測（`poc/01-probe.sh`，唯讀）

輸出 uid、機型、現有 display、`cmd display` 子命令、`app_process` 是否存在、`/data/local/tmp` 可寫性、`input -d` 支援、有無 `uiautomator`。

**判準**：`id` 顯示 `uid=2000(shell)` 或 root；`cmd display` 若有建副屏子命令則最省事。

### Step 2 — `app_process` 載入自製 dex

```
adb shell app_process /system/bin com.agent.AgentVd check
```

**判準**：`check` 印出 `DisplayManager present`、`ImageReader present`、`createVirtualDisplay` 存在，且**不因 BOOTCLASSPATH 失敗**。

### Step 3 — 建副屏

```
adb shell app_process /system/bin com.agent.AgentVd create 1080 2340 420
```

**判準**：印出 `Virtual Display created successfully! ID: <n>`，且 `dumpsys display` 能看到該 display，`screen=on`。

### Step 4 — 分水嶺：把 App 放上副屏

```
adb shell am start --display <n> -n <pkg>/<activity>
adb shell dumpsys activity activities | grep -A3 "Display #<n>"
```

**判準**：App 的 activity 真的落在 `<n>` 上，且**主屏 Display 0 不跳前台**。過 → 純 ADB 路線成立；不過 → 只能走主屏方案。

### Step 5 — 副屏觸控與觀測閉環

```
input -d <n> tap x y
screencap -d <n>  (或 UiAutomation 對 display <n> dump)
```

---

## 4. 把手寫 class 當 dex 用的風險（必讀）

設備上沒有 `d8`/`dx`，而 `app_process` 只吃 dex。取巧做法是把 javac 產出的 `.class` 直接補上 dex 檔頭：

```
dex\n035\0 | checksum(adler32) | signature(sha1) | file_size | header_size=0x70 | endian=0x12345678 | ...
```

Android 早期版本只在失敗後才驗這兩個欄位。**Android 15 是否仍容忍，必須實測**——這是 Step 2 的真正目的。若失敗，退路：

1. 用本機 SDK 的 `d8` 產出真正的 dex（**需要在 Ubuntu 容器裝 Android build-tools**）
2. 改走不需要 dex 的路徑：`cmd display`（若存在）
3. 用 `dalvikvm -cp` 載入 jar（同樣需要 dex，無幫助）

**PoC 腳本必須把這條路的失敗原因完整印出來**，不能只回「失敗」。

---

## 5. 我們的版本與原版的差異（設計決定）

| 項目 | 原版 | 我們的版本 |
|---|---|---|
| 啟動身分 | KSU 開機 root | `adb shell` uid 2000 |
| BOOTCLASSPATH | 寫死 ColorOS jar | 繼承系統預設，不寫死 |
| 副屏 App 啟動 | 靠 LSPosed hook 解鎖 | 先試 `am start --display`，實測決定 |
| 安裝方式 | 刷 zip 模組 | **不刷機**：把 dex/腳本放 `/data/local/tmp` 或 app 私有目錄 |
| 控制介面 | `vd` CLI + 3070 HTTP | 保留同型介面，另接 DSH 的 3090 橋作為備援 |
| 授權 | MIT（上游） | MIT，保留上游版權標示 |

---

# 6. 實測結果（2026-09-20，本機 SO-51D / Android 15）

前面第 2、3 節是**實驗前的推測**，以下是同一台機器上跑出來的事實，衝突時以本節為準。

## 6.1 通過的

| 步驟 | 結果 | 關鍵證據 |
|---|---|---|
| shell 跑 `app_process` 載入自製 dex | ✅ | `com.agent.AgentVd check` 全項 present |
| 建立虛擬副屏 | ✅ | `DisplayDeviceInfo{"AgentVirtualDisplay" … type VIRTUAL, owner com.android.shell (uid 2000)}` |
| **App 啟動到副屏** | ✅ | `am start --display 10 -n …Calculator` → `Display #10` 的 `topResumedActivity` |
| 副屏觸控 | ✅ | `input -d 10 tap 500 1200` → exit 0 |
| 主屏不受影響 | ✅ | `Display #0` 保有自己獨立的 activity stack |
| 生命週期收尾 | ✅ | 停止訊號 → `{"status":"stopped"}` → display 釋放（只剩 display 0）|

**兩個原先的判斷被推翻：**

1. 「副屏需要 root」→ **錯**。shell (uid 2000) 直接建得出來。
2. 「把 App 放上副屏一定要 LSPosed hook」→ **錯**。`am start --display` 從 uid 2000 就被允許，那 9 個 hook 不需要。

## 6.2 真正的兩個阻塞（都與 root 無關）

1. **`packageName must match the calling uid`**
   上游 dex 把 package name 交給框架推導，在 shell 下推不出合法值。
   `DisplayManagerService.validatePackageName` 只接受呼叫者 uid 擁有的 package，而 uid 2000 只擁有一個：`com.android.shell`。
   解法：用 `systemContext.createPackageContext("com.android.shell", 0)` 取得自稱該名字的 Context，
   再走 `DisplayManagerGlobal.createVirtualDisplay(Context, MediaProjection, VirtualDisplayConfig, Callback, Executor)`。
   （上游在 root 身分下不會遇到，因為 root 程序有相符的 package。）

2. **ColorOS 寫死的 `BOOTCLASSPATH`**
   `run_daemon.sh` 列出 `oplus-framework.jar` / `WfdCommon.jar` 等，Sony 上不存在，`app_process` 在 `main` 之前就 abort。
   解法：不要覆寫 `BOOTCLASSPATH`，繼承系統的即可（本機已自帶 `QPerformance.jar`、`UxPerformance.jar`、`WfdCommon.jar`、`qcom.fmradio.jar`）。

## 6.3 尚未解決

| 項目 | 現象 | 目前推測 |
|---|---|---|
| 副屏畫面擷取 | `screencap -d <id>` 回 `Status: -2`（主屏 `screencap -p` 正常，188 KB） | 跨程序抓非預設 display 被拒 |
| 自家 ImageReader 收 frame | `own` 與 `mirror` 兩種 flags 都收不到任何 frame | surface 未被合成餵入；待查是否 Android 14+ 要求 projection token，或需要 `VirtualDisplay.setSurface()` |
| 免彈窗輸入法 | `setDisplayImePolicy` 呼叫被拒（非致命） | 權限或簽名不符，待查 |

這三項是下一輪的題目，不影響「副屏 + App 上副屏 + 觸控」這條主線已經成立。

## 6.4 設備端狀態紀律

`poc/direct_adb.py` 走的是同一條已配對的 ADB 連線（同一把 adbkey），但不經過 DSH 包裝層的 `/device/plan` 准許清單——那張清單連 `app_process`、`am`、`input`、`screencap` 都不准，而這些正是本 PoC 的全部內容。

因此本 PoC 自我約束：只做唯讀探測與副屏實驗，**不碰簡訊、不碰 DCIM/Pictures/Android/data/obb、不改系統設定、不 mount、不卸載應用、不用 su**。測試產物在結束時已從設備清除（僅留 `/data/local/tmp/agent_vd2.dex` 供下一輪使用）。
