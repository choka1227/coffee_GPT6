# G20 — 品項層促銷（買一送一、第二件半價、指定品項折扣）

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G20（P2 升為 P1） |
| 版本 | v1.0（2026-10-04） |
| 登記來源 | `docs/specs/G07-order-discounts.md` §11.2「只做訂單層折扣，品項層與身分別另立 —— G20 / G21」；`docs/GAP-ANALYSIS.md` P2 表 G20 列 |
| Flyway 版號 | **V14**（主線目前最高是 V13，見 §4.1） |
| 涉及後端模組 | `coffee-catalog`（主）、`coffee-orders`（呼叫端） |
| 新增資料表 | `item_promotions`、`order_item_promotions`（兩張都在 V14） |
| 涉及前端模組 | `modules/catalog`（總部維護頁）、`modules/ordering`（購物車與訂單明細）、`shared/types.ts` |
| 施工階段 | 四階段 S1–S4，見 §9 |
| 預計 PR 數 | 1（分支 `codex/g20-item-promotions`，逐階段推進、逐階段可合併） |

---

## 1. 背景與目標

### 1.1 現況

G07（PR #31，2026-09-28 合併）做完了**訂單層**折扣：一張訂單輸入一組優惠碼，折抵算在小計上，快照寫 `order_discounts`。實作在 `coffee-catalog` 的 `DiscountService`，計價接點在 `OrderService.create()`（`OrderService.java:84-86`）。

但咖啡廳每天真正在賣的促銷是**品項層**的：

- 「指定飲品買一送一」
- 「第二杯半價」
- 「蛋糕類全品項九折」

這三種現在都做不到。店員唯一的替代做法是**開一組訂單層優惠碼然後口頭約定使用條件** —— 系統不會檢查顧客是不是真的買了兩杯，折抵也不會落在品項上。後果有三個，都是每天會踩到的：

1. **算錯錢**：「第二杯半價」用訂單層百分比折扣近似，買一杯也會被折；買三杯則折太少。
2. **對不起帳**：`order_discounts` 只記得「套了 SPRING20 折 50 元」，記不得「折的是哪一杯」。退單、客訴、日結都查不出來。
3. **稽核軌跡斷掉**：`ORDER_DISCOUNT` 的 summary 只有代碼與金額，看不出促銷規則本身。

### 1.2 要擋的缺陷（具體的）

| # | 缺陷 | 現在的行為 |
| --- | --- | --- |
| 1 | 數量條件不存在 | 「第二杯半價」只能寫成無條件九五折；買一杯的顧客也被折 |
| 2 | 折抵無法歸屬到品項 | `order_items` 沒有任何折抵欄位，`order_discounts` 是訂單層單列 |
| 3 | 同類商品混買無法計價 | 「蛋糕類任選兩件打八折」需要跨 `product_id` 的分類條件，`discounts` 表只有 `min_subtotal` |
| 4 | 前端會算錯應收 | `checkout.ts` 的 `POS_CASH_SINGLE` 路徑直接用前端購物車小計當應收金額（`checkout.ts:48-52`）。只要折抵依數量浮動，前端算的就不是真的 |

### 1.3 目標

1. 總部可以維護品項層促銷規則，規則本身是資料，不是程式。
2. 建立訂單時**後端自動**判定並套用，店員與顧客都不需要輸入任何代碼。
3. 折抵金額落在**品項列**上並留下快照，事後查得出「折的是哪一杯、依哪一條規則」。
4. 與 G07 的訂單層優惠碼**並存且順序明確**，不互相覆蓋、不重複折抵。
5. 前端不再自行計算任何應收金額。

### 1.4 為什麼現在排這一項

這一項在 G07 §11.2 被登記時附帶一個閘門：「排在 G07 上線並跑過一段時間之後 —— 屆時 `order_discounts` 已經有真實資料，可以看出實際用了哪幾種促銷再決定要不要做」。

**本輪（2026-10-04）決定推翻這個閘門，理由三點：**

1. **閘門不可能滿足。** Claude 與 Codex 都沒有可部署、可營業的環境（G07 §11.11 已登記），PO 又把金流整批延後到最後才串接。「等真實 `order_discounts` 資料」在現況下等於「永遠不做」。閘門的本意是避免猜錯促銷型態，不是把這一項無限期擱置。
2. **猜錯的風險已經被規格設計壓到很低。** §13.1 把規則收斂成**兩種**（`ITEM_PERCENT`、`NTH_PERCENT`），而「買一送一」「買二送一」「第二件半價」「第二件七折」全部是 `NTH_PERCENT` 的參數組合，不是四個不同的實作。要加第三種型態才叫猜錯，而目前看不出第三種是什麼。
3. **工作順序上沒有別的可開工項，而上一輪已經對 Codex 承諾了這一項。** 第 19 項（G08a）已於 2026-10-04 隨 PR #62 合併，第 20 項（金流 G01–G04 其餘部分）由 PO 整批延後。Claude 在 PR #62 的 review 裡寫明「下一輪依 P2 升排一項並產出規格書 —— Codex 不需要等，也不需要自己挑題目開工」。不產出就等於讓實作端空等。

**其餘 P2 項目為什麼不是這一項**（逐項排除，與 2026-10-03 那一輪相同，不重複推導）：G05（要引入 Redis，屬 PO 的相依決策）、G12（採購決策，PO）、G16（濫用防治與個資法遵，明文不在設計決策授權內，PO）、G17 與 G28（開工前提是真實營運資料，而且不像 G20 有可收斂的參數空間）、G21（會員價需要先有會員，而會員自助註冊是 G16，卡在 PO）。**G20 是唯一一項「閘門只是等資料、而且可以用收斂參數空間取代等資料」的。**

**推翻本決定的代價：** 若日後真實資料顯示實際促銷落在第三種型態（例如「滿額贈指定商品」「加價購」），`item_promotions.kind` 要加列舉值並補一條演算法分支；`order_item_promotions` 的快照欄位（`percent`、`nth`）對新型態可能不夠，要再加欄位。但表結構與接線點都不用動，是加法。

---

## 2. 範圍

### 2.1 在範圍內

1. `item_promotions` 規則主檔，總部維護（`GET`／`POST /api/promotions`）。
2. 兩種規則型態：`ITEM_PERCENT`（符合條件的每一單位打折）、`NTH_PERCENT`（每第 n 個單位打折）。
3. 規則目標二選一：單一商品（`PRODUCT`）或單一分類（`CATEGORY`）。
4. 建立訂單時自動套用，一張訂單最多一條規則（§13.2）。
5. `order_items.discount_amount` 與 `order_item_promotions` 快照。
6. `Orders.Order` / `Orders.Line` 的 API 加法擴充。
7. 稽核：`PROMOTION_SAVE`（維護）、`ORDER_ITEM_DISCOUNT`（套用）。
8. 前端：總部維護頁、購物車與訂單明細顯示品項折抵、POS 現金改走兩階段。

