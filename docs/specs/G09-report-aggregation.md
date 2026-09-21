# G09 — 報表彙整下推 SQL 與 `coffee-reporting` 的 `api` package

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G09 |
| 優先順序 | P2 → **升為 P1**（理由見 §1.3） |
| 版本 | v1.0（2026-09-21） |
| 規格作者 | Claude（PM / SA） |
| 實作 | Codex（PG / SD） |
| 前置相依 | **G07（訂單折扣）必須已合併**，理由見 §2.3 |
| Flyway 版號 | **V10**（V9 由 G07 占用，見 §4.1） |
| 施工階段 | S1 / S2 / S3，三階段。S1／S2 是純內部重寫（對外 JSON 一個位元都不變），S3 是型別化 |

---

## 1. 背景與目標

### 1.1 問題

`ReportService.report()`（`backend/coffee-reporting/src/main/java/com/coffee/reporting/internal/ReportService.java:27-170`）把**整個月的已付款訂單全部載入記憶體**，然後對同一份 list 做十幾次全掃描：

```java
var sales = db.query("select o.id,o.branch_id,b.name,o.total,o.paid_at,... from orders o ...");
// ↑ 一列一張訂單，整月全部進記憶體

for (int day = 1; day <= m.lengthOfMonth(); day++) {          // 31 次
  var ds = sales.stream().filter(s -> ...getDayOfMonth() == d).toList();   // 每次全掃
}
for (int h = 0; h < 24; h++) {                                 // 24 次
  sales.stream().filter(s -> ...getHour() == hour).count();    // 每次全掃
}
for (var b : branches) { sales.stream().filter(...).toList(); }  // 分店數 × 全掃
sales.stream().filter(s -> s.method().equals("CASH")).count();   // 再三次全掃
```

以 N = 當月已付款訂單數、B = 分店數：

| 區塊 | 掃描次數 | 複雜度 |
| --- | --- | --- |
| `daily`（`:66-80`） | 每月天數（28–31） | O(31 × N) |
| `hourly`（`:136-147`） | 24 | O(24 × N) |
| `branches` 績效（`:112-131`） | B | O(B × N) |
| `cashOrders` / `onlineOrders` / `takeawayOrders`（`:165-169`） | 3 | O(3 × N) |
| `revenue` / `count`（`:63-64`） | 1 | O(N) |

合計約 **O((59 + B) × N)** 次比較，外加 N 個 `Sale` 物件與 N 次 `Instant.ofEpochMilli(...).atZone(zone)` 的時區換算（`daily` 與 `hourly` 各做一遍，所以其實是 2N 次）。

而且 `Sale` 投影撈了兩個**從頭到尾沒有人讀**的欄位：`id`（`:19`）與 `branchName`（`:21`）。整月訂單的 id 與分店名稱被搬進記憶體，只為了被丟掉。

### 1.2 為什麼現在會痛、以前不會

單店、單月幾百筆時這是個不痛不癢的迴圈。但這套系統的既有設計已經把資料往「會痛」的方向推：

- **跨店月報是預設路徑。** 總部帳號（`a.global()`）不帶 `branchId` 時 `filter` 是空字串（`:45`），撈的是**全鏈**整月訂單。分店越多，N 越大而且 B 也越大 —— `O(B × N)` 那一項是兩邊同時長
- **G10 已經把訂單清單的同類問題修掉了。** 訂單列表當初是「一頁 201 次查詢」，G10 規格立下「一頁固定 3 次查詢」的性質並用測試釘住。報表是同一套資料上最後一個還在用「全載入 + 記憶體彙整」的讀路徑
- **報表沒有分頁、沒有上限。** 訂單清單至少有游標分頁擋著；`report()` 的 `sales` 沒有 `limit`，唯一的界線是「一個月」，而那個界線只會隨營業額變大

這不是「將來某天會爆」的假設性風險，是**資料累積的線性函數**，而且沒有任何上限機制。

### 1.3 為什麼從 P2 升上來

第二次盤點把 G09 放在 P2「規模與體驗」，理由是「單店資料量下沒問題」。這個判斷在當時是對的，現在有三件事改變了它：

1. **P1 清空了。** G06 / G10 / G11 / G13 / G14 / G15 全部合併，G07 在審查中。G09 之外的 P2 項目全都有「現在做一定是猜的」或「要等別的東西」的明確理由（見 §11.5），G09 沒有
2. **`coffee-reporting` 缺 `api` package 這項已登記的架構缺口，正好在同一個模組。** `GAP-ANALYSIS.md` 記著「違反 `AGENTS.md` 每個業務模組固定兩個 package，ArchUnit 因 `coffee-app` 是組裝層例外而放行，排進後續規格處理」。兩件事合成一份規格，動的是同一個 171 行的檔案，分開做等於把同一個檔案改兩次、審兩次
3. **這是唯一不需要新功能決策的一項。** 沒有新端點、沒有新權限、沒有 UI、對外 JSON 不變。規格風險低、驗收客觀（輸出相同 + 查詢次數固定）

**決策：G09 升為 P1，排在 G07 之後**。推翻的代價見 §11.1。

### 1.4 目標

