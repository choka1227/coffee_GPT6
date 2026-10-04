# G20c — 報表的淨營收歸屬與折抵口徑一致性

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G20c |
| 版本 | v1.1（2026-10-04） |
| 登記來源 | `docs/specs/G20-item-level-promotions.md` §14「G20c 報表的淨營收歸屬（含 G07 訂單層折扣的分攤規則）」、§13.11「報表不改，品項營收維持定價毛額」 |
| Flyway 版號 | **無 migration**（主線目前最高 V14，由 G20 占用；**V15 仍然空著，留給下一份需要 schema 的規格**，見 §4） |
| 涉及後端模組 | `coffee-reporting`（主）、`coffee-orders`（S1 的一行投影修正） |
| 新增資料表 | 無 |
| 涉及前端模組 | `modules/reporting`、`shared/types.ts`、`shared/testing/fixtures.ts` |
| 施工階段 | 三階段 S1–S3，見 §9 |
| 預計 PR 數 | 1（分支 `codex/g20c-report-net-revenue`，逐階段推進、逐階段可合併） |

---

## 1. 背景與目標

### 1.1 現況

G20（PR #65，2026-10-04）把品項層促銷做完了，`order_items.discount_amount` 現在逐列記著「這一列被折了多少」。同時 §5.5 刻意把 `orders.discount_amount` 的語意改成**總折抵**（品項層 + 訂單層優惠碼）。

G20 §13.11 當時決定「報表不改」，理由是「品項營收歸屬要另立缺口討論」。那個決定本身是對的（不要在實作促銷的同一份規格裡順手改報表），但它留下的狀態是**報表內部的金額口徑互相矛盾**：

| 報表欄位 | 目前的算法 | 口徑 |
| --- | --- | --- |
| `revenue`（營業額） | `sum(o.total)` | **淨額**（已扣全部折抵） |
| `discount`（折扣） | `sum(o.discount_amount)` | 總折抵（G20 後自動含品項折抵） |
| `grossProfit`（商品毛利） | `revenue − cost` | **淨額** − 成本 |
| `products[].revenue`（單品營收） | `sum((unit_price+options_price)*quantity)` | **毛額**（完全不看折抵） |
| `categories`（分類營收） | 由 `products[].revenue` 累加 | **毛額** |

### 1.2 要擋的缺陷（具體的，都看得到）

| # | 缺陷 | 現在的行為 | 位置 |
| --- | --- | --- | --- |
| 1 | 單品毛利高估 | 商品排行表的「商品毛利」印 `p.revenue - p.cost`，用的是毛額營收減成本。只要那個品項被促銷折過，這個數字就比真實毛利高 | `ReportsView.vue:481-482` |
| 2 | 單品營收加總對不上營業額 | `Σ products[].revenue` 是毛額、`revenue` 是淨額，兩者固定差一個折抵金額。同一頁上下兩個數字互相矛盾，而且頁面沒有任何地方解釋這個差額 | `ReportService.java`（products 查詢）vs 同檔 totals 查詢 |
| 3 | 分類營收佔比被扭曲 | 分類圓餅圖用毛額累加。「蛋糕類全品項九折」這種促銷會讓蛋糕類的佔比被系統性高估 | `ReportsView.vue:118` |
| 4 | CSV 匯出把矛盾帶出系統 | 匯出的「營業額」是淨額、商品明細是毛額，拿去對帳的人無從得知 | `ReportsView.vue:158-177` |
| 5 | 對帳投影回報錯誤的優惠碼折抵 | `reconciliationCandidates` 的 select 清單沒有選 `d.discount_amount`，所以巢狀 `OrderDiscount.discountAmount` 取到的是 `o.discount_amount`（G20 之後＝總折抵），不是優惠碼自己的折抵 | `OrderService.java:586-610` |

缺陷 1–4 是 G20 §13.11 預告過的；**缺陷 5 是 Claude 在審查 PR #65（2026-10-04）時發現的新缺陷**，由本規格一併修掉（理由見 §13.1）。

### 1.3 目標

1. 報表同時提供**毛額**與**淨額**兩套品項營收，兩者都標明口徑，不再有「同一頁兩個相互矛盾的數字」。
2. 淨營收與營業額之間有一條**可用測試釘住的恆等式**。
3. 前端報表頁與 CSV 以淨額為主、毛額為輔。
4. 修掉缺陷 5。
5. **不動 `OrderService.create` 的計價路徑、不新增 migration。** 本規格是純讀取層的修補，見 §13.2。

### 1.4 為什麼現在排這一項

- G20 剛把品項折抵寫進 `order_items`，**資料已經在表上**，淨額是一個 `sum()` 就拿得到的東西。閘門已經自己解除了。
- 缺陷 1 是**財務數字錯誤**，不是顯示瑕疵。店主會拿「商品毛利」那一欄決定要不要繼續做這個促銷 —— 那一欄現在系統性地偏高，剛好會讓促銷看起來比實際賺錢。G20 上線後這個偏差從「只有用優惠碼的訂單才有」變成「每一張命中促銷的訂單都有」。
- `docs/GAP-ANALYSIS.md` 的「排定的工作順序」在 G20 之後只剩下 PO 或真實資料擋著的項目（G02–G04 金流延後、G05／G12／G16／G21 卡 PO、G17／G28 要真實資料）。本項是目前唯一**完全沒有外部阻擋**且修的是金額正確性的缺口。

---

## 2. 範圍

### 2.1 在範圍內

