# G19 — 分店例外營業日（公休、臨時調整）

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G19 |
| 優先順序 | P2 → **升為 P1**（理由見 §13.1） |
| 版本 | v1.0（2026-09-29，Claude 定案） |
| 前置相依 | G14（PR #29，已合併，Flyway 占用 V8）、G22（PR #39，**已於 2026-09-29 合併**，前端測試框架可用） |
| Flyway | **`V10__branch_hour_overrides.sql`**（本規格占用 V10，下一份需要 migration 的規格自 V11 起算） |
| 前端變更 | 有（S3） |
| 新增第三方相依 | **零** |

---

## 1. 背景與目標

### 1.1 問題

G14 做了每週固定營業時段（`branch_hours`，一列一段，沒有列＝24 小時營業）。**它只能表達「每個星期三都這樣」，表達不了「10 月 10 日這一天不一樣」。**

咖啡廳每年至少會遇到這幾種：

| 情境 | 頻率 | 目前做得到嗎 |
| --- | --- | --- |
| 國定假日公休 | 一年約 5–10 天 | ❌ |
| 臨時公休（設備故障、盤點、員工訓練） | 一年數次 | ❌ |
| 颱風天停止營業 | 一年 1–3 次 | ❌ |
| 提早打烊（除夕、跨年前夕） | 一年數次 | ❌ |
| 特定日延長營業 | 一年數次 | ❌ |

### 1.2 目前的替代方案有實際成本

G14 §11.3 寫的替代方案是「切 `active` 一天」。那個方案有三個具體成本：

1. **`active=false` 不只是打烊，是整間分店消失。** `BranchService.list()` 在非管理模式下 `where active=true`（`BranchService.java:38`），顧客端連分店選單都看不到這家店；`requireOpen()` 直接丟「分店不存在或已暫停營業」（`:43-47`）。顧客看到的不是「今天公休」，是「這家店不見了」
2. **`requireOpen()` 被 `IdentityService.saveAccount()` 共用**（`IdentityService.java:93`）。切掉 `active` 會讓總部在公休當天無法維護該分店的帳號 —— 這正是 G14 §5.7 當初拒絕把時段檢查塞進 `requireOpen()` 的同一個理由，換成切 `active` 等於自己踩回去
3. **要有人記得切回來。** 沒有排程、沒有到期日，忘了切回來就是隔天整天沒生意，而且系統不會叫。這是「靠人記得」的典型缺陷，成本在出事那天才出現

### 1.3 目標

1. 總部能為「某一分店的某一天」設定例外：**整天公休**，或**改用當天專屬的時段**
2. 例外**覆蓋**當天的每週固定時段，優先序明確且可測
3. 例外**自動到期** —— 它綁在日期上，過了那天就不再影響任何判斷，**不需要引入任何排程作業**
4. 顧客端看到的是「今日公休（國定假日）」這種可理解的訊息，不是「分店不存在」
5. `active` 一個位元都不動

### 1.4 不是目標

- **不做「即將打烊」提示與最後點餐時間（last order）。** G14 §2「不在範圍」第 2 項把它掛在 G19「一併考慮」—— 本規格明確**排除**它，理由見 §13.5，另立 **G25**
- **不做國定假日自動匯入**（讀行事曆 API 或內建假日表）。理由見 §13.6
- **不做重複規則**（「每月第一個星期一公休」）。理由見 §13.7
- **不讓店長設定自己分店的例外日。** 維持與 `saveHours()` 相同的總部權限，理由與推翻代價見 §13.3，另立 **G24**
- **不改 `active` 的語意**，也不自動寫入 `active`
- **不引入排程作業**（`@Scheduled` / quartz）。是否營業一律在查詢的當下計算 —— 沿用 G14 §11 的同一條決定
- **不做跨時區。** 全系統固定 `Asia/Taipei`（`AGENTS.md`「時間」）
- **不擋員工 POS 下單。** 例外日與固定時段一樣，只對顧客自助下單強制（G14 §11.1 的同一條決定）
- **不擋已存在訂單的後續操作**（付款、狀態轉換、取消）。公休不能讓前一天的訂單卡在半路

---

## 2. 範圍

### 2.1 在範圍

| # | 檔案／項目 | 變更 |
| --- | --- | --- |
| 1 | `backend/coffee-app/src/main/resources/db/migration/V10__branch_hour_overrides.sql` | **新增** |
| 2 | `coffee-branches/api/Branches.java` | 新增 `DayOverride` record 與四個方法（§5.2） |
| 3 | `coffee-branches/internal/BranchService.java` | 例外日讀寫 + 判定演算法改用 §6 的解析器 |
| 4 | `coffee-branches/internal/BranchController.java` | 三個新端點（§7） |
| 5 | `frontend/src/shared/types.ts` | 新增 `BranchDayOverride` 型別 |
| 6 | `frontend/src/modules/branches/overrides.ts` | **新增** —— 純函式（排序、摘要文字） |
| 7 | `frontend/src/modules/branches/overrides.spec.ts` | **新增** —— vitest |
| 8 | `frontend/src/modules/branches/BranchesView.vue` | 時段 Modal 增加「例外日」區塊 |
| 9 | `frontend/src/modules/ordering/MenuView.vue` | 打烊訊息顯示例外日備註 |
| 10 | `backend/coffee-app/src/test/java/com/coffee/app/BranchHourOverrideTest.java` | **新增** |
| 11 | `docs/reports/G19-branch-hour-overrides.md` | **新增** —— 實作回報（`AGENTS.md:44`） |
| 12 | `docs/GAP-ANALYSIS.md` | 狀態與工作順序更新 |

> **第 11 項是這一份新增的通則。** G22 的 §2.1 漏列了它，導致 PR #39 是唯一沒有 `docs/reports/` 回報的實作（Claude 在 PR #39 的 review 記為非阻斷觀察）。`AGENTS.md:44` 寫明「實作回報寫到 `docs/reports/`」，從本規格起一律列進檔案清單。

### 2.2 不在範圍

| # | 項目 | 去哪裡 |
| --- | --- | --- |
| 1 | 最後點餐時間 / 即將打烊提示 | **G25**（本規格登記，§13.5） |
| 2 | 店長自行設定本店例外日 | **G24**（本規格登記，§13.3） |
| 3 | 國定假日自動匯入 | 不做，§13.6 |
| 4 | 重複規則（每月第 N 個星期 X） | 不做，§13.7 |
| 5 | 依時段切換菜單 | 未登記；那是 G13 `branch_products` 的延伸 |
| 6 | 例外日影響報表的「應營業天數」 | 不做。報表目前沒有這個欄位，加了會動到 G09 剛下推完的 SQL |

---

## 3. 涉及模組與邊界

