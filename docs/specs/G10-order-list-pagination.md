# G10 — 訂單清單分頁、篩選與 N+1 修正

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G10 |
| 優先順序 | P1 |
| 規格版本 | v1.0 |
| 撰寫 | Claude（PM / SA），2026-09-20 |
| 實作 | Codex（PG / SD） |
| 基準 commit | `abbed9a`（PR #20「G13 分店菜單可用性」合併後的 `feature/init-project`） |

> **排程前提：本規格排在 G11+G15（`specs/G11-G15-audit-and-cash-sessions.md`）之後。** 兩份都會動到 `coffee-orders/internal/OrderService.java`：G11+G15 在 `cash()` / `transition()` 加稽核寫入並在 `orders` 加 `cash_session_id` 欄位，本規格改寫 `list()` 並新增 migration。依 `docs/GAP-ANALYSIS.md`「一次一份」的序列化決策，**G11+G15 整份合併進主線之後才開工本規格**，開工時從最新主線開 `codex/g10-*` 分支。

---

## 1. 背景與目標

### 問題

`OrderService.list(Actor)`（`backend/coffee-orders/src/main/java/com/coffee/orders/internal/OrderService.java:137-155`）是門市訂單頁與顧客「我的訂單」頁**唯一**的資料來源，目前長這樣：

```java
return db.queryForList(
        "select id from orders where " + where + " order by created_at desc limit 100",
        String.class, params)
    .stream().map(this::snapshot).toList();
```

三個獨立的缺陷：

**(1) 只看得到最近 100 筆，而且沒有出口。** `limit 100` 是寫死的常數，沒有任何分頁或日期參數。第 101 筆之後的訂單**在系統裡完全查不到** —— 不是「翻頁麻煩」，是沒有任何 UI 路徑能到達。單店一天百來筆，等於營業第二天就開始看不到昨天的單。

**(2) 一次列表 = 上百次 DB 查詢。** `snapshot(id)` 對每一筆訂單做：1 次 `orders join branches`、1 次 `order_items`，再**對每一個品項**做 1 次 `order_item_options`（`OrderService.java:293-345`）。100 筆、每筆 3 個品項，就是 `1 + 100 × (1 + 1 + 3) = 501` 次查詢。G06 的選項模型上線後這個數字還會往上長 —— 第一次盤點記的「201 次」是 G06 之前的數字，現在更糟。

**(3) 篩選全部在前端做。** `OrdersView.vue` 的 `visible` computed 對**已經抓回來的那 100 筆**做狀態篩選與關鍵字搜尋。使用者看到的「全部／待付款／製作中…」分頁籤，篩的是「最近 100 筆裡面的待付款」，不是「所有待付款」。頁面底部那句「顯示最近 100 筆訂單中的 N 筆」誠實地寫出了這件事，但它描述的是一個不能用的行為。

三者互相纏住：不先把分頁做到後端，篩選就永遠只能篩那 100 筆；不先把 N+1 解掉，把 `limit` 開大就等於把查詢次數開大。

### 目標

1. 訂單清單改為**游標分頁**，可以一直往前翻到最早的訂單
2. 狀態、分店、時間區間、關鍵字**全部在後端篩選**，篩的是全集不是當頁
3. 一頁訂單的 DB 查詢次數**固定為 3 次**，與該頁筆數、品項數、選項數無關
4. 資料範圍規則完全不變（顧客只有自己的、BRANCH 只有所屬分店、GLOBAL 跨店），並補上跨店篩選的越權測試

### 不是目標

- 不做任何金額、狀態機、下單流程的變更
- 不做排序選項（一律 `created_at` 由新到舊）。理由見 §11.3
- 不做全文檢索、不引入任何檢索套件（`AGENTS.md` 禁止引入新相依）
- 不改 `reconciliationCandidates()` 的行為（只補一行註解，見 §5.8）
- 不做匯出 CSV／報表（那是 G09 的範圍）

---

## 2. 範圍

### 在範圍

| # | 項目 |
| --- | --- |
| 1 | 新增 migration：`orders(created_at, id)` 複合索引（GLOBAL 範圍的游標掃描用） |
| 2 | `Orders` api 新增 `Query` / `Page` record 與 `page(Actor, Query)` 方法 |
| 3 | `OrderService` 新增 `page()`：一次分頁查詢 + 批次載入品項與選項，固定 3 次查詢 |
| 4 | 新端點 `GET /api/orders/page`（S1 加），S3 改掛到 `GET /api/orders` 並移除舊的陣列版本 |
| 5 | 前端 `OrdersView.vue` 改用分頁端點：狀態／關鍵字／時間送後端，加「載入更多」 |
| 6 | `docs/API.md` 更新 |
| 7 | 順手結清 G01a 留下的兩項非阻斷觀察（`Orders.java` 的註解、`scope.replace` 字串替換），見 §5.8 |

