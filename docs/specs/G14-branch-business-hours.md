# G14 — 分店營業時間

| 項目 | 內容 |
| --- | --- |
| 版本 | v1.0 |
| 日期 | 2026-09-20（Asia/Taipei） |
| 對應缺口 | G14（`docs/GAP-ANALYSIS.md` P1） |
| 主要模組 | `coffee-branches`（新增資料與 API）、`coffee-orders`（只透過 `Branches` 的 `api` 呼叫） |
| 前置相依 | 無。G11+G15（PR #22）與 G13（PR #20）都已合併，`coffee-audit` 的公開 API 可直接使用 |
| 與 G10 的關係 | 不相交。G10 動 `OrderService.list()` / `page()`（讀），本規格動 `OrderService.create()`（寫）的一行查核。兩者可並行，但依 `AGENTS.md`「一次一份」仍序列化 |
| Flyway | **預期 V8**（V7 保留給 G10）。若開工時 G10 尚未合併，V7 就是空的 —— **一律以開工當下 `backend/coffee-app/src/main/resources/db/migration/` 裡下一個未使用號為準**，並在 PR 描述寫明實際用了哪一號 |
| 施工階段 | S1 / S2 / S3，三段。S1、S2 純加法，唯一的行為變更集中在 S3 |

---

## 1. 背景與目標

### 問題

`BranchService.requireOpen()`（`backend/coffee-branches/src/main/java/com/coffee/branches/internal/BranchService.java:40-45`）只做一件事：

```java
return db.query("select * from branches where id=? and active=true", this::row, id)...
```

`branches` 表（`V1__coffee_schema.sql:1`）除了 `active BOOLEAN` 之外**沒有任何時間欄位**。後果有三層，一層比一層麻煩：

**第一層 —— 凌晨三點照樣能下單。** 顧客自助點餐沒有任何時段限制。店裡沒有人，訂單照樣進系統，隔天早上才有人看到。

**第二層 —— `active` 被挪用成每日開關門開關，但它不是那個語意。** 目前店家唯一能做的就是手動切 `active`。而 `active=false` 的真正語意是「這家分店停業／尚未開幕」，被拿來當日常開關會踩到：

- 忘了切回來 → 整個白天不能接單，而且沒有任何告警
- `active=false` 時 `IdentityService.saveAccount()`（`IdentityService.java:93`）也會擋下來 —— **打烊期間總部不能新增或調整該分店的員工帳號**，錯誤訊息還是「分店不存在或已暫停營業」
- `BranchService.list(actor, manage=false)` 會把該分店從清單裡整個拿掉，顧客端看到的是「這家店不存在」，不是「這家店現在沒開」

**第三層 —— 沒有任何紀錄能回答「這家店昨天幾點打烊」。** 營業時間不是一個設定，是一個對外承諾。目前它只存在於店長的記憶裡。

### 目標

1. 每家分店可設定**每週固定營業時段**，支援一天分多段（午休、早午餐與晚餐分場）
2. 後端強制：非營業時間的**顧客自助下單**被擋下，錯誤訊息講得出營業時間
3. 顧客端看得到「現在有沒有開」與完整營業時間，而不是分店直接從清單消失
4. `active` 回歸它原本的語意（停業／未開幕），不再被當成每日開關
5. 時段的每一次修改都進稽核軌跡

### 不是目標

- **不做例外日曆**（國定假日、臨時公休、颱風天、提早打烊）→ 另立 **G19**，見 §11.3
- **不自動切換 `active`**。`active` 與營業時段是兩個獨立的維度，互不寫入對方
- **不引入排程作業**（`@Scheduled` / quartz）。是否營業一律在查詢的當下計算
- **不做跨時區**。全系統固定 `Asia/Taipei`（`AGENTS.md`「時間」）
- **不擋員工 POS 下單**，見 §11.1
- **不擋已存在訂單的後續操作**（付款、狀態轉換、取消）。打烊不能讓白天的訂單卡在半路，見 §6.2

---

## 2. 範圍

### 在範圍

| # | 項目 |
| --- | --- |
| 1 | `branch_hours` 表與 migration |
| 2 | `Branches` api 新增 `Hours` record、`hours()`、`saveHours()`、`openAt()`、`requireOrderable()` |
| 3 | `GET /api/branches/{id}/hours`（公開讀取）、`PUT /api/branches/{id}/hours`（總部維護） |
| 4 | `GET /api/branches` 回應增加 `openNow` |
| 5 | `OrderService.create()` 的顧客端時段查核 |
| 6 | 總部分店管理頁的時段編輯 UI、顧客端的「營業中／已打烊」顯示 |
| 7 | 稽核 `BRANCH_HOURS_SAVE` |
| 8 | 測試：判定演算法單元測試、越權、CSRF、邊界與跨夜 |

### 不在範圍

| # | 項目 | 去哪裡 |
| --- | --- | --- |
| 1 | 例外日／公休日／臨時調整 | G19（本規格登記） |
| 2 | 「即將打烊」提示、最後點餐時間（last order） | G19 一併考慮 |
| 3 | 自動切換 `active` | 不做，§11.5 |
| 4 | 依時段切換菜單（早餐菜單／下午茶菜單） | 未登記；要做的話是 G13 `branch_products` 的延伸，不是本規格 |
| 5 | 分店特休、排班、人力 | 不在本系統範圍 |