```
coffee-branches   新增例外日的讀寫與判定        依賴：shared、audit.api（皆為既有）
coffee-orders     零變更（繼續呼叫 requireOrderable，語意不變）
coffee-identity   零變更（繼續呼叫 requireOpen，本規格不碰它）
coffee-catalog    零變更
coffee-reporting  零變更
coffee-payments   零變更
```

**只有 `coffee-branches` 一個模組有 Java 變更。** 其他模組看到的是同一組 `Branches` 方法簽章（既有的四個一個字不改），只是回答變得更準。這是本規格能做到「純加法」的原因。

新增的 `DayOverride` record 放在 `coffee-branches/api`，跨模組可見；例外日的解析與 SQL 全部留在 `internal`。不得有任何模組 import `com.coffee.branches.internal.*`（`ModuleBoundariesTest` 會擋）。

前端 `modules/branches/overrides.ts` 放在 `modules/branches/`，與 `BranchesView.vue` 同目錄，不跨模組。它的純度規則沿用 G22 §3：**不得 import `vue`、`shared/api`、`identity/store`**，並用同款讀原始碼的測試釘住（驗收 18）。

---

## 4. DB schema 與 migration

檔名：**`V10__branch_hour_overrides.sql`**（GAP-ANALYSIS 已載明 V1–V9 全部進主線，下一份自 V10 起算）。

```sql
CREATE TABLE branch_day_overrides(
  branch_id VARCHAR(36) NOT NULL REFERENCES branches(id),
  on_date INTEGER NOT NULL,
  closed BOOLEAN NOT NULL,
  note VARCHAR(40) NOT NULL DEFAULT '',
  updated_at BIGINT NOT NULL,
  updated_by VARCHAR(36) NOT NULL REFERENCES accounts(id),
  PRIMARY KEY(branch_id,on_date)
);

CREATE TABLE branch_day_override_hours(
  id VARCHAR(36) PRIMARY KEY,
  branch_id VARCHAR(36) NOT NULL,
  on_date INTEGER NOT NULL,
  open_minute INTEGER NOT NULL CHECK(open_minute BETWEEN 0 AND 1439),
  close_minute INTEGER NOT NULL CHECK(close_minute BETWEEN 1 AND 1440),
  UNIQUE(branch_id,on_date,open_minute),
  FOREIGN KEY(branch_id,on_date)
    REFERENCES branch_day_overrides(branch_id,on_date) ON DELETE CASCADE
);

CREATE INDEX idx_branch_day_overrides_date ON branch_day_overrides(on_date);
```

### 4.1 `on_date` 的編碼：`INTEGER` 的 `yyyyMMdd`

**沿用專案既有的台北營業日編碼，不要自創。** `branch_products.sold_out_date` 是 `INTEGER`，值由 `CatalogService.today()` 產生（`CatalogService.java:269-272`）：

```java
Integer.parseInt(LocalDate.now(ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.BASIC_ISO_DATE))
```

即 `20261010`。本規格用完全相同的編碼與相同的時區來源。

**為什麼不用 `DATE` 欄位**：本專案測試跑 H2、生產跑 PostgreSQL，`DATE` 與 JDBC driver 之間的時區轉換行為在兩者不一致（這正是 G09 §5.2 拒絕用資料庫時區函式的同一類風險，症狀是「測試全綠但差一天」）。`INTEGER` 的 `yyyyMMdd` 沒有時區，排序與範圍查詢也照樣正確（`20260930 < 20261001`）。

**跨月／跨年的日期加減一律在 Java 裡用 `LocalDate`**，不要在 SQL 裡對 `yyyyMMdd` 做算術（`20260930 + 1 = 20260931`，不存在）。§6.3 會再強調一次。

### 4.2 為什麼是兩張表

一天有三種狀態，缺一不可：

| 狀態 | 意思 |
| --- | --- |
| **沒有例外列** | 用每週固定時段（現狀） |
| **有例外列且 `closed=true`** | 整天公休 |
| **有例外列且 `closed=false`** | 用本表 `branch_day_override_hours` 的時段 |

第一與第二種都對應「零個時段列」，所以**時段列的有無無法區分它們**。要區分就必須有一個「這一天被覆蓋了」的表頭列，而 `PRIMARY KEY(branch_id,on_date)` 正好讓「一天最多一個例外」由資料庫強制，不靠應用層記得。

單表加一個 `closed` 欄位 + 可為 NULL 的分鐘數做不到這件事：一天有三個時段就是三列，主鍵擋不了；而 `UNIQUE` 在 H2 與 PostgreSQL 都把 NULL 視為互不相同，所以「公休列只能有一列」也擋不住。兩張表是為了讓不變條件由 DB 而不是由註解維護。

### 4.3 不變條件

1. `closed=true` 的日期**不得有任何 `branch_day_override_hours` 列**
2. `closed=false` 的日期**至少要有一個時段列** —— 否則它與 `closed=true` 在行為上完全相同，等於同一件事有兩種寫法，日後必然分岔
3. 同一天的時段不得重疊，每天最多 4 段 —— 與 `branch_hours` 完全相同的規則，**直接重用 `BranchService.validateHours()` 的時段檢查邏輯**（§5.4）

前兩條由 `saveOverride()` 在同一個交易內強制（§5.4）。**不要**用 DB CHECK 去表達它們：跨表的 CHECK 兩個資料庫都不支援，寫成 trigger 是本專案沒有的技術，成本遠大於收益。

### 4.4 既有資料零遷移

兩張表都是新建、初始零列。**零列時 §6 的解析結果與目前 `isOpenAt()` 逐字相同**（§6.4 有證明），所以 migration 跑完、程式換上新解析器之後，既有分店的行為一個位元都不變。驗收 1 會驗這件事。

---

## 5. Api 與服務層

### 5.1 既有簽章一個字不改

```java
Branch requireOpen(String id);
List<Hours> hours(String branchId);
boolean openAt(String branchId, long atEpochMs);
Map<String, Boolean> openAt(List<String> branchIds, long atEpochMs);
Branch requireOrderable(String id, long atEpochMs);
List<Hours> saveHours(Actor actor, String branchId, List<Hours> hours);
```

這六個方法**簽章與語意都不動**，只有內部實作改成呼叫 §6 的解析器。`coffee-orders`、`coffee-identity` 零變更就是靠這一條。

### 5.2 `Branches` 新增

```java
record DayOverride(int onDate, boolean closed, String note, List<Hours> hours) {}

List<DayOverride> overrides(String branchId, int fromDate, int toDate);

DayOverride saveOverride(Actor actor, String branchId, DayOverride override);

void deleteOverride(Actor actor, String branchId, int onDate);
```

`DayOverride.hours()` 重用既有的 `Hours` record，但**`dayOfWeek` 欄位在例外日情境下無意義**。約定：