### 不在範圍

- `GET /api/orders/{id}` 明細端點不變
- `reconciliationCandidates()` 的查詢邏輯不變
- 報表（`coffee-reporting`）不動
- 顧客端「我的訂單」與門市端共用同一個端點，不拆成兩支

---

## 3. 涉及模組與邊界

| 模組 | 變更 |
| --- | --- |
| `coffee-orders/api` | 新增 `Orders.Query`、`Orders.Page` record；新增 `page()`；S3 移除 `list()` |
| `coffee-orders/internal` | `OrderService.page()` 與批次載入；`OrderController` 端點 |
| `coffee-app` | 新增 Flyway migration；測試 |
| `frontend/src/modules/ordering` | `OrdersView.vue` |
| `frontend/src/shared` | `types.ts` 加 `OrderPage` 型別 |

**邊界規則**：本規格**不新增任何跨模組依賴**。`page()` 需要的 `branch_name` 沿用現有的 `orders join branches` 查詢（`coffee-orders` 本來就這樣做，見 `snapshot()`），不新增對 `coffee-branches` 的 Java 依賴。`ModuleBoundariesTest` 的既有斷言不需要修改 —— 如果實作後它紅了，代表做法走偏了，不要改測試。

---

## 4. DB schema 與 migration

### 4.1 migration 檔名

**預期檔名 `V7__order_list_indexes.sql`。** V5／V6 預留給 G11+G15（稽核擴充與現金班別）。**開工當下以 `backend/coffee-app/src/main/resources/db/migration/` 目錄裡實際的下一個未使用版號為準**，並在 PR 描述註明實際用了哪一號。不得修改任何既有 migration 檔。

### 4.2 內容

```sql
-- 游標分頁在 GLOBAL 資料範圍下的排序鍵
CREATE INDEX idx_orders_created_id ON orders(created_at, id);
```

**只加這一支索引。** 理由與「為什麼不順手改既有索引」寫在 §11.1。

`order_items(order_id)`（`idx_order_items_order`）與 `order_item_options(order_item_id)`（`idx_order_item_options_item`）在 V1／V3 已經存在，批次載入用得到，不需要新增。

---

## 5. API

### 5.1 `Orders` interface 的變更（`coffee-orders/src/main/java/com/coffee/orders/api/Orders.java`）

新增兩個 record 與一個方法。`Order` / `Line` / `LineOption` 的欄位**完全不動**：

```java
  /**
   * 訂單清單查詢條件。所有欄位皆可為 null（代表不篩）。
   * cursor 為前一頁回傳的 nextCursor，原樣回傳即可，前端不得自行組裝。
   */
  record Query(
      String status, String branchId, Long from, Long to, String q, String cursor, int limit) {}

  /** nextCursor 為 null 代表已無下一頁。 */
  record Page(List<Order> items, String nextCursor) {}

  Page page(Actor a, Query query);
```

`List<Order> list(Actor a)` 在 S1／S2 期間**保留不動**，S3 才移除（見 §8）。

### 5.2 HTTP 端點

**S1／S2 期間**：

```
GET /api/orders/page?status=&branchId=&from=&to=&q=&cursor=&limit=
```

**S3 之後**（路徑改掛，參數與回應完全相同）：

```
GET /api/orders?status=&branchId=&from=&to=&q=&cursor=&limit=
```

| 參數 | 規則 |
| --- | --- |
| `status` | 訂單狀態，需為 `orders.status` 的 CHECK 清單之一（`PENDING_PAYMENT`／`PAID`／`PREPARING`／`READY`／`COMPLETED`／`CANCELLED`）。不給＝不篩。給了不在清單內的值 → 400 |
| `branchId` | 分店 id。資料範圍規則見 §5.5 |
| `from` / `to` | `created_at` 的 epoch millis 閉區間。只給一邊也可以。`from > to` → 400 |
| `q` | 關鍵字，比對規則見 §5.6。前後空白去除，去除後為空字串視同未給 |
| `cursor` | 前一頁的 `nextCursor`，格式見 §5.4 |
| `limit` | 預設 50，上限 200。**超過上限夾到 200，不報錯**；小於 1（含未給、負數）一律當 50 |

回應：

```json
{
  "items": [ { /* 與現行 Orders.Order 完全相同的物件，含 items 明細 */ } ],
  "nextCursor": "1758300000000:ORD-20260920-0007"
}
```

