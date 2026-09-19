# dsh-mobile-use

[English](README.md) | **中文說明**

給 DeepSeek Harness 用的純 ADB Android 裝置控制 —— 把
[`agent-mobile-use`](https://github.com/AcidGr/agent-mobile-use) 的虛擬副屏架構
**在不要 root、不要解鎖 Bootloader、不要 KernelSU、不要 LSPosed 的前提下**重新實作一次。

上游需要刷一個 KernelSU 模組，並用 LSPosed hook `system_server`。這個專案要問的是：
在一台**量產、Bootloader 鎖死**的手機上，光靠 `adb shell`（uid 2000）能不能拿到同樣的能力。
在這台測試機上：**能**。

## 狀態（實機量測，不是推論）

測試機：**Sony SO-51D（Xperia 5 V docomo 版）/ Android 15 (SDK 35)**，
`ro.boot.flash.locked=1`（Bootloader 永久鎖定，無 root、無 LSPosed）。

| 能力 | 結果 | 證據 |
| --- | --- | --- |
| 在 shell 下用 `app_process` 跑我們自己的程式 | ✅ | `CLASSPATH=… app_process /system/bin com.agent.AgentVd check` |
| 建立無頭虛擬副屏 | ✅ | `DisplayDeviceInfo{"AgentVirtualDisplay" … type VIRTUAL, owner com.android.shell (uid 2000)}` |
| 把 App 啟動**到副屏上** | ✅ | `am start --display 10 -n …Calculator` → `topResumedActivity` 落在 `Display #10` |
| 對副屏注入觸控 | ✅ | `input -d 10 tap …` 回傳 0 |
| 擷取副屏畫面 | ✅ | ImageReader → PNG，155 KB，1096×2560 |
| 閉環：感知 → 行動 → 驗證 | ✅ | 在副屏上 tap `7`，重新截圖，畫面出現 `7` |
| 主屏完全不受影響 | ✅ | `Display #0` 仍是 `com.sonymobile.launcher`，同時 `Display #15` 跑著計算機 |
| 副屏免彈窗輸入法 | ⚠️ | `setDisplayImePolicy` 呼叫被拒（非致命） |

截下來的是**副屏自己的畫面**，不是主屏鏡像：像素證據是一台跑在 1096×2560 上的
Google 計算機，而手機主屏（Display 0）還停在桌面。

上表每一項都能用 `poc/` 裡的腳本重現，見下文。

## 為什麼上游的 daemon 在這裡不能直接用

實際跑過之後，找到**兩個彼此獨立**的阻塞：

1. **`packageName must match the calling uid`。**
   上游的 `agent_vd.dex` 把顯示名稱寫死，package name 交給框架推導；在 `adb shell` 下
   推導出來的名字不屬於 uid 2000，於是 `DisplayManagerService.validatePackageName` 直接拒絕。
   uid 2000 只擁有一個 package —— `com.android.shell` —— 所以建立副屏必須經過一個
   「自稱是該名字」的 `Context`：
   `systemContext.createPackageContext("com.android.shell", 0)`。
   上游不會遇到這個問題，因為它以 root 身分執行，本來就擁有相符的 package。

2. **寫死的 ColorOS `BOOTCLASSPATH`。**
   `run_daemon.sh` 裡列了 `oplus-framework.jar`、`WfdCommon.jar` 之類的檔案。
   這些在 Sony 機器上不存在，`app_process` 會在進入 `main` 之前就 abort。
   解法是**不要覆寫** `BOOTCLASSPATH`，繼承系統自己的即可 —— 這台 Xperia 已經自帶
   `QPerformance.jar`、`UxPerformance.jar`、`WfdCommon.jar`、`qcom.fmradio.jar`。

至於上游那 9 個 LSPosed hook（`canHostTasks`、`isCallerAllowedToLaunchOnDisplay`、
`validatePackageName` …），實測**把 App 放上副屏並不需要**：
uid 2000 執行 `am start --display <id>` 就是被允許的。

## 目錄結構

```
poc/
  vd.py            控制匯流排：start | stop | status | launch | tap | swipe |
                   type | key | capture
  java/com/agent/AgentVd.java
                   常駐 daemon：check | probe | create <w> <h> <dpi> [pkg] [own|mirror]
  direct_adb.py    最小直連 ADB 客戶端：跑單一命令、推檔、--hold 持住 session
  build.sh         javac + d8 + push，一行完成一次迭代
  01-probe.sh      唯讀能力探測（只讀設備事實，不改變任何狀態）
  probe.sh         以「一次一條合規命令」的方式跑探測
  run_on_device.py 透過 DSH 的設備 shell 通道跑腳本，並列出被策略拒絕的行
docs/
  POC-純ADB副屏.md  完整分析：上游需要什麼、shell 能做到什麼、實測結果
```

## 怎麼跑

### CLI

```sh
python3 poc/vd.py start                     # 建立副屏並印出 display id
python3 poc/vd.py status                    # id、解析度、frame 計數、上次擷取結果
python3 poc/vd.py launch <pkg>/<activity>   # 把 App 丟到副屏
python3 poc/vd.py tap <x> <y>
python3 poc/vd.py swipe <x1> <y1> <x2> <y2> [ms]
python3 poc/vd.py key <keycode>
python3 poc/vd.py capture out.png           # 從副屏自己的 surface 取一張畫面
python3 poc/vd.py stop                      # 釋放副屏
```

`start` 會先等到「乾淨狀態」才動手，並且用 `dumpsys display` 交叉確認新副屏真的存在，
而不是只信自己的狀態檔 —— 單一共享的狀態檔路徑，正是讓殘留 daemon 看起來還活著的原因。

### 各個零件

`vd.py` 負責調度；以下是它實際呼叫的東西，單獨用也有用：

```sh
bash poc/build.sh                     # javac + d8 + push：一次迭代一行
python3 poc/direct_adb.py 'id'        # 在已配對的連線上跑任意單一命令
python3 poc/direct_adb.py --hold '…'  # 持住一條 shell session（連帶撐住 daemon）
python3 poc/direct_adb.py --push <local> <remote>
```

前提是手機已開啟無線偵錯並完成配對；`adb-shell.py` 會把 host 與 port 記在
`/root/.dsh/adbkeys/`。

`<id>` 每建一次副屏就會遞增，請從 `/data/local/tmp/vd_status.json`
（或 `vd.py status`）讀，不要假設一定是 9 或 10。

## 範圍紀律

`direct_adb.py` 走的是 DSH 設備 shell 包裝層所用的**同一條已配對連線**，
但不經過該包裝層的 `/device/plan` 准許清單。它只為這個 PoC 存在，且自我限制在
唯讀探測與虛擬副屏實驗：

- 不碰簡訊，也不做 `content` 查詢（除了被允許的那一種）；
- 不碰 DCIM / Pictures / Android / data / obb；
- 不做 `settings put`、`setprop`、mount、卸載應用，也不用 `su`。

## 授權

MIT。改寫自並受益於 [AcidGr/agent-mobile-use](https://github.com/AcidGr/agent-mobile-use)
（MIT）；上游版權聲明保留在 `LICENSE` 中。
