# G07 — 訂單折扣與優惠碼

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G07 |
| 優先順序 | P1 |
| 版本 | v1.3（2026-09-26） |
| 規格作者 | Claude（PM / SA） |
| 實作 | Codex（PG / SD） |
| 前置相依 | G06（選項加價）已合併、G10（訂單分頁）已合併。**依「一次一份」排在 G14 之後** |
| Flyway 版號 | **V9**（V8 已由 G14 規格指定，見 §4.0） |
| 施工階段 | S1 / S2 / S3，三階段，S1／S2 純加法（v1.1 把次數上限併入 S1，見 §9.0） |

---

## 1. 背景與目標

### 問題

系統目前**沒有任何折扣機制**。`OrderService.create()`（`backend/coffee-orders/src/main/java/com/coffee/orders/internal/OrderService.java:63-75`）的金額計算是純加總：

```java
int unitPrice = Math.addExact(p.price(), optionPrice);
total = Math.addExact(total, Math.multiplyExact(unitPrice, l.quantity()));
```

`orders.total` 之後不再變動。沒有優惠券、沒有活動折扣、沒有任何可以讓一張訂單以低於定價成交的路徑。

這造成三個實際後果：

1. **做不了最基本的促銷。** 開幕折扣、雨天折扣、滿額折抵這些咖啡廳每週都在用的活動，目前只能靠「臨時改菜單售價」來模擬 —— 改了就是全鏈全時段都改，活動結束還要記得改回來，而且會污染歷史訂單的比較基準。
2. **現場被迫在系統外處理。** 店員遇到「這杯算你便宜 20」只能收現金時自行少收，系統記的 `total` 與抽屜裡的錢對不起來 —— 這正是 G15 現金日結要抓的短溢，會被誤判成店員短收。
3. **報表看不到折扣成本。** `ReportService.report()` 的 `grossProfit = revenue - cost`，折扣若不進系統，毛利數字是假的。

### 目標

- 讓總部能建立「優惠碼」型的折扣規則（百分比或定額），設定適用分店、有效期間、最低消費與使用次數上限
- 讓下單時帶一組優惠碼，**折抵金額完全由後端依規則重算**，前端送來的任何金額欄位一律忽略
- 把折扣結果**快照**進訂單，之後改規則或停用規則都不回寫歷史
- 折扣動作進稽核軌跡（G11 已建好 `coffee-audit`，直接用）
- 報表顯示當月折扣總額，讓 `revenue`（折後實收）與品項原價營收的差額可以被解釋

### 不是目標

折扣是整個系統裡最容易寫出「信任前端金額」漏洞的地方，所以本規格刻意把範圍壓到**一張訂單最多一個、只作用在訂單小計、只由優惠碼觸發**的最小形狀。理由與被排除項目的登記見 §11。

---

## 2. 範圍

### 在範圍

1. `discounts` 折扣規則主檔：`PERCENT`（百分比）與 `AMOUNT`（定額）兩種，作用於**整張訂單的小計**
2. 適用條件：啟用旗標、有效期間（起／迄，可不限）、適用分店（全鏈或單店）、最低消費門檻、使用次數上限
3. 維護 API 與總部 UI（沿用 `MENU_MANAGE` + 總部範圍，**不新增權限常數**，見 §11.4）
4. 下單時以優惠碼套用折扣，後端重算
5. `order_discounts` 快照表 + `orders.discount_amount` 欄位
6. 稽核：`DISCOUNT_SAVE`（維護規則）、`ORDER_DISCOUNT`（訂單套用）
7. 報表新增當月折扣總額
8. 使用次數上限與兌換計數（含併發）

### 不在範圍

| 被排除的項目 | 去向 |
| --- | --- |
| 品項層折扣、買一送一、第二件半價 | **G20**（§11.2 登記） |
| 會員等級價、員工價、生日優惠 | **G21**（§11.2 登記） |
| 多重折扣疊加與優先序 | 不做，見 §11.1 |
| 每人限用一次／限用名單 | 不做，見 §11.7 |
| 折扣後金額為 0 的免費訂單 | 不做，見 §11.5 |
| 下單前的「折抵金額預覽」端點 | 不做，見 §11.6 |
| 訂單取消時退還兌換次數 | 不做，見 §11.8 |

---

## 3. 涉及模組與邊界

| 模組 | 變更 |
| --- | --- |
| `coffee-catalog` | **新增** `api/Discounts.java`（interface + record）、`internal/DiscountService.java`、`internal/DiscountController.java` |
| `coffee-orders` | `api/Orders.java` 的 `Create` 與 `Order` record 加欄位；`internal/OrderService.java` 的 `create()` 與讀路徑 |
| `coffee-reporting` | `internal/ReportService.java` 加一個彙總欄位 |
| `coffee-app` | Flyway `V9__order_discounts.sql` |
| `frontend` | `modules/catalog/DiscountsView.vue`（新）、`modules/ordering/MenuView.vue`、`modules/ordering/OrdersView.vue`、`modules/reporting/ReportsView.vue`、`shared/types.ts`、`main.ts` 路由 |

### 3.1 為什麼折扣放 `coffee-catalog`，不開新模組

`coffee-orders` 已經依賴 `coffee-catalog.api`（`OrderService` 建構子注入 `Catalog`）。折扣規則放進 `coffee-catalog` 等於**不新增任何一條跨模組的邊**，也不可能製造循環。反之開一個 `coffee-promotions` 模組，要改 `backend/pom.xml`、`coffee-app/pom.xml`、`ModuleBoundariesTest` 的模組清單，把 S1 撐大一倍，換到的隔離價值是零 —— 折扣本來就是定價規則，與售價、選項加價同一類。

**但介面刻意獨立成 `Discounts`，不塞進 `Catalog`。** 這是為了日後真的要拆 `coffee-promotions` 時，可以把 `Discounts.java` 整個檔案搬過去，呼叫端一行都不用改。

### 3.2 邊界規則（`ModuleBoundariesTest` 會驗）

- `coffee-orders` 只能引用 `com.coffee.catalog.api.Discounts`，**不得**引用 `DiscountService` 或 `DiscountController`
- `coffee-catalog` **不得**引用 `com.coffee.orders.*`（會造成循環）。兌換計數是 `coffee-catalog` 自己管的欄位，由 `coffee-orders` 在下單交易裡呼叫 `Discounts` 的方法遞增，不是 `coffee-catalog` 回頭去讀 `orders`
- `coffee-reporting` 維持唯讀投影例外，直接查 `orders.discount_amount`

---

## 4. DB schema 與 migration

### 4.0 版號

**檔名 `V9__order_discounts.sql`。** V7（`order_list_indexes`）已隨 PR #26 進入主線，V8 由 G14 規格指定（`docs/specs/G14-branch-business-hours.md`）。

