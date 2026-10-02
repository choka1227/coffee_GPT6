# G24 — 店長自行設定本店例外營業日與本日最後點餐時間

規格版本 **v1.0**（2026-10-02，Claude 定案）
狀態：**待實作**
前置閘門：**無**（G14 PR #29、G19 PR #42、G25 PR #51、G27 PR #53 皆已合併進主線）
Flyway：**新增 `V12__branch_day_settings.sql`**（V11 由 G25 占用；下一份需要 migration 的規格自 `V13` 起算）

---

## 1. 背景與目標

### 1.1 現況

「這家店今天怎麼營業」目前由三張表決定：

- **G14**（PR #29，`V8`）—— `branch_hours`，每週固定時段。沒有時段列 = 24 小時營業
- **G19**（PR #42，`V10`）—— `branch_day_overrides` / `branch_day_override_hours`，例外日整段取代當天的每週時段
- **G25**（PR #51，`V11`）—— `branches.last_order_minutes`，打烊前幾分鐘停止接單（0–120）

三者的寫入路徑**全部是總部限定**，而且是同一句程式碼：

```java
actor.require("BRANCH_MANAGE");
if (!actor.global()) throw new Problem(403, "此功能限總部範圍");
```

出現在 `BranchService.java` 的 `saveHours`（兩個多載）、`saveOverride`、`deleteOverride`、`save`。`MANAGER` 角色（`InitialData.java:46-58`，scope `BRANCH`）**沒有** `BRANCH_MANAGE`，所以店長連 `/branches` 這一頁都看不到（`App.vue:64`、`main.ts` 的 `meta.permissions`）。

### 1.2 要擋的缺陷（具體的）

兩個，都已經被前一輪規格寫下來並明文登記到本缺口：

**(a) 颱風天的反應速度**（G19 §13.3 原文：「這個決定放棄了什麼（要誠實寫下來）：**颱風天的反應速度。** 颱風天下午三點決定停止營業，照本規格必須找總部帳號的人動手，店長只能打電話。這是真實的成本，不是假設。」）

**(b) 依當天人力調整收單時間**（G25 §13.5 原文：「店長不能依當天人力調整收單時間（今天少一個人，想早點收），只能打電話給總部。」）

兩者現場唯一的替代做法都是**切 `active`**，而那個做法已經被 G19 §1.2 判定為不可接受 —— 它的三個成本裡，「要有人記得切回來」會直接損失一整天營收，而且 `active=false` 期間總部連該分店的員工帳號都不能動（G14 寫規格時發現的 `requireOpen()` 連帶效應）。

### 1.3 目標

讓 scope 為 `BRANCH` 的店長能對**自己分店**做兩件事，而且**只有這兩件**：

1. 設定／刪除近期的例外營業日（公休、臨時調整時段）
2. 設定該例外日當天的最後點餐提前分鐘數

每週固定時段、分店基本資料、月目標、分店層級的 `last_order_minutes` 預設值**維持總部限定，一個字不改**。

### 1.4 為什麼現在排這一項

P1 已經清空（G25、G26、G27 全部合併），工作順序剩下的第 17 項是金流，而金流已由 PO 整批延後。G24 是 P2 裡唯一同時滿足下列四條的項目：

- 不需要新依賴（G05 要 Redis、G12 要外部選型）
- 不需要產品／法遵決策（G16 涉及濫用防護與個資）
- 不需要「先有真實資料」（G17／G20／G21 都明文登記要等）
- 前置功能全部已經在主線上（G19、G25 都已合併），本規格只動授權與一個欄位

G19 §13.3 與 G25 §13.5 已經把修法、代價與測試清單寫得很具體，本規格做的是把那兩段落實成施工階段，並補上兩者交會處的那個設計問題（見 §12.2）。

**誠實交代一件事**：G19 §13.3 寫的是「若 PO 或營運端反映颱風天的反應速度是實際痛點，G24 應立刻升排」。**目前沒有收到任何營運端回饋**（Claude 與 Codex 都沒有可接觸營運端的管道）。排這一項靠的是上面四條「其餘項目都被別的東西擋住」，不是營運證據。若 PO 認為有更該做的，這份規格可以整份擱置 —— 它不擋任何其他工作。

---

## 2. 範圍

### 2.1 在範圍

| # | 項目 |
| --- | --- |
| 1 | 新增權限常數 `BRANCH_HOURS_OVERRIDE`（名稱沿用 G19 §13.3 的登記），授予 `MANAGER` 與 `HQ` |
| 2 | `PUT`／`DELETE /api/branches/{id}/hour-overrides/{onDate}` 改為「有 `BRANCH_HOURS_OVERRIDE` 且資料範圍涵蓋該分店」 |
| 3 | 非 GLOBAL 的呼叫者只能設定／刪除**今日起 14 天內**的日期（§6.3） |
| 4 | `branch_day_overrides` 新增 nullable 欄位 `last_order_minutes`，為該日覆寫分店預設值 |
| 5 | 新增一種例外日形狀：**沿用每週時段、只覆寫最後點餐**（`closed=false` + 空時段 + 有 `lastOrderMinutes`） |
| 6 | 前端新增「本店營業設定」頁，給沒有 `BRANCH_MANAGE` 的店長使用；既有分店管理頁補上每日最後點餐欄位 |
| 7 | 越權測試（店長改他店、收銀員、顧客、店長改每週時段、店長設超出 14 天） |

### 2.2 不在範圍

| # | 項目 | 去處 |
| --- | --- | --- |
| 1 | 店長修改**每週固定時段**（`PUT /{id}/hours`） | 不做，§12.1 |
| 2 | 店長修改分店層級的 `last_order_minutes` 預設值 | 不做，§12.2 |
| 3 | 店長修改分店基本資料、`active`、`monthlyTarget` | 不做，§12.1 |
| 4 | 例外日跨夜時段 | 維持 G19 §13.4 的禁止，不解禁 |
| 5 | 審批流程（店長提出、總部核准） | 不做，§12.6 |
| 6 | 通知總部「某店今天公休了」 | 不做，§12.6。稽核軌跡已經記了，總部查得到 |
| 7 | 國定假日自動匯入、重複規則 | 維持 G19 §13.6／§13.7 的不做 |
| 8 | 顧客端 UI 變更 | 零變更。顧客看到的 `openNow`／`orderableNow`／`minutesUntilLastOrder` 欄位語意完全不變 |

