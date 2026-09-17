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
- **`InitialData` 的商品 seed 後綁定預設選項，以及 `order_items` seed 的欄位清單修正**（見第 4.3 節）
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
| `coffee-app` | Flyway migration；`InitialData` 的預設綁定與 `order_items` seed 修正 |
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
  price_delta INTEGER NOT NULL CHECK(price_delta>=0),
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
-- 注意：這一段只對「執行 V3 當下已存在的商品」生效，見 4.3 節
INSERT INTO product_option_groups(product_id,group_id,sort_order)
  SELECT id,'temperature',1 FROM products WHERE category <> '手作烘焙'
  UNION ALL
  SELECT id,'sugar',2 FROM products WHERE category <> '手作烘焙';
```

`price_delta` 全部為 0，所以**遷移不改變任何既有商品的售價**。遷移後 `validateOptions` 的行為由這份資料完整取代。

歷史 `order_items` 的 `temperature` / `sugar` 字串**不回填**進 `order_item_options`（見第 13.3 節）。舊訂單繼續由這兩個欄位顯示，新訂單由 `order_item_options` 顯示，前端兩者都要能渲染。

### 4.3 `InitialData` 必須一起改（全新資料庫會壞在這裡）

Flyway 在應用啟動時執行，`InitialData` 是 `ApplicationRunner`，**在 Flyway 之後才跑**。所以有兩個問題，兩個都會讓全新資料庫壞掉：

**問題一：全新資料庫上 V3 的預設綁定是空的。**

V3 的 `INSERT ... SELECT FROM products` 在既有資料庫上正確（商品已存在），但在全新資料庫上執行時 `products` 還是空的 —— demo 商品要等 `InitialData` 才建立。結果是所有 demo 飲品**一個選項群組都沒綁**，溫度與甜度整個消失。

**修法**：`InitialData` 在 seed 完商品之後，補上預設綁定。`InitialData` 每次啟動都會執行，所以這段必須是冪等的：

```java
// 在 products seed 之後
for (String[] p : ps)
  if (!p[3].equals("手作烘焙"))
    for (String[] g : new String[][] {{"temperature", "1"}, {"sugar", "2"}})
      db.update(
          "insert into product_option_groups(product_id,group_id,sort_order)"
              + " select ?,?,? where not exists(select 1 from product_option_groups"
              + " where product_id=? and group_id=?)",
          p[0], g[0], Integer.parseInt(g[1]), p[0], g[0]);
```

實作方式不限（`ON CONFLICT DO NOTHING` 也可以，但要確認 H2 的 PostgreSQL 模式支援），**但必須冪等**，重複啟動不得失敗也不得產生重複列。

**問題二：`order_items` 的 seed 會因欄位數不符而啟動失敗。**

`InitialData.java:130` 目前是：

```java
db.update("insert into order_items values(?,?,?,?,?,?,?,?,?,?)", ...)   // 10 個位置參數，無欄位名稱
```

V3 用 `ALTER TABLE` 加了 `options_price` 與 `options_cost` 之後，`order_items` 變成 12 欄，這句十參數的位置式 INSERT 會直接拋錯，`app.seed-demo=true` 的環境**啟動就失敗**。

**修法**：改為明確欄位清單。兩個新欄位有 `DEFAULT 0`，所以列出原本的十欄即可：

```java
db.update(
    "insert into"
        + " order_items(id,order_id,product_id,name,category,unit_price,unit_cost,quantity,temperature,sugar)"
        + " values(?,?,?,?,?,?,?,?,?,?)", ...)
```

這些 seed 出來的是歷史訂單，`temperature` / `sugar` 保留原本的字串值、不建立 `order_item_options`，正好可以當成「遷移前的歷史訂單」測試資料。

**一般規則**：本規格對既有表新增欄位，Codex 開工時請先 `grep` 全專案有沒有其他不帶欄位名稱的 `insert into <該表> values(...)`，一併改成明確欄位清單。目前已知只有 `InitialData.java:130` 這一處，但請自行確認。

---

## 5. `Catalog` api 變更

`com.coffee.catalog.api.Catalog` 新增（全部是 `api` package 內的 record 與 interface method，不放實作）：

```java
record OptionItem(String id, String groupId, String name, int priceDelta, int costDelta,
                  boolean active, int sortOrder) {}