- 把 `daily`、`hourly`、分店績效、付款方式與取餐方式的彙整**全部下推到 SQL 的 `GROUP BY`**，讓 `report()` 不再持有任何 O(N) 的集合
- **對外 JSON 的每一個 key、每一個值、每一種型別完全不變** —— 前端 `ReportsView.vue` 一個字都不用改
- 每次 `report()` 的 DB 查詢次數**固定**，與訂單筆數、月份天數、分店數都無關，並用測試釘住（比照 G10 的做法）
- 補上 `coffee-reporting` 的 `api` package，結清已登記的架構缺口

### 1.5 不是目標

| 被排除的項目 | 理由 |
| --- | --- |
| 改變任何報表數字的定義 | 見 §5.1，這是本規格的紅線 |
| 報表快取 / 物化檢視 / 預先彙總表 | §11.2 |
| 自訂區間（非整月）報表 | §11.3 |
| 報表匯出格式與前端變更 | 前端一個字不改，見 §6 |
| `products` / `topToday` 查詢的改寫 | 它們本來就是 `GROUP BY`，已經是對的形狀 |

---

## 2. 範圍

### 2.1 在範圍

1. `daily`（每日營收與訂單數）下推 SQL
2. `hourly`（每小時訂單數）下推 SQL
3. 月總計（`revenue`、`orders`、`discount`、`cashOrders`、`onlineOrders`、`takeawayOrders`）下推 SQL
4. 分店績效（`branches`）下推 SQL
5. 刪除 `Sale` record 與 `sales` 全載入
6. `V10__report_indexes.sql`：`orders(paid_at)` 相關索引
7. `coffee-reporting` 新增 `api/Reports.java`（interface + record），`ReportService implements Reports`，`ReportController` 改依賴介面

### 2.2 不在範圍

見 §1.5。另外**不動 `ReportController` 的路徑、權限與參數**（`:23` 行的那支 `GET /api/reports`），它現在是對的。

### 2.3 為什麼必須等 G07 合併

G07（PR #31）正在改**同一個方法**：`ReportService.java:18-25` 的 `Sale` record 加 `discountAmount` 欄位、`:48-62` 的 `select` 補 `o.discount_amount`、`:148-169` 的回傳 Map 加 `discount` key。

本規格要**刪掉**那個 `Sale` record 並改寫那支 `select`。兩件事同時進行必然衝突，而且衝突的形狀是「一邊加欄位、一邊刪掉整個 record」—— 不是解得乾淨的那種。

**G07 合併後，`discount` key 是本規格必須原樣保留的既有輸出**（§5.1 的清單已經含它）。若開工時 G07 尚未合併，**停下來等，不要繞過** —— 這是「一次一份」的直接應用。

---

## 3. 涉及模組與邊界

| 模組 | 變更 |
| --- | --- |
| `coffee-reporting` | **新增** `api/Reports.java`；改寫 `internal/ReportService.java`；`internal/ReportController.java` 改依賴 `Reports` |
| `coffee-app` | Flyway `V10__report_indexes.sql` |
| `frontend` | **零變更** |

### 3.1 唯讀投影例外維持不變

`AGENTS.md`「資料存取」寫明：

> 報表是明確的唯讀投影例外：可直接查 `orders` / `order_items` / `branches`，但沒有寫入權限。

本規格**完全在這個例外的範圍內** —— 只是把原本撈回來在 Java 裡算的東西改成在 SQL 裡算，查的還是同樣那三張表，一樣沒有任何寫入。`coffee-reporting` 的 pom 依賴（只有 `coffee-shared` + jdbc + validation + spring-web）**一個字都不會動**，不新增任何跨模組相依。

### 3.2 `api` package 的形狀

`AGENTS.md`「模組邊界」要求每個業務模組固定兩個 package：`api`（只放 interface 與 record）、`internal`（Controller 與 Service）。`coffee-reporting` 目前只有 `internal`。

```
com/coffee/reporting/api/Reports.java          ← 新增：interface Reports + 巢狀 record
com/coffee/reporting/internal/ReportService.java   ← implements Reports
com/coffee/reporting/internal/ReportController.java ← 建構子注入 Reports，不再注入 ReportService
```

**目前沒有任何其他模組呼叫報表**，所以這一步不改變任何跨模組的邊，純粹是把本模組改成符合規範的形狀。價值是：(1) 結清已登記的缺口；(2) 回傳型別從 `Map<String, Object>` 變成有型別的 record，日後誰要改報表欄位，編譯器會幫忙找出所有呼叫點。

> **注意：`ReportService` 目前是被 `coffee-app` 的測試直接注入的**（G07 的 `OrderDiscountTest` 就 `@Autowired ReportService reports`）。S3 改成 interface 之後那些測試要改成注入 `Reports`。這是編譯期可見的變更，改不完會 build fail，不會默默出錯 —— 所以放在最後一階段。

---

## 4. DB schema 與 migration

### 4.1 版號

**檔名 `V10__report_indexes.sql`。** V9 由 G07 規格指定並已由 PR #31 實際占用（該 PR 尚未合併）。

**即使開工時 V9 還沒進主線，也一樣用 V10**，理由與 G07 §4.0 相同：Flyway 預設不接受事後補插較小版號（out-of-order），占走 V9 會讓 G07 在既有資料庫上無號可用。空一個版號的成本是零。

### 4.2 索引

