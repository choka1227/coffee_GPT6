# G13 — 分店菜單可用性與售罄

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G13 |
| 優先順序 | P0（僅次於 G06） |
| 規格版本 | v1 |
| 撰寫 | Claude（PM / SA），2026-09-17 |
| 實作 | Codex（PG / SD） |
| 基準 commit | `7b09f4b`（PR #10 合併後的 `feature/init-project`） |

> **排程前提：本規格與 G06 都會修改 `Catalog.sellable()` 的簽章與 `OrderService.create()` 的品項迴圈。兩者不要同時開工。** 建議順序為 G06 先實作合併，G13 再從最新主線開分支。若 PO 決定倒過來做，請在開工前回報，本規格第 4、5 節的版號與簽章需要對調調整。

---

## 1. 背景與目標

### 問題

`products` 表沒有任何分店維度：

```sql
-- V1__coffee_schema.sql:5
CREATE TABLE products(id VARCHAR(36) PRIMARY KEY,name VARCHAR(80) NOT NULL,...,active BOOLEAN NOT NULL,sort_order INTEGER NOT NULL DEFAULT 99);
```

可售判斷只看全域的 `active` 布林：

```java
// CatalogService.java:55-59
public Product sellable(String id) {
  return db.query("select * from products where id=? and active=true", this::row, id).stream()
      .findFirst()
      .orElseThrow(() -> new Problem(400, "商品已下架，請重新整理菜單"));
}
```

三家分店（`taipei` / `banqiao` / `taichung`，`InitialData.java:59-67`）共用同一份菜單。造成兩個每天都會踩到的問題：

1. **「今天這項賣完了」做不到。** 中山店可頌賣完，店員唯一的手段是請總部把 `active` 設成 `false`，但那會讓板橋店與台中店的可頌**同時下架**。實務上店員只能繼續讓顧客下單，再在出餐時道歉退單 —— 而系統連退單都沒有（G03 尚未實作），變成現場用現金私下處理，帳就對不起來。
2. **分店限定品項做不到。** 新店試賣、區域限定、某店沒有烤箱所以不供應烘焙類 —— 目前都只能靠店員口頭記憶。

`OrderService.create()` 在第 59 行呼叫 `catalog.sellable(l.productId())` 時**根本沒有把 `q.branchId()` 傳進去**，即使資料層補了分店維度，不改這個呼叫也擋不住越店下單。

前端 `MenuView.vue:72` 是先載 `/menu` 再載 `/branches`，菜單清單與當下選的分店完全無關，切換分店時菜單不會變。

### 目標

1. 分店可以把單一商品標記為**今日售完**，隔日自動恢復，不需要任何排程作業
2. 總部可以決定**某分店不供應某商品**（分店限定品項的反面）
3. 「可不可以賣」的最終判斷在後端，**下單時依下單分店重新查核**，前端隱藏只是體驗
4. 售完與不供應的操作進稽核軌跡（誰、哪一店、哪個商品、何時）
5. 既有資料零遷移成本：沒有設定的分店 × 商品組合，行為與現在完全相同

### 不是目標

- **分店各自定價（區域定價）** —— 盤點提到過，但定價權會牽動金額重算、成本快照與報表毛利三處，與 G06 的加價計算直接相撞。**本版明確不做**，建議另立 G17 獨立處理（見第 11 節）
- **庫存扣減（G08）** —— 本規格是人工標記，不是自動扣減。售完是店員按下去的，不是系統算出來的
- 補貨提醒、售完統計報表
- 分店各自的商品說明、圖片、排序

---

## 2. 範圍

### 在範圍

- `branch_products` 覆寫表與 Flyway migration
- 新權限 `MENU_AVAILABILITY`（含既有資料庫的角色授權 migration，與 `InitialData` 的 seed 同步）
- `Catalog` api 擴充：`sellable` 帶分店、菜單查詢帶分店
- `OrderService.create()` 改為依下單分店查核
- 可用性維護 API（店端售完、總部供應設定）
- `audit_log` 寫入
- 前端：點餐畫面依分店重載菜單、售完標示與店員切換按鈕；總部菜單維護畫面的分店供應設定
- `docs/API.md` 的端點表更新

### 不在範圍

