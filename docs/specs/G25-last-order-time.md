# G25 — 最後點餐時間（last order）與即將打烊提示

規格版本 **v1.0**（2026-09-30，Claude 定案）
狀態：**待實作**
前置閘門：**無**（G14 PR #29、G19 PR #42、G23 PR #45 皆已合併進主線）
Flyway：**新增 `V11__branch_last_order.sql`**（V10 由 G19 占用；下一份需要 migration 的規格自 `V12` 起算）

---

## 1. 背景與目標

### 1.1 現況

營業時間這條線目前有兩層：

- **G14**（PR #29，2026-09-21，`V8`）—— `branch_hours` 每週固定時段。沒有時段列 = 24 小時營業
- **G19**（PR #42，2026-09-30，`V10`）—— `branch_day_overrides` / `branch_day_override_hours` 例外日。例外日整段取代當天的每週時段

兩者都只回答一個問題：**「現在是不是營業時間？」**（`BranchService.isOpenAt`，`BranchService.java:189-213`）。顧客自助下單走 `requireOrderable()`（`BranchService.java:152-161`），在營業時間內就放行，**營業結束前一秒送出的訂單一樣會成立**。

### 1.2 要擋的缺陷（具體的）

`OrderService.create()`（`OrderService.java:59-65`）只對顧客呼叫 `requireOrderable`：

```java
if (a.customer()) {
  branches.requireOrderable(q.branchId(), now);
} else {
  branches.requireOpen(q.branchId());   // 員工 POS 不受時段限制
  ...
}
```

於是 21:59 送進來的外帶單，在 22:00 打烊的門市是**合法且必須做**的訂單。現實裡咖啡機 21:45 就開始清洗，這杯做不出來。目前系統對這件事完全沒有表達能力，店家只有兩個選擇：

1. **把營業時間提早填 15 分鐘**（填 09:00–21:45）—— 代價是顧客端顯示「21:45 打烊」，21:50 走進店裡的客人會被門口的營業時間牌與 App 互相打臉，而且員工 POS 也看不出真正的打烊時間
2. **照收，然後打電話取消** —— 那是 `orders` 已經成立之後的事，牽動退款與稽核

兩個都是把系統的缺口轉嫁給現場。

### 1.3 順帶補的一個體驗缺口

同一份資料還能解掉 `GAP-ANALYSIS.md` P2 登記的另一半：**即將打烊提示**。顧客在 21:30 打開菜單，畫面上沒有任何訊號告訴他「再 15 分鐘就停止接單」，他慢慢挑完送出，收到的是一個冷冰冰的 400。後端只要順手回「距離停止接單還有幾分鐘」，前端就能在點餐前就講清楚。

**這兩件事共用同一個計算**（「現在距離本時段結束還有幾分鐘」），分開做會把同一段時間軸邏輯寫兩次，所以合成一份規格。

### 1.4 目標

1. 分店可設定「打烊前 N 分鐘停止接單」，**預設 0 = 行為與現在完全相同**
2. 顧客自助下單超過截止點 → 400，訊息講明最後點餐時間
3. 顧客端在**下單前**就看得到「距離停止接單還有 N 分鐘」與「已停止接單」
4. 員工 POS **不受影響**（與 G14 的既有決定一致，見 §13.4）

---

## 2. 範圍

### 2.1 在範圍內

- `branches` 新增一個分店層級的 `last_order_minutes` 欄位（`V11`）
- `BranchService` 新增一支**純函式** `windowAt(...)`，回答「現在是否營業，以及距離本時段結束還有幾分鐘」
- `requireOrderable()` 在截止點之後改丟 400，訊息含最後點餐時間
- `GET /api/branches`、`GET /api/branches/{id}/hours` 回應**新增**欄位，讓前端在下單前就知道狀態
- `PUT /api/branches/{id}/hours` 的 body **新增選填** `lastOrderMinutes`，由總部設定
- 前端：`BranchesView` 的營業時間編輯器加一個欄位；`MenuView` 顯示即將打烊／已停止接單並擋住送出
- `AUDIT` 紀錄沿用既有的 `BRANCH_HOURS_SAVE`

### 2.2 不在範圍內（逐項有理由，見 §13）

| 不做 | 理由所在 |
| --- | --- |
| 每個時段各自的最後點餐時間 | §13.1 |
| 例外日各自的最後點餐時間 | §13.2 |
| 員工 POS 也受截止點限制 | §13.4 |
| 「即將打烊」推播／通知 | §13.6 |
| 店長自行設定（仍是總部限定） | §13.5，仍掛在 G24 |
| 最後點餐時間影響已成立訂單的製作流程 | §13.7 |

---

## 3. 涉及模組與邊界

| 模組 | 變更 |
| --- | --- |
| `coffee-branches` | `api`：`Branches` 介面**新增**方法與一個 record；`internal`：`BranchService` / `BranchController` |
| `coffee-orders` | **零變更**。`OrderService.java:61` 已經呼叫 `requireOrderable`，語意擴充在 `coffee-branches` 內完成 |
| `coffee-app` | 新增 `V11` migration 與測試 |
| 前端 `modules/branches` | `BranchesView.vue` |
| 前端 `modules/ordering` | `MenuView.vue` |
| 前端 `shared` | `types.ts` |

**邊界檢查**：不新增模組、不新增跨模組依賴、不引用任何他人的 `internal`。`coffee-orders` 對 `coffee-branches` 的依賴仍然只有 `branches.api`。`ModuleBoundariesTest` 不需要改。

**不新增任何依賴**（`AGENTS.md`「不得引入新框架或新依賴」）。

---

## 4. DB schema 與 migration

### 4.1 migration 檔名

`backend/coffee-app/src/main/resources/db/migration/V11__branch_last_order.sql`