```sql
-- 月報的主要述詞：paid_at 區間 +（可選的）branch_id
CREATE INDEX idx_orders_paid_at ON orders(paid_at);
CREATE INDEX idx_orders_branch_paid_at ON orders(branch_id, paid_at);
```

**為什麼兩支都要：** 總部不指定分店時（跨店月報，`filter` 為空字串）述詞只有 `paid_at`，走 `idx_orders_paid_at`；分店帳號或總部指定分店時述詞是 `branch_id = ? and paid_at between ...`，走 `idx_orders_branch_paid_at`（前導欄位是等值述詞，範圍述詞在後，是正確的複合索引順序）。

**`orders.paid_at` 可為 NULL**（未付款訂單）。兩支索引都不加 `WHERE paid_at IS NOT NULL` 的部分索引 —— H2 不支援部分索引，而本專案測試跑 H2、生產跑 PostgreSQL，寫了會讓兩邊行為分岔。全索引的額外成本在這個資料量下可忽略。

**不對 `order_items` 加索引。** `products` / `topToday` 兩支查詢 join `order_items`，但它們的過濾條件在 `orders.paid_at` 上，join 走的是既有的 `order_items.order_id`（V1 建表時的外鍵）。要不要加要看真實執行計畫，現在加是猜的 —— 登記在 §11.4。

### 4.3 沒有 schema 變更

**本規格不新增、不修改任何資料表或欄位。** 只有兩支索引。既有資料零遷移，migration 可以在任何時間點對任何既有資料庫執行。

---

## 5. 彙整下推（本規格的核心）

### 5.1 紅線：對外 JSON 一個位元都不能變

`report()` 目前回傳的 `Map.ofEntries(...)`（`:148-169`）有 18 個 key（G07 合併後含 `discount`）。**每一個 key 的名稱、值的型別與數值的計算定義，全部維持原樣：**

| key | 型別 | 定義（不得更動） |
| --- | --- | --- |
| `month` | String | 請求參數原樣回傳 |
| `today` | String | `LocalDate.now(Asia/Taipei).toString()` |
| `revenue` | long | 當月已付款訂單的 `sum(orders.total)`（**折後實收**） |
| `discount` | long | 當月已付款訂單的 `sum(orders.discount_amount)`（G07 加入） |
| `orders` | long | 當月已付款訂單筆數 |
| `averageOrder` | long | `count == 0 ? 0 : Math.round((double) revenue / count)` |
| `quantity` | long | 由 `products` 加總（**不動**） |
| `grossProfit` | long | `revenue - cost`，`cost` 由 `products` 加總（**不動**） |
| `grossMargin` | double | `revenue == 0 ? 0 : Math.round((revenue - cost) * 1000.0 / revenue) / 10.0` |
| `daily` | List&lt;Map&gt; | 每個元素 `{day: "01".."31", revenue: long, orders: int}` |
| `products` | List&lt;Map&gt; | 既有 `GROUP BY` 查詢，**一個字不動** |
| `topToday` | List&lt;Map&gt; | 既有 `GROUP BY` 查詢，**一個字不動** |
| `branches` | List&lt;Map&gt; | 每個元素 `{id, name, revenue: long, orders: int, target: int, achievement: double}` |
| `categories` | Map&lt;String, Long&gt; | 由 `products` 在 Java 端 merge（**不動**） |
| `hourly` | List&lt;Map&gt; | 每個元素 `{hour: "00:00".."23:00", orders: long}` |
| `cashOrders` | long | `payment_method='CASH'` 的筆數 |
| `onlineOrders` | long | `payment_method='ECPAY'` 的筆數 |
| `takeawayOrders` | long | `fulfillment='TAKEAWAY'` 的筆數 |

**三個容易在改寫時漏掉的細節，每一個都有對應的驗收條件：**

1. **`daily` 一定有當月的每一天**，即使那天沒有任何訂單（`revenue: 0, orders: 0`）。`day` 是**零補位兩位數字串**（`String.format("%02d", day)`），不是數字
2. **`hourly` 一定有 24 筆**，`hour` 格式是 `"%02d:00"`（`"00:00"` 到 `"23:00"`），沒有訂單的小時 `orders: 0`
3. **`branches` 一定含所有在查詢範圍內的分店**，包含當月營收為 0 的分店（`revenue: 0, orders: 0, achievement: 0`）。目前是從 `branches` 表撈出來再比對 `sales` 得到的（`:106-131`），所以零營收分店會出現；改成 `GROUP BY` 之後**天真的寫法會讓它們消失**，前端的分店績效表就少了幾行

`GROUP BY` 天生只產出有資料的桶。**空桶必須在 Java 端補回來**，這是本規格最容易寫錯的地方。

### 5.2 台北時區的日／時分桶：用 epoch 毫秒算術，不要用資料庫的時區函式

`orders.paid_at` 是 `BIGINT` epoch millis（UTC）。`daily` 要按**台北日期**分桶、`hourly` 要按**台北小時**分桶。

**決定：在 SQL 裡用固定偏移量的整數算術。**

```sql
-- 台北 epoch 日（自 1970-01-01 起的第幾天）
(paid_at + 28800000) / 86400000

-- 台北小時（0-23）
((paid_at + 28800000) / 3600000) % 24
```

`28800000` = 8 小時的毫秒數。