---

## 3. 涉及模組與邊界

```
coffee-shared     零變更（Actor.branch() 已存在，正是本規格要用的那一支）
coffee-identity   api/Identity.PERMISSIONS 加一個常數。零邏輯變更
coffee-branches   授權條件、例外日欄位與每日最後點餐的解析   依賴：shared、audit.api（皆為既有）
coffee-orders     零變更（繼續呼叫 requireOrderable，語意不變）
coffee-app        InitialData 角色清單、V12 migration、測試
frontend          modules/branches 新增一頁與純函式；shared/types.ts 加一個欄位
```

**不新增模組、不新增依賴邊、不引入任何新相依。** `ModuleBoundariesTest` 應該一個字都不用改。

---

## 4. DB schema 與 migration

### 4.0 版號

**`V12__branch_day_settings.sql`。** V11 由 G25 占用並已合併。Flyway 預設不接受事後補插較小版號，空版號的成本是零，**不得回頭使用 V12 以下的任何版號**。

### 4.1 內容

```sql
-- 1. 每日最後點餐（NULL = 沿用 branches.last_order_minutes）
ALTER TABLE branch_day_overrides ADD COLUMN last_order_minutes INTEGER;
ALTER TABLE branch_day_overrides ADD CONSTRAINT ck_branch_day_overrides_last_order
  CHECK(last_order_minutes IS NULL OR last_order_minutes BETWEEN 0 AND 120);

-- 2. 權限授予（冪等，形狀沿用 V4__branch_menu_availability.sql）
INSERT INTO role_permissions(role_code,permission)
  SELECT 'MANAGER','BRANCH_HOURS_OVERRIDE'
  WHERE EXISTS(SELECT 1 FROM roles WHERE code='MANAGER')
    AND NOT EXISTS(SELECT 1 FROM role_permissions
                   WHERE role_code='MANAGER' AND permission='BRANCH_HOURS_OVERRIDE');

INSERT INTO role_permissions(role_code,permission)
  SELECT 'HQ','BRANCH_HOURS_OVERRIDE'
  WHERE EXISTS(SELECT 1 FROM roles WHERE code='HQ')
    AND NOT EXISTS(SELECT 1 FROM role_permissions
                   WHERE role_code='HQ' AND permission='BRANCH_HOURS_OVERRIDE');
```

**三個注意點：**

1. **不授予 `CASHIER`。** 理由見 §12.3。驗收會驗 `CASHIER` 拿不到這個權限
2. **`ADD COLUMN` 不加 `NOT NULL`、不加 `DEFAULT`。** NULL 就是「沒有覆寫」，語意比「0 代表沒設定」乾淨 —— 0 是合法的最後點餐值（打烊前 0 分鐘才停收），拿它當哨兵值會讓「不覆寫」與「覆寫成 0」永遠分不開
3. **具名 CHECK 約束**（`ck_branch_day_overrides_last_order`）。沿用 `V11__branch_last_order.sql` 的 `ck_branches_last_order` 慣例 —— V1 留下的匿名 CHECK 在 G07 §11.5 製造過一次無法移除的硬限制，不要再製造第二個

### 4.2 既有資料

`branch_day_overrides` 的既有列全部拿到 `last_order_minutes = NULL`，行為與現在逐位元相同（沿用 `branches.last_order_minutes`）。**零資料遷移。**

---

## 5. 權限與資料範圍

### 5.1 新權限常數

`Identity.PERMISSIONS`（`coffee-identity/src/main/java/com/coffee/identity/api/Identity.java:7-21`）在 `BRANCH_MANAGE` 之後插入 `"BRANCH_HOURS_OVERRIDE"`。目前 13 個常數，之後 14 個。

名稱沿用 G19 §13.3 原文登記的 `BRANCH_HOURS_OVERRIDE`，不要自己另取。長度 21 字元，`role_permissions.permission` 是 `VARCHAR(40)`，放得下。

### 5.2 角色預設

| 角色 | scope | 是否授予 | 為什麼 |
| --- | --- | --- | --- |
| `HQ` | GLOBAL | **是** | 既有行為必須完全保留。`InitialData.java:58` 用 `Identity.PERMISSIONS` 整包給 HQ，新站台自動涵蓋；既有站台靠 V12 的 `INSERT` |
| `MANAGER` | BRANCH | **是** | 本規格的目的 |
| `CASHIER` | BRANCH | 否 | §12.3 |
| `CUSTOMER` | SELF | 否 | 不解釋 |

`InitialData.java:48-57` 的 `MANAGER` 權限清單要加上 `"BRANCH_HOURS_OVERRIDE"` —— 那一段只在 `roles` 表為空時執行，所以**新站台靠它、既有站台靠 V12**，兩條路都要補，少一條就會有一邊沒權限。

### 5.3 端點授權表

| 端點 | 現況 | 本規格之後 |
| --- | --- | --- |
| `GET /api/branches/{id}/hours` | 已登入即可 | **不變** |
| `GET /api/branches/{id}/hour-overrides` | 已登入即可 | **不變** |
| `PUT /api/branches/{id}/hours` | `BRANCH_MANAGE` + `global()` | **不變**（§12.1） |
| `POST /api/branches` | `BRANCH_MANAGE` + `global()` | **不變** |
| `GET /api/branches?manage=true` | `BRANCH_MANAGE` | **不變** |
| `PUT /api/branches/{id}/hour-overrides/{onDate}` | `BRANCH_MANAGE` + `global()` | `BRANCH_HOURS_OVERRIDE` + `actor.branch(id)` + §6.3 日期範圍 |
| `DELETE /api/branches/{id}/hour-overrides/{onDate}` | 同上 | 同上 |

### 5.4 授權寫法（照抄）

`saveOverride` 與 `deleteOverride` 開頭那兩行：

```java
actor.require("BRANCH_MANAGE");
if (!actor.global()) throw new Problem(403, "此功能限總部範圍");
```

改成：