---

## 3. 涉及模組與邊界

```
coffee-branches   新增 branch_hours 的讀寫與判定        依賴：shared, audit.api（皆為既有）
coffee-orders     呼叫 branches.requireOrderable()      依賴：branches.api（既有）
coffee-app        migration、前端組裝                    組裝層
```

- **不新增模組、不新增相依邊**。`coffee-branches` 對 `coffee-audit` 的依賴在 G11 已經建立（`BranchService` 已 `import com.coffee.audit.api.Audit`）
- `coffee-orders` 只碰 `Branches` 這個 `api` interface，**不得**直接查 `branch_hours`
- 判定「現在有沒有開」的邏輯**只能有一份**，放在 `coffee-branches`。前端可以自己算來顯示，但**後端的答案才算數**

### 邊界規則（`ModuleBoundariesTest` 會驗）

1. `coffee-orders` 不得 import `com.coffee.branches.internal.*`
2. `branch_hours` 的 SQL 只出現在 `coffee-branches.internal`
3. 不產生新的循環：`orders → branches.api` 是既有方向，本規格不新增反向依賴

---

## 4. DB schema 與 migration

新增一支 migration（預期 `V8__branch_business_hours.sql`，版號以開工當下目錄為準）：

```sql
CREATE TABLE branch_hours(
  id VARCHAR(36) PRIMARY KEY,
  branch_id VARCHAR(36) NOT NULL REFERENCES branches(id),
  day_of_week SMALLINT NOT NULL CHECK(day_of_week BETWEEN 1 AND 7),
  open_minute INTEGER NOT NULL CHECK(open_minute BETWEEN 0 AND 1439),
  close_minute INTEGER NOT NULL CHECK(close_minute BETWEEN 1 AND 1440),
  UNIQUE(branch_id, day_of_week, open_minute)
);
CREATE INDEX idx_branch_hours_branch ON branch_hours(branch_id);
```

- `day_of_week` 用 **ISO-8601：1 = 星期一 … 7 = 星期日**，與 `java.time.DayOfWeek.getValue()` 完全一致。**不要**用 0-based，也不要用 `java.util.Calendar` 的 1=Sunday
- `open_minute` / `close_minute` 是**台北當地時間**的「距午夜分鐘數」。09:30 → 570
- `UNIQUE(branch_id, day_of_week, open_minute)` 只擋完全同起點的重複列；**重疊要靠 service 驗**（§5.5），DB 做不到

**migration 不寫入任何資料列。** 既有分店在升級後一列都沒有，依 §4.2 視為全時段營業，行為與升級前完全相同。

### 4.1 為什麼是一張表，不是在 `branches` 加欄位

在 `branches` 加 `mon_open` / `mon_close` … 共 14 個欄位，是能存一週時段的最短路徑，但：

- **存不了分段營業**（11:00–14:00 + 17:00–21:00）。而分段是咖啡廳的常態，不是特例
- 14 個欄位要 14 個 nullable 判斷，`Branch` record 會從 6 個欄位膨脹到 20 個
- 日後要加例外日（G19）時，那張表的形狀本來就是「一段時間」，與本表同構；欄位式的 schema 到時候要整個重做

一張 `branch_hours` 的額外成本只有一次 join 或一次額外查詢，而它一次解決分段、可變段數與未來的例外日。

### 4.2 沒有任何 `branch_hours` 列 = 24 小時營業

**設計決策。** 一家分店在 `branch_hours` 裡一列都沒有時，`openAt()` 一律回 `true`。

理由：

- **既有資料零遷移。** 升級後所有分店維持現狀，S1 與 S3 都可以單獨合併而不改變任何既有行為 —— 這是能把破壞性變更壓縮進 S3 的前提
- 另一個選項是「沒有列 = 全天不營業」，那會讓 migration 一跑完，全部三家分店立刻不能接單。這種 migration 不能單獨合併，整份規格就切不開階段了
- 第三個選項是「migration 寫入預設 09:00–21:00」，等於 migration 在替店家做商業決定，而且改變了既有行為

**代價：** 新建立的分店預設 24 小時營業，店長可能沒注意到要設。所以 S2 的 UI **必須**在沒有任何時段時顯示明確提示（「目前未設定營業時間，視為 24 小時營業」），而不是顯示一張空表了事。這是 UI 的驗收條件，不是建議。

**推翻它的代價：** 若日後要改成「沒設定 = 不營業」，要先確保所有既有分店都已設定時段（一支資料檢查 + 一次資料補寫），再改判定。改判定本身是一行，難的是資料。

### 4.3 `close_minute` 可以 ≤ `open_minute`，代表跨夜

窗口定義為**左閉右開** `[open_minute, close_minute)`。

- `close_minute > open_minute` → 當日窗口。`(540, 1260)` = 09:00–21:00
- `close_minute ≤ open_minute` → **跨夜窗口**，結束時間落在隔天。`(1320, 120)` = 22:00–隔天 02:00
- `close_minute = 1440` → 當日午夜整。`1440 > open_minute` 恆成立，所以它**永遠**是當日窗口，不會與跨夜規則衝突。這是把上界定成 1440 而不是 1439 的唯一理由