- `products` 表結構不動（不加 `branch_id`，見第 3 節設計說明）
- `coffee-reporting` 不動。歷史訂單已快照名稱與單價，售完與否不影響任何已完成的營收數字
- 不動 `V1__coffee_schema.sql`、`V2__payment_reconciliation.sql`（`AGENTS.md` 禁止事項第 6 條）
- 不引入任何新的第三方相依，不引入排程框架（本設計刻意不需要 `@Scheduled`）

---

## 3. 涉及模組與邊界

| 模組 | 變更 |
| --- | --- |
| `coffee-catalog` | 主要實作位置：`branch_products` 讀寫、可用性 API、`Catalog` interface 擴充 |
| `coffee-orders` | `create()` 改呼叫帶分店的 `sellable`；不直接碰 `branch_products` |
| `coffee-identity` | `Identity.PERMISSIONS` 新增一個常數 |
| `coffee-app` | Flyway migration；`InitialData` 的角色權限 seed |
| `frontend/src/modules/ordering` | `MenuView.vue`：依分店載菜單、售完標示與切換按鈕 |
| `frontend/src/modules/catalog` | `MenuAdminView.vue`：總部的分店供應設定 |

### 邊界規則（`ModuleBoundariesTest` 會驗）

- **`branch_products` 歸 `coffee-catalog`。** `coffee-orders`、`coffee-reporting`、前端一律不得直接查這張表
- `coffee-catalog` 目前**不依賴 `coffee-branches`**（見第 8 節的依賴決議）。本規格**不新增**這條依賴 —— 分店存在與否的檢查留在 `coffee-orders`（`OrderService.create():49` 已經有 `branches.requireOpen()`），`coffee-catalog` 只用 `branchId` 當字串鍵，資料完整性交給 FK
- `coffee-orders` 只能透過 `Catalog` 這個 `api` interface 判斷可售性，不得引用 `com.coffee.catalog.internal`

### 為什麼是覆寫表，不是在 `products` 加 `branch_id`

在 `products` 加 `branch_id` 等於每家店各一份商品列，八個商品三家店變二十四列。後果：`order_items.product_id` 的 FK 指向哪一列？總部改一次價要更新三列？報表的「商品營收」要跨三個 id 合併？—— 每一個都是新的缺口。

覆寫表的語意是「全鏈一份主檔，分店只覆寫可用性」：**沒有覆寫列 = 完全沿用現在的行為**，既有資料不需要任何 migration，回退也只要不查這張表。

---

## 4. DB schema 與 migration

**新增檔案：`backend/coffee-app/src/main/resources/db/migration/V4__branch_menu_availability.sql`**

> **版號注意：`V3` 已由 `docs/specs/G06-product-options.md` 預約。** 若 G06 先合併，本規格就是 V4；若本規格先開工，請取當下 migration 目錄中**下一個未使用的版號**，不要照抄本文的數字。撞號要重跑整個資料庫。

```sql
CREATE TABLE branch_products(
  branch_id VARCHAR(36) NOT NULL REFERENCES branches(id),
  product_id VARCHAR(36) NOT NULL REFERENCES products(id),
  availability VARCHAR(12) NOT NULL CHECK(availability IN ('AVAILABLE','SOLD_OUT','UNLISTED')),
  sold_out_date INTEGER,
  updated_at BIGINT NOT NULL,
  updated_by VARCHAR(36) NOT NULL REFERENCES accounts(id),
  PRIMARY KEY(branch_id,product_id),
  CHECK((availability='SOLD_OUT' AND sold_out_date IS NOT NULL)
     OR (availability<>'SOLD_OUT' AND sold_out_date IS NULL))
);

CREATE INDEX idx_branch_products_branch ON branch_products(branch_id,availability);

INSERT INTO role_permissions(role_code,permission)
  SELECT 'MANAGER','MENU_AVAILABILITY'
  WHERE EXISTS(SELECT 1 FROM roles WHERE code='MANAGER')
    AND NOT EXISTS(SELECT 1 FROM role_permissions
                   WHERE role_code='MANAGER' AND permission='MENU_AVAILABILITY');

INSERT INTO role_permissions(role_code,permission)
  SELECT 'CASHIER','MENU_AVAILABILITY'
  WHERE EXISTS(SELECT 1 FROM roles WHERE code='CASHIER')
    AND NOT EXISTS(SELECT 1 FROM role_permissions
                   WHERE role_code='CASHIER' AND permission='MENU_AVAILABILITY');

INSERT INTO role_permissions(role_code,permission)
  SELECT 'HQ','MENU_AVAILABILITY'
  WHERE EXISTS(SELECT 1 FROM roles WHERE code='HQ')
    AND NOT EXISTS(SELECT 1 FROM role_permissions
                   WHERE role_code='HQ' AND permission='MENU_AVAILABILITY');
```