`items` 依 `created_at` 由新到舊，同一毫秒時再依 `id` 由大到小。最後一頁的 `nextCursor` 為 `null`。

**為什麼清單裡仍然帶完整品項明細**：見 §11.2。

### 5.3 分頁查詢 SQL

排序鍵固定 `(created_at desc, id desc)`。游標條件**寫成展開式，不要用 row value 比較**：

```sql
select o.*, b.name branch_name
  from orders o join branches b on b.id = o.branch_id
 where <資料範圍條件>
   [and o.status = ?]
   [and o.created_at >= ?] [and o.created_at <= ?]
   [and (<q 條件，見 5.6>)]
   [and (o.created_at < ? or (o.created_at = ? and o.id < ?))]   -- 游標
 order by o.created_at desc, o.id desc
 limit ?
```

**為什麼不用 `(o.created_at, o.id) < (?, ?)`**：測試跑的是 H2 的 PostgreSQL 相容模式（`MODE=PostgreSQL`），正式環境是 PostgreSQL。展開式在兩邊都保證可用、都能吃到索引，row value 比較則要賭 H2 該版本的支援程度。這裡沒有效能差異，只有可攜性差異。（G11 規格 §5.4 寫的是 row value 形式；如果 G11 實作時已經在 H2 上驗證可用，兩種寫法本規格都接受，但不要為了「跟 G11 一致」而把已經能動的展開式改掉。）

**多抓一筆判斷有沒有下一頁**：SQL 的 `limit` 送 `limit + 1`，回來的列數 > `limit` 時，砍掉最後一筆，並以**保留下來的最後一筆**組出 `nextCursor`；否則 `nextCursor = null`。不要用 `count(*)` 再查一次總數 —— 總數對游標分頁沒有用途，而且是第 4 次查詢。

### 5.4 游標格式與解析

游標是 `createdAt + ":" + id`，例如 `1758300000000:ORD-20260920-0007`。對前端而言是**不透明字串**，只能原樣回傳。

解析規則（**任何一條不符就是 400**，錯誤訊息「查詢游標格式不正確」）：

1. 以第一個 `:` 切成兩段，右段本身可以含 `:`（`orders.id` 目前不含，但不要依賴這件事）
2. 左段必須全為數字且可解析為 `long`（**用 `try/catch` 或先驗證字元，絕對不可以讓 `NumberFormatException` 逸出成 500**）
3. 右段不得為空、長度不得超過 `orders.id` 的欄位長度（`VARCHAR(20)`）

游標**不做簽章也不做加密**。它只是排序鍵，偽造一個游標最多讓自己從別的時間點開始翻頁，翻到的仍然是通過 §5.5 資料範圍過濾後的資料 —— 偽造游標**無法越權**，這是本設計的必要性質，實作時不要把資料範圍條件做成「靠游標帶進來」。

### 5.5 權限與資料範圍

| 呼叫者 | 規則 |
| --- | --- |
| 顧客（`a.customer()`，scope `SELF`） | 強制 `account_id = a.id()`。不需要 `ORDER_MANAGE`。帶了 `branchId` **照樣套用**（交集後仍只有自己的訂單，不洩漏任何東西） |
| 員工，scope `GLOBAL` | 需要 `ORDER_MANAGE`。`branchId` 未給＝全部分店；給了就篩該分店 |
| 員工，scope `BRANCH` | 需要 `ORDER_MANAGE`。強制 `branch_id = a.branchId()`。`branchId` 給了且**不等於**所屬分店 → **403「只能存取所屬分店資料」**（用 `a.branch(branchId)`，不要自己寫字串比對） |

**跨店的 `branchId` 一律 403，不是「靜默忽略」。** 靜默忽略會讓越權測試寫不出有意義的斷言（回 200 且資料正確，測試就只能斷言「沒看到別店資料」，而看不到的原因可能只是那家店沒訂單）。403 是可斷言的。

順序上，**先做權限判斷，再組 SQL**：`a.require("ORDER_MANAGE")` 與 `a.branch(branchId)` 都在查詢之前，不要讓沒有權限的呼叫先打到 DB。

### 5.6 關鍵字 `q` 的比對規則

`q` 同時比對**訂單編號**與**品項名稱**，任一命中即算命中：

```sql
and ( lower(o.id) like ? escape '\'
      or exists (select 1 from order_items i
                  where i.order_id = o.id and lower(i.name) like ? escape '\') )
```