- 讀出來時 `dayOfWeek` 一律填該日期實際的星期（1–7，`LocalDate.getDayOfWeek().getValue()`），方便前端直接顯示
- 寫進來時 **`dayOfWeek` 一律忽略**，不驗證、不儲存。`branch_day_override_hours` 沒有 `day_of_week` 欄位

> 為什麼不新增一個只有兩個欄位的 `Period` record：`Hours` 已經在 api 上、前端型別也已經對齊，新增一個 90% 重疊的 record 會讓「該用哪一個」變成每次都要想一次的問題。忽略一個欄位比多一個型別便宜，代價是必須在這裡寫清楚 —— 已寫清楚。驗收 12 會釘住「送進來的 `dayOfWeek` 被忽略且不造成錯誤」。

### 5.3 `overrides()` 的讀取範圍與授權

```java
List<DayOverride> overrides(String branchId, int fromDate, int toDate)
```

- 分店不存在 → `Problem(404, "找不到分店")`（與 `hours()` 一致）
- `fromDate > toDate` → `Problem(400, "日期範圍不正確")`
- **範圍上限 400 天**：`toDate` 與 `fromDate` 相差超過 400 天 → `Problem(400, "日期範圍最多 400 天")`。這是無上限撈取的防線（G01a review 第 2 項留下的同一類教訓）。400 天讓「顯示未來一整年的假日」一次問完
- **不需要任何權限**，與 `hours()` 同級（登入即可讀）。營業時間與公休日是要給顧客看的資訊

回傳依 `on_date` 升冪，每個 `DayOverride` 的 `hours()` 依 `openMinute` 升冪。

### 5.4 `saveOverride()`

```java
@Transactional
DayOverride saveOverride(Actor actor, String branchId, DayOverride override)
```

步驟，順序不可換：

1. `actor.require("BRANCH_MANAGE")`
2. `if (!actor.global()) throw new Problem(403, "此功能限總部範圍")` —— 與 `saveHours()` 逐字相同
3. `Problem.check(override != null, "請提供例外日設定")`
4. 驗證 `onDate`：必須是**合法日期**。做法：`Problem.check` 包住 `LocalDate.parse(String.valueOf(onDate), DateTimeFormatter.BASIC_ISO_DATE)`，解析失敗 → `Problem(400, "日期格式不正確")`。**這一步不可省** —— `20261345` 是合法的 `INTEGER`，存進去之後永遠不會等於任何真實日期，變成一列永遠不生效又刪不掉的髒資料
5. 驗證 `note`：`note == null` 視為 `""`；長度上限 40 → 超過丟 `Problem(400, "備註請在 40 字內")`
6. `select branch_id from branches where id=? for update` 取不到 → `Problem(404, "找不到分店")`。**行鎖不可省**：本方法是「先刪後插」，兩個總部人員同時存同一天會交錯。鎖 `branches` 而不是 `branch_day_overrides`，因為尚無例外列時後者沒有列可鎖 —— 與 G13 §5.5 的同一條理由
7. 依 `closed` 分流驗證：
   - `closed=true` → `Problem.check(override.hours() == null || override.hours().isEmpty(), "公休日不能同時設定營業時段")`
   - `closed=false` → `Problem.check(override.hours() != null && !override.hours().isEmpty(), "請至少設定一個營業時段，或改為整天公休")`，然後把時段**當成同一天的時段**交給時段檢查
8. 時段檢查：抽出 `BranchService.validateHours()` 裡「單日」那一半，成為
   ```java
   static void validateDayPeriods(List<Hours> periods)
   ```
   規則：每段 `openMinute` 0–1439、`closeMinute` 1–1440、**最多 4 段**、同一天不得重疊。訊息逐字沿用既有的「開始時間不正確」／「結束時間不正確」／「每天最多 4 個時段」／「同一天的營業時段不能重疊」。
   **例外日不支援跨夜段**：`Problem.check(closeMinute > openMinute, "例外日的時段不能跨夜")`。理由見 §13.4
9. `delete from branch_day_override_hours where branch_id=? and on_date=?`
10. upsert `branch_day_overrides`：先 `update`，`==0` 時 `insert`（`updated_at` = `System.currentTimeMillis()`、`updated_by` = `actor` 的帳號 id）
11. `closed=false` 時逐段 insert 時段列
12. 稽核：
    ```java
    audit.record(actor, "BRANCH_HOURS_OVERRIDE_SAVE", branchId + ":" + onDate, branchId, summary);
    ```
    `summary` = `closed` 時 `"設定 20261010 公休（國定假日）"`，否則 `"設定 20261010 例外時段（2 段）"`。備註為空時省略括號
13. 回傳重讀後的 `DayOverride`

**`target_id` 長度**：`branchId`(36) + `:`(1) + `yyyyMMdd`(8) = **45 字元**，`audit_log.target_id` 是 `VARCHAR(80)`，不會截斷。**這個計算要寫在實作的註解裡** —— G13 v1.1 就是踩了 target_id 超長讓整筆設定回滾。
**`action` 長度**：`BRANCH_HOURS_OVERRIDE_SAVE` = 26 字元，`BRANCH_HOURS_OVERRIDE_DELETE` = 28 字元，欄位是 `VARCHAR(40)`，都安全。

### 5.5 `deleteOverride()`

```java
@Transactional
void deleteOverride(Actor actor, String branchId, int onDate)
```

1–2 步與 `saveOverride()` 相同（`BRANCH_MANAGE` + global）
3. `select branch_id from branches where id=? for update` → 取不到丟 404
4. `delete from branch_day_override_hours where branch_id=? and on_date=?`
5. `delete from branch_day_overrides where branch_id=? and on_date=?`
6. **回傳 0 列時不要丟 404。** 刪除一個不存在的例外日是**冪等成功**：呼叫端想要的最終狀態（這一天沒有例外）已經達成。丟 404 會讓「重按一次刪除鈕」變成錯誤訊息
7. 只有實際刪到列時才寫稽核 `BRANCH_HOURS_OVERRIDE_DELETE`。沒刪到就不寫 —— 稽核軌跡不記錄沒有發生的事

> `ON DELETE CASCADE` 已經會清掉時段列，第 4 步是刻意的顯式刪除：H2 與 PostgreSQL 的 cascade 行為一致，但顯式刪除讓「刪掉表頭卻留下孤兒時段列」在任何情況下都不可能發生，成本是一行 SQL。

---

## 6. 判定演算法（本規格的核心）

### 6.1 現況

`BranchService.isOpenAt(List<Hours> schedule, long atEpochMs)`（`BranchService.java:92-113`）是 `static`、純的，處理三種情形：當天的正常段、當天的跨夜起始段、**前一天**的跨夜尾段。且 `schedule.isEmpty()` → `true`（沒設定＝24 小時營業）。