### 4.1 三個狀態的語意

| `availability` | 意義 | 誰能設 | 顧客看到 | 能不能下單 |
| --- | --- | --- | --- | --- |
| （無此列） | 沿用全鏈設定 | — | 依 `products.active` | 依 `products.active` |
| `AVAILABLE` | 明確供應（用於解除售完或解除不供應） | 店端 / 總部 | 正常顯示 | 可以 |
| `SOLD_OUT` | 今日售完 | 店端（限自店） | 顯示但標「今日售完」且不可加入 | **不可以** |
| `UNLISTED` | 本店不供應 | **總部** | 完全不顯示 | **不可以** |

`products.active=false`（全鏈下架）優先於任何分店設定：全鏈下架的商品，分店設成 `AVAILABLE` 也不可售。

### 4.2 `sold_out_date` 為什麼是 INTEGER 而不是 BIGINT epoch

`AGENTS.md` 的「時間」規則是針對**時間點**（`created_at`、`paid_at` 這種）。`sold_out_date` 不是時間點，是**業務日期**：「這個售完標記屬於哪一個營業日」。

存成 `yyyyMMdd` 的整數（例：`20260917`），以 `ZoneId.of("Asia/Taipei")` 換算，好處是：

- **隔日自動恢復，不需要任何排程作業。** 判斷式就是 `sold_out_date = 今天的台北日期`，日期一過，同一列自動失效。本專案主程式碼目前沒有任何 `@Scheduled` 或 `@EnableScheduling`（`docs/GAP-ANALYSIS.md` G01 段落已載明），本規格刻意不引入
- 不會有時區換算誤差累積，也不會出現「凌晨 00:05 恢復供應」這種半夜跳動

實作時一律用同一個 helper 取得今日日期，不要各處自行 `LocalDate.now()`：

```java
private int today() {
  return Integer.parseInt(
      java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei"))
          .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
}
```

**換日時間定為台北午夜 00:00。** 若 PO 認為營業日應該以開店時間（例如 05:00）為界，見第 11 節待決事項 —— 那會改變本節的計算方式，不要自行決定。

### 4.3 既有資料庫的角色授權

`InitialData.run()` 的角色 seed **只在 `roles` 表為空時執行**（`InitialData.java:37`）。已經跑過的資料庫不會補上新權限，所以 migration 必須自己 INSERT（上面的 SQL 已處理），**同時**也要更新 `InitialData.java:39-45` 的清單，讓全新環境一致：

```java
role("CASHIER", "收銀員", "BRANCH",
    List.of("ORDER_CREATE", "POS_ORDER", "ORDER_MANAGE", "MENU_AVAILABILITY"));
role("MANAGER", "店長", "BRANCH",
    List.of("ORDER_CREATE", "POS_ORDER", "ORDER_MANAGE", "REPORT_STORE",
            "PAYMENT_RECONCILE", "MENU_AVAILABILITY"));
```

`HQ` 吃的是 `Identity.PERMISSIONS` 全集（`InitialData.java:46`），常數加進去就自動有了。

---

## 5. API

### 5.1 `Catalog` interface 的變更（`coffee-catalog/api/Catalog.java`）

```java
record Product(
    String id, String name, String subtitle, String category,
    int price, int cost, String image, String badge, boolean active,
    String availability) {}          // 新增：AVAILABLE / SOLD_OUT / UNLISTED

record BranchAvailability(
    String branchId, String productId, String productName,
    String availability, Long updatedAt, String updatedBy) {}

List<Product> list(Actor a, boolean manage, String branchId);   // 簽章變更
Product sellable(String branchId, String id);                    // 簽章變更
Product save(Actor a, Product p);                                // 不變
List<BranchAvailability> availability(Actor a, String branchId);
void setAvailability(Actor a, String branchId, String productId, String availability);
```

