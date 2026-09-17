# G06 — 商品選項模型與加價

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G06 |
| 優先順序 | P0（PO 於 2026-09-17 將金流整批延後後，本項升為第一順位） |
| 規格版本 | v1 |
| 撰寫 | Claude（PM / SA），2026-09-17 |
| 實作 | Codex（PG / SD） |
| 基準 commit | 盤點依據 `16f801c`；合併主線 `7c2f2eb`（PR #9 已進入主線）後仍適用 |

---

## 1. 背景與目標

### 問題

目前的選項是兩個自由字串欄位，**完全不影響金額**：

```sql
-- V1__coffee_schema.sql
temperature VARCHAR(12) NOT NULL, sugar VARCHAR(12) NOT NULL
```

```java
// OrderService.java:62
total = Math.addExact(total, Math.multiplyExact(p.price(), l.quantity()));
```

單價就是 `products.price`，乘上數量就是全部。系統賣不了任何加價品項 —— 加珍珠 +10、換燕麥奶 +20、加濃縮 +25、大杯 +15 這些每天都在賣的東西，一律只能用原價賣，或是由店員在系統外口頭加收現金。**這是目前唯一一個直接造成營收短收的缺口。**

第二個問題在 `OrderService.validateOptions()`（`OrderService.java:100-111`）：

```java
if (p.category().equals("手作烘焙"))
  Problem.check("不適用".equals(l.temperature()) && "不適用".equals(l.sugar()), "烘焙商品不提供冰量甜度");
else {
  Problem.check(Set.of("熱", "正常冰", "少冰", "去冰").contains(...), "溫度不正確");
  Problem.check(Set.of("無糖", "微糖", "半糖", "正常糖").contains(...), "甜度不正確");
}
```

分類字串 `"手作烘焙"` 與溫度／甜度的可選集合被寫死在 `coffee-orders` 裡，但分類清單其實歸 `coffee-catalog` 管（`CatalogService.java:69`）。總部在菜單管理新增一個分類，點餐端的驗證就默默失準：新分類會走進 else 分支，被要求填溫度與甜度。同樣地，「熱美式只能選無糖」這種單品層級的限制完全無法表達 —— 目前任何飲品都能選任何糖度。

### 目標

1. 商品可以綁定選項群組，選項可以帶加價，**加價由後端依有效菜單重算**
2. 選項的可選範圍、必選與否、可複選上下限，由資料決定，不再寫死在 `coffee-orders` 的 Java 程式碼裡
3. 溫度與甜度統一收進同一套選項模型，不再是特例字串
4. 訂單建立時完整快照選項名稱與加價，之後改菜單不回寫歷史
5. 報表的商品營收與毛利要把加價算進去，不能與訂單總額對不起來

### 不是目標

- 折扣與促銷（G07）—— 加價是正的、折扣是負的，但兩者的授權與稽核需求完全不同，不要順手一起做
- 分店各自的選項與售價（G13）—— 本版選項是全鏈共用，與 `products` 目前的設計一致
- 選項層級的庫存扣減（G08）
- 套餐與組合餐（未列入盤點）

---

## 2. 範圍

### 在範圍

- 選項群組與選項項目的資料模型、Flyway migration
- 總部維護選項的 API 與管理畫面
- 點餐時的選項驗證與加價重算
- 訂單項目的選項快照
- 既有溫度／甜度遷移進新模型
- `ReportService` 兩支彙整 SQL 的修正
- 前端點餐畫面的選項選擇 UI

### 不在範圍

- 折扣（G07）、分店覆寫（G13）、庫存（G08）
- 不新增任何權限常數。選項維護沿用既有 `MENU_MANAGE`

---

## 3. 涉及模組與邊界

| 模組 | 變更 |
| --- | --- |
| `coffee-catalog` | 主要實作位置：選項群組／項目的 CRUD、查詢、`Catalog.Product` 擴充 |
| `coffee-orders` | 改用 `Catalog` 的選項定義做驗證與加價；刪除 `validateOptions` 的硬編規則；快照選項 |
| `coffee-reporting` | 兩支商品彙整 SQL 改為把加價計入 |
| `coffee-app` | Flyway migration |
| `frontend/src/modules/catalog` | 選項維護畫面 |
| `frontend/src/modules/ordering` | 點餐時的選項選擇 |