1. `OrderService.reconciliationCandidates` 的投影修正（缺陷 5）。
2. `Reports.MonthlyReport` 加法擴充：品項層淨營收、淨毛利、折抵拆解、分類淨額。
3. `ReportService` 的 SQL 調整（**不新增往返次數**，見 §11.1）。
4. 前端報表頁、CSV 匯出、`shared/types.ts`、報表 DOM 測試。

### 2.2 不在範圍內（逐項有理由）

| 項目 | 為什麼不做 |
| --- | --- |
| **把訂單層優惠碼折抵分攤到品項** | 這是 G20 §14 登記 G20c 時寫的「含 G07 訂單層折扣的分攤規則」那一半。經評估後**本規格明確不做**，理由與完整的分攤演算法見 §13.2 與附錄 A。缺陷 1–4 不需要它就能修掉 |
| 任何 migration | 淨額可由既有欄位算出。不碰 schema 就不會占用 V15、不會與下一份規格撞號 |
| 改 `orders.discount_amount` 的語意 | G20 §5.5 剛定案，推翻它要動 `snapshot()` 的 `subtotal` 回推與前端三行金額。本規格依賴那個語意，不改它 |
| 改 `OrderService.create` | 本規格是讀取層修補。為了報表的呈現去動金額寫入路徑，風險與收益完全不成比例 |
| 退單／退款的營收沖銷 | G03 延後（PO） |
| 人事、租金、稅費、金流手續費 | 報表的「商品毛利」本來就只扣商品成本，頁面已明寫「未扣營運費用」。本規格維持 |
| 淨營收的歷史回填 | 不需要 —— 淨額是查詢時算的，不是存起來的。這正是 §13.2 選讀取層方案的附帶好處 |

---

## 3. 涉及模組與邊界

```
coffee-orders    S1：OrderService.reconciliationCandidates 的 select 清單（模組內部，無邊界變化）
coffee-reporting S2：ReportService + Reports.api 的 record 擴充
frontend         S3：modules/reporting、shared/types.ts、shared/testing/fixtures.ts
```

- **沒有任何新的跨模組依賴。** `coffee-reporting` 依賴 `shared`，本規格不增加。
- `coffee-reporting` 直接查 `orders` / `order_items` / `branches` 是 `AGENTS.md`「資料存取」明文承認的**唯讀投影例外**，本規格沿用，不新增寫入權限。
- `ModuleBoundariesTest`（ArchUnit）不需要調整。

---

## 4. DB schema 與 migration

**本規格零 migration。**

淨額全部由 G20 已經建好的欄位算出：

```
order_items.discount_amount   （G20 V14 新增，逐列品項折抵）
orders.discount_amount        （總折抵）
orders.item_discount_amount   （G20 V14 新增，品項折抵合計）
orders.total                  （淨額）
```

**`V15` 仍然空著。** 下一份需要 schema 的規格請占用 V15，不要因為本規格編號在 G20 之後就以為 V15 被用掉了。

**要注意的測試 DDL：** `ReportAggregationTest` 自己用 `db.execute("create table ...")` 建一份精簡 schema，**兩張表都缺本規格要用的欄位，S2 要各補一個**：

| 手寫 DDL | 位置 | 缺的欄位 | 誰要用它 |
| --- | --- | --- | --- |
| `order_items` | `ReportAggregationTest.java:55-59` | `discount_amount integer not null` | §5.3(a) products 查詢、§5.3(b) topToday 查詢 |
| `orders` | `ReportAggregationTest.java:51-54` | `item_discount_amount integer not null` | §5.3(d) totals 查詢 |

**漏掉 `orders` 那一個最容易發生**，因為它不在品項那張表上，而缺口是 §5.3(d) 的 totals 查詢而不是商品查詢。兩個都補才會綠。欄位型別跟著 V14 的 `NOT NULL DEFAULT 0` 走；H2 的手寫 DDL 沒有 default，所以 `seed(...)` 的 insert 也要一起帶值（既有案例一律帶 0，驗收 10 才會原封不動通過）。

這與 PR #65 在 `OrderPaginationTest` 踩到的是同一個坑 —— 本 repo 有多處手寫測試 DDL，改 SQL 時要一起掃。

---

## 5. 技術設計

### 5.1 S1：`reconciliationCandidates` 的投影修正

現況（`OrderService.java:586-610`）：

```java
"select o.*,b.name branch_name,d.code discount_code,d.name discount_name,"
    + "d.kind discount_kind,d.percent discount_percent,d.amount discount_rule_amount"
    + " from orders o join branches b on b.id=o.branch_id"
    + " left join order_discounts d on d.order_id=o.id"
...
    r.getString("discount_code") == null ? null : new OrderDiscount(
        r.getString("discount_code"), r.getString("discount_name"),
        r.getString("discount_kind"), r.getInt("discount_percent"),
        r.getInt("discount_rule_amount"), r.getInt("discount_amount")),
```

`d.discount_amount` 沒有被選進來，所以最後那個 `r.getInt("discount_amount")` 取到的是 `o.*` 帶進來的 `orders.discount_amount`。G20 §5.5 之後那是總折抵。

**修法：** select 清單補一個別名，讀它：

```java
"select o.*,b.name branch_name,d.code discount_code,d.name discount_name,"
    + "d.kind discount_kind,d.percent discount_percent,d.amount discount_rule_amount,"
    + "d.discount_amount discount_code_amount"
...
        r.getInt("discount_rule_amount"), r.getInt("discount_code_amount")),
```

**別名為什麼不能省：** `o.*` 已經帶進一個叫 `discount_amount` 的欄位，再選一個同名的 `d.discount_amount` 會讓 `r.getInt("discount_amount")` 的行為取決於驅動程式解析重名欄位的順序 —— H2 與 PostgreSQL 不保證一致。**一定要給別名。**