一開始就支援跨夜，是因為「營業到凌晨 1 點」對咖啡廳不算罕見，而事後要加跨夜就得改判定演算法與所有既有資料的語意 —— 那會是一個不可分割的破壞性階段。現在支援它的成本只有下面這段演算法多兩個分支。

### 4.4 判定演算法（實作請照抄這段語意）

```
輸入：branchId、atEpochMs（毫秒，UTC）
1. ZonedDateTime z = Instant.ofEpochMilli(atEpochMs).atZone(ZoneId.of("Asia/Taipei"))
2. int today = z.getDayOfWeek().getValue()            // 1..7
   int prev  = today == 1 ? 7 : today - 1
   int m     = z.getHour() * 60 + z.getMinute()       // 0..1439，秒與毫秒一律無視
3. 取出該分店全部的 branch_hours 列
4. 沒有任何列 → return true（§4.2）
5. 只要存在任一列 r 滿足下列三者之一，就是營業中：
   (a) r.day == today && r.close >  r.open && r.open <= m && m < r.close
   (b) r.day == today && r.close <= r.open && m >= r.open          // 跨夜窗口的「當日段」
   (c) r.day == prev  && r.close <= r.open && m <  r.close         // 跨夜窗口的「隔日段」
6. 否則 return false
```

三個容易寫錯的點，每一個都要有測試（§10.1）：

- **(c) 的 `prev`**：星期一 01:00 要去看**星期日**的跨夜列。`today - 1` 在 `today == 1` 時會變成 0，必須繞回 7
- **右開區間**：`close_minute = 1260`（21:00）時，20:59 營業中、**21:00 已打烊**。這是刻意的，讓相鄰的兩段（…–14:00、14:00–…）不會重疊
- **秒與毫秒無視**：20:59:59.999 仍然營業中

### 4.5 為什麼存「台北當地分鐘數」而不是 epoch

`AGENTS.md`「時間」規定 DB 存 `BIGINT` epoch milliseconds。那條規範的對象是**事件發生的時間點**（`created_at`、`paid_at`、`received_at`），本表存的是**週期性的當地規則**，兩者不同類：

- 營業時間是「牆上時鐘的 09:00」，不是某一個絕對時刻。存 epoch 得挑一個基準週，語意會立刻歪掉
- 台灣目前沒有日光節約時間，但 1979 年以前有過。萬一恢復，存當地分鐘數的行為是正確的（照牆上時鐘開門），存 epoch 偏移的會錯一小時
- `day_of_week` 也是當地的星期，用 `Asia/Taipei` 換算，與 G13 的營業日、報表的日期歸屬同一套規則

**`branch_hours` 表不受「DB 存 epoch」那條規範拘束，這是刻意的例外，理由如上。** 稽核紀錄的 `created_at` 仍然是 epoch，不變。

---

## 5. API

### 5.1 `Branches` interface 的變更（`coffee-branches/api/Branches.java`）

**全部是加法。`requireOpen(String id)` 的簽章與行為一個字都不改**（理由見 §5.7）。

```java
public interface Branches {
  record Branch(String id, String name, String address, String phone,
                boolean active, int monthlyTarget) {}   // 不變

  /** 一週中的一段營業時間。dayOfWeek 1=星期一…7=星期日（ISO-8601）；
      分鐘數為台北當地時間距午夜的分鐘。closeMinute <= openMinute 表示跨夜。 */
  record Hours(int dayOfWeek, int openMinute, int closeMinute) {}

  List<Branch> list(Actor actor, boolean manage);        // 不變（回應的組裝見 5.4）
  Branch requireOpen(String id);                         // 不變
  Branch save(Actor actor, Branch branch);               // 不變

  /** 該分店的營業時段，依 dayOfWeek、openMinute 排序。公開資料。 */
  List<Hours> hours(String branchId);

  /** 指定時刻是否在營業時段內。沒有任何時段列時恆為 true。 */
  boolean openAt(String branchId, long atEpochMs);

  /** 分店存在、未停業、且在營業時段內；否則丟 Problem。 */
  Branch requireOrderable(String id, long atEpochMs);

  /** 整批取代該分店的一週時段。回傳寫入後的結果。 */
  List<Hours> saveHours(Actor actor, String branchId, List<Hours> hours);
}
```

`Branch` record **不加** `openNow` 欄位：它不是分店的屬性，是「查詢當下」的推導值，放進 record 會讓 `save()` 與 `requireOpen()` 的回傳值都得憑空生一個。`openNow` 只出現在 HTTP 回應（§5.4）。

### 5.2 HTTP 端點

| 方法 | 路徑 | 權限 | 說明 |
| --- | --- | --- | --- |
| `GET` | `/api/branches/{id}/hours` | 公開（與 `GET /api/branches` 同級） | 讀取該分店營業時段 |
| `PUT` | `/api/branches/{id}/hours` | `BRANCH_MANAGE` + 總部範圍 + CSRF | 整批取代 |

`GET /api/branches/{id}/hours` 回應：

```json
{
  "branchId": "b-001",
  "openNow": true,
  "hours": [
    { "dayOfWeek": 1, "openMinute": 540, "closeMinute": 840 },
    { "dayOfWeek": 1, "openMinute": 1020, "closeMinute": 1260 },
    { "dayOfWeek": 6, "openMinute": 600, "closeMinute": 120 }
  ]
}
```