### 邊界規則（`ModuleBoundariesTest` 會驗）

- **選項的定義與定價歸 `coffee-catalog`。** `coffee-orders` 只能透過 `Catalog` 這個 `api` interface 取得選項與加價，**不得**直接查 `option_groups` / `option_items` / `product_option_groups` 三張表，也不得引用 `com.coffee.catalog.internal`
- `order_items` 與新的 `order_item_options` 屬於 `coffee-orders`，由它自己讀寫
- `coffee-reporting` 對 `order_items` / `order_item_options` 的直接查詢是 `AGENTS.md` 已載明的唯讀投影例外，沿用即可，但**只能讀**
- 不得引入任何新的第三方相依

---

## 4. DB schema 與 migration

**新增檔案：`backend/coffee-app/src/main/resources/db/migration/V3__product_options.sql`**

> **版號注意：`V2__payment_reconciliation.sql` 已隨 PR #9 進入主線**（2026-09-17 合併）。本規格使用 **V3**。開工前請確認主線上的 migration 目錄，取下一個未使用的版號，**不要**沿用本文寫死的數字而不檢查 —— 撞號要重跑整個資料庫。

不得修改 `V1__coffee_schema.sql`（`AGENTS.md` 禁止事項第 6 條）。

```sql
CREATE TABLE option_groups(
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(40) NOT NULL,
  selection VARCHAR(8) NOT NULL CHECK(selection IN ('SINGLE','MULTI')),
  min_select INTEGER NOT NULL CHECK(min_select>=0),
  max_select INTEGER NOT NULL CHECK(max_select>=1),
  active BOOLEAN NOT NULL,
  sort_order INTEGER NOT NULL DEFAULT 99,
  CHECK(min_select<=max_select)
);

CREATE TABLE option_items(
  id VARCHAR(36) PRIMARY KEY,
  group_id VARCHAR(36) NOT NULL REFERENCES option_groups(id),
  name VARCHAR(40) NOT NULL,
  price_delta INTEGER NOT NULL,
  cost_delta INTEGER NOT NULL CHECK(cost_delta>=0),
  active BOOLEAN NOT NULL,
  sort_order INTEGER NOT NULL DEFAULT 99
);
CREATE INDEX idx_option_items_group ON option_items(group_id,sort_order);

CREATE TABLE product_option_groups(
  product_id VARCHAR(36) NOT NULL REFERENCES products(id),
  group_id VARCHAR(36) NOT NULL REFERENCES option_groups(id),
  sort_order INTEGER NOT NULL DEFAULT 99,
  PRIMARY KEY(product_id,group_id)
);

CREATE TABLE order_item_options(
  id VARCHAR(36) PRIMARY KEY,
  order_item_id VARCHAR(36) NOT NULL REFERENCES order_items(id),
  group_id VARCHAR(36) NOT NULL,
  group_name VARCHAR(40) NOT NULL,
  option_id VARCHAR(36) NOT NULL,
  option_name VARCHAR(40) NOT NULL,
  price_delta INTEGER NOT NULL,
  cost_delta INTEGER NOT NULL
);
CREATE INDEX idx_order_item_options_item ON order_item_options(order_item_id);

ALTER TABLE order_items ADD COLUMN options_price INTEGER NOT NULL DEFAULT 0;
ALTER TABLE order_items ADD COLUMN options_cost INTEGER NOT NULL DEFAULT 0;
ALTER TABLE order_items ALTER COLUMN temperature DROP NOT NULL;
ALTER TABLE order_items ALTER COLUMN sugar DROP NOT NULL;
```

`order_item_options` 的 `group_id` / `option_id` **刻意不加 foreign key**：它是歷史快照，總部日後刪掉某個選項時不該連動失敗或級聯刪除。這與 `order_items` 已經快照 `name` / `unit_price` / `unit_cost` 是同一個道理。

### 遷移既有溫度與甜度

同一支 V3 的後半段，把現有的兩組硬編選項建成資料：