| 規則 | 內容 |
| --- | --- |
| 大小寫 | 兩邊都套 `lower()`。**不要用 `ILIKE`** —— 那是 PostgreSQL 方言，H2 相容模式不保證 |
| 比對樣式 | `%` + 使用者輸入 + `%`（含頭萬用字元，理由見 §11.4） |
| 跳脫 | 使用者輸入裡的 `\`、`%`、`_` **必須先跳脫**（`\` → `\\`、`%` → `\%`、`_` → `\_`），並宣告 `escape '\'`。沒跳脫的話，使用者打一個 `%` 就會撈到全部 |
| 長度上限 | 去空白後 > 60 字元 → 400「搜尋關鍵字過長」。避免把整份文件貼進來當樣式 |

前端現行的「搜尋編號或餐點」行為因此**原樣保留**，只是改在後端做、篩的是全集。

### 5.7 N+1 的解法：固定三次查詢

一頁的組裝流程：

1. **第 1 次**：§5.3 的分頁查詢 → 得到最多 `limit` 筆訂單表頭（含 `branch_name`）
2. **第 2 次**：`select * from order_items where order_id in (?,?,…) order by order_id, name`
3. **第 3 次**：`select order_item_id, group_name, option_name, price_delta from order_item_options where order_item_id in (?,?,…) order by order_item_id, id`

然後在記憶體裡以 `Map<String, List<…>>` 組回 `Order` → `Line` → `LineOption`。

實作要求：

- **`in (…)` 的佔位符依實際 id 數量動態生成**，不要用字串拼接把 id 值直接放進 SQL（那是 SQL injection 的標準入口，即使 id 是自家產生的也不要）
- **第 1 次查詢回 0 筆時，直接回傳空 `Page`，不要送出第 2、3 次查詢**（`in ()` 是語法錯誤）
- 第 2 次查詢回 0 筆時同理跳過第 3 次
- 排序 `order by order_id, name` 必須保留 —— 現行 `snapshot()` 的品項就是依 `name` 排序，這是使用者看得到的順序，不要因為改寫而變動
- `lineTotal` 仍然用 `Math.multiplyExact(Math.addExact(unit_price, options_price), quantity)` 在後端算，**不要改成從 DB 取或讓前端算**（§6）

`snapshot(String id)` 給 `get()` / `cash()` / `transition()` 用的單筆路徑**保持不動** —— 單筆的 N+1 不是問題，而且改它會擴大本規格對狀態變更路徑的影響面。

### 5.8 順手結清 G01a 留下的兩項觀察

這兩項記在 `docs/GAP-ANALYSIS.md`「G01a 合併時留下的三項非阻斷觀察」，條件都是「下次動到這兩個檔案時順手做」。本規格正好動到，所以**納入 S1 的範圍**，不是額外擴大範圍：

1. **`Orders.java` 的 `reconciliationCandidates()` 補註解**：
   ```java
   /** 只回表頭欄位，items() 固定為空 List（對帳不需要品項，避免 N+1）。 */
   List<Order> reconciliationCandidates(Actor actor, long since, long until, int limit, int offset);
   ```
2. **`OrderService.reconciliationCandidates()` 的 `scope.replace("branch_id", "o.branch_id")`（`OrderService.java:247`）改直接寫**：把 `scope` 的值由 `" and branch_id=?"` 改成 `" and o.branch_id=?"`，刪掉 `.replace(...)`。純等價改寫，行為不變，既有測試必須照常綠。

第 3 項觀察（用 DataSource proxy 釘住查詢次數）**不納入**，理由見 §11.5。

### 5.9 錯誤碼

| 狀況 | 狀態碼 | 訊息 |
| --- | --- | --- |
| 未登入 | 401 | （既有的 Security 處理，不新增） |
| 沒有 `ORDER_MANAGE`（非顧客） | 403 | 沒有此功能的操作權限 |
| BRANCH 範圍查別店 | 403 | 只能存取所屬分店資料 |
| `status` 不在允許清單 | 400 | 訂單狀態不正確 |
| `from > to` | 400 | 查詢時間區間不正確 |
| 游標格式錯誤 | 400 | 查詢游標格式不正確 |
| `q` 超過 60 字元 | 400 | 搜尋關鍵字過長 |
| `limit` 超過 200 | — | **不報錯**，夾到 200 |

400 那幾列用 `Problem.check(條件, "訊息")`；403 一律由 `Actor` 的 `require()` / `branch()` 丟出，不要自己 `new Problem(403, …)`（訊息會不一致）。

---

## 6. 金額規則

本規格**不計算任何金額**，只讀取既有快照欄位。仍然適用的硬規則：