```sql
ALTER TABLE branches ADD COLUMN last_order_minutes INTEGER NOT NULL DEFAULT 0;
ALTER TABLE branches ADD CONSTRAINT ck_branches_last_order
  CHECK(last_order_minutes BETWEEN 0 AND 120);
```

三點說明：

1. **`ALTER TABLE` 新增欄位是加法，不是修改既有 migration。** `AGENTS.md` 禁止的是改 `V1`–`V10` 的檔案內容，不是禁止後續 migration 動到同一張表
2. **`DEFAULT 0` 讓既有三家分店零遷移**，而 `0` 的語意就是「打烊當下才停止接單」＝ 現行行為。**S1 合併後系統行為一個位元都不會變**，這是 S1 能獨立合併的根據
3. **CHECK 取具名約束**（`ck_branches_last_order`），不要用匿名 CHECK。G07 已經踩過一次：`orders.total` 的匿名 `CHECK(total>0)` 在 H2 與 PostgreSQL 兩邊都沒有可靠的移除寫法，結果把「免費訂單」永久鎖死（見 `GAP-ANALYSIS.md` G07 段）。具名約束日後要放寬時 `ALTER TABLE ... DROP CONSTRAINT ck_branches_last_order` 兩邊都work

### 4.2 為什麼上限是 120

120 分鐘 = 打烊前兩小時。比這更早停止接單的營業模式（例如「午餐只賣到 13:00 但店開到 17:00」）應該用**兩個時段**表達，那是 `branch_hours` 本來就支援的（每天最多 4 段）。上限擋的是把分鐘誤填成小時之類的輸入錯誤 —— 填 `1400` 會讓分店整天無法接單，而且症狀（顧客端一直說停止接單、後台看起來營業中）很難聯想到這個欄位。

### 4.3 `branches` 而不是 `branch_hours`

見 §13.1。一句話版本：一個分店一個數字，**例外日自動適用**，`Branches.Hours` record 不用動，前端的時段格子不用動。

---

## 5. 技術設計

### 5.1 核心：一支純函式 `windowAt`

現行 `BranchService.isOpenAt(Resolver, long)`（`BranchService.java:189-213`）回一個 `boolean`。它已經在時間軸上找到了「命中哪一個時段」，只是把那個資訊丟掉了。G25 要的正是那個資訊。

**在 `BranchService` 新增（與 `DaySchedule` 並列，同樣是 `public static`，可直接單元測試，不需要 Spring context）：**

```java
public sealed interface OpenWindow {
  /** 目前不在任何營業時段內。 */
  record NotOpen() implements OpenWindow {}
  /** 24 小時營業（完全沒有每週時段列），沒有「本時段結束」這回事。 */
  record NoClosingTime() implements OpenWindow {}
  /** 目前在營業時段內，距離本時段結束還有 minutes 分鐘（minutes >= 1）。 */
  record ClosesIn(int minutes) implements OpenWindow {}
}

public static OpenWindow windowAt(Resolver resolver, long atEpochMs);
```

`windowAt` 的判斷順序**必須與現行 `isOpenAt` 逐句對應**，只是把「回 true」換成「回還剩幾分鐘」：

| 現行 `isOpenAt` | `windowAt` |
| --- | --- |
| `current instanceof Closed` → `false` | → `NotOpen()` |
| `current instanceof AlwaysOpen` → `true` | → `NoClosingTime()` |
| 今日時段 `close > open` 且 `open <= m < close` → `true` | → `ClosesIn(close - m)` |
| 今日時段 `close <= open`（跨夜）且 `m >= open` → `true` | → `ClosesIn(close + 1440 - m)` |
| `periods.fromOverride()` → `false` | → `NotOpen()` |
| 昨日跨夜時段 `close <= open` 且 `m < close` → `true` | → `ClosesIn(close - m)` |
| 其餘 → `false` | → `NotOpen()` |

**跨夜時段的 `close + 1440 - m` 是本規格唯一一處容易寫錯的算術。** 例：`open=1320`（22:00）、`close=120`（隔日 02:00）、現在 `m=1380`（23:00）→ 剩 `120 + 1440 - 1380 = 180` 分鐘，正確。若寫成 `close - m` 會得到 `-1260`，而負數在下游會被誤判成「早就過了截止點」，症狀是**跨夜營業的店晚上十一點就不能點餐**。

**`isOpenAt` 必須改寫成 `windowAt` 的包裝**，不要留兩份平行邏輯：

```java
public static boolean isOpenAt(Resolver resolver, long atEpochMs) {
  return !(windowAt(resolver, atEpochMs) instanceof OpenWindow.NotOpen);
}
```

這一步是 S1 的重點，也是 S1 唯一有風險的地方 —— 所以 S1 的驗收有一條「等價性掃描測試」（驗收 4）。

### 5.2 由 `OpenWindow` 導出三個對外的值

```java
record OpenState(boolean openNow, boolean orderableNow, Integer minutesUntilLastOrder) {}
```

給定 `OpenWindow w` 與分店的 `lastOrderMinutes L`：

| `w` | `openNow` | `orderableNow` | `minutesUntilLastOrder` |
| --- | --- | --- | --- |
| `NotOpen` | `false` | `false` | `null` |
| `NoClosingTime` | `true` | `true` | `null` |
| `ClosesIn(m)`，`m > L` | `true` | `true` | `m - L` |
| `ClosesIn(m)`，`m <= L` | `true` | **`false`** | `null` |

三件要講清楚的事：