```sql
INSERT INTO option_groups(id,name,selection,min_select,max_select,active,sort_order) VALUES
  ('temperature','溫度','SINGLE',1,1,true,1),
  ('sugar','甜度','SINGLE',1,1,true,2);

INSERT INTO option_items(id,group_id,name,price_delta,cost_delta,active,sort_order) VALUES
  ('temp-hot','temperature','熱',0,0,true,1),
  ('temp-normal','temperature','正常冰',0,0,true,2),
  ('temp-less','temperature','少冰',0,0,true,3),
  ('temp-none','temperature','去冰',0,0,true,4),
  ('sugar-none','sugar','無糖',0,0,true,1),
  ('sugar-light','sugar','微糖',0,0,true,2),
  ('sugar-half','sugar','半糖',0,0,true,3),
  ('sugar-full','sugar','正常糖',0,0,true,4);

-- 既有規則：非「手作烘焙」的商品才有溫度與甜度
INSERT INTO product_option_groups(product_id,group_id,sort_order)
  SELECT id,'temperature',1 FROM products WHERE category <> '手作烘焙'
  UNION ALL
  SELECT id,'sugar',2 FROM products WHERE category <> '手作烘焙';
```

`price_delta` 全部為 0，所以**遷移不改變任何既有商品的售價**。遷移後 `validateOptions` 的行為由這份資料完整取代。

歷史 `order_items` 的 `temperature` / `sugar` 字串**不回填**進 `order_item_options`（見第 13 節待 PO 決定第 3 項）。舊訂單繼續由這兩個欄位顯示，新訂單由 `order_item_options` 顯示，前端兩者都要能渲染。

---

## 5. `Catalog` api 變更

`com.coffee.catalog.api.Catalog` 新增（全部是 `api` package 內的 record 與 interface method，不放實作）：

```java
record OptionItem(String id, String groupId, String name, int priceDelta, int costDelta,
                  boolean active, int sortOrder) {}

record OptionGroup(String id, String name, String selection, int minSelect, int maxSelect,
                   boolean active, int sortOrder, List<OptionItem> items) {}

/** 點餐與菜單顯示用：某商品綁定的、且啟用中的選項群組，依 sortOrder 排序。 */
List<OptionGroup> productOptions(String productId);

/** 後端重算加價的唯一入口。回傳已解析、已驗證的選項快照；任何違規一律丟 Problem。 */
List<ResolvedOption> resolveOptions(String productId, List<String> optionIds);

record ResolvedOption(String groupId, String groupName, String optionId, String optionName,
                      int priceDelta, int costDelta) {}

// 總部維護
List<OptionGroup> optionGroups(Actor a);
OptionGroup saveOptionGroup(Actor a, OptionGroup g);
OptionItem saveOptionItem(Actor a, OptionItem i);
void bindProductOptions(Actor a, String productId, List<String> groupIds);
```

`Catalog.Product` 增加一個欄位供菜單顯示：`List<OptionGroup> optionGroups`（`GET /api/menu` 帶出來，`POST /api/menu` 忽略這個欄位）。

**`resolveOptions` 是本規格的核心。** `coffee-orders` 只呼叫它、只相信它的回傳值，所有驗證與定價都在 `coffee-catalog` 裡完成。這樣加價規則只有一個實作位置。

---

## 6. 選項驗證規則

`resolveOptions(productId, optionIds)` 必須逐條檢查，任何一條不過就丟 `Problem(400, ...)`：

| # | 規則 | 錯誤訊息（繁體中文） |
| --- | --- | --- |
| 1 | `optionIds` 不得為 `null`，長度上限 20 | 選項數量超過上限 |
| 2 | 不得有重複的 `optionId` | 同一個選項不能重複選擇 |
| 3 | 每個 `optionId` 必須存在、`active=true`，且所屬群組 `active=true` | 選項已停用，請重新整理菜單 |
| 4 | 每個選項所屬的群組必須綁定在該 `productId` 上 | 此商品不提供所選的選項 |
| 5 | `selection='SINGLE'` 的群組最多選 1 個 | 「{群組名}」只能選擇一項 |
| 6 | 每個綁定群組的選擇數量須在 `min_select`–`max_select` 之間 | 「{群組名}」需選擇 {min}–{max} 項 |
| 7 | `min_select>=1` 的群組必須有選擇 | 請選擇「{群組名}」 |