```java
actor.require("BRANCH_HOURS_OVERRIDE");
actor.branch(branchId);
```

`Actor.branch()`（`coffee-shared/src/main/java/com/coffee/shared/Actor.java:25-28`）已經是「global 放行、否則 branchId 必須相符，不符就 403『只能存取所屬分店資料』」。**不要自己寫 scope 判斷**，那是 `AGENTS.md`「授權」那節指定的既有工具。

**不要把 `BRANCH_MANAGE` 留著當 or 條件。** HQ 兩個權限都有，加 or 只會多一條永遠測不到的分支。

---

## 6. API

### 6.1 `PUT /api/branches/{id}/hour-overrides/{onDate}`

請求（`BranchController.OverrideRequest` 加一個欄位）：

```json
{ "onDate": 20261010, "closed": false, "note": "颱風天縮短營業",
  "hours": [{"openMinute": 600, "closeMinute": 1080}],
  "lastOrderMinutes": 30 }
```

回應（`BranchController.DayOverrideResponse` 加同一個欄位）：

```json
{ "onDate": 20261010, "dayOfWeek": 6, "closed": false, "note": "颱風天縮短營業",
  "hours": [{"dayOfWeek": 6, "openMinute": 600, "closeMinute": 1080}],
  "lastOrderMinutes": 30 }
```

`lastOrderMinutes` 是 `Integer`（可為 `null`）。`null` = 不覆寫，沿用 `branches.last_order_minutes`。

**路徑上的 `{onDate}` 仍然是權威值**，request body 的 `onDate` 照既有寫法被忽略（`BranchController.saveOverride` 用 `@PathVariable int onDate` 組 `DayOverride`）。不要改這個行為。

### 6.2 三種合法形狀

| 形狀 | `closed` | `hours` | `lastOrderMinutes` | 意義 |
| --- | --- | --- | --- | --- |
| A 整天公休 | `true` | 空 | **必須 null** | 既有行為，不變 |
| B 覆寫時段 | `false` | 非空 | null 或 0–120 | 既有行為 + 可選的每日最後點餐 |
| C **只覆寫最後點餐** | `false` | 空 | **必須非 null** | **新增**：沿用每週時段，只改今天的收單時間 |

形狀 C 是本規格新增的那一種。它存在的理由很具體：店長今天少一個人，想提早 40 分鐘收單，但營業時間照舊 —— 若沒有形狀 C，店長必須把今天的每週時段整組重打一次才能附上最後點餐，而重打時打錯一個數字就等於改了當天的營業時間。

### 6.3 錯誤碼與訊息

| 情境 | 狀態 | 訊息 |
| --- | --- | --- |
| 沒有 `BRANCH_HOURS_OVERRIDE` | 403 | `沒有此功能的操作權限`（`Actor.require` 既有） |
| 有權限但不是自己的分店 | 403 | `只能存取所屬分店資料`（`Actor.branch` 既有） |
| 非 GLOBAL，`onDate` 早於今日 | 400 | `只能設定今天起 14 天內的日期` |
| 非 GLOBAL，`onDate` 晚於今日 + 14 天 | 400 | `只能設定今天起 14 天內的日期` |
| `closed=true` 又帶 `lastOrderMinutes` | 400 | `公休日不需要設定最後點餐時間` |
| `closed=false`、時段空、`lastOrderMinutes` 也是 null | 400 | `請至少設定一個營業時段、改為整天公休，或設定本日最後點餐時間` |
| `lastOrderMinutes` 不在 0–120 | 400 | `最後點餐提前時間需為 0–120 分鐘`（與 G25 同字串，刻意一致） |
| 其餘（日期格式、時段重疊、跨夜、備註長度） | — | 全部維持 G19 既有訊息，一個字不改 |

最後一列那句「請至少設定一個營業時段、改為整天公休，或設定本日最後點餐時間」取代 G19 原本的「請至少設定一個營業時段，或改為整天公休」。**這是使用者可見字串的變更，要連既有測試一起改**，不是放寬測試（見 §10.2）。

### 6.4 `DELETE` 與 `GET`

`DELETE` 的請求／回應格式完全不變，只有授權與 §6.3 的日期範圍規則變。
`GET /api/branches/{id}/hour-overrides` 的回應多出 `lastOrderMinutes` 欄位，其餘不變。

---

## 7. 時間規則

- 「今天」一律 `Asia/Taipei`（`AGENTS.md`「時間」）
- **禁止無參數 `now()`。** G27（PR #53）的 `TimeZoneGuardTest` 會以 ArchUnit 擋下 `LocalDate.now()` / `LocalDateTime.now()` / `YearMonth.now()` / `ZonedDateTime.now()` / `LocalTime.now()`，寫下去就是 build fail。§6.3 的「今日」要寫成：

  ```java
  LocalDate today = Instant.now().atZone(TAIPEI).toLocalDate();
  ```

  `Instant.now()` 在那條規則裡是**明文放行**的（`Instant` 是絕對時刻、與時區無關），`BranchController.java:90` 已經是這個寫法，照抄即可

- 日期界線用 `onDate`（`yyyyMMdd` 的 `INTEGER`），與 G19 既有格式相同，不要改成字串或 epoch
- §6.3 的範圍是**閉區間** `today <= onDate <= today + 14`

---

## 8. 金額規則

**本規格不碰任何金額。** 不新增、不讀取、不計算任何金額欄位；`orders.total`、`discount_amount`、`last_order_minutes` 以外的 `branches` 欄位全部不動。

唯一與金額沾邊的是間接效果：例外日會讓顧客端下不了單，進而影響當日營收。那是功能本身的目的，不是金額計算。

---

## 9. 核心邏輯：每日最後點餐怎麼解析

這一節是本規格唯一需要小心的地方，實作端照著做就好，不要自己發明。

### 9.1 既有形狀

```java
public sealed interface DaySchedule { AlwaysOpen | Closed(note) | Periods(periods, fromOverride) }
public sealed interface OpenWindow  { NotOpen | NoClosingTime | ClosesIn(minutes) }

public static DaySchedule resolveDay(LocalDate, boolean weeklyEmpty, List<Hours> weekly, DayOverride);
public static OpenWindow  windowAt(Resolver, long atEpochMs);
public static OpenState   state(OpenWindow, int lastOrderMinutes);
```