1. **`openNow` 的語意一個字都沒變。** 過了最後點餐時間、還沒打烊的分店，`openNow` 仍然是 `true`（店確實開著，員工還在、客人還能進來坐）。**不要**順手把 `openNow` 改成「可下單」—— 理由見 §13.3
2. **`L = 0` 時 `ClosesIn(m)` 的 `m` 至少是 1**（時段內任一分鐘距離結束都還有 ≥1 分鐘），所以 `m > 0` 恆真，`orderableNow` 恆等於 `openNow`。**這就是「S1 行為零變更」的形式證明**
3. **時段長度短於 `L` 時，該時段整段都不可下單**（`m <= L` 恆成立）。這是定義，不是缺陷。存檔時不做跨表驗證去阻止它（見 §13.8），但驗收 10 要求有測試釘住這個行為

### 5.3 `Branches` 介面的變更（全部是加法）

```java
// 新增
record OpenState(boolean openNow, boolean orderableNow, Integer minutesUntilLastOrder) {}

int lastOrderMinutes(String branchId);
OpenState stateAt(String branchId, long atEpochMs);
Map<String, OpenState> stateAt(List<String> branchIds, long atEpochMs);
List<Hours> saveHours(Actor actor, String branchId, List<Hours> hours, int lastOrderMinutes);
```

**既有的四支方法一律保留、簽章一個字都不改**：

- `boolean openAt(String, long)`、`Map<String,Boolean> openAt(List<String>, long)` —— 改為 `stateAt(...)` 的包裝（取 `openNow`）
- `List<Hours> saveHours(Actor, String, List<Hours>)` —— 改為委派到四參數版，`lastOrderMinutes` 帶入**該分店目前存的值**（不是 0，否則呼叫舊簽章會靜默清掉設定）
- `Branch requireOrderable(String, long)` —— 簽章不變，內部語意擴充

**`Branches.Branch` 與 `Branches.Hours` 兩個 record 完全不動。** `BranchService.row()`（`BranchService.java:30-38`）是逐欄位取值不是 `SELECT *` 映射，新欄位不會自己跑進 `Branch`；`save()`（`484-517`）的 `update branches set name=?,...` 沒有列到 `last_order_minutes`，所以**存分店基本資料不會覆寫最後點餐設定**。這兩點各自要有測試（驗收 12）。

保留舊簽章不是為了相容外部使用者（本 repo 沒有外部使用者），是為了讓 **S1 不必動 `BranchHoursTest` / `BranchHourOverrideTest` / `BranchHoursAdminTest` 三支既有測試**。既有測試零修改，才有資格說「S1 行為零變更」。

### 5.4 `requireOrderable` 的新行為

```java
public Branch requireOrderable(String id, long atEpochMs) {
  Branch branch = requireOpen(id);                    // 不變
  ... 組出 resolver ...                                // 不變
  OpenWindow window = windowAt(resolver, atEpochMs);
  int last = lastOrderMinutes(id);
  if (window instanceof OpenWindow.NotOpen)
    throw new Problem(400, closedMessage(resolver.resolve(today)));   // 不變，四個既有訊息原封不動
  if (window instanceof OpenWindow.ClosesIn closes && closes.minutes() <= last)
    throw new Problem(400, lastOrderMessage(atEpochMs, closes.minutes(), last));   // 新增
  return branch;
}
```

**四個既有的打烊訊息一個字都不能改**（`BranchHourOverrideTest.java:356-375` 釘住了它們）：`分店今日公休`、`分店今日公休（<note>）`、`分店今日未營業`、`分店目前未營業（今日營業時間 …）`。

新訊息：

```java
private static String lastOrderMessage(long atEpochMs, int minutesUntilClose, int lastOrderMinutes) {
  int nowMinute = Instant.ofEpochMilli(atEpochMs).atZone(TAIPEI).getHour() * 60
                + Instant.ofEpochMilli(atEpochMs).atZone(TAIPEI).getMinute();
  int cutoff = Math.floorMod(nowMinute + minutesUntilClose - lastOrderMinutes, 1440);
  return "分店已停止接單（最後點餐時間 " + formatMinute(cutoff) + "），請於明日營業時間再下單";
}
```

為什麼用 `nowMinute + m - L` 而不是 `close - L`：`close` 在跨夜時段是隔日的分鐘數，直接減會得到負數或錯的日內分鐘。`nowMinute + m` 恆等於「以今天 0 點為原點的結束時刻」，再減 `L` 之後用 `Math.floorMod` 歸位，跨夜與不跨夜兩種情況用同一行處理。`formatMinute` 沿用既有的（`BranchService.java:235-238`）。

> **注意 `formatMinute(1440)` 會回 `"24:00"`，但 `Math.floorMod(..., 1440)` 永遠不會產出 1440**，所以截止點剛好是午夜時會顯示 `00:00` 而不是 `24:00`。這是可接受的，不要為此特別處理 —— 多一個分支就多一個要測的路徑，而 `00:00` 對使用者並不難懂。

### 5.5 `saveHours` 的四參數版

```java
@Transactional
public List<Hours> saveHours(Actor actor, String branchId, List<Hours> schedule, int lastOrderMinutes) {
  actor.require("BRANCH_MANAGE");
  if (!actor.global()) throw new Problem(403, "此功能限總部範圍");
  Problem.check(schedule != null, "請提供營業時段");
  Problem.check(lastOrderMinutes >= 0 && lastOrderMinutes <= 120, "最後點餐提前時間需為 0–120 分鐘");
  ... lockBranch(branchId) ... validateHours(schedule) ...
  db.update("update branches set last_order_minutes=? where id=?", lastOrderMinutes, branchId);
  ... 既有的 delete + insert 迴圈不變 ...
  audit.record(actor, "BRANCH_HOURS_SAVE", branchId, branchId,
      "更新營業時間（" + schedule.size() + " 段，最後點餐提前 " + lastOrderMinutes + " 分）");
  return loadHours(branchId);
}
```