`Product.availability` 在 `manage=true`（總部全鏈維護）或未指定分店時一律回 `"AVAILABLE"`。

> `save()` 收到的 `Product` 現在多一個 `availability` 欄位。**`save()` 必須忽略它** —— 全鏈主檔不承載分店狀態，改分店狀態只能走 `setAvailability`。這與「不信任前端傳來的金額」是同一類規則：前端送什麼都不算數。

### 5.2 HTTP 端點

| 方法 | 路徑 | 權限 | 說明 |
| --- | --- | --- | --- |
| GET | `/api/menu?branchId={id}` | 已登入 | 點餐菜單。**`manage=false` 時 `branchId` 必填** |
| GET | `/api/menu?manage=true` | `MENU_MANAGE` | 總部全鏈維護，忽略 `branchId`（行為與現在相同） |
| GET | `/api/menu/availability?branchId={id}` | `MENU_AVAILABILITY`，限該分店 | 該分店的可用性清單 |
| POST | `/api/menu/availability` | 見 5.4 | 設定單一商品的可用性 |

**`GET /api/menu?branchId=taipei`** 回應（`200`）：

```json
[
  {"id":"latte","name":"經典拿鐵","subtitle":"濃縮咖啡 × 香醇鮮乳","category":"經典咖啡",
   "price":140,"cost":0,"image":"latte","badge":"人氣首選","active":true,
   "availability":"AVAILABLE"},
  {"id":"croissant","name":"法式奶油可頌","subtitle":"酥脆層次・法國發酵奶油","category":"手作烘焙",
   "price":90,"cost":0,"image":"pastry","badge":"每日烘焙","active":true,
   "availability":"SOLD_OUT"}
]
```

- `UNLISTED` 的商品**不出現在清單裡**（本店不供應，顧客不需要看到）
- `SOLD_OUT` 的商品**照常回傳**並標記。理由：直接消失會讓熟客以為系統壞了，標「今日售完」才是店裡實際會說的話
- `cost` 維持現有行為（非 `manage` 一律回 `0`，`CatalogService.java:39-52`）

**`POST /api/menu/availability`** 請求：

```json
{"branchId":"taipei","productId":"croissant","availability":"SOLD_OUT"}
```

回應 `200`，body 為該筆更新後的 `BranchAvailability`。需要 CSRF token（寫入請求，無例外）。

### 5.3 錯誤碼

| 狀態 | 訊息（繁體中文，寫給使用者看） | 觸發情境 |
| --- | --- | --- |
| 400 | `請選擇分店` | `manage=false` 但沒帶 `branchId` |
| 400 | `商品已下架，請重新整理菜單` | `products.active=false`（維持現有訊息不變） |
| 400 | `本店今日已售完此商品，請調整餐點` | 下單時該商品在該分店是 `SOLD_OUT` |
| 400 | `本店未供應此商品` | 下單時該商品在該分店是 `UNLISTED` |
| 400 | `供應狀態不正確` | `availability` 不在三個列舉值內 |
| 403 | `只能存取所屬分店資料` | 跨店設定（沿用 `Actor.branch()` 既有訊息） |
| 403 | `分店供應品項限總部設定` | 店端嘗試設 `UNLISTED` |
| 403 | `沒有此功能的操作權限` | 缺 `MENU_AVAILABILITY`（沿用 `Actor.require()` 既有訊息） |
| 404 | `找不到商品` | `productId` 不存在 |

### 5.4 權限與資料範圍

**新增權限常數 `MENU_AVAILABILITY`**，加進 `Identity.PERMISSIONS`（`Identity.java:7-18`）。

| 操作 | 需要 | 資料範圍 |
| --- | --- | --- |
| `GET /api/menu?branchId=` | 已登入即可 | 任何分店（菜單本來就是公開資訊，顧客要能跨店比較） |
| `GET /api/menu/availability` | `MENU_AVAILABILITY` | **BRANCH** —— 非 GLOBAL 只能查自店，用 `a.branch(branchId)` |
| 設 `SOLD_OUT` / 解除為 `AVAILABLE` | `MENU_AVAILABILITY` | **BRANCH** —— 用 `a.branch(branchId)` |
| 設 `UNLISTED` / 從 `UNLISTED` 解除 | `MENU_MANAGE` **且** `a.global()` | **GLOBAL** |