同一支方法的 `Order.discountAmount`（第 603 行的 `r.getInt("discount_amount")`）**維持不動** —— 那一欄的語意就是總折抵，現在是對的。

`snapshot()`（走 `discountSnapshot()`）與 `page()`（走批次查 `order_discounts`）都是從 `order_discounts` 直接讀的，**沒有這個問題，不要跟著改**。

### 5.2 S2：淨營收的定義

**品項層淨營收**（本規格的核心定義）：

```
netRevenue(品項) = Σ (unit_price + options_price) * quantity − Σ discount_amount
```

也就是「定價毛額 − 落在這一列的品項促銷折抵」。**不含訂單層優惠碼折抵**（§13.2）。

**恆等式**（要有測試釘住，這是本規格最重要的驗收項）：

```
Σ products[].grossRevenue  − Σ products[].itemDiscount = Σ products[].netRevenue
Σ products[].netRevenue    − codeDiscount              = revenue        （= sum(o.total)）
itemDiscount + codeDiscount                            = discount       （= sum(o.discount_amount)）
```

第二條是整份規格的重點：它讓「單品淨營收加總」與「營業額」中間**只差一個有名字、看得見的數字**（`codeDiscount`），而不是像現在差一個無名的黑洞。

`codeDiscount` 不必另外查表，由既有欄位相減得到：

```sql
coalesce(sum(o.discount_amount - o.item_discount_amount),0) as code_discount
```

這條等式成立的依據是 G20 §8 第 5 點已經釘住的 `orders.discount_amount = orders.item_discount_amount + coalesce(order_discounts.discount_amount, 0)`。**不要再去 join `order_discounts`** —— 多一次 join 換不到任何東西，而且會動到 G09 的 SQL 往返次數上限。

### 5.3 S2：`ReportService` 的查詢調整

**(a) products 查詢**（`ReportService.java:62-72`）在既有的 `queryForList` 上加兩個彙總欄位，**不新增查詢**：

```sql
select i.product_id as id, i.name as name, i.category as category,
       sum(i.quantity) as quantity,
       sum((i.unit_price+i.options_price)*i.quantity) as revenue,
       sum(i.discount_amount) as item_discount,
       sum((i.unit_price+i.options_price)*i.quantity - i.discount_amount) as net_revenue,
       sum((i.unit_cost+i.options_cost)*i.quantity) as cost
  from order_items i join orders o on o.id=i.order_id
 where o.paid_at>=? and o.paid_at<? [and o.branch_id=?]
 group by i.product_id, i.name, i.category
 order by quantity desc, revenue desc
```

- `revenue` 這個 key 的**名稱與語意都不變**（維持毛額）。前端與 CSV 既有的引用因此不會壞 —— 這是加法原則。
- 排序鍵**維持 `quantity desc, revenue desc`**（毛額）。改排序鍵會讓既有的商品排行順序無聲地變動，那不是本規格要的，而且 `ReportAggregationTest` 有釘排序。
- `products` 是 `List<Map<String,Object>>`，新增的 key 會自動流到 API 與前端，**`Reports.java` 的 record 不用為它們改任何東西**。

**(b) topToday 查詢**（`ReportService.java:80-88`）**只加 `net_revenue` 一欄**，不加 `item_discount`。今日前五名在前端是「數量 + 營收」兩欄，營收改用淨額才與主表一致。

> **為什麼 topToday 不跟 products 一樣加兩欄**：topToday 的唯一消費端是 §5.5(c) 的今日前五名卡片，那張卡片沒有折抵欄也不會有（它只有兩欄的空間）。`topToday` 在 `shared/types.ts:221` 是自己的 inline 型別、不與 `products[]` 共用，所以兩者欄位不對稱**不會**造成型別重複宣告。多選一個沒有人讀的彙總欄位，只會讓「API 回傳的每個欄位都有消費端」這條界線鬆掉。要是哪天卡片真的要顯示折抵，那時再加一欄 `sum(i.discount_amount) as item_discount` 並補一條驗收 —— 它折在既有查詢裡，往返次數不變，是零風險的加法。

**(c) 分類淨額**：既有的 `categories` 由 `products` 累加（`ReportService.java:115-118`）。在它旁邊再累加一份：

```java
var categoriesNet = new LinkedHashMap<String, Long>();
for (var p : products)
  categoriesNet.merge(
      (String) p.get("category"), ((Number) p.get("net_revenue")).longValue(), Long::sum);
```

`categories`（毛額）**保留不刪**。理由見 §13.3。

**(d) totals 查詢**（`ReportService.java:131-146`）在既有的 `queryForObject` 上多選兩欄，**不新增查詢**：

```sql
coalesce(sum(o.item_discount_amount),0),
coalesce(sum(o.discount_amount - o.item_discount_amount),0)
```

**(e) 新的頂層欄位**由上面的結果組出：

```java
long itemDiscount = totals[6];
long codeDiscount = totals[7];
long netProductRevenue = products.stream()
    .mapToLong(p -> ((Number) p.get("net_revenue")).longValue()).sum();
long netProfit = netProductRevenue - cost;
```

### 5.4 S2：`Reports.MonthlyReport` 的擴充（全部是加法）