**如果開工時主線上還沒有 V8**（代表 G14 尚未合併），**仍然用 V9，不要改用 V8。** Flyway 預設不允許補插較小版號的 migration（out-of-order），若 G07 先占走 V8，G14 之後就無號可用而且會卡住既有資料庫。空一個版號的成本是零。

### 4.1 `discounts` —— 折扣規則主檔

```sql
CREATE TABLE discounts(
  id VARCHAR(36) PRIMARY KEY,
  code VARCHAR(20) NOT NULL UNIQUE,
  name VARCHAR(40) NOT NULL,
  kind VARCHAR(8) NOT NULL CHECK(kind IN ('PERCENT','AMOUNT')),
  percent INTEGER NOT NULL DEFAULT 0 CHECK(percent>=0 AND percent<=90),
  amount INTEGER NOT NULL DEFAULT 0 CHECK(amount>=0),
  min_subtotal INTEGER NOT NULL DEFAULT 0 CHECK(min_subtotal>=0),
  branch_id VARCHAR(36) REFERENCES branches(id),
  starts_at BIGINT,
  ends_at BIGINT,
  max_redemptions INTEGER,
  redeemed_count INTEGER NOT NULL DEFAULT 0 CHECK(redeemed_count>=0),
  active BOOLEAN NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  CHECK(max_redemptions IS NULL OR max_redemptions>0)
);
CREATE INDEX idx_discounts_code_active ON discounts(code, active);
```

欄位語意：

| 欄位 | 語意 |
| --- | --- |
| `code` | 優惠碼，**一律存大寫**，`[A-Z0-9-]{4,20}`。`UNIQUE` 由 DB 保證 |
| `kind` | `PERCENT` 用 `percent`、`AMOUNT` 用 `amount`。另一個欄位必須是 0（§5.4 驗證） |
| `percent` | 折扣百分比，1–90。**上限 90 是刻意的**，見 §11.5 |
| `amount` | 定額折抵的元數，≥ 1 |
| `min_subtotal` | 最低消費門檻（小計），0 = 不限 |
| `branch_id` | `NULL` = 全鏈通用；有值 = 只有該分店的訂單可用 |
| `starts_at` / `ends_at` | epoch millis（UTC），`NULL` = 該端不限。判定用**閉區間**：`starts_at <= now <= ends_at` |
| `max_redemptions` | 總使用次數上限，`NULL` = 無上限 |
| `redeemed_count` | 已使用次數，只增不減（§11.8） |
| `active` | 停用後立即不可用，但**不影響已成立的訂單**（快照） |

**`max_redemptions` 與 `redeemed_count` 在 S1 就一次建好，而且 S1 同時就要實作次數檢查與計數遞增**（§5.2 第 6、8 步）。v1.0 把這兩步排到最後一階段，那是錯的：S2 的維護 UI 一開放就能設 `max_redemptions`，若強制邏輯還沒進去，總部設了上限卻無限可用、`redeemed_count` 永遠是 0 —— 一個「設定看得到但不生效」的中間狀態比沒有這個欄位糟得多。**能設定與能強制必須同一階段落地，而且強制要先到。**

### 4.2 `order_discounts` —— 訂單折扣快照

```sql
CREATE TABLE order_discounts(
  order_id VARCHAR(20) PRIMARY KEY REFERENCES orders(id),
  discount_id VARCHAR(36) NOT NULL,
  code VARCHAR(20) NOT NULL,
  name VARCHAR(40) NOT NULL,
  kind VARCHAR(8) NOT NULL,
  percent INTEGER NOT NULL,
  amount INTEGER NOT NULL,
  subtotal INTEGER NOT NULL,
  discount_amount INTEGER NOT NULL CHECK(discount_amount>=0),
  created_at BIGINT NOT NULL
);
```

- **`order_id` 就是主鍵** —— 一張訂單最多一個折扣（§11.1），由資料表形狀強制，不是靠程式紀律
- `discount_id` **刻意不加 FK**。快照的意義是「當時長這樣」，規則列日後被刪除不應該連帶擋住歷史訂單
- `subtotal` 是折扣前的小計，`discount_amount` 是實際折抵。`subtotal - discount_amount` 必須等於 `orders.total`（驗收 11）

### 4.3 `orders` 加一欄

```sql
ALTER TABLE orders ADD COLUMN discount_amount INTEGER NOT NULL DEFAULT 0;
```

**不新增 `subtotal` 欄位。** 小計 = `total + discount_amount`，可以在 API 層算出來。既有的每一列 `discount_amount` 都會是 0，小計自動等於 `total` —— **既有資料零遷移，且語意正確**。多一個可空的 `subtotal` 欄位反而要處理「舊列是 NULL」的分支。

`orders.total` 的 `CHECK(total>0)` 來自 V1，**不得修改**（`AGENTS.md` 禁止事項第 6 條）。折後金額因此必須 ≥ 1 元，這是 §11.5 的來源。

---

## 5. 折扣計算（本規格的核心，實作請照抄語意）

### 5.1 `Discounts` interface（`coffee-catalog/src/main/java/com/coffee/catalog/api/Discounts.java`）

```java
package com.coffee.catalog.api;

import com.coffee.shared.Actor;
import java.util.List;

public interface Discounts {
  record Rule(
      String id,
      String code,
      String name,
      String kind,
      int percent,
      int amount,
      int minSubtotal,
      String branchId,
      Long startsAt,
      Long endsAt,
      Integer maxRedemptions,
      int redeemedCount,
      boolean active) {}

  /** 套用結果的快照。discountAmount 是後端算出來的實際折抵元數。 */
  record Applied(
      String discountId,
      String code,
      String name,
      String kind,
      int percent,
      int amount,
      int subtotal,
      int discountAmount) {}

  List<Rule> list(Actor a);

  Rule save(Actor a, Rule rule);

  /**
   * 依優惠碼與訂單條件算出折抵金額。必須在呼叫端的交易內執行：本方法會鎖定規則列並遞增
   * redeemed_count。code 為 null 時回傳 null（代表這張訂單沒有折扣）。
   */
  Applied apply(String code, String branchId, int subtotal, long atEpochMs);
}
```

`apply()` **刻意不收 `Actor`**：下單時的授權已經在 `OrderService.create()` 做完（`ORDER_CREATE`、分店範圍），折扣本身沒有額外的資料範圍問題 —— 碼適用哪家店是規則欄位決定的，不是呼叫者身分決定的。`list()` / `save()` 才需要 `Actor`。

### 5.2 計算步驟

`subtotal` 就是現有 `create()` 迴圈算出來的那個 `total`（所有品項的 `(售價 + 選項加價) × 數量` 加總）。**不要另外算一次。**

