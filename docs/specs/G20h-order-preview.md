# G20h — 後端購物車試算端點

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G20h |
| 版本 | v1.0（2026-10-05） |
| 登記來源 | [`G20a-promotion-hints.md`](G20a-promotion-hints.md) §13.1、§14 |
| 分支 | `codex/g20h-order-preview` |
| Flyway | **零 migration**（`V15` 仍然空著，本規格一個 SQL 檔都不加） |
| 新依賴 | **零**（前後端都不新增任何套件） |
| 施工階段 | **三階段**（S1 折扣碼試算／S2 試算端點／S3 前端接線與顯示） |
| 開工前提 | **已滿足**。G20（`e81fa44`）與 G20a（[PR #72](https://github.com/choka1227/coffee_GPT6/pull/72)）皆已合併進主線 |

---

## 1. 背景與目標

### 1.1 G20a 刻意留下的那一半

G20a 讓顧客在菜單與購物車看得到促銷**存在**，但它的 §13.1 明確拒絕在前端顯示**金額**：

> 提示只說「第 2 件 5 折」，不說「可省 25 元」。

理由不是金額不重要，而是**前端算不得**。要在 TypeScript 裡算出「省 25 元」，就得重做一份 `PromotionService.best` / `evaluate`，其中有四個不顯然的地方（多規則取最大、平手比 id 字串序、`NTH_PERCENT` 先依單價排序再折最便宜那幾件、整數除法截斷）。兩份實作遲早漂移，症狀是**「畫面說省 25、帳單折了 24」—— 顧客會認為店家在騙他，而兩邊的程式碼單看都對**。

G20a 因此把「顯示真實預估金額」整個切出去，登記為本規格：**讓後端算，前端只顯示。**

### 1.2 現在顧客看到什麼

G20a 上線後，顧客在送出訂單前看到的是：

| 位置 | 內容 | 有金額嗎 |
| --- | --- | --- |
| 商品卡 | 「第二杯半價：第 2 件 5 折」 | ❌ 只有規則 |
| 購物車品項列 | 同上 + `(unitPrice+optionsPrice)*quantity` | ⚠️ **毛額**，沒有折抵 |
| 購物車門檻提示 | 「再加 1 件可享…」 | ❌ 只有件數 |
| 購物車總計 | 「總計」= **毛額合計** | ⚠️ **這個數字比他實際要付的多** |
| POS 現金第二階段 | 小計 / 品項促銷折抵 / 優惠碼折抵 / 應收 | ✅ **但只有店員的現金收銀走得到** |

最後一列是 G20 S3 做的，依 `checkout.ts` 的 `checkoutPath`，那條路徑是 `POS_CASH_TWO_STAGE`。**顧客自己點餐（`CUSTOMER_PENDING`）與刷卡（`ECPAY`）在送出前都只看得到毛額的「總計」。**

所以今天的狀態是：**顧客看得到「有促銷」，卻看不到「所以我要付多少」。** 他按下送出鍵時，螢幕上那個「總計」數字是錯的 —— 比他實際被扣的多。促銷愈成功，這個數字錯得愈多。

### 1.3 這為什麼現在要做

1. **閘門已解除。** 計價邏輯（`PromotionService.apply` + `DiscountService.apply` + `OrderService.create` 的算術）全部在主線上，本規格**不需要任何新的計價邏輯**，只需要把既有的那一條路徑開一個不寫入的入口
2. **零 migration、零新依賴、零產品決策。** 要顯示的數字完全由既有規則決定，不需要 PO 判斷任何取捨
3. **成本只會變高。** 促銷規則現在還是空的（`InitialData` 沒有建任何 `item_promotions` 列），所以今天上線時**所有既有畫面的數字一個都不會變**（折抵為 0 時試算結果等於毛額）。等到有真實規則在跑，再動結帳前的金額顯示就要同時驗「有促銷」與「沒促銷」兩套畫面

### 1.4 目標

- 新增 `POST /api/orders/preview`：收購物車，回**後端算出來的**小計／品項促銷折抵／優惠碼折抵／應收，**不寫入任何資料**
- 購物車在送出前顯示真實預估金額，顧客與店員兩條路徑都看得到
- **金額仍然只有一個來源** —— 試算與建立訂單走**同一段程式碼**，不是兩份

---

## 2. 範圍

### 2.1 在範圍內

- `Discounts.quote`：優惠碼的**不消耗**試算（S1）
- `Orders.preview` 與 `POST /api/orders/preview`（S2）
- `OrderService` 把 `create` 的計價段抽成共用私有方法（**純抽取，行為零變更**）（S2）
- 前端 `preview.ts`（debounce 與競態處理的純函式）、`MenuView.vue` 的購物車金額顯示（S3）
- **G20i 搭順風車**：`promo-hint` / `cart-line-promo` / `cart-promotion-progress` 的樣式（S3，見 §13.7）

### 2.2 不在範圍內

| 項目 | 為什麼 | 去哪裡 |
| --- | --- | --- |
| 改變任何既有金額的算法 | 本規格是**開一個唯讀入口**，不是改計價 | —— |
| 建立訂單的請求／回應欄位 | 一個欄位都不動 | —— |
| 多規則疊加 | 開工前提是要有真實促銷方案 | G20b |
| 優惠碼折抵分攤到品項 | 攤法本質上武斷，且要動 `OrderService.create` 的寫入路徑 | G20f |
| 試算端點的限流 | 需要新的限流基礎設施（本 repo 目前沒有），而 debounce 已把呼叫量壓到可接受 | **G20j**（§14 登記） |
| 把試算結果快取 | 沒有證據顯示需要；先量再說 | —— |

---

## 3. 涉及模組與邊界

```
coffee-catalog   Discounts.quote（新增，api + internal）        依賴不變
coffee-orders    Orders.preview（新增，api + internal）          依賴 catalog.api（既有）
frontend         modules/ordering/preview.ts（新增）
                 modules/ordering/MenuView.vue（修改）
                 shared/types.ts（新增 record 對應型別）
```

- `OrderService` 呼叫的是 `Discounts`（`coffee-catalog` 的 **`api`**），**不是** `DiscountService`。既有依賴，不新增跨模組邊界
- **不動** `coffee-reporting`、`coffee-payments`、`coffee-branches`、`coffee-identity`
- `ModuleBoundariesTest` 不需要改，也**不可以**改

---

## 4. DB schema 與 migration

**零 migration。** 本規格不新增、不修改任何資料表或欄位，`V15` 仍然空著。

**而且不只是「沒有 migration」，是「不寫入任何一列」** —— 這由 §5.3 的 `@Transactional(readOnly = true)` 在結構上保證，不是靠實作記得不要寫。

---

## 5. 技術設計

### 5.1 S1：`DiscountService.apply` 會消耗一次兌換 —— 這是本規格最大的陷阱

**先看這一行**（`DiscountService.java:150`）：

```java
db.update("update discounts set redeemed_count=redeemed_count+1 where id=?", rule.id());
```

`Discounts.apply` **不是純函式**。它會：

1. 用 `select * from discounts where code=? **for update**` 取**行鎖**（`:136`）
2. 把 `redeemed_count` **加一**（`:150`）

**如果試算端點直接重用 `apply`，後果是**：

| 症狀 | 怎麼發生 |
| --- | --- |
| **一個顧客瀏覽就把優惠碼用光** | 購物車每變動一次就試算一次。一個 `maxRedemptions=100` 的碼，顧客加加減減 100 次就歸零 —— **而且他一張訂單都還沒送出** |
| **真的要下單時被自己擋住** | `redeemedCount >= maxRedemptions` 回 409「此優惠碼的使用次數已達上限」 |
| **行鎖爭用** | 每次試算都對同一列取 `for update`，熱門優惠碼會把下單的交易卡住 |

這是**不會在開發時被發現的那一類 bug**：`maxRedemptions` 為 null（無上限）時完全看不出異狀，`redeemed_count` 只是個沒人看的數字慢慢長大，直到某天行銷問「為什麼這個碼顯示已經用了 8000 次但訂單只有 30 筆」。

**所以 S1 必須先把「驗證 + 計算」與「消耗」拆開。**

#### 在 `Discounts`（`coffee-catalog/api/Discounts.java`）新增

```java
Applied quote(String code, String branchId, int subtotal, long atEpochMs);
```

#### `DiscountService` 的改法 —— 抽共用，不複製

`apply` 現在的 `:131-154` 拆成三塊，**`apply` 的對外行為一字不變**：

```java
@Override
public Applied apply(String requestedCode, String branchId, int subtotal, long atEpochMs) {
  Rule rule = resolve(requestedCode, branchId, subtotal, atEpochMs, true);
  if (rule == null) return null;
  db.update("update discounts set redeemed_count=redeemed_count+1 where id=?", rule.id());
  return applied(rule, subtotal);
}

@Override
public Applied quote(String requestedCode, String branchId, int subtotal, long atEpochMs) {
  Rule rule = resolve(requestedCode, branchId, subtotal, atEpochMs, false);
  return rule == null ? null : applied(rule, subtotal);
}

/** 驗證並取出規則。code 為空回 null；任何一項驗證不過就 throw。lock=true 時取行鎖。 */
private Rule resolve(
    String requestedCode, String branchId, int subtotal, long atEpochMs, boolean lock) {
  String code = normalize(requestedCode);
  if (code == null) return null;
  Problem.check(code.matches("[A-Z0-9-]{4,20}"), "優惠碼格式不正確");
  Rule rule = db.query(
          "select * from discounts where code=?" + (lock ? " for update" : ""), this::row, code)
      .stream()
      .findFirst()
      .orElseThrow(() -> new Problem(404, INVALID));
  if (!rule.active()
      || (rule.startsAt() != null && rule.startsAt() > atEpochMs)
      || (rule.endsAt() != null && atEpochMs > rule.endsAt())
      || (rule.branchId() != null && !rule.branchId().equals(branchId))) {
    throw new Problem(404, INVALID);
  }
  Problem.check(subtotal >= rule.minSubtotal(), "訂單金額未達此優惠碼的最低消費");
  if (rule.maxRedemptions() != null && rule.redeemedCount() >= rule.maxRedemptions()) {
    throw new Problem(409, "此優惠碼的使用次數已達上限");
  }
  return rule;
}

private Applied applied(Rule rule, int subtotal) {
  return new Applied(
      rule.id(), rule.code(), rule.name(), rule.kind(), rule.percent(), rule.amount(), subtotal,
      calculate(rule, subtotal));
}
```

**`quote` 與 `apply` 的驗證必須是同一份。** 這一點和 G20a §5.2 共用 `activeRules` 是同一個原則：`active` / 期間 / 分店 / `minSubtotal` / `maxRedemptions` 這五個條件定義了「什麼叫做這張碼現在能用」。試算端自己寫一份，就會出現「試算說能用、下單說不能用」或反過來 —— **最難追的那一類 bug，因為兩邊程式碼單看都對**。

> **`maxRedemptions` 的競態是已知且接受的：** 試算時還有一次額度、送出時被別人用掉了，下單會回 409。這是對的 —— **試算是估計，不是保留**。要讓它變成保留就得引入預約／過期機制，那是完全不同的一個缺口，而且會讓「只是看看」的使用者佔住額度。試算端點**不保留任何東西**。

### 5.2 S2：`OrderService.create` 的計價段抽成共用方法

`create`（`OrderService.java:44`）目前的 `:75-100` 是「把購物車變成金額」的完整邏輯。**試算要的就是這一段，一行不多一行不少。**

新增一個 `internal` 的 record 與私有方法（**都不進 `api`**，它們是實作細節）：

```java
/** 把購物車算成金額。products / resolved 供 create 寫快照用，preview 不使用。 */
private record Priced(
    List<Catalog.Product> products,
    List<List<Catalog.ResolvedOption>> resolved,
    List<Promotions.Line> lines,
    int subtotal,
    Promotions.Applied promotion,
    Discounts.Applied code,
    int itemDiscountAmount,
    int codeDiscountAmount,
    int discountAmount,
    int total) {}

private Priced price(
    String branchId, List<LineInput> items, String discountCode, long now, boolean redeem) {
  List<Catalog.Product> products = new ArrayList<>();
  List<List<Catalog.ResolvedOption>> resolved = new ArrayList<>();
  List<Promotions.Line> promotionLines = new ArrayList<>();
  int gross = 0;
  for (LineInput l : items) {
    Problem.check(l != null && l.quantity() >= 1 && l.quantity() <= 50, "單品數量需為 1–50");
    var p = catalog.sellable(branchId, l.productId());
    var options = catalog.resolveOptions(p.id(), l.optionIds());
    int optionPrice = options.stream().mapToInt(Catalog.ResolvedOption::priceDelta).sum();
    int unitPrice = Math.addExact(p.price(), optionPrice);
    Problem.check(unitPrice > 0, "商品金額不正確");
    products.add(p);
    resolved.add(options);
    promotionLines.add(new Promotions.Line(p.id(), p.category(), unitPrice, l.quantity()));
    gross = Math.addExact(gross, Math.multiplyExact(unitPrice, l.quantity()));
  }
  Problem.check(gross <= 1000000, "單筆訂單金額超過上限");
  int subtotal = gross;
  var promotion = promotions.apply(branchId, promotionLines, now);
  int itemDiscountAmount = promotion == null ? 0 : promotion.discountAmount();
  int afterItems = Math.subtractExact(subtotal, itemDiscountAmount);
  var applied =
      redeem
          ? discounts.apply(discountCode, branchId, afterItems, now)
          : discounts.quote(discountCode, branchId, afterItems, now);
  int codeDiscountAmount = applied == null ? 0 : applied.discountAmount();
  int discountAmount = Math.addExact(itemDiscountAmount, codeDiscountAmount);
  return new Priced(
      products, resolved, promotionLines, subtotal, promotion, applied,
      itemDiscountAmount, codeDiscountAmount, discountAmount,
      Math.subtractExact(subtotal, discountAmount));
}
```

`create` 改為：

```java
var priced = price(q.branchId(), q.items(), q.discountCode(), now, true);
```

之後沿用 `priced.products()`、`priced.resolved()`、`priced.subtotal()`、`priced.promotion()`、`priced.code()`、`priced.itemDiscountAmount()`、`priced.total()` 等欄位，**其餘邏輯（`reserveStock`、寫 `orders` / `order_items` / `order_discounts`、快照）一字不動**。

> **`redeem` 這個 boolean 參數是本規格唯一一處「用旗標分流」，而它是刻意的。** 替代方案是讓 `price` 不管優惠碼、由呼叫端各自算 —— 但那會把「品項折抵算完之後才輪到優惠碼」這個**順序**複製成兩份，而那個順序決定了優惠碼是對毛額還是對折後金額打折（`afterItems`）。**順序錯了金額就錯，而且錯得很小、很難看出來。** 一個 boolean 換這段順序只有一份，划算。
>
> 參數名用 `redeem` 不用 `preview`，因為它描述的是**這次呼叫會不會消耗兌換次數**，那才是兩條路徑真正的差別。

**驗證「純抽取」的方法：** `create` 的行為零變更，所以 `OrderDiscountTest`、`ItemPromotionOrderTest`、`DiscountRedemptionTest`、`CoffeeIntegrationTest`、`HttpWorkflowTest` **全部一字不改且全綠**。驗收 1 與 14 釘這一條。

### 5.3 S2：`Orders.preview` 與端點

#### `Orders`（`coffee-orders/api/Orders.java`）新增

```java
record PreviewRequest(String branchId, String discountCode, List<LineInput> items) {}

record PreviewLine(String productId, int unitPrice, int quantity, int lineTotal, int discountAmount) {}

record Quote(
    int subtotal,
    int itemDiscountAmount,
    int codeDiscountAmount,
    int discountAmount,
    int total,
    ItemPromotion itemPromotion,
    OrderDiscount discount,
    List<PreviewLine> items) {}

Quote preview(Actor a, PreviewRequest request);
```

**為什麼不重用 `Create` 當請求型別：** `Create` 帶 `fulfillment`、`paymentMethod`、`note` 三個與金額無關的必填欄位，而且 `create` 對它們有驗證（`取餐方式不正確` 等）。試算要的只有分店、購物車、優惠碼。**一個端點只收它會用到的欄位** —— 這與 G20a §5.1 的 `ActiveRule` 不重用 `Rule` 是同一個原則。

`ItemPromotion` 與 `OrderDiscount` **重用既有 record**（`Orders.java:18-26`），因為它們就是訂單上那兩個摘要，形狀相同；前端已經有對應型別，不必再造一組。

#### `OrderService.preview`

```java
@Override
@Transactional(readOnly = true)
public Quote preview(Actor a, PreviewRequest q) {
  a.require("ORDER_CREATE");
  Problem.check(q != null && q.branchId() != null && !q.branchId().isBlank(), "請選擇分店");
  Problem.check(
      q.items() != null && !q.items().isEmpty() && q.items().size() <= 50, "請選擇 1–50 個品項");
  if (!a.customer()) {
    a.require("POS_ORDER");
    a.branch(q.branchId());
  }
  var priced = price(q.branchId(), q.items(), q.discountCode(), System.currentTimeMillis(), false);
  List<PreviewLine> lines = new ArrayList<>();
  for (int i = 0; i < priced.lines().size(); i++) {
    var l = priced.lines().get(i);
    int lineDiscount =
        priced.promotion() == null ? 0 : priced.promotion().lineDiscounts().get(i);
    lines.add(new PreviewLine(
        l.productId(), l.unitPrice(), l.quantity(),
        Math.multiplyExact(l.unitPrice(), l.quantity()), lineDiscount));
  }
  return new Quote(
      priced.subtotal(), priced.itemDiscountAmount(), priced.codeDiscountAmount(),
      priced.discountAmount(), priced.total(),
      priced.promotion() == null ? null : itemPromotionOf(priced.promotion()),
      priced.code() == null ? null : orderDiscountOf(priced.code()),
      lines);
}
```

> `itemPromotionOf` / `orderDiscountOf` 是 `create` 裡既有的 `Applied` → `ItemPromotion` / `OrderDiscount` 轉換，**如果 `create` 目前是內嵌寫的，一併抽成私有方法共用**，不要複製第二份。

**`@Transactional(readOnly = true)` 是本規格的結構紅線，不是效能設定。** PostgreSQL 在唯讀交易裡執行任何 `INSERT` / `UPDATE` / `DELETE` 都會直接丟錯。所以**如果日後有人在 `price()` 共用路徑裡加了寫入**（例如把 `redeemed_count` 的增量搬進去、或加一筆稽核記錄），**試算端點會立刻、明確地爆掉**，而不是安靜地開始消耗優惠碼。驗收 7 驗的就是這一條。

> **沒有 `@Transactional(readOnly = true)` 會怎樣**：§5.1 那個 bug 會在某次重構之後悄悄復活，而且症狀（`redeemed_count` 莫名其妙變大）要幾個月後才有人注意到。**靠審查記得「這條路徑不能寫入」撐不了幾輪**，靠資料庫擋就永遠成立。

#### `OrderController` 新增

```java
@PostMapping("/preview")
Orders.Quote preview(@RequestAttribute Actor actor, @RequestBody Orders.PreviewRequest request) {
  return orders.preview(actor, request);
}
```

加在既有的 `OrderController`（`/api/orders`）裡。**不要**開新的 Controller。

**`POST` 而不是 `GET`**：購物車是一個結構化的清單（含每個品項的 `optionIds`），塞進 query string 會碰到長度上限與編碼問題。**`POST` 不代表會寫入** —— 不寫入由 `@Transactional(readOnly = true)` 保證，不是由 HTTP 動詞保證。代價是要帶 CSRF token（`api.ts:19` 對非 GET 自動帶），這是既有機制，不需要任何設定。

### 5.4 S2：刻意**不做**的三件驗證

| `create` 有、`preview` 沒有 | 為什麼 |
| --- | --- |
| `Idempotency-Key` | 試算不建立任何東西，沒有重複執行的風險 |
| `catalog.reserveStock` | **試算不佔用庫存。** 佔用就等於「只是看看也會讓別人買不到」 |
| `branches.requireOrderable` / `requireOpen` | 見下 |

**為什麼不檢查營業狀態（設計決策，§13.3 有完整理由）：** 試算回答的是「**這一籃多少錢**」，不是「**我現在能不能下單**」。打烊時間的把關已經在 `create` 做了，而且 G14／G19／G25 已經把營業時間與最後點餐時間顯示在畫面上。如果試算也擋，顧客在開店前瀏覽菜單時購物車會完全沒有金額 —— **而那正是促銷最需要影響他的時刻**。

**但權限與分店範圍仍然檢查**：`ORDER_CREATE` 是必要的（不能下單的人不需要知道價格），店員還要 `POS_ORDER` + `a.branch(branchId)`，**跨店試算要被擋**（驗收 9 的越權測試）。

### 5.5 S2：售完商品的處理

`catalog.sellable` 對已下架／本店未供應／今日售完分別丟 400（`CatalogService.java:123-139`）。**試算原樣往上拋，不吞掉。**

理由：`MenuView` 已經有 `unavailableCart` 與「切換分店後，點餐單中有商品已售完或未供應，請先移除」的處理。試算若自己跳過售完品項算出一個金額，那個金額**對應不到任何一張送得出去的訂單**。前端把試算失敗當成「暫時顯示不了預估」處理（§5.7），既有的售完提示照常運作。

### 5.6 S3：前端型別

`shared/types.ts` 新增（放在既有 `ActivePromotion` 之後）：

```ts
export interface PreviewLine {
  productId: string;
  unitPrice: number;
  quantity: number;
  lineTotal: number;
  discountAmount: number;
}
export interface OrderQuote {
  subtotal: number;
  itemDiscountAmount: number;
  codeDiscountAmount: number;
  discountAmount: number;
  total: number;
  itemPromotion: OrderItemPromotion | null;
  discount: OrderDiscountSummary | null;
  items: PreviewLine[];
}
```

> `OrderItemPromotion` / `OrderDiscountSummary` 用 `types.ts` 裡既有的那兩個（`Order.itemPromotion` / `Order.discount` 的型別）。**名稱以原始碼現況為準** —— 若既有名稱不同就用既有的，不要為了對齊本規格改名。

### 5.7 S3：競態與 debounce —— 本規格前端唯一的難點

購物車每變動一次就要重算。直接 `watch` 會產生兩個問題：

1. **呼叫量**：連按五次「＋」就是五次請求
2. **競態**：第 3 次的回應可能比第 5 次晚到，**把舊金額蓋在新購物車上** —— 畫面顯示的金額對應的是一個已經不存在的購物車

第 2 點是真正危險的那個：它顯示的是**一個看起來完全合理、但就是錯的金額**，而且不會報錯。

**解法：單調遞增的請求序號 + 只接受最新序號的回應。** 這一段寫成純函式放 `modules/ordering/preview.ts`，不經 DOM 就能測：

```ts
import type { OrderQuote } from "../../shared/types";

export interface QuoteState {
  /** 已送出的最新請求序號 */
  sent: number;
  /** 已採用的回應序號；小於 sent 代表還在等 */
  settled: number;
  quote: OrderQuote | null;
  /** 最近一次請求是否失敗（失敗時不顯示預估，不顯示錯誤橫幅） */
  failed: boolean;
}

export function emptyQuoteState(): QuoteState {
  return { sent: 0, settled: 0, quote: null, failed: false };
}

/** 送出一次請求：序號加一並回傳新狀態與該次序號。 */
export function beginQuote(state: QuoteState): { state: QuoteState; seq: number } {
  const seq = state.sent + 1;
  return { state: { ...state, sent: seq }, seq };
}

/** 回應抵達。序號落後於已採用的結果就整個丟棄。 */
export function settleQuote(
  state: QuoteState,
  seq: number,
  result: { ok: true; quote: OrderQuote } | { ok: false },
): QuoteState {
  if (seq <= state.settled) return state;
  return result.ok
    ? { ...state, settled: seq, quote: result.quote, failed: false }
    : { ...state, settled: seq, quote: null, failed: true };
}

/** 購物車清空或分店改變：捨棄一切，並讓所有在途回應失效。 */
export function resetQuote(state: QuoteState): QuoteState {
  return { sent: state.sent, settled: state.sent, quote: null, failed: false };
}

/** 是否該顯示預估金額。在途時沿用上一筆，避免數字閃爍。 */
export function showsQuote(state: QuoteState): boolean {
  return state.quote !== null;
}
```

**三個刻意的設計：**

- **`seq <= state.settled` 才丟棄，不是 `seq < state.sent`。** 用後者會把「最新那一次」以外的全部丟掉，包含**比它早送出但先回來、而最新那次還在路上**的那一筆 —— 那筆其實是當下最好的估計。用 `settled` 比較只保證**不會倒退**，是最小而正確的條件
- **`resetQuote` 把 `settled` 推到 `sent`**：這是「讓所有在途回應失效」的唯一正確做法。只把 `quote` 設成 null 的話，一個在途的舊回應回來時會把金額裝回去，而購物車已經清空了
- **在途時沿用上一筆金額**（`showsQuote` 只看 `quote`）：每次都清成 null 會讓數字在每次加減時閃爍一下。沿用舊值的代價是它有幾百毫秒不準，而清空的代價是畫面一直在跳 —— **後者比較惱人，而且會讓人以為壞掉了**

**debounce**：`MenuView.vue` 裡用既有的 `setTimeout` / `clearTimeout`（**不引入任何套件**），延遲 **300ms**。購物車或優惠碼變動時重設計時器；計時器到期才 `beginQuote` 並送出。分店變動或購物車清空時 `resetQuote` 並取消計時器。

> **為什麼 300ms**：連按加號的間隔通常小於 200ms，300ms 足以把一串連按併成一次；同時它短到使用者不會覺得數字「慢半拍」。這個值沒有魔法，**但要寫成具名常數**（`QUOTE_DEBOUNCE_MS`）讓它可調、可測。

### 5.8 S3：購物車的金額顯示

`pendingCashOrder` 存在時（POS 現金第二階段），**既有的四行真實金額一字不動** —— 那時候後端已經回了真正的訂單，試算沒有意義。其餘情形在「總計」那一行之前插入：

```html
<div v-if="showsQuote(quoteState) && quoteState.quote!.discountAmount > 0" class="cart-estimate">
  <div class="estimate-row"><span>小計</span><span>{{ money(quoteState.quote!.subtotal) }}</span></div>
  <div v-if="quoteState.quote!.itemDiscountAmount" class="estimate-row discount">
    <span>品項促銷折抵</span><span>−{{ money(quoteState.quote!.itemDiscountAmount) }}</span>
  </div>
  <div v-if="quoteState.quote!.codeDiscountAmount" class="estimate-row discount">
    <span>優惠碼折抵</span><span>−{{ money(quoteState.quote!.codeDiscountAmount) }}</span>
  </div>
</div>
```

並把既有的「總計」改為：**有可用試算時顯示 `quote.total`，否則顯示既有的毛額 `total`**。

> **`discountAmount > 0` 才顯示明細**：沒有任何折抵時多三行「小計 / 折抵 0 / 折抵 0」只是噪音，總計也等於毛額。**沒有促銷的店完全看不出本功能上線過**，這正是 §1.3 第 3 點要的。

> **總計在試算失敗時退回毛額**，不是顯示 0、不是顯示「—」。退回毛額的壞處是它偏高；顯示 0 或空白的壞處是顧客不知道要付多少。**偏高且誠實** 比 **不知道** 好，而且送出後的訂單明細會給出真實金額。

### 5.9 S3：`promotions.ts` 的紅線不變

G20a 驗收 12（v1.1 已收緊為必須有測試）要求 `modules/ordering/promotions.ts` 不含 `unitPrice` / `optionsPrice` / `price` / `total` 也沒有乘法。**本規格不放寬它。**

金額顯示全部走 `preview.ts` 與 `MenuView.vue`，資料來自後端。`promotions.ts` 仍然只處理「規則條件」與「件數」。**驗收 13 要求 G20a 那條原始碼守衛測試在本規格之後仍然綠。**

---

## 6. API

### 6.1 `POST /api/orders/preview`（新增）

**請求**

```json
{
  "branchId": "taipei",
  "discountCode": "WELCOME10",
  "items": [
    { "productId": "latte", "quantity": 2, "optionIds": ["oat"] }
  ]
}
```

| 欄位 | 必填 | 說明 |
| --- | --- | --- |
| `branchId` | **是** | 缺少或空白回 400 `請選擇分店` |
| `discountCode` | 否 | `null` 或空字串代表沒有優惠碼 |
| `items` | **是** | 1–50 筆，每筆 `quantity` 1–50 |

**回應 200**

```json
{
  "subtotal": 280,
  "itemDiscountAmount": 70,
  "codeDiscountAmount": 21,
  "discountAmount": 91,
  "total": 189,
  "itemPromotion": {
    "promotionId": "p-001", "name": "第二杯半價", "kind": "NTH_PERCENT",
    "percent": 50, "nth": 2, "discountedUnits": 1, "discountAmount": 70
  },
  "discount": {
    "code": "WELCOME10", "name": "新客九折", "kind": "PERCENT",
    "percent": 10, "amount": 0, "discountAmount": 21
  },
  "items": [
    { "productId": "latte", "unitPrice": 140, "quantity": 2, "lineTotal": 280, "discountAmount": 70 }
  ]
}
```

- 沒有促銷時 `itemPromotion` 為 `null`、`itemDiscountAmount` 為 `0`
- 沒有優惠碼時 `discount` 為 `null`、`codeDiscountAmount` 為 `0`
- **恆等式**：`discountAmount == itemDiscountAmount + codeDiscountAmount` 且 `total == subtotal - discountAmount`（驗收 5）

### 6.2 既有端點

| 端點 | 本規格是否改動 |
| --- | --- |
| `POST /api/orders` | **不改。** 請求與回應一個欄位都不動，行為零變更（`price` 是純抽取） |
| `GET /api/promotions/active` | **不改**（G20a 建的） |
| `GET /api/menu` | **不改** |

### 6.3 錯誤碼

| 狀態 | 情境 | 訊息 |
| --- | --- | --- |
| 400 | `branchId` 缺少或空白 | `請選擇分店` |
| 400 | `items` 為空或超過 50 筆 | `請選擇 1–50 個品項` |
| 400 | 單品數量不在 1–50 | `單品數量需為 1–50` |
| 400 | 商品已下架／本店未供應／今日售完 | 沿用 `catalog.sellable` 既有訊息 |
| 400 | 優惠碼格式不正確 | `優惠碼格式不正確` |
| 400 | 未達最低消費 | `訂單金額未達此優惠碼的最低消費` |
| 401 | 未登入 | `請先登入` |
| 403 | 無 `ORDER_CREATE`；或店員跨店試算 | 由 `Actor` 統一回 |
| 404 | 優惠碼不存在／已失效／不適用本店 | 沿用 `DiscountService.INVALID` |
| 409 | 優惠碼使用次數已達上限 | `此優惠碼的使用次數已達上限` |

**所有訊息沿用既有常數，不新寫一組。** 同一個失敗在試算與下單給不同訊息，只會讓前端長出分支。

---

## 7. 權限與資料範圍

| 端點 | 權限 | 資料範圍 |
| --- | --- | --- |
| `POST /api/orders/preview` | `ORDER_CREATE`（店員另需 `POS_ORDER`） | 顧客：`SELF`，不限分店（與下單一致）；店員：`BRANCH`，**只能試算自己分店** |

- **不新增任何權限常數**，`Identity.PERMISSIONS` 一字不改（驗收 15）
- 資料範圍與 `create` **完全一致**。理由：試算回答「我下這張單要付多少」，能試算的範圍就該等於能下單的範圍。不一致會讓店員試算得到一個他下不了的單
- `SecurityConfiguration` 不改：`/api/**` 一律 `authenticated()`，新端點自動需要登入
- **CSRF**：`POST` 需要 token，`api.ts:19` 對非 GET 自動帶。`/api/payments/ecpay/callback` 仍是唯一例外

---

## 8. 金額規則

| 規則 | 本規格的遵守方式 |
| --- | --- |
| 後端一律依有效菜單重算，忽略前端送來的金額 | **請求裡沒有任何金額欄位** —— 只有 `productId` / `quantity` / `optionIds` / `discountCode`。單價一律由 `catalog.sellable` 與 `resolveOptions` 查出來 |
| 不得在前端信任任何金額 | 前端**一個金額都不算**，只顯示後端回的數字。`promotions.ts` 的原始碼守衛測試仍然綠（驗收 13） |
| 新台幣整數元 | 全程 `int`，沿用既有算術 |
| 溢位用 `Math.addExact` / `multiplyExact` | `price()` 是從 `create` 原樣搬過來的，既有的 `addExact` / `multiplyExact` / `subtractExact` 全部保留 |
| 建立訂單時快照 | **不適用** —— 試算不建立訂單、不寫快照 |

**試算金額與實際帳單的關係：** 兩者走**同一段程式碼**（`price()`），所以在購物車不變、促銷規則不變、優惠碼額度仍在的前提下**必定相同**。會不同的情形只有三種，都是真實世界的變化而不是程式缺陷：促銷規則在期間邊界上過期／生效、優惠碼額度被別人用完、商品在這段時間內售完。

---

## 9. 施工階段

> 三個階段，**每階段獨立 CI 綠、獨立可合併、有自己的驗收子集**。全部是加法。

### S1 —— 優惠碼的不消耗試算（小）

**動到：** `Discounts.java`（+1 方法）、`DiscountService.java`（`apply` 拆成 `resolve` + `applied`，新增 `quote`）、`DiscountRedemptionTest`（+2 條）

**這一階段不新增任何端點**，合進主線後對外行為**完全沒有變化** —— 只是多了一個沒有人呼叫的 `quote`。這正是 AGENTS.md「能編譯、測試綠、但功能尚未接上」那種安全的中間狀態。

**驗收子集：** 1、2、3、14

### S2 —— 試算端點（中）

**動到：** `Orders.java`（+3 record、+1 方法）、`OrderService.java`（抽 `price`、新增 `preview`）、`OrderController.java`（+1 端點）、新測試檔 `OrderPreviewTest`

**驗收子集：** 4、5、6、7、8、9、10、14、15、16

### S3 —— 前端接線與顯示（中）

**動到：** `shared/types.ts`（+2 interface）、新檔 `modules/ordering/preview.ts` 與 `preview.spec.ts`、`MenuView.vue`、`MenuView.dom.test.ts`、**樣式（G20i）**

**驗收子集：** 11、12、13、17、18

### 階段之外：給中斷續作的指示

- **S1 與 S2 之間可以停。** S1 單獨合併是安全的
- **S2 與 S3 之間可以停。** S2 單獨合併後端點存在但沒有人呼叫，既有畫面完全不變
- **S3 內部盡量不要停。** 真的要停，就停在「`preview.ts` 與它的單元測試已完成、`MenuView.vue` 尚未接線」—— 那同樣是一個沒人呼叫的新模組，CI 會綠
- 續作前先看 PR 描述的進度檢查表，**不要重做已完成的階段**

---

## 10. 驗收條件

### S1

- [ ] 1. `Discounts.quote` 存在；對同一組輸入，`quote` 與 `apply` 回傳的 `Applied` **每個欄位都相等**（除了呼叫 `apply` 會使 `redeemed_count` 加一）
- [ ] 2. **連續呼叫 `quote` 20 次，`redeemed_count` 仍然是 `0`**；之後呼叫一次 `apply`，變成 `1`
- [ ] 3. `quote` 與 `apply` 對下列五種無效情形**丟出相同的狀態碼與訊息**：停用、未開始、已結束、不適用本店、未達最低消費；`maxRedemptions` 已滿時兩者都回 409

### S2

- [ ] 4. `POST /api/orders/preview` 以**顧客身分**帶 1 個品項回 200，`subtotal` 等於 `(單價+選項加價)×數量`
- [ ] 5. **恆等式**：回應必定滿足 `discountAmount == itemDiscountAmount + codeDiscountAmount` 且 `total == subtotal − discountAmount`；促銷與優惠碼同時命中的案例也要驗
- [ ] 6. 試算的 `total` 與**同一組購物車實際建立訂單後**的 `order.total` **相等**（同一條測試裡先 preview 再 create 比對 —— 這是本規格最重要的一條）
- [ ] 7. **試算不寫入任何資料**：呼叫前後 `orders`、`order_items`、`order_discounts`、`audit_log` 的列數**都不變**，且 `discounts.redeemed_count` 不變
- [ ] 8. **試算不佔用庫存**：對設有每日可售數量的商品試算 10 次後，該商品的剩餘量**不變**，且之後仍可正常下單
- [ ] 9. **越權測試：** 店員對**非所屬分店**試算回 **403**；無 `ORDER_CREATE` 的帳號回 **403**；未登入回 **401**
- [ ] 10. 售完／已下架／本店未供應的商品在購物車裡時，試算回 **400** 且訊息與 `create` **完全相同**
- [ ] 14. **`create` 的行為零變更**：`OrderDiscountTest`、`ItemPromotionOrderTest`、`DiscountRedemptionTest`、`CoffeeIntegrationTest`、`HttpWorkflowTest` **一字不改且全綠**
- [ ] 15. `Identity.PERMISSIONS` **一字未改**
- [ ] 16. **零 migration** —— `db/migration/` 沒有新增任何檔案

### S3

- [ ] 11. `settleQuote` 的競態：序號 1 送出、序號 2 送出、**序號 2 先回、序號 1 後回** → 最終狀態是序號 2 的結果（單元測試，不經 DOM）
- [ ] 12. `resetQuote` 之後，一個在途的舊回應抵達**不會**把金額裝回去
- [ ] 17. 購物車有折抵時顯示「小計 / 品項促銷折抵 / 優惠碼折抵」三行且「總計」等於 `quote.total`；**完全沒有折抵時這三行不出現**，總計等於既有毛額
- [ ] 18. 試算端點回 500 時：**購物車照常渲染**、沒有錯誤橫幅、總計退回毛額；`pendingCashOrder` 存在時既有四行真實金額**一字未變**

### 全階段

- [ ] 13. G20a 驗收 12 的原始碼守衛測試（`promotions.ts` 不含 `unitPrice`／`optionsPrice`／`price`／`total`，無乘法）**仍然綠**
- [ ] 19. 沒有新增任何 npm 或 Maven 依賴
- [ ] 20. `cd frontend && npm ci && npm run build`、`npm test`、`cd backend && ./mvnw -B -ntp verify` 全綠
- [ ] 21. 執行位元：`backend/mvnw`、`scripts/build.sh`、`start-demo.sh` 皆 `100755`

---

## 11. 測試要求

### 11.1 後端 S1（`DiscountRedemptionTest` 加測，不新建檔）

| 情境 | 驗什麼 | 驗收 |
| --- | --- | --- |
| `quote` 20 次 | `redeemed_count` 仍為 0 | 2 |
| `quote` 後 `apply` 一次 | `redeemed_count` 為 1 | 2 |
| 同輸入的 `quote` vs `apply` | `Applied` 每個欄位相等 | 1 |
| 五種無效情形 ×2 方法 | 狀態碼與訊息相同 | 3 |

### 11.2 後端 S2（新檔 `OrderPreviewTest`）

| 情境 | 驗什麼 | 驗收 |
| --- | --- | --- |
| 顧客試算單品項 | 200 + `subtotal` 正確 | 4 |
| 促銷 + 優惠碼同時命中 | 兩條恆等式 | 5 |
| **先 preview 再 create** | `quote.total == order.total` | **6** |
| 前後比對四張表列數 + `redeemed_count` | 全部不變 | 7 |
| 對限量商品試算 10 次 | 剩餘量不變，之後仍可下單 | 8 |
| 店員跨店 / 無權限 / 未登入 | 403 / 403 / 401 | 9 |
| 售完商品在購物車 | 400 + 訊息與 `create` 相同 | 10 |

**驗收 6 要用真實 HTTP**（`HttpWorkflowTest` 那一類，帶 Cookie + CSRF），因為要比對的是**兩個端點實際回出去的 JSON**，而不是 Service 層的回傳值。

> **驗收 6 是本規格存在的理由。** 其他每一條都可以看成衛生條件，只有這一條直接驗「試算與帳單不會漂移」。它如果紅了，本規格就沒有意義 —— **不要為了讓它綠而調整任何一邊的期望值，要去找為什麼兩條路徑算出不同的數字。**

### 11.3 前端純函式（`modules/ordering/preview.spec.ts`）

與 `checkout.spec.ts`／`promotions.spec.ts` 同一個形狀：

- `beginQuote` 連續三次 → 序號 1、2、3
- `settleQuote` 亂序抵達（2 先、1 後）→ 採用 2，忽略 1（驗收 11）
- `settleQuote` 失敗 → `quote` 為 null、`failed` 為 true，且**不會**被更早的成功回應覆蓋
- `resetQuote` 後舊回應抵達 → 狀態不變（驗收 12）
- `showsQuote` 在途時仍回 true（沿用上一筆）

### 11.4 前端 DOM（`MenuView.dom.test.ts`）

- **先在 `mountMenu` 的預設 stub 加 `"/api/orders/preview": () => <零折抵的 quote>`**，確認既有案例全部仍綠（與 G20a 加 `/api/promotions/active` 時同一個坑）
- 有折抵 → 三行明細出現、總計為 `quote.total`（驗收 17）
- 零折抵 → 三行不出現、總計為毛額（驗收 17）
- 試算回 500 → 購物車照常、無錯誤橫幅、總計退回毛額（驗收 18）
- `pendingCashOrder` 存在 → 既有四行不變、試算明細不出現（驗收 18）

> **debounce 在 DOM 測試裡用 `vi.useFakeTimers()` 推進**，不要用真的 `await new Promise(r => setTimeout(r, 400))` —— 那會讓測試慢且不穩。

### 11.5 不要做的事

- **不要**為了讓驗收 6 綠而讓 `create` 或 `preview` 任何一邊改用不同的計價路徑。兩邊必須都走 `price()`
- **不要**把 `@Transactional(readOnly = true)` 拿掉，即使它讓某條測試比較難寫
- **不要**在 `promotions.ts` 裡引用 `OrderQuote` 或任何金額欄位（驗收 13）
- **不要**修改 `shared/testing/harness.ts` 的 `stubApi`（未設定路由回 404 是刻意的）
- **不要**為 debounce 引入 lodash 或任何套件 —— `setTimeout` 夠用（驗收 19）

---

## 12. 與其他工作的並行注意

**唯一可能同時在途的是 G20g**（[PR #70](https://github.com/choka1227/coffee_GPT6/pull/70) 的規格，實作分支 `codex/g20g-report-chart-tests`）。

| 檔案 | G20g | G20h |
| --- | --- | --- |
| `shared/testing/harness.ts` | 新增 `chartStub` 匯出 | **不動** |
| `ReportsView.vue` / `Chart.vue` | 動 | **不動** |
| `MenuView.vue` / `MenuView.dom.test.ts` | **不動** | 動 |
| `shared/types.ts` | **不動** | 在檔尾加兩個 interface |
| 後端 | **零變更** | 動 `coffee-catalog` 與 `coffee-orders` |

**檔案層級零交集，不需要合併順序協調。** 兩份都零 migration，不可能撞 Flyway 版號。

> 這張表是**查證過的**，不是推測的。G20g §12 在 v1.0 曾經寫過一個不存在的 `harness.ts` 交集，v1.1 已更正 —— **並行注意只寫查得到的交集**。

---

## 13. 設計決策

每一項都附理由與推翻它的代價。**本節沒有「待 PO 決定」**，設計決策已整批授權給 Claude（`AGENTS.md`「設計決策的歸屬」）。

### 13.1 試算與下單走同一段 `price()` —— **不接受第二份計價**

**決定：** `create` 與 `preview` 共用一個私有方法，差別只有一個 `redeem` boolean。

**理由：** 這是本規格唯一的存在理由。G20a §13.1 拒絕前端算金額，用的就是「第二份實作必定漂移」這個論證；如果後端自己長出第二份，等於把同一個問題搬到後端 —— 而且更難發現，因為兩份都是 Java、都看起來很對。

**推翻的代價：** 要維持兩份實作同步，就得為每一條計價規則寫兩套測試，並且每次改價邏輯都要記得改兩邊。**漏一次的症狀是顧客被多收或少收錢。**

### 13.2 `Discounts.quote` 與 `apply` 分家 —— **消耗是 `apply` 獨有的副作用**

**決定：** 把驗證與計算抽成 `resolve` + `applied`，`apply` 在其上多做「取行鎖 + `redeemed_count+1`」。

**理由：** 見 §5.1。不分家的話，光是把購物車加減幾次就會把優惠碼用光，而且**在 `maxRedemptions` 為 null 時完全看不出來**。

**推翻的代價：** 讓 `preview` 直接呼叫 `apply`，就是接受「瀏覽會消耗額度」。那不是一個權衡，那是一個 bug。

**為什麼不是讓 `apply` 多一個參數：** 試過這個形狀 —— `apply(code, branchId, subtotal, now, boolean consume)`。問題是 `Discounts` 是**跨模組的 `api` interface**，一個名為 `apply` 但「可能不套用」的方法會讓呼叫端每次都要回去看那個 boolean 的意思。**兩個名字各自誠實**比一個名字加旗標好。（`OrderService.price` 的 `redeem` 旗標是 `internal` 的私有方法，不是跨模組契約，標準不同。）

### 13.3 試算不檢查營業狀態 —— **它回答價格，不回答能不能下單**

**決定：** `preview` 不呼叫 `branches.requireOrderable` / `requireOpen`，但保留 `ORDER_CREATE` 與店員的分店範圍檢查。

**理由：** 打烊時的把關已經在 `create` 做了，而且 G14／G19／G25 把營業時間與最後點餐時間顯示在畫面上。如果試算也擋，顧客在開店前瀏覽時購物車會完全沒有金額 —— **而那正是促銷最該影響他的時刻**。「這一籃多少錢」在打烊時也有正確答案。

**代價：** 試算會在一個當下送不出去的購物車上成功。這是**時機**的落差，不是**金額**的落差 —— 他明天來買就是這個價。

**推翻的代價：** 加那兩行呼叫即可，前端不必改（它本來就要處理試算失敗，§5.7）。**所以這個決定很便宜就能推翻** —— 如果日後發現顧客因為「有金額」而誤以為能下單，直接加回去。

### 13.4 `@Transactional(readOnly = true)` 是紅線，不是效能設定

**決定：** `preview` 標 `readOnly = true`。

**理由：** 它讓「這條路徑不可寫入」由資料庫強制，而不是靠下一個改這段程式的人記得。§5.1 那個 bug 的本質是「一個看起來無害的共用方法裡藏著副作用」—— **同一類問題還會再來**，差別只在下次藏的是稽核記錄還是計數器。有這一行，下次會在 CI 就爆掉。

**推翻的代價：** 拿掉它，就要改為靠審查與測試覆蓋每一條可能的寫入路徑。**驗收 7 只能驗它當下沒有寫入，驗不了半年後新增的那一行。**

### 13.5 競態用「單調序號」而不是 `AbortController`

**決定：** 用 `sent` / `settled` 兩個序號比大小，不取消在途請求。

**理由：** `AbortController` 需要把 controller 穿過 `shared/api.ts` 的 `api()`（它目前不收 `signal`），那是**為了一個前端顯示問題去改所有端點共用的 HTTP 層**。序號方案完全在 `modules/ordering` 裡，而且是純函式、好測（驗收 11、12）。被取消的請求省下的後端負擔，已經由 300ms debounce 處理掉大半。

**推翻的代價：** 若日後量測到試算請求真的造成後端壓力，再讓 `api()` 支援 `signal` 並加上取消。那時序號邏輯仍然要留著 —— **取消不保證對方沒開始處理，序號才是正確性的保證**。

### 13.6 預估明細只在「有折抵」時出現

**決定：** `discountAmount === 0` 時不顯示小計與折抵行，總計照舊。

**理由：** 促銷規則現在是空的，所以本功能上線時**所有既有畫面一個像素都不會變**（§1.3 第 3 點）。這讓 S3 的合併風險降到最低 —— 任何畫面變化都代表真的有折抵命中，而不是本規格弄壞了什麼。

**推翻的代價：** 若日後希望永遠顯示小計，改一個 `v-if` 即可，但要同時更新驗收 17 與對應的 DOM 測試。

### 13.7 G20i（促銷提示的樣式）搭 S3 順風車

**決定：** `promo-hint` / `cart-line-promo` / `cart-promotion-progress` 三個 class 的樣式在 S3 一併補上。

**理由：** G20a 登記 G20i 時就寫了「夾進下一個本來就要動 `MenuView.vue` 的工作即可，不值得單獨開一輪」。S3 正是那個時機 —— 本來就要改 `MenuView.vue`、本來就要跑前端 CI。

**範圍限制：** 只加樣式，**不改任何 G20a 的 class 名、DOM 結構或 `v-if` 條件**（那會動到 G20a 的 DOM 測試）。新的 `cart-estimate` / `estimate-row` 樣式一併加。

**推翻的代價：** 拆出去單獨做，就要為三行 CSS 開一支分支、一個 PR、一輪 CI 與一次互審。

---

## 14. 登記給後續的缺口

| 編號 | 內容 | 來源 |
| --- | --- | --- |
| **G20j** | **試算端點的限流**。`POST /api/orders/preview` 每次都會查菜單與促銷規則。目前靠 300ms debounce 把量壓下來，但那是**前端自律**，繞過前端直接打端點不受限。本 repo 目前沒有任何限流基礎設施（錯誤碼表裡的 429 還沒有任何端點在用），要做就是一整套，不該夾在本規格裡 | §2.2 |
| G20b | 多規則疊加與單位消耗模型（**開工前提仍是要有真實促銷方案**） | G20 §13.2 |
| G20d | 選項層促銷（加料免費、第二份加料半價） | G20 §2.2 |
| G20f | 訂單層優惠碼折抵分攤到品項（**分攤演算法已寫好**放在 G20c 附錄 A）。**本規格沒有推翻 G20c §13.2 的拒絕理由** —— 試算端點讓顧客看得到總折抵，但不需要知道它怎麼攤到每個品項 | G20c §13.2 |
| G21 | 會員價與員工價 | G07 §11.2 |

**這些都不計入規格庫存**，登記的目的是讓下一輪不用重新推導。

編號說明：`G20i` 由 G20a §14 占用（促銷提示的樣式，本規格 S3 順道做掉），`G20j` 由本規格占用，所以 **`G20k`** 是 `G20` 系列下一個未使用號。主序列的下一個未使用號仍是 `G29`。

---

## 15. 版本紀錄

| 日期 | 版本 | 變更 |
| --- | --- | --- |
| 2026-10-05 | v1.0 | 初版。依 G20a §13.1／§14 的 G20h 登記產出，開工前提（G20 與 G20a 皆已合併）於本日滿足。**本規格最重要的發現是 §5.1**：`DiscountService.apply` 會 `redeemed_count+1` 並取行鎖，試算若直接重用它，顧客光是加減購物車就會把優惠碼額度用光 —— 因此 S1 先把 `quote` 與 `apply` 分家。結構上的防線是 §5.3 的 `@Transactional(readOnly = true)`，讓「試算不可寫入」由資料庫強制而不是靠審查記得。驗收 6（先 preview 再 create，比對 `total` 相等）是本規格存在的理由 |