授權碼的形狀（放在 `CatalogService.setAvailability`）：

```java
public void setAvailability(Actor a, String branchId, String productId, String availability) {
  Problem.check(
      Set.of("AVAILABLE", "SOLD_OUT", "UNLISTED").contains(availability), "供應狀態不正確");
  boolean toUnlisted = "UNLISTED".equals(availability);
  boolean fromUnlisted = "UNLISTED".equals(currentAvailability(branchId, productId));
  if (toUnlisted || fromUnlisted) {
    a.require("MENU_MANAGE");
    if (!a.global()) throw new Problem(403, "分店供應品項限總部設定");
  } else {
    a.require("MENU_AVAILABILITY");
    a.branch(branchId);
  }
  ...
}
```

> **`fromUnlisted` 這一段是必要的，不是多餘的防呆。** 少了它，店員可以把總部設定的「本店不供應」直接改成 `AVAILABLE`，等於用店端權限繞過總部決定。

`Actor.customer()`（顧客，scope `SELF`）沒有 `MENU_AVAILABILITY`，會被 `require()` 擋在 403。

---

## 6. 下單時的查核（`coffee-orders`）

`OrderService.create()` 的品項迴圈（`OrderService.java:57-63`）：

```java
for (LineInput l : q.items()) {
  Problem.check(l != null && l.quantity() >= 1 && l.quantity() <= 50, "單品數量需為 1–50");
  var p = catalog.sellable(q.branchId(), l.productId());   // 改：帶入下單分店
  validateOptions(p, l);
  products.add(p);
  total = Math.addExact(total, Math.multiplyExact(p.price(), l.quantity()));
}
```

必須遵守的三點：

1. **查核在 `create()` 的 `@Transactional` 內進行**，與既有的 `branches.requireOpen()`、金額重算同一個交易
2. **金額規則完全不變。** 售完與否不影響任何價格。單價一律來自 `catalog.sellable()` 回傳的 `Product.price()`，不讀前端送來的任何金額欄位
3. **冪等重放不重新查核。** 同一個 `Idempotency-Key` 打進來時，既有邏輯直接回傳原訂單（`OrderService.java:45-48`），這是對的 —— 訂單既然已經成立，就不該因為之後標了售完而變成失敗

### 售完的競態

兩位顧客同時下單最後一杯，兩單都會成立。**這是刻意接受的**：本規格是人工標記，不是庫存扣減（G08）。售完標記的語意是「店員按下去之後，不再接受新單」，不是「剩幾杯」。實作不需要為此加行鎖。

---

## 7. 稽核

`setAvailability` 成功後寫入既有的 `audit_log` 表（`V1__coffee_schema.sql:15`）：

| 欄位 | 值 |
| --- | --- |
| `id` | `Ids.next()` |
| `actor_id` | `a.id()` |
| `action` | `MENU_AVAILABILITY` |
| `target_id` | `{branchId}:{productId}:{availability}`（`target_id` 是 `VARCHAR(80)`，足夠） |
| `created_at` | `System.currentTimeMillis()` |

寫入與 `setAvailability` 同一個 `@Transactional`。

本規格**不做**稽核查詢端點 —— 那是 G11 的範圍。這裡只負責讓資料留下來，不然 G11 做完也查不到任何歷史。

---

## 8. 依賴決議：`coffee-catalog` 不新增對 `coffee-branches` 的依賴

模組依賴表（`AGENTS.md`）目前是：

```
coffee-catalog     菜單、售價、成本、上下架     依賴：shared
```

直覺上「分店菜單」應該讓 catalog 依賴 branches，才能驗 `branchId` 存在且營業中。**本規格不這麼做**，理由：

- `branch_products.branch_id` 的 FK 已經保證分店存在，寫不進不存在的分店
- 「分店是否營業中」的檢查在下單路徑上已經有了（`OrderService.create():49` 呼叫 `branches.requireOpen()`，`BranchService.java:36-40`），再加一層是重複
- 新增模組依賴會動到 `ModuleBoundariesTest` 的既有斷言與 `AGENTS.md` 的依賴表。`AGENTS.md` 禁止事項第 2 條寫明「不得改動模組邊界，要調整先提出來討論」

