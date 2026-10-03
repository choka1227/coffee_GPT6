# G08 — 分店每日可售數量與自動售完

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G08（P2 升為 P1） |
| 版本 | **v1.2（2026-10-03）** —— v1.1 修補 v1.0 回補路徑的超賣缺口，新增保留憑據表（§4.6、§5.5、§13.12）；v1.2 修補兩處規格缺陷：驗收 16 原條件不可能成立（§5.6 的「v1.2 修正」），剩餘徽章與「今日售完」徽章的關係未定（§13.13、驗收 19a）。完整對照見 §15 |
| 登記來源 | `docs/GAP-ANALYSIS.md` P2 表第 G08 列：「`products` 沒有任何庫存欄位。注意 G13（售罄）是 G08 的輕量版」 |
| Flyway 版號 | **V13**（V12 由 G24 占用，見 §4.1） |
| 涉及後端模組 | `coffee-catalog`（主）、`coffee-orders`（呼叫端） |
| 新增資料表 | `branch_product_stock`、`branch_product_stock_reservation`（兩張都在 V13） |
| 涉及前端模組 | `modules/ordering`（POS 菜單）、`shared/types.ts` |
| 施工階段 | 四階段 S1–S4，見 §9 |

---

## 1. 背景與目標

### 1.1 現況

分店端目前只有一個二元旗標可以表達「這項今天不要賣了」：G13 做的 `branch_products.availability='SOLD_OUT'` + `sold_out_date`（`V4__branch_menu_availability.sql`）。店員在 POS 菜單上按「標記售完」（`frontend/src/modules/ordering/MenuView.vue:403`），`CatalogService.sellable`（`CatalogService.java:115`）隔天自動失效，因為 `sold_out_date` 不等於今天就讀成 `AVAILABLE`。

這個機制本身是對的，而且已經有測試護著（`BranchMenuAvailabilityTest`）。它的限制是**時機**：

> 「標記售完」要有人**先發現**賣完了，才有人去按。

`products` 沒有任何數量欄位，`branch_products` 也沒有。系統完全不知道今天這項做了幾份、賣掉幾份，所以在「最後一份被賣掉」與「有人想起來去按標記售完」之間，店是**超賣**狀態。

### 1.2 要擋的缺陷（具體的）

1. **手作烘焙類的超賣。** 今天只烤了 12 個可可司康。第 12 個賣掉之後，菜單上它還是「供應中」，顧客端（`MenuView.vue` 的顧客模式）照樣下單成立、照樣收錢。店員要等到備餐時才發現沒有了，只能退款或道歉 —— 而 G03（退款與退單）是**延後項目**，目前系統裡**沒有退款流程**，所以這筆錯單除了手動取消（僅限 `PENDING_PAYMENT` 且 `CASH`，見 `OrderService.java:471`）之外無處可去。線上付款（ECPAY）的超賣訂單在現況下**完全無法退**。
2. **忙的時候沒有人記得按。** 「標記售完」是一個額外的動作，而且發生在最忙的時段。靠紀律解決的事，在尖峰時段一定會漏。
3. **沒有「剩幾份」這個資訊。** 店員無法回答顧客「還有幾個」，也無法在剩 2 份時決定要不要留給內用。這不是缺陷，是缺資訊，但它與 1 和 2 同源。

### 1.3 目標

1. 分店可以為**某個商品**設定**今天**的可售數量（例如可可司康 12 份）
2. 訂單成立時**在同一個交易裡扣減**，扣到 0 就擋下後續的下單，**不需要任何人按任何按鈕**
3. 數量不足時的錯誤訊息要講得出**剩幾份**，讓店員當場就能改單，而不是「失敗，請重試」
4. 訂單取消時把數量**還回去**
5. **不設定數量的商品完全不受影響** —— 這是讓 S1 成為純加法、也是讓本規格可以安全分段的前提

### 1.4 為什麼現在排這一項

工作順序上 G24 之後的下一項是金流（G01–G04 其餘部分），**PO 已整批延後**（`GAP-ANALYSIS.md` 開頭的 2026-09-17 決策），所以工作順序裡沒有可開工的下一項，必須從 P2 升排一項。P2 其餘未開始項目逐項排除：

| 項目 | 為什麼不是現在 |
| --- | --- |
| G05 Session 集中化 | 要先引入 Redis / Spring Session。`AGENTS.md`「不得引入新框架或新依賴」的預設是不引入，而單店單機營運下它不是痛點 |
| G12 外送、硬體印單 | 要先選外部廠商與硬體型號，那不是設計決策，是採購決策 |
| G16 顧客自助註冊 | 涉及濫用防治與個資法遵，是產品與法遵決策，不在「設計決策授權」的範圍內 |
| G17 常用組合快捷 | 明文刻意延後：要先有真實訂單資料才知道哪些組合常用，現在做出來的一定是猜的 |
| G20 品項層折扣 / G21 會員價 | 會動到 `order_items` 快照結構與報表的品項營收歸屬，且買一送一「折的是哪一件」需要真實的促銷方案才定得出規則 |
| **G08 每日可售數量** | **沒有外部依賴、沒有法遵決策、不需要真實資料**。它要的只是一張表與一個扣減動作，而且 G13 已經把「台北日編碼」與「分店 × 商品」這兩個模式驗證過了 |

**誠實交代：** 和 G24 一樣，升排 G08 **不是**因為營運端回饋了超賣事故 —— 目前沒有收到任何營運端回饋。升排靠的是「其餘項目都被別的東西擋住，而這一項不是」。本規格不擋任何其他工作，若 PO 認為有更該做的，可以整份擱置。

---

## 2. 範圍

### 2.1 在範圍內

1. 新表 `branch_product_stock`：分店 × 商品 × 台北日 的「今日可售數量」與「剩餘數量」
2. 新表 `branch_product_stock_reservation`：**保留憑據**，記下「哪一筆訂單實際從哪一天的哪一列扣了多少」（§4.6，v1.1 新增）
3. 讀寫端點：`GET /api/menu/stock`、`POST /api/menu/stock`
4. 訂單成立時扣減、不足時擋下（含同商品多行的合併與行鎖順序）
5. 訂單轉為 `CANCELLED` 時**依保留憑據**回補（只逆轉這筆訂單真的扣過的量）
6. 菜單把「剩餘 0」顯示為今日售完；商品帶上 `remaining`
7. POS 前端的「今日可售數量」設定與剩餘數量顯示
8. 稽核紀錄：設定數量、因扣減而售完

### 2.2 不在範圍內（逐項有理由，見 §13 與 §14）

1. **永續庫存帳**（進貨、報廢、盤點、跨日結存、庫存成本）→ §13.1，登記為 **G28**
2. **選項層庫存**（例如燕麥奶賣完）→ §13.2，登記為 **G28**
3. **原料 BOM**（一杯拿鐵扣多少豆子、多少奶）→ §13.1，登記為 **G28**
4. **低庫存警示的推播或通知** → §13.7
5. **報表的庫存維度**（今日備量 vs 售出率）→ §13.8
6. **線上未付款訂單的逾時釋放** → §13.5，這一項是本規格**已知且刻意接受**的缺口，依賴延後中的 G04
7. **總部跨店設定數量** → §13.4
8. **「標記售完」機制的移除或合併** → §13.3，兩者刻意並存

---

## 3. 涉及模組與邊界

```
coffee-catalog   新增 branch_product_stock 與 reservation 的讀寫與扣減/回補   依賴不變（shared）
coffee-orders    create 扣減、transition 回補                 依賴不變（catalog.api, branches.api, shared）
coffee-app       V13 migration、測試                          組裝層
```

**邊界要求（`ModuleBoundariesTest` 會驗）：**

- `branch_product_stock` 與 `branch_product_stock_reservation` 是 `products` / `branch_products` 的同族資料，**兩張都歸 `coffee-catalog` 所有**。`coffee-orders` **不得**直接查這兩張表，一律經過 `Catalog` 介面
- `branch_product_stock_reservation` 存了 `order_id`，但那是一個**不透明字串**，`coffee-catalog` 不因此認識 `coffee-orders`：它不 import 任何 orders 的型別、不查 `orders` 表、**schema 上也刻意不建 FK**（理由見 §4.6）。依賴方向維持 `orders → catalog.api` 單向，`ModuleBoundariesTest` 不受影響
- 扣減與回補的方法加在 **`coffee-catalog` 的 `api` 介面 `Catalog`** 上（`Catalog.java`），`coffee-orders` 透過已有的 `catalog` 欄位呼叫。**不新增模組間依賴**
- `coffee-reporting` **不碰**這張表（§13.8）

---

## 4. DB schema 與 migration

### 4.1 migration 檔名

**`V13__branch_product_stock.sql`**

V12 由 G24（`docs/specs/G24-branch-manager-day-settings.md`，PR #54）預定占用。**不得改用 V12**，Flyway 預設不接受事後補插較小版號（out-of-order），空一個版號的成本是零。若 G24 最終被擱置而 V12 從未使用，**也不要回頭把 G08 改成 V12** —— 留一個空號永遠比改動已推送的版號安全。

### 4.2 Schema

```sql
CREATE TABLE branch_product_stock(
  branch_id  VARCHAR(36) NOT NULL REFERENCES branches(id),
  product_id VARCHAR(36) NOT NULL REFERENCES products(id),
  on_date    INTEGER NOT NULL,
  quantity   INTEGER NOT NULL CHECK(quantity  >= 0 AND quantity  <= 9999),
  remaining  INTEGER NOT NULL CHECK(remaining >= 0 AND remaining <= 9999),
  updated_at BIGINT NOT NULL,
  updated_by VARCHAR(36) NOT NULL REFERENCES accounts(id),
  PRIMARY KEY(branch_id,product_id,on_date),
  CHECK(remaining <= quantity)
);

CREATE INDEX idx_branch_product_stock_date ON branch_product_stock(branch_id,on_date);

CREATE TABLE branch_product_stock_reservation(
  order_id   VARCHAR(36) NOT NULL,
  product_id VARCHAR(36) NOT NULL REFERENCES products(id),
  branch_id  VARCHAR(36) NOT NULL REFERENCES branches(id),
  on_date    INTEGER NOT NULL,
  quantity   INTEGER NOT NULL CHECK(quantity > 0),
  created_at BIGINT NOT NULL,
  PRIMARY KEY(order_id,product_id)
);

CREATE INDEX idx_bps_reservation_row
  ON branch_product_stock_reservation(branch_id,product_id,on_date);
```