- **驗證在 `Problem.check`，不是只靠 DB 的 CHECK。** 靠 CHECK 會得到英文的 `DataIntegrityViolationException` 訊息，違反「錯誤訊息一律繁體中文」
- **`update branches` 與既有的 `delete/insert branch_hours` 在同一個 `@Transactional` 內**，而且都在 `lockBranch()` 取到 `select ... for update` 之後。時段與截止點必須一起生效，不能出現「時段換了但截止點還是舊的」的中間狀態
- **不新增 audit action 常數**，沿用 `BRANCH_HOURS_SAVE`，只在 summary 多一段文字。新增 action 需要同步更新稽核查詢的篩選清單，代價大於收益

### 5.6 `BranchController` 的回應變更（全部是加法）

```java
record BranchResponse(String id, String name, String address, String phone,
    boolean active, int monthlyTarget, boolean openNow,
    boolean orderableNow, Integer minutesUntilLastOrder) {}          // 後兩項新增

record HoursResponse(String branchId, boolean openNow, List<Branches.Hours> hours,
    int lastOrderMinutes, boolean orderableNow, Integer minutesUntilLastOrder) {}  // 後三項新增

record HoursRequest(List<Branches.Hours> hours, Integer lastOrderMinutes) {}       // 後一項新增，選填
```

- `list()` 把 `service.openAt(ids, now)` 換成 `service.stateAt(ids, now)`，**一次查詢取三個值**，不要為了 `orderableNow` 再掃一次資料庫
- `HoursRequest.lastOrderMinutes` 為 `null` 時，**沿用該分店目前存的值**（不是 0）。理由：舊版前端或任何漏帶欄位的呼叫不該靜默清掉設定。這條要有測試（驗收 11）
- `GET /api/branches/{id}/hours` **不需要權限**（維持現狀，顧客要看營業時間）；`minutesUntilLastOrder` 也是給顧客看的，沒有敏感資訊

### 5.7 前端

**`shared/types.ts`**（加欄位，不改既有欄位）：

```ts
export interface Branch { /* ...既有... */ openNow: boolean; orderableNow: boolean; minutesUntilLastOrder: number | null }
export interface BranchHoursResponse { branchId: string; openNow: boolean; hours: BranchHours[];
  lastOrderMinutes: number; orderableNow: boolean; minutesUntilLastOrder: number | null }
```

**`BranchesView.vue`**：營業時間 Modal 內加一個數字欄位

- `label` 文字：`最後點餐（打烊前幾分鐘停止接單）`
- `<input type="number" min="0" max="120" step="5">`，綁 `lastOrderMinutes` ref
- `editHours()` 讀 `hoursResult.lastOrderMinutes` 填進去（`BranchesView.vue:93-110`）
- 送出時 body 變成 `{ hours: hours.value, lastOrderMinutes: lastOrderMinutes.value }`（`BranchesView.vue:216-219`）
- 欄位旁一行說明：`0 = 打烊當下才停止接單`

**`MenuView.vue`**：

1. `customerClosed`（`MenuView.vue:94-96`）維持現狀不動 —— 它管的是「打烊」
2. **新增** `customerNotOrderable = computed(() => auth.customer && !!branch.value && !branch.value.orderableNow)`
3. 結帳按鈕的 `:disabled`（`MenuView.vue:686`）與商品卡的 `:aria-disabled`（`504`）把 `customerClosed` 換成 `customerNotOrderable`
   —— **`customerNotOrderable` 在 `customerClosed` 為真時必為真**（`openNow=false ⇒ orderableNow=false`），所以這是嚴格放寬觸發條件，不會漏掉原本擋住的情況
4. `hours-status` 區塊（`433-443`）新增一個中間態：
   - 已打烊 → 現狀不變
   - `orderableNow === false` 且 `openNow === true` → `<strong>已停止接單</strong>` + `今日已過最後點餐時間，歡迎明日再來`
   - `orderableNow === true` 且 `minutesUntilLastOrder !== null && minutesUntilLastOrder <= 30` → `<strong>即將停止接單</strong>` + `距離最後點餐還有 {{ minutesUntilLastOrder }} 分鐘`
   - 其餘 → 現狀不變
5. `notify` 訊息（`MenuView.vue:231-232`）比照新增一則：停止接單時提示 `分店已過最後點餐時間，請於明日營業時間再下單`

**30 分鐘這個門檻是前端常數，不是後端欄位。** 後端只回事實（還有幾分鐘），要不要提示、幾分鐘開始提示是呈現決策。寫成 `const LAST_ORDER_WARNING_MINUTES = 30` 放在 `MenuView.vue` 的 script 頂端，改它不需要動後端。

---

## 6. API

**不新增端點。** 三支既有端點的回應加欄位、一支的請求加選填欄位。

| 端點 | 變更 | 權限 |
| --- | --- | --- |
| `GET /api/branches` | 回應每筆多 `orderableNow`、`minutesUntilLastOrder` | 不變（登入即可） |
| `GET /api/branches/{id}/hours` | 回應多 `lastOrderMinutes`、`orderableNow`、`minutesUntilLastOrder` | 不變（不需權限） |
| `PUT /api/branches/{id}/hours` | 請求多選填 `lastOrderMinutes` | 不變（`BRANCH_MANAGE` + `global()`） |
| `POST /api/orders` | **請求與回應零變更**，只是顧客在截止點後會收到 400 | 不變 |

### 6.1 錯誤碼

| 情境 | 狀態碼 | 訊息 |
| --- | --- | --- |
| 顧客在最後點餐時間之後下單 | 400 | `分店已停止接單（最後點餐時間 HH:mm），請於明日營業時間再下單` |
| `lastOrderMinutes` 超出 0–120 或非整數 | 400 | `最後點餐提前時間需為 0–120 分鐘` |
| 非總部呼叫 `PUT /hours` | 403 | `此功能限總部範圍`（既有） |
| 分店不存在 | 404 | `找不到分店`（既有） |

**既有的四個打烊訊息一字不改**（§5.4）。