```java
record MonthlyReport(
    String month,
    String today,
    long revenue,              // 不變：sum(o.total)，淨額
    long discount,             // 不變：sum(o.discount_amount)，總折抵
    long itemDiscount,         // 新增：品項促銷折抵合計
    long codeDiscount,         // 新增：訂單層優惠碼折抵合計
    long orders,
    long averageOrder,
    long quantity,
    long grossProfit,          // 不變：revenue − cost
    double grossMargin,        // 不變
    long netProductRevenue,    // 新增：Σ products[].net_revenue
    long netProductProfit,     // 新增：netProductRevenue − cost
    double netProductMargin,   // 新增：netProductProfit / netProductRevenue，分母為 0 時回 0
    List<Daily> daily,
    List<Map<String, Object>> products,
    List<Map<String, Object>> topToday,
    List<BranchPerformance> branches,
    Map<String, Long> categories,     // 不變：毛額
    Map<String, Long> categoriesNet,  // 新增：淨額
    List<Hourly> hourly,
    long cashOrders,
    long onlineOrders,
    long takeawayOrders) {}
```

**新欄位全部插在語意相近的既有欄位後面**，而不是一律附加在尾端 —— 這個 record 是具名建構，欄位位置不影響任何呼叫端的正確性，可讀性優先。

`revenue` / `discount` / `grossProfit` / `grossMargin` / `categories` / `products[].revenue` 的語意**一律不變**。本階段對既有呼叫端是純加法，前端不改也不會壞（只是看不到新數字）—— 這是 S2 能獨立合併的依據。

**為什麼 `netProductProfit` 與 `grossProfit` 要並存：**
- `grossProfit = revenue − cost`：訂單層視角，扣掉了全部折抵（含優惠碼），是「這個月商品這條線實際賺多少」。
- `netProductProfit = netProductRevenue − cost`：品項層視角，只扣品項促銷折抵，可以與單品毛利逐列加總核對。
- 兩者固定差 `codeDiscount`。頁面要把這件事講出來（§5.5）。

### 5.5 S3：前端

**(a) 商品排行表**（`ReportsView.vue:460-485`）

| 欄位 | 現在 | 改成 |
| --- | --- | --- |
| 商品營收 | `p.revenue`（毛額） | `p.net_revenue`（淨額），欄名改「商品淨營收」 |
| 商品毛利 | `p.revenue - p.cost` | `p.net_revenue - p.cost` |
| — | — | **新增一欄**「促銷折抵」`p.item_discount`，僅在該列 > 0 時顯示數字，等於 0 顯示 `—` |

毛額不從頁面消失：在表格下方的說明文字補一句，寫明定價毛額 = 淨營收 + 促銷折抵。

**(b) 分類圓餅圖**（`ReportsView.vue:118`）改用 `categoriesNet`，圖標題或副標註明是淨額。

**(c) 今日前五名**改用 `net_revenue`。

**(d) 折抵的拆解**：既有的摘要區（`ReportsView.vue:246-290` 一帶）把單一的「折扣」改成兩行「品項促銷折抵」與「優惠碼折抵」，並保留總折抵。**版面以既有卡片樣式為準，不要為此重排整頁。**

**(e) CSV 匯出**（`ReportsView.vue:155-180`）：
- 摘要區塊加「品項促銷折抵」、「優惠碼折抵」、「商品淨營收」三列
- 商品明細的欄位改成「名稱 / 數量 / 淨營收 / 促銷折抵 / 毛額」
- **標頭列的文字要寫清楚口徑**，匯出的檔案會離開系統，拿到的人沒有頁面可以看

**(f) `shared/types.ts`** 的 `Report` 依 §5.4 同步加欄位；`products[]` 加 `net_revenue` 與 `item_discount`，`topToday[]` **只加 `net_revenue`**（理由見 §5.3(b)）。型別宣告要與 SQL 實際產生的欄位**逐一對齊** —— 宣告一個查詢不會回傳的必填欄位，等於在編譯期騙過型別檢查、在執行期拿到 `undefined`。

> **命名**：後端 `queryForList` 回的是 SQL 欄位名，所以前端拿到的 key 是 **snake_case**（`net_revenue`、`item_discount`），與既有的 `id` / `name` / `category` / `revenue` / `cost` 同一個來源。**不要**在後端為它們做 camelCase 轉換 —— 那會讓這張表的 key 一半 snake 一半 camel。型別定義照實寫 snake_case。

---

## 6. API

### 6.1 `GET /api/reports/{month}`（路徑與請求不變）

回應多出 §5.4 列的欄位，以及：

| 陣列 | 新增的 key |
| --- | --- |
| `products[]` | `net_revenue`、`item_discount` |
| `topToday[]` | `net_revenue`（**只有這一個**，理由見 §5.3(b)） |

**沒有新端點、沒有新的請求參數、沒有任何欄位被移除或改名。**

### 6.2 錯誤碼

與現況完全相同（`ReportService.report` 的驗證一字未改）：

| 狀態 | 情境 |
| --- | --- |
| 400 | 月份格式不是 `YYYY-MM`、年份超出 2020–2100 |
| 401 | 未登入 |
| 403 | 無 `REPORT_ALL`／`REPORT_STORE`，或店長指定他店 |

---

## 7. 權限與資料範圍

**完全沿用現況，不新增權限常數、不改資料範圍。**

```java
if (a.global()) a.require("REPORT_ALL");
else a.require("REPORT_STORE");
```

- GLOBAL：可看全鏈，可用 `branchId` 指定單店
- BRANCH：強制 `branch = a.branchId()`；若帶了他店的 `branchId`，`a.branch(requestedBranch)` 會擋下（`ReportService.java:30-31`）

**新增的欄位沒有擴大任何人看得到的資料範圍** —— 它們是既有查詢的彙總值，筛選條件（`filter` / `params`）一字未改。這一點要有測試釘住（驗收 11）。

S1 的 `reconciliationCandidates` 權限路徑也不動。