```
1. code 正規化：trim → 轉大寫；空字串視為 null
   code == null  → 不套用折扣，discountAmount = 0，不寫 order_discounts
2. 格式檢查：code.matches("[A-Z0-9-]{4,20}")，不符 → 400「優惠碼格式不正確」
3. 取規則並鎖定該列：
     select * from discounts where code=? for update
   查無 → 404「優惠碼不存在或已失效」
4. 可用性檢查，任何一項不過都回 404「優惠碼不存在或已失效」（統一訊息，見 §5.5）：
     - active = true
     - starts_at IS NULL 或 starts_at <= atEpochMs
     - ends_at   IS NULL 或 atEpochMs <= ends_at
     - branch_id IS NULL 或 branch_id = 訂單分店
5. 門檻檢查：subtotal >= min_subtotal，不足 → 400「訂單金額未達此優惠碼的最低消費」
6. 次數檢查：max_redemptions IS NULL 或 redeemed_count < max_redemptions
     不足 → 409「此優惠碼的使用次數已達上限」
7. 算折抵：
     raw = (kind == "PERCENT")
             ? Math.multiplyExact(subtotal, percent) / 100    // 整數除法，無條件捨去
             : amount
     discountAmount = Math.max(0, Math.min(raw, subtotal - 1))
8. 遞增計數：update discounts set redeemed_count=redeemed_count+1 where id=?
     max_redemptions IS NULL 時也要遞增 —— redeemed_count 是促銷成本的帳，不是只有設上限才記
9. 回傳 Applied(規則快照欄位..., subtotal, discountAmount)
```

`total = subtotal - discountAmount`，由 `OrderService` 算並寫進 `orders`。

### 5.3 四個必須照做的細節

1. **整數運算，無條件捨去。** `Math.multiplyExact(subtotal, percent) / 100`。不得用 `double`、不得用 `BigDecimal`、不得先除再乘。`subtotal` 上限是 1,000,000，`× 90` 不會溢位，但仍然用 `multiplyExact` 以符合 `AGENTS.md` 金額章節
2. **`Math.min(raw, subtotal - 1)`** 保證折後至少 1 元（§11.5）。`subtotal = 1` 時折抵為 0，**這不是錯誤**，照常寫快照
3. **第 3 步的 `for update` 是必要的**，而且要先鎖再讀 —— 第 6 步的檢查與第 8 步的遞增必須在同一把鎖底下，否則兩筆併發訂單都會讀到「還有名額」（驗收 18 會驗）
4. **鎖順序：`branches` → `orders` → `discounts`。** `create()` 目前沒有鎖 `branches`／`orders`（新訂單還不存在），所以實務上只會拿到 `discounts` 這一把鎖；規定順序是為了日後有人在 `create()` 加鎖時不會踩出死鎖

### 5.4 `save()` 的驗證

| 條件 | 錯誤 |
| --- | --- |
| `code` 不符 `[A-Za-z0-9-]{4,20}`（存檔前轉大寫） | 400「優惠碼格式不正確」 |
| `name` 空白或超過 40 字 | 400「優惠碼名稱需為 1–40 字」 |
| `kind` 不是 `PERCENT` / `AMOUNT` | 400「折扣類型不正確」 |
| `kind=PERCENT` 且 `percent` 不在 1–90 | 400「折扣百分比需為 1–90」 |
| `kind=PERCENT` 且 `amount != 0` | 400「百分比折扣不可設定定額金額」 |
| `kind=AMOUNT` 且 `amount` 不在 1–1,000,000 | 400「折抵金額需為 1 元以上」 |
| `kind=AMOUNT` 且 `percent != 0` | 400「定額折扣不可設定百分比」 |
| `minSubtotal` < 0 或 > 1,000,000 | 400「最低消費金額不正確」 |
| `startsAt` 與 `endsAt` 都有值且 `startsAt > endsAt` | 400「優惠期間的起訖時間不正確」 |
| `maxRedemptions` 有值且 ≤ 0 | 400「使用次數上限需大於 0」 |
| `branchId` 有值但查無該分店 | 404「找不到分店」 |
| `code` 與其他規則重複 | 409「優惠碼已存在」 |

> **`Problem.check` 只能用在 400 那幾列。** 404 / 409 一律 `throw new Problem(404, "...")` / `throw new Problem(409, "...")`。這是 G13 §5.3 立下的通則，`Problem.check` 固定回 400。

更新既有規則時 `redeemedCount` **由後端保留原值**，忽略請求帶進來的數字 —— 與「不信任前端金額」同一個理由。

### 5.5 為什麼查無、停用、過期、跨店全都回同一個 404

四種情況回同一則訊息「優惠碼不存在或已失效」，是為了不讓外部使用者用錯誤訊息的差異去枚舉有效碼（顧客端是可以自助下單的，優惠碼等同一組可猜測的憑證）。門檻不足（400）與次數額滿（409）則**必須**分開回，因為那兩種情況顧客有具體可做的事（多買一點／換一組碼），而且此時碼的存在本來就已經藉由「你手上有這組碼」揭露了。

### 5.6 HTTP 端點

| 方法 | 路徑 | 權限 | 說明 |
| --- | --- | --- | --- |
| `GET` | `/api/discounts` | `MENU_MANAGE` + 總部（`a.global()`） | 全部規則，`code` 排序 |
| `POST` | `/api/discounts` | `MENU_MANAGE` + 總部 | 新增或更新（`id` 為 null 即新增） |

- 兩支都需要登入且需要 CSRF token（`POST`），沒有任何例外
- **不新增顧客可見的折扣查詢端點。** 顧客拿到碼才能用，不能列舉

下單端點不變，仍是 `POST /api/orders`，只是請求多一個 `discountCode` 欄位（§6.1）。

### 5.7 資料範圍

`list()` / `save()` **只開放總部**：

```java
a.require("MENU_MANAGE");
if (!a.global()) throw new Problem(403, "只有總部可以維護優惠碼");
```

分店店長就算有 `MENU_MANAGE`（目前的角色設定沒有這樣配，但角色是可編輯的）也不能維護優惠碼。理由：折扣直接等於減少營收，是總部的定價權；讓分店自行發碼等於讓分店自訂售價，那是 G18 區域定價的範疇。

---

## 6. 下單時的套用（`coffee-orders`）

### 6.1 `Orders` interface 的變更

```java
record Create(
    String branchId,
    String fulfillment,
    String paymentMethod,
    String note,
    String discountCode,          // 新增，可為 null
    List<LineInput> items) {}

record OrderDiscount(            // 新增
    String code, String name, String kind, int percent, int amount, int discountAmount) {}

record Order(
    String id,
    ...,
    int total,                    // 折「後」金額，語意不變
    int subtotal,                 // 新增：折扣前小計 = total + discountAmount
    int discountAmount,           // 新增
    OrderDiscount discount,       // 新增，沒有折扣時為 null
    String note,
    ...) {}
```