- `total` 直接取 `orders.total`，`lineTotal` 由 `unit_price + options_price` 與 `quantity` 在**後端**用 `Math.addExact` / `Math.multiplyExact` 算出，與現行 `snapshot()` 完全一致
- **查詢參數裡不得出現任何金額欄位**（不做「金額區間篩選」）。這不是遺漏，是刻意不給：金額篩選會誘使前端傳金額進來，而本專案的硬規則是後端不信任前端金額。要找某筆訂單請用編號或時間
- 前端不得自行加總品項金額顯示成訂單總額

---

## 7. 前端

`frontend/src/modules/ordering/OrdersView.vue`：

| 現況 | 改為 |
| --- | --- |
| `orders.value = await api<Order[]>("/orders")` | `api<OrderPage>("/orders/page?…")`（S3 後改 `/orders?…`） |
| `visible` computed 做狀態篩選 | 狀態改為查詢參數，切換分頁籤 → 重新查第一頁（游標歸零） |
| `visible` computed 做關鍵字搜尋 | 關鍵字改為查詢參數，**加 300ms debounce**，送出前 `trim()`，變更即重查第一頁 |
| 底部「顯示最近 100 筆訂單中的 N 筆」 | 改為「已載入 N 筆」＋ `nextCursor` 非 null 時顯示「載入更多」按鈕 |
| — | 「載入更多」帶 `cursor` 查下一頁，結果 **append** 到現有陣列；載入中按鈕 disabled |

其他要求：

- `types.ts` 新增 `export type OrderPage = { items: Order[]; nextCursor: string | null }`
- 查詢字串用 `URLSearchParams` 組，**不要手動字串拼接**（`q` 會有 `&`、`#`、空白）
- 任何篩選條件變更都必須**清掉已載入的資料與游標**再查，不可以把新條件的結果 append 在舊條件的結果後面
- 狀態變更（收款、轉狀態、取消）後的 `load()` 維持現行行為：**重查第一頁**，已翻過的頁數不保留。理由：狀態變更會改變該筆是否仍符合目前的狀態篩選，保留舊頁面會顯示已經不符合條件的列
- `route.query.order` 的深連結行為不變（先在已載入的列裡找，找不到再打 `/orders/{id}`）
- 顧客端與門市端共用同一個元件與端點，差異只在後端的資料範圍

---

## 8. 施工階段

三個階段，**每階段獨立可編譯、測試全綠、CI 綠、單獨合併不破壞既有行為**。S1／S2 全是加法，破壞性變更全部集中在 S3，而 S3 本身很小。

### 施工進度（G10）—— PR 描述請維護這張表

```markdown
## 施工進度（G10）
- [ ] S1 後端分頁查詢與批次載入（純加法）
- [ ] S2 前端改用分頁端點
- [ ] S3 移除舊的 `GET /api/orders` 陣列版本
```

### S1 — 後端分頁查詢與批次載入（純加法）

| 項目 | 內容 |
| --- | --- |
| 規模 | 中。1 個 migration、`Orders.java`、`OrderService.java`、`OrderController.java`、1 個新測試類別 |
| 動到的檔案 | `V7__order_list_indexes.sql`（版號以開工當下為準）、`coffee-orders/api/Orders.java`、`coffee-orders/internal/OrderService.java`、`coffee-orders/internal/OrderController.java`、`coffee-app/src/test/java/com/coffee/app/OrderPaginationTest.java`、`docs/API.md` |
| 內容 | §4 migration、§5.1 record 與方法、§5.3–§5.7 的 `page()` 實作、`GET /api/orders/page` 端點、§5.8 兩項結清 |
| 不含 | 前端一律不動。舊的 `list()` 與 `GET /api/orders` 原封不動 |
| 驗收子集 | §9 的 1–14 |

**這一階段合併後，系統行為對使用者完全沒有變化** —— 新端點沒有人呼叫。這是刻意的：S1 單獨合併的風險接近零。

### S2 — 前端改用分頁端點

| 項目 | 內容 |
| --- | --- |
| 規模 | 小到中。1 個 Vue 元件、1 個型別、1 份文件 |
| 動到的檔案 | `frontend/src/modules/ordering/OrdersView.vue`、`frontend/src/shared/types.ts`、`docs/API.md` |
| 內容 | §7 全部 |
| 驗收子集 | §9 的 15–20 |

合併後使用者才真正拿到分頁與全集篩選。舊端點仍在，沒有人呼叫。

### S3 — 移除舊的陣列版本（破壞性，但很小）