**兩張表都在同一支 `V13`。** 它們是同一個機制的兩半，分成兩支 migration 只會讓 S1 合併之後出現「有庫存表但沒有憑據表」的中間狀態。

**不新增任何權限列。** 本規格複用既有的 `MENU_AVAILABILITY`（§7.1），所以這支 migration 裡**沒有** `INSERT INTO role_permissions`。這與 `V4__branch_menu_availability.sql` 不同，是刻意的。

### 4.3 `on_date` 用 `INTEGER` 的 `yyyyMMdd` 台北日編碼，不用 `DATE`

沿用 `branch_products.sold_out_date`（`V4`）與 `branch_day_overrides.on_date`（G19 的 `V10`）已經走過的路。理由是 H2 與 PostgreSQL 的 `DATE` 時區行為會分岔，症狀是「測試全綠但差一天」—— 這一條在 G19 §4.1 已經有完整論述，本規格不重複，只要求**一致**。

產生這個值一律用 `CatalogService.today()`（`CatalogService.java:269`）。**不得**自己寫 `LocalDate.now()` 之類的無參數呼叫 —— G27 的 `TimeZoneGuardTest` 會讓 build 直接紅。

### 4.4 為什麼 `quantity` 與 `remaining` 兩欄都要

只存 `remaining` 的話，店員只看得到「剩 3」，看不到「今天備了 20、賣了 17」。只存 `quantity` + 另外去 `order_items` 加總的話，每次讀菜單都要掃訂單，而且取消的訂單要不要算、跨分店要不要算，每個查詢都要重新決定一次。

兩欄的不變式是 **`已售 = quantity - remaining`**，`CHECK(remaining <= quantity)` 由 DB 強制。中途調整備量時 `remaining` 以**差額**同步（§5.3），所以「已售」不會因為調整備量而被改寫。

### 4.5 為什麼上限是 9999

單行數量上限 50、單筆訂單上限 50 行（`OrderService.java:38,70`），所以一筆訂單最多扣 2500。9999 足以涵蓋任何一天的實際備量，同時讓 `INTEGER` 連溢位的邊都碰不到。即使如此，§5.4 仍要求用 `Math.subtractExact` / `Math.addExact`，理由是 `AGENTS.md`「溢位用 `Math.addExact`，不要裸算」是通則，不因為「這裡不會溢位」而例外。

### 4.6 為什麼需要 `branch_product_stock_reservation`（v1.1 新增，這一節是 v1.0 的缺陷修補）

v1.0 的回補只用「取消當下的台北日 + branchId + productId」去找當日列並加回數量，**沒有任何證據證明這筆訂單真的從那一列扣過**。Codex 在 PR #55 的 review 指出了這個缺口，它是對的。具體的超賣路徑：

| 時刻 | 動作 | `quantity` / `remaining` | 實際狀況 |
| --- | --- | --- | --- |
| 10:00 | 下單 2 份（當時**沒有**設定備量，不限量，**沒有扣減**） | 無列 | 已出 2 份 |
| 12:00 | 店員設定今日備量 5 | 5 / 5 | 可再出 5 份 |
| 12:30 | 另一筆訂單買 2 份（正常扣減） | 5 / **3** | 可再出 3 份 |
| 13:00 | 取消 10:00 那筆舊訂單 | 5 / **5** ← 錯 | **實際只剩 3 份** |

最後一列憑空生出 2 份可售額度，`已售 = quantity - remaining` 的不變式（§4.4）被破壞，而且**會真的超賣**。同一類錯誤還有第二條路徑：設定 5 → 賣 2（剩 3）→ 解除限量（刪列）→ 重新設定 5 → 取消第一輪的舊訂單，回補會落到**新一輪**的備量上。

兩條路徑的共同根因是「回補的對象靠推論，不靠紀錄」。修法是讓扣減留下可持久化、可冪等對應的憑據：

- `reserveStock` **真的扣到**某一列時，為 `(order_id, product_id)` 寫一列憑據，記下 `branch_id`、`on_date` 與**實際扣減的數量**
- 不限量（找不到當日列）時**什麼都不扣，也就不寫憑據** —— 所以上表 10:00 那筆訂單沒有憑據，13:00 取消時找不到東西可以回補，`remaining` 維持 3。第一條路徑消失
- `releaseStock` **只依憑據回補**，回補完立刻刪掉憑據。沒有憑據就是沒扣過，不回補
- `PRIMARY KEY(order_id, product_id)` 讓「同一筆訂單扣兩次」由 DB 擋下，而不是靠呼叫端自律（§5.4 的冪等重放因此有了第二道防線）
- `setStock` 在**從無到有建立限量**時，刪掉該 `(branch_id, product_id, on_date)` 的所有未結憑據（§5.3 第 6 步），第二條路徑消失

**為什麼 `order_id` 不建 FK 到 `orders(id)`：** 兩個理由，缺一不可。其一，§5.4 要求 `reserveStock` 在 `insert into orders` **之前**呼叫（擋下來的訂單不該留下任何痕跡），那一刻 `orders` 列還不存在，有 FK 會直接違反約束。其二，`coffee-catalog` 的表指向 `coffee-orders` 的表會讓依賴方向在 schema 層反過來。代價是 DB 不幫你擋「憑據指向不存在的訂單」—— 可接受，因為憑據永遠由 `reserveStock` 在同一個交易內寫出（訂單沒建成就一起回滾），而且本系統不刪 `orders` 列。

**為什麼不改用「在庫存列上放版號」：** 那需要讓庫存列在解除限量後**不被刪除**（版號才留得住），於是 `quantity` / `remaining` 要改成 nullable，`CHECK(remaining <= quantity)` 與 §6.1 的 `null` 語意都要跟著重寫，而且訂單那一端仍然要有地方存「我扣的是第幾版」—— 一樣要一張表或一組新欄位。憑據表的成本更低，而且順便解決了冪等與雙重回補。

---

## 5. 技術設計

### 5.1 `Catalog` 介面的變更（全部是加法）

在 `coffee-catalog/src/main/java/com/coffee/catalog/api/Catalog.java` 加入：

```java
record ProductStock(
    String branchId,
    String productId,
    String productName,
    int onDate,
    Integer quantity,   // null = 今日不限量
    Integer remaining,  // null = 今日不限量
    Long updatedAt,
    String updatedBy) {}

record StockLine(String productId, int quantity) {}

/** 今日全部商品的可售數量（沒有設定的商品 quantity/remaining 皆為 null）。 */
List<ProductStock> stock(Actor a, String branchId);

/** quantity 為 null 時解除限量（刪除當日列）。 */
ProductStock setStock(Actor a, String branchId, String productId, Integer quantity);

/** 訂單成立時扣減，並為實際扣到的每個商品寫下保留憑據。數量不足直接 throw Problem。呼叫端必須已在交易內。 */
void reserveStock(String branchId, String orderId, List<StockLine> lines);

/** 訂單取消時依保留憑據回補，並刪除憑據。沒有憑據就什麼都不做。呼叫端必須已在交易內。 */
void releaseStock(String branchId, String orderId);
```

> **`releaseStock` 不再收 `lines`（v1.1 的簽名變更）。** 要回補多少、回補到哪一天，完全由憑據決定，不由呼叫端重算 —— 呼叫端重算就是 §4.6 那個缺陷的來源。`branchId` 保留下來只當**防禦性條件**（憑據的 `branch_id` 必須相符才回補），讓「訂單編號張冠李戴」不會跨分店改到別人的備量。

`Product` record **加一個欄位**：

```java
record Product(
    String id, String name, String subtitle, String category,
    int price, int cost, String image, String badge,
    boolean active, String availability,
    Integer remaining,                    // ← 新增，null = 不限量
    List<OptionGroup> optionGroups) {}
```

> **這是本規格唯一的破壞性變更。** `Product` 加欄位會讓**所有**建構呼叫編譯失敗（`CatalogService` 內多處、測試多處）。這是機械性修改：在 `availability` 與 `optionGroups` 之間補一個值即可。**不得順手改動任何既有測試的斷言**，只補參數。
>
> 為什麼不用多載或另開 record 來避開（對照 G25 §9.3 選了多載）：那裡要避的是 29 處**斷言**的改寫，這裡要改的是**建構呼叫的參數列**，補一個 `null` 就結束，沒有斷言需要重寫。為一個欄位另立 `ProductWithStock` 會讓前端拿到兩種商品型別，成本比補參數高。

### 5.2 `reserveStock` 的演算法（這一節是本規格的核心，請逐步照做）

```
reserveStock(branchId, orderId, lines):
  1. 把 lines 依 productId 合併加總          ← 同一商品不同選項會出現多行
  2. 把合併結果依 productId 字典序排序        ← 固定鎖順序，避免死鎖
  3. today = today()
  4. for each (productId, qty) in 排序後的結果:
       a. row = select quantity,remaining from branch_product_stock
                where branch_id=? and product_id=? and on_date=? for update
       b. 若 row 不存在 → continue            ← 不限量，什麼都不做
       c. 若 row.remaining == 0 → throw new Problem(400, "本店今日已售完此商品，請調整餐點")
       d. 若 row.remaining < qty → throw new Problem(400,
              "本店今日此商品僅剩 " + row.remaining + " 份，請調整數量")
       e. update branch_product_stock set remaining=?,updated_at=?
            where branch_id=? and product_id=? and on_date=?
          新值為 Math.subtractExact(row.remaining, qty)
       f. insert into branch_product_stock_reservation
            (order_id,product_id,branch_id,on_date,quantity,created_at)
            values(?,?,?,?,?,?)          ← 保留憑據，qty 是「實際扣掉的量」
```