### 2.2 不在範圍內（逐項有理由）

| 不做 | 理由 |
| --- | --- |
| 一張訂單疊加多條品項規則 | §13.2 |
| 自動把贈品加進購物車 | §13.5 |
| 促銷使用次數上限 | §13.8 |
| 菜單上的促銷徽章 | §13.12，登記為後續 |
| 報表的淨營收歸屬 | §13.11，與 G07 現狀一致，登記為後續 |
| 會員價、員工價 | G21，卡在 G16（PO） |
| 滿額贈、加價購、組合餐 | 看不出需求，§1.4 第 2 點 |
| 選項層促銷（加料免費） | 選項不是獨立可售單位，登記為後續 |

---

## 3. 涉及模組與邊界

```
coffee-catalog
  api/Promotions.java          新增 —— interface + record（Rule、Applied、Line、Allocation）
  internal/PromotionService    新增 —— implements Promotions
  internal/PromotionController 新增 —— /api/promotions
coffee-orders
  internal/OrderService        修改 —— 建構子多注入 Promotions，create() 多一段計價
  api/Orders.java              修改 —— Order 與 Line 加欄位（加法）
coffee-app
  db/migration/V14__item_promotions.sql  新增
```

**邊界檢查：** `coffee-orders` 已經依賴 `coffee-catalog.api`（`Catalog`、`Discounts`），多一個 `Promotions` 不新增模組間依賴、不產生循環，`ModuleBoundariesTest` 不需要修改。計價邏輯放 `coffee-catalog` 而不是 `coffee-orders`，理由與 G07 把 `DiscountService` 放 catalog 一致：促銷規則是菜單側的資料，訂單側只是消費者。

---

## 4. DB schema 與 migration

### 4.1 migration 檔名

`backend/coffee-app/src/main/resources/db/migration/V14__item_promotions.sql`

主線目前最高是 `V13__branch_product_stock.sql`（G08，PR #59）。**V14 由本規格占用。** Flyway 預設不接受事後補插較小版號，所以就算本規格最終被擱置，也不要回頭補用 V14。

### 4.2 Schema

```sql
CREATE TABLE item_promotions(
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(40) NOT NULL,
  kind VARCHAR(12) NOT NULL CHECK(kind IN ('ITEM_PERCENT','NTH_PERCENT')),
  percent INTEGER NOT NULL CHECK(percent>=1 AND percent<=100),
  nth INTEGER NOT NULL DEFAULT 0 CHECK(nth=0 OR nth>=2),
  target_kind VARCHAR(8) NOT NULL CHECK(target_kind IN ('PRODUCT','CATEGORY')),
  product_id VARCHAR(36) REFERENCES products(id),
  category VARCHAR(40),
  branch_id VARCHAR(36) REFERENCES branches(id),
  starts_at BIGINT,
  ends_at BIGINT,
  active BOOLEAN NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  CHECK((target_kind='PRODUCT' AND product_id IS NOT NULL AND category IS NULL)
     OR (target_kind='CATEGORY' AND category IS NOT NULL AND product_id IS NULL)),
  CHECK((kind='ITEM_PERCENT' AND nth=0 AND percent<=90)
     OR (kind='NTH_PERCENT' AND nth>=2))
);

CREATE INDEX idx_item_promotions_active ON item_promotions(active, branch_id);

CREATE TABLE order_item_promotions(
  order_id VARCHAR(20) PRIMARY KEY REFERENCES orders(id),
  promotion_id VARCHAR(36) NOT NULL,
  name VARCHAR(40) NOT NULL,
  kind VARCHAR(12) NOT NULL,
  percent INTEGER NOT NULL,
  nth INTEGER NOT NULL,
  target_kind VARCHAR(8) NOT NULL,
  target_id VARCHAR(40) NOT NULL,
  discounted_units INTEGER NOT NULL CHECK(discounted_units>0),
  discount_amount INTEGER NOT NULL CHECK(discount_amount>0),
  created_at BIGINT NOT NULL
);

ALTER TABLE orders ADD COLUMN item_discount_amount INTEGER NOT NULL DEFAULT 0;
ALTER TABLE order_items ADD COLUMN discount_amount INTEGER NOT NULL DEFAULT 0;
```

**H2 相容性：** 表級 `CHECK` 已有先例（`V9__order_discounts.sql` 的 `CHECK(max_redemptions IS NULL OR max_redemptions>0)`），本檔用的是同一種寫法，不需要 `ALTER TABLE ... ADD CONSTRAINT`。本檔**不修改**任何既有 migration。

### 4.3 為什麼快照表是訂單層單列（`order_id` 當 PK），不是品項層多列

一張訂單最多套用一條規則（§13.2），所以「規則本身」只需要存一次；而「折了多少」要落在品項上，由 `order_items.discount_amount` 承接。拆成兩處是刻意的：

- 規則快照單列 → 查詢訂單時一次 join 就拿到（與 `order_discounts` 完全同形，`discountSnapshot()` 旁邊再寫一個 `promotionSnapshot()` 即可）
- 品項折抵用欄位 → `order_items` 本來就是逐列讀的，不多一次查詢，也不會有 N+1

### 4.4 `target_id` 為什麼在快照裡要存成單一 `VARCHAR(40)`

`item_promotions` 分成 `product_id` 與 `category` 兩欄是為了讓外鍵與 `CHECK` 都成立。快照不需要外鍵（快照的本意就是不隨主檔變動），所以合成一欄，`target_kind` 決定它是商品 id 還是分類名。`VARCHAR(40)` 取 `category` 的長度（商品 id 是 36 字元的 UUID，放得下）。

### 4.5 `percent` 的上限為什麼分型態

`ITEM_PERCENT` 上限 90，`NTH_PERCENT` 上限 100。理由在 §8 的「總額永遠 ≥ 1 元」證明：`NTH_PERCENT` 因為 `nth>=2`，數學上保證至少有一個單位不被折，所以可以允許 100%（買一送一就是 100%）；`ITEM_PERCENT` 折的是每一個單位，若允許 100% 就會出現 0 元訂單，而 `orders.total` 的 `CHECK(total>0)` 來自 V1、不得修改（G07 §11.9 已經為這件事做過一次決策）。

---

## 5. 技術設計

### 5.1 `Promotions` 介面（全部是加法）