規則 6 與 7 的檢查對象是**該商品綁定的所有啟用群組**，不是只有請求裡出現的群組 —— 否則漏送必選群組會直接通過。

`{群組名}` 用實際群組名稱代入，訊息是寫給點餐的顧客與櫃台人員看的。

---

## 7. 金額規則

**這一節是整份規格最不能妥協的部分。** 加價是最容易出現「信任前端傳來金額」漏洞的地方。

- **`LineInput` 只接受 `optionIds`，不得有任何價格欄位。** record 裡不存在 `priceDelta` / `price` / `amount` 欄位，所以前端就算送了也會被 Jackson 丟棄。**不得**為了「方便前端顯示」而在請求 record 上加價格欄位
- 每份單價 = `products.price` + Σ `option_items.price_delta`（該 line 所選選項，值取自 DB，不是請求）
- 每份成本 = `products.cost` + Σ `option_items.cost_delta`
- line 小計 = 每份單價 × `quantity`，用 `Math.multiplyExact` / `Math.addExact`，不要裸算
- **每份單價必須 > 0**，否則 `Problem(400, "商品金額不正確")`。`price_delta` 允許為負（自帶杯折 5 元之類），但不得把單價壓到 0 或負數
- 單一 `price_delta` 限制在 −1000 至 10000、`cost_delta` 限制在 0 至 10000（`saveOptionItem` 時檢查）
- 訂單總額上限 `1000000` 的既有檢查不變
- 全程新台幣整數元，不得出現 `double` / `float` / `BigDecimal`

### 寫進 `order_items` 的欄位

| 欄位 | 值 |
| --- | --- |
| `unit_price` | `products.price`（**不含**加價，維持既有語意） |
| `unit_cost` | `products.cost`（不含加價） |
| `options_price` | Σ `price_delta`（每份，不乘數量） |
| `options_cost` | Σ `cost_delta`（每份，不乘數量） |

把加價獨立成欄位而不是併進 `unit_price`，是為了報表能分得出「本體營收」與「加購營收」，也讓既有以 `unit_price` 為基礎的查詢不會在語意上被偷換。

### 冪等指紋

`OrderService.fingerprint()` 目前是 `SHA-256(Create.toString())`（`OrderService.java:112-121`）。`LineInput` 加上 `optionIds` 之後，選項會自動進入指紋 —— **這是必要的**，否則同一組 `Idempotency-Key` 可以換一組選項重送，拿到的卻是第一筆訂單。

但 `List.toString()` 對順序敏感，`["珍珠","燕麥奶"]` 與 `["燕麥奶","珍珠"]` 會算出不同指紋，同一杯飲料會變成兩張不同的訂單。**計算指紋前必須先把每個 line 的 `optionIds` 排序正規化**（`stream().sorted().toList()`），排序後的結果同時用於指紋與後續寫入。

---

## 8. API

### 8.1 點餐（既有端點的請求格式變更）

`POST /api/orders` 的 `items[]` 從

```json
{ "productId": "latte", "quantity": 1, "temperature": "熱", "sugar": "無糖" }
```

改為

```json
{ "productId": "latte", "quantity": 1, "optionIds": ["temp-hot", "sugar-none", "opt-oatmilk"] }
```

`LineInput` 移除 `temperature` / `sugar` 兩個欄位，改為 `List<String> optionIds`。

**這是一個破壞性的請求格式變更。** 前端與 `CoffeeIntegrationTest` / `HttpWorkflowTest` 裡所有建立訂單的地方都要同步改。`Orders.Order` 回應中的 `items[]` 則**同時**保留舊欄位與新欄位：

```json
{
  "productId": "latte", "name": "經典拿鐵", "category": "經典咖啡",
  "unitPrice": 140, "quantity": 1,
  "temperature": null, "sugar": null,
  "optionsPrice": 20,
  "lineTotal": 160,
  "options": [
    { "groupName": "溫度", "optionName": "熱", "priceDelta": 0 },
    { "groupName": "甜度", "optionName": "無糖", "priceDelta": 0 },
    { "groupName": "鮮乳", "optionName": "換燕麥奶", "priceDelta": 20 }
  ]
}
```