`stateAt`、`stateAt(List)`、`requireOrderable` 三處都是「`windowAt` 拿視窗 → 配上 `branches.last_order_minutes` → `state()`」。

### 9.2 `resolveDay` 的變更（一行）

目前：

```java
if (override != null && override.closed()) return new DaySchedule.Closed(override.note());
if (override != null) return new DaySchedule.Periods(override.hours(), true);
```

改成：

```java
if (override != null && override.closed()) return new DaySchedule.Closed(override.note());
// 形狀 C：例外日沒有時段時代表「只覆寫最後點餐」，當天的營業時段仍然沿用每週設定
if (override != null && !override.hours().isEmpty())
  return new DaySchedule.Periods(override.hours(), true);
```

**既有資料不可能走到新分支**：`saveOverride` 一路以來都拒絕「非公休 + 空時段」，所以 DB 裡不存在這種列。新分支只對本規格新寫入的形狀 C 生效。

`BranchHourOverrideTest.resolveDayUsesTheFourSpecifiedPriorities`（`BranchHourOverrideTest.java:66-87`）要補上形狀 C 的第五個案例。若該測試既有案例裡有「非公休 + 空時段 → `Periods([], true)`」的斷言，**改掉它並在 PR 描述說明**（這是規格指定的行為變更，不是放寬測試）。

### 9.3 `windowAt` 的變更（加一個多載，不動既有簽章）

問題：跨夜時，00:30 的營業視窗來自**前一天**的每週時段，該用哪一天的最後點餐？答案是前一天的（人力是前一天那一班的）。但 `OpenWindow.ClosesIn(minutes)` 沒有帶出「這個視窗屬於哪一天」。

**不要改 `ClosesIn` 的欄位，也不要改既有 `windowAt`／`state` 的簽章** —— 那會波及 `BranchLastOrderTest`（13 處）與 `BranchHoursTest`（16 處）的既有斷言，規模與風險都不值得。改用多載：

```java
public record TimedWindow(OpenWindow window, int lastOrderMinutes) {}

/** 既有行為：把實作搬進三參數版，這一支變成委派，回傳值逐位元相同。 */
public static OpenWindow windowAt(Resolver resolver, long atEpochMs) {
  return windowAt(resolver, atEpochMs, date -> 0).window();
}

/**
 * lastOrderOf 傳入的是「某一天的有效最後點餐分鐘數」。
 * 回傳的 lastOrderMinutes 取自**視窗所屬的那一天**：
 *   - 當天時段命中  -> lastOrderOf(today)
 *   - 前一天尾段命中 -> lastOrderOf(previous)
 *   - NotOpen / NoClosingTime -> 0（呼叫端不會用到）
 */
public static TimedWindow windowAt(
    Resolver resolver, long atEpochMs, java.util.function.ToIntFunction<LocalDate> lastOrderOf);
```

三參數版的本體就是現在 `windowAt` 的程式碼，差別只在每個 `return new OpenWindow.ClosesIn(...)` 改成 `return new TimedWindow(new OpenWindow.ClosesIn(...), lastOrderOf.applyAsInt(當天或前一天))`。

### 9.4 `effectiveLastOrder` 與三個呼叫端

```java
private static int effectiveLastOrder(
    Map<LocalDate, DayOverride> overrides, int branchDefault, LocalDate date) {
  DayOverride override = overrides.get(date);
  return override == null || override.lastOrderMinutes() == null
      ? branchDefault
      : override.lastOrderMinutes();
}
```

`stateAt(String, long)`、`stateAt(List<String>, long)`、`requireOrderable` 三處改成：

```java
TimedWindow timed = windowAt(resolver, atEpochMs,
    date -> effectiveLastOrder(overrides, branchDefault, date));
OpenState result = state(timed.window(), timed.lastOrderMinutes());
```

**三處都已經載入了「今天與昨天」兩天的例外日**（`loadOverrides(branchId, dateInt(today.minusDays(1)), dateInt(today))`，以及 `stateAt(List)` 的 `where o.on_date in (?,?)`），所以 `lastOrderOf` 要用到的兩天都在手上，**不需要多打任何一次 DB**。這是刻意的：G10 剛把查詢次數壓下來，不要在這裡加回去。

### 9.5 查詢要補欄位

三支 SQL 的 select 清單要加 `o.last_order_minutes`，並在組 `DayOverride` 時用 `(Integer) result.getObject("last_order_minutes")` 讀（**不要用 `getInt`**，那會把 NULL 讀成 0，正是 §4.1 注意點 2 要避免的那個 bug）：

- `BranchService.loadOverrides`（`branch_day_overrides o left join branch_day_override_hours h`）
- `BranchService.stateAt(List<String>, long)` 裡的那支 `where o.on_date in (?,?)`
- `MutableOverride` 要多存一個 `Integer lastOrderMinutes`

### 9.6 `requireOrderable` 的訊息

`lastOrderMessage` 已經接受 `lastOrderMinutes` 參數，傳有效值進去即可，**字串格式一個字不改**。顧客在設了每日最後點餐的那天看到的仍然是「分店已停止接單（最後點餐時間 HH:MM），請於今日 HH:MM 起的營業時段再下單」。

---

## 10. 施工階段

四個階段。**S1／S2／S3 是後端，S4 是前端**，彼此之間只有 S3 依賴 S1 的欄位。任何一個階段單獨合進主線都不破壞既有行為。

### S1 — 權限常數與資料層（純加法，零行為變更）

**動到的檔案**

- `backend/coffee-identity/src/main/java/com/coffee/identity/api/Identity.java` —— `PERMISSIONS` 加 `"BRANCH_HOURS_OVERRIDE"`
- `backend/coffee-app/src/main/java/com/coffee/app/bootstrap/InitialData.java` —— `MANAGER` 角色清單加同一個常數
- `backend/coffee-app/src/main/resources/db/migration/V12__branch_day_settings.sql` —— 新檔，內容見 §4.1
- `backend/coffee-app/src/test/java/com/coffee/app/BranchDaySettingsMigrationTest.java` —— 新檔