### 6.2 新的解析器

把「取得某一天的有效時段」與「判斷某一刻是否在營業」拆成兩層。

```java
/** 某一天的有效營業狀態。 */
sealed interface DaySchedule {
  record AlwaysOpen() implements DaySchedule {}
  record Closed() implements DaySchedule {}
  record Periods(List<Hours> periods) implements DaySchedule {}
}

static DaySchedule resolveDay(
    LocalDate date,
    boolean weeklyEmpty,
    List<Hours> weeklyForThatWeekday,
    DayOverride overrideForThatDate /* 可為 null */);
```

規則，**順序就是優先序**：

1. `overrideForThatDate != null && closed` → `Closed`
2. `overrideForThatDate != null && !closed` → `Periods(override.hours())`
3. `weeklyEmpty` → `AlwaysOpen`
4. 其餘 → `Periods(weeklyForThatWeekday)`（該星期沒有列時就是空 list，代表那天不營業）

**第 3 條放在第 1、2 條之後是本規格最關鍵的一行。** 目前「完全沒有 `branch_hours` 列」的分店是 24 小時營業（示範資料就是這種）。如果先判 `weeklyEmpty`，那麼對這些分店設公休會完全沒有效果 —— 而「臨時公休」正是本規格最主要的用例。**例外日必須贏過「沒設定＝全天營業」這條預設。** 驗收 4 專門釘住這個組合。

### 6.3 `openAt` 的組合規則

```java
static boolean isOpenAt(Resolver resolver, long atEpochMs)
```

1. `local = Instant.ofEpochMilli(atEpochMs).atZone(TAIPEI)`
2. `today = local.toLocalDate()`、`previous = today.minusDays(1)` —— **用 `LocalDate.minusDays`，不要對 `yyyyMMdd` 做整數減一**（`20261001 - 1 = 20261000`）
3. `minute = local.getHour() * 60 + local.getMinute()`
4. 令 `D = resolveDay(today, ...)`、`P = resolveDay(previous, ...)`
5. **開著** ⟺ 下列任一成立：
   - `D` 是 `AlwaysOpen`
   - `D` 是 `Periods` 且某段 `close > open` 且 `open <= minute < close`（當天正常段）
   - `D` 是 `Periods` 且某段 `close <= open` 且 `minute >= open`（當天跨夜起始段）
   - **`P` 是 `Periods`** 且某段 `close <= open` 且 `minute < close`（前一天的跨夜尾段）

三條 `Periods` 的判斷式與目前 `isOpenAt()` 的三個 `anyMatch` 分支**逐字相同**，只是資料來源從「同一份 weekly schedule」換成「兩個各自解析過的日期」。

### 6.4 兩個必須寫進實作註解的邊界

**(a) `AlwaysOpen` 不貢獻跨夜尾段。** 第 5 步的第四條只接受 `P` 是 `Periods`。若 `P` 是 `AlwaysOpen`（完全沒設每週時段的分店）而 `D` 被設為 `Closed`，`P` 一旦能貢獻尾段，公休就永遠被前一天的「24 小時」蓋掉，整個功能形同失效。**`AlwaysOpen` 是「這一天全天開著」的陳述，不是「跨到隔天」的陳述。**

**(b) 例外日不繼承每週時段。** `closed=false` 的例外日是**完整替換**當天的時段，不是「在每週時段之上再加幾段」。要「當天多開兩小時」就把完整的時段寫進例外日。理由：合併語意有兩種都說得通的方向（聯集或覆蓋），任何一種都要在 UI 上向使用者解釋，而總部設「10/10 只開 09:00–12:00」時期望的必然是覆蓋。驗收 5 釘住這件事。

### 6.5 零例外列時的等價性

例外列為零時，對任何日期 `resolveDay` 都拿到 `override == null`，於是：
- `weeklyEmpty` → 兩天都是 `AlwaysOpen` → 第 5 步第一條成立 → `true`，與目前 `schedule.isEmpty() → true` 相同
- 否則 → `D`／`P` 都是該星期的 weekly 時段 → 三條判斷式與目前的三個 `anyMatch` 逐字相同 → 結果相同

**所以 S1 合併進主線時，既有行為零變更。** 驗收 1 會把這件事釘成測試：G14 留下的 `BranchHoursTest` 全部案例不得修改一個字元且必須全綠。

### 6.6 查詢次數

`openAt(List<String> branchIds, long)`（`BranchController.list()` 用它，`BranchService.java:71-85`）目前是**固定 1 次**全表查詢。加上例外日之後：

**固定 2 次**，與分店數無關：
1. 既有的 `select ... from branch_hours order by branch_id,day_of_week,open_minute`
2. `select ... from branch_day_overrides o left join branch_day_override_hours h on ... where o.on_date in (?,?)` —— 只取今天與昨天兩個日期

第 2 次查詢帶 `on_date in (today, yesterday)`，用 `idx_branch_day_overrides_date`，回傳列數上限是「分店數 × 2 天 × 4 段」。**不得對每個分店各查一次**（那是 G10 修掉的 N+1 的同一個形狀）。驗收 14 用 3 家分店 + 例外日的案例釘住輸出，查詢次數由 code review 認定（本專案目前沒有計數用的 DataSource proxy，G01a §4.2 留下的同一個限制）。

單店的 `openAt(String, long)` 與 `requireOrderable()` 同樣只多 1 次查詢（同樣 `in (today, yesterday)`）。

### 6.7 打烊訊息

`closedMessage()`（`BranchService.java:115-133`）目前輸出「分店今日未營業」或「分店目前未營業（今日營業時間 09:00–21:00）」。擴充為：

| 當天狀態 | 訊息 |
| --- | --- |
| `Closed` 且有備註 | `分店今日公休（國定假日）` |
| `Closed` 且無備註 | `分店今日公休` |
| `Periods` 且當天有段 | `分店目前未營業（今日營業時間 09:00–12:00）` ← 既有格式，時段來自**解析後**的當天 |
| `Periods` 且當天無段 | `分店今日未營業` ← 既有訊息 |
| `AlwaysOpen` | 不可能走到（`AlwaysOpen` 永遠是營業中） |

**備註是總部輸入的自由文字，會顯示給顧客。** 上限 40 字（§5.4 第 5 步）。前端一律當純文字綁定（Vue 的 `{{ }}` 本身就會轉義），**不得用 `v-html`** —— 驗收 19 會讀原始碼釘住這一條。

---

## 7. API

### 7.1 `GET /api/branches/{id}/hour-overrides`