```java
package com.coffee.catalog.api;

public interface Promotions {
  record Rule(
      String id,
      String name,
      String kind,          // ITEM_PERCENT | NTH_PERCENT
      int percent,
      int nth,              // ITEM_PERCENT 固定 0
      String targetKind,    // PRODUCT | CATEGORY
      String productId,     // targetKind=PRODUCT 時非 null
      String category,      // targetKind=CATEGORY 時非 null
      String branchId,      // null = 全鏈
      Long startsAt,
      Long endsAt,
      boolean active) {}

  /** 計價輸入：一列購物車品項。unitPrice 已含選項加價。 */
  record Line(String productId, String category, int unitPrice, int quantity) {}

  /** 計價輸出。lineDiscounts 與輸入的 lines 等長、同序。 */
  record Applied(
      String promotionId,
      String name,
      String kind,
      int percent,
      int nth,
      String targetKind,
      String targetId,
      int discountedUnits,
      int discountAmount,
      List<Integer> lineDiscounts) {}

  List<Rule> list(Actor actor);

  Rule save(Actor actor, Rule rule);

  /** 無任何規則命中時回 null。純計算，不寫任何資料表。 */
  Applied apply(String branchId, List<Line> lines, long atEpochMs);
}
```

`apply` 不帶 `Actor`：與 `Discounts.apply` 以及 `Catalog.reserveStock` 一致 —— 它是 `OrderService.create` 在已完成授權之後呼叫的內部計價步驟，授權已經在上游做完（理由同 G08 §13.6）。

### 5.2 `apply` 的演算法（本規格的核心，請逐步照做）

> 這一段是純函式，不碰資料庫（規則讀取在 `apply` 的第 1 步，之後全部是記憶體運算）。**S2 要先把它連測試一起做完，再在 S3 接線。**

**第 1 步 — 取候選規則**

```sql
select * from item_promotions
 where active=true
   and (branch_id is null or branch_id=?)
   and (starts_at is null or starts_at<=?)
   and (ends_at is null or ends_at>=?)
 order by id
```

不取行鎖、不更新任何欄位（§13.8）。

**第 2 步 — 逐規則試算**

對每一條候選規則 `r`：

1. **展開命中單位。** 對 `lines` 由 0 開始逐列檢查是否命中：
   - `targetKind=PRODUCT` → `line.productId().equals(r.productId())`
   - `targetKind=CATEGORY` → `line.category().equals(r.category())`

   命中的列產生 `line.quantity()` 個單位，每個單位帶 `(lineIndex, unitPrice)`。
   命中單位總數記為 `u`。`u == 0` → 這條規則的折抵是 0，跳過。

2. **算折抵。**
   - `ITEM_PERCENT`：每一個命中單位折 `unitPrice * percent / 100`（整數除法，向下取整），折抵單位數 `k = u`。
   - `NTH_PERCENT`：`k = u / nth`（整數除法）。`k == 0` → 折抵 0，跳過。把命中單位**依 `(unitPrice 升冪, lineIndex 升冪)` 排序**，取前 `k` 個，每個折 `unitPrice * percent / 100`。

3. **累加回列。** 把每個被折單位的折抵加到 `lineDiscounts[lineIndex]`。
4. `discountAmount` = `lineDiscounts` 的總和。

所有乘法用 `Math.multiplyExact`，加法用 `Math.addExact`。

**第 3 步 — 選一條**

取 `discountAmount` 最大的那一條；相同時取 `id` 字典序較小的那一條（第 1 步已經 `order by id`，所以「嚴格大於才換人」就足夠）。最大值為 0 → 回 `null`。

**第 4 步 — 回傳**

`Applied` 帶上被選中規則的快照欄位、`discountedUnits=k`、`discountAmount`、以及與輸入等長同序的 `lineDiscounts`。

**為什麼排序取「最便宜的單位」：** 見 §13.4。

**為什麼要用 `lineIndex` 當第二排序鍵：** 同價單位的選擇必須是確定的，否則同一張購物車在不同 JVM 或不同 `HashMap` 迭代順序下會算出不同的 `lineDiscounts`（總額相同但歸屬不同），快照就不可重現。

### 5.3 `OrderService.create` 的接入點

現況（`OrderService.java:83-86`）：

```java
int subtotal = total;
var applied = discounts.apply(q.discountCode(), q.branchId(), subtotal, now);
int discountAmount = applied == null ? 0 : applied.discountAmount();
total = Math.subtractExact(subtotal, discountAmount);
```

改成：

```java
int subtotal = total;

// 1. 品項層先算（自動，不需代碼）
var promo = promotions.apply(
    q.branchId(),
    promoLines,                 // 與 q.items() 等長同序，見下
    now);
int itemDiscount = promo == null ? 0 : promo.discountAmount();
int afterItems = Math.subtractExact(subtotal, itemDiscount);

// 2. 訂單層優惠碼算在已折抵的小計上（§13.3）
var applied = discounts.apply(q.discountCode(), q.branchId(), afterItems, now);
int codeDiscount = applied == null ? 0 : applied.discountAmount();

int discountAmount = Math.addExact(itemDiscount, codeDiscount);
total = Math.subtractExact(subtotal, discountAmount);
```

`promoLines` 在既有的品項迴圈（`OrderService.java:71-81`）裡順手建起來 —— 那個迴圈已經算出 `p.category()` 與 `unitPrice`：

```java
promoLines.add(new Promotions.Line(p.id(), p.category(), unitPrice, l.quantity()));
```

**位置很重要：** 這一段必須在 `catalog.reserveStock(...)`（`OrderService.java:87`）**之前或之後都可以，但一定要在 `insert into orders` 之前**，因為 `total` 與 `item_discount_amount` 都要寫進那一筆 insert。建議維持現有順序（計價 → `reserveStock` → insert），把品項層計價插在 `discounts.apply` 之前即可，`reserveStock` 不受影響（促銷不改數量，§13.5）。

### 5.4 寫入

`insert into orders(...)` 多一欄 `item_discount_amount`（值 `itemDiscount`），`discount_amount` 寫**總折抵**（`itemDiscount + codeDiscount`）。

`promo != null` 時：

```java
db.update(
    "insert into order_item_promotions(order_id,promotion_id,name,kind,percent,nth,target_kind,target_id,discounted_units,discount_amount,created_at)"
        + " values(?,?,?,?,?,?,?,?,?,?,?)", ...);
audit.record(a, "ORDER_ITEM_DISCOUNT", id, q.branchId(),
    "套用品項促銷 " + promo.name() + "，折抵 " + promo.discountAmount() + " 元（" + promo.discountedUnits() + " 件）");
```

既有的品項迴圈（`OrderService.java:117-143`）的 `insert into order_items(...)` 多一欄 `discount_amount`，值取 `promo == null ? 0 : promo.lineDiscounts().get(i)`。

### 5.5 `orders.discount_amount` 的語意變更（請特別讀這一段）

**變更後 `orders.discount_amount` = 品項層折抵 + 訂單層折抵（總折抵）。**

這是刻意的，因為 `OrderService.snapshot()`（`OrderService.java:620`）用 `total + discount_amount` 回推 `subtotal`。只有讓 `discount_amount` 含全部折抵，`subtotal` 才會繼續等於**定價毛額小計**；否則 `subtotal` 會變成「扣掉品項折抵之後的小計」，而前端顯示的「小計／折抵／應收」三行就對不起來。