**第 4f 步（保留憑據）是 v1.1 新增的，不能省。** 省掉它就回到 §4.6 那個會超賣的設計。三個要點：

1. **只有真的扣到才寫。** 第 4b 步 `continue`（不限量）的商品**不寫憑據** —— 這正是 §4.6 第一條路徑的解法
2. **寫的是合併後的量**（第 1 步的結果），所以一筆訂單對一個商品只會有一列憑據，與 `PRIMARY KEY(order_id, product_id)` 一致
3. **`on_date` 寫 `today`**，也就是實際扣減的那一天。回補時以憑據的 `on_date` 為準，不重算「今天」（§5.5）

**第 1 步為什麼必要：** `OrderService.create` 的 `q.items()` 是「商品 + 選項」的行，同一個商品點了兩種甜度會是兩行。若不合併就逐行扣，剩 1 份時「冰的 1 杯 + 熱的 1 杯」會在第一行通過、第二行才失敗，錯誤訊息變成「僅剩 0 份」而不是「僅剩 1 份」，店員看不懂。合併後一次判斷，訊息才對得上事實。

**第 2 步為什麼必要：** 兩筆訂單各含商品 A 與 B、取鎖順序相反時會死鎖。字典序排序把它降為零成本。這與 `OrderService` 既有的 `lock(id)`（`OrderService.java:582`）是同一類手法。

**自動售完不寫稽核。** `reserveStock` 的簽名**不帶 `Actor`**（理由見 §13.6），所以它記不出「誰」造成售完。因此扣到 0 的時候**不寫稽核**，改由 `OrderService.create` 既有的訂單紀錄承擔 —— 訂單本身就是「誰在什麼時候買掉最後一份」的完整紀錄，再寫一筆 `STOCK_EXHAUSTED` 只是重複。**不要為「扣到 0」加任何 `audit.record`。**（寫在這裡是因為第一直覺會想加，說明為什麼不加比較省一輪 review。v1.0 把這一條編為第 4f 步再叫你刪掉，v1.1 把編號讓給真正要實作的憑據寫入，避免誤讀。）

### 5.3 `setStock` 的差額同步

```
setStock(actor, branchId, productId, quantity):
  1. actor.require("MENU_AVAILABILITY"); actor.branch(branchId);
  2. 商品必須存在且 active=true，否則 404「找不到商品」
  3. quantity == null →
       delete from branch_product_stock where branch_id=? and product_id=? and on_date=?
       audit "STOCK_SET"，summary「解除 <商品名> 的今日限量」
       回傳 quantity=null, remaining=null
  4. Problem.check(quantity >= 0 && quantity <= 9999, "可售數量需為 0–9999")
  5. row = select ... for update（同 §5.2 的 a）
  6. row 不存在 → delete from branch_product_stock_reservation
                    where branch_id=? and product_id=? and on_date=?     ← v1.1 新增
                  insert，quantity=q, remaining=q
  7. row 存在   → sold = row.quantity - row.remaining
                  newRemaining = Math.max(0, Math.subtractExact(q, sold))
                  update quantity=q, remaining=newRemaining
                  （**不動憑據** —— 這些憑據綁的就是這一列，差額同步已經保住「已售」）
  8. audit "STOCK_SET"，summary「<商品名> 今日可售 <q> 份，剩餘 <newRemaining> 份」
```

**第 6 步為什麼要刪未結憑據（v1.1 新增）：** 「從無到有建立限量」的意思是店員在宣告「從現在起這個商品今天只出 N 份」。在那之前成立的訂單對這一輪備量沒有任何請求權，它們的憑據如果留著，取消時就會回補到新一輪的數字上 —— 這正是 §4.6 的第二條路徑（設定 5 → 賣 2 → 解除限量 → 重新設定 5 → 取消第一輪的訂單）。

**刪除範圍嚴格限定在 `(branch_id, product_id, on_date)` 三者都相同的憑據。** 不要少掉任何一個條件 —— 少了 `on_date` 會清掉其他日期的憑據，少了 `product_id` 會清掉同分店其他商品的。

**第 7 步刻意不刪憑據**，兩者的差別要講清楚：row 還在，代表這一輪限量從頭到尾沒有中斷，既有憑據扣的就是這一列，第 7 步的差額同步已經保證「已售」不被改寫（見下一段），所以憑據必須留著，取消時才回補得了。**把第 6、7 步寫成同一段邏輯是本節最容易犯的錯。**

**第 7 步的 `Math.max(0, ...)` 是刻意的：** 已賣 17 份時把備量調成 10，`newRemaining` 算出 -7，夾成 0。意思是「不再出餐」，**不會**回頭取消那 17 筆已成立的訂單。此時 `已售(17) > quantity(10)`，但 DB 的 `CHECK(remaining <= quantity)` 看的是 `0 <= 10`，仍然成立。這一條要寫進驗收（§10 第 6 條）。

### 5.4 `OrderService.create` 的接入點

`OrderService.java:36` 的 `create` 內，**在既有的 `for (LineInput l : q.items())` 迴圈結束之後、`db.update("insert into orders...")` 之前**插入：

```java
catalog.reserveStock(
    q.branchId(),
    id, // ← OrderService.java:67 的 String id = Ids.order()，在 insert 之前就已產生
    q.items().stream().map(l -> new Catalog.StockLine(l.productId(), l.quantity())).toList());
```

**`id` 從哪裡來：** `OrderService.java:67` 的 `String id = Ids.order();` 就在金額迴圈之前，所以接入點拿得到它，**不需要把 `Ids.order()` 往前搬，也不需要改 `insert into orders` 的參數**。這也是 `branch_product_stock_reservation.order_id` 不能有 FK 的直接原因（§4.6）：這一刻 `orders` 列還不存在。

**為什麼放在迴圈之後而不是迴圈內：** 迴圈內已經在逐行呼叫 `catalog.sellable`（`OrderService.java:73`），看起來順手就能扣。但扣減必須在**合併與排序之後**（§5.2 第 1、2 步），迴圈內做不到。而且迴圈內 throw 會讓部分商品已扣、部分未扣 —— 雖然 `@Transactional` 會回滾，但依賴回滾來維持正確性，比一開始就不製造中間狀態脆弱。

**為什麼放在 `insert into orders` 之前：** 擋下來的訂單不該留下任何痕跡。放在之後雖然也會回滾，但主鍵與冪等鍵已經消耗過，`PENDING_PAYMENT` 的那一瞬間也可能被其他交易讀到。

**冪等重放不扣第二次：** `create` 開頭的既有冪等檢查（`OrderService.java:51-58`）在發現 `idempotency_key` 已存在時直接 `return get(...)`，**根本走不到** `reserveStock`。這是既有行為，不需要新程式碼，但**要寫測試證明它**（§11.1 第 7 條）—— 這一條錯掉的代價是「顧客重試一次就扣兩份庫存」，是本規格最貴的潛在 bug。

v1.1 多了一道**資料庫層**的防線：憑據表的 `PRIMARY KEY(order_id, product_id)` 會讓同一個 `orderId` 的第二次 `reserveStock` 直接違反主鍵而拋出，整筆交易回滾。這不是用來取代上面那個檢查的（走到主鍵衝突已經代表邏輯有問題），而是讓「扣兩次」從**靜默的資料錯誤**變成**吵鬧的失敗**。

### 5.5 `OrderService.transition` 的回補

`OrderService.java:471` 的 `transition`，在既有的 `db.update("update orders set status=? where id=?", next, id)` **之後**插入：

```java
if ("CANCELLED".equals(next)) {
  catalog.releaseStock(o.branchId(), id);
}
```

`o` 是 `transition` 已經取好的 `snapshot(id)`，而且 `lock(id)` 已經先取了訂單的行鎖，所以不會有同一筆訂單被回補兩次的情形（第二次會在 `Problem.check(allowed, ...)` 被擋下，因為 `CANCELLED` 不在允許的轉換表裡）。

> **呼叫端不再傳 `o.items()`（v1.1 的變更）。** 回補多少、回補到哪一天由憑據決定。傳 `o.items()` 的版本有兩個獨立的錯誤來源：訂單品項是**商品 + 選項**的行（同商品多行要合併，與 §5.2 第 1 步同一個坑），而且它完全看不出這筆訂單當初到底扣沒扣、扣的是哪一天。

`releaseStock(branchId, orderId)` 的演算法：

```
releaseStock(branchId, orderId):
  1. rs = select product_id,on_date,quantity
            from branch_product_stock_reservation
            where order_id=? and branch_id=?
            order by product_id                  ← 固定鎖順序，同 §5.2 第 2 步
  2. rs 為空 → 直接 return（這筆訂單沒扣過任何備量，不限量或憑據已被 §5.3 第 6 步清掉）
  3. for each r in rs:
       a. row = select quantity,remaining from branch_product_stock
                where branch_id=? and product_id=r.product_id and on_date=r.on_date
                for update                        ← 用憑據的 on_date，不是 today()
       b. 若 row 存在:
            newRemaining = Math.min(row.quantity, Math.addExact(row.remaining, r.quantity))
            update remaining=newRemaining, updated_at=?
          若 row 不存在 → 不回補（限量已被解除，沒有帳可以記）
       c. delete from branch_product_stock_reservation
            where order_id=? and product_id=r.product_id
```

四個要點：