所以 `coffee-catalog` 把 `branchId` 當成不透明字串鍵。**如果實作中發現這條界線走不通，先回報，不要自己加依賴。**

---

## 9. 驗收條件

實作完成時，下列每一條都要能逐條勾選：

**資料模型**

- [ ] `branch_products` 建表 migration 存在，版號是 migration 目錄中下一個未使用的號碼
- [ ] 沒有修改 `V1__coffee_schema.sql` 與 `V2__payment_reconciliation.sql`
- [ ] `CHECK` 約束確保 `SOLD_OUT` 必有 `sold_out_date`、其他狀態必為 `NULL`
- [ ] 全新資料庫與既有資料庫（已有 `roles` 資料）啟動後，`MANAGER` / `CASHIER` / `HQ` 三個角色都拿得到 `MENU_AVAILABILITY`

**查詢**

- [ ] `GET /api/menu?branchId=taipei` 回傳的每個商品都帶 `availability`
- [ ] 被標 `SOLD_OUT` 的商品仍在清單中且標記正確；被標 `UNLISTED` 的商品不在清單中
- [ ] `GET /api/menu?manage=true` 行為與本次變更前完全相同（含 `cost` 可見、含已下架商品）
- [ ] `GET /api/menu` 未帶 `branchId` 且 `manage=false` → 400 `請選擇分店`
- [ ] 沒有任何 `branch_products` 列時，菜單內容與本次變更前逐欄位相同

**售完與供應設定**

- [ ] 店長標記自店售完後，`GET /api/menu?branchId=` 該商品變 `SOLD_OUT`，其他兩家分店不受影響
- [ ] 售完標記在台北時區隔日自動失效（測試用可注入的日期或直接寫入昨日的 `sold_out_date` 驗證）
- [ ] 解除售完後恢復可售
- [ ] 總部可設 `UNLISTED`；店端無法設 `UNLISTED`，也無法把 `UNLISTED` 改成 `AVAILABLE`
- [ ] `products.active=false` 時，分店設 `AVAILABLE` 仍不可售

**下單**

- [ ] 對 `SOLD_OUT` 商品下單 → 400 `本店今日已售完此商品，請調整餐點`
- [ ] 對 `UNLISTED` 商品下單 → 400 `本店未供應此商品`
- [ ] 同一商品在 A 店售完、B 店正常時，B 店下單成功且金額正確
- [ ] 訂單總額完全不受可用性影響；前端送任何金額欄位都不被採用
- [ ] 已成立訂單之後才標售完，用同一個 `Idempotency-Key` 重放仍回傳原訂單

**稽核**

- [ ] 每次 `setAvailability` 成功都在 `audit_log` 留下一列，`action='MENU_AVAILABILITY'`、`actor_id` 正確
- [ ] 失敗的設定（403 / 400）不留稽核列

**前端**

- [ ] 點餐畫面切換分店時重新載入菜單
- [ ] `SOLD_OUT` 商品卡片顯示「今日售完」且無法加入購物車
- [ ] 已在購物車中的商品若分店切換後在新分店不可售，結帳前要擋下並提示
- [ ] 有 `MENU_AVAILABILITY` 的使用者在點餐畫面看得到「標記售完 / 恢復供應」按鈕；顧客看不到
- [ ] 所有 HTTP 呼叫走 `shared/api.ts`，型別更新在 `shared/types.ts`

**規範**

- [ ] `docs/API.md` 端點表已更新
- [ ] `frontend && npm ci && npm run build` 綠
- [ ] `backend && ./mvnw -B -ntp verify` 綠（含 `ModuleBoundariesTest`）
- [ ] `git ls-files -s backend/mvnw scripts/build.sh start-demo.sh` 三個都是 `100755`

---

## 10. 測試要求

放在 `backend/coffee-app/src/test/java/com/coffee/app/`，沿用既有三支測試的分工。

### 10.1 業務規則（`CoffeeIntegrationTest`，不需要 HTTP）