---

## 8. 金額規則

1. 一律新台幣**整數元**。所有新增欄位都是 `long`／`sum()`，不出現小數。
2. **百分比（`netProductMargin`）維持現有算法**：`Math.round(x * 1000.0 / y) / 10.0`，分母為 0 時回 `0`。與 `grossMargin` 同形，不要換算法。
3. 報表是唯讀投影，**不重算也不寫入任何金額**。所有折抵都來自 `OrderService.create` 當時寫下的快照。
4. 本規格**不產生任何新的金額計算** —— 只有 `sum()` 與相減。相減的兩邊都來自同一筆 `orders` 列或同一個 `group by` 群組，所以不會有跨列對不上的問題。
5. **溢位**：`sum()` 在 Java 端一律收成 `long`（既有程式已經是 `long`）。`netProductProfit` 的相減用 `long` 直接算即可 —— 兩邊都是 `sum()` 的結果，不是逐筆累加，`Math.subtractExact` 在這裡沒有保護價值（但若實作端覺得加了更安心，加了不算錯）。
6. **負數防線**：`net_revenue` 理論上不可能為負（G20 §8 的證明保證每一列的 `discount_amount` 不超過該列毛額）。要有一條測試斷言 `products[].net_revenue >= 0`（驗收 7），把這個保證釘在報表這一端 —— 如果哪天 G20 的計價出了 bug，報表會是第一個看出來的地方。

---

## 9. 施工階段

三個階段，每一階段獨立 CI 綠、獨立可合併、有自己的驗收子集。

### S1 — 對帳投影的折抵別名修正（規模：小）

- `OrderService.reconciliationCandidates` 依 §5.1 補別名並改讀
- 測試：`ReconciliationTest`（`com.coffee.app`）補一條 —— 建立一張「同時命中品項促銷與優惠碼」的 ECPAY 待付訂單，斷言 `reconciliationCandidates` 回的 `discount().discountAmount()` 等於**優惠碼自己的折抵**，而 `discountAmount()`（訂單層）等於總折抵

**本階段只改一個 select 清單與一個讀取位置，零 schema、零行為擴張。**
**驗收子集：** 1、2。

### S2 — 報表的淨營收欄位（規模：中）

- `Reports.MonthlyReport` 依 §5.4 擴充
- `ReportService` 依 §5.3 調整四處查詢（**不新增往返**）
- `ReportAggregationTest` 的手寫 DDL 補兩個欄位：`order_items.discount_amount` 與 `orders.item_discount_amount`（§4）
- 測試：§11.1 的 (a)–(f)

**本階段對既有呼叫端是純加法**，前端不改也不會壞（`npm run build` 不受影響，因為 `Report` 介面只是多了可選讀的欄位 —— 但仍要照 §11.3 跑一次前端建置確認）。
**驗收子集：** 3–11。

### S3 — 前端報表頁與 CSV（規模：中）

- `ReportsView.vue` 依 §5.5(a)–(e)
- `shared/types.ts` 依 §5.5(f)
- `shared/testing/fixtures.ts` 新增 `reportFixture()` —— 目前沒有報表的 fixture，S3 要建一個（與既有 `orderFixture()` 同形、同樣接 `Partial<Report>` overrides）
- 新增 `frontend/src/modules/reporting/ReportsView.dom.test.ts` —— 目前 `modules/reporting/` 完全沒有測試
- 測試：§11.2

**驗收子集：** 12–17。

### 階段切分的理由

- **S1 與 S2／S3 完全無關**，它只是碰巧是同一類缺陷（G20 語意變更的下游）。把它放第一個是因為它最小、最快綠，而且它修的是一個**已經在主線上的錯誤值**，不應該等報表那一大段做完才上。
- **S2 是純加法，所以「獨立可合併且不破壞既有行為」是結構上成立的**，不靠測試運氣：沒有任何既有欄位被改名或改語意。
- **S2 與 S3 之所以不合成一個階段**：S2 要改四處 SQL 加一處手寫測試 DDL 並寫六組測試；S3 要改一個 500 行以上的 Vue 檔、建第一個報表 fixture、寫第一份報表 DOM 測試。兩邊各自都是一次執行的份量。
- **S3 不需要與 S2 同階段落地**（對比 G20 的 `checkout.ts` 必須與計價同階段）：S2 只是讓 API 多回幾個欄位，前端沒跟上的期間，頁面維持現況 —— 現況雖然口徑矛盾，但那是**合併前就已經存在的狀態**，S2 沒有讓它變差。沒有「多收錢」這類風險，所以可以安心拆。

---

## 10. 驗收條件

可逐條勾選。括號標示所屬階段。