**這一階段不碰任何授權判斷、不碰任何端點。** 新權限誰都還用不到，新欄位沒有人讀也沒有人寫。

**驗收子集**：§11 的 1–5

### S2 — 例外日授權開放給店長（行為變更，集中在兩個方法）

**動到的檔案**

- `backend/coffee-branches/src/main/java/com/coffee/branches/internal/BranchService.java` —— `saveOverride`／`deleteOverride` 的開頭兩行（§5.4）+ §6.3 的日期範圍檢查
- `backend/coffee-app/src/test/java/com/coffee/app/BranchHourOverrideTest.java` —— 既有的「manager → 403 此功能限總部範圍」斷言（`BranchHourOverrideTest.java:275-287`）要改，並新增越權案例

**日期範圍檢查放哪裡**：`saveOverride` 與 `deleteOverride` 各一次，寫成一支 private static helper，`actor.global()` 直接 return。

```java
private static void requireNearDate(Actor actor, int onDate) {
  if (actor.global()) return;
  LocalDate today = Instant.now().atZone(TAIPEI).toLocalDate();
  LocalDate date = parseDate(onDate, "日期格式不正確");
  Problem.check(
      !date.isBefore(today) && !date.isAfter(today.plusDays(14)), "只能設定今天起 14 天內的日期");
}
```

**順序很重要**：先 `require(權限)` → 再 `actor.branch()` → 再日期範圍 → 再既有的欄位驗證。權限不足的人不該從錯誤訊息推斷出日期是否合法。

**驗收子集**：§11 的 6–12

### S3 — 每日最後點餐時間

**動到的檔案**

- `backend/coffee-branches/src/main/java/com/coffee/branches/api/Branches.java` —— `DayOverride` 加 `Integer lastOrderMinutes`
- `backend/coffee-branches/src/main/java/com/coffee/branches/internal/BranchService.java` —— §9.2–§9.5 全部
- `backend/coffee-branches/src/main/java/com/coffee/branches/internal/BranchController.java` —— `OverrideRequest`／`DayOverrideResponse` 加欄位
- 既有測試：`DayOverride` 是 record，加一個欄位會讓所有 `new DayOverride(...)` 編譯失敗。`BranchHourOverrideTest`、`BranchLastOrderTest` 裡的建構呼叫要補上 `null`。**這是機械性修改，不是行為變更**，不要順手改那些測試的斷言
- 新測試：見 §11 的 13–20

**這一階段是四個裡面最大的一個**，但切不開：`DayOverride` 加欄位、解析規則、三個呼叫端是同一件事的三個面，分兩次做會留下一個「欄位存在但沒人讀」的中間狀態 —— 那種狀態可以合併（無害），但分兩個 PR 等於審兩次同一段邏輯。若 Codex 判斷一次執行做不完，**合法的切法是先推「record 加欄位 + 讀寫 DB + 回應欄位」、再推「resolveDay 與 windowAt 的解析」**，第一半單獨合併時行為不變（欄位存得進去但不影響判定），是安全的中間狀態。

**驗收子集**：§11 的 13–20

### S4 — 前端

**動到的檔案**

- `frontend/src/shared/types.ts` —— `BranchDayOverride` 加 `lastOrderMinutes: number | null`
- `frontend/src/modules/branches/overrides.ts` —— `overrideSummary()` 要把每日最後點餐帶進摘要；新增 `dayWindowLimits(todayOnDate)` 之類的純函式算出可選日期範圍
- `frontend/src/modules/branches/overrides.spec.ts` —— 補上述純函式的測試
- `frontend/src/modules/branches/BranchDayView.vue` —— **新檔**，店長用的「本店營業設定」頁
- `frontend/src/modules/branches/BranchesView.vue` —— 既有例外日編輯表單加「本日最後點餐」欄位（總部端也要能設）
- `frontend/src/main.ts` —— 新路由 `/branch-day`，`meta: { permissions: ["BRANCH_HOURS_OVERRIDE"] }`
- `frontend/src/App.vue` —— 新 nav 項目「本店營業設定」，`show: auth.can("BRANCH_HOURS_OVERRIDE") && !auth.can("BRANCH_MANAGE")`
- `frontend/src/modules/branches/BranchDayView.dom.test.ts` —— 元件測試，用 G23 建好的 `shared/testing/harness.ts`

**為什麼另開一頁而不是讓店長看既有的分店管理頁**：`BranchesView.vue:52` 第一件事就是 `api("/branches?manage=true")`，那支需要 `BRANCH_MANAGE`，店長打了會 403；要共用就得把那一頁改成「有沒有 `BRANCH_MANAGE` 走兩條路」，那是一頁兩種身分的條件渲染，是前端最容易長出 bug 的形狀。新開一頁只讀 `/branches/{自己的 branchId}/hours` 與 `/hour-overrides`，不碰列表端點。

**nav 的 `!auth.can("BRANCH_MANAGE")`**：HQ 兩個權限都有，既有的「分店管理」頁已經涵蓋全部功能，再給它一個只能管一家店的入口只會造成混淆。

**驗收子集**：§11 的 21–26

### 階段之間的關係

```
S1 ──> S2 （S2 用到 S1 的權限常數）
 └───> S3 （S3 用到 S1 的 last_order_minutes 欄位）
S2、S3 可以任意順序；S4 需要 S3 的 API 欄位
```

S2 與 S3 都改 `BranchService.java`，但改的是不同方法（S2 改開頭的授權行，S3 改解析與查詢），**先做哪個都行，後做的那個要先把主線 merge 進來**。

---

## 11. 驗收條件

逐條可勾選。括號裡是所屬階段。

**S1**

- [ ] 1. `Identity.PERMISSIONS` 含 `"BRANCH_HOURS_OVERRIDE"`，共 14 個常數
- [ ] 2. `V12__branch_day_settings.sql` 存在；`backend/coffee-app/src/main/resources/db/migration/` 底下**沒有任何既有檔案被修改**（`git diff --stat` 驗證）
- [ ] 3. migration 後 `branch_day_overrides` 有 nullable 的 `last_order_minutes`；寫入 `-1` 或 `121` 會被 CHECK 擋下
- [ ] 4. migration 後 `MANAGER` 與 `HQ` 都有 `BRANCH_HOURS_OVERRIDE`；`CASHIER` 與 `CUSTOMER` 都**沒有**
- [ ] 5. `InitialData` 建立的新站台，`MANAGER` 同樣有該權限（測試可清空 `roles` 後重跑 `InitialData`，或直接斷言常數清單）