| 測試 | 斷言 |
| --- | --- |
| 無覆寫列 | `sellable("taipei","latte")` 與變更前同樣成功 |
| 售完 | 標記 taipei/croissant 後，`sellable("taipei","croissant")` 丟 400；`sellable("banqiao","croissant")` 成功 |
| 隔日恢復 | 寫入昨日的 `sold_out_date` 後，`sellable` 成功 |
| 不供應 | `UNLISTED` 的商品 `sellable` 丟 400，且不出現在 `list()` |
| 全鏈下架優先 | `products.active=false` + 分店 `AVAILABLE` → `sellable` 仍丟 400 |
| 下單擋售完 | `orders.create()` 帶售完商品 → 400，且 `orders` / `order_items` 都沒有新列（交易回滾） |
| 金額不受影響 | 同一張單在標售完前後，重算總額一致 |

### 10.2 越權測試（必要，`AGENTS.md` 授權章節強制）

| 情境 | 期望 |
| --- | --- |
| taipei 的收銀員標記 **banqiao** 的商品售完 | 403 |
| taipei 的收銀員標記 **自店** 商品售完 | 200 |
| 顧客（`CUSTOMER`，scope `SELF`）呼叫 `POST /api/menu/availability` | 403 |
| 店長設 `UNLISTED` | 403 `分店供應品項限總部設定` |
| 店長把總部設的 `UNLISTED` 改成 `AVAILABLE` | 403 |
| 總部（`HQ`，GLOBAL）設任一分店的任一狀態 | 200 |
| taipei 收銀員查 `GET /api/menu/availability?branchId=banqiao` | 403 |
| 未登入呼叫 `POST /api/menu/availability` | 401 |

### 10.3 CSRF（`HttpWorkflowTest`，真實 HTTP + Cookie）

**這一條請特別照做**：`POST /api/menu/availability` 的 CSRF 測試必須打**該帳號有權限、且會成功的那一條路徑**（自店、`SOLD_OUT`），只差在有無 CSRF token：

- 無 CSRF token → 403
- 有 CSRF token → 200

> 不要拿跨店或無權限的請求來驗 CSRF。PR #9 的 `ReconciliationTest.java:233-235` 正是這樣寫的：兩次 POST 都打跨店訂單、都期望 403，結果**即使 CSRF 保護被整個關掉也照樣通過**。同一個坑不要踩第二次。

### 10.4 邊界與併發

- `availability` 傳空字串、null、`"sold_out"`（小寫）→ 400
- 不存在的 `productId` → 404
- 兩位顧客同時對最後一杯下單 → **兩單都成立**（第 6 節的刻意設計，寫成測試釘住這個行為，避免日後有人「順手修掉」）

---

## 11. 待 PO（HSIN）決定

1. **營業日換日時間** —— 本規格定為台北午夜 `00:00`。若實際營業到凌晨（例如收攤 01:00），店員在 00:30 標的售完會在 30 分鐘後自動解除。要改成 05:00 換日的話，第 4.2 節的日期計算要一併調整。**先確認再實作。**
2. **`CASHIER` 要不要有 `MENU_AVAILABILITY`** —— 本規格給了。理由是售完是現場即時動作，店長不一定在場。若 PO 認為只有店長能決定停售，把 migration 與 `InitialData` 裡的 `CASHIER` 那兩行拿掉即可，其餘不變。
3. **區域定價（分店各自售價）** —— 盤點提過，本規格明確排除。它會同時動到金額重算、成本快照與報表毛利，且與 G06 的選項加價直接相撞。建議另立 **G17**，排在 G06 與 G13 都合併之後。請 PO 確認是否要排入。
4. **`UNLISTED` 的既有訂單** —— 某商品在某店被設為不供應後，該店過去賣過的訂單仍會出現在歷史與報表中（快照不回寫，這是 `AGENTS.md` 的既有規則）。確認這是預期行為。

---

## 12. 給 Codex 的施工提醒

- **開工前先確認 G06 的狀態。** 兩者都改 `Catalog.sellable()` 的簽章與 `OrderService` 的品項迴圈，同時進行一定會撞
- `Catalog.Product` 加欄位會影響所有 `new Product(...)` 的呼叫點：`CatalogService.row()`、`list()` 的 map、`save()` 的回傳（`CatalogService.java:17-28`、`39-52`、`106-115`）。編譯器會全部指出來，不要漏掉前端 `types.ts` 的對應型別
- Flyway 版號開工前現查，不要照抄本文
- 錯誤訊息一律繁體中文台灣用語，寫給顧客與店員看
- 規格有錯或漏掉邊界條件，**先回報再動工**（`AGENTS.md` 第 46 行）