**為什麼可以用固定偏移量：** Asia/Taipei 自 1979 年起就是固定的 UTC+8，沒有日光節約時間。而 `report()` 已經把月份限制在 2020–2100（`ReportService.java:36`），**這個區間內偏移量恆為 +8，沒有任何例外**。

**為什麼不用 `AT TIME ZONE` / `DATE_TRUNC` / `to_timestamp`：** 本專案的測試跑 H2（`MODE=PostgreSQL`）、生產跑 PostgreSQL。H2 的相容模式**不保證**這些時區與日期函式的行為與 PostgreSQL 一致 —— 一旦分岔，症狀會是「測試全綠但生產的報表日期差一天」，而那是最難發現的一類缺陷（數字看起來都很合理）。整數加法、除法與取模在兩邊定義完全相同，沒有相容性面積。

**兩個必須注意的算術細節：**

1. **整數除法。** 兩個運算元都必須是整數型別。`paid_at` 是 `BIGINT`、字面量寫成不帶小數點的整數，結果就是整數除法（向零捨去）。**不要寫成 `86400000.0`**，那會變成浮點除法然後得到錯的桶
2. **不會有負數。** `paid_at` 是現代 epoch 毫秒（≥ 2020 年），加上正偏移仍然遠大於 0，所以不必處理「負數的整數除法在不同資料庫捨入方向不同」這個經典陷阱。**但也因此，不要把這段算術複製到任何可能出現負 epoch 的地方**

**從台北 epoch 日換回「當月第幾天」在 Java 端做：**

```java
LocalDate.ofEpochDay(epochDay).getDayOfMonth()
```

不要在 SQL 裡算 day-of-month —— 那需要日期函式，正是上面要避開的東西。

**查詢範圍本身已經是台北邊界。** `start` / `end`（`:43-44`）是用 `m.atDay(1).atStartOfDay(zone)` 算的，所以撈回來的每一列必然落在當月的台北日期內，`epochDay` 換算出來的 day-of-month 必然在 1..lengthOfMonth 之間。不需要額外的防禦判斷。

**推翻的代價**：若台灣重新實施日光節約時間，或系統要支援多時區分店，這段算術就不成立，必須改用真正的時區函式，而且要先解決 H2 / PostgreSQL 的行為一致性問題（可能要改成在 Java 端分桶，但那就回到本規格要消滅的 O(N)）。**在那之前，固定偏移是又快又精確又可攜的做法。**

### 5.3 改寫後的查詢清單

**查詢 1 —— `daily`**

```sql
select (o.paid_at + 28800000) / 86400000 as taipei_day,
       sum(o.total) as revenue,
       count(*) as orders
  from orders o
 where o.paid_at >= ? and o.paid_at < ? [and o.branch_id = ?]
 group by (o.paid_at + 28800000) / 86400000
```

回傳列數 ≤ 當月天數。Java 端先建好 1..lengthOfMonth 的完整骨架（`revenue: 0L, orders: 0`），再用查詢結果覆寫對應的天。

**查詢 2 —— `hourly`**

```sql
select ((o.paid_at + 28800000) / 3600000) % 24 as taipei_hour,
       count(*) as orders
  from orders o
 where o.paid_at >= ? and o.paid_at < ? [and o.branch_id = ?]
 group by ((o.paid_at + 28800000) / 3600000) % 24
```

回傳列數 ≤ 24。Java 端同樣先建 0..23 的骨架再覆寫。

**查詢 3 —— 月總計（一列）**

```sql
select coalesce(sum(o.total), 0) as revenue,
       coalesce(sum(o.discount_amount), 0) as discount,
       count(*) as orders,
       coalesce(sum(case when o.payment_method = 'CASH' then 1 else 0 end), 0) as cash_orders,
       coalesce(sum(case when o.payment_method = 'ECPAY' then 1 else 0 end), 0) as online_orders,
       coalesce(sum(case when o.fulfillment = 'TAKEAWAY' then 1 else 0 end), 0) as takeaway_orders
  from orders o
 where o.paid_at >= ? and o.paid_at < ? [and o.branch_id = ?]
```

**`coalesce` 是必要的**：沒有任何訂單時 `sum()` 回傳 NULL（`count(*)` 才是 0）。目前的 Java 版本在空月份回傳的是 0，漏掉 `coalesce` 會讓空月份的報表變成 `null` 或 NPE。

**查詢 4 —— 分店績效**

```sql
select o.branch_id,
       coalesce(sum(o.total), 0) as revenue,
       count(*) as orders
  from orders o
 where o.paid_at >= ? and o.paid_at < ? [and o.branch_id = ?]
 group by o.branch_id
```

回傳列數 ≤ 分店數。與查詢 5 的分店清單**左合併**（分店清單為主，沒有對應列的分店填 0）。

**查詢 5 —— 分店清單（既有，`:106-111`，不動）**

```sql
select id, name, monthly_target from branches [where id = ?] order by name
```

**這支是分店績效的骨架來源，不能省。** 它決定了輸出的順序（`order by name`）與零營收分店的存在。

**查詢 6 —— `products`（既有，`:81-89`，一個字不動）**
**查詢 7 —— `topToday`（既有，`:98-105`，一個字不動）**

### 5.4 查詢次數與每支查詢的列數上限