（第三列是週六 10:00 開、週日 02:00 打烊的跨夜例子。）

`PUT /api/branches/{id}/hours` 請求 —— **帶的是該分店完整的一週**，不是差異：

```json
{ "hours": [ { "dayOfWeek": 1, "openMinute": 540, "closeMinute": 1260 } ] }
```

回應與 `GET` 相同格式。空陣列 `{"hours": []}` 是合法的，意思是「清空 → 回到 24 小時營業」（§4.2），**不是** 400。

### 5.3 錯誤碼

| 狀況 | 狀態碼 | 訊息（繁中，寫給終端使用者） |
| --- | --- | --- |
| `dayOfWeek` 不在 1–7 | 400 | `星期格式不正確` |
| `openMinute` 不在 0–1439 | 400 | `開始時間不正確` |
| `closeMinute` 不在 1–1440 | 400 | `結束時間不正確` |
| 同一天兩段重疊（§5.5） | 400 | `同一天的營業時段不能重疊` |
| 單日超過 4 段，或總列數超過 28 | 400 | `每天最多 4 個時段` |
| `hours` 為 `null` | 400 | `請提供營業時段` |
| 無 `BRANCH_MANAGE` | 403 | （沿用 `Actor.require` 既有訊息） |
| 有 `BRANCH_MANAGE` 但非總部 | 403 | `此功能限總部範圍` |
| 分店不存在 | 404 | `找不到分店` |
| **下單時非營業時間** | **400** | `分店目前未營業（今日營業時間 09:00–21:00）` |

下單被擋用 **400 而不是 409**，理由：`requireOpen()` 現行的「分店不存在或已暫停營業」就是 400，同一個呼叫點丟出兩種狀態碼會讓前端要處理兩條路徑，而這兩件事對使用者是同一件事（現在不能下單）。`AGENTS.md` 的 409 留給「與既有資料衝突」（重複開班、重複交班那類）。

**錯誤訊息要帶出今天的營業時間**，這是驗收條件不是建議 —— 「分店目前未營業」而不說幾點開，使用者只能猜。今天沒有任何時段時，訊息用 `分店今日未營業`。時間格式 `HH:mm`，多段用 `、` 連接，跨夜段標示到隔日：`22:00–隔日 02:00`。

### 5.4 權限與資料範圍

| 動作 | 權限 | 資料範圍 |
| --- | --- | --- |
| 讀取營業時間 | 無 | 公開。顧客要看得到才能決定要不要來 |
| 修改營業時間 | `BRANCH_MANAGE` | **`a.global()`，限總部** |

**不新增任何權限常數。** `Identity.PERMISSIONS` 不動，角色權限不變，因此**不需要為權限寫任何 migration**（對照 G13 的 §4.3、G11 的 V5 都得補角色授權 —— 本規格刻意避開）。

`GET /api/branches` 的每一筆增加 `openNow`（boolean）。**實作提醒：不要對每家分店各查一次 `branch_hours`。** 一次 `select * from branch_hours`（或 `where branch_id in (…)`）撈回全部，在記憶體裡分組判定。分店數量是個位數，但這是 G10 正在修的同一類 N+1，不要再製造新的。

`GET /api/branches`（`manage=false`）維持只回 `active=true` 的分店 —— **打烊的分店仍然會出現在清單裡，只是 `openNow=false`**，見 §11.5。

### 5.5 重疊必須擋下來

同一個 `dayOfWeek` 的兩段窗口不得重疊。判定時把每段展開成台北當地的分鐘區間再兩兩比對，**跨夜段要一併算進隔天**：

- 週一 `(540, 840)` 與週一 `(800, 1260)` → 重疊，400
- 週一 `(540, 840)` 與週一 `(840, 1260)` → **不重疊**（右開區間），放行
- 週一 `(1320, 120)`（跨到週二 02:00）與週二 `(60, 600)`（01:00 開） → **重疊**，400
- 週日 `(1320, 120)`（跨到週一 02:00）與週一 `(60, 600)` → **重疊**，400（週日 = 7，隔天要繞回 1）

理由：重疊會讓「現在營業中嗎」有兩個來源，UI 也無法決定要顯示哪一段的打烊時間；而且它幾乎必然是使用者輸入錯誤，不是需求。擋在寫入端，讀取端就永遠不必處理這個情況。

**這段的週日→週一繞接很容易漏，要有專門的測試（§10.4）。**

### 5.6 寫入是整批取代，且要鎖 `branches` 該列

`saveHours()` 在**同一個 `@Transactional` 內**：

1. `select id from branches where id=? for update` —— 取行鎖（與 G13 `fromUnlisted`、G15 開班／交班的鎖慣例一致，一律鎖 `branches` 那一列）
2. 分店不存在 → 404
3. 驗證全部輸入（範圍、重疊、段數上限），**任何一項失敗就整批拒絕**，不做部分寫入
4. `delete from branch_hours where branch_id=?`
5. 逐列 `insert`
6. `audit.record(a, "BRANCH_HOURS_SAVE", branchId, branchId, "更新營業時間（N 段）")`

用 PUT 整批取代而不是逐列 PATCH：逐列要對外暴露列 id、要處理「改到一半另一個人也在改」的合併規則，而營業時間的實際編輯行為就是「打開一週的表、改完按儲存」。整批取代讓併發退化成「後者覆蓋前者」，而行鎖保證兩次覆蓋不會交錯。