- [ ] 1. `reconciliationCandidates` 的 select 清單含 `d.discount_amount discount_code_amount`，且 `OrderDiscount` 的最後一個引數讀該別名（S1）
- [ ] 2. 同時有品項促銷與優惠碼的 ECPAY 待付訂單：`reconciliationCandidates` 回的 `discount().discountAmount()` = 優惠碼折抵、`discountAmount()` = 總折抵（S1）
- [ ] 3. `products[]` 每列含 `net_revenue` 與 `item_discount`，`revenue` 維持毛額且 key 名稱不變（S2）
- [ ] 3a. `topToday[]` 每列含 `net_revenue`**且不含 `item_discount`**；`revenue` 維持毛額且 key 名稱不變（S2）。這一條同時驗兩個方向 —— 有 `net_revenue`（否則 §5.5(c) 的卡片沒東西可讀）、沒有 `item_discount`（否則 §5.5(f) 的型別宣告會與實際回傳不符）。編號用 `3a` 而不是插進 4，是為了不動既有 17 條的編號
- [ ] 4. `Σ products[].revenue − Σ products[].item_discount = Σ products[].net_revenue`（S2）
- [ ] 5. `Σ products[].net_revenue − codeDiscount = revenue`（S2，本規格最重要的一條）
- [ ] 6. `itemDiscount + codeDiscount = discount`（S2）
- [ ] 7. 任何 `products[].net_revenue` 都 ≥ 0（S2）
- [ ] 8. `categoriesNet` 各分類加總 = `netProductRevenue`；`categories`（毛額）維持原值不變（S2）
- [ ] 9. `netProductProfit = netProductRevenue − 商品成本合計`；`netProductMargin` 的分母為 0 時回 0（S2）
- [ ] 10. 沒有任何折抵的月份：`net_revenue` 等於 `revenue`、`itemDiscount` 與 `codeDiscount` 都是 0、`netProductRevenue` 等於 `revenue`（S2 —— 既有的 `ReportAggregationTest` 案例應該要能原封不動通過）
- [ ] 11. SQL 往返次數**不增加**：`ReportAggregationTest` 既有的語句計數斷言維持原數字（S2，見 §11.1）
- [ ] 12. 店長只看得到本店、指定他店回 403；新欄位不改變任何資料範圍（S2）
- [ ] 13. 商品排行表顯示「商品淨營收」、「促銷折抵」、「商品毛利（淨額基礎）」，折抵為 0 的列顯示 `—`（S3）
- [ ] 14. 分類圓餅圖用 `categoriesNet`，標題或副標註明淨額（S3）
- [ ] 15. 摘要區把折抵拆成「品項促銷折抵」與「優惠碼折抵」兩行（S3）
- [ ] 16. CSV 匯出含 §5.5(e) 的欄位，且標頭文字寫明口徑（S3）
- [ ] 17. `npm run build` 與 `npm test` 綠；`./mvnw -B -ntp verify` 綠（S3）

---

## 11. 測試要求

### 11.1 後端（S2，`com.coffee.reporting.internal.ReportAggregationTest`）

既有這支測試已經在**計算 SQL 語句數**（`AtomicInteger statements`，G09 立的 SQL 下推防線）。本規格的要求是：

> **語句數不准增加。** 新欄位一律折進既有的四個查詢，不准為了好寫而多查一次。

既有的語句數斷言**不要改數字** —— 如果它變了，那就是實作多查了一次，要改實作不是改測試（`AGENTS.md` 禁止事項 7）。

測試矩陣：

(a) 有品項促銷、無優惠碼 → 驗收 4、5、6、7
(b) 有品項促銷、有優惠碼 → 驗收 5、6（`codeDiscount` 要剛好吸收掉差額）
(c) 完全沒有折抵 → 驗收 10（既有案例沿用）
(d) 跨兩個品項、其中一個被折到 `net_revenue = 0`（買一送一折掉便宜那一列）→ 驗收 7，確認不是負數也不會讓分類加總跑掉
(e) `categoriesNet` 與 `netProductRevenue` 的加總一致 → 驗收 8
(f) 店長與他店 → 驗收 12（既有的越權案例擴充，斷言新欄位同樣只含本店）

**時間：** 一律注入固定的 epoch 毫秒，**不得使用無參數的 `now()`** —— `TimeZoneGuardTest`（G27）會掃出來，CI 直接紅。`ReportService` 內部用 `LocalDate.now(zone)` 算「今日」是既有行為，**本規格不碰它**；測試要透過 `paid_at` 控制月份而不是改系統時間（既有測試已經是這個做法，照抄）。

### 11.2 後端（S1，`com.coffee.app.ReconciliationTest`）

一條新案例，依驗收 2。需要先寫一筆 `item_promotions` 規則與一組優惠碼，再建一張 ECPAY 待付訂單 —— 建單走既有的 `orders.create`，不要手刻 insert（手刻會跳過 G20 的快照寫入，測不到東西）。

### 11.3 前端（S3）

- `ReportsView.dom.test.ts`（新檔）：
  - 商品排行表在 `item_discount > 0` 與 `= 0` 兩種列的渲染（驗收 13）
  - 摘要區出現兩行折抵（驗收 15）
  - 分類圖的資料來源是 `categoriesNet`（可斷言傳給圖表元件的 series 或改斷言可見文字，依既有 `ReportsView` 的 ECharts 封裝方式決定；**不要為了好測而改動圖表的封裝方式**）
- `reportFixture()` 要讓上面三組案例都能只靠 overrides 表達，不要在每個測試裡手刻整份報表
- S2 完成後、S3 開工前，先跑一次 `npm run build` 確認 S2 的 API 擴充沒有讓既有型別壞掉

### 11.4 不要做的事

- 不要為了讓測試好寫而放寬 `ReportAggregationTest` 的語句計數
- 不要改 `revenue`、`discount`、`grossProfit`、`grossMargin`、`categories`、`products[].revenue` 的語意或名稱
- 不要改 products 查詢的 `order by`
- 不要在報表裡新增任何寫入
- 不要分攤訂單層優惠碼（§13.2）

---

## 12. 與其他工作的並行注意