| 項目 | 內容 |
| --- | --- |
| 規模 | 小 |
| 動到的檔案 | `coffee-orders/api/Orders.java`、`coffee-orders/internal/OrderService.java`、`coffee-orders/internal/OrderController.java`、`OrdersView.vue`（只改路徑字串）、既有測試裡呼叫 `list()` 的地方、`docs/API.md` |
| 內容 | 刪除 `Orders.list(Actor)` 與 `OrderService.list()`；`OrderController` 把 `@GetMapping("/page")` 改成 `@GetMapping`（`/api/orders/page` 一併消失）；前端改回 `/orders`；更新所有仍在呼叫 `list()` 的測試 |
| 驗收子集 | §9 的 21–23 |

**為什麼值得多花一個階段做這件事**：`/api/orders/page` 這個路徑只是過渡期的產物，留著它等於永久多一條沒人維護的路徑，而且下一個讀 `OrderController` 的人會看到兩支長得像的端點。S3 很小（刪兩個方法、改一個 annotation、改一個字串），但必須是獨立階段，因為它是唯一會讓既有呼叫端壞掉的一步。

**額度不足時的收尾**：S1 沒做完就先推「migration + record + 尚未接上的 `page()`」這種可編譯、測試綠的中間狀態，PR 維持 draft，並更新上面那張進度表。

---

## 9. 驗收條件

逐條可勾。括號裡是所屬階段。

**S1 — 後端**

1. （S1）migration 新增 `idx_orders_created_id`，未修改任何既有 migration 檔
2. （S1）`Orders.Query` / `Orders.Page` 定義在 `api` package 的 interface 內部，且都是 `record`
3. （S1）`GET /api/orders/page` 未給任何參數時，回最新 50 筆與一個非 null 的 `nextCursor`（資料超過 50 筆時）
4. （S1）連續帶 `nextCursor` 翻完所有頁：所有頁的 id 聯集**等於**該資料範圍下的全集，且**沒有任何重複**
5. （S1）最後一頁的 `nextCursor` 為 `null`
6. （S1）`limit=500` 回 200 筆（夾到上限），**不報錯**；`limit=0` 與未給一樣回 50 筆
7. （S1）`status=PAID` 篩出的是**全集裡**所有 `PAID` 的訂單（造 > 50 筆資料，確認第 51 筆也篩得到），不是當頁篩選
8. （S1）`from` / `to` 篩選為閉區間，邊界值（等於 `from`、等於 `to` 的訂單）都包含在內
9. （S1）`q` 同時命中訂單編號與品項名稱；大小寫不敏感
10. （S1）`q=%` 只會比對字面上的百分比符號，**不會**撈出全部（跳脫生效）
11. （S1）游標格式錯誤（`abc`、`:`、`123:`、空字串）一律 400，**沒有任何 500**
12. （S1）同一毫秒建立的多筆訂單翻頁時不重複、不遺漏（`id` 作為第二排序鍵生效）
13. （S1）一頁的 DB 查詢次數為 3 次，且與該頁筆數無關（驗法見 §10.4）
14. （S1）`reconciliationCandidates()` 的既有測試照常綠；`Orders.java` 上有 §5.8 的註解；`scope.replace` 已移除

**S2 — 前端**

15. （S2）訂單頁初次載入顯示 50 筆，底部顯示「載入更多」
16. （S2）點「載入更多」把下一頁 append 在後面，不重複、不跳號
17. （S2）切換狀態分頁籤會重新查第一頁，且篩的是全集（第 51 筆之後的同狀態訂單也會出現）
18. （S2）搜尋框有 debounce，輸入中途不會每個字打一次 API
19. （S2）收款／轉狀態／取消之後畫面重查第一頁，狀態正確
20. （S2）底部不再出現「最近 100 筆」字樣

**S3 — 收尾**

21. （S3）`GET /api/orders` 回的是 `{items, nextCursor}`，`/api/orders/page` 已不存在（404）
22. （S3）`Orders.list(Actor)` 已從 api 移除，全專案沒有任何呼叫點
23. （S3）`docs/API.md` 的訂單清單一節與實作一致

**全階段共同**

24. `ModuleBoundariesTest` 綠，沒有為了通過而修改測試
25. `frontend: npm ci && npm run build` 綠、`backend: ./mvnw -B -ntp verify` 綠
26. 錯誤訊息全部是繁體中文（台灣用語），格式 `{"message": "..."}`

---

## 10. 測試要求

測試放 `backend/coffee-app/src/test/java/com/coffee/app/`，新檔案建議 `OrderPaginationTest.java`。

### 10.1 業務規則（優先寫不需要 Spring context 的單元測試）