| # | 查詢 | 回傳列數上限 |
| --- | --- | --- |
| 1 | `daily` | 31 |
| 2 | `hourly` | 24 |
| 3 | 月總計 | 1 |
| 4 | 分店績效 | 分店數 |
| 5 | 分店清單 | 分店數 |
| 6 | `products` | 商品數 |
| 7 | `topToday` | 5 |

**固定 7 次查詢，與訂單筆數完全無關**，而且**沒有任何一支查詢會回傳 O(訂單數) 的列**。這兩件事一起才是本規格的目標 —— 只把查詢次數壓住但仍然撈回整月訂單，等於沒改。

改寫前是 4 次查詢，其中一支回傳 O(N) 列。**次數從 4 變 7 是刻意的取捨**：多 3 次 round-trip（每次幾毫秒）換掉 N 個物件的傳輸、配置與 (59 + B) 次全掃描。在 N 稍大的時候這筆帳沒有懸念；在 N 很小的時候兩者都是毫秒等級，看不出差別。

> **設計決策：不為了把查詢次數壓回 4 次而合併查詢。** `daily`、`hourly`、月總計三支可以硬塞進一支 `GROUP BY GROUPING SETS` 或 `UNION ALL`，但 H2 對 `GROUPING SETS` 的支援與 PostgreSQL 不一致（又回到 §5.2 那個問題），而且合併後的結果集要在 Java 端拆回三種形狀，可讀性大幅下降。**三支獨立、各自一眼看懂的查詢，勝過一支聰明的。**

### 5.5 `Sale` record 與 `sales` 全載入必須刪掉

S2 結束時，`ReportService` 裡**不能再有任何一個 O(訂單數) 的集合**。`Sale` record（`:18-25`）與 `var sales = db.query(...)`（`:48-62`）整段刪除。

> 這條是驗收 8 的來源。留著一個「暫時還沒用到」的 `sales` 就等於這份規格白做了 —— 記憶體占用與查詢傳輸量完全沒有改善，只是把 CPU 上的迴圈搬走。

順帶結清 §1.1 提到的死欄位：`Sale.id` 與 `Sale.branchName` 隨 record 一起消失，不需要另外處理。

---

## 6. 前端

**零變更。** 這是本規格的驗收條件之一（驗收 1）。

`frontend/src/modules/reporting/ReportsView.vue` 與 `frontend/src/shared/types.ts` 的 `Report` interface 都不動 —— 如果需要動，就代表 JSON 變了，就代表違反了 §5.1 的紅線。

**實作端自我檢查的方法：** 改完之後 `git diff --stat` 裡**不應該出現任何 `frontend/` 的檔案**。出現了就是做錯了，回頭看 §5.1。

---

## 7. 稽核

**不新增任何稽核紀錄。** 報表是唯讀查詢，`AGENTS.md` 要求進稽核軌跡的是金額相關的**寫入**動作。既有行為也沒有記錄報表查詢，本規格不改變這一點。

---

## 8. 權限與資料範圍

**完全不變**，原樣保留 `ReportService.java:28-42` 的邏輯：

```java
if (a.global()) a.require("REPORT_ALL");
else a.require("REPORT_STORE");
...
String branch = a.global()
    ? ((requestedBranch == null || requestedBranch.isBlank()) ? null : requestedBranch)
    : a.branchId();            // ← 分店帳號一律被強制成自己的分店
if (!a.global() && requestedBranch != null && !requestedBranch.isBlank())
  a.branch(requestedBranch);   // ← 分店帳號指定別家分店時擋下來
```

**七支查詢每一支都要套用同一個 `branch` 過濾條件。** 這是本規格唯一的越權風險：改寫時漏掉任何一支的 `and branch_id = ?`，分店帳號就會在那個區塊看到全鏈資料。

**特別注意查詢 4（分店績效）與查詢 5（分店清單）：** 分店帳號查自己的報表時，這兩支都必須只回傳自己那一家。目前的 `:106-111` 已經有 `branch == null ? "" : " where id=?"`，改寫時不要弄丟。

驗收 9、10 就是在驗這件事，而且**必須包含「分店帳號拿到的 `branches` 陣列只有一個元素」**這個斷言 —— 光驗 `revenue` 對不對是不夠的，越權洩漏最可能發生在清單型的欄位上。

---

## 9. 施工階段

> 規則見 `AGENTS.md`「施工階段與中斷續作」。每階段獨立 CI 綠、獨立可合併、有自己的驗收子集。**做完一個階段就 push**，不要整份做完才推。

三個階段的切法是「先立測試網、再改、最後型別化」：

### S1 — 輸出等價測試 + 索引 + `daily` / `hourly` 下推

| 項目 | 內容 |
| --- | --- |
| 檔案 | `V10__report_indexes.sql`、`internal/ReportService.java`（只改 `daily` / `hourly` 兩段） |
| 測試 | **新增 `ReportAggregationTest`**（輸出等價 + 查詢次數 + 空桶） |
| 驗收子集 | 驗收 1–5、11 |

**先寫測試再改程式。** `ReportAggregationTest` 要先建立一份**跨日、跨小時、跨分店、含零營收分店、含空白日與空白小時**的種子資料，斷言 `report()` 的完整輸出。這份斷言在 S1 開始時是對著**舊實作**寫的 —— 它就是 S2、S3 的安全網。