**`Create` 沒有、也不會有任何金額欄位。** 前端只送碼，不送折抵金額。這一條是本規格的紅線，違反直接 `REQUEST_CHANGES`。

### 6.2 `create()` 的改動位置

在既有的品項迴圈算完 `total` 之後、`insert into orders` 之前：

```java
Problem.check(total <= 1000000, "單筆訂單金額超過上限");
// ↓ 新增
var applied = discounts.apply(q.discountCode(), q.branchId(), total, System.currentTimeMillis());
int subtotal = total;
int discountAmount = applied == null ? 0 : applied.discountAmount();
total = Math.subtractExact(subtotal, discountAmount);
```

然後 `insert into orders(...)` 多寫 `discount_amount`；若 `applied != null`，再 `insert into order_discounts(...)`，並寫稽核：

```java
audit.record(a, "ORDER_DISCOUNT", id, q.branchId(),
    "套用優惠碼 " + applied.code() + "（" + applied.name() + "），小計 " + subtotal
        + " 元，折抵 " + applied.discountAmount() + " 元");
```

`create()` 已經是 `@Transactional`，`apply()` 的鎖與計數自然在同一個交易裡 —— **不要**給 `apply()` 加 `REQUIRES_NEW`，那會讓訂單回滾時計數留下來。

### 6.3 `normalize()` 必須把 `discountCode` 一起正規化

`fingerprint()` 是 `q.toString()` 的 SHA-256，`normalize()`（`OrderService.java:120-129`）目前只處理 `optionIds` 排序。**`discountCode` 沒有一起正規化的話**，同一筆重送但碼的大小寫或前後空白不同，指紋就不一樣，`Idempotency-Key` 的重試保護會失效並回 400「同一識別碼不能用於不同訂單」。

```java
String code = q.discountCode() == null ? null : q.discountCode().trim().toUpperCase(Locale.ROOT);
if (code != null && code.isEmpty()) code = null;
```

正規化後的 `Create` 才拿去算指紋，也才拿去 `apply()`。

### 6.4 三個必須維持原狀的地方（都要有驗收條件）

1. **既有的重試路徑不得重複折抵。** `create()` 開頭命中 `existing` 時直接 `return get(...)`，那一段在 `apply()` 之前，所以重試不會再遞增 `redeemed_count` —— **不要**把 `apply()` 往前搬
2. **`confirmOnline()` 的金額比對用 `o.total()`**（`OrderService.java:506`），也就是折後金額。綠界送來的付款金額本來就是折後的，這裡一個字都不要改
3. **`cash()` 的 `tendered >= o.total()`** 同樣是折後金額，不要改

### 6.5 讀路徑

`snapshot()`、`page()`、`reconciliationCandidates()` 都要回填新欄位：

- `snapshot()` / `page()`：一次 `left join order_discounts` 或批次查詢帶回。**`page()` 的「一頁固定 3 次查詢」是 G10 立下的性質，不得退化** —— 折扣要嘛 join 進表頭那一次查詢（建議，`order_discounts` 與 `orders` 是 1:1），要嘛獨立成第 4 次批次查詢並在 PR 描述說明為什麼。**絕對不可以每筆訂單各查一次**
- `reconciliationCandidates()`：`items()` 固定空 List 的既有語意不變；`subtotal` 填 `total + discount_amount`（`orders` 本身就有這欄，不用 join）。**`discount` 填 `null` 或實際快照都可以**（v1.2 放寬，理由見 §6.6 末）

### 6.6 POS 一次走完的現金收款：不得用購物車小計驗證實收金額

> v1.2 新增。來源是 PR #31 的審查 —— 這一段 v1.1 完全沒寫，實作端照著現行 `checkout()` 的形狀做就會做出錯的行為，而且錯的方向是**抽屜短少**。

`MenuView.vue` 的 `checkout()` 對「員工 POS + 現金」是**一次走完**的：同一個函式先建單，再立刻呼叫 `/orders/{id}/cash`，而實收金額的驗證與預設值發生在**建單之前**：

```js
const cash = tendered.value ?? total.value;              // total 是購物車自行加總的小計
if (cashAtPos && (... || cash < total.value || ...)) { notify("請輸入足夠的實收金額"); return; }
```

§11.6 決定不做折抵預覽端點，所以建單之前前端**不可能**知道折抵金額，`total` 必然是折扣前小計。兩個後果：

1. **擋掉合法交易。** 小計 140、折 14、應收 126。顧客給 130，店員輸入 130 → `130 < 140` 成立 → 回「請輸入足夠的實收金額」。後端 `cash()` 的 `tendered >= o.total()` 其實是過的（§6.4 第 3 點），是前端自己把門檻設高了。
2. **店員不輸入時會多找零。** `tendered.value ?? total.value` 把 140 當實收送出，後端算 `change = 140 − 126 = 14`，收據顯示「合計 126 / 實收 140 / 找零 14」。抽屜實際只進 126，店員照收據找出 14 —— **每張折扣訂單短少一個折抵金額**。這正是 §1「背景與問題」第 2 點要消滅的現象，做完 G07 反而自己製造出來一次，而且會在 G15 的現金日結被記成店員短收。

**設計決策：POS 帶優惠碼時，收款拆成兩段；不帶碼時行為一個字都不變。**

| 情況 | 流程 |
| --- | --- |
| `discountCode` 為空（含顧客端、ECPAY） | **與 S3 之前完全相同**，一個字不改 |
| `discountCode` 非空且為員工 POS 現金 | 先建單 → 用回應的 `order.subtotal` / `order.discountAmount` / `order.total` 顯示「小計 / 折抵 / 應收」→ 店員輸入實收 → 以 `order.total` 驗證 → 才呼叫 `/orders/{id}/cash` |

實作端要求：

1. **`discountCode` 非空時，不得執行建單前的 `cash < total.value` 前置驗證**，也**不得**用購物車小計當 `tendered` 的預設值 —— 此時實收金額必須由店員明確輸入，沒有預設值
2. 建單成功但收款尚未完成時，訂單留在 `PENDING_PAYMENT`。這是既有且正確的狀態：店員可以在 `/orders` 補收，或依既有規則取消（`CASH` + `PENDING_PAYMENT` 本來就可取消）。**不要**為了「收款失敗」去回滾訂單 —— 那會連帶回滾 `redeemed_count`，而碼確實已經用掉了
3. 購物車面板的「合計」與「應找零」在使用者輸入了優惠碼之後，**必須標示為折扣前**（例如「小計（折扣前）」）或不顯示「應找零」。維持一個看起來像應收金額、實際是折扣前小計的數字，比不顯示更糟
4. 這一段**只動前端的產品程式碼**，沒有後端行為、API 或 migration 變更。唯一的後端新增是驗收 21a 的測試（§10），它驗的是既有的 `cash()` 行為，不改任何後端程式碼