連帶效果，全部是想要的：

- `ReportService`（`ReportService.java:137`）的 `sum(o.discount_amount)` 自動含品項折抵，不用改程式。
- 既有訂單（沒有品項促銷）的 `discount_amount` 不變，`item_discount_amount` 預設 0 → **零資料遷移**。
- `order_discounts.subtotal` 的語意**也變了**：它記的是「優惠碼計算所依據的小計」，也就是扣掉品項折抵之後的 `afterItems`。這是 §13.3 的直接結果，要寫進 `Discounts.Applied` 的 javadoc。

### 5.6 API 擴充（`Orders`）

```java
record ItemPromotion(
    String promotionId, String name, String kind, int percent, int nth,
    int discountedUnits, int discountAmount) {}

record Line(
    ..., int lineTotal, int discountAmount, List<LineOption> options) {}   // discountAmount 為加入欄位

record Order(
    ..., int discountAmount, int itemDiscountAmount,
    OrderDiscount discount, ItemPromotion itemPromotion, ...) {}
```

`Line.lineTotal` 的語意**不變**（定價毛額），折抵另外一欄。理由：收據要同時顯示「原價」與「折抵」，把 `lineTotal` 直接減掉就印不出原價了。

`snapshot()` 旁邊加一個 `promotionSnapshot(orderId)`，與既有的 `discountSnapshot(orderId)` 同形。`page()` 的投影查詢（`OrderService.java:521` 一帶的 `reconciliationCandidates` 與分頁查詢）**不**加 `order_item_promotions` 的 join —— 清單不顯示促銷明細，`itemDiscountAmount` 直接讀 `o.item_discount_amount` 欄位就夠，`itemPromotion` 在清單情境回 `null`。這一點要寫進 `Orders` 介面的註解，與既有的 `reconciliationCandidates` 註解同樣處理。

### 5.7 前端

**(a) POS 現金一律兩階段（S3，與計價同階段）**

`frontend/src/modules/ordering/checkout.ts` 的 `checkoutPath()` 移除 `POS_CASH_SINGLE` 的回傳路徑：

```ts
export function checkoutPath(input: {
  isCustomer: boolean;
  paymentMethod: string;
  discountCode: string;      // 保留參數，簽章不變，避免呼叫端一起改
}): CheckoutPath {
  if (input.paymentMethod === "ECPAY") return "ECPAY";
  if (!input.isCustomer && input.paymentMethod === "CASH") return "POS_CASH_TWO_STAGE";
  return "CUSTOMER_PENDING";
}
```

`CheckoutPath` 的 `"POS_CASH_SINGLE"` 型別成員與 `amountDue()`／`effectiveTendered()` 裡對它的分支**保留不刪**（加法原則；刪掉會讓這一階段變成破壞性變更，而且 `amountDue()` 的 `throw` 分支正是我們要的防線）。`checkout.spec.ts` 既有的 `POS_CASH_SINGLE` 案例改為直接呼叫 `amountDue({path:"POS_CASH_SINGLE", ...})`，不再經由 `checkoutPath()` 取得。

**為什麼一定要跟計價同階段：** 見 §9 的「階段切分的理由」。

**(b) 總部維護頁（S4）**

`frontend/src/modules/catalog/PromotionsView.vue`，與既有的 `DiscountsView.vue` 同形、同一個路由層級（`App.vue` 導覽多一列，只對 `MENU_MANAGE` 且 `global` 的使用者顯示 —— 前端導覽只是體驗，真正的授權在後端）。

欄位：名稱、型態（下拉：每件折扣／第 N 件折扣）、折扣百分比、N、目標（下拉：商品／分類 + 對應的第二個下拉）、適用分店（空白=全鏈）、起訖時間、啟用。

**(c) 購物車與訂單明細（S4）**

- 購物車：建立訂單後顯示後端回傳的「小計 / 品項促銷折抵 / 優惠碼折抵 / 應收」四行。促銷名稱顯示 `itemPromotion.name`。
- `OrdersView.vue` 訂單明細：品項列在 `discountAmount > 0` 時加一行「促銷折抵 −N」。
- `shared/types.ts`：`Order` 加 `itemDiscountAmount`、`itemPromotion`；`Line` 加 `discountAmount`。

---

## 6. API

### 6.1 `GET /api/promotions`

總部列出全部規則（含停用）。權限：`MENU_MANAGE` 且 `global()`。

回應：`Promotions.Rule[]`。

### 6.2 `POST /api/promotions`

新增或更新。`id` 為空字串或 `null` → 新增（後端產生 `Ids.next()`）；有值 → 更新。

請求／回應：`Promotions.Rule`。

驗證（全部回 400，訊息用繁體中文）：

| 條件 | 訊息 |
| --- | --- |
| `name` 空或超過 40 字 | `促銷名稱需為 1–40 字` |
| `kind` 不在列舉內 | `促銷型態不正確` |
| `kind=ITEM_PERCENT` 且 `percent` 不在 1–90 | `每件折扣的百分比需為 1–90` |
| `kind=NTH_PERCENT` 且 `percent` 不在 1–100 | `第 N 件折扣的百分比需為 1–100` |
| `kind=NTH_PERCENT` 且 `nth < 2` | `第 N 件折扣的 N 需大於或等於 2` |
| `targetKind` 不在列舉內 | `促銷目標不正確` |
| `targetKind=PRODUCT` 但商品不存在 | `找不到指定的商品`（404） |
| `targetKind=CATEGORY` 但 `category` 空或超過 40 字 | `促銷分類需為 1–40 字` |
| `branchId` 非空但分店不存在 | `找不到指定的分店`（404） |
| `startsAt` 與 `endsAt` 都非 null 且 `startsAt > endsAt` | `促銷結束時間不能早於開始時間` |

`kind=ITEM_PERCENT` 時後端強制把 `nth` 正規化為 0（不要回 400 —— 前端下拉切換時很容易留著舊值，正規化比擋下來友善，而 `CHECK` 也要求它是 0）。

成功後 `audit.record(actor, "PROMOTION_SAVE", rule.id(), null, "新增/更新品項促銷 <name>（<說明>）")`，`branchId` 固定傳 `null`（總部行為，與 `DISCOUNT_SAVE` 一致）。

### 6.3 建立訂單

`POST /api/orders` 的**請求不變** —— 品項促銷是自動套用的，沒有任何新的請求欄位。回應多 `itemDiscountAmount`、`itemPromotion`，以及每個 `items[]` 的 `discountAmount`。

### 6.4 錯誤碼