- **游標編解碼**：`encode(createdAt, id)` → `decode()` 往返一致；`decode()` 對 `abc`、`:`、`123:`、`:x`、空字串、超長 id 全部丟 `Problem(400)`，**不丟 `NumberFormatException`**
- **`q` 的跳脫**：輸入 `100%`、`a_b`、`c\d` 產生的樣式字串正確

### 10.2 分頁正確性（整合測試）

- 造 **> 2 頁**的訂單（例如 `limit=10` 配 25 筆），連續翻頁把所有 id 收集起來，斷言「無重複」且「等於全集」
- 造**同一毫秒**的多筆訂單（直接指定 `created_at` 相同），重複上述斷言
- 翻頁途中**插入一筆新訂單**，斷言已翻過的頁不會因此重複或遺漏既有資料（游標分頁對 append-only 排序鍵的基本性質；這正是不用 offset 的原因）

### 10.3 越權測試（必要，`AGENTS.md` 授權章節強制）

- 顧客只看得到自己的訂單：A 顧客查詢，結果不含 B 顧客的任何訂單 id
- 顧客帶 `branchId` 仍只看得到自己的訂單
- BRANCH 員工不帶 `branchId`：只有所屬分店的訂單
- **BRANCH 員工帶別店 `branchId` → 403**（不是 200 加空清單）
- 沒有 `ORDER_MANAGE` 的員工帳號 → 403
- GLOBAL 員工帶 `branchId` → 只有該店；不帶 → 跨店都有
- **游標不能越權**：拿 A 顧客那一頁的 `nextCursor`，用 B 顧客的身分帶進去，回來的仍然只有 B 的訂單

### 10.4 查詢次數（驗收條件 13）

用一層計數用的 `DataSource` / `Connection` proxy（`java.lang.reflect.Proxy` 包住 `DataSource`，攔 `prepareStatement`）數一次 `page()` 呼叫送出的 statement 數：

- 5 筆訂單的一頁 → 3
- 50 筆訂單的一頁 → 3（**同一個數字**，這才是斷言的重點）
- 0 筆的一頁 → 1

這是本規格唯一要求的效能測試。**不要**改用「量執行時間」那種會在 CI 上飄的斷言。

### 10.5 CSRF

清單是 `GET`，不需要 CSRF token，也不該因為缺 token 被擋。`HttpWorkflowTest` 補一條：未帶 CSRF token 的 `GET /api/orders/page`（S3 後為 `/api/orders`）回 200。

---

## 11. 設計決策

每一項都附理由與推翻它的代價。決策是給下一輪推翻用的。

### 11.1 只加一支索引，不動既有的兩支 —— **只加 `(created_at, id)`**

**決定**：新增 `idx_orders_created_id ON orders(created_at, id)`，**不動** V1 既有的 `idx_orders_account_created(account_id, created_at)` 與 `idx_orders_branch_created(branch_id, created_at)`。

**理由**：顧客與 BRANCH 員工的查詢都有前導欄位（`account_id` / `branch_id`），既有索引已經能定位到 `created_at` 的位置，同一毫秒的少數幾列再比 `id` 是可忽略的成本。GLOBAL 範圍沒有前導欄位，才真的缺一支純 `created_at` 排序索引。把既有索引重建成三欄是**沒有實測依據的優化**，而且 `CREATE INDEX` / `DROP INDEX` 在正式資料上是要停等的動作。

**推翻的代價**：如果日後出現大量同毫秒訂單（壓測、批次匯入），再加 `(branch_id, created_at, id)` 與 `(account_id, created_at, id)` 兩支即可，**純新增、不必刪舊的**，成本很低。所以現在不做是安全的。

### 11.2 清單仍回完整品項明細 —— **回**

**決定**：`page()` 回傳的每一筆都含完整 `items`（與現行 `Order` 完全相同的形狀），不另外設計精簡的 summary record。

**理由**：(1) 批次載入之後，品項與選項固定是 2 次查詢，**與頁筆數無關** —— N+1 已經被解掉了，精簡形狀省下的是網路 payload，不是查詢次數；(2) 現行 UI 的清單列要顯示第一項品名與總件數，明細 Modal 直接用同一個物件，改成 summary 會逼出「點開就再打一次 API」的往返，而門市人員整天都在點開那個 Modal；(3) 少一個 record 就少一個要跟 `Order` 同步維護的形狀。

**推翻的代價**：若 payload 變成問題（例如 `limit=200` 且每筆十幾個品項），加一個 `?fields=summary` 參數與對應 record 即可，屬於純加法，`Order` 的既有形狀不受影響。