種子資料至少要涵蓋：

- 台北時間 00:30 與 23:30 的訂單（**抓時區偏移寫反的錯**：若偏移方向弄錯，這兩筆會掉到前一天／後一天）
- 當月第 1 天與最後一天的訂單（**抓邊界**）
- 至少一天完全沒有訂單（**抓空桶**）
- 至少一家分店當月零營收（**抓分店空桶**）
- `CASH` 與 `ECPAY`、`DINE_IN` 與 `TAKEAWAY` 都有（**抓 case when**）
- 至少一張帶折扣的已付款訂單（**抓 `discount`**）

改完 `daily` / `hourly` 之後，**同一份斷言必須原封不動地通過**。

**查詢次數用 `OrderPaginationTest` 已經在用的計數 DataSource proxy**（`backend/coffee-app/src/test/java/com/coffee/orders/internal/OrderPaginationTest.java:1-35` 的 `counting(source, statements)`）—— 那個形狀已經驗證過，照抄，不要自己發明一套。S1 結束時查詢次數是 **6**（原本 4 支，`sales` 還在，加 `daily`、`hourly` 兩支）。

S1 結束時 `sales` 仍然存在（月總計、分店績效、付款方式還在用它），**這是安全的中間狀態**：輸出不變，最重的兩個迴圈（31 × N 與 24 × N）已經消失。

### S2 — 月總計與分店績效下推，刪除 `sales`

| 項目 | 內容 |
| --- | --- |
| 檔案 | `internal/ReportService.java` |
| 測試 | `ReportAggregationTest` 補查詢次數與「無 O(N) 集合」的斷言 |
| 驗收子集 | 驗收 6–10 |

把查詢 3、4 加進去，刪掉 `Sale` record 與 `sales` 全載入。S2 結束時查詢次數是 **7**，且 `ReportService` 裡沒有任何 O(訂單數) 的集合。

**S1 那份輸出等價斷言仍然必須原封不動地通過。** 它從頭到尾沒有改過 —— 這正是它的價值。

### S3 — `coffee-reporting` 的 `api` package

| 項目 | 內容 |
| --- | --- |
| 檔案 | **新增** `api/Reports.java`；`internal/ReportService.java` 加 `implements Reports`；`internal/ReportController.java` 改注入 `Reports`；既有測試改注入 `Reports` |
| 測試 | `ModuleBoundariesTest` 既有規則自動涵蓋；`ReportAggregationTest` 不變 |
| 驗收子集 | 驗收 12–14 |

```java
package com.coffee.reporting.api;

import com.coffee.shared.Actor;
import java.util.List;
import java.util.Map;

public interface Reports {
  record Daily(String day, long revenue, int orders) {}

  record BranchPerformance(
      String id, String name, long revenue, int orders, int target, double achievement) {}

  record Hourly(String hour, long orders) {}

  record MonthlyReport(
      String month,
      String today,
      long revenue,
      long discount,
      long orders,
      long averageOrder,
      long quantity,
      long grossProfit,
      double grossMargin,
      List<Daily> daily,
      List<Map<String, Object>> products,
      List<Map<String, Object>> topToday,
      List<BranchPerformance> branches,
      Map<String, Long> categories,
      List<Hourly> hourly,
      long cashOrders,
      long onlineOrders,
      long takeawayOrders) {}

  MonthlyReport report(Actor a, String month, String branchId);
}
```

**`products` 與 `topToday` 刻意維持 `List<Map<String, Object>>`。** 它們是 `db.queryForList()` 的直接輸出，欄位由 SQL 的 `as` 別名決定。要型別化就得同時改那兩支查詢的讀取方式，而那兩支查詢本規格**一個字都不動**（§5.1）。把它們留成 Map 是為了讓 S3 只做「包一層介面」這一件事 —— 登記在 §11.6。

**S3 的風險在於 Jackson 序列化出來的 JSON 必須與 `Map.ofEntries` 完全一致。** record 的元件名稱就是 JSON 的 key，所以上面每一個元件名都**必須**與 §5.1 的表格逐字相同。`ReportAggregationTest` 是欄位層的斷言，抓不到序列化層的差異 —— **S3 必須另外補一個經過真實 MockMvc 的 JSON 斷言**（驗收 13）。

### 9.1 為什麼切得開

- **S1 與 S2 都不改變對外輸出**，各自合併進主線的行為變化是零。S1 單獨合併的狀態是「兩個最重的迴圈沒了，其餘照舊」，完全自洽
- **S3 是純型別化**，不碰任何一支 SQL 與任何一個數字
- **輸出等價測試在 S1 一次寫好，之後兩階段都不改它** —— 每一階段都是對著同一份不動的斷言驗證，這比每階段各自修改測試可靠得多

### 9.2 PR 描述請維護這張表

```markdown
## 施工進度（G09）
- [ ] S1 輸出等價測試 + 索引 + daily/hourly 下推 —— 未開始
- [ ] S2 月總計與分店績效下推、刪除 sales —— 未開始
- [ ] S3 coffee-reporting 的 api package —— 未開始
```

---

## 10. 驗收條件

逐條可勾選。每一條都要有對應的測試。

**S1**