record OptionGroup(String id, String name, String selection, int minSelect, int maxSelect,
                   boolean active, int sortOrder, List<OptionItem> items) {}

/**
 * 點餐與菜單顯示用：某商品綁定的、且啟用中的選項群組，依 sortOrder 排序。
 * 回傳的 OptionItem 一律 costDelta=0，見第 5.1 節。
 */
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

### 5.1 選項成本絕對不能外流到菜單回應

`CatalogService.list(actor, manage)` 目前對非管理呼叫端**刻意把 `Product.cost` 重建為 `0`**（`CatalogService.java:41-51`）。成本是內部經營資料，不給顧客也不給收銀員看。

`optionGroups` 嵌進 `Product` 之後，`GET /api/menu` 會把整棵樹序列化出去。**如果 `OptionItem.costDelta` 照實帶出來，等於從旁邊繞過上面那道既有保護** —— 顧客可以直接讀到「換燕麥奶的成本是 12 元」。

規則（比照既有 `cost` 的處理方式，不要另創機制）：

| 路徑 | `costDelta` |
| --- | --- |
| `GET /api/menu`（`list(a, false)`，任何登入者） | **一律 `0`** |
| `GET /api/menu?manage=true`（`MENU_MANAGE` + `global()`） | 真實值 |
| `GET /api/menu/options`（`MENU_MANAGE` + `global()`） | 真實值 |
| `productOptions(productId)` | **一律 `0`**（它是給菜單顯示用的） |
| `resolveOptions(...)` → `ResolvedOption.costDelta` | 真實值。**這是模組內呼叫，不是 HTTP 回應**，由 `coffee-orders` 寫進 `order_items.options_cost` |
| 訂單回應的 `items[].options[]` | **不含任何成本欄位**（比照 `Orders.Line` 本來就沒有 `unitCost`） |

`ResolvedOption` 帶真實成本是必要的 —— `options_cost` 要寫進資料庫供報表算毛利。但它**不得**出現在任何送到瀏覽器的 DTO 裡。`Orders.Line` 新增的 `options` 欄位，其 record 只放 `groupName` / `optionName` / `priceDelta`，不放 `costDelta`。

同理，`order_item_options.cost_delta` 只有 `coffee-reporting` 的彙整 SQL 會讀，不經任何端點回傳。

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
- **`price_delta` 不得為負**（第 13.1 節定案）。加價就是加價，折扣一律留給 G07。schema 有 `CHECK(price_delta>=0)`，`saveOptionItem` 也要在應用層擋，不要只靠 DB constraint 丟出英文錯誤
- 單一 `price_delta` 與 `cost_delta` 皆限制在 0 至 10000（`saveOptionItem` 時檢查）
- **每份單價必須 > 0**。在 `price_delta >= 0` 的前提下這條只會在 `products.price` 本身有問題時觸發（既有 schema 已有 `CHECK(price>0)`），但仍然要檢查 —— 它是 G07 導入折扣後的第一道防線，現在就寫好

> `order_item_options.price_delta`（快照表）**刻意不加** `CHECK(price_delta>=0)`。它記錄的是「當時實際收了多少」，G07 導入折扣後可能出現負值，歷史快照不該因為當下的規則而寫不進去。
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

**`items[].costDelta` 一律為 `0`**，比照同一支回應裡 `cost` 已經是 `0` 的既有行為（第 5.1 節）。只有 `manage=true` 的呼叫端拿得到真實成本。

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
| 400 | `price_delta` / `cost_delta` 為負或超過 10000 | 加價金額需為 0 至 10000 元 |
| 400 | 群組名稱或項目名稱為空或超過 40 字 | 名稱需為 1–40 字 |
| 400 | `min_select > max_select` | 選擇數量下限不能大於上限 |
| 400 | 綁定不存在的群組或商品 | 找不到指定的選項群組 |
| 403 | 無 `MENU_MANAGE` 或非 GLOBAL | 菜單管理限總部範圍 |
| 409 | 刪除／停用仍被商品綁定的群組 | 此選項群組仍有商品使用，請先解除綁定 |