**S2**

- [ ] 6. `manager`（taipei）`PUT /api/branches/taipei/hour-overrides/{近期日期}` → **200**，資料寫入，稽核 `BRANCH_HOURS_OVERRIDE_SAVE` 有一筆且 `branch_id='taipei'`
- [ ] 7. `manager`（taipei）`PUT /api/branches/banqiao/hour-overrides/{近期日期}` → **403**，訊息 `只能存取所屬分店資料`
- [ ] 8. `manager2`（banqiao）對 `banqiao` → 200；對 `taipei` → 403
- [ ] 9. `cashier` → **403**，訊息 `沒有此功能的操作權限`；`customer` 同樣 403 同樣訊息
- [ ] 10. `manager` `PUT` 昨天的日期 → **400** `只能設定今天起 14 天內的日期`；`today+15` → 同樣 400；`today` 與 `today+14` → 200
- [ ] 11. `hq` 設定 `today+200` → **200**（總部不受 14 天限制，既有行為保留）
- [ ] 12. `manager` `PUT /api/branches/taipei/hours`（每週時段）→ **403** `此功能限總部範圍`。**這一條是本規格最容易被實作成 200 的一條**，一定要有測試
- [ ] 12b. `manager` `DELETE` 的四個對應情境（本店近期 → 204、他店 → 403、超出範圍 → 400、無權限角色 → 403）

**S3**

- [ ] 13. 形狀 B：`PUT` 帶 `hours` 與 `lastOrderMinutes: 30` → 200，`GET` 讀回 `lastOrderMinutes: 30`
- [ ] 14. 形狀 C：`PUT` `{"closed":false,"note":"","hours":[],"lastOrderMinutes":40}` → 200；該日的 `GET /hours`／`stateAt` 顯示的營業時段**仍然是每週時段**，但 `minutesUntilLastOrder` 依 40 分鐘計算
- [ ] 15. `closed=true` 又帶 `lastOrderMinutes` → **400** `公休日不需要設定最後點餐時間`
- [ ] 16. `closed=false`、`hours` 空、`lastOrderMinutes` 為 null → **400** `請至少設定一個營業時段、改為整天公休，或設定本日最後點餐時間`
- [ ] 17. `lastOrderMinutes` 為 `-1` 或 `121` → **400** `最後點餐提前時間需為 0–120 分鐘`
- [ ] 18. 沒設每日值的日子，`stateAt` 與 `requireOrderable` 的結果與本規格合併前**逐位元相同**（回歸測試：同一組固定時間點，斷言 `OpenState` 三個欄位）
- [ ] 19. 跨夜：週一 22:00–隔日 02:00 的每週時段，**週一**設 `lastOrderMinutes=60`，則週二 00:30 的 `orderableNow` 為 `false`（視窗屬於週一，用週一的值）；週二設值、週一不設，則週二 00:30 仍用分店預設值
- [ ] 20. `stateAt(List<String>, long)`（分店列表那支）與 `stateAt(String, long)` 對同一組資料回傳相同的 `OpenState`；**整支列表端點的 DB 查詢次數不增加**（§9.4）
- [ ] 20b. `requireOrderable` 在每日最後點餐生效時的 400 訊息格式與 G25 完全相同，只有時間數字不同

**S4**

- [ ] 21. 以 `manager` 登入，側欄出現「本店營業設定」，**沒有**「分店管理」
- [ ] 22. 以 `hq` 登入，側欄出現「分店管理」，**沒有**「本店營業設定」
- [ ] 23. 以 `cashier` 登入，兩者都沒有；直接打 `/branch-day` 網址會被 `main.ts` 的 `beforeEach` 導回 `/` 並提示
- [ ] 24. `BranchDayView` 不呼叫 `/branches?manage=true`（元件測試可斷言 fetch 呼叫清單）
- [ ] 25. 日期選擇器只允許今日起 14 天；`overrides.spec.ts` 覆蓋該純函式的邊界（今日、today+14、today+15、跨月、跨年）
- [ ] 26. `BranchesView` 的例外日編輯表單可設定與清空每日最後點餐

**全階段共同**

- [ ] 27. `cd backend && ./mvnw -B -ntp verify` 綠
- [ ] 28. `cd frontend && npm ci && npm run build` 與 `npm test` 綠
- [ ] 29. `git ls-files -s backend/mvnw scripts/build.sh start-demo.sh` 三個都是 `100755`
- [ ] 30. `ModuleBoundariesTest` 與 `TimeZoneGuardTest` 皆綠，且**兩支都沒有被修改**
- [ ] 31. 全 repo 沒有新增任何無參數 `now()` 呼叫（由 `TimeZoneGuardTest` 保證，但 PR 描述要自述一次）

---

## 12. 設計決策

每一條都附理由與推翻它的代價。決策是給下一輪推翻用的，不是給人核准用的。

### 12.1 每週固定時段與分店基本資料維持總部限定 —— **不開放**

**決定**：`PUT /{id}/hours`、`POST /api/branches`、`GET /branches?manage=true` 的授權一個字不改。

**理由**：三點。(1) 本缺口的兩個具體痛點（G19 §13.3 的颱風天、G25 §13.5 的當天人力）都是**當天的事**，例外日與每日最後點餐就完全涵蓋，開放每週時段不解決任何已知痛點；(2) 每週時段是「這家店平常怎麼營業」，會進印刷品、Google 商家與加盟合約，那是公司層級的決定；(3) 授權面積越小，越權測試才寫得完 —— 本規格只放寬兩個方法，§11 就要 12 條授權驗收，再多兩個方法會讓這份規格的測試量翻倍。

**這個決定放棄了什麼**：店長調整常態營業時間（例如冬季提早一小時打烊）仍然要找總部。那是每季一次的事，不是每天一次的事。