1. [ ] `git diff --stat` **不含任何 `frontend/` 的檔案**；`ReportsView.vue` 與 `types.ts` 零變更
2. [ ] 對一份跨日／跨小時／跨分店的種子資料，`report()` 的完整輸出與改寫前**逐欄相同**（含 `daily`、`hourly`、`branches`、`categories` 的內容與**順序**）
3. [ ] `daily` 恰好有當月天數筆，`day` 是零補位兩位字串；**沒有訂單的那一天存在且 `revenue: 0, orders: 0`**
4. [ ] `hourly` 恰好 24 筆，`hour` 格式 `"HH:00"`；**沒有訂單的小時存在且 `orders: 0`**
5. [ ] 台北時間 00:30 與 23:30 的訂單分別落在**正確的台北日期與小時**（偏移方向與整數除法都正確）
11. [ ] 全新資料庫跑完 Flyway 後有 `idx_orders_paid_at` 與 `idx_orders_branch_paid_at`；既有資料庫升級後訂單列數不變

**S2**

6. [ ] `ReportService` 原始碼中**不再有 `Sale` record，也沒有任何撈回整月訂單的查詢**
7. [ ] `report()` 的 DB 查詢次數為 **7**，且在 30 筆訂單與 300 筆訂單兩種種子資料下**完全相同**（計數 proxy 比照 `OrderPaginationTest`）
8. [ ] 任一支查詢回傳的列數都不隨訂單數成長（`daily` ≤ 31、`hourly` ≤ 24、月總計 = 1、分店績效 ≤ 分店數）
9. [ ] 當月完全沒有已付款訂單時，`revenue` / `discount` / `orders` / `cashOrders` / `onlineOrders` / `takeawayOrders` 全部是 `0`（**不是 `null`**），`averageOrder` 與 `grossMargin` 是 `0`，`daily` 仍有當月天數筆、`hourly` 仍有 24 筆
10. [ ] **越權**：分店帳號（`REPORT_STORE`、`BRANCH` 範圍）查詢時，`revenue` 只含自己分店，且 **`branches` 陣列只有自己那一家**；指定別家分店的 `branchId` 回 403；總部帳號不指定分店時 `branches` 含所有分店（**含當月零營收的分店**）

**S3**

12. [ ] `com.coffee.reporting.api.Reports` 存在且只含 interface 與 record；`ReportService implements Reports`；`ReportController` 建構子注入的是 `Reports`，不是 `ReportService`
13. [ ] 經過真實 HTTP（MockMvc）取得的 `GET /api/reports` 回應 JSON，**key 集合與改寫前完全相同**（18 個 key，逐一比對名稱）
14. [ ] `ModuleBoundariesTest` 通過；`coffee-reporting` 的 pom 依賴沒有新增任何項目

---

## 11. 設計決策

每一項都是 Claude 定案。附理由與推翻它的代價。

### 11.1 G09 升為 P1，排在 G07 之後 —— **升**

**理由**：見 §1.3。三句話版本：P1 已經清空、這是唯一不需要新功能決策的一項、它順便結清一個已登記的架構缺口而且在同一個檔案裡。

**推翻的代價**：幾乎沒有。G09 不阻擋任何其他項目，也沒有任何項目阻擋它（除了 G07 的檔案衝突）。若 PO 或下一輪判斷別的 P2 項目更急，把 G09 降回 P2、讓別的項目插隊，**唯一的成本是報表繼續按 O((59 + B) × N) 跑** —— 那不會壞掉，只會慢，而且慢得很線性、很可預測。**這是本批次裡最可以安全延後的一份規格**，寫出來是因為它同時也是最便宜、風險最低的一份。

### 11.2 不做快取、不做預先彙總表 —— **只改查詢**

**理由**：快取要處理失效（新訂單、改狀態、退款都會讓當月報表過期），預先彙總表要處理回填與一致性（彙總表與明細不一致時信哪一份？）。兩者都是**新的正確性問題**，換到的是「已經夠快之後再快一點」。下推 `GROUP BY` 沒有任何新的正確性面積 —— 同一份資料、同一個定義，只是換個地方算。

**先把 O(N) 變成 O(分桶數)，再看還痛不痛。** 絕大多數情況到這裡就結束了。

**推翻的代價**：若下推之後仍然太慢（那代表 `orders` 本身已經大到連 `GROUP BY` 掃描都吃不消），正確的下一步是**物化的日彙總表 + 以 `paid_at` 為界的增量回填**，而不是應用層快取。那是一份獨立規格，規模與本規格相當，**而且必須先有真實的慢查詢證據才值得寫**。

### 11.3 不支援自訂區間，維持「整月」 —— **維持**

**理由**：`report()` 現在的形狀是「一個月」，`month` 參數、`YearMonth.parse`、`daily` 的 1..lengthOfMonth 骨架全部依賴這個假設。改成自訂區間要重新定義 `daily` 的骨架（可能上千天）、`hourly` 的語意（跨多天的「每小時」是加總還是平均？）與 `today` 的意義。**那是新功能，不是效能改寫**，混進來會讓本規格的「輸出完全不變」這條紅線失效，連帶讓驗收 2 無法成立。