所有訊息一律繁體中文、台灣用語，回應格式固定 `{"message": "..."}`。

---

## 9. 權限與資料範圍

**不新增權限常數。** 選項是菜單的一部分，沿用 `MENU_MANAGE`：

| 操作 | 要求 | 成本可見性 |
| --- | --- | --- |
| `GET /api/menu`（含 optionGroups） | 登入即可，比照既有菜單查詢 | `cost` 與 `costDelta` 皆為 `0` |
| `GET /api/menu?manage=true` | `MENU_MANAGE` + `global()` | 真實成本 |
| `GET /api/menu/options` | `MENU_MANAGE` + `global()` | 真實成本 |
| 三個維護端點 | `MENU_MANAGE` + `global()` | 真實成本 |
| 點餐時選擇選項 | `ORDER_CREATE`，比照既有下單 | 回應不含成本 |
| 查看訂單明細 | 比照既有訂單查詢 | 回應不含成本 |

成本可見性的規則見第 5.1 節。**店長（`BRANCH` scope）也看不到成本** —— 既有的 `list(a, manage)` 對 `manage=true` 同時要求 `MENU_MANAGE` 與 `global()`，本規格不放寬這點。

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
- [ ] **既有資料庫升級**：升級前已存在的所有非「手作烘焙」商品綁定這兩個群組，「手作烘焙」商品一個都沒綁
- [ ] **全新資料庫 + `app.seed-demo=true` 啟動**：demo 的 6 項飲品都綁到溫度與甜度，2 項烘焙商品都沒綁（驗證第 4.3 節問題一已修）
- [ ] **全新資料庫 + `app.seed-demo=false` 啟動**（`HttpWorkflowTest` 的模式）無錯誤
- [ ] `InitialData` 的預設綁定是冪等的：連續啟動兩次不失敗、不產生重複列
- [ ] `InitialData` 的 `order_items` seed 已改為明確欄位清單，demo 環境啟動不再因欄位數不符失敗（驗證第 4.3 節問題二已修）
- [ ] seed 出的歷史訂單 `options_price` / `options_cost` 為 0，`temperature` / `sugar` 保留原字串
- [ ] 全專案已無其他不帶欄位名稱的 `insert into order_items values(...)`
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

**成本保護（第 5.1 節）**

- [ ] `GET /api/menu` 對顧客、收銀員、店長回應中，所有 `cost` 與 `costDelta` 皆為 `0`
- [ ] `GET /api/menu?manage=true` 對總部回應真實 `cost` 與 `costDelta`
- [ ] `Orders.Line` 新增的 `options` record 沒有任何成本欄位
- [ ] 訂單查詢回應（`GET /api/orders`、`GET /api/orders/{id}`）不含任何選項成本
- [ ] `order_items.options_cost` 有正確寫入（供報表用），但不經任何端點回傳

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
- [ ] **群組清單顯示「目前有 N 個商品使用」並可展開看清單**（第 13.4 節：群組是跨商品共用的，沒有這個提示，改一個群組會在總部看不見的地方影響一堆商品）
- [ ] **停用被綁定的群組時，409 的畫面要列出還綁著的商品，並提供解綁入口**（第 13.5 節：不然「請先解綁」無從下手）
- [ ] 訂單明細同時能顯示新訂單的 `options` 與歷史訂單的 `temperature` / `sugar`
- [ ] 金額顯示使用 `shared/format.ts`，HTTP 經過 `shared/api.ts`
- [ ] `npm run build` 通過

---

## 12. 測試要求

放在 `backend/coffee-app/src/test/java/com/coffee/app/`。

### 單元測試（不需 Spring context 優先）