`temperature` / `sugar` 對新訂單一律是 `null`，只有遷移前的歷史訂單有值。`lineTotal = (unitPrice + optionsPrice) × quantity`，由後端算好回傳，前端不要自己乘。

### 8.2 `GET /api/menu`

每個 product 多帶 `optionGroups`，內容是該商品綁定且啟用中的群組與項目，依 `sortOrder` 排序。無綁定時回空陣列。

### 8.3 選項維護（總部）

三個端點都需要 `MENU_MANAGE` 且 `Actor.global()`，比照 `CatalogService.save()` 的既有寫法（`CatalogService.java:62-63`）。都需要 CSRF。

| 方法 | 路徑 | 說明 |
| --- | --- | --- |
| `GET` | `/api/menu/options` | 列出所有群組與項目（含停用的），供管理畫面 |
| `POST` | `/api/menu/options/groups` | 新增或更新群組。`id` 為 `null` 時新增 |
| `POST` | `/api/menu/options/items` | 新增或更新項目。`id` 為 `null` 時新增 |
| `POST` | `/api/menu/{productId}/options` | 設定該商品綁定哪些群組，request body `{"groupIds":[...]}`，整批覆蓋 |

### 錯誤碼

| 狀態 | 情境 | 訊息 |
| --- | --- | --- |
| 400 | 第 6 節任一驗證規則不過 | 見第 6 節表格 |
| 400 | 每份單價 ≤ 0 | 商品金額不正確 |
| 400 | `price_delta` / `cost_delta` 超出範圍 | 加價金額需為 −1000 至 10000 元 |
| 400 | 群組名稱或項目名稱為空或超過 40 字 | 名稱需為 1–40 字 |
| 400 | `min_select > max_select` | 選擇數量下限不能大於上限 |
| 400 | 綁定不存在的群組或商品 | 找不到指定的選項群組 |
| 403 | 無 `MENU_MANAGE` 或非 GLOBAL | 菜單管理限總部範圍 |
| 409 | 刪除／停用仍被商品綁定的群組 | 此選項群組仍有商品使用，請先解除綁定 |

所有訊息一律繁體中文、台灣用語，回應格式固定 `{"message": "..."}`。

---

## 9. 權限與資料範圍

**不新增權限常數。** 選項是菜單的一部分，沿用 `MENU_MANAGE`：

| 操作 | 要求 |
| --- | --- |
| `GET /api/menu`（含 optionGroups） | 登入即可，比照既有菜單查詢 |
| `GET /api/menu/options` | `MENU_MANAGE` + `global()` |
| 三個維護端點 | `MENU_MANAGE` + `global()` |
| 點餐時選擇選項 | `ORDER_CREATE`，比照既有下單 |

選項是全鏈共用的，沒有分店資料範圍問題。分店各自覆寫是 G13 的事，本版不做。

**店長（`MANAGER`）沒有 `MENU_MANAGE`**（見 `InitialData.java:41`），所以店長不能改選項與加價 —— 這是刻意的，定價權歸總部。

---

## 10. 報表必須同步修正

`ReportService` 目前有兩支 SQL 用 `unit_price` 算商品營收（`ReportService.java:81-89` 與 `98-105`）：

```sql
sum(i.unit_price*i.quantity) as revenue, sum(i.unit_cost*i.quantity) as cost
```

而報表頂層的 `revenue` 來自 `orders.total`（`ReportService.java:63`）。**加價進了 `orders.total` 卻沒進 `unit_price`，兩邊就會對不起來**：`categories` 的加總不再等於 `revenue`，`grossProfit = revenue - cost` 會把加價成本漏掉而虛報毛利。

兩支 SQL 都要改成：

```sql
sum((i.unit_price+i.options_price)*i.quantity) as revenue,
sum((i.unit_cost+i.options_cost)*i.quantity) as cost
```

**驗收時要實際驗證** `sum(products[].revenue) == revenue`（同一組篩選條件下）。這是本規格最容易被漏掉的一項。

---

## 11. 驗收條件

**資料層**