---

## 7. 權限與資料範圍

**不新增權限常數。** `Identity.PERMISSIONS`（`Identity.java:7-21`）維持 13 個，不需要 `role_permissions` 的 migration。

| 操作 | 權限 | 資料範圍 |
| --- | --- | --- |
| 設定 `lastOrderMinutes`（`PUT /hours`） | `BRANCH_MANAGE` | **`GLOBAL`**（`actor.global()`，與 `saveHours` 現行逐字相同） |
| 讀 `GET /branches/{id}/hours` | 無 | 全體可讀（維持現狀） |
| 讀 `GET /branches` | 登入即可 | 依既有 `list()` 規則 |
| 下單受截止點限制 | —— | **只有 `actor.customer()`（`scope = SELF`）** |

**總部限定的理由與 G19 §13.3 完全相同**，不在這裡重複；G24（店長自行設定本店例外營業日）若日後升排，應**一併**把最後點餐時間納入店長可改的範圍，兩者是同一類設定。這一點寫進 G24 的登記裡（§13.5）。

**越權測試（驗收 14，缺一不可）：**

1. `CASHIER`（`InitialData.java:39-44`，**沒有** `BRANCH_MANAGE`）呼叫 `PUT /hours` 帶 `lastOrderMinutes` → 403（由 `actor.require("BRANCH_MANAGE")` 擋下）
2. 自訂一個 `scope = BRANCH` **但有** `BRANCH_MANAGE` 的測試帳號呼叫 → 403 `此功能限總部範圍`（由 `actor.global()` 擋下）。**這兩條是不同的防線，缺一條就少測一層**
3. 顧客（`scope = SELF`）呼叫 → 403
4. 未登入呼叫 → 401
5. **越權失敗後 `last_order_minutes` 的值必須沒有變**（403 之後再讀一次確認）—— 只斷言狀態碼會漏掉「擋了但已經寫進去」這類缺陷

---

## 8. 金額規則

**本規格不計算、不讀取、不傳輸任何金額。**

一條必須守住的紅線：`POST /api/orders` 的請求與回應**零變更**。最後點餐時間是**下單前的閘門**，不是折扣、不是加價、不會出現在任何金額計算路徑上。實作時若發現自己動到 `OrderService` 的金額迴圈（`OrderService.java:67-80`），那代表走錯路了 —— 本規格對 `coffee-orders` 的變更量應該是**零**。

G23 已經把「`POST /api/orders` 的 body 鍵集合白名單」寫成前端測試；本規格不得讓那條測試變紅。若它紅了，代表有人在前端 payload 裡加了東西（驗收 16）。

---

## 9. 施工階段

三個階段，**全部是加法**。每階段獨立 CI 綠、獨立可合併、有自己的驗收子集。

### S1 — 資料層與 `windowAt`（規模：小到中）

**動到的檔案**：`V11__branch_last_order.sql`（新增）、`Branches.java`、`BranchService.java`、`BranchController.java`、新增 `BranchLastOrderTest.java`

1. 寫 `V11`（§4.1）
2. `BranchService` 新增 `OpenWindow` 與 `windowAt(...)`（§5.1），**把 `isOpenAt(Resolver, long)` 改寫成 `windowAt` 的包裝**
3. `Branches` 新增 `OpenState`、`lastOrderMinutes(String)`、`stateAt(String, long)`、`stateAt(List<String>, long)`（§5.3）；既有 `openAt` 兩支改為 `stateAt` 的包裝
4. `BranchController` 的 `BranchResponse` / `HoursResponse` 加欄位（§5.6），`list()` 改用 `stateAt`
5. **不改 `requireOrderable` 的行為**，不加 `saveHours` 的四參數版，前端一行都不動

S1 合併後，`last_order_minutes` 全部是 0，`orderableNow` 恆等於 `openNow`，**系統行為零變更**。新欄位對前端是多餘但無害的 JSON（TypeScript 的 `interface` 不會因為多欄位而失敗）。

**S1 驗收子集**：1、2、3、4、5、12、17、18、19

### S2 — 截止點強制與總部設定（規模：中）

**動到的檔案**：`Branches.java`、`BranchService.java`、`BranchController.java`、`BranchLastOrderTest.java`（續寫）

1. `saveHours` 四參數版（§5.5），三參數版委派
2. `HoursRequest` 加選填 `lastOrderMinutes`，`null` → 沿用現值（§5.6）
3. `requireOrderable` 加截止點判斷與 `lastOrderMessage`（§5.4）
4. 越權測試全套（§7）

S2 合併後後端完整可用：總部設得了、顧客擋得住。**前端還沒接，所以顧客會在按下結帳時才收到 400** —— 這個中間狀態是可接受的（比現在好：現在根本擋不住），且不破壞任何既有行為。

**S2 驗收子集**：6、7、8、9、10、11、13、14、17、18、19

### S3 — 前端設定與提示（規模：中）

**動到的檔案**：`shared/types.ts`、`modules/branches/BranchesView.vue`、`modules/ordering/MenuView.vue`、`modules/ordering/MenuView.dom.test.ts`（續寫）

1. `types.ts` 加欄位（§5.7）
2. `BranchesView` 的數字欄位與送出 body
3. `MenuView` 的 `customerNotOrderable`、三態 `hours-status`、按鈕 disabled、notify 訊息
4. 補 G23 風格的元件測試（§11.2）

**S3 驗收子集**：15、16、17、18、19、20

### 階段切分的理由

S1 是「時間軸的算術對不對」，S2 是「閘門擋不擋得住、誰設得了」，S3 是「顧客看不看得到」。三者紅燈的原因完全不同。**S1 是唯一有回歸風險的階段**（它改寫了 `isOpenAt` 的內部），所以刻意讓它不帶任何行為變更 —— 一旦 S1 之後 `BranchHoursTest` / `BranchHourOverrideTest` / `BranchHoursAdminTest` 有任何一支紅了，就知道是 `windowAt` 的改寫寫壞了，不必在三件事裡猜。