### 5.7 為什麼**不**把時段檢查加進 `requireOpen()`

**這是本規格最容易寫錯的一條，也是最貴的一條。**

`requireOpen()` 目前有兩個呼叫端，語意完全不同：

| 呼叫點 | 它其實在問什麼 | 與「現在幾點」有關嗎 |
| --- | --- | --- |
| `OrderService.java:53`（`create()`） | 這家店現在能不能接單 | **有關** |
| `IdentityService.java:93`（`saveAccount()`） | `branchId` 是不是一家有效的分店 | **無關** |

把時段檢查塞進 `requireOpen()`，`saveAccount()` 會跟著被擋：**總部晚上 10 點不能新增門市員工帳號**，而且錯誤訊息會是「分店已打烊」——一個與帳號管理毫無關係的理由。這種缺陷不會被既有測試抓到（現有測試不會在非營業時間跑，因為現在根本沒有營業時間），只會在某天晚上有人建帳號時爆出來。

所以：**新增 `requireOrderable(id, atEpochMs)`，`requireOpen()` 一個字都不改。** `saveAccount()` 那一行也不動。

同樣的理由，日後如果又有人要在「分店有效性」上加條件，先問一次這個檢查屬於哪一種語意。

---

## 6. 下單時的查核（`coffee-orders`）

### 6.1 改動位置

`OrderService.create()`，目前是：

```java
branches.requireOpen(q.branchId());
if (!a.customer()) {
  a.require("POS_ORDER");
  a.branch(q.branchId());
}
```

S3 改為：

```java
if (a.customer()) {
  branches.requireOrderable(q.branchId(), now);   // 顧客：含時段
} else {
  branches.requireOpen(q.branchId());             // 員工：只檢查分店有效（行為不變）
  a.require("POS_ORDER");
  a.branch(q.branchId());
}
```

`now` 取**同一次呼叫裡已經取得的時間基準**（建單寫入 `created_at` 用的那一個），不要在方法裡呼叫兩次 `System.currentTimeMillis()` —— 兩次取值跨過整分鐘時，會出現「檢查通過但 `created_at` 落在打烊後」的紀錄。

### 6.2 三個必須維持原狀的地方（都要有驗收條件）

1. **冪等短路要在時段檢查之前。** 現行 `create()` 先比對 `(account_id, idempotency_key)` 並在命中時直接 `return get(...)`（`OrderService.java:44-52`），那一段在 `requireOpen()` 之前。**順序不能動**：顧客在 20:59 送出、網路重試在 21:00 才到達，重試必須回傳同一筆訂單，不能因為打烊而變成 400
2. **既有訂單的後續操作不受時段限制。** `transition()`、`cash()`、綠界回呼、對帳**一律不加**時段檢查。20:50 的訂單要能在 21:10 收款結帳
3. **員工 POS 下單不受時段限制**，見 §11.1

---

## 7. 稽核

沿用 `coffee-audit` 的公開 API（`BranchService` 已注入 `Audit`）：

| action | target_id | summary |
| --- | --- | --- |
| `BRANCH_HOURS_SAVE` | `branchId` | `更新營業時間（N 段）` |

- 寫在業務交易**提交之後**（`AuditService.record()` 既有的行為，直接呼叫即可，不要自己包 `REQUIRES_NEW`）
- 不新增 `AUDIT_VIEW` 之外的任何權限；稽核查詢頁不需要改
- `action` 字串進 `audit_log.action`（`VARCHAR(40)`），`BRANCH_HOURS_SAVE` 長度 18，安全

---

## 8. 施工階段

三個階段。**S1 與 S2 合併進主線後，使用者完全感覺不到任何變化**；唯一的行為變更在 S3，而 S3 很小。

### 施工進度（G14）

Codex 請把這張表複製到 PR 描述並逐階段更新：

```markdown
## 施工進度（G14）
- [ ] S1 資料層與判定 —— 未開始
- [ ] S2 維護 API 與總部 UI —— 未開始
- [ ] S3 下單強制與顧客端顯示 —— 未開始
```

### S1 — 資料層與判定（純加法，無行為變化）

| 動到 | 內容 |
| --- | --- |
| `V8__branch_business_hours.sql`（版號以目錄為準） | `branch_hours` 表與索引，**不寫入任何資料** |
| `coffee-branches/api/Branches.java` | 加 `Hours` record、`hours()`、`openAt()`、`requireOrderable()` 三個方法宣告 |
| `coffee-branches/internal/BranchService.java` | 三個方法的實作 + §4.4 的判定 |
| 測試 | 判定演算法單元測試（不需要 Spring）、migration 測試 |

**驗收子集：** migration 在空 DB 與既有 DB 都能跑；`openAt()` 對沒有時段列的分店恆為 `true`；§4.4 三個分支與 §10.1 的邊界表全部通過；`requireOpen()` 的既有測試一個都沒改。**此階段沒有任何呼叫端**，單獨合併零風險。

### S2 — 維護 API 與總部 UI（純加法）

