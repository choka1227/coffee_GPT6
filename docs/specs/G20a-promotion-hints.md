# G20a — 菜單與購物車的促銷提示

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G20a |
| 版本 | v1.1（2026-10-05） |
| 來源 | G20 §13.5「不自動把贈品加進購物車」與 §13.12「菜單不顯示促銷徽章」各自登記的同一個缺口，G20 §14 列為 G20a |
| 前置條件 | **已滿足。** G20 已隨 [PR #65](https://github.com/choka1227/coffee_GPT6/pull/65) 於 2026-10-04 合併進主線（合併提交 `e81fa44`，Flyway `V14__item_promotions.sql`） |
| Flyway | **零 migration。`V15` 仍然空著** |
| 施工階段 | 三階段（S1 後端端點／S2 菜單卡提示／S3 購物車提示） |

---

## 1. 背景與目標

### 1.1 G20 上線後的實際狀態

G20 的促銷是**自動套用**的（§13.6：沒有 `code` 欄位，建立訂單的請求也沒有任何促銷欄位）。後端在 `OrderService.create` 裡呼叫 `Promotions.apply`，算出折抵、寫進 `order_items.discount_amount` 與 `orders.item_discount_amount`。這一段是對的，而且是唯一的金額真相。

問題在**顧客在決定要買什麼的那一刻，看不到任何促銷存在的痕跡**：

| 畫面 | 顧客看到什麼 | 促銷在哪裡 |
| --- | --- | --- |
| 菜單商品卡（`MenuView.vue:583-634`） | 名稱、副標、**定價**、售完／剩餘徽章 | 完全沒有 |
| 購物車品項列（`MenuView.vue:682-701`） | 名稱、選項、`(unitPrice+optionsPrice)*quantity` | 完全沒有 |
| 購物車總計（`MenuView.vue:757-762`） | 「總計」= 毛額合計 | 完全沒有 |
| POS 現金第二階段（`MenuView.vue:733-748`） | 小計 / 品項促銷折抵 / 優惠碼折抵 / 應收 | **終於出現** |

最後一列是 G20 S3 做的，而它**只在 `pendingCashOrder` 存在時才渲染** —— 依 `checkout.ts:7-14` 的 `checkoutPath`，那條路徑是 `POS_CASH_TWO_STAGE`，**只有店員的現金收銀會走到**。顧客自己點餐走 `CUSTOMER_PENDING`、刷卡走 `ECPAY`，兩條路徑在送出前都只看得到毛額的「總計」。

### 1.2 這為什麼是缺口，而不是「只是沒做」

**一個看不見的促銷，等於沒有促銷。** 「第二杯半價」存在的唯一理由是讓本來只買一杯的人買兩杯。顧客不知道它存在時：

- 他買一杯就走 —— 促銷的行為誘因**完全沒有發生**
- 他剛好買了兩杯 —— 折抵默默生效，他事後才在明細裡發現，**促銷變成一份意外的禮物而不是一個購買理由**

兩種結果都是：店家承擔了折抵的成本，卻沒有換到它想買的那個行為。G20 §13.5 把這件事寫得很清楚：「**已知且刻意接受的後果：顧客只買 1 杯時看不到任何提示。**」那是當時為了收斂 G20 的範圍而刻意接受的，不是認為它不重要 —— 同一節的 §13.12 也寫了「推翻的代價：加一支 `GET /api/promotions/active?branchId=`（顧客可讀，只回已啟用且在期間內的規則，不含成本資訊），前端在商品卡與購物車顯示。**純加法**，登記為後續（§14）。」

本規格就是把那個「純加法」做掉。

### 1.3 為什麼現在做

1. **閘門已解除。** G20 的規則主檔 `item_promotions` 與 `PromotionService.apply` 的篩選查詢都已在主線上，本規格要的資料**不需要任何 schema 變更就拿得到**
2. **成本只會愈來愈高。** 促銷規則現在是空的（`InitialData` 沒有建任何 `item_promotions` 列），所以今天上線的提示功能**不會改變任何既有訂單的任何金額或任何畫面**。等到有真實規則在跑，再動菜單與購物車就要同時驗「有促銷」與「沒促銷」兩套畫面
3. **它不是 G20b。** G20b（多規則疊加）的開工前提是「要有真實促銷方案」，因為疊加優先序沒有真實資料就一定是猜的。**本規格不需要真實資料** —— 它顯示的是規則自己已經寫明的條件（`percent`、`nth`、`targetKind`），不需要判斷任何商業取捨

### 1.4 目標

一句話：**讓「有促銷」這件事在顧客決定買多少之前就看得到，而且不在前端算任何金額。**

拆成三條可驗的紅線：

1. 菜單商品卡上，命中促銷的商品要看得出「它有促銷，條件是什麼」
2. 購物車裡，**差一點就滿足條件**的情況要講出來（「再加 1 杯可享第 2 件 5 折」）—— 這是 §13.5「數量由顧客決定」留下的缺口的正面解法
3. **前端一個折抵金額都不算。** 金額的唯一來源仍然是後端

第 3 條是本規格最重要的設計約束，理由見 §13.1。

---

## 2. 範圍

### 2.1 在範圍內

- 新增 `GET /api/promotions/active?branchId=` —— 任何已登入者可讀，只回已啟用且在期間內的規則，**不含成本、不含排程、不含分店欄位**
- `Promotions` api 新增一個顧客安全的投影 record 與一支方法
- 菜單商品卡顯示促銷提示（文字，不是金額）
- 購物車顯示促銷提示與「再加 N 件即可享」的門檻提示（**只數件數，不算金額**）
- 上述三者的測試，含 `GET /api/promotions`（總部清單）仍然擋住非總部的越權測試

### 2.2 不在範圍內

| 項目 | 為什麼不在範圍 | 去哪裡 |
| --- | --- | --- |
| **前端預先算出折抵金額** | 會變成金額演算法的第二份實作，必定與後端漂移。理由見 §13.1 | 登記為 **G20h**（後端購物車試算端點） |
| 自動把贈品加進購物車 | G20 §13.5 已決定「數量由顧客決定」，本規格不推翻那個決定，只補它留下的提示缺口 | 維持 G20 §13.5 的決定 |
| 多規則疊加的提示 | 後端 `PromotionService.best` 目前就是「只取折抵最大的一條」（G20 §13.2）。提示端不該顯示一個系統不會套用的疊加結果 | G20b |
| 選項層促銷的提示 | 選項層促銷本身還不存在 | G20d |
| 優惠碼（`discounts`）的提示 | 優惠碼是給特定對象的，依定義不該在菜單上廣告（G20 §13.6 把兩者刻意分開） | 不做 |
| 促銷的圖片／橫幅／行銷版位 | 需要素材與版面決策，而且不改變任何功能 | 不做 |
| 報表端的促銷成效 | 口徑問題由 G20c 處理 | G20c |

---

## 3. 涉及模組與邊界

| 模組 | 動什麼 | 邊界 |
| --- | --- | --- |
| `coffee-catalog` | `api/Promotions.java` 加 record + 方法；`internal/PromotionService.java` 加實作；`internal/PromotionController.java` 加端點 | **促銷主檔一向屬於 catalog**，本規格不移動歸屬 |
| `coffee-shared` | 不動 | — |
| `coffee-orders` | **完全不動** | 本規格不碰 `OrderService.create`，不碰任何金額寫入路徑 |
| `coffee-app` | 不動（`SecurityConfiguration` 不需要改，理由見 §7.2） | — |
| 前端 `modules/ordering` | `MenuView.vue`、`MenuView.dom.test.ts` | 讀取透過 `shared/api.ts`，不直接 `fetch` |
| 前端 `shared` | `types.ts` 加型別；`testing/fixtures.ts` 加 fixture；`testing/harness.ts` **不動** | — |

**沒有任何跨模組的新依賴。** 前端 `modules/ordering` 讀 `/api/promotions/active` 是 HTTP 呼叫，與它既有的 `/api/menu`、`/api/branches` 呼叫同一類，不是程式碼層的模組依賴。`ModuleBoundariesTest` 的兩條規則（只依賴對方 `api`、不得循環）都不受影響。

---

## 4. DB schema 與 migration

**本規格零 migration。不新增任何 Flyway 檔案，不新增任何索引，不修改任何既有 migration。**

用到的資料全部來自 G20 已建好的 `item_promotions`（`V14__item_promotions.sql:1-21`）：

```
item_promotions.id, name, kind, percent, nth, target_kind, product_id, category
                   branch_id, starts_at, ends_at, active
```

篩選用的索引也已經在：`idx_item_promotions_active ON item_promotions(active, branch_id)`（`V14:23`）。本規格的查詢 `where active=true and (branch_id is null or branch_id=?)` 與那支索引的欄位與順序完全相符，**不需要新索引**。

> **`V15` 仍然空著。** G20c 也是零 migration。下一份需要 schema 的規格請占用 `V15`。

---

## 5. 技術設計

### 5.1 S1：顧客安全的投影 record

在 `Promotions`（`coffee-catalog/api/Promotions.java`）新增：

```java
record ActiveRule(
    String id,
    String name,
    String kind,
    int percent,
    int nth,
    String targetKind,
    String targetId) {}

List<ActiveRule> active(Actor actor, String branchId);
```

**為什麼不直接回既有的 `Rule`**（`Promotions.java:7-19`）：`Rule` 帶著 `branchId`、`startsAt`、`endsAt`、`active` 四個欄位。它們不是成本，但也不是顧客需要的東西，而且 `startsAt`／`endsAt` 等於把促銷的排程表公開（「這個促銷下週三結束」）。**一個端點只回它的消費端會用的欄位**，這與 `CatalogService.withProductOptions` 在非 manage 路徑把 `cost` 硬寫成 `0`（`CatalogService.java:116`）是同一個原則：**不要讓投影的安全性取決於呼叫端記不記得忽略某個欄位。**

`targetId` 把 `Rule` 的 `productId` / `category` 兩個欄位收成一個，語意由 `targetKind` 決定 —— 這與 `Applied.targetId`（`Promotions.java:33`）已經在用的收法一致，前端不需要處理「兩個欄位剛好有一個是 null」。

> **成本欄位：** `item_promotions` 這張表**本來就沒有任何成本欄位**（見 §4 的欄位清單），所以「不含成本資訊」在本規格是結構上成立的，不靠程式碼記得過濾。驗收 5 仍然要驗它，因為那是一條**不該被未來的欄位新增悄悄破壞**的紅線。

### 5.2 S1：`PromotionService` 的實作 —— 把既有查詢抽成一支私有方法

`PromotionService.apply`（`PromotionService.java:188-199`）裡已經有一段「篩出此刻有效的規則」的查詢。**新端點要的篩選條件與它一字不差**，所以**抽出來共用，不要複製第二份**：

```java
private List<Rule> activeRules(String branchId, long atEpochMs) {
  return db.query(
      "select * from item_promotions where active=true"
          + " and (branch_id is null or branch_id=?)"
          + " and (starts_at is null or starts_at<=?)"
          + " and (ends_at is null or ends_at>=?) order by id",
      this::row,
      branchId,
      atEpochMs,
      atEpochMs);
}
```

`apply` 改為呼叫它（**行為零變更**，是純抽取），新方法也呼叫它：

```java
@Override
public List<ActiveRule> active(Actor actor, String branchId) {
  Problem.check(branchId != null && !branchId.isBlank(), "請選擇分店");
  return activeRules(branchId, System.currentTimeMillis()).stream()
      .map(r -> new ActiveRule(
          r.id(), r.name(), r.kind(), r.percent(), r.nth(), r.targetKind(),
          "PRODUCT".equals(r.targetKind()) ? r.productId() : r.category()))
      .toList();
}
```

**為什麼共用一支方法是這一段最重要的事**：`active=true`、`branch_id is null or =?`、`starts_at<=?`、`ends_at>=?` 這四個條件定義了「什麼叫做現在有效的促銷」。如果提示端自己寫一份，兩邊就會各自定義「有效」—— 然後出現「菜單說有促銷、結帳沒折到」或反過來，而那是**最難追的那一類 bug**：兩邊的程式碼單看都對。

`actor` 參數**目前不被使用**（理由見 §7.1），但仍然收在簽章裡：這是本 repo 所有 `Promotions` / `Catalog` 方法的既有形狀，而且日後若要依角色調整投影，不必改 interface。**不要**因為沒用到就把它拿掉。

> `Problem.check` 的訊息用台灣用語繁體中文，與 `CatalogService.list` 的「請選擇分店」（`CatalogService.java:87`）**完全相同** —— 同一個缺失條件在不同端點給不同訊息，只會讓前端的錯誤處理長出分支。

### 5.3 S1：`PromotionController` 的新端點

```java
@GetMapping("/active")
List<Promotions.ActiveRule> active(
    @RequestAttribute Actor actor, @RequestParam(required = false) String branchId) {
  return promotions.active(actor, branchId);
}
```

**`required = false` 是刻意的，不是筆誤**（v1.1 更正，v1.0 此處寫成預設的 `required = true`，與本規格自己的 §6.3 錯誤表互相矛盾）：`required = true` 時，缺少 `branchId` 會在請求進到 Service 之前就被 Spring 擋下來丟 `MissingServletRequestParameterException`，回出去的訊息**不會**是 §6.3 指定的 `請選擇分店`，而且是英文的。要讓「缺少」與「空白」兩種情形都回同一個中文訊息，參數就必須進得到 `PromotionService.active` 的 `Problem.check`。

加在既有的 `PromotionController`（`/api/promotions`）裡。**路由不會與既有的 `@GetMapping`（`/api/promotions` 本身）相撞** —— 一個是集合路徑、一個是子路徑 `/active`，Spring 以最長前綴比對。

> **為什麼不開一支新的 Controller**：促銷的讀寫都在這一支，分開會讓「促銷的 HTTP 入口在哪裡」變成兩個答案。權限差異由 Service 層的方法各自負責（`list`／`save` 走 `requireHeadquarters`，`active` 不走），這與 `CatalogController` 用同一支 Controller 同時服務 `manage=true`（總部）與 `manage=false`（顧客）是同一個做法。

### 5.4 S2：前端型別與讀取

**(a) `shared/types.ts`** 新增（放在既有的 `PromotionRule`（`types.ts:262-275`）**後面**，兩者不要合併 —— 一個是總部維護用的完整規則、一個是顧客可見的投影，合併會讓維護頁誤用投影型別）：

```ts
export interface ActivePromotion {
  id: string;
  name: string;
  kind: "ITEM_PERCENT" | "NTH_PERCENT";
  percent: number;
  nth: number;
  targetKind: "PRODUCT" | "CATEGORY";
  targetId: string;
}
```

**全部非 null。** 後端的投影保證了這件事（`id`／`name`／`kind`／`targetKind` 在 `item_promotions` 都是 `NOT NULL`，`targetId` 由 `CHECK` 保證兩者恰有一個非 null）。

**(b) `MenuView.vue` 的讀取**：在 `loadMenu`（`MenuView.vue:172-185`，既有實作只打 `/menu`）裡與菜單**並行**取回，不要另開一支 `onMounted`：

```ts
const promotions = ref<ActivePromotion[]>([]);
```

```ts
async function loadMenu(showError = true) {
  if (!branchId.value) {
    products.value = [];
    promotions.value = [];
    return;
  }
  products.value = [];
  promotions.value = [];
  try {
    const query = `?branchId=${encodeURIComponent(branchId.value)}`;
    const [menuResult, promotionResult] = await Promise.all([
      api<Product[]>(`/menu${query}`),
      api<ActivePromotion[]>(`/promotions/active${query}`).catch(() => []),
    ]);
    products.value = menuResult;
    promotions.value = promotionResult;
    if (unavailableCart.value.length)
      notify("切換分店後，點餐單中有商品已售完或未供應，請先移除");
  } catch (e) {
    if (showError) error.value = (e as Error).message;
    else notify((e as Error).message);
  }
}
```

**`.catch(() => [])` 是刻意的，不要拿掉。** 促銷提示是**體驗**；菜單是**功能**。提示拿不到時正確的行為是「沒有提示的菜單」，不是「點不了餐」。這也讓 S2 對既有流程是真正的加法：促銷端點掛掉、權限改了、或路由還沒上線，點餐一切照舊。

> **⚠️ 會打到既有測試的地雷：** `stubApi`（`shared/testing/harness.ts`）對**沒有設定的路由回 404**，而 `api()` 收到 404 會 `throw`。`loadMenu` 多打一支端點之後，`MenuView.dom.test.ts` 既有的每一個案例都會經過那個 404 分支。有 `.catch` 在，**測試不會紅**（這正是上面那個 catch 的附帶好處），但斷言不到真實行為。**S2 必須同時在 `MenuView.dom.test.ts` 的 `mountMenu`（`MenuView.dom.test.ts` 的 `stubApi({...})` 路由表）的預設 stub 加上 `"/api/promotions/active": () => []`**，並讓需要促銷的案例覆寫它。這與 PR #65 在 `OrderPaginationTest`、G20c 在 `ReportAggregationTest` 踩到的是同一類坑：**本 repo 有多處集中定義的測試替身，新增端點時要一起掃。**

**(c) 比對用的索引**（`computed`）：

```ts
const promotionByProduct = computed(() => {
  const byProduct = new Map<string, ActivePromotion>();
  const byCategory = new Map<string, ActivePromotion>();
  for (const rule of promotions.value) {
    const target = rule.targetKind === "PRODUCT" ? byProduct : byCategory;
    if (!target.has(rule.targetId)) target.set(rule.targetId, rule);
  }
  return { byProduct, byCategory };
});
function promotionFor(p: Product): ActivePromotion | null {
  const { byProduct, byCategory } = promotionByProduct.value;
  return byProduct.get(p.id!) ?? byCategory.get(p.category) ?? null;
}
```

**一個商品只顯示一條提示，`PRODUCT` 優先於 `CATEGORY`。** 理由見 §13.2。同一個 target 有多條規則時取**第一條**（後端已 `order by id`，所以這個「第一條」是穩定的，不是隨機的）。

### 5.5 S2：菜單商品卡的提示

在 `product-copy` 的價格那一行**下面**新增一個元素（`MenuView.vue:610-618` 一帶）：

```html
<p v-if="p.availability !== 'SOLD_OUT' && promotionFor(p)" class="promo-hint">
  {{ promotionText(promotionFor(p)!) }}
</p>
```

**提示文字由規則自己生成**（純函式，放在 `MenuView.vue` 的 `<script setup>`，或與 `checkout.ts` 同層的新檔 —— 見 §9 的階段說明）：

```ts
export function promotionText(rule: ActivePromotion): string {
  return rule.kind === "ITEM_PERCENT"
    ? `${rule.name}：每件 ${discountLabel(rule.percent)}`
    : `${rule.name}：第 ${rule.nth} 件 ${discountLabel(rule.percent)}`;
}
```

`discountLabel(percent)` 把「折抵的百分比」翻成台灣的折扣說法。**這一層轉換不能省，而且是本節最容易做錯的地方**：

| `percent`（折抵掉幾 %） | 台灣說法 | 不能寫成 |
| --- | --- | --- |
| 100 | **免費** | ~~0 折~~ |
| 50 | **5 折** | ~~50 折~~、~~打 50%~~ |
| 10 | **9 折** | ~~1 折~~ |
| 15 | **85 折** | ~~1.5 折~~ |
| 33 | **67 折** | ~~6.7 折~~ |

```ts
export function discountLabel(percent: number): string {
  if (percent >= 100) return "免費";
  const remaining = 100 - percent;            // 付幾 %
  return remaining % 10 === 0
    ? `${remaining / 10} 折`                  // 50 → 5 折
    : `${remaining} 折`;                      // 85 → 85 折、67 → 67 折
}
```

**規則：`percent` 是「折掉的比例」，台灣的「X 折」是「付的比例」，兩者是 `100 - percent`。** 這在 `PromotionService.evaluate`（`PromotionService.java:246`）看得很清楚：`discount = unitPrice * percent / 100`，算出來的是**折抵額**。寫反了畫面會把「第二件 5 折」顯示成「第二件 95 折」，而那個錯誤不會讓任何測試變紅 —— 所以驗收 8 把這張對照表的五個值都釘成測試。

**整十折用一位數（5 折），非整十折用兩位數（85 折）**，這是台灣的慣例寫法；`67 折` 這種不整的值也照這個規則走，不要寫成 `6.7 折`（小數的折數在台灣幾乎不出現，而且會讓人誤讀成 67 折）。

**(b) 為什麼提示不放進照片的徽章位**（`MenuView.vue:598-607`）：那個位置現在有兩個競爭者 —— `sold-out` 與 `p.badge`，用 `v-if` / `v-else-if` 決定優先序。**G08a（PR #62）那個缺陷的成因，就是兩個徽章共用一個位置而沒有定義優先序**（剩餘徽章與售完徽章並存，規格沒寫關係，實作照字面做是對的）。在同一個位置塞第三個競爭者，是去再製造一次同一個 bug。促銷提示放在 `product-copy` 裡自己的一行，**與任何既有元素都沒有排他關係**，所以不需要定義優先序 —— 這是結構上避開問題，不是靠規則記得寫對。

**(c) 售完時不顯示提示**（上面的 `p.availability !== 'SOLD_OUT'`）：買不到的東西有促銷是純粹的噪音，而且卡片已經有 `unavailable` 的灰階樣式，再疊一行促銷會互相打架。

### 5.6 S3：購物車的提示與門檻

購物車要回答兩個問題，**兩個都不需要算金額**：

**(a) 這一列有促銷** —— 在品項列（`MenuView.vue:682-701`）的選項 `<small>` 下面加一行提示，文字與菜單卡同一個 `promotionText`。

**(b) 再加幾件就滿足條件** —— 只有 `NTH_PERCENT` 需要，因為 `ITEM_PERCENT` 沒有數量門檻（`PromotionService.evaluate:233` 的 `discountedUnits = units.size()`，買一件就折）。

門檻的算法必須與後端的**件數**邏輯一致。後端是（`PromotionService.evaluate:235-237`）：

```java
discountedUnits = units.size() / rule.nth();
if (discountedUnits == 0) return null;
```

`units` 是**命中規則的所有列、依 `quantity` 展開的單件**。所以前端要數的是同一件事：

```ts
export function promotionProgress(input: {
  rule: ActivePromotion;
  matchedUnits: number;
}): { discountedUnits: number; unitsToNext: number } {
  if (input.rule.kind === "ITEM_PERCENT")
    return { discountedUnits: input.matchedUnits, unitsToNext: 0 };
  const nth = input.rule.nth;
  const discountedUnits = Math.floor(input.matchedUnits / nth);
  const unitsToNext = nth - (input.matchedUnits % nth);
  return { discountedUnits, unitsToNext };
}
```

`matchedUnits` 由購物車算出：**所有 `promotionFor(line)` 指向同一條規則的列，其 `quantity` 之和**。注意 `targetKind='CATEGORY'` 時跨商品累加（兩杯不同的拿鐵與美式都算進「咖啡」分類的件數）—— 這與後端 `evaluate` 的 `matches` 判斷（`PromotionService.java:221-223`）一致。

**提示文字**：

```ts
export function promotionCartHint(input: {
  rule: ActivePromotion;
  matchedUnits: number;
}): string {
  const { discountedUnits, unitsToNext } = promotionProgress(input);
  if (input.rule.kind === "ITEM_PERCENT")
    return `已符合「${input.rule.name}」：每件 ${discountLabel(input.rule.percent)}`;
  if (discountedUnits === 0)
    return `再加 ${unitsToNext} 件可享「${input.rule.name}」第 ${input.rule.nth} 件 ${discountLabel(input.rule.percent)}`;
  return `已符合「${input.rule.name}」：已折 ${discountedUnits} 件，再加 ${unitsToNext} 件可再折 1 件`;
}
```

渲染位置：購物車的品項列之後、備註欄之前（`MenuView.vue:702` 一帶，備註欄 `cart-note` 之前），**每條命中的規則一行**，不是每個品項一行 —— 否則三杯同分類的飲料會印三次同一句話。

> **這一段是本規格唯一碰到「數字」的地方，而它數的是件數，不是錢。** `matchedUnits`、`discountedUnits`、`unitsToNext` 全部是件數。**沒有任何一處出現 `unitPrice`、`optionsPrice`、`total` 或任何乘法。** 這條界線由驗收 12 與 §11.2 的程式碼檢查守住。

**(c) `pendingCashOrder` 存在時不顯示門檻提示。** 那時後端已經算完並回了真正的折抵（`MenuView.vue:733-748` 的四行），**真金額一出現，預測就該讓位** —— 兩者並存只會讓店員在螢幕上同時看到「再加 1 件可享」與「已折 25 元」，而第一句在那個時點已經沒有意義（訂單已經建了，加品項要改單）。

---

## 6. API

### 6.1 `GET /api/promotions/active`（新增）

**請求**

| 參數 | 位置 | 必填 | 說明 |
| --- | --- | --- | --- |
| `branchId` | query | **是** | 分店 id。缺少或空白回 400 |

**回應 200** —— `ActiveRule` 陣列，依 `id` 升冪（後端 `order by id`）：

```json
[
  {
    "id": "p-001",
    "name": "下午茶第二杯半價",
    "kind": "NTH_PERCENT",
    "percent": 50,
    "nth": 2,
    "targetKind": "CATEGORY",
    "targetId": "咖啡"
  }
]
```

沒有任何有效規則時回 `[]`（**不是 404**）—— 「這家店目前沒有促銷」是正常狀態，不是找不到資源。

**回應欄位是完整清單。** 不含 `branchId`、`startsAt`、`endsAt`、`active`（已在後端篩掉，見 §5.1），**不含任何成本欄位**。

### 6.2 既有端點

| 端點 | 本規格是否改動 |
| --- | --- |
| `GET /api/promotions` | **不改。** 仍然是 `MENU_MANAGE` + `global()` |
| `POST /api/promotions` | **不改** |
| `GET /api/menu` | **不改。** 促銷資訊走獨立端點，不塞進菜單回應，理由見 §13.3 |
| 建立訂單 | **不改。** 請求與回應一個欄位都不動 |

### 6.3 錯誤碼

| 狀態 | 情境 | 訊息 |
| --- | --- | --- |
| 400 | `branchId` 缺少或空白 | `請選擇分店` |
| 401 | 未登入 | `請先登入`（由 `SecurityConfiguration` 的 entry point 統一回） |

**沒有 403。** 任何已登入者都可讀，理由見 §7.1。

> **不存在的 `branchId` 回 `[]` 而不是 404。** 理由：查詢條件是 `branch_id is null or branch_id=?`，一個不存在的分店 id 會自然地只match 到全店通用的規則。為了回 404 要多一次 `select count(*) from branches`，**多一次往返換一個沒有消費端的錯誤碼**。`GET /api/menu` 在同樣的情境也沒有驗分店存在（`CatalogService.list:87` 只驗非空）—— 兩個端點的行為一致比各自「更嚴謹」有價值。

---

## 7. 權限與資料範圍

### 7.1 設計決策：任何已登入者可讀，**不檢查 `Actor.branch()`**

| 端點 | 權限 | 資料範圍 |
| --- | --- | --- |
| `GET /api/promotions/active` | **無需任何權限常數** | 依 `branchId` 參數，**不限制呼叫者的所屬分店** |
| `GET /api/promotions`（既有） | `MENU_MANAGE` + `global()` | GLOBAL |
| `POST /api/promotions`（既有） | `MENU_MANAGE` + `global()` | GLOBAL |

**不新增任何權限常數**，`Identity.PERMISSIONS` 一字不改。

**為什麼不檢查所屬分店：** 因為 `GET /api/menu?branchId=` 已經不檢查（`CatalogService.list:86-110` 的非 manage 路徑只驗 `branchId` 非空）。顧客本來就能瀏覽任一分店的菜單並向任一分店下單 —— 那是這套系統的產品前提，不是漏洞。促銷提示是**貼在那家店牆上的海報**，比菜單更公開。

如果這裡加上 `actor.branch(branchId)`，結果會是：**顧客（`SELF` scope，`branchId` 為 null）連自己要下單的那家店的促銷都讀不到**，而他讀得到那家店的整份菜單。這不是更安全，是壞掉。

**真正要守的那條線在別處：** `item_promotions` 的**成本與排程**不可外洩，而那條線由 §5.1 的投影守（投影裡沒有那些欄位），不是由分店檢查守。**驗收 5 與 6 驗的是這一條。**

### 7.2 `SecurityConfiguration` 不需要改

`/api/**` 一律 `authenticated()`（`SecurityConfiguration.java:43-44`），`permitAll` 只有 `/api/auth/csrf`、`/api/auth/login`、`/api/payments/ecpay/callback`、`/actuator/health`。新端點落在 `/api/**`，**自動需要登入，這正是要的行為**。

**不要把 `/api/promotions/active` 加進 `permitAll`。** 未登入者看促銷沒有任何商業價值（他還不能下單），而放寬一條規則就要重新論證「匿名流量打這支端點的成本」。驗收 4 驗未登入回 401。

### 7.3 CSRF

新端點是 `GET`，**不需要 CSRF token**（`api.ts:19` 只對非 GET 加 header）。既有的 CSRF 設定一字不改，`/api/payments/ecpay/callback` 仍然是唯一的例外。

---

## 8. 金額規則

**本規格不產生、不接收、不計算任何金額。**

| 規則 | 本規格的遵守方式 |
| --- | --- |
| 後端一律依有效菜單重算，忽略前端送來的金額 | **本規格沒有任何寫入端點**，沒有任何請求帶金額 |
| 不得在前端信任任何金額 | 前端只讀 `percent`／`nth`／`targetId`，**不算折抵**。唯一的算術是件數的整數除法與餘數（§5.6） |
| 新台幣整數元，不用 `double`／`BigDecimal` | `percent`、`nth`、件數全部是 `int`／整數。`discountLabel` 的 `100 - percent` 與 `remaining / 10` 都在整數域（`remaining % 10 === 0` 才做除法，所以不會產生小數） |
| 溢位用 `Math.addExact` | 本規格沒有乘法與累加金額，**不適用**。件數的累加上限由購物車列數與 `quantity` 的既有驗證擋住 |
| 建立訂單時快照 | **不適用**，本規格不建立訂單 |

**顧客看到的提示與他實際被折的金額之間的關係**：提示說的是「這條規則的條件與比例」，實際折抵由後端在 `OrderService.create` 算。兩者不可能不一致，因為**提示沒有宣告任何金額** —— 這正是 §13.1 選這個設計的理由。

---

## 9. 施工階段

> 三個階段，**每個階段獨立 CI 綠、獨立可合併、有自己的驗收子集**。階段之間全部是加法。

### S1 —— 後端端點（小～中）

**動到的檔案：** `coffee-catalog/api/Promotions.java`（加 record + 方法）、`coffee-catalog/internal/PromotionService.java`（抽 `activeRules` + 加 `active`）、`coffee-catalog/internal/PromotionController.java`（加 `@GetMapping("/active")`）、`coffee-app/src/test/.../ItemPromotionAdminTest.java`（加越權與投影測試，或新增一支測試類）

**驗收子集：** 1–7

**為什麼可以獨立合併：** 端點上線後**沒有任何呼叫端**（前端還沒改），而 `apply` 的改動是純抽取、行為零變更。這是 `AGENTS.md`「施工階段與中斷續作」說的那種安全的中間狀態：能編譯、測試綠、功能尚未接上。

### S2 —— 菜單商品卡的提示（中）

**動到的檔案：** `shared/types.ts`（加 `ActivePromotion`）、`MenuView.vue`（讀取 + `promotionFor` + 商品卡一行 + `promotionText`／`discountLabel`）、`MenuView.dom.test.ts`（**預設 stub 要加 `/api/promotions/active`**，見 §5.4(b) 的地雷警告）、`shared/testing/fixtures.ts`（加 `activePromotionFixture`）

**驗收子集：** 8–11

**`promotionText` 與 `discountLabel` 放哪裡：** 新增 `frontend/src/modules/ordering/promotions.ts`，與 `checkout.ts` 同層同性質 —— **純函式、可單獨 `vitest`、不碰 DOM**。這很重要：`discountLabel` 的五個邊界值（§5.5 的對照表）要用快跑的單元測試釘住，不是用 DOM 測試繞一圈去驗。本 repo 的既有慣例就是這樣（`checkout.ts` + `checkout.spec.ts`），照抄。

### S3 —— 購物車的提示與門檻（中）

**動到的檔案：** `modules/ordering/promotions.ts`（加 `promotionProgress`／`promotionCartHint`）、`promotions.spec.ts`（補測試）、`MenuView.vue`（購物車兩處）、`MenuView.dom.test.ts`（補案例）

**驗收子集：** 12–17

**為什麼 S2 與 S3 不合成一個階段：** S2 要新建一個檔案、一份 fixture、並**修到集中的測試替身**（會牽動 `MenuView.dom.test.ts` 的每一個既有案例）；S3 要在 897 行的 `MenuView.vue` 裡改購物車區塊、處理跨商品的分類累加、並與 `pendingCashOrder` 的既有四行互動。兩邊各自都是一次執行的份量，而且 S2 合併後 S3 的起點更乾淨（型別與讀取都已在主線）。

### 階段之外：給中斷續作的指示

- 分支用 `codex/g20a-promotion-hints`。**續作一律回到這支分支**，不要另開
- 若一次執行做不完 S1，推一個「`ActiveRule` record 與 `activeRules` 抽取已完成、`active` 方法與端點尚未接上」的中間狀態 —— **那個狀態是綠的**（純抽取 + 一個沒人呼叫的 record）
- PR 維持 draft 直到至少一個完整階段綠

---

## 10. 驗收條件

### S1

- [ ] 1. `GET /api/promotions/active?branchId=B1` 以**顧客身分**（`SELF` scope）呼叫回 200，且回傳只含該分店適用（`branch_id is null` 或 `=B1`）、`active=true`、且 `starts_at`／`ends_at` 涵蓋當下的規則
- [ ] 2. `active=false` 的規則**不出現**在回應裡
- [ ] 3. `starts_at` 在未來、或 `ends_at` 已過的規則**不出現**在回應裡
- [ ] 4. **未登入**呼叫回 **401**，訊息為 `請先登入`
- [ ] 5. 回應的每一個物件**只有** `id`、`name`、`kind`、`percent`、`nth`、`targetKind`、`targetId` 七個 key —— **沒有** `branchId`、`startsAt`、`endsAt`、`active`，也沒有任何成本欄位
- [ ] 6. **越權測試：** 顧客（`SELF`）與分店店長（`BRANCH`）呼叫既有的 `GET /api/promotions`（總部清單）**仍然回 403**；`POST /api/promotions` 同樣仍然回 403
- [ ] 7. `branchId` 缺少或空白回 **400**，訊息為 `請選擇分店`；不存在的 `branchId` 回 **200 + 只含全店通用規則**（不是 404）

### S2

- [ ] 8. `discountLabel` 的五個值：`100` → `免費`、`50` → `5 折`、`10` → `9 折`、`15` → `85 折`、`33` → `67 折`（單元測試，不經 DOM）
- [ ] 9. 命中 `NTH_PERCENT` 的商品卡顯示「第 N 件 X 折」；命中 `ITEM_PERCENT` 的顯示「每件 X 折」；兩者都帶規則名稱
- [ ] 10. `availability === 'SOLD_OUT'` 的商品卡**不顯示**促銷提示；照片徽章區的 `sold-out` / `p.badge` 行為**一字未變**（G08a 的「售完優先」不受影響）
- [ ] 11. `/api/promotions/active` 回 500 或 404 時，**菜單照常渲染**、沒有錯誤橫幅、沒有促銷提示（驗 §5.4(b) 的 `.catch`）

### S3

- [ ] 12. `modules/ordering/promotions.ts` 全檔**不出現** `unitPrice`、`optionsPrice`、`price`、`total` 任何一個字，也沒有任何 `*` 乘法 —— 提示層不算錢。**必須由 §11.2 那條讀取原始碼的測試守住**（v1.1 收緊：v1.0 寫成「可用測試**或 code review** 檢查」，而 code review 不會在下一次有人改這個檔時自動再跑一次 —— 那正是 §11.2 說它是「唯一一條靠結構而不是靠審查維持的紅線」要避免的事。**這條收緊不回頭要求 [PR #72](https://github.com/choka1227/coffee_GPT6/pull/72) 補**：它依 v1.0 的字面選了 code review，Claude 已於審查時逐字確認通過，是合規的；收緊適用於**未來任何改動 `promotions.ts` 的工作**）
- [ ] 13. 購物車有 1 件、規則為 `nth=2` → 顯示「再加 1 件可享…第 2 件 5 折」
- [ ] 14. 購物車有 2 件、規則為 `nth=2` → 顯示「已符合…已折 1 件，**再加 2 件**可再折 1 件」（**不是「再加 1 件」** —— 已經折掉第 2 件，下一件折抵要湊到第 4 件，所以 `unitsToNext = nth − matchedUnits % nth = 2 − 0 = 2`。這一條與驗收 13 刻意成對，就是要釘住「餘數為 0 時補滿一整輪」這個邊界）
- [ ] 15. `targetKind='CATEGORY'` 時**跨商品累加**：同分類兩個不同商品各 1 件 → 件數為 2，門檻判定與單一商品 2 件**相同**
- [ ] 16. `pendingCashOrder` 存在時**不顯示**門檻提示，既有的小計／品項促銷折抵／優惠碼折抵／應收四行**一字未變**
- [ ] 17. `npm run build` 與 `npm test` 綠；`./mvnw -B -ntp verify` 綠

### 全階段

- [ ] 18. `db/migration/` **沒有新增任何檔案**
- [ ] 19. `Identity.PERMISSIONS` **一字未改**
- [ ] 20. 建立訂單的請求與回應**一個欄位都沒變**；`OrderService` 未被修改

---

## 11. 測試要求

### 11.1 後端（S1）

放在 `backend/coffee-app/src/test/java/com/coffee/app/`，可加進既有的 `ItemPromotionAdminTest`（G20 建的）或新增 `PromotionHintTest`。

**必須涵蓋：**

| 情境 | 驗什麼 | 驗收 |
| --- | --- | --- |
| 顧客讀 `/active` | 200 + 內容正確 | 1 |
| `active=false` 的規則 | 不出現 | 2 |
| 未開始 / 已結束的規則 | 不出現 | 3 |
| **未登入** | 401 | 4 |
| 回應的 key 集合 | **只有七個**，逐一比對 | 5 |
| **顧客 / 店長讀 `GET /api/promotions`** | **403** | 6 |
| **顧客 / 店長打 `POST /api/promotions`** | **403** | 6 |
| 缺 `branchId` | 400 + 訊息 | 7 |
| 不存在的 `branchId` | 200 + 只含全店通用 | 7 |

驗收 5 要用**真實 HTTP**（`HttpWorkflowTest` 那一類，帶 Cookie + CSRF）而不是直接呼叫 Service —— 因為要驗的是**序列化後實際送出去的 JSON 有哪些 key**，那是 record 的形狀決定的，Service 層的回傳型別看不出來。

> 驗收 6 的兩條是本規格的**越權測試**。新端點放寬了讀取權限，所以要同時證明「**既有的那兩支沒有被一起放寬**」—— 這類回歸最容易在「把權限檢查從 Controller 搬到 Service」的重構裡無聲消失。

### 11.2 前端純函式（S2／S3）

`frontend/src/modules/ordering/promotions.spec.ts`，與 `checkout.spec.ts` 同一個形狀。

- `discountLabel`：§5.5 對照表的五個值（驗收 8），外加 `percent=1` → `99 折`
- `promotionText`：兩種 `kind` 各一條（驗收 9）
- `promotionProgress`：`matchedUnits` 為 0／1／2／3／4 配 `nth=2` 與 `nth=3`，以及 `ITEM_PERCENT`（驗收 13、14）
- `promotionCartHint`：三種分支（未達門檻／已折且可再折／`ITEM_PERCENT`）

**驗收 12 的檢查方式**：在 `promotions.spec.ts` 裡讀自己的原始碼並斷言不含金額字樣：

```ts
it("提示層不算錢", async () => {
  const source = await readFile(
    new URL("./promotions.ts", import.meta.url), "utf8");
  for (const forbidden of ["unitPrice", "optionsPrice", "price", "total"])
    expect(source).not.toContain(forbidden);
});
```

這條測試看起來很笨，但它守的是本規格**唯一一條靠結構而不是靠審查維持的紅線**：只要 `promotions.ts` 裡沒有價格，就不可能在前端算出折抵金額。日後有人想「順手」在提示裡顯示省了多少錢，會先看到這條測試紅掉，然後去讀 §13.1，而不是直接做。

### 11.3 前端 DOM（S2／S3）

`MenuView.dom.test.ts`，沿用既有的 `mountMenu` 與 `stubApi`。

- **先改 `mountMenu` 的預設 stub 加 `"/api/promotions/active": () => []`**（§5.4(b)），確認既有案例全部仍綠
- 商品卡提示的兩種 `kind`（驗收 9）、售完時不顯示（驗收 10）
- 端點失敗時菜單照常（驗收 11）—— stub 該路由回 500
- 購物車三種件數狀態（驗收 13、14）、分類跨商品累加（驗收 15）、`pendingCashOrder` 時不顯示（驗收 16）

### 11.4 不要做的事

- **不要**為了讓測試好寫而把 `.catch(() => [])` 拿掉
- **不要**放寬既有的 `ItemPromotionAdminTest` 對 `GET/POST /api/promotions` 的 403 斷言
- **不要**在 DOM 測試裡斷言任何金額字串 —— 提示層沒有金額，斷言金額就是把 G20h 的功能偷做進來
- **不要**修改 `shared/testing/harness.ts` 的 `stubApi`（未設定路由回 404 是刻意的設計，讓漏掉的端點被看見）

---

## 12. 與其他工作的並行注意

- **動到的既有後端檔案：** `Promotions.java`、`PromotionService.java`、`PromotionController.java`（全部在 `coffee-catalog`，全部是加法；`PromotionService.apply` 的改動是純抽取）
- **動到的既有前端檔案：** `shared/types.ts`（加一個 interface）、`MenuView.vue`、`MenuView.dom.test.ts`、`shared/testing/fixtures.ts`
- **與 G20c 的檔案交集：** `shared/types.ts` 一個檔。G20c 在那裡改 `Report` 與 `products[]`／`topToday[]`（`types.ts:140` 一帶的 `itemPromotion` 與報表段），本規格在 `PromotionRule` 後面（`types.ts:275` 之後、`ReconciliationPending` 之前）加一個新 interface。**兩者的行不重疊**，但同一個檔案仍可能產生 git 衝突，解法是純文字層面的，沒有語意衝突
- **與 G20c 的語意交集：零。** G20c 動報表（`coffee-reporting` + `ReportsView.vue`），本規格動菜單與購物車（`coffee-catalog` + `MenuView.vue`）。**沒有任何共用的後端檔案**
- **實作先後自由。** 兩份都不需要對方先完成
- **Flyway：** 兩份都是零 migration，**不可能撞版號**。`V15` 對兩者都是空的

---

## 13. 設計決策

> 以下每一項都是已經定案的決定，附理由與推翻它的代價。**不需要任何人核准**，下一輪要推翻就照「推翻的代價」那一段做。

### 13.1 前端不算折抵金額 —— **只顯示規則條件，不顯示省了多少錢**

**決定：** 提示文字只說「第 2 件 5 折」，**不說「可省 25 元」**。購物車不顯示任何預估的折抵金額或預估應收。顧客第一次看到真正的折抵金額，是在後端建立訂單之後（店員現金路徑看 `pendingCashOrder`，顧客看訂單明細）。

**理由：** 要在前端顯示「可省多少」，就要在 TypeScript 裡重做一份 `PromotionService.best` + `evaluate`（`PromotionService.java:201-262`）。那段邏輯有四個不顯然的地方：多規則取折抵最大者、平手時比 `promotionId` 字串序、`NTH_PERCENT` 要先把單件依 `unitPrice` 排序再折最便宜的那些、以及 `discount = unitPrice * percent / 100` 的整數除法截斷。**兩份實作遲早會漂移**，而漂移的症狀是「畫面說省 25、帳單折了 24」—— 顧客會認為店家在騙他，而這類 bug 兩邊的程式碼單看都對。

`AGENTS.md` 的「不得在前端信任任何金額」講的是輸入方向，但它背後的原則是**金額只有一個真相來源**。顯示一個前端自己算的金額，就是製造第二個真相來源。

**已知且刻意接受的後果：** 顧客知道「有第二件 5 折」但不知道確切省多少，要加到購物車並送出才看得到。對 `NTH_PERCENT` 這類「折最便宜的那一件」的規則，省多少本來就取決於他選了哪些品項，**提示階段給一個數字反而更容易錯**。

**推翻的代價：** 正確的推翻方式**不是**把演算法搬到前端，而是加一支後端試算端點（`POST /api/orders/preview`，收購物車、回 `Promotions.Applied` 與小計，不寫任何資料）。那樣金額仍然只有一個來源，代價是購物車每次變動多一次往返，以及要決定 debounce 與競態（使用者連點加號時，晚回來的舊回應不能覆蓋新狀態）。**已登記為 G20h**，見 §14。

### 13.2 一個商品只顯示一條提示，`PRODUCT` 優先於 `CATEGORY`

**決定：** `promotionFor` 回單一規則。同一商品同時被「商品級」與「分類級」規則命中時，顯示**商品級**那一條。同一層有多條時取 `order by id` 的第一條。

**理由：** 後端只會套用一條（`PromotionService.best` 取折抵最大者，G20 §13.2 的決定）。**提示端顯示兩條，就是宣告一個系統不會做的事。** 而提示端沒有金額（§13.1），所以**無法**用「折抵最大」來挑 —— 必須用一個不需要金額的規則。商品級優先於分類級，是因為它更具體：有人特地為這個商品設了規則，那條更可能是現在要宣傳的那個。

**已知後果：** 商品級規則折得比分類級少時，提示顯示的不是後端最終會套用的那一條。顧客看到「本品 9 折」、結帳折到的是分類的「第二件 5 折」—— **他被折得比預期多**，不會有客訴，但提示確實不精確。

**推翻的代價：** 要精確就要知道金額，就要 §13.1 的後端試算端點。在那之前任何「更聰明」的挑法都是在猜。

### 13.3 促銷不塞進 `GET /api/menu` —— **獨立端點**

**決定：** `Catalog.Product` 不加促銷欄位，`GET /api/menu` 的回應一字不動。促銷走 `GET /api/promotions/active`。

**理由：** 三層。(1) `GET /api/menu` 同時服務總部維護（`manage=true`）與顧客點餐（`manage=false`）兩條很不一樣的路徑，加欄位要同時想清楚兩條，而維護路徑根本不需要促銷；(2) 促銷是**分類級或商品級**的，塞進 product 列表會讓分類級規則在每一個命中的商品上重複一份；(3) 獨立端點讓 §5.4(b) 的 `.catch(() => [])` 成立 —— 促銷掛掉不影響菜單。塞在同一支端點裡就做不到這件事。

**代價：** 前端多一次 HTTP 往返。用 `Promise.all` 與菜單並行，**牆鐘時間幾乎不變**。

**推翻的代價：** 要合併成一支就要決定 `manage=true` 時回不回促銷、分類級規則怎麼不重複、以及菜單的失敗語意（促銷查詢失敗要不要讓整份菜單失敗）。三個都是新決策。

### 13.4 `percent` → 「X 折」的轉換放在前端

**決定：** 後端回原始的 `percent`（折抵比例），前端的 `discountLabel` 翻成台灣的折扣說法。

**理由：** `percent` 是計價用的數字，`折` 是顯示用的語言。後端已經有一條硬規則「錯誤訊息一律使用台灣用語的繁體中文」，但那是**錯誤訊息**；把顯示字串放進 API 回應會讓後端開始承擔文案，而文案改一次就要改 API。前端翻譯則讓同一份資料可以在不同位置用不同說法（商品卡「第 2 件 5 折」、購物車「再加 1 件可享…」），不必為每種說法加一個欄位。

**代價：** 轉換寫反的風險（§5.5 的對照表就是在防這件事），由驗收 8 的五個邊界值測試守住。

**推翻的代價：** 要搬到後端就要決定 `ActiveRule` 多幾個顯示欄位、以及那些欄位的文案歸誰維護。純加法，但會把文案責任移進 Java。

### 13.5 不推翻 G20 §13.5（不自動加贈品）

**決定：** 本規格**不**自動把數量從 1 變成 2，維持 G20 §13.5 的決定。本規格的做法是**告訴顧客再加一件會發生什麼**，由他自己決定。

**理由：** G20 §13.5 的理由仍然完全成立（自動加品項會連帶改 G08 的備量扣減、G06 的選項解析、與購物車的可編輯性）。而那一節寫的「已知後果：顧客只買 1 杯時看不到任何提示」—— **提示正是那個後果的解法，而且不需要承擔自動加品項的任何複雜度。**

**代價：** 顧客要自己按加號。相對於「系統偷偷改了我的購物車」，這個代價是正的。

### 13.6 提示放在 `product-copy`，不放進照片徽章位

**決定：** 促銷提示是 `product-copy` 裡自己的一行，不與 `sold-out` / `p.badge` / `remaining-badge` 競爭照片上的位置。

**理由：** **G08a（PR #62）的缺陷成因就是兩個徽章共用一個位置而規格沒定義優先序。** 照片徽章區現在用 `v-if` / `v-else-if` 排他（`MenuView.vue:598-607`），加第三個競爭者就要定義 3! 種順序裡哪一種對，而且每次再加都要重新想一次。**放在不衝突的位置是結構上解決，不是靠規則記得寫對。**

**代價：** 提示在卡片上比較不醒目（在文字區而不是壓在照片上）。純視覺，而且可以靠樣式補。

**推翻的代價：** 要搬進徽章區就要在規格裡寫出完整的優先序表（售完 / 促銷 / 自訂 badge / 剩餘量，四個），並為每一種組合寫一條 DOM 測試。那是 G08a 當初該做而沒做的事。

---

## 14. 登記給後續的缺口

| 編號 | 內容 | 來源 |
| --- | --- | --- |
| **G20h** | **後端購物車試算端點**（`POST /api/orders/preview`：收購物車、回 `Promotions.Applied` 與小計／折抵／應收，不寫任何資料）。讓購物車能顯示**真實**的預估折抵金額，而金額仍然只有後端一個來源。要處理 debounce 與「晚回來的舊回應不能覆蓋新狀態」的競態 | §13.1 |
| **G20i** | **促銷提示的樣式**（`promo-hint` / `cart-line-promo` / `cart-promotion-progress` 三個 class 目前沒有任何 CSS，提示會以未加樣式的 `<p>`／`<small>` 呈現）。§5.5 給了 class 名卻沒給樣式，§13.6 也只說「純視覺，可以靠樣式補」—— **是規格端沒寫，實作照規格做是對的**。規模極小，適合夾進下一個本來就要動 `MenuView.vue` 的工作，不值得單獨開一輪 | §5.5／§13.6（Claude 審查 PR #72 時登記） |
| G20b | 多規則疊加與單位消耗模型（**開工前提仍是要有真實促銷方案**） | G20 §13.2 |
| G20d | 選項層促銷（加料免費、第二份加料半價） | G20 §2.2 |
| G20f | 訂單層優惠碼折抵分攤到品項（**分攤演算法已寫好**放在 G20c 附錄 A） | G20c §13.2 |
| G20g | 報表圖表層的測試 | G20c §13.6 |

**這些都不計入規格庫存**，也都不是本規格的驗收條件。登記的目的是讓下一輪不用重新推導。

---

## 15. 版本紀錄

| 日期 | 版本 | 變更 |
| --- | --- | --- |
| 2026-10-05 | v1.1 | 審查 [PR #72](https://github.com/choka1227/coffee_GPT6/pull/72)（G20a 實作，已 `APPROVE`）時發現的**兩處規格端缺陷**，都不是實作的問題：(1) §5.3 的範例碼寫 `@RequestParam String branchId`（預設 `required = true`），與本規格自己的 §6.3「缺少 `branchId` 回 400 `請選擇分店`」互相矛盾 —— `required=true` 會讓 Spring 在進到 Service 前就丟英文的 `MissingServletRequestParameterException`。實作端已自行改為 `required = false` 並在 PR 描述主動揭露，**做法正確**，本版把範例碼與理由補正，避免下一個讀規格的人改回去;(2) 驗收 12 原寫「可用測試**或 code review** 檢查」，但 §11.2 同時說那條測試是「唯一一條靠結構而不是靠審查維持的紅線」—— 兩句互相抵消。本版收緊為必須有測試，且明述不回頭要求 #72 補。另登記 §5.5 的 `promo-hint` 無樣式（見 §14） |
| 2026-10-04 | v1.0 | 初版。依 G20 §14 的 G20a 登記產出。核心設計決策是 §13.1「前端不算折抵金額」—— 把 G20 §13.12 登記的「純加法」限縮為「只顯示規則條件」，並把「顯示真實預估金額」切出去成為 **G20h**（後端試算端點），理由是前端重做一份計價演算法必定與後端漂移。§13.6 明確避開 G08a 的徽章優先序坑 |