**這三階段切得開，不需要預留完整一次執行。** 若額度只夠一階段，做完 S1 就推。

---

## 10. 驗收條件

逐條可勾選。括號內是所屬階段。

- [ ] 1. （S1）`V11__branch_last_order.sql` 存在，內容只有兩條 `ALTER TABLE`；`V1`–`V10` 的檔案內容零修改
- [ ] 2. （S1）CHECK 約束具名為 `ck_branches_last_order`；H2 與 PostgreSQL 皆可建立
- [ ] 3. （S1）`windowAt` 是 `public static`，**不需要 Spring context 就能單元測試**
- [ ] 4. （S1）**等價性掃描測試**：對「每週單段、每週雙段、跨夜、例外日時段、例外日公休、無時段（24 小時）」六種排程，把一天 1440 分鐘逐分鐘掃過，斷言 `windowAt(...) instanceof NotOpen` 與改寫前的 `isOpenAt` 結果**完全一致**（改寫前的期望值直接寫死在測試裡，不要呼叫 `isOpenAt` 自我比對）
- [ ] 5. （S1）既有的 `BranchHoursTest`、`BranchHourOverrideTest`、`BranchHoursAdminTest` **內容零修改**且全綠
- [ ] 6. （S2）跨夜時段的 `ClosesIn` 分鐘數正確：`open=1320`、`close=120`、現在 `23:00` → `ClosesIn(180)`；`L=30` 時 `01:45` 不可下單、`01:15` 可下單
- [ ] 7. （S2）`L=0` 時 `orderableNow` 與 `openNow` 在上述六種排程、逐分鐘掃描下**完全相等**
- [ ] 8. （S2）顧客在截止點之後 `POST /api/orders` → 400，訊息**逐字**為 `分店已停止接單（最後點餐時間 HH:mm），請於明日營業時間再下單`
- [ ] 9. （S2）四個既有打烊訊息**逐字不變**（`BranchHourOverrideTest.java:356-375` 仍綠）
- [ ] 10. （S2）時段長度 ≤ `L` 時（例如時段 `10:00–10:20`、`L=30`），該時段內任一分鐘皆不可下單，且 `openNow` 仍為 `true`
- [ ] 11. （S2）`PUT /hours` 的 body **不帶** `lastOrderMinutes` 時，分店原有的值不變（先設 15，再送一次不帶該欄位的 PUT，讀回來仍是 15）
- [ ] 12. （S1／S2）`POST /api/branches`（存分店基本資料）**不會**覆寫 `last_order_minutes`（先設 15，再存一次分店名稱，讀回來仍是 15）
- [ ] 13. （S2）`lastOrderMinutes` 為 `-1`、`121`、非整數 → 400 且訊息為 `最後點餐提前時間需為 0–120 分鐘`；**資料庫的值沒有變**
- [ ] 14. （S2）§7 的五條越權測試全綠，含「403 之後值沒有被寫進去」
- [ ] 15. （S3）`BranchesView` 可設定並存檔；重開 Modal 讀得回剛存的值
- [ ] 16. （S3）G23 的 `POST /api/orders` body 鍵集合白名單測試**仍然全綠**（前端沒有多送任何欄位）
- [ ] 17. （全階段）`ModuleBoundariesTest` 綠；`coffee-orders` 的變更量為**零**
- [ ] 18. （全階段）`cd backend && ./mvnw -B -ntp verify` 綠
- [ ] 19. （全階段）`git ls-files -s backend/mvnw scripts/build.sh start-demo.sh` 三個都是 `100755`
- [ ] 20. （S3）`cd frontend && npm ci && npm run build && npm run test` 綠

---

## 11. 測試要求

### 11.1 後端

放在 `backend/coffee-app/src/test/java/com/coffee/app/BranchLastOrderTest.java`。

**優先寫不需要 Spring context 的單元測試**（`AGENTS.md`「測試」）：`windowAt` 與 `OpenState` 的導出規則都是純函式，逐分鐘掃描 1440 個點在單元測試裡是毫秒級的事，放進 Spring context 測試會慢兩個數量級。

必測清單：

1. **等價性掃描**（驗收 4）—— 這是 S1 唯一的安全網
2. **跨夜算術**（驗收 6）—— `close + 1440 - m`
3. **`L = 0` 的行為恆等**（驗收 7）
4. **時段短於 `L`**（驗收 10）
5. **例外日**：例外日的時段一樣套用 `L`；例外日公休時 `orderableNow = false` 且訊息是既有的公休訊息（不是停止接單訊息）
6. **24 小時營業**（無每週時段列）：`NoClosingTime` → 永遠可下單，`L` 設多少都一樣
7. **員工 POS 不受限**：同一時刻，`CASHIER` 建單成功、顧客建單 400（同一支測試裡對照，最有說服力）
8. **越權五條**（§7）
9. **交易性**：`saveHours` 中途丟 `Problem`（例如時段重疊）時，`last_order_minutes` 也要一起回滾

### 11.2 前端

續寫 G23 建立的 `MenuView.dom.test.ts`（`.dom.test.ts` 由 dom project 撿走）：

1. `orderableNow = false`、`openNow = true` → 結帳按鈕 disabled，且畫面出現「已停止接單」
2. `orderableNow = true`、`minutesUntilLastOrder = 20` → 畫面出現「即將停止接單」與數字 `20`，按鈕**未** disabled
3. `orderableNow = true`、`minutesUntilLastOrder = 45` → **不**出現「即將停止接單」
4. `openNow = false` → 維持 G23 既有案例的行為（按鈕 disabled）