| 動到 | 內容 |
| --- | --- |
| `coffee-branches/api/Branches.java` | 加 `saveHours()` |
| `coffee-branches/internal/BranchService.java` | `saveHours()` 實作（行鎖、驗證、整批取代、稽核） |
| `coffee-branches/internal/BranchController.java` | `GET` / `PUT /api/branches/{id}/hours` |
| `frontend/src/modules/branches/BranchesView.vue` | 一週時段編輯；未設定時顯示「視為 24 小時營業」提示（§4.2） |
| `frontend/src/shared/types.ts` | `Hours` 型別 |
| `docs/API.md` | 兩個端點 |
| 測試 | 驗證、重疊、上限、越權、CSRF |

**驗收子集：** §9 的第 6–14 項。此時**還沒有人依這份資料做任何判斷**，所以就算店長設錯也不影響下單 —— 單獨合併風險仍然接近零。

### S3 — 下單強制與顧客端顯示（唯一的行為變更）

| 動到 | 內容 |
| --- | --- |
| `coffee-orders/internal/OrderService.java` | §6.1 的分支 |
| `coffee-branches/internal/BranchController.java` | `GET /api/branches` 回應加 `openNow`（一次撈取，§5.4） |
| `frontend/src/modules/ordering/*` | 「營業中／已打烊」標示；打烊時停用下單並顯示營業時間 |
| `frontend/src/shared/types.ts` | `Branch` 加 `openNow` |
| 測試 | 顧客打烊下單被擋、員工不受限、冪等重放不受影響、既有訂單後續操作不受影響 |

**驗收子集：** §9 的第 15–22 項。

**這三段切得開的原因就是 §4.2**（沒有列 = 24 小時營業）。如果預設是「不營業」，S1 的 migration 一跑完就會全店停擺，三個階段會被迫黏成一個。

---

## 9. 驗收條件

逐條可勾選。括號內是所屬階段。

**資料層（S1）**

1. [ ] `branch_hours` 表建立，`day_of_week` / `open_minute` / `close_minute` 三個 CHECK 都存在且會擋下越界值（S1）
2. [ ] migration 在全新 DB 與既有 DB（已有 V1–V7 資料）都能跑完，且**不新增任何資料列**（S1）
3. [ ] 升級後既有分店的 `openAt()` 一律為 `true`，既有下單行為一個字都沒變（S1）
4. [ ] `requireOpen()` 的簽章、SQL、錯誤訊息與既有測試完全未動（S1）
5. [ ] `openAt()` 通過 §10.1 的全部邊界案例，包含週日→週一的跨夜繞接（S1）

**維護 API（S2）**

6. [ ] `PUT /api/branches/{id}/hours` 以總部 `BRANCH_MANAGE` 帳號可寫入，`GET` 讀回來的內容與寫入一致且依 `dayOfWeek, openMinute` 排序（S2）
7. [ ] 未登入可 `GET` 營業時間（公開）（S2）
8. [ ] 無 `BRANCH_MANAGE` → 403；有 `BRANCH_MANAGE` 但為分店範圍帳號 → 403 且訊息為「此功能限總部範圍」（S2）
9. [ ] 缺 CSRF token 的 `PUT` → 403，帶 token 的相同請求 → 成功（兩次請求只差 token）（S2）
10. [ ] 重疊時段被擋（含跨夜與隔天重疊、週日→週一繞接）→ 400（S2）
11. [ ] `dayOfWeek=0`、`dayOfWeek=8`、`openMinute=-1`、`openMinute=1440`、`closeMinute=0`、`closeMinute=1441` 全部 400（S2）
12. [ ] 單日 5 段 → 400；`{"hours": []}` → 200 且清空，該分店回到 24 小時營業（S2）
13. [ ] 驗證失敗時**一列都沒寫進去**（先送一批合法的、再送一批含錯誤的，讀回來仍是第一批）（S2）
14. [ ] 每次成功寫入產生一筆 `BRANCH_HOURS_SAVE` 稽核，`target_id` 為 `branchId`；寫入失敗時不產生稽核（S2）
15. [ ] 總部 UI 在該分店沒有任何時段時顯示「視為 24 小時營業」提示（S2）

**下單強制（S3）**

16. [ ] 顧客帳號在非營業時間建單 → 400，訊息含當日營業時間（`HH:mm–HH:mm`）（S3）
17. [ ] 今日完全沒有時段的顧客建單 → 400，訊息為「分店今日未營業」（S3）
18. [ ] 顧客帳號在營業時間內建單 → 成功（S3）
19. [ ] **員工（`POS_ORDER`）在非營業時間建單 → 成功**（§11.1）（S3）
20. [ ] 打烊後用**同一組 `Idempotency-Key`** 重放一筆營業時間內建立的訂單 → 回傳原訂單，不是 400（§6.2 第 1 點）（S3）
21. [ ] 營業時間內建立的訂單，在打烊後仍可 `transition()`、`cash()`、收到綠界回呼（§6.2 第 2 點）（S3）
22. [ ] `GET /api/branches` 每筆帶 `openNow`；打烊的分店**仍出現在清單中**（`active=true` 時），只是 `openNow=false`（§11.5）（S3）
23. [ ] `GET /api/branches` 對 N 家分店只查一次 `branch_hours`（§5.4）（S3）
24. [ ] `active=false` 的分店不受本規格影響：仍然不出現在顧客清單、仍然不能下單（S3）

---

## 10. 測試要求