| 狀態 | 情境 |
| --- | --- |
| 400 | §6.2 的驗證失敗 |
| 401 | 未登入 |
| 403 | 非總部（含有 `MENU_MANAGE` 但 `branch` 級的店長） |
| 404 | 目標商品／分店不存在 |

**建立訂單不會因為促銷而失敗。** 促銷是減價，沒有「促銷不適用」這種錯誤 —— 不命中就是折 0，不回任何錯誤碼。

---

## 7. 權限與資料範圍

### 7.1 複用 `MENU_MANAGE` + `global()`，不新增權限常數

與 `DiscountService.requireHeadquarters()`（`DiscountService.java` 末段）完全一致：

```java
private static void requireHeadquarters(Actor actor) {
  actor.require("MENU_MANAGE");
  if (!actor.global()) throw new Problem(403, "只有總部可以維護品項促銷");
}
```

**理由：** 品項促銷與優惠碼是同一類東西（總部定的價格政策），資料範圍也一樣是 GLOBAL。新增一個 `PROMOTION_MANAGE` 會動到 `Identity.PERMISSIONS` 與角色驗證規則，那是跨模組的共用清單，撞號成本高於收益。

**推翻的代價：** 若日後要讓店長自訂本店促銷（參考 G24 的做法），就需要 `PROMOTION_MANAGE` 並把 `branch_id` 的寫入權限下放。屆時 `item_promotions.branch_id` 已經在表上，所以是純權限層的加法，不用改 schema。

### 7.2 `apply` 不做授權檢查

它是 `OrderService.create` 授權完成後的內部計價步驟。顧客與店員都不能指定要套哪一條規則（沒有這個請求欄位），所以沒有越權面。

### 7.3 顧客端

顧客看得到自己訂單上的 `itemPromotion` 與品項 `discountAmount`（那是他自己的收據）。顧客**不能**呼叫 `GET /api/promotions`（403）—— 規則主檔含成本敏感的全鏈資訊與未啟用的未來促銷。

---

## 8. 金額規則

1. 一律新台幣**整數元**。所有乘法 `Math.multiplyExact`、加減 `Math.addExact` / `Math.subtractExact`。
2. 折抵**逐單位**計算、逐單位向下取整（`unitPrice * percent / 100`），不做分攤補差。見 §13.9。
3. 後端完全重算，忽略前端送來的任何金額欄位。請求裡本來就沒有促銷欄位（§6.3），所以這一條自動成立。
4. 快照：`order_item_promotions` 記下規則當時的 `percent` / `nth` / 目標，之後改規則**不回寫歷史**。
5. **恆等式**（要有測試釘住）：

   ```
   orders.discount_amount    = orders.item_discount_amount + coalesce(order_discounts.discount_amount, 0)
   orders.item_discount_amount = sum(order_items.discount_amount)
   orders.total              = 定價毛額小計 − orders.discount_amount
   Order.subtotal（API）      = orders.total + orders.discount_amount = 定價毛額小計
   ```

6. **`orders.total` 永遠 ≥ 1 元的證明**（V1 的 `CHECK(total>0)` 不得修改，所以這一條是硬需求）：

   - 既有保證：`OrderService.java:77` 已經擋下 `unitPrice <= 0`，所以每個單位的價格 ≥ 1。
   - `ITEM_PERCENT`：`percent ≤ 90`，故每單位折抵 `floor(price*90/100) ≤ price − 1`（price=1 時折 0），每單位至少留 1 元 → `afterItems ≥ 單位數 ≥ 1`。
   - `NTH_PERCENT`：`nth ≥ 2`，故被折單位數 `k = floor(u/nth) ≤ u/2 < u`，至少有一個命中單位完全不被折，它的價格 ≥ 1 → `afterItems ≥ 1`。即使 `percent = 100` 也成立。
   - 訂單層優惠碼：`DiscountService.calculate()` 已經把折抵夾在 `min(raw, subtotal − 1)`，而本規格傳給它的 `subtotal` 就是 `afterItems ≥ 1` → `codeDiscount ≤ afterItems − 1` → `total = afterItems − codeDiscount ≥ 1`。

   **`DiscountService.calculate()` 不需要任何修改** —— 它的夾擠邏輯在新的輸入下自然成立。這是把品項層排在前面（§13.3）的附帶好處。

---

## 9. 施工階段

四個階段，每一階段獨立 CI 綠、獨立可合併、有自己的驗收子集。

### S1 — 資料層與總部維護端點（規模：中）

- `V14__item_promotions.sql`
- `coffee-catalog/api/Promotions.java`（只放 `Rule`、`Line`、`Applied` record 與 `list`／`save`／`apply` 的簽章；`apply` 先回 `null` 的實作留到 S2，**或**本階段直接 `throw new UnsupportedOperationException` 亦可，因為還沒有人呼叫）
- `PromotionService.list` / `save`（含 §6.2 全部驗證與 `PROMOTION_SAVE` 稽核）
- `PromotionController`
- 測試：維護的正常路徑、§6.2 每一條驗證、越權（店長 403、顧客 403、未登入 401）、migration 可重跑

**本階段零計價影響** —— `OrderService` 完全沒改，既有行為不可能退化。
**驗收子集：** 1–8、19、20。

### S2 — 計價演算法（規模：中，純函式 + 單元測試）

- `PromotionService.apply` 完整實作（§5.2）
- **不需要 Spring context 的單元測試**，放 `backend/coffee-app/src/test/java/com/coffee/catalog/internal/ItemPromotionCalculationTest.java`（與既有的 `DiscountCalculationTest.java` 同一個位置與風格）

把演算法抽成可直接測的靜態方法，規則讀取留在 `apply` 裡：

```java
static Applied evaluate(Rule rule, List<Line> lines);   // 第 2 步
static Applied best(List<Rule> rules, List<Line> lines); // 第 3 步
```

- 測試矩陣至少要蓋：§11.1 的 (a)–(i) 九組
- **本階段仍然沒有任何呼叫端**，`OrderService` 不動 → 既有行為不可能退化

**驗收子集：** 9–13。

### S3 — 訂單整合與前端守門（規模：中大，四個裡最大的）

- `OrderService` 建構子注入 `Promotions`，`create()` 依 §5.3 接線
- `insert into orders` / `insert into order_items` 加欄位，`order_item_promotions` 快照，`ORDER_ITEM_DISCOUNT` 稽核
- `Orders.Order` / `Orders.Line` 依 §5.6 擴充，`promotionSnapshot()`
- `checkout.ts` 依 §5.7(a) 改為一律兩階段 + `checkout.spec.ts` 調整
- 測試：§11.1 的 (j)–(n)

**驗收子集：** 14–18、21–24。

### S4 — 前端維護頁與顯示（規模：中）

- `PromotionsView.vue`、`App.vue` 導覽、`shared/types.ts`
- 購物車四行金額、`OrdersView.vue` 品項折抵列
- DOM 測試（§11.2）