| 項目 | 內容 |
| --- | --- |
| 授權 | 登入即可（與 `GET /api/branches/{id}/hours` 同級） |
| Query | `from`、`to`，格式 `yyyyMMdd` 的整數。皆可省略 |
| 預設 | `from` 省略 → 今天（台北）；`to` 省略 → `from` + 90 天 |

回應：

```json
{
  "branchId": "b1",
  "from": 20260929,
  "to": 20261228,
  "overrides": [
    { "onDate": 20261010, "dayOfWeek": 6, "closed": true, "note": "國慶日公休", "hours": [] },
    { "onDate": 20261231, "dayOfWeek": 4, "closed": false, "note": "除夕提早打烊",
      "hours": [{ "dayOfWeek": 4, "openMinute": 540, "closeMinute": 1020 }] }
  ]
}
```

`DayOverride` 在 JSON 上多一個 `dayOfWeek`（該日期的星期），由 controller 計算後放進回應，**不進 DB**。前端顯示「10/10（六）」時不必自己算星期。

錯誤：`404 找不到分店`、`400 日期範圍不正確`、`400 日期範圍最多 400 天`、`400 日期格式不正確`。

### 7.2 `PUT /api/branches/{id}/hour-overrides/{onDate}`

| 項目 | 內容 |
| --- | --- |
| 授權 | `BRANCH_MANAGE` + 總部範圍（`actor.global()`） |
| Path | `onDate` 為 `yyyyMMdd` |
| Body | `{ "closed": true, "note": "國慶日公休", "hours": [] }` |

**路徑上的 `onDate` 是唯一的日期來源。** body 若也帶 `onDate`，**以路徑為準並忽略 body 的值**，不要比對後丟 400 —— 兩個來源不一致時報錯只會讓呼叫端猜哪個才對，而路徑是 RESTful 的資源識別，它贏。驗收 13 釘住這條。

回應與 §7.1 的單一元素同形（含 `dayOfWeek`）。

錯誤：`403`（無權限或非總部）、`404 找不到分店`、`400 日期格式不正確`、`400 公休日不能同時設定營業時段`、`400 請至少設定一個營業時段，或改為整天公休`、`400 例外日的時段不能跨夜`、`400 每天最多 4 個時段`、`400 同一天的營業時段不能重疊`、`400 備註請在 40 字內`。

### 7.3 `DELETE /api/branches/{id}/hour-overrides/{onDate}`

| 項目 | 內容 |
| --- | --- |
| 授權 | 同 §7.2 |
| 回應 | `204 No Content` |

不存在的日期也回 `204`（§5.5 第 6 步的冪等）。

### 7.4 `SecurityConfiguration` 一個字不動

主線的 `SecurityConfiguration` 對所有 `/api/**` 一律 `authenticated()`。三個新端點都在 `/api/branches/**` 之下，**自動被現有規則涵蓋**。

> G14 v1.1 就是在這裡出過錯：規格原本寫「公開端點」，但 `GET /api/branches` 本身就不是公開的，照原文施工必然回 401。本規格的「登入即可讀」指的就是現況，**不需要也不得修改 `SecurityConfiguration`**。驗收 20 會驗它零變更。

### 7.5 `PUT`／`DELETE` 走 CSRF

主線對非 GET 一律要求 CSRF token。兩個寫入端點照既有規則，**不做任何豁免**。驗收 17 要有一條「無 CSRF token 的 `PUT` 回 403」的測試，而且**必須與越權測試打不同的情境**（有 token 的合法總部呼叫要成功、無 token 的同一個呼叫要 403）—— G01a §3 就是因為兩次 POST 都打跨店訂單、都期望 403，導致 CSRF 關掉也照樣通過。

---

## 8. 權限與資料範圍

| 操作 | 權限 | 資料範圍 |
| --- | --- | --- |
| `GET /api/branches/{id}/hour-overrides` | 登入即可 | 全部分店（營業資訊本來就要給顧客看） |
| `PUT /api/branches/{id}/hour-overrides/{d}` | `BRANCH_MANAGE` 且 `actor.global()` | 全部分店 |
| `DELETE …` | 同上 | 同上 |

**不新增任何權限常數，因此不需要 `role_permissions` 的 migration。** 沿用 `BRANCH_MANAGE`（G14 §5 的同一條決定）。V10 只有兩個 `CREATE TABLE` 與一個 `CREATE INDEX`，沒有任何 `INSERT INTO role_permissions`。驗收 16 會驗這件事。

**越權測試（必要，不可只測 happy path）**：

1. `MANAGER`（branch scope，**有** `BRANCH_MANAGE`）呼叫 `PUT` → **403**，訊息「此功能限總部範圍」。這一條是 §13.3 決定的直接後果，也是最容易被實作成 200 的一條
2. `CASHIER`（無 `BRANCH_MANAGE`）呼叫 `PUT` → **403**
3. 顧客（scope `SELF`）呼叫 `PUT` → **403**
4. 顧客呼叫 `GET /api/branches/{id}/hour-overrides` → **200**（刻意可讀）
5. 未登入呼叫 `GET` → **401**（由 `SecurityConfiguration` 提供，驗證前提沒被打破）

---

## 9. 金額規則

**本規格不涉及任何金額。** 不讀、不寫、不計算價格、折扣或收款。

但有一條相關的界線要留下：例外日只影響「顧客能不能建立訂單」（`requireOrderable()`），**不影響任何已存在訂單的金額或狀態**。公休日仍然可以對前一天的訂單收款、轉狀態、對帳 —— `OrderService.cash()`、`transition()` 都不呼叫 `requireOrderable()`，本規格也不得讓它們開始呼叫。驗收 9 用「公休日對前一日訂單收現金成功」釘住這條。

---

## 10. 施工階段

三個階段。每階段獨立 CI 綠、獨立可合併、有自己的驗收子集。**全部是加法。**

### S1 — schema 與判定演算法（無端點、無 UI）

**規模**：`V10__branch_hour_overrides.sql`（新）、`Branches.java`（加 record 與三個方法簽章）、`BranchService.java`（解析器 + 讀寫實作）、`BranchHourOverrideTest.java`（新，只測 service 層）。四個檔。

**做什麼**

1. 寫 V10
2. `Branches` 加 `DayOverride` 與 `overrides()` / `saveOverride()` / `deleteOverride()`
3. `BranchService` 實作 §6 的 `resolveDay()` 與新的 `isOpenAt()` 組合；`openAt()`（單店與批次）、`requireOrderable()`、`closedMessage()` 改用它
4. 實作三個新方法；抽出 `validateDayPeriods()`
5. 測試：§6 的全部組合 + §5 的驗證規則 + `BranchHoursTest` 原封不動全綠

**驗收子集**：1、2、3、4、5、6、7、8、11、12、16、20