**推翻它的代價**：**低，而且是加法。** `saveHours` 的兩行授權改成與 `saveOverride` 相同的形狀即可，不需要新權限常數（`BRANCH_HOURS_OVERRIDE` 的名字本來就涵蓋「hours」）、不需要 migration。要補的是「店長改他店每週時段 → 403」那組測試。**但要先想清楚第 (2) 點**：常態營業時間若能被店長改，總部就失去對外公告時間的控制權，那是營運政策問題，不是技術問題。

### 12.2 每日最後點餐放在例外日上，不開放店長改分店預設值 —— **放在例外日**

**決定**：店長能改的是 `branch_day_overrides.last_order_minutes`（有日期、會自動失效），不是 `branches.last_order_minutes`（永久）。

**理由**：這條直接回應 G25 §13.5 的那個反對意見 —— 它說「把其中一個欄位開放給店長、另一個不開放，會做出一個『同一個 PUT 裡有些欄位你改得了有些改不了』的授權模型」，而那個反對意見是對的。本規格的做法讓店長與總部走**不同的端點改不同的欄位**，沒有任何一支端點需要做欄位層級的授權：

| 誰 | 端點 | 改什麼 | 生效範圍 |
| --- | --- | --- | --- |
| 總部 | `PUT /{id}/hours` | `branches.last_order_minutes` | 永久預設值 |
| 總部與店長 | `PUT /{id}/hour-overrides/{onDate}` | `branch_day_overrides.last_order_minutes` | 只有那一天 |

第二個理由更重要：**有日期的設定會自己過期。** G19 §1.2 判定「切 `active`」不可接受的關鍵成本是「要有人記得切回來」。若把分店預設值開放給店長，今天提早收單、明天忘記改回來，就是同一個缺陷換一張表。例外日沒有這個問題 —— 它綁死在一個日期上，隔天自動回到每週設定。

**這個決定放棄了什麼**：店長要「從今天起一直提早收單」必須一天設一次，或打電話給總部。這是刻意的摩擦：常態性的改變本來就該走總部。

**推翻它的代價**：**中。** 要開放分店預設值，必須回答「誰負責改回來」。可行做法是 §12.5 登記的那條（例外日支援日期區間），而不是把永久欄位交出去。

### 12.3 `CASHIER` 不授予此權限 —— **不給**

**決定**：只給 `MANAGER` 與 `HQ`。

**理由**：公休與停止接單是**當天要不要做生意**的決定，與 `MENU_AVAILABILITY`（某品項賣完了）不是同一個量級 —— 後者錯了損失一個品項的當日銷售，前者錯了損失整天營收。收銀員目前拿到的權限（`MENU_AVAILABILITY`、`CASH_SESSION`、`ORDER_MANAGE`）全部是「處理眼前這一筆」，沒有一個會改變分店的對外狀態。

**這個決定放棄了什麼**：店長不在場（休假、跑外務）時，收銀員不能宣布公休。

**推翻它的代價**：**零。** 角色與權限的對應可以由 `HQ` 在「角色與權限」頁（`RolesView.vue`，`ROLE_MANAGE`）直接勾選，不需要改任何程式碼 —— 本規格的 V12 只設定**預設值**，不是硬編碼的規則。這一點要寫進 PR 描述，省得日後有人以為要改 migration。

### 12.4 日期範圍上限 14 天 —— **14 天，只限制非 GLOBAL**

**決定**：非 GLOBAL 的呼叫者只能設定／刪除 `今日 <= onDate <= 今日 + 14` 的日期。總部不受限（既有行為保留）。

**理由**：店長的用途是颱風天與臨時人力，那是「這週」的事。把年度行事曆（過年、裝修、盤點）留給總部有兩個好處：(1) 誤設的遠期公休若由店長設在三個月後，很可能到那天開不了門才被發現，而 14 天內的錯誤幾乎一定會在週會或排班時被看到；(2) 總部要盤整全公司的公休日時，不必擔心某家店在半年後埋了一個自己設的日期。

**為什麼是 14 而不是 7 或 30**：14 天涵蓋兩個完整排班週期，颱風預報與連假調整都在這個範圍內；30 天已經進入「下個月的事」，那是排班而不是應變。這個數字沒有強理由，**它是可以被營運事實推翻的那一類決定**。

**過去的日期也擋**：店長不能補設昨天的公休。理由是過去的日期對 `requireOrderable` 完全沒有影響（只會查今天與昨天，而昨天的那一筆只用於跨夜尾段），改它唯一的效果是污染稽核與日後的人工判讀。清理歷史列歸總部。

**推翻它的代價**：**零。** 一個常數加一條測試。寫成 `private static final int MANAGER_DAYS_AHEAD = 14;`，不要把 14 散落在三個地方。

### 12.5 不支援日期區間，一次一天 —— **一次一天**

**決定**：`PUT /{id}/hour-overrides/{onDate}` 維持單日語意。要設連續五天公休就呼叫五次。

**理由**：區間寫入會立刻帶出三個新問題 —— 區間與既有單日列重疊怎麼辦、刪除區間時要不要連帶刪掉中間被改過的那一天、稽核該記一筆還是五筆。G19 的資料形狀（`PRIMARY KEY(branch_id, on_date)`）是為單日設計的，硬塞區間會讓那張表同時有兩種語意。而店長的實際用量是「今天」或「這個週末」，五次呼叫是可以接受的。

**推翻它的代價**：**中。** 前端可以先用「迴圈呼叫五次」偽裝成區間（本規格的 S4 不做，但那是零後端成本的做法）；真正的區間支援要新增一張 `branch_day_override_ranges` 或在既有表加 `until_date`，並定義與單日列的優先序。若營運端反映「連假一次設七天很痛」，**先做前端迴圈，不要動資料表**。

### 12.6 不做審批流程、不做通知 —— **不做**

**決定**：店長設了就生效，不需要總部核准；系統也不主動通知總部。

**理由**：稽核軌跡已經記了（`BRANCH_HOURS_OVERRIDE_SAVE`／`_DELETE`，含 `branch_id` 與 `updated_by`），總部有 `AUDIT_VIEW` 且 scope GLOBAL，查得到誰在什麼時候把哪一天設成公休。審批流程會把「颱風天下午三點的決定」變成「等總部按核准」，那正是本缺口要解決的問題，加回去等於白做。通知則需要通知管道（email／推播），那是本專案目前沒有、而且 `AGENTS.md` 禁止擅自引入的新依賴。