- [ ] 新增 `V3__product_options.sql`（**不是 V2**），未修改 `V1__coffee_schema.sql` 與 `V2__payment_reconciliation.sql`
- [ ] 四張新表與索引建立成功
- [ ] `order_items` 新增 `options_price` / `options_cost`，`temperature` / `sugar` 改為可為 null
- [ ] 遷移後 `temperature` / `sugar` 兩個群組與 8 個項目存在，`price_delta` 全為 0
- [ ] 遷移後所有非「手作烘焙」商品綁定這兩個群組，「手作烘焙」商品一個都沒綁
- [ ] 空資料庫啟動後 Flyway 遷移到 V3 無錯誤
- [ ] **遷移不改變任何既有商品的售價**（以遷移前後的 `products.price` 比對驗證）

**選項模型**

- [ ] 第 6 節七條驗證規則全部實作，錯誤訊息與表格一致
- [ ] 必選群組漏送時被擋（規則 7 檢查的是商品綁定的群組，不是請求裡出現的群組）
- [ ] `SINGLE` 群組送兩個選項被擋
- [ ] 送別的商品才有的 `optionId` 被擋
- [ ] 送已停用的選項或已停用群組的選項被擋

**金額**

- [ ] `LineInput` 沒有任何價格欄位
- [ ] 加價一律取自 `option_items.price_delta`，請求中的任何價格欄位都不影響結果
- [ ] 每份單價 = `products.price + Σ price_delta`，line 小計用 `Math.multiplyExact` / `addExact`
- [ ] 每份單價 ≤ 0 時被擋
- [ ] `order_items.unit_price` 維持「不含加價」語意，加價寫在 `options_price`
- [ ] `order_item_options` 完整快照 `group_name` / `option_name` / `price_delta` / `cost_delta`
- [ ] 改選項定價後，既有訂單的金額與快照**完全不變**

**冪等**

- [ ] `optionIds` 進入 `fingerprint`
- [ ] 計算指紋前 `optionIds` 已排序正規化：選項相同、順序不同的兩次請求，用同一把 `Idempotency-Key` 會拿到同一張訂單
- [ ] 同一把 `Idempotency-Key` 換一組**不同**選項重送 → 400「同一識別碼不能用於不同訂單」

**邊界**

- [ ] `coffee-orders` 未直接查 `option_groups` / `option_items` / `product_option_groups`
- [ ] `coffee-orders` 未引用 `com.coffee.catalog.internal`
- [ ] `OrderService.validateOptions()` 的硬編分類字串與溫度／甜度集合已刪除
- [ ] 未引入任何新的第三方相依
- [ ] `ModuleBoundariesTest` 通過

**報表**

- [ ] `ReportService` 兩支商品彙整 SQL 已計入 `options_price` / `options_cost`
- [ ] 同一組篩選條件下 `sum(products[].revenue) == revenue`
- [ ] `grossProfit` 把加價成本計入

**既有行為不回歸**

- [ ] `CoffeeIntegrationTest` / `HttpWorkflowTest` / `ModuleBoundariesTest` / `CheckMacTest` 全數通過
- [ ] 遷移前建立的歷史訂單仍能正確顯示（`temperature` / `sugar` 有值、`options` 為空）

**前端**

- [ ] 點餐畫面依 `optionGroups` 動態渲染選項，必選群組未選時不能加入購物車
- [ ] 選項加價即時反映在小計上，但**送出時只送 `optionIds`**
- [ ] 菜單管理畫面可維護群組、項目與商品綁定
- [ ] 訂單明細同時能顯示新訂單的 `options` 與歷史訂單的 `temperature` / `sugar`
- [ ] 金額顯示使用 `shared/format.ts`，HTTP 經過 `shared/api.ts`
- [ ] `npm run build` 通過

---

## 12. 測試要求

放在 `backend/coffee-app/src/test/java/com/coffee/app/`。

### 單元測試（不需 Spring context 優先）

- [ ] 第 6 節七條驗證規則，逐條一個案例
- [ ] 每份單價計算：無選項、單一加價、多個加價、負加價、負加價把單價壓到 0（應被擋）
- [ ] `Math.multiplyExact` 溢位路徑：極大 `price_delta` × 極大 `quantity`
- [ ] 指紋正規化：`["a","b"]` 與 `["b","a"]` 算出相同指紋

### 整合測試