**為什麼獨立可合併**：兩張新表初始零列，§6.5 證明零列時行為與現狀逐字相同；新方法沒有端點，外界呼叫不到。合進主線對任何既有行為是零影響。

> 這一階段就算後兩階段永遠沒做，schema 與演算法已經在，是永久的進度。

### S2 — 三個端點、授權與稽核

**規模**：`BranchController.java`（三個端點）、`BranchHourOverrideTest.java`（補 HTTP 層與越權、CSRF）。兩個檔。

**做什麼**

1. §7 的三個端點，含 `dayOfWeek` 的計算與 `from`／`to` 的預設值
2. §8 的五條越權測試 + §7.5 的 CSRF 測試
3. 稽核兩個 action 寫入與 `target_id` 長度註解（§5.4 第 12–13 步已在 S1 實作，S2 補 HTTP 層的驗證）

**驗收子集**：9、10、13、14、15、17、18（後端半）

**為什麼獨立可合併**：只新增端點，既有端點零變更。

### S3 — 前端：總部例外日編輯與顧客端訊息

**規模**：`types.ts`、`overrides.ts`（新）、`overrides.spec.ts`（新）、`BranchesView.vue`、`MenuView.vue`。五個檔。

**做什麼**

1. `types.ts` 加 `BranchDayOverride`
2. `modules/branches/overrides.ts`：純函式
   - `sortOverrides(list)` —— 依 `onDate` 升冪
   - `overrideSummary(o)` —— 回顯示文字：公休 → `"公休"` 或 `"公休 · 國慶日"`；有時段 → `"09:00–17:00"`（多段以 `"、"` 連接），重用 `minuteTime()`
   - `formatOnDate(onDate)` —— `20261010` → `"10/10（六）"`。**用 `LocalDate` 等價的純算術或 `Date`，不要用 `Intl`**（G22 §13.8：`Intl` 的輸出隨 Node 的 ICU 版本漂移，釘進測試會製造假紅燈）
3. `overrides.spec.ts`：vitest，涵蓋上述三支（G22 的框架已在，`npm run test` 已進 CI）
4. `BranchesView.vue` 的時段 Modal 增加「例外日」區塊：列出 `from=今天 to=+90 天` 的例外，可新增／改／刪一天；公休與「自訂時段」二選一
5. `MenuView.vue`：顧客端打烊時顯示後端的訊息（含備註）。**`hours-status` 區塊已存在（`MenuView.vue:408`），只換文字來源，不改版面**

**驗收子集**：18（前端半）、19、21、22

**為什麼獨立可合併**：`overrides.ts` 是新檔；兩個 `.vue` 的改動都是新增區塊或換文字來源，不動既有元素。

### 10.1 為什麼切得開

三階段動的是不相交的檔案集合（S1 後端 service + migration，S2 後端 controller，S3 前端），而且每階段的新增都不被前一階段的**介面變更**牽動 —— S1 之後 api 簽章就定了，S2、S3 只是接上去。S1 合併後 S2 從主線重拉分支不會衝突。

S1 是三者中最大的一個（一張 migration + 解析器重寫 + 完整單元測試），但它不含任何端點、UI 或相依變更，**單次執行做得完**。真的做不完時，安全的中間狀態是：**只推 V10 與 `resolveDay()` + 其單元測試，`openAt()` 暫時仍用舊的 `isOpenAt()`**（新表零列時兩者結果相同，所以既有測試照樣全綠），下一次執行再把呼叫端切過去。這個中間狀態可編譯、CI 綠、行為零變更。

### 10.2 PR 描述請維護這張表

```markdown
## 施工進度（G19）
- [ ] S1 schema 與判定演算法
- [ ] S2 三個端點、授權與稽核
- [ ] S3 前端例外日編輯與顧客端訊息
```

---

## 11. 驗收條件

逐條可勾選。

**S1**

1. [ ] **零例外列時行為零變更**：`BranchHoursTest` 的既有案例**一個字元都不修改**且全綠；`BranchHoursAdminTest` 同樣全綠
2. [ ] `resolveDay()` 的四條優先序各有測試：`closed` 例外 → `Closed`；有時段例外 → `Periods(例外的時段)`；無例外且 `weeklyEmpty` → `AlwaysOpen`；無例外且該星期有列 → `Periods(該星期的列)`
3. [ ] 無例外且該星期**沒有**列（例如只設週一到週五的分店在星期六）→ `Periods(空)`，`openAt` 回 `false`
4. [ ] **例外日贏過「沒設定＝全天營業」**：一家 `branch_hours` 零列的分店，設某日 `closed=true`，該日任一時刻 `openAt` 回 `false`，而前一日與後一日回 `true`（§6.2 第 3 條的順序）
5. [ ] **例外日是覆蓋不是相加**：每週時段 09:00–21:00 的分店，某日例外設 14:00–16:00 → 該日 10:00 回 `false`、15:00 回 `true`（§6.4b）
6. [ ] **跨夜尾段的兩個方向**：(a) 每週段 22:00–隔日 02:00，隔日 01:00 回 `true`（既有行為不變）；(b) 前一日是 `AlwaysOpen`（`weeklyEmpty`）而當日 `closed=true` 時，當日 01:00 回 **`false`**（§6.4a，`AlwaysOpen` 不貢獻尾段）
7. [ ] `saveOverride()` 驗證：`closed=true` 帶時段 → 400；`closed=false` 不帶時段 → 400；`closeMinute <= openMinute` → 400「例外日的時段不能跨夜」；5 段 → 400；重疊 → 400；`note` 41 字 → 400；`onDate=20261345` → 400「日期格式不正確」
8. [ ] `deleteOverride()` 刪不存在的日期**不丟錯**且不寫稽核；刪存在的日期會連帶刪掉時段列（查 `branch_day_override_hours` 為零列）
11. [ ] `overrides()`：分店不存在 → 404；`from > to` → 400；相差 401 天 → 400；正常範圍依 `on_date` 升冪且每日時段依 `openMinute` 升冪
12. [ ] 寫入時 body 的 `Hours.dayOfWeek` **被忽略**：送 `dayOfWeek=3` 存到一個星期六的日期，不報錯，讀回來的 `dayOfWeek` 是 `6`
16. [ ] `V10__branch_hour_overrides.sql` 只有兩個 `CREATE TABLE` 與一個 `CREATE INDEX`，**沒有任何 `INSERT INTO role_permissions`**；`db/migration/` 沒有其他新增檔案
20. [ ] `SecurityConfiguration` 零變更（`git diff --name-only` 不含它）；`coffee-orders`、`coffee-identity`、`coffee-catalog`、`coffee-reporting`、`coffee-payments` 五個模組零變更

**S2**