1. **第 3a 步用 `r.on_date`，不得呼叫 `today()`。** 這是跨日取消的正解（§13.9 已依此改寫）：昨天的訂單取消時回補的是**昨天那一列**，不會給今天多出額度
2. **第 3b 步的 `Math.min` 夾在 `quantity` 以內。** 備量被調低之後取消訂單，回補不能讓 `remaining` 超過現在的 `quantity`，否則違反 `CHECK(remaining <= quantity)`（驗收 14）
3. **第 3c 步的刪除讓回補天然冪等。** 第二次呼叫 `releaseStock` 時 `rs` 是空的，直接 return。訂單狀態機已經擋住重複取消，這一條是它的後備
4. **`row` 不存在不是錯誤**，直接跳過並刪憑據。限量被解除（`quantity=null`）之後那一天就沒有在記帳了，硬是把列建回來等於替店員決定「今天其實有限量」

### 5.6 `sellable` 與 `list` 的剩餘數量（S3）

**`sellable`（`CatalogService.java:115`）** 在既有的 `branch_products` 判斷之後，加一段查詢當日 `branch_product_stock`：

- 找不到列 → `remaining = null`，行為完全不變
- `remaining == 0` → `throw new Problem(400, "本店今日已售完此商品，請調整餐點")`，**與既有的手動售完同一句訊息**（理由見 §13.3）
- 其他 → 回傳的 `Product` 帶上 `remaining`

**`list`（`CatalogService.java:80`）** 的顧客／POS 分支（`manage=false`）在既有的 `effective_availability` CASE 之外 `left join branch_product_stock`，規則：

| `branch_products` | 當日 `remaining` | `effective_availability` | `remaining` |
| --- | --- | --- | --- |
| `UNLISTED` | 任意 | **整列不回傳** —— 既有 `WHERE` 已把它排除（見下方「v1.2 修正」） | 不適用 |
| `SOLD_OUT` 且 `sold_out_date=今天` | 任意 | `SOLD_OUT` | 照實回傳 |
| 其他 | 無列 | `AVAILABLE` | `null` |
| 其他 | `0` | **`SOLD_OUT`** | `0` |
| 其他 | `> 0` | `AVAILABLE` | 照實回傳 |

`manage=true` 分支（總部的商品維護）**不接** `branchId`，所以 `remaining` 一律 `null`。這不是疏漏：那個畫面管的是跨店的商品主檔，顯示某一店的剩餘數量沒有意義。

> **注意順序：** 「`remaining == 0` ⇒ `SOLD_OUT`」放在 `UNLISTED` **之後**判斷。`UNLISTED` 代表「本店不供應」，它比「今天賣完」更強，不能被覆蓋。