**驗收子集：** 25–28。

### 階段切分的理由

- **S1→S2→S3 是「資料 → 計算 → 接線」。** 前兩階段都沒有呼叫端，所以「獨立可合併且不破壞既有行為」是數學上成立的，不是靠測試運氣。
- **`checkout.ts` 的兩階段守門為什麼綁在 S3 而不是 S4：** S3 一合併，總部就可能建立規則並讓折抵真的發生。若此時前端還在用 `POS_CASH_SINGLE` 自行算應收，店員會收到定價毛額（多收錢）。這是本規格唯一一個「前後端必須同一階段落地」的點，不可以為了階段漂亮而拆開。
- S1 與 S2 之所以沒有合成一個階段，是因為 S2 的測試矩陣（九組）本身就是一次執行的份量，而它完全不依賴 S1 的 controller 是否寫好 —— 真的有餘裕時兩階段一次推完也沒問題，但不要為了湊成一個階段而壓縮測試。

---

## 10. 驗收條件

可逐條勾選。括號標示所屬階段。

- [ ] 1. `V14__item_promotions.sql` 建立兩張表與兩個 `ALTER TABLE`，`./mvnw -B -ntp verify` 綠（S1）
- [ ] 2. 既有 migration 檔一字未改（S1）
- [ ] 3. 總部可 `POST /api/promotions` 新增 `ITEM_PERCENT` 規則並由 `GET` 讀回（S1）
- [ ] 4. 總部可新增 `NTH_PERCENT` 規則（`nth=2`、`percent=100`，即買一送一）（S1）
- [ ] 5. `kind=ITEM_PERCENT` 且 `percent=91` → 400『每件折扣的百分比需為 1–90』（S1）
- [ ] 6. `kind=NTH_PERCENT` 且 `nth=1` → 400『第 N 件折扣的 N 需大於或等於 2』（S1）
- [ ] 7. `targetKind=PRODUCT` 但 `productId` 不存在 → 404（S1）
- [ ] 8. `startsAt > endsAt` → 400（S1）
- [ ] 9. `ITEM_PERCENT` 九折、單價 55、數量 3 → 折抵 15（每單位 floor(55*10/100)=5）（S2）
- [ ] 10. `NTH_PERCENT` `nth=2` `percent=100`、單價 60、數量 4 → 折抵 120（折 2 個單位）（S2）
- [ ] 11. `NTH_PERCENT` `nth=2`、數量 1 → 折抵 0，`apply` 回 `null`（S2）
- [ ] 12. `NTH_PERCENT` 跨兩列同分類（單價 60 × 1、單價 100 × 1，`nth=2` `percent=100`）→ 折抵 60，折在**便宜的那一列**（S2）
- [ ] 13. 兩條規則同時命中時，取折抵較大的那一條；折抵相同時取 `id` 字典序較小的那一條（S2）
- [ ] 14. 建立訂單（無優惠碼）命中 `NTH_PERCENT` → `orders.item_discount_amount` 等於折抵、`orders.discount_amount` 等於同一個值、`orders.total` = 毛額 − 折抵（S3）
- [ ] 15. 同一張訂單同時有品項促銷與優惠碼 → 優惠碼的折抵依 `afterItems` 計算，`order_discounts.subtotal` 存的是 `afterItems`（S3）
- [ ] 16. `sum(order_items.discount_amount)` 等於 `orders.item_discount_amount`（S3）
- [ ] 17. `GET /api/orders/{id}` 的 `subtotal` 仍等於**定價毛額小計**，`items[].lineTotal` 仍是毛額、折抵在 `items[].discountAmount`（S3）
- [ ] 18. 套用促銷時寫入一筆 `ORDER_ITEM_DISCOUNT` 稽核，summary 含促銷名稱與折抵金額（S3）
- [ ] 19. 店長（`MENU_MANAGE` 但 `branch` 級）`POST /api/promotions` → 403（S1）
- [ ] 20. 顧客 `GET /api/promotions` → 403；未登入 → 401（S1）
- [ ] 21. 沒有任何 `item_promotions` 列時，建立訂單的金額與本規格之前**完全相同**（既有測試全綠即為滿足）（S3）
- [ ] 22. 停用（`active=false`）、未開始（`startsAt > now`）、已結束（`endsAt < now`）、他店專屬（`branch_id` 非本店）的規則都不套用（S3）
- [ ] 23. 促銷**不改變**訂單數量與 G08 的備量扣減筆數（S3）
- [ ] 24. `checkoutPath()` 對 POS 現金（無論有無優惠碼）一律回 `POS_CASH_TWO_STAGE`（S3）
- [ ] 25. `PromotionsView.vue` 可建立／停用規則，只對總部顯示在導覽上（S4）
- [ ] 26. 購物車在建立訂單後顯示「小計／品項促銷折抵／優惠碼折抵／應收」四行，數字與後端回傳一致（S4）
- [ ] 27. `OrdersView.vue` 的品項列在 `discountAmount > 0` 時顯示折抵列，等於 0 時不顯示（S4）
- [ ] 28. `npm run build` 與 `npm test` 綠（S4）

---

## 11. 測試要求

### 11.1 後端

**不需要 Spring context 的單元測試（S2，放 `com.coffee.catalog.internal.ItemPromotionCalculationTest`）：**

(a) `ITEM_PERCENT` 單列多數量的逐單位取整
(b) `ITEM_PERCENT` 跨列（同分類兩個不同商品）
(c) `NTH_PERCENT` `nth=2` 數量 4 → 折 2 個
(d) `NTH_PERCENT` `nth=3` 數量 7 → 折 2 個（`7/3=2`）
(e) `NTH_PERCENT` 數量不足 `nth` → 折 0
(f) `NTH_PERCENT` 跨列時折最便宜的單位（含「同價時取 `lineIndex` 較小者」）
(g) 不命中任何列 → 折 0
(h) 多條規則擇優 + 同額時 `id` 字典序決勝
(i) `percent=100` + `nth=2` 時 `afterItems ≥ 1`（§8 證明的回歸測試）

**整合測試（S3，放 `com.coffee.app`，新檔 `ItemPromotionOrderTest`）：**

(j) 建立訂單命中促銷 → 四個恆等式（§8 第 5 點）全部成立
(k) 促銷 + 優惠碼並存的計算順序與 `order_discounts.subtotal`
(l) 停用／未開始／已結束／他店規則不套用
(m) 稽核 `ORDER_ITEM_DISCOUNT` 寫入
(n) 同一個 `Idempotency-Key` 重送 → 回同一張訂單，不重複折抵、不重複寫 `order_item_promotions`

**越權測試（S1，放 `com.coffee.app`，可併入 `ItemPromotionAdminTest`）：**