沿用 G23 的規矩：「不存在」用 `toBeNull()` / `exists() === false`，**不用 `isVisible()`**；不斷言 `money()` 的字面輸出；每個 `it` 自己 `mountView`。

**本規格會改到 `MenuView.vue` 的打烊訊息區塊**，所以 G23 §12 那條「不得斷言打烊訊息文字」的約束在本規格**解除** —— 改文字的人就是本規格自己，測試跟著一起改是正確的。

---

## 12. 與其他工作的並行注意

| 對象 | 交集 | 處理 |
| --- | --- | --- |
| G23（PR #45） | `MenuView.dom.test.ts` | **已解除** —— PR #45 於 2026-09-30 合併，`MenuView.dom.test.ts` 已在主線上，S3 直接續寫即可 |
| G24（未開工） | `saveOverride` 的授權 | 零交集。G24 動的是例外日的授權，G25 動的是 `branches` 的一個欄位 |
| 任何新 migration | Flyway 版號 | **G25 占用 `V11`**。下一份需要 migration 的規格自 `V12` 起算 |

`V11` 的占用以本規格合併進主線的時間點為準。若有其他規格同時在寫，先合併的取 `V11`，後者改 `V12` 並更新自己的規格 —— **不要兩份都寫 `V11` 然後在實作時才發現**。

---

## 13. 設計決策

每一條都是 Claude 定案，附理由與推翻它的代價。決策是給下一輪推翻用的。

### 13.1 最後點餐時間是**分店層級**的一個數字，不是每個時段各一個 —— **分店層級**

**決定**：`branches.last_order_minutes`，一個分店一個值，套用到**所有**時段（每週的與例外日的）。

**理由**：四點。

1. **語意上它本來就是分店層級的。** 「打烊前 15 分鐘清機器」是這家店的作業流程，不是「週二下午那一段」的性質。每段各設一個值，第一個要回答的問題就是「為什麼週二下午跟週三下午不一樣」—— 那個問題通常沒有答案
2. **例外日自動適用，一行程式都不用寫。** 若放在 `branch_hours`，例外日的時段在 `branch_day_override_hours`，那張表也得加同一個欄位，`saveOverride` 也得驗證，前端的例外日編輯器也得加欄位。等於把同一件事做三次
3. **`Branches.Hours` record 不用動。** `Hours` 有 3 個 component，加第 4 個會打斷 `BranchService` 內至少 6 處 `new Hours(...)`、`BranchController` 的兩個回應 record、前端的 `BranchHours` 型別與時段格子 UI。那是一個又大又不可分割的破壞性變更，正是 `AGENTS.md`「盡量用加法」要避開的形狀
4. **UI 成本天差地別。** 分店層級是**一個**輸入框；每段各一個是**每天最多 4 段 × 7 天 = 28 個**輸入框旁邊再各加一個

**這個決定放棄了什麼**（要誠實寫下來）：**「平日 21:45 停、週末 22:45 停」這種需求做不到。** 但注意它其實做得到 —— 用不同的 `close_minute` 就好（平日開到 22:00、週末開到 23:00，`L=15` 兩邊都成立）。真正做不到的是「同樣的打烊時間、不同的提前量」，例如「週末人手多，只提前 5 分鐘」。那是真實但少見的需求。

**推翻它的代價**：**中等，而且是加法。** 要改成每段各一個，做法是在 `branch_hours` 與 `branch_day_override_hours` 各加一個**可為 NULL** 的 `last_order_minute`，`NULL` 時沿用分店層級的值。`Hours` record 加第 4 個 component 仍是破壞性的，但屆時可以用「新增一個 `HoursDetail` record 給需要的路徑、`Hours` 保留」來閃過。兩層設定的優先序規則（段 > 分店）與 G19 的「例外日 > 每週」同形，不是新概念。

### 13.2 例外日不能有自己的最後點餐時間 —— **不能**

**決定**：例外日套用分店層級的同一個值。

**理由**：這是 §13.1 的直接推論。額外一點：**例外日最常見的用途是公休**（`closed = true`），而公休日根本沒有時段，最後點餐時間無從談起。剩下的「改用當天時段」情境，店家想提早收單只要把 `close_minute` 填早一點就好 —— 例外日的時段本來就是當天現填的。

**推翻它的代價**：低，見 §13.1 的推翻路徑，`branch_day_override_hours` 那一半。

### 13.3 `openNow` 的語意不變，另開 `orderableNow` —— **不合併成一個**

**決定**：`openNow` 仍然是「店開著嗎」，新增 `orderableNow` 表示「現在能不能下單」。

**理由**：三點。

1. **它們是兩件事，而且兩件都有人用。** 21:50（22:00 打烊、`L=15`）時，門是開的、店員在、內用客人還在喝 —— `openNow = false` 會讓分店在顧客端的清單上顯示「已打烊」，那是錯的資訊
2. **`openNow` 已經有既有的契約。** `GET /api/branches` 的 `openNow` 被 `MenuView.vue:428` 用來顯示「營業中／已打烊」的分店選單標記，`BranchHoursTest.java:84` 與 `BranchHoursAdminTest.java:64` 也釘住了它。改它的語意等於改一個已經有人依賴的欄位的意思，**而且改完編譯照樣過、測試照樣綠，錯誤會以「顧客覺得店提早打烊了」的形式在現場出現**
3. **`orderableNow` 是純加法**，舊前端不讀它就完全沒感覺

**這個決定放棄了什麼**：多一個欄位要維護，而且兩個布林值的關係（`!openNow ⇒ !orderableNow`）是一條要記住的不變式。§5.2 的表格與驗收 7 就是為了把這條不變式釘在測試裡。

**推翻它的代價**：高。合併之後要再拆開，得回頭查每一個讀 `openNow` 的地方當初想問的是哪一件事 —— 那個資訊到時候已經不在程式碼裡了。