- **本規格零 migration，V15 仍然空著。** 下一份需要 schema 的規格請占用 V15
- **動到的既有後端檔案：** `OrderService.java`（S1，一處 select 清單 + 一處讀取）、`Reports.java`（S2）、`ReportService.java`（S2）、`ReportAggregationTest.java`（S2）、`ReconciliationTest.java`（S1）
- **與 G20（PR #65）的關係：** 本規格**依賴** G20 的 `order_items.discount_amount` 與 `orders.item_discount_amount`。**這個前提已經滿足** —— [PR #65](https://github.com/choka1227/coffee_GPT6/pull/65) 已於 2026-10-04 合併進 `feature/init-project`（主線合併提交 `e81fa44`，Flyway `V14__item_promotions.sql`），兩個欄位都在主線上。**本項沒有任何外部閘門，可直接開工。**
- **動到的既有前端檔案：** `ReportsView.vue`、`shared/types.ts`、`shared/testing/fixtures.ts`（S3）。這三個檔案 G20 S4 剛動過 `types.ts` 與 `fixtures.ts`，**本規格只加報表相關的欄位，不碰 G20 加的 `itemPromotion` / `discountAmount`**
- 本規格**不碰** `OrderService.create`、`PromotionService`、`DiscountService`、任何 migration

---

## 13. 設計決策

每一項都附理由與「推翻它的代價」。決策是給下一輪推翻用的，不是給人核准用的。

### 13.1 把缺陷 5（對帳投影的折抵別名）併進本規格 —— **併進來**

**決定：** 不另開缺口編號，當作本規格的 S1。

**理由：** 它與缺陷 1–4 是同一個根因 —— G20 §5.5 把 `orders.discount_amount` 改成總折抵之後，所有「讀取層把它當成優惠碼折抵」的地方都錯了。分開做要多一支分支、多一輪審查、多一次 CI，而它本身只有兩行。放在 S1（最小、最先綠）不會拖慢報表那一段。

**推翻的代價：** 幾乎沒有。如果實作端覺得 S1 與 S2／S3 混在一個 PR 不舒服，拆成兩支 PR 也完全可以 —— S1 不依賴 S2，S2 不依賴 S1。

### 13.2 不把訂單層優惠碼折抵分攤到品項 —— **不分攤**

**決定：** `products[].net_revenue` 只扣品項促銷折抵。優惠碼折抵以 `codeDiscount` 單列呈現，不落到任何品項上。

**理由（三點）：**

1. **分攤規則本質上是武斷的。** 一組「滿 500 折 50」的優惠碼折的是「這整張訂單」，不是任何一杯。按金額比例攤是最常見的做法，但它會讓同一杯拿鐵在不同訂單裡有不同的淨營收 —— 那個數字拿去做單品決策的意義很可疑。相對地，品項促銷折抵本來就**定義在那一列上**（G20 直接把它寫進 `order_items.discount_amount`），攤不攤不是問題，它本來就在那裡。
2. **缺陷 1–4 不需要分攤就能修完。** 缺陷 1（單品毛利高估）的主要成因是品項促銷，那是 G20 剛引入、每張命中的訂單都會發生的；優惠碼折抵自 G07 起就存在且是整單層的。修掉前者就修掉了 G20 帶進來的退化，剩下的差額由 `codeDiscount` 明確標示，不再是黑洞。
3. **成本差距是一個數量級。** 分攤要：新增 `order_items.code_discount_amount` 欄位（V15）、在 `OrderService.create` 加一段取整與餘數分配、處理歷史訂單要不要回填、還要證明「每列折抵不超過該列毛額」在餘數分配後仍然成立。**那是動到金額寫入路徑**，為了報表的一欄去碰它，風險與收益不成比例。不分攤的版本是純讀取層、零 migration、零寫入變更。

**推翻的代價：** 想做的時候，分攤演算法已經寫好放在**附錄 A**，照著做即可。屆時 `products[].net_revenue` 的定義要從「扣品項折抵」改成「扣品項折抵 + 攤到的優惠碼折抵」，§5.2 的第二條恆等式會從 `Σ net_revenue − codeDiscount = revenue` 簡化成 `Σ net_revenue = revenue`（更漂亮）。那是一次**語意變更**而不是加法，所以要連同前端與 CSV 的欄位說明一起改，並決定歷史訂單是否回填。本規格把那一天的工作量壓到「照附錄 A 做」，而不是「重新想一次」。

### 13.3 毛額欄位全部保留，不替換 —— **新舊並存**

**決定：** `products[].revenue`、`categories`、`grossProfit` 的名稱與語意一字不改，淨額一律用新 key。

**理由：** 這讓 S2 成為純加法，前端沒跟上也不會壞，S2 因此能獨立合併（這正是 `AGENTS.md`「施工階段」要求的「盡量用加法而不是破壞性變更」）。而且毛額本身**有用** —— 「定價毛額 vs 實收淨額」的差距就是促銷成本，店主要看的正是這個差。

**推翻的代價：** 報表的欄位會比最精簡的設計多一倍，`Report` 介面變長。真的要瘦身就移除毛額 key，但那是破壞性變更，要同時改前端與 CSV，會逼出一個不可分割的大階段 —— 而收益只有「少幾個欄位」。

### 13.4 排序鍵維持毛額 —— **不改 `order by`**

**決定：** products 查詢維持 `order by quantity desc, revenue desc`（`revenue` 是毛額）。

**理由：** 主排序鍵是數量，營收只是同數量時的決勝鍵，改成淨額幾乎不會改變任何真實排名，卻會讓既有測試的排序斷言無聲地鬆動。沒有收益的變更不做。

**推翻的代價：** 若日後要「依淨營收排行」，那是一個新的排序選項（前端加一個下拉），不是改預設值。

### 13.5 `netProductMargin` 的分母用淨營收，不用營業額 —— **用淨營收**

**決定：** `netProductMargin = netProductProfit / netProductRevenue`。