### 11.3 不做排序選項 —— **固定 `created_at desc, id desc`**

**決定**：不提供 `sort` 參數。

**理由**：游標分頁的游標**就是排序鍵**。開放排序等於每一種排序都要有自己的游標格式與自己的索引，而目前沒有任何一個實際需求是「依金額排序的訂單清單」 —— 門市要的是「最新的在最上面」，查舊單用時間區間。多開一個參數換來的是三倍的測試面。

**推翻的代價**：真的需要時，`Query` 加一個 `sort` 欄位、游標前面加一個排序鍵代號（`c:` / `t:`），並為新排序鍵補索引。屬於加法，但游標格式會變 —— 變更當下翻到一半的舊游標會失效（回 400 而不是回錯資料，可接受）。

### 11.4 `q` 用含頭萬用字元的 `like` —— **用，並接受它不走索引**

**決定**：`lower(x) like '%關鍵字%'`，明知含頭的 `%` 讓 B-tree 索引失效。

**理由**：門市搜尋的是「訂單編號後幾碼」與「品名中間幾個字」，前綴比對根本答不出使用者要的東西。正確的解法（pg_trgm GIN 索引或全文檢索欄位）要嘛引入擴充、要嘛加欄位與觸發器，而 `AGENTS.md` 明禁引入新相依，且目前資料量下一次順序掃描的成本遠低於做這件事的複雜度。**把限制寫進規格，比假裝它不存在好。**

**推翻的代價**：資料量長大到搜尋變慢時，`q` 的比對改成 `pg_trgm` 索引（PostgreSQL 內建擴充，`CREATE EXTENSION pg_trgm` 一行，不是新的 Java 相依），SQL 形狀不變。但 H2 沒有 pg_trgm，測試環境要另外處理 —— 那時候大概也該把測試換成 Testcontainers 了，那是更大的決定。

### 11.5 不做「查詢次數與資料量無關」的通用測試框架 —— **只在本規格釘住 3 次**

**決定**：§10.4 的 proxy 只服務本規格的 `page()`，不做成共用的測試基礎設施，也不回頭補 G01a 第 3 項觀察（`pending()` 的查詢次數）。

**理由**：`pending()` 那條路徑現在沒有人動，補測試是為了保護一個沒有變更壓力的地方；而 `page()` 的 3 次查詢是本規格的核心承諾，沒有測試釘住就會在下一次重構時默默退回 N+1。先釘住有變更壓力的那一個。

**推翻的代價**：若之後第三、第四處也需要，把 proxy 抽成測試工具類別即可，抽取成本很低（那本來就是二十行的東西）。

### 11.6 顧客端與門市端共用同一支端點 —— **共用**

**決定**：不拆成 `/api/orders/mine` 與 `/api/orders`。

**理由**：資料範圍本來就由 `Actor` 決定，拆成兩支等於把同一條規則寫兩遍，而「兩份會漂移的授權判斷」正是權限漏洞最常見的來源。現行 `list()` 也是共用的，維持一致。

**推翻的代價**：若顧客端日後需要完全不同的回應形狀（例如只回自取碼與狀態），再拆；屆時兩支的資料範圍判斷必須抽成同一個方法，不可以各寫各的。

---

## 12. 給 Codex 的施工提醒

1. **開工前先確認 G11+G15 已整份合併進主線**（`docs/GAP-ANALYSIS.md` 的工作順序），再從最新主線開 `codex/g10-*` 分支。本規格與 G11+G15 都改 `OrderService.java`
2. **migration 版號以開工當下目錄裡的下一個未使用號為準**，PR 描述要寫明實際用了哪一號
3. `page()` 的資料範圍條件**必須在 SQL 的 where 裡**，不可以查回來再用 Java filter 掉 —— 後者會讓 `limit` 的語意變成「先取 50 筆再濾剩幾筆」
4. 游標解析**不可以讓任何 runtime exception 逸出**成 500。解析失敗一律 `Problem(400, "查詢游標格式不正確")`
5. `in (…)` 的佔位符動態生成，**不要字串拼 id 值**
6. 三個階段各自推一次，每次推之前本機跑 `npm run build` 與 `./mvnw -B -ntp verify`
7. 規格有錯或做不到的地方，**寫進 PR 描述與 `docs/reports/`，寫完繼續做**，不要停下來等回覆（`AGENTS.md`「設計決策的歸屬」）
8. 完成後在 `docs/reports/G10-order-list-pagination.md` 留進度報告，格式參考 `reports/G13-branch-menu-availability.md`