### 13.4 員工 POS 不受最後點餐時間限制 —— **不受限**

**決定**：`OrderService.create()` 的分支維持原樣，只有 `a.customer()` 走 `requireOrderable`。

**理由**：與 G14 當初「時段只對顧客自助下單強制，員工 POS 不受限」的決定同源。現場的事實是：**最後點餐時間是給顧客的承諾，不是給店員的禁令。** 21:55 走進來的熟客，店員判斷還做得出來就做 —— 那個判斷只有站在機器前的人做得了。系統把它變成硬規則，結果一定是店員找別的方式繞過去（開一張明天的單、收現金不入帳），那比沒擋更糟。

**這個決定放棄了什麼**：無法用系統強制「全店 21:45 之後一律不收單」。若某天營運端需要那個強制力（例如加盟店稽核），那是另一個需求。

**推翻它的代價**：低，而且是加法 —— 在 `else` 分支多呼叫一次 `requireOrderable`，或加一個「是否對 POS 也強制」的分店開關。但**推翻前要先想清楚店員怎麼處理例外**，否則等於把問題趕到系統外面。

### 13.5 仍是總部限定 —— **總部限定，併入 G24**

**決定**：`PUT /api/branches/{id}/hours` 的授權一個字不改（`BRANCH_MANAGE` + `actor.global()`）。

**理由**：與 G19 §13.3 完全相同，不重複。額外一點：最後點餐時間與營業時段是**同一支端點**存的，把其中一個欄位開放給店長、另一個不開放，會做出一個「同一個 PUT 裡有些欄位你改得了有些改不了」的授權模型 —— 那是最難測也最容易寫錯的形狀。

**這個決定放棄了什麼**：店長不能依當天人力調整收單時間（今天少一個人，想早點收），只能打電話給總部。

**推翻它的代價**：低，而且與 G24 是同一筆工作。**G24 若升排，應把最後點餐時間一併納入店長可改的範圍** —— 兩者都是「這家店今天怎麼營業」，分開授權沒有道理。本條登記進 G24 的範圍。

### 13.6 不做即將打烊的推播／通知 —— **不做**

**決定**：只有顧客打開菜單時看得到提示，不主動推播。

**理由**：推播需要顧客裝置的 token、需要訂閱管理、需要一個會在固定時間醒來的排程作業 —— 本專案目前**沒有任何排程基礎設施**（G13 刻意設計成「售完標記記在營業日上、隔日自動失效」就是為了不引入排程）。為了一則提示引進整套排程，代價遠大於收益。

**推翻它的代價**：高。它不是加一個欄位，是加一整層基礎設施（排程、訂閱、送達重試）。真要做，應該獨立成自己的缺口編號，不要夾在本規格裡。

### 13.7 最後點餐時間不影響已成立的訂單 —— **不影響**

**決定**：截止點只擋 `POST /api/orders`。已經是 `PENDING_PAYMENT` 的訂單，之後的付款、狀態轉換、現金收款一律不受影響。

**理由**：訂單一旦成立就是一筆債權債務關係，用「現在過了最後點餐時間」去擋它的付款，只會製造一批收不了錢的孤兒單。`OrderService.transition()` 與 `cash()` 的行為本規格一律不碰。

**推翻它的代價**：不該推翻。若真的需要「打烊後自動取消未付款訂單」，那是訂單逾時的議題（與最後點餐時間無關），應獨立立案。

### 13.8 存檔時不驗證「`L` 是否會讓某個時段整段不可下單」 —— **不驗證**

**決定**：`saveHours` 只驗 `L` 在 0–120，不去比對每個時段的長度。

**理由**：兩點。

1. **要驗就得跨表讀全部時段，而且得在每次改時段時也重驗一次**（改 `L` 要驗時段、改時段也要驗 `L`）。例外日更麻煩 —— 例外日是未來 400 天內任何一天都可能新增的，存 `L` 的當下驗不到還不存在的例外日。**驗證的涵蓋範圍做不到完整，做一半的驗證只會給人「已經驗過了」的錯覺**
2. **「時段短於 `L`」在現實裡幾乎只會出現在例外日**（例如颱風天只開 10:00–10:20 讓員工收拾），而那正是驗不到的那一半

**這個決定放棄了什麼**：總部可能存下一組讓某天整天不能線上下單的設定而當下不會被警告。**緩解方式在 UI 而不是 API**：`BranchesView` 的欄位旁顯示「目前設定下，最短的營業時段是 X 分鐘」由前端算（資料它手上就有），是提示不是阻擋。這一項列在 S3，但**不列入驗收條件** —— 它是體貼，不是正確性。

**推翻它的代價**：低。要改成硬驗證，加在 `saveHours` 與 `saveOverride` 兩處即可，但要接受「驗不到未來的例外日」這個天生的破口。

### 13.9 上限 120 分鐘、`DEFAULT 0` —— **定案**

**決定**：`CHECK(last_order_minutes BETWEEN 0 AND 120)`，預設 0。

**理由**：`DEFAULT 0` 是 S1「行為零變更」的根據（§5.2 第 2 點）。上限 120 的理由見 §4.2。下限 0 而不是 1：0 必須是合法值，否則既有分店無法表達「沒有最後點餐時間」。

**推翻它的代價**：**極低**，因為 CHECK 具名（§4.1 第 3 點）。`ALTER TABLE branches DROP CONSTRAINT ck_branches_last_order` 之後再加一條新的即可，H2 與 PostgreSQL 都支援。這正是 G07 在 `orders.total` 上付出代價才學到的教訓。

---

## 14. 修訂紀錄

| 版本 | 日期 | 變更 |
| --- | --- | --- |
| v1.0 | 2026-09-30 | 初版，Claude 定案。G19 §13.5 登記的 G25 正式立案 |