**理由：** 分子分母要同一個口徑才有意義。既有的 `grossMargin = (revenue − cost) / revenue` 用的是訂單層淨額，那一條也維持不動 —— 兩個比率各自內部一致，並存且各自標明口徑。

**推翻的代價：** 頁面上有兩個「毛利率」，要靠文案分清楚。若覺得混淆，可以只顯示一個 —— 但那是前端的呈現選擇，不需要改 API。

### 13.6 前端只在報表頁改動，不碰圖表封裝 —— **不重構**

**決定：** S3 只換資料來源與欄位，不調整 ECharts 的封裝方式，即使那會讓 DOM 測試比較難寫。

**理由：** 重構圖表封裝屬於規格沒要求的範圍擴大（`AGENTS.md` 禁止事項 1），而且會把一個「中」規模的階段撐成「大」。測不到圖表內部就改斷言可見文字或傳入的 option 物件，不要反過來改生產程式碼來迎合測試。

**推翻的代價：** 報表圖表的測試覆蓋會比其他模組薄。要補就另立缺口（前端圖表層測試），與 G23 同一類。

---

## 14. 登記給後續的缺口

| 編號 | 內容 | 來源 |
| --- | --- | --- |
| **G20f** | 訂單層優惠碼折抵分攤到品項（演算法見本規格附錄 A；需要 `order_items.code_discount_amount` 與歷史回填決策） | §13.2 |
| **G20g** | 報表圖表層的測試（ECharts option 的斷言方式，與 G23 同一類） | §13.6 |
| G20a | 菜單與購物車的促銷提示（需要 `GET /api/promotions/active` 顧客端端點） | G20 §13.5、§13.12 |
| G20b | 多規則疊加與單位消耗模型 | G20 §13.2 |
| G20d | 選項層促銷（加料免費、第二份加料半價） | G20 §2.2 |

這些**都不計入規格庫存**，登記的目的是讓下一輪不用重新推導。

---

## 附錄 A — 優惠碼折抵的分攤演算法（給 G20f，本規格不實作）

先寫下來，省得下一輪重新推導。符號：訂單有 `n` 列，第 `i` 列扣掉品項促銷後的金額為 `net_i`（`= 毛額_i − item_discount_i`），`S = Σ net_i`（即 G20 §5.3 的 `afterItems`），優惠碼折抵為 `C`。

```
1. C == 0 → 全部 share_i = 0，結束
2. share_i = floor(C * net_i / S)          // 用 long 運算
3. R = C − Σ share_i                        // 0 ≤ R < n
4. 依 (rem_i = (C * net_i) mod S 降冪, lineIndex 升冪) 排序，掃過每一列：
     若 R > 0 且 share_i < net_i → share_i += 1、R -= 1
   一輪掃完 R 仍 > 0 就再掃一輪（最多 n 輪）
```

**為什麼第 4 步要檢查 `share_i < net_i`：** `net_i` 可以是 0 —— 買一送一折掉便宜那一整列時就會發生。若不檢查就加 1，那一列的總折抵會超過它的毛額，收據會印出負數。

**為什麼一定會終止：** `DiscountService.calculate()` 已經把 `C` 夾在 `S − 1` 以下，所以總餘裕 `Σ (net_i − share_i) = S − (C − R) = S − C + R > R`。每一輪至少配掉一元，所以最多 `n` 輪。

**為什麼第二排序鍵是 `lineIndex`：** 同餘項的選擇必須是確定的，否則同一張購物車在不同 JVM 下會算出不同的分攤歸屬（總額相同但歸屬不同），快照就不可重現。與 G20 §5.2 第 2 步的理由相同。

**要一起決定的事：** 歷史訂單是否回填。本 repo 至今沒有真實營業資料（G07 §11.11 已登記「沒有可部署、可營業的環境」），所以當下的建議是**不回填**，並在報表說明該欄位自某個版本起才有值。若屆時已有真實資料，回填要寫成一次性腳本而不是 Flyway migration —— 分攤要逐單迭代，用純 SQL 寫不乾淨。

---

## 15. 版本紀錄

| 日期 | 版本 | 變更 |
| --- | --- | --- |
| 2026-10-04 | v1.1 | 依 Codex 在 [PR #66](https://github.com/choka1227/coffee_GPT6/pull/66) 的 `REQUEST_CHANGES` 修補三處會讓實作端拿到互相矛盾指示的缺口：(1) `topToday[]` 的 API 契約統一為**只加 `net_revenue`**（§5.3(b) 原本只要求一欄，§5.5(f) 與 §6.1 卻要求兩欄，型別會宣告一個查詢不產生的必填欄位）；(2) `ReportAggregationTest` 的手寫 DDL **兩張表都要補欄位** —— 原本 §4 與 §9 只點了 `order_items.discount_amount`，漏了 §5.3(d) totals 查詢要用的 `orders.item_discount_amount`，照原文施工 S2 必定紅（此項為 Claude 複查時自行發現，不在 Codex 的 review 內）；(3) §12 的開工前提改為「已滿足」，G20 已合併（主線 `e81fa44`）。**設計決策一條未改**；驗收條件既有 17 條的內容與編號全數未動，另加一條 `3a` 把 `topToday[]` 的欄位契約變成可驗證的紅線（原本 §5.3(b) 與 §5.5(c) 都要求它，卻沒有任何一條驗收看得到它）。 |
| 2026-10-04 | v1.0 | 初版。依 G20 §14 的 G20c 登記產出，並在 §13.2 說明為什麼把「訂單層優惠碼的分攤規則」切出去成為 G20f。缺陷 5（對帳投影的折抵別名）為 Claude 審查 PR #65 時發現，併入 S1 |