- 店長 `POST` → 403
- 顧客 `GET` / `POST` → 403
- 未登入 → 401
- 總部 `POST` 他店專屬規則（`branchId` 指定某分店）→ 200（總部本來就能跨店）

**時間：** 任何需要「現在」的測試一律注入固定的 epoch 毫秒，**不得使用無參數的 `now()`** —— `TimeZoneGuardTest`（G27）會掃出來，CI 直接紅。

### 11.2 前端

- `checkout.spec.ts`：驗收 24；既有的 `POS_CASH_SINGLE` 案例改為直接呼叫 `amountDue()`（§5.7(a)）
- 新增 `PromotionsView.dom.test.ts`：規則清單渲染、型態下拉切換時 `nth` 欄位的顯示／隱藏
- `OrdersView` 的品項折抵列：驗收 27（DOM 測試，`discountAmount` 為 0 與大於 0 各一條）

### 11.3 不要做的事

- 不要為了讓測試好寫而放寬 `CHECK` 或改既有 migration
- 不要在 `item_promotions` 加 `redeemed_count` 之類的計數欄位（§13.8）
- 不要在 `order_items` 以外的地方再存一份品項折抵（§4.3）
- 不要改 `ReportService`（§13.11）

---

## 12. 與其他工作的並行注意

- **Flyway V14 由本規格占用。** 下一份需要 migration 的規格自 V15 起算。
- **動到的既有後端檔案：** `OrderService.java`（S3）、`Orders.java`（S3）。本規格產出時主線沒有任何 open PR，所以衝突風險是零；開工前仍要先 `git pull` 主線。
- **動到的既有前端檔案：** `checkout.ts`、`checkout.spec.ts`（S3）、`App.vue`、`shared/types.ts`、`OrdersView.vue`、`MenuView.vue`（S4）。`MenuView.vue` 剛被 G08／G08a 改過（剩餘徽章），**本規格不碰徽章區塊**，只在購物車金額區加列。
- 本規格**不碰** `Identity.java`、`CatalogService.java` 的可售判定、`branch_product_stock` 相關邏輯。

---

## 13. 設計決策

每一項都是 Claude（PM/SA）定案，附理由與推翻它的代價。決策是給下一輪推翻用的。

### 13.1 只做兩種規則型態 —— **`ITEM_PERCENT` + `NTH_PERCENT`**

**決定：** 不做獨立的「買一送一」「買二送一」「第二件半價」型態，全部表達成 `NTH_PERCENT(nth, percent)`。

| 促銷 | 參數 |
| --- | --- |
| 買一送一 | `nth=2, percent=100` |
| 買二送一 | `nth=3, percent=100` |
| 第二件半價 | `nth=2, percent=50` |
| 第二件七折 | `nth=2, percent=30` |
| 全品項九折 | `ITEM_PERCENT, percent=10` |

**理由：** 四種行銷說法是同一條數學式的參數組合。做成四個 `kind` 會有四條演算法分支、四組測試、四個前端下拉選項，而它們之間不可能有行為差異。`percent` 語意統一為「折掉的百分比」（九折 = `percent=10`），避免「折扣率」與「付款率」混用。

**推翻的代價：** 若要加「滿額贈指定商品」這種非百分比型態，`kind` 加列舉值 + 一條演算法分支 + 可能的新參數欄位。表與接線點不動，是加法。

### 13.2 一張訂單最多套用一條品項層規則 —— **擇優取一**

**決定：** 試算所有命中的規則，只套折抵最大的那一條。

**理由：** 三層。(1) 多規則疊加會變成「哪些單位已被哪條規則消耗」的配置問題，最佳解是 NP-hard 的變體，近似解則讓收據無法解釋；(2) 收據上寫「本單套用：第二件半價」是店員與顧客都能對帳的；寫「套用三條規則，合計折抵 87 元」則不能；(3) 擇優取一對顧客永遠不比「只套第一條」差，所以不會有客訴。

**推翻的代價：** 要定義規則優先序、單位消耗模型與疊加上限，測試矩陣從九組長到數十組，`order_item_promotions` 的 PK 要從 `order_id` 改成 `(order_id, promotion_id)`（這一項是破壞性變更）。

### 13.3 品項層先算，訂單層優惠碼算在已折抵的小計上 —— **品項先**

**決定：** `codeDiscount = DiscountService.calculate(rule, subtotal − itemDiscount)`。

**理由：** 三層。(1) 優惠碼的 `min_subtotal`（最低消費）應該對「實際買了多少錢的東西」設限，不是對定價毛額；反過來會讓促銷商品湊出不該成立的最低消費；(2) `DiscountService.calculate()` 既有的 `min(raw, subtotal − 1)` 夾擠在這個順序下**自動**保住 `orders.total ≥ 1`，一行程式都不用改（§8 第 6 點）；反過來排則要重新證明並可能要改 `calculate`，而那會動到 G07 已經合併的程式；(3) 對顧客來說品項先算的總折抵較小 —— 這是刻意的，兩種促銷本來就不該複利疊加。

**推翻的代價：** 要改 `DiscountService.calculate()` 的夾擠邏輯並重新證明 `total ≥ 1`，同時 `order_discounts.subtotal` 的語意又要改一次。

### 13.4 `NTH_PERCENT` 折最便宜的單位 —— **折最便宜的**

**決定：** 命中單位依單價升冪排序，前 `k` 個被折。

**理由：** 台灣零售的通行做法（「送最便宜的那一杯」），顧客預期如此，也讓店家的讓利可預測。若折最貴的，一張混買的訂單折抵會比顧客預期高出一截，且促銷成本無法估算。

**推翻的代價：** 把比較器反向即一行，但要同步改驗收 12 與測試 (f)，並且改的是對顧客不利的方向 —— 上線後再改會變成客訴來源。

### 13.5 不自動把贈品加進購物車 —— **數量由顧客決定**

**決定：** 「買一送一」不會自動把數量從 1 變成 2。顧客（或店員）必須自己把數量設成 2，系統才會折第二杯。

**理由：** 自動加品項會連帶改變 G08 的備量扣減（`reserveStock` 的數量）、G06 的選項解析（贈的那一杯要用哪個選項組合？）與購物車 UI 的可編輯性。三個都是為了一個行銷話術而承擔的真實複雜度。

**已知且刻意接受的後果：** 顧客只買 1 杯時看不到任何提示。這一半由 §13.12 登記為後續缺口。

**推翻的代價：** 需要定義贈品的選項解析規則（最便宜？與原品相同？）、備量是否扣贈品、以及購物車上贈品列能不能被刪除。那是一份新規格的份量。

### 13.6 自動套用，不需輸入代碼 —— **自動**

**決定：** 品項促銷沒有 `code` 欄位，建立訂單的請求也沒有任何促銷欄位。