放在 `backend/coffee-app/src/test/java/com/coffee/app/`，新增 `BranchHoursTest`；migration 測試沿用既有命名 `BranchBusinessHoursMigrationTest`。

### 10.1 判定演算法（純單元測試，不需要 Spring context）

`AGENTS.md`「測試」要求業務規則優先寫不需要 Spring 的單元測試。§4.4 的判定是純函式，**必須**這樣寫。至少涵蓋：

| # | 情境 | 期望 |
| --- | --- | --- |
| 1 | 無任何時段列 | 恆 `true` |
| 2 | 週一 09:00–21:00，查週一 08:59 | `false` |
| 3 | 同上，查週一 09:00 | `true`（左閉） |
| 4 | 同上，查週一 20:59:59.999 | `true`（秒與毫秒無視） |
| 5 | 同上，查週一 21:00 | `false`（右開） |
| 6 | 同上，查週二 12:00 | `false`（只設了週一） |
| 7 | 分段 11:00–14:00 + 17:00–21:00，查 15:00 | `false` |
| 8 | 同上，查 13:59 / 17:00 | `true` / `true` |
| 9 | 相鄰段 09:00–14:00 + 14:00–21:00，查 14:00 | `true`（由第二段涵蓋） |
| 10 | 跨夜：週六 22:00–週日 02:00，查週六 23:00 | `true`（分支 b） |
| 11 | 同上，查週日 01:00 | `true`（分支 c） |
| 12 | 同上，查週日 02:00 | `false` |
| 13 | 同上，查週日 12:00 | `false` |
| 14 | **跨夜繞接：週日 22:00–週一 02:00，查週一 01:00** | `true`（`prev` 必須由 1 繞回 7） |
| 15 | `close_minute = 1440`，查 23:59 | `true` |
| 16 | 同上，查隔日 00:00 | `false`（1440 是當日窗口，不跨夜） |
| 17 | UTC 邊界：台北週一 07:00（= UTC 週日 23:00），週一有時段 | `true`（換算用 `Asia/Taipei`，不是系統預設時區） |

第 17 項要用固定的 epoch 常數，不要用 `System.currentTimeMillis()`。**測試不得依賴執行機器的預設時區**（CI 跑在 `ubuntu-latest`，時區是 UTC）。

### 10.2 越權測試（`AGENTS.md` 授權章節強制）

1. 無 `BRANCH_MANAGE` 的帳號 `PUT` → 403
2. 有 `BRANCH_MANAGE` 但資料範圍為 `BRANCH` 的帳號 `PUT`（含改自己那家店）→ 403，訊息「此功能限總部範圍」
3. 顧客帳號 `PUT` → 403
4. 未登入 `PUT` → 401；未登入 `GET` → 200（公開）

### 10.3 CSRF（`HttpWorkflowTest`，真實 HTTP + Cookie）

`PUT /api/branches/{id}/hours` 無 token → 403，有 token → 成功。**兩次請求除了 token 以外必須完全相同** —— G01a §3 修掉的正是「兩次都打跨店訂單，關掉 CSRF 也照樣通過」那種無效測試，不要重蹈。

### 10.4 邊界與併發

1. 重疊偵測的四種情形（§5.5 的四個例子）各一個案例，**含週日→週一繞接**
2. 驗證失敗不部分寫入（驗收 13）
3. 兩個交易同時 `saveHours()` 同一分店 → 因行鎖而序列化，最終結果是其中一批的完整內容，**不會兩批交錯**
4. 冪等重放跨越打烊時間（驗收 20）—— 這一項用時間可控的方式測（注入固定 `now`，不要 `Thread.sleep`）

---

## 11. 設計決策

每一項都附理由與推翻它的代價，供下一輪推翻。

### 11.1 時段只對「顧客自助下單」強制，員工 POS 不擋 —— **不擋**

**決定：** `a.customer()` 為真時套用時段檢查；持有 `POS_ORDER` 的員工不受限。

**理由：**

1. 缺口盤點記載的問題是「凌晨三點照樣能下單」，那是**顧客自助**的路徑。員工要下單，人得站在店裡，物理在場本身就是授權
2. 21:00 打烊，21:02 還在結帳最後一位客人的那杯拿鐵 —— 擋下來等於逼店員改用系統外的方式收錢，帳就對不起來（這正是 G15 現金日結要避免的事）
3. 替代方案是加一個 `ORDER_OFFHOURS` 權限，但那要動 `Identity.PERMISSIONS`、角色 seed 與 migration，成本遠高於它擋下的風險

**代價：** 員工可以在任何時間建單，系統不會攔。若日後要稽核「非營業時間的員工建單」，`orders.created_at` 與 `branch_hours` 已經足夠**事後**查出來，不需要改資料結構。

**推翻它的代價：** 要改成擋員工，得同時決定「怎麼放行合法的收尾訂單」（寬限期？覆寫權限？），那是一個新的設計，不是把 `if` 拿掉。

### 11.2 沒有 `branch_hours` 列 = 24 小時營業 —— **是**

理由、代價與推翻代價見 §4.2（那一節是資料模型的一部分，不重複）。

### 11.3 不做例外日／公休日 —— **排除，另立 G19**

**決定：** 本規格只做每週固定時段。國定假日、臨時公休、提早打烊、颱風天全部不做。