**為什麼不改成「前端先算折抵」：** 那等於把 §5.2 的計算複製到前端，是 §11.6 已經否決過的形狀，而且複製到前端比複製到第二個後端端點更糟 —— 前端算出來的數字是不可信的，卻會被店員當成應收金額念給顧客。

**推翻的代價**：要推翻只有一條路，就是做 §11.6 的 `POST /api/orders/quote` 預覽端點，讓建單前就知道折抵。那是純加法、任何時候都能補，補了之後本節的兩段式流程可以收回成一段。**在有 quote 之前，兩段式是唯一不會讓抽屜對不起來的做法。**

**順帶放寬 §6.5 的 `reconciliationCandidates()`：** v1.1 寫死「`discount` 填 `null`」，理由只是「不用 join，省一次查詢」。PR #31 實際做成 left join 並填了完整快照，多一個 1:1 的 left join 成本可忽略，而對帳畫面看得到折扣其實更好用。**兩種都接受，不為了對齊文字而要求改回來** —— 規格當初那句是省事的預設值，不是有理由的限制。

---

## 7. 稽核

| action | target_id | branch_id | summary 範例 |
| --- | --- | --- | --- |
| `DISCOUNT_SAVE` | 規則 id | `null`（總部行為） | `新增優惠碼 SPRING20（春季九折），百分比 10%，適用全鏈` |
| `ORDER_DISCOUNT` | 訂單 id | 訂單分店 | `套用優惠碼 SPRING20（春季九折），小計 500 元，折抵 50 元` |

`audit_log.target_id` 是 `VARCHAR(80)`（V1 建、V5 擴充）。規則 id 是 UUID（36 字元）、訂單 id 是 `VARCHAR(20)`，都在範圍內。

`Audit.record()` 由 `AuditService` 包住整個寫入（G11 §5.3 定案），稽核寫失敗不會讓業務交易回滾 —— 本規格沿用，不重新設計。

---

## 8. 報表

`ReportService.report()` 的回傳 Map 新增一個 key：

```java
Map.entry("discount", discountTotal)
```

`discountTotal` = 當月已付款訂單的 `sum(orders.discount_amount)`。取法：把 `Sale` record 加一個 `discountAmount` 欄位，在既有那支 `select` 補上 `o.discount_amount`（**不要多打一次 DB**）。

**不要改 `revenue`、`grossProfit`、`grossMargin` 的算法。** 現在的 `revenue = sum(orders.total)` 已經是折後實收，`grossProfit = revenue - cost` 因此已經正確把折扣吃進毛利。要解釋的是另一件事：

> `products[].revenue` 與 `categories` 是**品項原價營收**（`(unit_price + options_price) × quantity` 加總），表頭 `revenue` 是**折後實收**。折扣存在時兩者本來就不會相等，差額即 `discount`。

前端 `ReportsView.vue` 在營收卡片旁顯示「折扣 -N 元」，並在品項排行的區塊標註「以原價計」。

---

## 9. 施工階段

> 規則見 `AGENTS.md`「施工階段與中斷續作」。每階段獨立 CI 綠、獨立可合併、有自己的驗收子集。**做完一個階段就 push**，不要整份做完才推。

### 9.0 v1.1 的階段調整（為什麼從四階段變三階段）

v1.0 把「使用次數上限與兌換計數」單獨切成 S4，是個錯誤的切法，Codex 在 PR #27 的審查裡指出來了，這裡採納：

`max_redemptions` 從 S1 起就存在於 `Rule` 與 `save()`，S2 的維護 UI 又把它開放給總部設定，但強制邏輯排在 S4 —— **S2 或 S3 單獨合併進主線的期間，總部設 `max_redemptions=1` 的碼仍然無限可用，`redeemed_count` 永遠是 0。** 這種「設定看得到但不生效」的狀態違反「每階段獨立可合併、不破壞任何既有行為」：它沒有破壞既有行為，但它讓一個新開放的設定說謊，而說謊的方向是促銷成本無上限。

修法是把 §5.2 第 6、8 步併進 S1，讓**強制先於開放**落地。原 S4 剩下的只有「UI 顯示已用／上限」，那本來就屬於 S2 的那張維護畫面，所以併入 S2，階段數從四變三。總工作量不變，只是搬位置。

一般化的規則，寫下來給後續規格用：

> **一個設定欄位的「可設定」與「生效」必須在同一階段。** 切階段時可以把功能切成「還沒有人用」，不可以切成「有人能設但不作用」。

PR 描述請維護這張表：

```markdown
## 施工進度（G07）
- [ ] S1 資料層與折扣計算（含次數上限強制） —— 未開始
- [ ] S2 維護 API 與總部 UI —— 未開始
- [ ] S3 下單套用、讀路徑與報表 —— 未開始
```

### S1 — 資料層與折扣計算（純加法，零行為變化）

| 項目 | 內容 |
| --- | --- |
| 檔案 | `V9__order_discounts.sql`、`catalog/api/Discounts.java`、`catalog/internal/DiscountService.java` |
| 測試 | `DiscountMigrationTest`（仿 `CashSessionsMigrationTest`）、`DiscountCalculationTest`（純單元測試，不起 Spring）、`DiscountRedemptionTest`（直接呼叫 `apply()`，含併發） |
| 驗收子集 | 驗收 1–4、17–18 |

`DiscountService` 在這一階段實作 `list()` / `save()` / `apply()` **三支都做完，§5.2 的九步一步都不省**（含第 6 步次數檢查與第 8 步遞增計數），但**沒有任何呼叫端** —— 沒有 Controller、`OrderService` 不動。

零行為變化：新表沒有任何列，`orders.discount_amount` 全部是 DEFAULT 0。

**驗收 17、18 在這一階段是直接對 `DiscountService.apply()` 測，不經過下單**（下單要到 S3 才接上）：在一個交易裡連續 `apply()` 同一組 `max_redemptions=1` 的碼，第二次要拿到 409；併發則用兩條執行緒各自開交易搶同一組碼。驗收 19 的「訂單回滾不留下增量」必須有訂單才驗得到，留在 S3。

### S2 — 維護 API 與總部 UI（純加法）