9. [ ] **公休不擋既有訂單**：分店今日 `closed=true`，對前一日建立的 `PENDING_PAYMENT` 訂單 `POST /orders/{id}/cash` **成功**；顧客 `POST /orders` 回 400 且訊息是「分店今日公休…」
10. [ ] `requireOrderable()` 的訊息四種情形逐字比對（§6.7 的表）
13. [ ] `PUT` 的 body 帶一個與路徑不同的 `onDate` → **以路徑為準**，存進去的是路徑那一天
14. [ ] `GET /api/branches` 的 `openNow` 在有例外日時正確：3 家分店，一家今日公休、一家例外時段中、一家照每週時段 → 三個 `openNow` 各自正確，**且整支請求不因分店數增加而增加查詢次數**（§6.6，查詢次數由 code review 認定）
15. [ ] 稽核：`PUT` 寫入 `BRANCH_HOURS_OVERRIDE_SAVE`、`DELETE` 寫入 `BRANCH_HOURS_OVERRIDE_DELETE`，`target_id` 為 `branchId:yyyyMMdd`、`branch_id` 欄位為該分店，且**可由 `GET /api/audit` 查到**
17. [ ] **CSRF**：有 token 的總部 `PUT` → 2xx；**同一個呼叫**去掉 token → 403。兩條必須是同一個情境的有／無 token 對照（不得像 G01a §3 那樣兩條都打越權情境）
18. [ ] §8 的五條越權測試全部通過，特別是 `MANAGER` 有 `BRANCH_MANAGE` 但非總部 → 403
     - [ ] `overrides.ts` **不 import `vue`、`shared/api`、`identity/store`**，用 G22 §12.4 同款的讀原始碼測試釘住（此子項屬 S3）

**S3**

19. [ ] `BranchesView.vue` 與 `MenuView.vue` 顯示備註時**不使用 `v-html`**，由讀原始碼的測試斷言兩個檔案都不含 `v-html`
21. [ ] `overrides.spec.ts` 覆蓋 `sortOverrides`（含相同日期的穩定性）、`overrideSummary`（公休無備註／公休有備註／單段／多段）、`formatOnDate`（月初、月底、跨年、星期正確）
22. [ ] `npm run test` 與 `npm run build` 皆綠；總部可在 UI 上新增一個公休日、看到它出現在清單、刪除它；顧客端在該日看到公休訊息（此項由 S3 的原始碼佐證 + 互審覆核，沿用 G07 §11.11 的做法，**不押在實機操作上**）

---

## 12. 測試要求

### 12.1 時間一律注入，不得用 `System.currentTimeMillis()`

`openAt`／`requireOrderable` 都收 `atEpochMs`，測試一律傳固定值。`BranchHoursTest` 已有 `taipei(...)` helper（`BranchHoursTest.java:84` 附近），**重用它，不要另寫一個**。

`saveOverride()` 內部的 `updated_at` 與 `deleteOverride()` 的稽核時間可以用 `System.currentTimeMillis()`（與 `CatalogService.setAvailability()` 一致），但**任何影響「今天是哪一天」的判斷都不得用它** —— 那會讓測試在台北時間午夜前後變成隨機紅燈。

### 12.2 `today` 的預設值要能被測試控制

§7.1 的 `from` 預設「今天」。controller 取 `System.currentTimeMillis()`，但 service 層的 `overrides(branchId, from, to)` 收的是明確的整數，**預設值的計算留在 controller**。這樣 service 層的測試完全不依賴當前時間；預設值本身由一條 HTTP 測試「不帶 `from`／`to` 時回 200 且 `from` 等於今天」覆蓋。

### 12.3 越權測試不可只看狀態碼

403 要連訊息一起斷言（「此功能限總部範圍」對「權限不足」是兩種不同的失敗），否則「權限檢查寫錯但剛好也回 403」會被放過。

### 12.4 不要為了讓測試通過而放寬測試

`AGENTS.md` 禁止事項第 7 條。§6 的演算法與既有 `isOpenAt()` 對不起來時，**要改的是新演算法**，不是把 `BranchHoursTest` 的斷言改掉 —— 驗收 1 明文要求它一個字元都不改。

---

## 13. 設計決策

每一項都是 Claude 定案。附理由與推翻它的代價。

### 13.1 G19 升為 P1，作為 G22 之後的下一份 —— **升**

**決定**：從 P2 升到 P1，排在 G22（PR #39，已合併）之後。

**理由**：G22 合併後 P1 清空、規格庫存為 0。P2 剩下的項目逐一檢視（沿用 G09 §11.5 的同一套判準）：

| 候選 | 為什麼不是它 |
| --- | --- |
| G16 顧客自助註冊 | 涉及濫用防護、驗證信、個資，是產品與法遵決策，**不在 Claude 的授權範圍** |
| G05 Session 集中化 | 要引入 Redis／Spring Session，違反「不得引入新依賴」的預設；且單店單機營運下不是阻擋 |
| G08 庫存扣減 | 輕量版（今天賣完）已由 G13 做掉，完整庫存管理需要真實進貨資料 |
| G17／G20／G21 | 都明文登記要先有真實營運資料才值得設計 |
| G23 前端元件層測試 | 純加法、不擋任何人（G22 §13.3 明文），而且**連續兩份測試基礎設施規格**而業務缺口還開著，順序不對 |
| G12 外送與硬體印單 | 依賴外部選型 |
| **G19 例外營業日** | ✅ 業務缺口、每年必然遇到、資料形狀已由 G14 §11.3 確定為純加法、不需要新相依、不需要產品決策、驗收客觀 |

**推翻它的代價**：把 G19 押後，就是繼續用「切 `active` 一天」當公休 —— 而 §1.2 已經列出那個方案的三個具體成本，其中「要有人記得切回來」是會直接損失一整天營收的那種。

### 13.2 例外日綁「日期」而不是「時間範圍」 —— **綁日期**

**決定**：例外的單位是一個台北日曆日（`yyyyMMdd`），不是一段任意的起訖時間。

**理由**：與 `branch_hours` 同構（G14 §11.3 登記時就是這個判斷），也與 `branch_products.sold_out_date` 的日粒度一致。真實用例全部是「某一天」：公休、颱風天、除夕提早打烊。「連續五天盤點」就是五列，寫五次比設計一套區間模型便宜太多。

**推翻它的代價**：要改成區間就得引入 `from_date`／`to_date` 與區間重疊檢查，而且「區間與單日混用時誰贏」又是一個新的優先序問題。**但推翻是加法** —— 日後要做批次設定，可以在 UI 層讓一次操作展開成 N 次 `PUT`，DB 一個字都不用改。這是選日粒度的附帶好處。

### 13.3 維持總部權限，店長不能設自己分店的例外日 —— **總部限定**

**決定**：`PUT`／`DELETE` 要求 `BRANCH_MANAGE` **且** `actor.global()`，與 `saveHours()` 逐字相同。