**理由：**

1. 例外日需要自己的資料表、自己的 UI（日曆）、自己的規則（提前幾天設、過期後要不要自動清掉、與固定時段誰優先），規模與本規格相當，塞進來會把 G14 從三個小階段撐成一個大階段
2. 現階段有一個夠用的替代方案：`active` 切掉一天。它不精緻，但臨時公休本來就是低頻事件
3. 例外日的資料形狀（一段時間 + 一個日期）與 `branch_hours` 同構，**日後加一張 `branch_hours_overrides` 是純加法**，不必改本規格的任何東西

**登記為 G19 — 分店例外營業日。** `G17` 已由 G06 第 13.6 節的「常用組合快捷」占用，`G18` 已由 G13 第 11.3 節的「區域定價」占用，**G19 是下一個未使用的編號**。

**推翻它的代價：** 若現在就要做，本規格的 S1 要多一張表、判定要多一層優先序（例外日覆蓋固定時段），S2 的 UI 要從「一週七列」變成日曆 —— 三個階段會變成五個，且 S1 不再是那個「合併後零影響」的小階段。

### 11.4 修改權限沿用 `BRANCH_MANAGE` + 總部範圍 —— **沿用，不新增權限**

**決定：** 與 `BranchService.save()` 完全相同的授權條件（`a.require("BRANCH_MANAGE")` + `a.global()`）。分店店長**不能**改自己店的營業時間。

**理由：**

1. 營業時間是對外承諾，與 `monthly_target`、地址、電話同一類「分店基本設定」，而那些目前全部限總部
2. 不新增權限常數 → `Identity.PERMISSIONS` 不動 → **不需要角色 migration**，S1 的 migration 可以只有一張表。G11 與 G13 都得處理「既有資料庫的既有角色要補授權」（G13 §4.3、G11 的 V5），那段是 migration 出錯率最高的地方，本規格直接避開
3. 授權條件與既有方法完全一致，店長不會遇到「能改地址卻不能改時間」這種不一致

**代價：** 店長要調整時段得找總部。以每週固定時段的變更頻率（一年數次）來說可以接受；臨時性的調整走 `active`（§11.3 第 2 點）。

**推翻它的代價：** 要放給店長，得新增 `BRANCH_HOURS` 權限常數、補角色 seed、寫既有資料庫的授權升級 migration，並把 `a.global()` 換成 `a.branch(branchId)` —— 三處改動，但都是加法，日後要做不困難。

### 11.5 打烊的分店仍然出現在顧客的分店清單 —— **仍然出現**

**決定：** `GET /api/branches` 不因打烊而過濾掉分店，只標 `openNow=false`。過濾仍然只看 `active`。

**理由：**

1. 「這家店晚上 8 點打烊」和「這家店不存在」對顧客是兩件完全不同的事。把打烊的店藏起來，顧客會以為分店收了
2. 顧客需要看得到營業時間才能決定明天幾點來 —— 藏起來就看不到了
3. `active` 與 `openNow` 語意分離，正是本規格要把 `active` 從「每日開關」解放出來的目的（§1 第二層）

**推翻它的代價：** 要過濾的話是一行，但顧客端要另外提供一個「查看所有分店營業時間」的入口，否則資訊就消失了。

---

## 12. 給 Codex 的施工提醒

八條，前三條是踩到就會出事的。

1. **不要改 `requireOpen()`。** 見 §5.7。改了會讓總部在非營業時間無法管理該分店的帳號（`IdentityService.java:93`），而既有測試抓不到
2. **`day_of_week` 用 ISO-8601（1=星期一，7=星期日），對應 `java.time.DayOfWeek.getValue()`。** 不要用 `java.util.Calendar`（1=星期日）或 0-based。混用會讓整個判定錯開一天，而且週一到週五的測試可能還是會過
3. **冪等短路必須留在時段檢查之前**（§6.2 第 1 點）。順序動了，跨越打烊時間的重試會變成 400，顧客會看到「已扣款但訂單不存在」
4. **時間換算一律 `ZoneId.of("Asia/Taipei")`**，不要用 `ZoneId.systemDefault()`。CI 跑在 UTC，用系統預設時區的程式在本機可能過、在 CI 會錯八小時
5. **`now` 在 `create()` 裡只取一次**（§6.1），與寫入 `created_at` 的是同一個值
6. **`GET /api/branches` 不要 N+1**（§5.4）。一次撈全部 `branch_hours` 再在記憶體分組
7. **跨夜判定的 `prev` 要從 1 繞回 7**（§4.4）。這是最常漏的一條，§10.1 第 14 項專門釘它
8. **`saveHours()` 的驗證要全部做完才寫**（驗收 13）。先 `delete` 再逐列 `insert` 並在中途才發現錯誤，靠交易回滾雖然也對，但錯誤訊息會變成只報最後一列 —— 先驗完整批，錯誤訊息才講得清楚是哪一天哪一段

另外兩點不影響正確性但會影響審查：

- `Hours` record 放在 `Branches` interface 內部（`AGENTS.md` 程式碼風格：DTO 一律 `record`，定義在 `api` package 的 interface 內）
- 前端的時段顯示用既有的格式工具，不要在元件裡自己拼 `HH:mm`；HTTP 一律走 `shared/api.ts`