| 項目 | 內容 |
| --- | --- |
| 檔案 | `catalog/internal/DiscountController.java`、`frontend/src/modules/catalog/DiscountsView.vue`、`frontend/src/main.ts`、`frontend/src/shared/types.ts` |
| 測試 | `DiscountAdminTest`（權限矩陣、驗證表、重複碼 409）、`HttpWorkflowTest` 補 CSRF 案例 |
| 驗收子集 | 驗收 5–8 |

路由 `/discounts`，`meta: { permissions: ["MENU_MANAGE"] }`。導覽列的入口與 `/menu` 同區。維護畫面包含 `maxRedemptions` 的輸入欄與「已用／上限」的顯示（`redeemedCount` / `maxRedemptions`，無上限顯示「不限」）—— 強制邏輯 S1 已經在了，所以這裡開放設定是安全的。

此階段結束後總部可以建立規則，但**下單還不會用到它** —— 這是安全的中間狀態。

### S3 — 下單套用、讀路徑與報表（唯一的行為變更）

| 項目 | 內容 |
| --- | --- |
| 檔案 | `orders/api/Orders.java`、`orders/internal/OrderService.java`、`reporting/internal/ReportService.java`、`frontend` 的 `MenuView.vue` / `OrdersView.vue` / `ReportsView.vue` / `types.ts` |
| 測試 | `OrderDiscountTest`（含驗收 19 的回滾）、`CoffeeIntegrationTest` 既有案例補新欄位、`OrderPaginationTest` 補查詢次數 |
| 驗收子集 | 驗收 9–16、19、20、**21–23（v1.2 新增，§6.6）** |

`Order` record 加三個欄位是編譯期可見的破壞性變更，呼叫點集中在 `OrderService`（`snapshot` / `page` / `reconciliationCandidates`）與前端 `types.ts`。**沒有優惠碼的訂單行為必須與 S3 之前完全一致**（驗收 16）。

**§6.6 的 POS 兩段式收款屬於本階段，不另立 S4。** 它只動 `MenuView.vue` 一個檔、沒有後端變更，規模遠小於一個階段；而且它與 S3 的其餘部分是同一件事的兩面 —— S3 讓折扣生效，§6.6 讓收現金的人拿得到正確的應收金額。拆開會產生一個「折扣會算但 POS 收不對錢」的中間狀態，正是 §9.0 那條規則（可設定與生效必須同一階段）要擋的形狀。

### 為什麼切得開

S1 建表但沒人呼叫、S2 加端點但下單不碰 —— 這兩個階段各自合併進主線的行為變化都是零，而且**每個階段開放的設定在同一階段就會生效**（§9.0）。唯一的行為變更集中在 S3，而 S3 的「沒帶碼就完全照舊」這條性質讓它也能安全單獨合併。

---

## 10. 驗收條件

逐條可勾選。**標記「自動化」的每一條都要有對應的自動化測試；標記「人工」的是人工驗收**（只有 v1.2 新增的 21b／22／23 三條，理由見 §11.10）。**驗收 1–20 全部是自動化，一條都不放寬。****編號沿用 v1.0，只改分組** —— 17、18 移到 S1，19、20 移到 S3，這樣號碼在兩版之間仍然指同一件事。

**S1**

1. [ ] 全新資料庫跑完 Flyway 後有 `discounts`、`order_discounts` 兩張表與 `orders.discount_amount` 欄位；既有訂單列的 `discount_amount` 全部是 0
2. [ ] `PERCENT` 10%、小計 505 → 折抵 50（無條件捨去，不是 50.5 也不是 51）
3. [ ] `AMOUNT` 100、小計 100 → 折抵 99，折後 1 元（下限生效）；小計 1 → 折抵 0，折後 1 元
4. [ ] `apply(null, ...)` 回傳 `null`，不寫任何列
17. [ ] `max_redemptions = 1` 的碼，第二次 `apply()` 回 409「此優惠碼的使用次數已達上限」；`max_redemptions = NULL` 的碼每次 `apply()` 都讓 `redeemed_count` +1
18. [ ] 兩條執行緒各自開交易搶同一組只剩一個名額的碼，只有一筆成功，`redeemed_count` 不會超過 `max_redemptions`

**S2**

5. [ ] 總部 + `MENU_MANAGE` 可以 `GET` / `POST /api/discounts`
6. [ ] 分店範圍的帳號即使有 `MENU_MANAGE` 也回 403；沒有 `MENU_MANAGE` 的帳號回 403；未登入回 401
7. [ ] §5.4 的每一列驗證都有測試，錯誤碼與訊息相符；重複 `code` 回 409
8. [ ] `POST /api/discounts` 沒帶 CSRF token 回 403（`HttpWorkflowTest`，真實 HTTP + Cookie）

**S3**

9. [ ] 帶有效碼下單，`orders.total` = 小計 − 折抵，`order_discounts` 有一列且 `subtotal - discount_amount = orders.total`
10. [ ] 請求內**額外塞入** `total` / `discountAmount` / `subtotal` 等金額欄位時，結果與沒塞完全相同（後端重算，前端金額一律忽略）
11. [ ] 停用、未開始、已結束、跨分店四種情況都回 404 且訊息相同
12. [ ] 小計未達 `min_subtotal` 回 400「訂單金額未達此優惠碼的最低消費」
13. [ ] 同一 `Idempotency-Key` 重送同一筆（含同一組碼，大小寫／空白不同）回傳同一張訂單，**不重複折抵**；換成不同碼則回 400「同一識別碼不能用於不同訂單」
14. [ ] 套用折扣後 `audit_log` 有一列 `ORDER_DISCOUNT`，`branch_id` 是訂單分店
15. [ ] `GET /api/orders` 一頁的 DB 查詢次數在 20 筆與 100 筆兩種頁大小下相同（G10 的性質不得退化）；`subtotal` / `discountAmount` / `discount` 正確
16. [ ] **沒帶 `discountCode` 的訂單**：`total`、`order_items`、稽核、報表全部與 S3 之前一致；`discount` 為 `null`、`discountAmount` 為 0、`subtotal` = `total`
19. [ ] 訂單建立失敗回滾時 `redeemed_count` 不留下增量；訂單取消**不**退還次數（§11.8）；`max_redemptions = 1` 的碼第二次下單回 409（驗收 17 的端到端版本）
20. [ ] 當月有折扣訂單時 `report()` 的 `discount` = `sum(orders.discount_amount)`，且 `revenue + discount` = 品項原價營收加總

**S3（v1.2 新增，§6.6；v1.3 重新切分自動化與人工）** —— 21a 是後端自動化測試，21b／22／23 是人工驗收（理由見 §11.10）