**理由**：三點。(1) 不新增權限常數就不需要 `role_permissions` 的 migration，V10 因此只有 DDL，風險最小；(2) 分店設定在本系統一路都是總部的範圍（`saveHours`、`save`、`monthlyTarget` 全部如此），這裡開一個特例會讓「分店設定誰能改」變成要逐項記憶的規則；(3) 公休日在多店連鎖通常是公司層級的決定，總部設得到。

**這個決定放棄了什麼**（要誠實寫下來）：**颱風天的反應速度。** 颱風天下午三點決定停止營業，照本規格必須找總部帳號的人動手，店長只能打電話。這是真實的成本，不是假設。

**推翻它的代價**：**低，而且是加法。** 要開放給店長，需要 (a) 一個新權限常數（例如 `BRANCH_HOURS_OVERRIDE`）與一支 `role_permissions` 的 migration、(b) `saveOverride()` 的授權改成「global 或 `actor.branchId().equals(branchId)`」、(c) 三條新的資料範圍測試（店長改別店 → 403）。既有的總部路徑一個字不用改。登記為 **G24**，排在 P2。**若 PO 或營運端反映颱風天的反應速度是實際痛點，G24 應立刻升排** —— 那是營運事實，不是設計偏好。

### 13.4 例外日不支援跨夜時段 —— **不支援**

**決定**：例外日的每一段都必須 `closeMinute > openMinute`，違反丟 400。

**理由**：跨夜段的語意是「這一段延續到隔天」，而例外日的整個設計前提是「一天覆蓋一天」。允許例外日跨夜會立刻生出一個沒有好答案的問題：**10/10 的例外段延到 10/11 02:00，而 10/11 也被設為公休，哪個贏？** 兩種答案都說得通（例外段是 10/10 的營業、公休是 10/11 的狀態），而選錯的症狀是「公休日凌晨還能下單」。

不支援的代價很小：真實的跨夜例外只有跨年夜，而它可以寫成「12/31 開到 24:00」+「1/1 從 00:00 開始」兩列，行為完全一樣且沒有歧義。

**推翻它的代價**：要支援就必須先回答上面那個優先序問題，並為它寫測試。§6.3 第 5 步的第四條也要放寬成接受例外日的尾段。這是可做的，但**不要在沒有真實需求時預支**。

### 13.5 不做最後點餐時間（last order） —— **不做，另立 G25**

**決定**：G14 §2 把「即將打烊提示／最後點餐」掛給 G19「一併考慮」，本規格明確排除。

**理由**：它與例外日是兩個不同的東西。例外日是「哪一天營業」，最後點餐是「營業時段之內再切一個更早的下單截止點」—— 它要動的是 `branch_hours` 的欄位（每段多一個 `last_order_minute`）或一個分店層級的「打烊前 N 分鐘停止收單」設定，而且會改到 `requireOrderable()` 的判斷式本體。塞進 G19 會讓 S1 同時改兩張表的語意，失去「零例外列時行為零變更」這個讓 S1 可獨立合併的性質。

**推翻它的代價**：很低，而且是加法（在 `branch_hours` 加一個可為 NULL 的欄位，NULL＝沿用 `close_minute`）。登記為 **G25**，排在 P2。

### 13.6 不做國定假日自動匯入 —— **不做**

**決定**：不讀行事曆 API、不內建假日表。

**理由**：(1) 讀外部 API 要新增相依與網路呼叫，而 Codex 的環境連 Maven Central 都解析不到（G07 §11.11 的紀錄），無法驗證；(2) 內建假日表要每年維護，忘了更新就是一張說謊的表；(3) **台灣的國定假日不等於咖啡廳公休** —— 很多店在假日生意最好。自動匯入會替使用者做一個他多半不想要的決定。手動設 5–10 天，一年一次的操作成本可接受。

**推翻它的代價**：低。`PUT` 是單日冪等的，任何匯入工具都只是在呼叫端展開成 N 次 `PUT`，不需要改 schema 或 api。

### 13.7 不做重複規則 —— **不做**

**決定**：不支援「每月第一個星期一公休」這類規則。

**理由**：重複規則要一套展開引擎（到哪一天為止？改了規則要不要回溯？某一次例外要怎麼取消？），而每週固定的部分 `branch_hours` 已經處理了。真實需求落在「每週固定」與「單一日期」之間的，數量極少。

**推翻它的代價**：中。要做就是新增一張規則表與展開邏輯，且必須決定「規則展開出來的日期」與「手動設的單日例外」誰贏（正確答案是手動贏）。單日例外表可以原封不動保留，所以仍是加法。

### 13.8 `deleteOverride()` 刪不存在的日期回成功 —— **冪等**

**決定**：不丟 404。

**理由**：呼叫端要的最終狀態是「這一天沒有例外」，而那已經成立。刪除鈕重按一次、或兩個總部人員同時刪同一天，都不該看到錯誤。這與 `PUT` 的 upsert 語意一致（兩者都是「把狀態設成這樣」而不是「執行一次動作」）。

**推翻它的代價**：改成 404 只會讓前端多寫一段「404 也算成功」的例外處理。不值得。

### 13.9 例外日的讀取不需要權限 —— **登入即可**

**決定**：`GET /api/branches/{id}/hour-overrides` 與 `GET /api/branches/{id}/hours` 同級，登入即可讀。

**理由**：營業時間與公休日是**本來就要給顧客看**的資訊，藏起來沒有任何保護價值，只會讓顧客端得多開一個端點或多一套權限。備註是總部自己輸入的對外文字（§6.7 已註明它會顯示給顧客），不是內部資料。

**推翻它的代價**：若日後備註要放內部訊息（例如「店長休假」），就得把它拆成對外／對內兩個欄位，或在回應中依權限裁剪。屆時再處理，**但要記得：現在的 40 字備註是對外的**，不要在它裡面寫內部資訊。

### 13.10 `AlwaysOpen` 不貢獻跨夜尾段 —— **不貢獻**

**決定**：見 §6.4a。

**理由**：若 `AlwaysOpen` 能貢獻尾段，任何一家沒設每週時段的分店（示範資料就是這種）都無法被設公休 —— 前一天的「24 小時」會永遠蓋掉今天的公休。那等於整個 G19 對這批分店失效，而它們正是最需要臨時公休的那批。

**推翻它的代價**：沒有值得推翻的理由。這條是「例外日必須贏」這個核心目標的直接推論，推翻它就等於推翻 §1.3 第 2 項。**寫下來是因為實作時很容易為了「統一處理」而讓 `AlwaysOpen` 走同一條路徑，然後被一條測試抓到才回頭想為什麼。** 驗收 6(b) 就是那條測試。