- [ ] 第 6 節七條驗證規則，逐條一個案例
- [ ] 每份單價計算：無選項、單一加價、多個加價、加價後溢位
- [ ] `saveOptionItem` 拒絕負的 `price_delta`（第 13.1 節），錯誤訊息為繁體中文而非 DB constraint 的英文
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

### 成本不外洩測試（第 5.1 節）

- [ ] 顧客、收銀員、店長三種角色各打一次 `GET /api/menu`，**掃過整份 JSON 回應**確認沒有任何非零的 `cost` / `costDelta`。建議用字串或樹走訪斷言，不要只檢查第一個商品 —— 洩漏會發生在巢狀的 `optionGroups[].items[]` 裡
- [ ] 同三種角色查訂單明細，回應不含任何成本欄位
- [ ] 總部 `GET /api/menu?manage=true` 拿得到真實 `costDelta`（確認遮蔽沒有做過頭，把管理介面也弄壞）
- [ ] `resolveOptions` 回傳的 `ResolvedOption.costDelta` 是真實值，且 `order_items.options_cost` 有正確寫入

### 啟動與初始化測試（第 4.3 節）

- [ ] 全新 H2 + `app.seed-demo=true` 啟動後，查 `product_option_groups` 確認 demo 飲品綁定完整
- [ ] 同一個資料庫再啟動一次不失敗（冪等）
- [ ] 全新資料庫 + demo seed 後，歷史訂單查得到且 `temperature` / `sugar` 有值

### 越權測試（`AGENTS.md` 明列必要項）

- [ ] 顧客（`SELF`）呼叫四個維護端點皆得 403
- [ ] 店長（`MANAGER`，有 `ORDER_MANAGE` 但無 `MENU_MANAGE`）呼叫維護端點得 403
- [ ] 有 `MENU_MANAGE` 但 scope 為 `BRANCH` 的帳號得 403（`global()` 檢查）
- [ ] 未登入得 401
- [ ] 缺 CSRF token 的維護 `POST` 被擋 —— **請用一個該帳號本來有權限的請求來驗**，否則 403 可能來自權限而不是 CSRF，斷言會失去意義

### HTTP 流程測試

- [ ] 於 `HttpWorkflowTest` 補一段真實 HTTP + Cookie + CSRF：總部建立一個 +20 的選項 → 綁定到商品 → 顧客點該商品並選該選項 → 驗證訂單總額含加價 → 報表數字相符

---

## 13. 設計決策（PM / SA 定案）

PO 已把設計決策授權給 Claude（見 `AGENTS.md`「設計決策的歸屬」）。以下六項**已經定案，直接照做，不要等確認**。每項附上判斷理由與推翻它的代價，供下一輪推翻用。

### 13.1 負加價 — **不允許**（與 v1 草稿相反）

`price_delta` 必須 `>= 0`，schema 加 `CHECK(price_delta>=0)`。自帶杯折 5 元這類需求**留給 G07 折扣**。

**理由**：負加價本質上是折扣，但走的是「加價」這條路 —— 它不會出現在任何折扣報表裡，也不會留下「這筆便宜了多少、誰核准的」紀錄。目前 G11（稽核）與 G07（折扣）都還不存在，開放負加價等於在兩者之前先開一條沒有稽核、沒有報表可見度的降價路徑，而且任何有 `MENU_MANAGE` 的人建好之後，每個收銀員都能套用。

**這也是不對稱風險**：事後放寬（把 CHECK 拿掉）幾乎零成本；事後收緊則要處理已經存在的負選項與已經產生的歷史訂單，貴得多。不確定的時候選容易反悔的那邊。

**代價**：自帶杯折扣要等 G07。這是體驗損失，不是營收損失 —— 加價品項（本規格的主要目的）完全不受影響。

> v1 草稿的預設值是「開放，下限 −1000」。定案時改為不允許，理由如上。第 7 節與第 8 節的錯誤碼表已同步更新。

### 13.2 選項成本 `cost_delta` — **記錄，預設 0**