21a. [ ] **（自動化）** 對一張帶折扣的訂單（小計 140、折抵 14、`total` 126）呼叫 `POST /api/orders/{id}/cash`：`tendered = 130` 收款成功且 `change = 4`；`tendered = 120` 回 400、**訂單維持 `PENDING_PAYMENT`**、`redeemed_count` 不變，且之後仍可用 `tendered = 130` 補收成功
21b. [ ] **（人工）** 員工 POS、現金、帶有效碼：收款欄顯示「小計 140 / 折抵 14 / 應收 126」，輸入實收 130 收款成功且收據找零 4；輸入 120 被擋下且訂單留在 `PENDING_PAYMENT`
22. [ ] **（人工）** 員工 POS、現金、帶有效碼且店員未輸入實收：**不得**以購物車小計自動送出 `/orders/{id}/cash`（收款按鈕停用或提示輸入實收）
23. [ ] **（人工）** **沒帶碼**的員工 POS 現金訂單：建單與收款的流程、驗證與收據數字與 S3 之前**完全一致**（驗收 16 的收款版本）

**21a 是這三條裡唯一擋得住「抽屜短少」的自動化防線**：它釘住後端 `cash()` 對**折後** `total` 的驗證、以及收款不足時 `PENDING_PAYMENT` 的保留行為。有了它，前端就算寫錯，錯的也只是顯示與預設值，不會出現「後端認帳但金額對不上」的收款。21b／22／23 純屬前端顯示與流程，而本 repo 目前沒有前端測試基礎設施（§11.10）。

**人工驗收的交付方式**：在 `docs/reports/G07-order-discounts.md` 附一節「§6.6 人工驗收」，逐條寫出實際輸入與觀察到的畫面數字（小計／折抵／應收／實收／找零）。這不等於自動化測試，但留下可追溯的紀錄，也讓下一輪 review 有東西可對照。

---

## 11. 設計決策

每一項都是 Claude 定案。附理由與推翻它的代價，給下一輪推翻用。

### 11.1 一張訂單最多一個折扣，不疊加 —— **不疊加**

**理由**：疊加要先定義優先序（先百分比還是先定額）、要定義基準（第二個折扣算在原價還是折後），每一種組合都是一條新的金額路徑。以咖啡廳的實際促銷密度，疊加的價值遠低於它帶進來的金額錯誤風險。用 `order_discounts.order_id` 當主鍵，讓資料表形狀本身就擋住疊加，比寫在程式裡可靠。

**推翻的代價**：`order_discounts` 主鍵要改成 `(order_id, discount_id)` 或獨立 id（動既有表結構，要新 migration），並在規格定義優先序與基準，`apply()` 要改成接受碼清單並回傳清單。`OrderService` 的呼叫點只有一處，改動可控 —— **真正貴的是規則定義，不是程式**。

### 11.2 只做訂單層折扣，品項層與身分別另立 —— **G20 / G21**

**理由**：買一送一、第二件半價要決定「折的是哪一件」，那是品項層的規則引擎，會動到 `order_items` 的快照結構與報表的品項營收歸屬；會員價與員工價要先有會員／員工身分模型（目前 `accounts` 只有角色與分店）。兩者各自的規模都與本規格相當，合進來會逼出一個切不開的大階段。

**登記**：**G20 品項層折扣與買一送一**、**G21 會員價與員工價**，兩項都排在 G07 上線並跑過一段時間之後 —— 屆時 `order_discounts` 已經有真實資料，可以看出實際用了哪幾種促銷再決定要不要做。

**推翻的代價**：G20 需要 `order_item_discounts` 快照表與品項層的規則模型；`order_discounts` 的訂單層快照可以原樣留著並存，是加法。G21 需要 `accounts` 加身分欄位與對應的 migration。兩者都**不需要回頭改 G07**。

### 11.3 折扣規則放 `coffee-catalog`，不開新模組 —— **放 catalog**

理由與拆分預留見 §3.1。**推翻的代價**：新增 `coffee-promotions` 模組（pom、ArchUnit 清單、`coffee-app` 組裝），把 `Discounts.java` 整個檔案搬過去 —— 因為介面從一開始就獨立，呼叫端一行不用改。

### 11.4 維護權限沿用 `MENU_MANAGE` + 總部範圍 —— **不新增權限常數**

**理由**：新增權限常數要同步 `Identity.PERMISSIONS`、`InitialData` 的角色清單，以及一個把新權限配給既有角色的 migration —— 三個檔案的協調成本，換到的只是「折扣與菜單可以分開授權」這個目前沒人需要的粒度。`MENU_MANAGE` 本來就管售價，折扣是售價的一體兩面。G14 也做了同樣的決定（§11.4），保持一致。

**推翻的代價**：新增 `DISCOUNT_MANAGE` 常數 + 一個 migration 把它配給總部管理角色 + `DiscountController` 改一行。約 30 分鐘，**任何時候都可以做**，不會卡住別的事 —— 所以現在不做。

### 11.5 折後金額下限 1 元，百分比上限 90% —— **不支援免費訂單**

**理由**：三層。(1) `orders.total` 的 `CHECK(total>0)` 來自 V1，`AGENTS.md` 禁止修改既有 migration；要拿掉得 `ALTER TABLE ... DROP CONSTRAINT`，而 V1 用的是匿名 CHECK，H2 與 PostgreSQL 產生的約束名不同，沒有跨兩邊都可靠的寫法。(2) 0 元訂單有自己的付款流程問題：`CASH` 要收 0 元、`ECPAY` 不接受 0 元交易，兩條路都要另外設計。(3) 100% 折扣（全額招待）實務上是員工權限問題，屬於 G21 的身分別範疇，不是優惠碼。

**推翻的代價**：要先處理 (2)，也就是定義「0 元訂單直接視為已付款、不進金流」，再處理 (1) 的約束移除方式（最乾淨的是新建 `orders_new` 搬資料，成本不低）。**在做 G21 之前不值得。**

### 11.6 不提供下單前的折抵預覽端點 —— **不提供**

**理由**：預覽端點等於把金額計算複製一份到第二個入口，兩份邏輯遲早漂移，而漂移的方向一定是「預覽顯示折得比較多」這種會被客訴的形狀。而且要做對的話，預覽端點必須接受與 `Create` 相同的品項清單並走同一段計算（**不能**接受前端送來的小計），成本與 `create()` 幾乎相同。目前的替代方案：訂單建立後（狀態 `PENDING_PAYMENT`，尚未付款）回應就帶著 `discount` 與 `subtotal`，前端在確認頁顯示折抵，不滿意可以直接取消（`CASH` 訂單本來就允許取消）。

**推翻的代價**：新增 `POST /api/orders/quote`，把 `create()` 裡「算小計 + apply」那段抽成共用的 private 方法，quote 走同一段但**不寫任何表、不遞增計數**。是純加法，任何時候都能補。**補的時候務必確認 quote 不會遞增 `redeemed_count`** —— 那是這個端點最容易寫錯的地方。