**推翻的代價**：新增一支 `GET /api/reports/range`，共用本規格改好的 `GROUP BY` 查詢（它們本來就只是 `paid_at` 區間查詢，不在乎那個區間是不是一個月）。**本規格的改寫正好讓這件事變便宜** —— 這是把它排除的附帶好處，不是損失。

### 11.4 只加 `orders` 的兩支索引，不碰 `order_items` —— **不碰**

**理由**：`products` / `topToday` 兩支查詢本規格一個字不動，它們的 join 走既有的 `order_items.order_id`。要不要加索引取決於真實資料分布與執行計畫，現在加是猜的，而每一支索引都是寫入時的固定成本（下單是本系統最頻繁的寫入）。

**推翻的代價**：加一支 `V11` 的索引 migration，純加法，任何時候都能做。**做之前請先有 `EXPLAIN` 的輸出**，不要因為「看起來應該要有索引」就加。

### 11.5 為什麼是 G09，不是其他 P2 項目

逐項理由，留給下一輪判斷要不要推翻這個順序：

| 項目 | 為什麼不是現在 |
| --- | --- |
| G16 顧客自助註冊 | 涉及濫用防護、驗證信、個資保存期限 —— 那是**產品與法遵範圍的決策**，不是設計取捨，不在 PO 授權給 Claude 的範圍內。要做得先問 PO 要不要開放對外註冊 |
| G05 Session 集中化 | 要引入 Spring Session + Redis，違反 `AGENTS.md`「不得引入新框架或新依賴」的預設。單店單機營運下沒有實際損害，等到真的要多實例部署時再連同部署架構一起決定 |
| G08 庫存扣減 | G13 已經把「今天這項賣完」這個每天會用到的部分做掉了。完整庫存要進貨、耗用、盤點、成本結轉，規模遠大於本批次任何一份 |
| G17 常用組合 / G20 品項層折扣 / G21 會員價 | 三項都明文登記「要先有真實資料才知道該做什麼」。G20／G21 還要等 `order_discounts` 累積實際促銷資料 |
| G19 分店例外營業日 | G14 §11.3 登記，替代方案（切 `active` 一天）可用，臨時公休是低頻事件 |
| G12 外送、硬體印單 | 依賴外部服務與硬體選型，技術選型未定前寫規格意義不大 |

### 11.6 `products` / `topToday` 維持 `List<Map<String, Object>>` —— **維持**

**理由**：見 §9 S3 的說明。型別化它們要動到那兩支查詢的讀取方式，而本規格承諾那兩支查詢一個字不動。**在同一份規格裡既承諾不動又動它，是自相矛盾的。**

**推翻的代價**：把兩支 `queryForList` 改成 `query` + RowMapper + record，加兩個 record 到 `Reports`。是純加法、約半小時，**任何時候都能做** —— 所以現在不做。登記在這裡，下一份動到 `ReportService` 的規格可以順手帶走。

### 11.7 `api` package 補在 S3 而不是 S1 —— **最後做**

**理由**：S3 會改動 `coffee-app` 裡所有 `@Autowired ReportService` 的測試（G07 的 `OrderDiscountTest` 就是一個）。把它放在最前面，等於在還沒有輸出等價測試網的時候就去動一堆測試檔 —— 那是最容易把「改壞了」藏進「測試本來就要改」裡面的順序。

放在最後，`ReportAggregationTest` 已經釘住了輸出，S3 的任何閃失都會被它抓到。

**推翻的代價**：無。順序是純粹的風險管理，不影響最終形狀。

---

## 12. 給 Codex 的施工提醒

1. **先寫 S1 的輸出等價測試，對著舊實作跑綠，再開始改。** 那份斷言從 S1 到 S3 完全不修改 —— 它是這份規格唯一的安全網。改到後來發現要修測試才能過，**幾乎一定是實作改錯了**，先回頭看 §5.1
2. **空桶必須補回來**（§5.1 的三個細節）。`GROUP BY` 只產出有資料的桶，天真的改寫會讓沒有訂單的日期、小時與零營收分店從輸出裡消失，而前端的圖表會默默少幾根柱子
3. **時區用 epoch 毫秒算術，不要用資料庫的日期／時區函式**（§5.2）。寫錯的症狀是「測試全綠、生產差一天」
4. **整數除法。** 字面量寫 `86400000` 不要寫 `86400000.0`
5. **七支查詢每一支都要套用 `branch` 過濾**（§8）。漏掉任何一支 = 越權洩漏，驗收 10 會驗，而且要驗到 `branches` 陣列的長度
6. **`sum()` 要包 `coalesce`**（§5.3 查詢 3）。空月份的 `sum()` 是 NULL 不是 0
7. **S2 結束時 `Sale` record 與 `sales` 必須不存在**。留著等於這份規格白做（驗收 6）
8. **`Reports` record 的每一個元件名必須與 §5.1 的表格逐字相同**，那就是 JSON 的 key。S3 要另外補一個真實 HTTP 的 JSON key 斷言（驗收 13）
9. **前端零變更**（§6）。`git diff --stat` 出現 `frontend/` 就是做錯了
10. **Flyway 用 V10**，即使開工時 V9 還沒進主線（§4.1）
11. **開工前確認 G07 已經合併進主線**（§2.3）。沒合併就等，不要繞過 —— 兩份規格改同一個方法，而且一邊加欄位一邊刪 record，衝突解不乾淨