換燕麥奶的成本確實比較高，不記錄會虛報毛利，而報表毛利是總部看的主要數字之一。建檔負擔用「預設 0」化解：總部可以先不填，之後再補。

**代價**：總部要維護一份額外的成本資料。可見性規則見第 5.1 節。

### 13.3 歷史訂單的溫度甜度 — **不回填**

舊 `order_items` 的 `temperature` / `sugar` 字串保持原樣，不寫進 `order_item_options`。

**理由**：那是改寫歷史快照。本專案在訂單快照上一貫的原則是「之後改菜單不回寫歷史」（第 1 節目標 4、`AGENTS.md` 金額規則），回填會自相矛盾。多一套顯示邏輯是可接受的代價，而且它會隨時間自然消失。

**代價**：前端要能渲染兩種格式，直到舊訂單過了保留期限。

### 13.4 選項群組 — **跨商品共用**（多對多）

`product_option_groups` 維持多對多。改「甜度」會一次影響所有綁定的商品。

**理由**：另一個選擇是每個商品一份獨立群組，那會讓「新增一個糖度選項」變成要改 N 個商品，總部一定會漏。共用的風險（改一個影響很多）比漏改的風險好控制。

**附帶要求**：管理畫面在群組旁邊**必須顯示「目前有 N 個商品使用」**，並可展開看清單。沒有這個，共用就變成看不見的地雷。這一條列入第 11 節前端驗收。

### 13.5 停用仍被綁定的群組 — **回 409，要先解綁**

**理由**：另一個選擇是允許停用、由查詢端過濾，但那會產生模糊狀態 —— 群組停用了，已綁定的商品到底還能不能點？必選群組停用後，那個商品是不是就點不了了？409 把這個決定推回給操作的人，明確且可預期。

**附帶要求**：409 的回應要能讓人知道**哪些商品還綁著**，否則「先解綁」無從下手。管理畫面直接提供解綁入口。

### 13.6 點餐 UI 的「常用組合」快捷 — **不做，另開 G17**

本版的點餐 UI 就是老實地一個群組一個群組選。

**理由**：常用組合要先有資料才知道哪些組合常用，現在沒有任何選項資料，做出來的一定是猜的。等 G06 上線跑一段時間，用真實訂單決定要不要做、做哪些。

**代價**：選項多的商品，手機版流程會偏長。先觀察，不要預先最佳化。已登記為 G17（見 `docs/GAP-ANALYSIS.md`）。

---

## 14. 給 Codex 的提醒

- **不需要等 PO 確認設計摘要**（見 `AGENTS.md`「設計決策的歸屬」）。輸出摘要留紀錄，然後直接開工
- 這份規格若有錯、不完整或技術上做不到，**明講出來**（設計摘要與 PR 描述），但**講完就繼續做** —— 依你的判斷補上合理處理並標明，缺口由下一輪規格修補。要避免的是默默補洞，不是補洞
- 第 13 節六項已經定案，**不要再當成待決事項**。特別注意 13.1 的負加價**不允許**，與早期草稿相反
- 第 8.1 節的 `LineInput` 是**破壞性變更**，前端與既有測試都要同步改。請在 PR 描述誠實列出所有被動到的檔案
- 第 10 節的報表修正最容易漏。加價進了 `orders.total` 卻沒進商品彙整，報表會安靜地對不起來，沒有任何錯誤訊息
- 第 7 節是本規格的核心：**任何情況下都不要相信請求裡的金額**。`LineInput` 上不要為了方便而加價格欄位
- 第 5.1 節同樣不能妥協：`costDelta` 嵌在 `Product.optionGroups` 裡，一不小心就會跟著 `GET /api/menu` 整棵樹序列化出去。既有的 `cost` 遮蔽擋不到巢狀結構
- 第 4.3 節是 `InitialData` 的兩個坑，**兩個都會讓全新資料庫啟動失敗或資料不完整**，而且在既有資料庫上測不出來。請務必用全新的 H2 實際跑一次 demo seed
- 版號用 **V3**，V2 留給 PR #9
- 實作回報寫到 `docs/reports/`