- [ ] 點一杯帶 +20 加價的飲料，`orders.total` = (price + 20) × quantity
- [ ] `order_item_options` 有對應筆數，`price_delta` 與下單當下一致
- [ ] 下單後把該選項的 `price_delta` 改掉，既有訂單的 `total` 與快照不變
- [ ] **金額竄改測試**：請求 JSON 裡硬塞 `"priceDelta": -100` / `"unitPrice": 1` / `"lineTotal": 1`，訂單金額不受影響（用 `MockMvc` 送原始 JSON，不要用 record 建構）
- [ ] 送別的商品的 `optionId` → 400
- [ ] 必選群組漏送 → 400
- [ ] 同一把 `Idempotency-Key`、選項順序不同 → 回同一張訂單，只建立一次
- [ ] 同一把 `Idempotency-Key`、選項內容不同 → 400
- [ ] 遷移後點「手作烘焙」商品不需要也不能選溫度甜度
- [ ] 報表：建立含加價的已付款訂單後，`sum(products[].revenue) == revenue`，且 `grossProfit` 已扣掉 `options_cost`

### 越權測試（`AGENTS.md` 明列必要項）

- [ ] 顧客（`SELF`）呼叫四個維護端點皆得 403
- [ ] 店長（`MANAGER`，有 `ORDER_MANAGE` 但無 `MENU_MANAGE`）呼叫維護端點得 403
- [ ] 有 `MENU_MANAGE` 但 scope 為 `BRANCH` 的帳號得 403（`global()` 檢查）
- [ ] 未登入得 401
- [ ] 缺 CSRF token 的維護 `POST` 被擋 —— **請用一個該帳號本來有權限的請求來驗**，否則 403 可能來自權限而不是 CSRF，斷言會失去意義

### HTTP 流程測試

- [ ] 於 `HttpWorkflowTest` 補一段真實 HTTP + Cookie + CSRF：總部建立一個 +20 的選項 → 綁定到商品 → 顧客點該商品並選該選項 → 驗證訂單總額含加價 → 報表數字相符

---

## 13. 待 PO 決定

Codex 實作前如果這幾項未定，請照括號內的**預設值**做，並在 PR 描述標明。

1. **負加價要不要開放** —— 自帶杯折 5 元是常見做法，但負加價本質上是折扣，會繞過 G07 未來的折扣稽核。（預設：開放，下限 −1000，且每份單價必須 > 0）
2. **選項成本 `cost_delta`** —— 換燕麥奶的成本確實比較高，不記錄會虛報毛利。但要求總部維護每個選項的成本會增加建檔負擔。（預設：記錄，預設值 0）
3. **歷史訂單的溫度甜度要不要回填** —— 回填成 `order_item_options` 可以讓顯示邏輯只有一套，但那是改寫歷史快照。（預設：不回填，前端兩種都要能渲染）
4. **選項群組可否跨商品共用** —— 本規格的設計是可以（`product_option_groups` 是多對多），所以改「甜度」會影響所有綁定的商品。也可以改成每個商品獨立一份。（預設：共用）
5. **停用選項群組時的處理** —— 目前規格是「仍被商品綁定就回 409，要先解綁」。也可以改成允許停用、由查詢端過濾。（預設：409）
6. **點餐 UI 的選項呈現** —— 加價選項多的時候（例如 5 個群組），手機版點餐流程會變長。要不要做「常用組合」快捷？（預設：不做，另開缺口）

---

## 14. 給 Codex 的提醒

- `AGENTS.md` 工作流程第 1 步：**先輸出設計摘要，等 PO 確認再動工**
- 這份規格若有錯、不完整或技術上做不到，**先回報，不要自己補洞後默默實作**
- 第 8.1 節的 `LineInput` 是**破壞性變更**，前端與既有測試都要同步改。請在 PR 描述誠實列出所有被動到的檔案
- 第 10 節的報表修正最容易漏。加價進了 `orders.total` 卻沒進商品彙整，報表會安靜地對不起來，沒有任何錯誤訊息
- 第 7 節是本規格的核心：**任何情況下都不要相信請求裡的金額**。`LineInput` 上不要為了方便而加價格欄位
- 版號用 **V3**，V2 留給 PR #9
- 實作回報寫到 `docs/reports/`