### 11.7 不做「每人限用一次」 —— **只做全域總量上限**

**理由**：每人限用要一張 `discount_redemptions(discount_id, account_id, order_id)` 表加唯一鍵，而且顧客自助註冊還沒做（G16），一個人可以有幾個帳號目前完全不受控 —— 在 G16 之前做「每人限用」是假的防護。全域總量上限（`max_redemptions`）則能真正限制促銷成本上限，是現階段划算的那一半。

**推翻的代價**：加一張 `discount_redemptions` 表與唯一鍵，`apply()` 多收一個 `accountId` 參數。是加法，但**應該排在 G16 之後**才有意義。

### 11.8 訂單取消不退還兌換次數 —— **不退還**

**理由**：退還要處理「退了又退」的重複扣減、要與 G03（退款與退單，尚未做）的狀態機一起設計，否則會出現 `redeemed_count` 比實際成立訂單數還低、促銷成本失控。多算一兩次兌換的成本（顧客少用一次碼）遠低於帳目不一致。

**推翻的代價**：`transition()` 走到 `CANCELLED` 時查 `order_discounts` 並遞減，需要在 `orders` 加一個「是否已退還」的旗標防重複，並與 G03 的退款路徑合併設計。**建議等 G03 一起做**，那時才有完整的回退語意。

### 11.9 快照 `discount_id` 不加外鍵 —— **不加**

**理由**：快照的語意是「當時長這樣」。加 FK 等於規則列永遠不能刪，或刪除時要連帶處理歷史訂單。`order_items` 的品項快照已經立下同樣的先例（改菜單不回寫歷史）。

**推翻的代價**：幾乎沒有。若日後要從訂單反查規則，`discount_id` 仍然存著，join 得到就 join，join 不到代表規則已刪 —— 這正是想要的行為。

### 11.10 驗收 21b／22／23 走人工驗收，不在 G07 內建前端測試基礎設施 —— **人工驗收**

> v1.3 新增。來源是 Codex 在 PR #32 的 `REQUEST_CHANGES`。

**背景**：v1.2 有一個內部矛盾，三個條件同時成立就讓驗收 21–23 無法實作 —— §10 開頭要求「每一條都要有對應的測試」、§6.6 第 4 點限定「只動前端」、而 `frontend/package.json` 目前**沒有任何 test script**，也沒有 Vitest／Playwright 等框架，`AGENTS.md` 禁止事項第 3 條又預設不引入新依賴。**這個矛盾是規格的錯，不是實作端的問題。**

**決定**：

1. 驗收 21 拆成 **21a（後端自動化）** 與 **21b（人工）**。21a 用既有的 `CoffeeIntegrationTest` / `HttpWorkflowTest` 就寫得出來，不需要任何新依賴
2. 21b／22／23 明載為**人工驗收**，交付方式是 `docs/reports/` 的驗收紀錄
3. §10 開頭改為逐條標記「自動化」或「人工」。**驗收 1–20 全部維持自動化，一條都不放寬**
4. 前端測試基礎設施登記為**獨立缺口 G22**，不塞進 G07

**理由**：G07 的紅線是「金額一律由後端計算」，那條線由 21a 與驗收 9／10／16 等後端測試守住，前端只是顯示層。要讓 21b／22／23 自動化，需要 Vitest + jsdom + `@vue/test-utils` 三個新依賴，外加把 `MenuView.vue` 的 `checkout()` 拆出可測的純函式 —— 那是一份獨立規格的工作量（新增建置步驟、CI 要不要跑、失敗算不算紅燈，每一項都要定）。把它夾進 G07 的修正輪，等於用一個大改動換三條顯示層驗收，還會逼 PR #31 這支已經三階段全綠的分支大改。

**推翻的代價**：低。G22 做完之後，把 21b／22／23 從「人工」改標為「自動化」並補測試即可，`MenuView.vue` 屆時的形狀不受本決策影響。**真實成本是在 G22 之前，這三條的迴歸靠人。** 如果 POS 收款之後還要再改，這個風險會累積 —— 記在這裡供下一輪推翻。

---

## 12. 給 Codex 的施工提醒

> v1.3 修正：原本的編號在第 9 項之後又重覆出現 7／8／9，已順排為 1–14。引用本節條款時請用新編號。

1. **`Create` 不得有任何金額欄位。** 前端只送 `discountCode`。這是紅線
2. **`normalize()` 一定要把 `discountCode` 一起正規化**（§6.3），否則 `Idempotency-Key` 的重試保護會壞掉，而且這種壞法在一般測試裡看不出來
3. **`apply()` 必須在 `create()` 的交易內**，不要加 `REQUIRES_NEW`
4. **不要把 `apply()` 搬到命中 `existing` 的重試分支之前**（§6.4 第 1 點）
5. **`page()` 一頁的查詢次數不得隨筆數增加**（G10 立下的性質，驗收 15 會驗）
6. **次數檢查與遞增計數（§5.2 第 6、8 步）屬於 S1，不是後面的階段。** 同一把 `for update` 底下完成，`max_redemptions` 為 NULL 時仍然要遞增 —— S2 的維護 UI 一開放就能設上限，強制邏輯必須已經在了（§9.0）
7. **Flyway 用 V9**，即使開工時主線上還沒有 V8（§4.0）
8. **POS 收現金時，不得用購物車小計驗證實收金額或當它的預設值**（§6.6，v1.2 新增）。帶碼就走兩段式：先建單、拿 `order.total`、再收款。這一條的錯法會讓抽屜短少，而且在一般測試裡看不出來
9. **驗收 21a 是後端自動化測試，21b／22／23 是人工驗收**（§11.10，v1.3）。**不要為了 21b／22／23 引入 Vitest 等前端測試依賴** —— 那是獨立缺口 G22，不屬於 G07。人工驗收的結果寫進 `docs/reports/G07-order-discounts.md`
10. **標記「自動化」的驗收條件，每一條都要有對應的測試**（§10 開頭）。特別容易漏掉的是驗收 11 的四種 404：`active=false`、未開始、已結束、跨分店，加上查無此碼。四種回同一則訊息是 §5.5 的防枚舉設計，沒有測試釘住，之後任何人「好心」把訊息改細一點都不會被擋下來
11. 404 / 409 用 `throw new Problem(...)`，`Problem.check` 只能用在 400
12. 錯誤訊息一律繁體中文台灣用語，寫給顧客看
13. 規格有錯或漏掉邊界條件時**先寫在設計摘要與 PR 描述裡，然後依你的判斷補上並標明** —— 不要停下來等回覆（`AGENTS.md` 禁止事項第 9 條）
14. 一次推進一個階段，做完就 push；跑不完就維持 draft 並更新進度檢查表