**理由：** 品項促銷是貨架促銷（看板上寫著「第二杯半價」），不是給特定對象的優惠券。要店員記住並輸入代碼才會生效，等於把營收交給記憶力。優惠券的語意已經由 G07 的 `discounts.code` 承擔，兩者刻意分開。

**推翻的代價：** 加一個 `code` 欄位與請求欄位是加法，但同時要決定「有代碼的品項促銷是否仍自動套用」，那是新的語意分叉。

### 13.7 複用 `MENU_MANAGE` + `global()` —— **不新增權限常數**

見 §7.1（決定、理由、推翻的代價都寫在那裡）。

### 13.8 不設使用次數上限 —— **不設**

**決定：** `item_promotions` 沒有 `max_redemptions` / `redeemed_count`。

**理由：** 兩層。(1) 次數上限的語意是「限量優惠券」，而貨架促銷是「這段期間都這樣賣」—— 用 `starts_at` / `ends_at` 表達就夠；(2) 計數欄位必須在建立訂單的交易裡 `select ... for update` 加鎖（`DiscountService.apply` 就是這樣做的），那會讓**每一張訂單**都去搶同一條促銷規則的行鎖 —— 優惠碼只有輸入代碼的訂單才搶，貨架促銷是每一張都搶，是尖峰時段的序列化點。

**推翻的代價：** 加兩個欄位與一次 `for update`，但要同時承擔上面第 (2) 點的鎖競爭，並決定「超過上限時是擋下訂單還是改為不折」（擋下訂單會讓顧客在結帳時被拒，很糟）。

### 13.9 逐單位向下取整，不做分攤補差 —— **逐單位 floor**

**決定：** 每個被折單位各自 `floor(unitPrice * percent / 100)`，不在列或訂單層做尾差補償。

**理由：** 與 `DiscountService.calculate()` 的既有做法一致（它也是 `subtotal * percent / 100` 整數除法）。逐單位取整讓收據上「每一杯折了多少」是可印出、可驗算的；若在訂單層算完再分攤回列，就會出現 1 元的尾差要指派給某一列，而那個指派規則本身沒有自然的答案。向下取整永遠對店家有利 1 元以內，不會出現折超過的情形。

**推翻的代價：** 要定義尾差的指派規則並補一組邊界測試，收益是顧客多折 0–N 元（N = 列數），不值得。

### 13.10 POS 現金一律兩階段，前端不再自行算應收 —— **一律兩階段**

**決定：** `checkoutPath()` 對 POS 現金一律回 `POS_CASH_TWO_STAGE`，即先建立訂單取得後端 `total`，再收現金。

**理由：** 三層。(1) `AGENTS.md`「不得在前端信任任何金額」—— `POS_CASH_SINGLE` 用前端購物車小計當應收，在促銷依數量浮動之後必然算錯（多收錢）；(2) 促銷是**自動**套用的（§13.6），前端無法從「使用者有沒有輸入優惠碼」預判金額會不會變，所以沒有可靠的縮小條件；(3) 代價只是多一次 round trip，而且兩階段的機制在 `checkout.ts` 已經存在（優惠碼路徑本來就走它），不是新寫的程式。

**推翻的代價：** 要新增一支「試算」端點，把 `OrderService.create` 的整段計價在不建立訂單的前提下重跑一次。那段邏輯重複實作就會與正式路徑漂移，而漂移的症狀是「畫面上的錢與實收的錢不一樣」—— 這一類缺陷比多一次 round trip 嚴重得多。

### 13.11 報表不改，品項營收維持定價毛額 —— **不改**

**決定：** `ReportService` 一行不動。商品層營收繼續是 `sum((unit_price+options_price)*quantity)`（毛額），折抵繼續只在訂單層的 `sum(o.discount_amount)` 出現（這一項因為 §5.5 會自動含品項折抵）。

**理由：** 現況對 G07 的訂單層折扣就是這樣（PR #31 沒有改報表），本規格若只把品項折抵淨掉，商品層營收會變成「淨於品項促銷、毛於優惠碼」—— 一個解釋不出來的混合值。要做就要連 G07 的訂單層折扣一起分攤到品項，而「訂單層折扣怎麼分攤到品項」本身是一個獨立的會計決策。

**推翻的代價：** 需要定義訂單層折扣的分攤規則（依毛額比例？依毛利比例？）、決定是否回寫歷史訂單，並重做 `ReportAggregationTest` 的期望值。登記為後續（§14）。

### 13.12 菜單不顯示促銷徽章 —— **不顯示**

**決定：** `GET /api/menu` 不回促銷資訊，商品卡上沒有「第二杯半價」徽章。

**理由：** 兩層。(1) 促銷是數量條件的（`nth=2` 在只買一杯時不成立），徽章掛在單張商品卡上會在條件未滿足時誤導；(2) 要正確顯示就要新增一支促銷列表端點並在前端做條件判定，那是 S5 的份量，而本規格已經四個階段。

**已知後果：** 顧客只買一杯時不知道有促銷（§13.5 的同一個缺口）。

**推翻的代價：** 加一支 `GET /api/promotions/active?branchId=`（顧客可讀，只回已啟用且在期間內的規則，不含成本資訊），前端在商品卡與購物車顯示。純加法，登記為後續（§14）。

### 13.13 規則主檔不做刪除，只做停用 —— **只停用**

**決定：** 沒有 `DELETE /api/promotions/{id}`，`active=false` 即為下架。

**理由：** `order_item_promotions` 存的是快照，不依賴主檔，所以刪除在技術上是安全的 —— 但稽核 `PROMOTION_SAVE` 的 `target_id` 會指向一個不存在的 id，查稽核時就斷線了。與 `discounts` 的既有做法一致（它也只有 `active`，沒有刪除端點）。

**推翻的代價：** 加一支軟刪除端點並在稽核查詢端處理孤兒 id。

---

## 14. 登記給後續的缺口

| 編號 | 內容 | 來源 |
| --- | --- | --- |
| **G20a** | 菜單與購物車的促銷提示（「加一杯第二件半價」）。需要 `GET /api/promotions/active` 顧客端端點 | §13.5、§13.12 |
| **G20b** | 多規則疊加與單位消耗模型 | §13.2 |
| **G20c** | 報表的淨營收歸屬（含 G07 訂單層折扣的分攤規則） | §13.11 |
| **G20d** | 選項層促銷（加料免費、第二份加料半價） | §2.2 |
| G21 | 會員價與員工價 | G07 §11.2；開工前提是 G16（顧客自助註冊），卡在 PO |

這四項都**不計入規格庫存**，也都不是本規格的驗收條件。登記的目的是讓下一輪不用重新推導。

---

## 15. 版本紀錄

| 日期 | 版本 | 變更 |
| --- | --- | --- |
| 2026-10-04 | v1.0 | 初版。依 G07 §11.2 的登記產出，並在 §1.4 說明為什麼推翻該節「等真實資料」的閘門 |