> **v1.2 修正 —— `UNLISTED` 那一列在顧客／POS 菜單上根本不會出現。**
> v1.1 的上表（與當時的驗收 16）照抄了 `effective_availability` 的 `CASE`，卻沒有一併讀 `list` 既有的 `WHERE` 子句：
>
> ```sql
> where p.active=true and (b.availability is null or b.availability<>'UNLISTED')
> ```
>
> 這一段把 `UNLISTED` 的商品**整列排除**，所以 `CASE` 裡的 `'UNLISTED'` 分支從來不會被取用 —— 它在本規格之前就已經是不可達的程式碼。`CoffeeIntegrationTest` 明確斷言顧客菜單不含 `UNLISTED` 商品，而本規格 §5.6 又禁止放寬既有斷言，因此 v1.1 的驗收 16（要求回傳 `UNLISTED` 商品並帶 `availability='UNLISTED'`）**在不破壞既有契約的前提下不可能成立**。
>
> **決定：維持既有的隱藏契約，不讓 `UNLISTED` 商品出現在顧客／POS 菜單。** 隱藏比回傳更安全：商品不在回應裡，就不可能洩漏 `remaining`。`sellable` 仍以 `本店未供應此商品` 擋下單一商品的直接查詢（`CatalogService.sellable` 既有行為，未改）。驗收 16 已依此改寫。
>
> Codex 在 [PR #59](https://github.com/choka1227/coffee_GPT6/pull/59) 依 `AGENTS.md`「先說出來，但講完就繼續做」處理：保留隱藏行為、不動既有斷言、在 PR 描述與 `docs/reports/G08-branch-product-stock.md` 明記偏離。那是對的做法，缺陷在規格端。
>
> **推翻它的代價：** 要讓顧客菜單回傳 `UNLISTED` 商品，就得改寫 `CoffeeIntegrationTest` 的既有斷言、決定前端該怎麼渲染一個「本店不供應」的商品卡（目前前端沒有這個狀態的樣式），並確認 `remaining` 在那條路徑上被遮成 `null`。換到的只有「顧客看得到本店不供應的品項」—— 那是體驗上的退步，不是進步。

### 5.7 前端（S4）

**`shared/types.ts`**

```ts
export interface Product {
  // ...既有欄位不動
  availability: "AVAILABLE" | "SOLD_OUT";
  remaining: number | null;      // ← 新增
  optionGroups: OptionGroup[];
}

export interface ProductStock {
  branchId: string;
  productId: string;
  productName: string;
  onDate: number;
  quantity: number | null;
  remaining: number | null;
  updatedAt: number | null;
  updatedBy: string | null;
}
```

**`modules/ordering/MenuView.vue`** —— 設定與顯示都放在 POS 菜單，與既有的「標記售完」按鈕（`MenuView.vue:569`）同一處：

1. 商品卡在「今日售完」徽章（`MenuView.vue:552`）旁，`availability !== "SOLD_OUT" && remaining !== null && remaining > 0` 時顯示 `剩 {{ remaining }} 份`。**手動標記售完時不顯示剩餘徽章**（兩個徽章不並存，v1.2 新增，理由見 §13.13）
2. 店員模式的按鈕列（`MenuView.vue:569` 那一組）增加「設定備量」，開一個只有一個數字輸入與「解除限量」的小表單，送 `POST /api/menu/stock`
3. 成功後重新載入菜單（沿用既有的 `load()`），**不要**在前端自己算 `remaining`

**金額無關：** 本規格不碰任何金額欄位，前端不需要動 `shared/format.ts` 或 `checkout.ts`。

---

## 6. API

### 6.1 `GET /api/menu/stock`

| 項目 | 內容 |
| --- | --- |
| Query | `branchId`（必填） |
| 權限 | `MENU_AVAILABILITY` + `actor.branch(branchId)` |
| 200 | `ProductStock[]`，**所有 active 商品都有一列**，沒設定的 `quantity`/`remaining` 為 `null`，依 `products.sort_order, products.name` 排序 |

回傳全部商品而不是只回傳有設定的那些，是為了讓前端的設定畫面不必再打一次 `/api/menu`。這與既有的 `GET /api/menu/availability`（`CatalogController.java:33`）一致。

### 6.2 `POST /api/menu/stock`

| 項目 | 內容 |
| --- | --- |
| Body | `{ "branchId": "...", "productId": "...", "quantity": 12 }`，`quantity` 可為 `null` |
| 權限 | `MENU_AVAILABILITY` + `actor.branch(branchId)` |
| 200 | 單一 `ProductStock` |
| CSRF | **需要**（寫入請求，無例外） |

Controller 的 record 沿用既有風格放在 `CatalogController` 內部：

```java
record StockInput(String branchId, String productId, Integer quantity) {}
```

### 6.3 錯誤碼

| 狀況 | 碼 | 訊息 |
| --- | --- | --- |
| `quantity` 超出 0–9999 | 400 | `可售數量需為 0–9999` |
| 商品不存在或已下架 | 404 | `找不到商品` |
| 未登入 | 401 | （既有機制） |
| 無 `MENU_AVAILABILITY` | 403 | （既有機制，`actor.require`） |
| 設定他店的數量 | 403 | （既有機制，`actor.branch`） |
| 下單時剩餘為 0 | 400 | `本店今日已售完此商品，請調整餐點` |
| 下單時剩餘不足 | 400 | `本店今日此商品僅剩 N 份，請調整數量` |

**為什麼「剩餘不足」是 400 而不是 409：** `AGENTS.md` 的狀態碼表把 409 定為「衝突」，看起來更貼切。但既有的手動售完走的是 `sellable` 的 `throw new Problem(400, ...)`，前端（`MenuView.vue` 的 `send` 錯誤處理）對兩者沒有分流。讓同一件事（「這個不能賣」）在手動與自動兩條路上回不同的碼，前端就必須處理兩種分支，而兩種分支要做的事一模一樣。**一致性在這裡比語意精確更值錢。** 推翻它的代價：改成 409 要同步改 `sellable` 的既有 400，那會動到 `BranchMenuAvailabilityTest` 的既有斷言，是本規格刻意避開的範圍。

---

## 7. 權限與資料範圍

### 7.1 複用 `MENU_AVAILABILITY`，不新增權限常數

設定「今天這項備 12 份」與按「標記今日售完」是**同一個營運動作的兩種精度**，同一批人在同一個畫面上做。所以：

- 權限：`actor.require("MENU_AVAILABILITY")`
- 資料範圍：`actor.branch(branchId)` —— `BRANCH` 級，店只能設自己的
- `Identity.PERMISSIONS`（`Identity.java:7-21`）**不動**
- `role_permissions` **不新增列** —— `V4` 已經把 `MENU_AVAILABILITY` 給了 `MANAGER` / `CASHIER` / `HQ`

**附帶好處：** G24（PR #54）要往 `Identity.PERMISSIONS` 加 `BRANCH_HOURS_OVERRIDE`。本規格不碰那個檔案，所以兩份規格在 `Identity.java` 上的檔案交集是**零**，可以任意順序合併。

**推翻它的代價：** 若日後總部要集中管備量、而把「標記售完」留給門市，就需要一個新的 `STOCK_MANAGE` 權限 + 一支 migration 補 `role_permissions`。那是純加法，成本低。現在先開一個權限換不到任何東西，只換到一支要維護的 migration 與一組要寫的角色驗證測試。

### 7.2 `reserveStock` / `releaseStock` 不做授權檢查

兩者是 `create` 與 `transition` 交易內部的機械步驟，授權在外層已經做完（`create` 要 `ORDER_CREATE` + `POS_ORDER` + `a.branch()` 或顧客身分；`transition` 要 `manage(a, o)` 或訂單屬於自己）。再檢查一次會變成「用顧客的 Actor 去要求 `MENU_AVAILABILITY`」，顧客當然沒有，顧客就無法下單。

這一條**必須在 code review 時特別看**：它是本規格裡唯一「刻意不檢查權限」的地方，而 `CLAUDE.md` 的固定檢查項第 6 條正是在找這種東西。判準是「這個方法能不能被外部請求直接觸達」—— 不能，它沒有 Controller 入口，只在同模組交易內被呼叫。

### 7.3 顧客端

顧客看得到 `remaining`（它隨 `GET /api/menu` 回傳）。這是刻意的：知道「只剩 2 份」對顧客有用，而且它不是敏感資訊 —— 競爭對手走進店裡看一眼櫃台也能知道。**成本欄位仍然要遮**（`CatalogService.withProductOptions` 既有的 `manage ? product.cost() : 0`），本規格不得放寬它。

---

## 8. 金額規則

**本規格不涉及任何金額計算。** `reserveStock` 只讀寫數量，不讀價格、不寫 `orders.total`。

但有兩條既有規則要在 review 時確認沒被破壞：

1. `OrderService.create` 的金額仍然**完全由後端依有效菜單重算**（`OrderService.java:71-85`）。`reserveStock` 插在金額算完之後，不得改動任何 `unitPrice` / `total` 的運算
2. 訂單成立後改備量**不回寫歷史**。`order_items` 的快照（名稱、分類、單價、成本、選項）與庫存完全無關，不得因為本規格而增加任何庫存欄位到 `order_items`

---

## 9. 施工階段

四個階段。每階段獨立 CI 綠、獨立可合併、有自己的驗收子集。

### S1 — 資料層與讀寫端點（規模：中）

| 動到的檔案 | 內容 |
| --- | --- |
| `V13__branch_product_stock.sql` | 新增，**兩張表**：`branch_product_stock` 與 `branch_product_stock_reservation`（§4.2） |
| `Catalog.java` | `ProductStock`、`StockLine` record；`stock`、`setStock` 方法 |
| `CatalogService.java` | `stock`、`setStock` 實作，含 §5.3 第 6 步的「刪未結憑據」 |
| `CatalogController.java` | `StockInput` record；`GET`/`POST /api/menu/stock` |
| `BranchProductStockMigrationTest.java` | 新增（對照 `BranchMenuAvailabilityMigrationTest`） |
| `BranchProductStockAdminTest.java` | 新增 |

**本階段零行為變更：** 沒有任何既有程式路徑會讀 `branch_product_stock`。設定了數量也不會影響下單 —— 這是刻意的，不是缺陷。**本階段不得動前端**，所以沒有人會透過 UI 設出一個「看起來設了卻沒用」的數量。

**憑據表在 S1 就建好，而且 `setStock` 的 `DELETE`（§5.3 第 6 步）也在 S1 就寫進去。** 本階段還沒有人寫憑據，所以那個 `DELETE` 一定刪到零列 —— 那是刻意的：把它留到 S2 會讓 S2 同時要加「寫憑據」「讀憑據」「清憑據」三件事，而 S2 已經是四個階段裡最大的一個。S1 寫它的成本是一行 SQL，驗收 14a 在 S2 才會真的驗到它。

**驗收子集：** §10 的第 1–7 條。

### S2 — 扣減、不足擋下與取消回補（規模：大，四個裡最大的）

| 動到的檔案 | 內容 |
| --- | --- |
| `Catalog.java` | `reserveStock(branchId, orderId, lines)`、`releaseStock(branchId, orderId)` |
| `CatalogService.java` | 兩者的實作（§5.2、§5.5），含憑據的寫入、讀取與刪除 |
| `OrderService.java` | `create` 的接入（§5.4，傳入 `id`）、`transition` 的回補（§5.5，只傳 `id`） |
| `BranchProductStockOrderingTest.java` | 新增，含併發測試與憑據的四條反向驗收（14a–14d） |

**扣減與回補必須在同一階段。** 只上扣減的話，取消的訂單會永久吃掉備量 —— 那不是「尚未實作的功能」，是一個會讓店員每天手動補數字的缺陷。`AGENTS.md` 要求每階段「單獨合進主線不會破壞任何既有行為」，扣減而不回補破壞的是「取消訂單等於這筆沒發生」這個既有語意。

**本階段是四個裡最大的一個**，若一次執行跑不完，切分點在：**先完成 `reserveStock`（含寫憑據）+ `create` + 不足測試並 push（draft 保留），再做 `releaseStock` + `transition`。** 中間狀態可編譯、測試綠，但 **PR 必須維持 draft**，理由同上。

**憑據的寫入必須跟 `reserveStock` 同一刀。** 切成「先扣減、憑據之後再補」會讓中間狀態扣得出去卻回補不回來，那是 §4.6 的缺陷又被做出來一次。憑據是扣減的一部分，不是後續增強。

**驗收子集：** §10 的第 8–14 條與 14a–14d。

### S3 — 菜單顯示（規模：中小）

| 動到的檔案 | 內容 |
| --- | --- |
| `Catalog.java` | `Product` 加 `remaining` 欄位 |
| `CatalogService.java` | `sellable`、`list` 的剩餘判斷（§5.6）；所有 `new Product(...)` 補參數 |
| 既有測試多處 | **只補 `new Product(...)` 的參數，不改斷言** |
| `BranchProductStockOrderingTest.java` | 補顯示相關斷言 |

**S2 與 S3 的順序不可交換。** S3 的「`remaining == 0` ⇒ 顯示售完」只有在 S2 真的會把 `remaining` 扣到 0 之後才有意義；反過來先做 S3，菜單會顯示一個永遠不會變的數字。

**驗收子集：** §10 的第 15–18 條。

### S4 — 前端設定與顯示（規模：中）

| 動到的檔案 | 內容 |
| --- | --- |
| `shared/types.ts` | `Product.remaining`、`ProductStock` |
| `modules/ordering/MenuView.vue` | 剩餘徽章、設定備量表單 |
| `MenuView.dom.test.ts` | 補可見性斷言 |

**驗收子集：** §10 的第 19–21 條。

### 階段切分的理由

- **S1 是純加法**，所以它可以先合併，把 migration 的版號風險（V13 與其他 PR 撞號）提早結清
- **S2 與 S3 都改 `CatalogService.java`**，但改不同方法（`reserveStock`/`releaseStock` vs `sellable`/`list`）。先合併的那一支不會逼另一支重寫，後做的只要先 merge 主線
- **S3 的 `Product` 加欄位是唯一的破壞性變更**，刻意排在第三個：前兩階段合併之前沒有人需要那個欄位，而把它留到最後就要讓 S4 等更久
- **S4 只動 `frontend/`**，與後端三階段的檔案交集是零

---

## 10. 驗收條件

逐條可勾選。括號裡是負責的階段。

**資料層與設定（S1）**

- [ ] 1. `V13__branch_product_stock.sql` 存在，表名、欄位、主鍵、兩個 `CHECK` 與索引與 §4.2 完全一致（S1）
- [ ] 2. 這支 migration **沒有** `INSERT INTO role_permissions`（S1）
- [ ] 3. `GET /api/menu/stock?branchId=taipei` 回傳**所有** active 商品，未設定的 `quantity` 與 `remaining` 皆為 `null`（S1）
- [ ] 4. `POST /api/menu/stock` 帶 `quantity=12` 後再 `GET`，該商品 `quantity=12`、`remaining=12`（S1）
- [ ] 5. `POST` 帶 `quantity=null` 會刪除當日列，`GET` 回 `null`/`null`（S1）
- [ ] 6. 已售 2 份後把 `quantity` 從 5 調成 1，`remaining` 變成 **0**（不是 -1，不是 1），且**沒有**任何訂單被改動（S1 + S2；S1 階段用直接寫入 `remaining` 的方式驗算式，S2 之後用真實訂單重驗）
- [ ] 7. `quantity` 給 `-1` 或 `10000` 回 400「可售數量需為 0–9999」；給不存在的 `productId` 回 404「找不到商品」（S1）

**扣減與回補（S2）**

- [ ] 8. 備量 3、下單 2 份 → 訂單成立，`remaining` 變 1（S2）
- [ ] 9. 備量 3、下單 5 份 → 400「本店今日此商品僅剩 3 份，請調整數量」，且**訂單沒有成立**（`orders` 表沒有新列）（S2）
- [ ] 10. 備量 0 → 下單 1 份回 400「本店今日已售完此商品，請調整餐點」（S2）
- [ ] 11. 同一訂單含同商品兩行（不同選項）各 2 份、備量 3 → 回 400「僅剩 3 份」（**不是**「僅剩 1 份」），訂單沒有成立（S2）
- [ ] 12. 同一個 `Idempotency-Key` 重送兩次 → 只扣一次，第二次回傳同一筆訂單（S2）
- [ ] 13. 備量 3、下單 2 份後取消該訂單 → `remaining` 回到 3（S2）
- [ ] 14. 備量 3、下單 2 份、把 `quantity` 調成 2、再取消訂單 → `remaining` 為 **2**（夾在 `quantity` 以內，不是 3）（S2）

**保留憑據的反向驗收（S2，v1.1 新增，全部是「不應該發生什麼」）**

這四條是 §4.6 那個超賣缺陷的回歸防線。**編號刻意用字母**，讓 v1.0 的 1–24 編號保持不變。

- [ ] 14a. **不限量時成立的訂單不得回補。** 不設備量 → 下單 2 份（成立，無憑據）→ 設定備量 5 → 另一筆訂單買 2 份（`remaining` 變 3）→ 取消最早那筆不限量訂單 → `remaining` **仍是 3**（不是 5）。同時斷言 `branch_product_stock_reservation` 裡沒有那筆訂單的列
- [ ] 14b. **解除後重建限量，舊訂單不得回補到新一輪。** 設定備量 5 → 下單 2 份（`remaining` 3）→ `quantity=null` 解除限量 → 重新設定備量 5（`remaining` 5）→ 取消第一輪那筆訂單 → `remaining` **仍是 5**，且那筆訂單的憑據已在重新設定時被刪除（§5.3 第 6 步）
- [ ] 14c. **回補落在憑據的日期，不是「今天」。** 直接寫入一列 `on_date` 為昨天的備量與一筆對應的憑據（測試手法見 §11.1 第 20 條），再取消該訂單 → **昨天那一列**的 `remaining` 增加，**今天那一列（若存在）完全不變**
- [ ] 14d. **同一筆訂單取消兩次只回補一次。** 備量 3、下單 2 份、取消（`remaining` 回到 3）→ 再次對同一筆訂單呼叫回補路徑 → `remaining` **仍是 3**（憑據已刪，第二次是 no-op）

**菜單顯示（S3）**

- [ ] 15. `remaining` 為 0 時，`GET /api/menu?branchId=X` 該商品的 `availability` 為 `SOLD_OUT`、`remaining` 為 `0`（S3）
- [ ] 16. **（v1.2 改寫，原條件不可能成立，推導見 §5.6 的「v1.2 修正」）** 同時是 `UNLISTED` 且 `remaining=0` 時，該商品**不出現在** `GET /api/menu?branchId=X` 的回應中（既有隱藏契約，`CoffeeIntegrationTest` 已鎖定），因此也不洩漏 `remaining`；`sellable("X", 該商品)` 仍以 400「本店未供應此商品」擋下（S3）
- [ ] 17. 沒有設定備量的商品 `remaining` 為 `null`，`availability` 與本規格之前**完全相同**（S3）
- [ ] 18. `GET /api/menu?manage=true` 的 `remaining` 一律 `null`（S3）

**前端（S4）**

- [ ] 19. POS 菜單在 `remaining > 0` 時顯示「剩 N 份」，`remaining` 為 `null` 時**不顯示**該徽章（S4）
- [ ] 19a. **（v1.2 新增）** 手動標記售完且當日 `remaining > 0` 時，商品卡**只顯示「今日售完」，不顯示「剩 N 份」**（兩個徽章不並存，理由見 §13.13）（S4）
- [ ] 20. 店員可在 POS 菜單設定與解除某商品的今日備量，成功後畫面上的剩餘數字更新（S4）
- [ ] 21. 顧客模式看不到「設定備量」按鈕（S4）

**全階段**

- [ ] 22. `./mvnw -B -ntp verify` 綠，含 `ModuleBoundariesTest` 與 `TimeZoneGuardTest`
- [ ] 23. `npm run build` 與 `npm test` 綠
- [ ] 24. `coffee-orders` 的任何檔案都**沒有**出現 `branch_product_stock` 字串（`grep -rn "branch_product_stock" backend/coffee-orders` 零命中 —— 這條 grep 同時涵蓋 `branch_product_stock_reservation`，因為它是前者的前綴）

---

## 11. 測試要求

### 11.1 後端

新增三支測試檔，位置 `backend/coffee-app/src/test/java/com/coffee/app/`：

**`BranchProductStockMigrationTest.java`** —— 對照既有的 `BranchMenuAvailabilityMigrationTest`

1. 表與索引存在，欄位型別正確
2. `quantity` 寫入 `-1` 或 `10000` 被 DB `CHECK` 擋下
3. `remaining > quantity` 被 DB `CHECK` 擋下
4. 同一 `(branch_id, product_id, on_date)` 重複 insert 被主鍵擋下
4a. `branch_product_stock_reservation` 表與 `idx_bps_reservation_row` 索引存在，欄位型別正確；`quantity` 寫入 `0` 被 `CHECK(quantity > 0)` 擋下；同一 `(order_id, product_id)` 重複 insert 被主鍵擋下；`order_id` 寫一個**不存在的訂單編號**可以成功（證明刻意沒有 FK，§4.6）

**`BranchProductStockAdminTest.java`**

5. `MENU_AVAILABILITY` + 本店 → 可設定（驗收 4）
6. **越權：`CASHIER@taipei` 設定 `kaohsiung` 的備量 → 403**
7. **越權：移除 `CASHIER` 的 `MENU_AVAILABILITY` 後設定本店備量 → 403**
8. 顧客身分（`actor.customer()`）打 `POST /api/menu/stock` → 403
9. 邊界：`quantity=0` 可以設定成功（它是合法值，意思是「今日不出」）；`quantity=9999` 可以；`-1` 與 `10000` 回 400
10. 差額同步（驗收 6）
11. `STOCK_SET` 稽核紀錄寫出來了，且 `branchId` 正確（對照 `BranchMenuAvailabilityTest` 既有的稽核斷言）

**`BranchProductStockOrderingTest.java`**

12. 驗收 8–14 逐條
12a. **驗收 14a–14d 逐條**（v1.1 新增）。這四條都是「不應該增加」的斷言，所以**一定要先斷言前置狀態**（例如 14a 要先確認取消前 `remaining` 真的是 3），否則「回補沒發生」與「前置狀態本來就不對」會長得一樣
12b. 憑據的生命週期：扣減後 `branch_product_stock_reservation` 有該訂單的列且 `quantity` 等於**合併後**的數量（同商品兩行各 2 份 → 憑據一列、`quantity=4`，不是兩列）；取消後該列被刪除；不限量的商品**不產生**憑據
13. **併發：** 備量 1，兩個執行緒同時下單 1 份 → **恰好一個成功、一個收到「已售完」或「僅剩 0 份」**，且最終 `remaining=0`。實作手法沿用既有的 `BranchMenuAvailabilityTest.java:162` `productLockSerializesHeadquartersUnlistedAgainstStoreChanges` 的 `CountDownLatch` 模式
14. **死鎖：** 兩個執行緒分別下「A+B」與「B+A」（各含兩個商品、順序相反），兩筆都要在合理時間內完成（不卡死）。這一條在驗 §5.2 的第 2 步
15. 沒有設定備量的商品下單行為**與本規格之前完全相同** —— 不要新寫，確認既有的 `CoffeeIntegrationTest` 與 `HttpWorkflowTest` 仍然綠就算通過
16. 驗收 24 的 `grep`：寫成一條 ArchUnit 或字串掃描斷言，或在 review 時人工確認。**不強制自動化**，但若 Codex 判斷自動化成本低，歡迎加
20. **驗收 14c 的測試手法（跨日）：** 不要想辦法讓系統以為「今天是昨天」（`TimeZoneGuardTest` 擋掉無參數 `now()`，而注入 `Clock` 是本規格沒要求的大改）。改成**直接用 `JdbcTemplate` 寫入**一列 `on_date = 昨天的 yyyyMMdd` 的 `branch_product_stock` 與一列對應的憑據，再走正常的取消路徑，斷言昨天那一列被回補、今天那一列不變。既有測試已有直接寫表再走正常路徑的先例，這條沿用同一個手法

### 11.2 前端

`MenuView.dom.test.ts` 補：

17. `remaining=2` 的商品卡出現「剩 2 份」
18. `remaining=null` 的商品卡**不出現**該徽章
19. 顧客模式不渲染「設定備量」按鈕（驗收 21）

純函式層面本規格沒有新的可抽離邏輯（顯示條件只有 `remaining !== null && remaining > 0` 一個判斷），**不需要**新開 `.ts` 純函式檔。若 Codex 認為值得抽，可以抽，但不是要求。

### 11.3 不要做的事

- **不得為了讓測試通過而放寬任何既有斷言。** S3 的 `new Product(...)` 補參數是機械性修改，若補完有既有測試變紅，那是實作錯了，不是測試該改
- **不得跳過併發測試。** 第 13 條是本規格最容易寫成「看起來對但其實沒鎖」的地方

---

## 12. 與其他工作的並行注意

| 對象 | 檔案交集 | 處理 |
| --- | --- | --- |
| **G24（PR #54）** | **零。** G24 動 `coffee-branches` 的 `BranchService`、`Identity.java`、`V12`；G08 動 `coffee-catalog` 的 `CatalogService`、`V13`，不碰 `Identity.java` | 任意順序，互不相讓 |
| `docs/GAP-ANALYSIS.md` | **有。** G08 與 G24 的規格 PR 都改這個檔 | **合併順序：PR #54（G24）先，G08 的規格 PR 後。** G08 的 PR 等 #54 合併後再 merge 主線解衝突，**不提前互相 merge 未合併的分支** |
| Flyway 版號 | G24 占 V12、G08 占 **V13**。下一份需要 migration 的規格自 **V14** 起算 | 已寫進 `GAP-ANALYSIS.md` 的「排程注意」 |

---

## 13. 設計決策

每一條都是 Claude（PM/SA）定案，附理由與推翻它的代價。**這些決策是給下一輪推翻用的，不是給人核准用的。**

### 13.1 庫存是「分店 × 商品 × 台北日」的每日可售數量，不是永續存量帳 —— **每日數量**

**決定：** 一張 `(branch_id, product_id, on_date)` 的表，隔天沒有列就等於不限量，沒有跨日結存、沒有進貨、沒有報廢、沒有原料 BOM。

**理由：**

1. **咖啡廳的真實約束是「今天做得出幾份」，不是「倉庫裡還有幾個」。** 手作烘焙是當天烤的，飲品受豆子與奶量約束但那是原料層（另一個問題）。每日數量直接對上營運端真正會回答的那個問題
2. **它自己會過期。** 這是 G19 §1.2 批評 `active` 旗標的那個理由的正面應用：沒有人需要記得明天把數字改回來。永續存量帳則永遠需要有人維護，一旦不準就比沒有更糟 —— 店員會學會不相信它，然後它就是一張騙人的表
3. **它複用已經驗證過的模式。** `sold_out_date`（V4）與 `branch_day_overrides.on_date`（V10）都是 `INTEGER` 台北日編碼，H2/PostgreSQL 的時區陷阱已經踩過並繞開
4. **永續帳需要的東西本規格給不出來。** 進貨單價要不要影響 `order_items.unit_cost` 的快照？盤差怎麼記？報廢算不算成本？這些是會計決策，不是設計決策，超出 PO 授權給 Claude 的範圍

**推翻它的代價：** 永續帳需要新表（`stock_movements` 之類）與一支把 `branch_product_stock` 降級為「當日快照」的 migration。**升級路徑是加法** —— 現有的表不必刪，但 `remaining` 的語意會從「今天還能賣幾份」變成「存量投影」，而 §5.3 的差額同步規則會整段作廢。約一份完整規格的工作量。**登記為 G28。**

### 13.2 不做選項層庫存 —— **不做**

**決定：** 「燕麥奶賣完了」目前無法表達，只能把所有用燕麥奶的商品各自標售完。

**理由：** 選項層庫存要處理「一個選項被多個商品共用」的扣減（`product_option_groups` 是多對多），一筆訂單扣的是選項的量而不是商品的量，而選項還有 `min_select`/`max_select` 的組合約束。那是另一個資料模型，不是本規格加個欄位能處理的。

**推翻它的代價：** 需要 `branch_option_stock` 與 `reserveStock` 的第二條扣減路徑。本規格的 `StockLine` 只帶 `productId`，要改成帶 `optionIds`，`OrderService.create` 的接入點要跟著改。**登記為 G28。**

### 13.3 「標記售完」與「備量歸零」並存，不合併 —— **並存**

**決定：** `branch_products.availability='SOLD_OUT'`（手動）與 `branch_product_stock.remaining=0`（自動）是**兩個獨立的判斷來源**，`sellable` 與 `list` 各自檢查，任一成立就不能賣。**不**讓扣減去寫 `branch_products.availability`。

**理由：**

1. **合併會立刻生出「這個 SOLD_OUT 是誰設的」這個問題。** 扣到 0 時寫 `availability='SOLD_OUT'`，然後取消訂單要不要把它清掉？要清就得先知道它是自動設的還是店員按的，於是要加一個 `auto` 旗標。那正是 G19 §1.2 批評 `active` 旗標被 `requireOpen()` 共用時踩到的同一類坑：**一個欄位承擔兩個語意，就會需要第三個欄位來分辨**
2. **分開之後取消回補是零狀態的。** `remaining` 從 0 變回 1，商品自動又能賣了，沒有任何旗標需要回捲
3. **兩者的語意本來就不同。** 「標記售完」是店員說「今天不賣了」（可能是臨時決定、可能是品質問題）；「備量歸零」是「做的那些賣完了」。店員按了售完之後把備量調高，商品**仍然不該**能賣 —— 分開的設計自然就是這個行為，合併的設計要特別寫程式才能維持

**代價（誠實列出）：** 判斷點變成兩個，`sellable` 與 `list` 都要檢查兩處。§5.6 的表格就是在控制這個成本 —— 把優先序一次寫清楚，而不是讓實作端每次自己推。

**推翻它的代價：** 合併成一欄要加 `auto_sold_out` 旗標、改寫 `BranchMenuAvailabilityTest` 的既有斷言、並重新定義取消回補的狀態轉換。而且換不到什麼 —— 查詢少 join 一張表，但那張表本來就要 join 來取 `remaining`。

### 13.4 備量是 `BRANCH` 級，總部不跨店設定 —— **BRANCH 級**

**決定：** `actor.branch(branchId)`，沒有跨店批次設定的端點。總部帳號（`global()`）依既有的 `Actor` 語意仍然過得了 `branch()` 檢查，但沒有「一次設全部分店」的功能。

**理由：** 今天烤幾個是門市當天的事，總部不知道。而且「一次設全部分店」是批次操作，要處理部分失敗、要有預覽，規模與價值不成比例。

**推翻它的代價：** 加一支 `POST /api/menu/stock/batch` 與對應的 `MENU_MANAGE` + `global()` 檢查。純加法。

### 13.5 線上未付款訂單會一直占住備量 —— **接受，登記為已知缺口**

**決定：** 扣減發生在**訂單成立**，不是付款完成。ECPAY 的 `PENDING_PAYMENT` 訂單若顧客關掉頁面就一直掛著，備量被它占住，而**目前沒有任何逾時釋放機制**（那屬於延後中的 G04）。

**理由（為什麼不改成付款時扣）：** 付款時扣會讓兩位顧客同時買走最後一份 —— 兩人都成立訂單、兩人都付款、其中一人拿不到東西。而現在**沒有退款流程**（G03 延後），所以那個結果比「備量被占住」嚴重得多。在「可能少賣」與「可能超賣且無法退款」之間，選少賣。

**接受的代價與現場解法：** 備量是一個**數字**而不是一本帳，所以店員發現數字不對時可以**直接調高**（§5.3 的差額同步保證調高不會弄壞已售數）。這是永續帳做不到的事 —— 在永續帳裡「隨手改個數字」會汙染成本與盤差。每日數量的這個「不精確」在這裡剛好變成優點。

**推翻它的代價：** G04 進場時要加一個逾時掃描，把超過 N 分鐘的 `PENDING_PAYMENT` ECPAY 訂單轉 `CANCELLED` 並呼叫 `releaseStock(branchId, orderId)`。本規格的 `releaseStock` 已經是公開方法，而逾時掃描手上一定有訂單編號，接上去是純加法。**本規格刻意先把這個鉤子準備好。** v1.1 的憑據機制讓這個鉤子更安全：逾時掃描不必自己判斷該訂單當初扣沒扣，沒扣過的就是沒有憑據、回補為 no-op。

### 13.6 `reserveStock` / `releaseStock` 不帶 `Actor` —— **不帶**

**決定：** 簽名是 `reserveStock(String branchId, String orderId, List<StockLine> lines)` 與 `releaseStock(String branchId, String orderId)`（v1.1 的簽名），兩者都**沒有 `Actor` 參數**，內部不做授權檢查，也不寫稽核。

**理由：**

1. **帶了會壞掉。** 顧客下單時手上的 `Actor` 是 `customer()`，它沒有 `MENU_AVAILABILITY`。要麼不檢查，要麼寫一段「顧客跳過檢查」的例外 —— 後者是把「不檢查」藏在看起來有檢查的程式裡，更糟
2. **稽核不需要它。** 「誰買掉最後一份」就是那筆訂單，`orders` 表加上既有的 `ORDER_*` 稽核已經記完了。再寫一筆庫存稽核是同一件事記兩次，而且兩份紀錄哪天不一致時沒人知道該信哪個
3. **授權的正確位置在外層。** `create` 與 `transition` 入口都已經做完授權與分店範圍檢查

**代價：** `Catalog` 這個公開介面上多了兩個「不檢查權限」的方法。這是 review 時會被盯的地方，所以 §7.2 已經把判準寫明：它們沒有 Controller 入口。

**推翻它的代價：** 若日後要記「每一次扣減」的細帳（例如要查某日某商品的扣減時序），就需要帶 `Actor`（或至少帶 `accountId`）並開一張 `stock_movements` 表 —— 那就是 §13.1 的永續帳，一併處理。

### 13.7 不做低庫存警示 —— **不做**

**決定：** 不做「剩 3 份時通知店長」的推播、紅點或聲音。

**理由：** 本系統沒有任何推播基礎設施（G12 的範圍）。而 POS 菜單上的「剩 N 份」徽章（§5.7）已經在店員每一次點餐時出現在眼前 —— 那是比通知更高頻、更不會被忽略的通道。

**推翻它的代價：** 要先有推播通道（G12 或 G16 可能帶進來），之後加一個門檻欄位與觸發條件。純加法。

### 13.8 報表不加庫存維度 —— **不加**

**決定：** `coffee-reporting` 完全不碰 `branch_product_stock`。

**理由：** 「今日備量 vs 實際售出」是有價值的分析，但它需要**跨日累積**才有意義（一天的數字看不出什麼），而跨日累積的前提是備量資料本身可靠。本規格剛上線時沒有人設過備量，報表會是一片空白或一堆 `null`。先讓資料長出來，再決定要看什麼。

**推翻它的代價：** `coffee-reporting` 目前依賴 `shared` 並直接查 `orders` / `order_items` / `branches`（唯讀投影例外）。要加庫存維度就要把 `branch_product_stock` 也納入那個例外清單，或改走 `catalog.api`。前者要修 `AGENTS.md` 的「資料存取」一節，不是局部決定。

### 13.9 跨日取消的訂單回補到**原本扣減的那一天**，不回補到今天 —— **回補原日（v1.1 修訂）**

**決定：** `releaseStock` 依憑據的 `on_date` 找列並回補（§5.5 第 3a 步）。昨天的訂單今天取消，回補的是**昨天那一列**。若那一列已經不存在（限量被解除），就不回補、只刪憑據。

**v1.0 寫的是「找不到今天的列就不回補」，v1.1 改成這樣，理由：** 原本要守的紅線是「**昨天沒賣掉的額度不可以變成今天的可售量**」—— 那條紅線在 v1.1 依然成立，而且守得更嚴：回補落在昨天那一列，今天的 `remaining` 一個字都不會動。差別在於 v1.0 連「昨天那一列的帳」也一起放棄了，於是昨天的 `已售 = quantity - remaining` 會永遠記著一筆已經取消的訂單。既然憑據已經精確記下是哪一天扣的（§4.6），把那一天的帳改對是零額外成本的。

**順帶解決的事：** v1.0 的「找不到今天的列就 `continue`」在跨日之外還會吞掉另一種情形 —— 不限量時成立的訂單在限量建立後取消。v1.0 那個版本不但沒吞掉，還會錯誤回補（§4.6 的第一條路徑）。依憑據回補讓「跨日」與「當初沒扣」兩件事各自有正確且不同的處理。

**推翻它的代價：** 若日後出現「可跨日保存」的商品類別（昨天沒賣掉的確實能今天賣），就需要一個商品層的旗標，並讓 `releaseStock` 依旗標決定回補到哪一天。那是 §13.1 的永續帳範圍。憑據已經帶著 `on_date`，所以那一天要改的只有「回補到哪一列」這一個決定點。

### 13.10 `remaining` 顯示給顧客 —— **顯示**

**決定：** `GET /api/menu` 對顧客也回傳 `remaining`。

**理由：** 「只剩 2 份」對顧客是有用的決策資訊，而且它不是敏感資料 —— 走進店裡看一眼櫃台就知道。隱藏它只會讓顧客下單後才發現不夠。

**代價：** 備量數字會出現在公開 API。若 PO 認為這洩漏了經營資訊（例如競爭對手可以推算日銷量），**成本是把 §5.6 的 `remaining` 在顧客分支改回 `null` 一行**，`availability='SOLD_OUT'` 的部分保留。**這是本規格最容易推翻的一條**，刻意設計成這樣。

### 13.11 不足時回 400 而不是 409 —— **400**

見 §6.3 的說明與推翻代價，不重複。

### 13.12 回補靠持久化的保留憑據，不靠推論 —— **憑據表（v1.1 新增）**

**決定：** 新增 `branch_product_stock_reservation`，`reserveStock` 真的扣到就寫一列，`releaseStock` 只依憑據回補並刪除憑據。完整的機制與缺陷推導寫在 §4.6，這裡只記決策與代價。

**理由：** v1.0 讓 `releaseStock` 自己用「今天 + branchId + productId」推論回補對象，而那個推論有兩種情形會推錯（§4.6 的兩條路徑），兩種都會憑空生出可售額度，也就是**真的會超賣**。這類缺陷的特徵是「編譯過、既有測試綠、只在營運現場出現」，而且症狀（備量數字不對）會先被當成店員操作失誤。憑據是唯一能分辨「這筆訂單當初到底扣沒扣、扣的是哪一天、扣了多少」的東西，推論做不到。

**代價：**

1. 多一張表、多一支 `insert`、多一支 `delete`。S1 多一行 SQL，S2 的 `releaseStock` 反而**變簡單**（不必合併訂單品項，憑據已經是合併後的結果）
2. `releaseStock` 的簽名從 `(branchId, lines)` 變成 `(branchId, orderId)`，`reserveStock` 多一個 `orderId` 參數。因為兩個方法都是 v1.1 連同本規格一起新增的，**沒有任何既有呼叫端要改**
3. 憑據是「未結」狀態的紀錄，不是歷史帳。回補完就刪，所以它**不能**當成「今天賣了什麼」的查詢來源 —— 那是 `orders` / `order_items` 的工作，也是 §13.8 不加庫存報表維度的理由之一
4. 不限量的訂單不留憑據，所以「這筆訂單當初沒扣」與「這筆訂單的憑據被 §5.3 第 6 步清掉」在資料上長得一樣。兩者的正確行為都是「不回補」，所以不需要分辨

**推翻它的代價：** 要回到推論式回補，就要先證明上面兩條路徑在營運上不可能發生 —— 做不到，因為「先不設限量、賣一陣子才設」是最自然的使用方式（店員早上不知道今天備多少，中午才決定）。另一條路是改用庫存列版號，§4.6 最後一段寫了為什麼那條更貴。

### 13.13 剩餘徽章與「今日售完」徽章不並存 —— **售完優先（v1.2 新增）**

**決定：** 商品卡在 `availability === "SOLD_OUT"` 時**只顯示「今日售完」**，即使當日 `remaining > 0` 也不顯示「剩 N 份」。

**為什麼這一條是 v1.2 才出現：** v1.1 的 §5.7 第 1 點只寫了「`remaining !== null && remaining > 0` 時顯示」，沒有說它與既有的「今日售完」徽章（`MenuView.vue:552`）之間的關係。照字面實作出來的結果是兩個獨立的 `v-if`，於是「店員手動標記售完」且「當日還有備量」時，同一張卡會同時出現「今日售完」與「剩 5 份」—— 兩句話互相矛盾，而且店員會以為系統算錯。這是規格沒寫清楚造成的，不是實作判斷錯誤（Codex 在 [PR #59](https://github.com/choka1227/coffee_GPT6/pull/59) 照 v1.1 的字面做對了）。

**理由：**

1. **這是 §13.3「兩個獨立判斷來源，任一成立就不能賣」在 UI 上的必然推論。** 後端兩個來源分開是對的（取消回補才能零狀態），但畫面上要回答的只有一個問題：「現在能不能賣」。不能賣的時候，「還剩幾份」是多餘且誤導的資訊
2. **手動售完的語意比備量更強。** 店員按「標記售完」可能是品質問題、可能是臨時決定（§13.3 的理由 3）。這種情形下剩餘數字正確但無意義 —— 那些份數今天就是不賣
3. **資料不必改。** 後端照 §5.6 繼續回傳真實的 `remaining`（手動 `SOLD_OUT` 那一列是「照實回傳」），只在前端決定不渲染。這樣「恢復供應」之後徽章會自己正確顯示剩餘量，不需要任何狀態回捲

**實作：** 剩餘徽章的條件加一個前綴 —— `v-if="p.availability !== 'SOLD_OUT' && p.remaining !== null && p.remaining > 0"`。一行，不動後端、不動 schema。驗收見 §10 第 19a 條。

**代價：** 手動售完期間店員在菜單上看不到當日剩餘量。要查得到的話從「設定備量」表單進去（它會顯示「目前剩 N 份」），所以資訊沒有消失，只是不在卡面上。

**推翻它的代價：** 若日後認為店員需要在卡面上同時看到兩者，做法不是把徽章並列（矛盾仍在），而是把售完徽章的文案改成能同時表達兩件事的句子（例如「今日售完（尚餘 5 份未開賣）」）。那需要文案決定與較寬的徽章版面，成本遠高於現在這一行。

---

## 14. 登記給後續的缺口

### G28 — 永續庫存帳與選項層庫存（本規格登記，P2）

本規格刻意只做「每日可售數量」。下列各項若日後成為實際痛點，合成一份 G28 處理：

1. **永續存量帳**：進貨、報廢、盤點、跨日結存（§13.1）
2. **選項層庫存**：燕麥奶、特定糖漿賣完（§13.2）
3. **原料 BOM**：一杯拿鐵扣多少豆子與奶（§13.1）
4. **跨日保存商品的回補規則**（§13.9）
5. **扣減細帳**（`stock_movements`，§13.6）

**開工的前提：** 要先有一段時間的真實備量資料（G08 上線後店員實際在用），否則 G28 設計出來的帳務模型一定是猜的 —— 這與 G17 刻意延後的理由相同。

---

## 15. 修訂紀錄

| 日期 | 版本 | 內容 |
| --- | --- | --- |
| 2026-10-02 | v1.0 | 初版。G08 由 P2 升為 P1，排為工作順序第 18 項。Flyway 占用 V13。登記 G28（永續庫存帳與選項層庫存） |
| 2026-10-02 | v1.1 | **修補 v1.0 回補路徑的交易正確性缺口**（Codex 於 [PR #55](https://github.com/choka1227/coffee_GPT6/pull/55) 的 `REQUEST_CHANGES` 指出，判定成立）。v1.0 的 `releaseStock` 用「今天 + branchId + productId」推論回補對象，有兩條路徑會回補未曾扣減的訂單並**真的造成超賣**（推導見新增的 §4.6）。修法：新增 `branch_product_stock_reservation` 保留憑據表（同一支 V13，§4.2），`reserveStock` 加 `orderId` 參數並在實際扣到時寫憑據（§5.2 第 4f 步），`releaseStock` 簽名改為 `(branchId, orderId)` 並只依憑據回補（§5.5），`setStock` 在從無到有建立限量時清掉未結憑據（§5.3 第 6 步）。§13.9 由「跨日不回補」改為「回補到憑據記載的那一天」，紅線不變但帳更準。新增設計決策 §13.12、驗收 14a–14d（四條反向驗收，含 Codex 要求的兩條）、測試要求 4a／12a／12b／20。**階段切分不變**（仍是 S1–S4），憑據表與 `setStock` 的 `DELETE` 放在 S1，憑據的寫入與讀取放在 S2 |
| 2026-10-03 | v1.2 | **修補 v1.1 的兩處規格缺陷，皆由 [PR #59](https://github.com/choka1227/coffee_GPT6/pull/59) 的審查發現，實作端無過失。**（1）§5.6 的 `UNLISTED` 列與驗收 16 照抄了 `effective_availability` 的 `CASE`，漏讀 `list` 既有的 `WHERE` 已把 `UNLISTED` 商品整列排除，使原驗收 16 在不破壞 `CoffeeIntegrationTest` 的前提下不可能成立 —— 改為「維持既有隱藏契約」並重寫驗收 16（§5.6 的「v1.2 修正」）。（2）§5.7 第 1 點沒有規定剩餘徽章與既有「今日售完」徽章的關係，照字面實作會讓兩個矛盾的徽章並存 —— 新增 §13.13 定案為「售完優先」並補驗收 19a。驗收 19a 是 G08 唯一未滿足的條件，登記為 **G08a**（一行前端修正，見 `docs/GAP-ANALYSIS.md`）。本版**不動** schema、API、授權與金額規則，已合併的實作除 19a 外全數有效 |