**推翻它的代價**：**低（通知）／高（審批）。** 通知等到有了通知管道再說，屆時它只是一個訂閱者。審批要新增狀態機（待審／核准／駁回）與對應的 UI，而且會重新引入它本來要消除的延遲 —— 真的需要時，比較可能的形狀是「事後追認」而不是事前核准。

### 12.7 沿用 `BRANCH_HOURS_OVERRIDE` 這個名字 —— **沿用**

**決定**：權限常數叫 `BRANCH_HOURS_OVERRIDE`，不改成 `BRANCH_DAY_SETTINGS` 之類更貼切的名字。

**理由**：G19 §13.3 已經白紙黑字登記了這個名字，GAP-ANALYSIS 與 G25 §13.5 都指向那一段。換名字會讓三份已合併的文件同時變成錯的，而「貼切一點」的收益趨近於零。

**推翻它的代價**：**低但不值得。** 權限常數改名要同步 `Identity.PERMISSIONS`、`InitialData`、migration、所有測試與前端字串，而且既有站台的 `role_permissions` 會留著舊名的孤兒列。

### 12.8 顧客端完全無感 —— **零變更**

**決定**：`openNow`／`orderableNow`／`minutesUntilLastOrder` 三個欄位的語意、`requireOrderable` 的錯誤訊息格式、`MenuView` 的打烊提示，全部一個字不改。

**理由**：對顧客而言「今天提早收單」與「這家店本來就這個時間收單」是同一件事，沒有必要區分，區分了還會引導顧客去問「為什麼今天提早」。而且這讓本規格的 S3 有一個很硬的回歸判準（驗收 18：沒設每日值的日子結果逐位元相同）。

**推翻它的代價**：**低。** 要讓顧客看到「今日提早收單」，`OpenState` 加一個 boolean 即可，解析邏輯已經知道值從哪裡來。

---

## 13. 測試要求

### 13.1 必須有的越權測試（§11 的 7、8、9、12、12b）

`AGENTS.md`「授權」那節：「新增任何端點，第一件事是決定權限與資料範圍，並寫測試驗證越權會被擋」。本規格**沒有新增端點，但放寬了既有端點的授權**，風險形狀相同，所以同一條規則適用。

最關鍵的是**驗收 12**：`manager` 打 `PUT /api/branches/taipei/hours` 必須仍然是 403。放寬 `saveOverride` 時順手把 `saveHours` 的 `global()` 檢查一起拿掉，是這份規格最可能發生的事故，而且**目前沒有任何測試會因此變紅**：

- `MANAGER` 角色預設就沒有 `BRANCH_MANAGE`（`InitialData.java:48-57`），所以店長打 `saveHours` 現在停在 `require("BRANCH_MANAGE")` 那一行，根本走不到 `global()` 檢查
- `BranchHoursHttpSecurityTest.putUsesExistingCsrfProtectionAndHeadquartersAuthorization`（全檔 71 行）只驗了 `hq` 與 `customer` 兩種身分，**沒有 manager 的案例**
- `BranchHourOverrideTest.java:61-62` 的 `@BeforeEach` 還會主動把 `MANAGER` 的 `BRANCH_MANAGE` 刪掉

**要新增的測試寫在 `BranchHoursHttpSecurityTest`**：臨時把 `BRANCH_MANAGE` 授予 `MANAGER`（`insert into role_permissions...`，與 `BranchHourOverrideTest.java:275-276` 同樣的手法），再斷言 `manager` 打 `PUT /api/branches/taipei/hours` 仍然是 403「此功能限總部範圍」。只有這樣才驗得到 `global()` 那一行還在。

### 13.2 要修改的既有測試（連同理由寫進 PR 描述）

| 檔案:行 | 現況 | 改成 | 為什麼不是「放寬測試」 |
| --- | --- | --- | --- |
| `BranchHourOverrideTest.java:275-287` | `manager` → 403「此功能限總部範圍」 | `manager` 對本店 → 200；對他店 → 403「只能存取所屬分店資料」；`cashier`／`customer` → 403「沒有此功能的操作權限」 | §5.3 指定的授權變更 |
| `BranchHourOverrideTest.java:66-87` | `resolveDay` 四種優先序 | 補第五種（形狀 C） | §9.2 新增的解析規則 |
| 既有「請至少設定一個營業時段，或改為整天公休」的斷言 | 舊訊息 | §6.3 的新訊息 | 訊息變更是 §6.3 指定的 |
| 所有 `new DayOverride(...)` 建構呼叫 | 四個參數 | 五個參數，補 `null` | record 加欄位的機械性修改 |

**除此之外不得修改任何既有測試。** 既有測試紅了是實作要改（`AGENTS.md` 禁止事項 7）。

### 13.3 回歸測試（驗收 18）

S3 最大的風險是「沒設每日值的日子行為被改掉了」。要有一支測試用固定的 epoch 時間點（不是「現在」）掃過一組固定排程，斷言 `OpenState` 的三個欄位。`BranchLastOrderTest.java:78-117` 已經有這種形狀的參數化測試，照它的寫法擴充，**不要另起爐灶**。

### 13.4 前端測試

- 純函式（日期範圍、摘要字串）→ `overrides.spec.ts`，vitest
- 可見性（側欄項目、頁面是否呼叫列表端點）→ `BranchDayView.dom.test.ts`，用 G23 建好的 `shared/testing/harness.ts` 與 `fixtures.ts`，**不要新增任何 devDependency**

---

## 14. 後續登記

| 編號 | 項目 | 來源 |
| --- | --- | --- |
| G24 §12.1 | 店長修改每週固定時段 | 不做；要做先回答「總部是否仍控制對外公告時間」 |
| G24 §12.5 | 例外日支援日期區間 | 不做；先用前端迴圈，不要動資料表 |
| G24 §12.6 | 公休／停收的通知 | 等有通知管道再說 |
| G24 §12.8 | 顧客端顯示「今日提早收單」 | 低成本，等營運反映 |
