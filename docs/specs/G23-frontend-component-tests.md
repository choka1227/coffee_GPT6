# G23 — 前端元件層測試（模板可見性）

規格版本 **v1.0**（2026-09-29，Claude 定案）
狀態：**待實作**
前置閘門：**無**（與 G19 零檔案交集，見 §12）
Flyway：**不新增任何 migration**（下一份需要 migration 的規格自 `V11` 起算）

---

## 1. 背景與目標

### 1.1 現況

G22（[`G22-frontend-test-infra.md`](G22-frontend-test-infra.md)，PR #39 於 2026-09-29 合併）把前端測試從零變成有：`vitest` 已是 devDependency、`npm run test` 已進 `.github/workflows/verify.yml` 的 `verify` job、`src/shared/format.spec.ts` 與 `src/modules/ordering/checkout.spec.ts` 兩支測試已在主線上。

但 G22 §13.3 明文放棄了一半：

> **這個決定放棄了什麼**（要誠實寫下來）：模板的**排版與可見性**仍然沒有防線 —— 例如 `MenuView.vue:610` 那個 `v-if="!auth.customer && payment === 'CASH' && (!discountCode.trim() || pendingCashOrder)"` 決定實收欄位出不出現，寫壞了純函式測試抓不到。

`GAP-ANALYSIS.md` 的「待 PO 驗收」一節把同一件事寫成：

> G22 只測純函式，能證明「顯示與送出的數字對不對」，證明不了「欄位有沒有出現在畫面上」（模板 `v-if` 的可見性）。那一半登記為 **G23**（P2）。**在 G23 完成之前，模板可見性靠互審讀 diff 把關。**

本規格就是把那一半補上。

### 1.2 要擋的缺陷類別（具體的，不是抽象的）

`MenuView.vue` 現行模板上有四個由 `v-if` / `:disabled` 決定的分支，每一個寫壞的後果都是金錢或營收，而且**後端測試與 G22 的純函式測試都看不到**：

| # | 位置 | 條件 | 寫壞的後果 |
| --- | --- | --- | --- |
| A | `MenuView.vue:620` 實收金額欄位 | `!auth.customer && payment === 'CASH' && (!discountCode.trim() \|\| pendingCashOrder)` | 欄位消失 → 店員無處輸入實收，`effectiveTendered()` 在單段式會靜默帶入應收，**抽屜短少不會被發現**；欄位對顧客出現 → 顧客自己填實收 |
| B | `MenuView.vue:609` 兩段式金額區塊 | `pendingCashOrder` | 消失 → 店員看不到後端算出的「應收」，只能憑折扣前小計收錢 |
| C | `MenuView.vue:632` 應找零列 | 同 A | 消失 → 找零靠心算 |
| D | `MenuView.vue:655-663` 結帳按鈕 `:disabled` | `(!cart.length && !pendingCashOrder) \|\| busy \|\| !branchId \|\| customerClosed \|\| !!unavailableCart.length` | 少一個條件 → 打烊分店可下單、售完商品可結帳、重複點擊可重複送單 |

A 與 C 共用同一個條件式，且那個條件式在模板裡**寫了兩次**（`620` 與 `634-638`）—— 一份複製貼上的條件，改一處漏一處是最典型的走樣方式。純函式測試對它完全無感，因為它從來沒有被抽成函式。

### 1.3 為什麼是現在

- P1 目前只有 G19 在實作中（PR #42，S1 已推），規格庫存 1 份，未達上限 2 份
- G23 是 P2 裡**唯一不需要任何產品決策、不需要新依賴以外的技術選型、不新增端點、不新增權限、不動後端、零 migration** 的一項（逐項排除見 §13.1）
- G22 §13.3 已經把做法寫死了：「加 `jsdom` + `@vue/test-utils` 兩個 devDependency，在 `vitest.config.ts` 用 `projects` 開第二組設定，既有的 node 測試一個字都不用改。」本規格是照著那句話施工，不是重新設計
- **它不擋任何人**：與 G19 零檔案交集（§12），Codex 做完 G19 直接接手即可

---

## 2. 範圍

### 2.1 在範圍

1. 引入 `jsdom` 與 `@vue/test-utils` 兩個 devDependency
2. `vitest.config.ts` 改為雙 project（`node` 與 `dom`），**`node` project 的設定逐字不變**
3. 新增可重複使用的掛載測試工具（fetch 替身、pinia、router、jsdom 補丁）
4. 對 `Modal.vue` 寫一支冒煙測試，證明基礎設施真的會掛載 SFC
5. 對 `MenuView.vue` 寫模板可見性測試，覆蓋 §1.2 的 A／B／C／D 四項
6. 對兩段式收現流程寫一支端到端（前端內）測試，並**釘住「送往 `POST /api/orders` 的 body 不含任何金額欄位」**

### 2.2 不在範圍（明確排除）

| 項目 | 為什麼不做 |
| --- | --- |
| **修改 `MenuView.vue`（含加 `data-testid`）** | §13.2。本規格對 `src/**/*.vue` 是**零修改**，驗收 14 會驗 |
| **修改 `backend/`** | 零變更，驗收 15 會驗 |
| **其他 12 個 `.vue` 檔的元件測試** | §13.6。框架一旦在主線上，補測試只是加一個 `.dom.test.ts`，不需要再開規格 |
| **E2E（Playwright / Cypress）** | G22 §13.4 已裁決不做，理由未變（沒有可執行的 backend 與瀏覽器環境） |
| **覆蓋率門檻** | G22 §13.7 已裁決不設，理由未變 |
| **快照測試（snapshot）** | §13.4 |
| **`Intl` 輸出字串的斷言** | G22 §13.8 已裁決不測，理由未變。本規格的斷言一律用**數字或結構**，不用 `money()` 的輸出字面 |
| **改 `verify.yml`** | 不需要。`npm run test` 已經在裡面，`vitest run` 會跑所有 project |
| **任何行為變更** | 本規格是純加法。既有的 `npm run build`、`npm run test` 的結果在 S1 前後必須完全相同（除了多跑一支 dom 測試） |

---

## 3. 涉及模組與邊界

**後端：零變更。** 不涉及任何 Java 模組，不動模組邊界，不新增權限常數。

前端：

| 檔案 | 動作 |
| --- | --- |
| `frontend/package.json` | 加兩個 devDependency、**不動任何 script** |
| `frontend/package-lock.json` | 隨 `npm install` 更新 |
| `frontend/vitest.config.ts` | 改為 `projects` |
| `frontend/src/shared/testing/setup.dom.ts` | 新增（jsdom 補丁） |
| `frontend/src/shared/testing/harness.ts` | 新增（掛載工具、fetch 替身、選取輔助） |
| `frontend/src/shared/testing/fixtures.ts` | 新增（Actor / Branch / Product / Order 夾具） |
| `frontend/src/shared/Modal.dom.test.ts` | 新增（S1 冒煙） |
| `frontend/src/modules/ordering/MenuView.dom.test.ts` | 新增（S2／S3） |

`src/shared/testing/` 放在 `src/` 底下是刻意的（§13.5）。它**不得被任何元件或 `main.ts` import**，驗收 13 會驗。

---

## 4. DB schema 與 migration

**無。本規格不新增任何 Flyway 檔案。**

現況版號：`V1`–`V9` 已在主線，**`V10__branch_hour_overrides.sql` 由 G19 占用**（規格已寫死檔名）。因此下一份需要 migration 的規格自 **`V11`** 起算。G23 不動用任何版號。

---

## 5. 技術設計

### 5.1 `vitest.config.ts` 改為雙 project

```ts
import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  test: {
    projects: [
      {
        test: {
          name: "node",
          environment: "node",
          include: ["src/**/*.spec.ts"],
          globals: false,
        },
      },
      {
        plugins: [vue()],
        test: {
          name: "dom",
          environment: "jsdom",
          include: ["src/**/*.dom.test.ts"],
          setupFiles: ["src/shared/testing/setup.dom.ts"],
          globals: false,
        },
      },
    ],
  },
});
```

三個關鍵點：

1. **`node` project 的三行（`environment` / `include` / `globals`）與現行 `vitest.config.ts` 逐字相同。** 既有兩支測試的跑法一個位元都沒變
2. **dom project 必須自己掛 `plugins: [vue()]`。** 現行 `vitest.config.ts` 沒有任何 plugin（它只測 `.ts`），不掛就無法編譯 `.vue`，錯誤訊息是難懂的語法錯誤
3. **不要覆寫 `exclude`。** 檔名選用 `.dom.test.ts` 而不是 `.dom.spec.ts` 正是為了讓兩組 `include` 天然互斥（§13.3）——`src/**/*.spec.ts` 會吃掉 `*.dom.spec.ts`，`*.dom.test.ts` 不會

> **若安裝到的 vitest 版本不支援 `test.projects`**（`projects` 自 Vitest 3.2 起提供，現行 `package.json` 已是 `vitest: ^3.2.7`，正常不會發生）：改用同等的 `workspace` 設定，並在 PR 描述說明。驗收條件看的是「`npm run test` 一條指令跑完兩組測試且全綠」，不是設定檔長相。

### 5.2 jsdom 補丁（`setup.dom.ts`）

jsdom 有兩個與本專案相關的空缺，**兩個都要在 setup 檔用「存在就不覆寫」的方式補上**，不要假設安裝到的版本有或沒有：

```ts
// src/shared/testing/setup.dom.ts
if (!HTMLDialogElement.prototype.showModal)
  HTMLDialogElement.prototype.showModal = function () { this.open = true; };
if (!HTMLDialogElement.prototype.close)
  HTMLDialogElement.prototype.close = function () { this.open = false; };
if (!globalThis.crypto?.randomUUID) { /* 補一個遞增的假 UUID 產生器 */ }
```

理由：`Modal.vue` 的 `onMounted` 直接呼叫 `dialog.value?.showModal()`，`MenuView.vue:315` 的 `nextIdempotency` 呼叫 `crypto.randomUUID()`。`HTMLDialogElement` 的實作在 jsdom 的不同版本之間有過變動，**把測試綁在「裝到哪個 jsdom 版本」上，症狀會是幾個月後某次 `npm ci` 無故變紅**，那正是 G22 §13.8 要避免的假紅燈。`??=` 形式的補丁在兩種版本下都對。

**不要補 `fetch`。** fetch 一律由 §5.3 的替身逐測試注入 —— 全域補一個會讓「忘了設夾具」的測試打到真的網路而不是立刻失敗。

### 5.3 fetch 替身

`src/shared/api.ts` 的 `api()` 只用到回應物件的 `ok` / `status` / `text()`，`refreshCsrf()` 用 `json()`。替身因此只要提供這四個成員：

```ts
export interface StubbedRequest { path: string; method: string; body: unknown; headers: Headers }

export function stubApi(routes: Record<string, unknown | ((req: StubbedRequest) => unknown)>): {
  requests: StubbedRequest[];
  fetch: ReturnType<typeof vi.fn>;
}
```

- key 是**不含 query string 的路徑**，例如 `"/api/branches"`、`"/api/menu"`、`"/api/branches/B1/hours"`、`"/api/orders"`
- value 是要回的 JSON，或一個依請求決定回應的函式
- 沒有對應 route 的請求→回 404 且 `message` 為「測試未設定此路由：<path>」，**不要靜默回 `{}`**。靜默回空物件會讓「夾具漏設」表現成難懂的渲染結果
- 每一筆請求（含 method、解析後的 body、headers）都推進 `requests`，供 S3 斷言
- `"/api/auth/csrf"` 一律由 `stubApi` 內建回 `{ token: "test-token", headerName: "X-CSRF-TOKEN" }`，各測試不必重複寫

**注意 `api.ts` 的 `csrf` 是模組層變數**，同一支測試檔的多個 `it` 之間會殘留。因此：**不得斷言 csrf 請求的次數**，也不得依賴「每個測試都會重新取 csrf」。

### 5.4 掛載工具

```ts
export async function mountView<T>(component: T, options: {
  actor: Actor | null;          // null = 未登入
  routes?: RouteRecordRaw[];    // 預設一條 catch-all
  fetch: ReturnType<typeof stubApi>["fetch"];
}): Promise<VueWrapper>
```

內部依序做四件事：

1. `setActivePinia(createPinia())`，並把 `actor` 直接寫進 `useAuth().user`、`loaded = true`
   —— **不要**走 `auth.init()`，那會多打兩支 API，和本規格要測的東西無關
2. 建一個 `createRouter({ history: createMemoryHistory(), routes })`，預設 routes 是單一 catch-all `{ path: "/:pathMatch(.*)*", component: { template: "<div />" } }`
   —— `MenuView.vue` 的 `useRouter()` 沒有 router 會直接 undefined 爆炸；沒有 catch-all 則 `router.push("/orders")` 會噴警告
3. 指派 `globalThis.fetch`
4. `mount(component, { global: { plugins: [pinia, router] } })`，然後 **`await flushPromises()` 兩次**
   —— `MenuView.vue` 的 `onMounted(load)` 有兩層 `await`（`Promise.all([branches, config])` 之後才 `Promise.all([loadMenu, loadBranchHours])`），一次 flush 不夠，渲染出來會是 `loading` 狀態

掛載後 `expect(wrapper.text()).not.toContain("載入")`（或等價的 loading 判定）**應由 `mountView` 自己斷言**，讓「夾具沒設好所以停在 loading」在工具裡就炸掉，而不是變成六個測試各自失敗且訊息都看不懂。

### 5.5 選取策略：用使用者看得到的文字，不用 `data-testid`

本規格**不修改 `MenuView.vue`**，所以沒有 `data-testid` 可用。選取一律經由三個輔助函式：

```ts
export function labelByText(wrapper: VueWrapper, text: string): DOMWrapper<HTMLLabelElement> | null;
export function rowByLabel(wrapper: VueWrapper, text: string): DOMWrapper<Element> | null;
export function checkoutButton(wrapper: VueWrapper): DOMWrapper<HTMLButtonElement>;
```

- `labelByText` 掃 `wrapper.findAll("label")`，回傳第一個 `text()` 以 `text` 開頭的；找不到回 `null`
- `rowByLabel` 掃 `wrapper.findAll(".change-row, .cart-total")`，比對其中 `<span>` 的文字
- `checkoutButton` 取 `wrapper.get("button.checkout")`

「不存在」一律用 `expect(labelByText(w, "實收金額")).toBeNull()` 表達，**不要用 `isVisible()`** —— `v-if` 是不渲染，`isVisible()` 是 `display:none`，兩者不同，用錯的那個會讓測試永遠是綠的。

理由與推翻代價見 §13.2。

---

## 6. API

**不新增、不修改任何端點。** 本規格對後端是唯讀的：測試以替身回應既有端點的形狀。

測試會用到的既有端點與其回應形狀（形狀來自 `src/shared/types.ts`，不是本規格發明的）：

| 端點 | 用途 | 夾具回傳 |
| --- | --- | --- |
| `GET /api/auth/csrf` | `api.ts` 的前置 | `{ token, headerName }` |
| `GET /api/branches` | 分店清單 | `Branch[]` |
| `GET /api/payments/config` | 綠界開關 | `{ enabled, environment }` |
| `GET /api/menu?branchId=` | 菜單 | `Product[]` |
| `GET /api/branches/{id}/hours` | 營業時間 | `BranchHoursResponse` |
| `POST /api/orders` | 建立訂單 | `Order` |
| `POST /api/orders/{id}/cash` | 現金收款 | `Order` |

---

## 7. 權限與資料範圍

**不新增端點，因此沒有新的權限判定。**

但本規格**測的正是「前端依身分顯示什麼」**，所以夾具要涵蓋三種 `Actor`：

| 夾具 | `scope` | `permissions` | 對應 |
| --- | --- | --- | --- |
| `customerActor()` | `SELF` | `[]` | 顧客自助點餐 |
| `branchStaffActor()` | `BRANCH` | `["ORDER_CREATE", "ORDER_CASH"]` | 門市 POS |
| `globalActor()` | `GLOBAL` | 含 `MENU_MANAGE` | 總部 |

**必須同時記住並在 `MenuView.dom.test.ts` 的檔頭註解寫下這一句**：

> 這些測試證明的是「畫面有沒有出現」，**不是授權**。真正的授權一律在後端（`AGENTS.md`「授權」）。前端把實收欄位藏起來不等於顧客不能送實收金額 —— 那條防線在 `OrderService`，由後端的越權測試守。

寫下它的理由：元件測試最容易養出的錯覺就是「前端擋住了 = 安全」。

---

## 8. 金額規則

本規格不計算任何金額，但**新增一條把既有紅線釘死的斷言**（S3，驗收 12）：

> `POST /api/orders` 的 request body 必須只有 `branchId` / `fulfillment` / `paymentMethod` / `note` / `discountCode` / `items`，且 `items` 的每一個元素只有 `productId` / `quantity` / `optionIds`。**出現任何金額欄位（`total`、`price`、`unitPrice`、`amount`、`subtotal`、`discountAmount`、`lineTotal`、`optionsPrice`）即為失敗。**

斷言方式用**白名單比對鍵集合**（`expect(Object.keys(body).sort()).toEqual([...])`），不要用黑名單列舉禁字 —— 黑名單漏掉沒想到的欄位名就放行了，白名單不會。

這條是 `AGENTS.md`「金額」與「不得在前端信任任何金額」的自動化版本。目前這條規則在前端**完全沒有測試**，只有互審在看。

---

## 9. 施工階段

三個階段全部是加法。每階段獨立 CI 綠、獨立可合併、有自己的驗收子集。

### S1 — 元件測試基礎設施（規模：小）

**動到的檔案**：`frontend/package.json`、`frontend/package-lock.json`、`frontend/vitest.config.ts`、新增 `src/shared/testing/setup.dom.ts`、`src/shared/testing/harness.ts`、`src/shared/Modal.dom.test.ts`

1. `npm i -D jsdom @vue/test-utils`
2. `vitest.config.ts` 照 §5.1 改為雙 project
3. 寫 `setup.dom.ts`（§5.2）與 `harness.ts`（§5.3、§5.4、§5.5）
4. 寫 `Modal.dom.test.ts`：掛載 `Modal.vue`（`title` prop），斷言
   (a) `<h2>` 文字等於傳入的 title；
   (b) slot 內容有渲染；
   (c) 點 `aria-label="關閉"` 的按鈕會 emit `close`

`Modal.vue` 是刻意挑的：它是最小的真 SFC，沒有 store、沒有 router、沒有 fetch，**而且它會踩到 `showModal()`** —— 基礎設施若有問題，S1 就會紅，不會拖到 S2 才發現。

**S1 驗收子集**：1–8、13、14、15

### S2 — `MenuView` 可見性矩陣（規模：中）

**動到的檔案**：新增 `src/shared/testing/fixtures.ts`、`src/modules/ordering/MenuView.dom.test.ts`

六個案例，全部只靠「掛載後設定表單欄位」達成，不需要送出任何訂單：

| 案例 | 身分 | 操作 | 斷言 |
| --- | --- | --- | --- |
| 1 | `branchStaffActor` | 預設（`payment=CASH`、無優惠碼、購物車有 1 項） | 「實收金額」label 存在；「應找零」列存在；結帳按鈕**未** disabled；按鈕文字含「確認收款」 |
| 2 | `customerActor` | 同上 | 「實收金額」label **不存在**；「應找零」列**不存在**；按鈕文字含「確認點餐」 |
| 3 | `branchStaffActor` | 在優惠碼欄輸入 `"WELCOME"` | 「實收金額」label **不存在**；「應找零」列**不存在**；按鈕文字含「建立訂單並計算應收」 |
| 4 | `branchStaffActor` | 付款方式選 `ECPAY`（夾具 `config.enabled = true`） | 「實收金額」label **不存在**；按鈕文字含「前往付款」 |
| 5 | `customerActor` | 夾具的分店 `openNow = false` | 結帳按鈕 **disabled** |
| 6 | `branchStaffActor` | 購物車有一項，但該商品的 `availability = "SOLD_OUT"` | 結帳按鈕 **disabled**；`.error-state` 存在 |

**購物車怎麼裝進去**：走使用者路徑 —— 點商品卡開 Modal、點「加入點餐單」。不要直接改元件內部狀態（`wrapper.vm.cart`），那會繞過本規格要測的那一層。若某個案例走使用者路徑太繞，**在測試裡留一行註解說明為什麼**，不要默默改用 `vm`。

案例 5 的分店打烊**只斷言按鈕 disabled，不斷言任何打烊訊息文字** —— 理由見 §12。

**S2 驗收子集**：9、10、13、14、15

### S3 — 兩段式收款流程與送出內容（規模：中）

**動到的檔案**：`src/modules/ordering/MenuView.dom.test.ts`（續寫）、`src/shared/testing/fixtures.ts`（加 `Order` 夾具）

一支完整的兩段式流程測試：

1. `branchStaffActor` 掛載，加一項商品進購物車（小計 140）
2. 優惠碼欄輸入 `"WELCOME"`
3. 點結帳按鈕 → 斷言 `POST /api/orders` 被呼叫，且 **body 的鍵集合符合 §8 的白名單**（驗收 12）
4. 夾具回傳 `Order { subtotal: 140, discountAmount: 14, total: 126 }`
5. `await flushPromises()` 後斷言：
   - 「小計」「優惠折抵」「應收」三列都出現，數字分別含 `140`、`14`、`126`
   - 「實收金額」label **重新出現**（`pendingCashOrder` 讓條件轉真）
   - 優惠碼輸入框與付款方式選單都變成 `disabled`
   - 按鈕文字含「確認收款」
6. 在實收欄輸入 `150`，斷言「應找零」列含 `24`
7. 再點結帳 → 斷言 `POST /api/orders/{id}/cash` 的 body 是 `{ tendered: 150 }`（**只有這一個鍵**）

這支測試把 §1.2 的 A／B／C 三項與 §8 的紅線一次蓋住，而且它走的是店員真正的操作順序。

**S3 驗收子集**：11、12、13、14、15

### 階段切分的理由

S1 是「基礎設施能不能動」，S2 是「條件對不對」，S3 是「流程與送出的內容對不對」。三者失敗的原因完全不同，混在一起會讓一次紅燈要查三種可能。S1 單獨合併後，任何人都能開始寫 `.dom.test.ts`，**不必等 S2／S3** —— 這是先切出 S1 的主要收益。

---

## 10. 驗收條件

逐條可勾選。括號內是所屬階段。

- [ ] 1. （S1）`frontend/package.json` 的 `devDependencies` 新增且只新增 `jsdom` 與 `@vue/test-utils`
- [ ] 2. （S1）`frontend/package.json` 的 `scripts` **一個字元都沒改**（`test` 仍是 `vitest run`）
- [ ] 3. （S1）`.github/workflows/verify.yml` **零變更**，job 名稱仍是 `verify`
- [ ] 4. （S1）`vitest.config.ts` 的 `node` project 三項設定（`environment: "node"`、`include: ["src/**/*.spec.ts"]`、`globals: false`）與改動前逐字相同
- [ ] 5. （S1）`npm run test` 一條指令跑完兩組 project 且全綠；輸出可分辨 `node` 與 `dom` 兩組
- [ ] 6. （S1）既有的 `src/shared/format.spec.ts` 與 `src/modules/ordering/checkout.spec.ts` **內容零修改**，且仍在 `node` project 下執行（不被 dom project 撿走）
- [ ] 7. （S1）`Modal.dom.test.ts` 三項斷言（title、slot、close emit）全綠
- [ ] 8. （S1）`setup.dom.ts` 的三個補丁都是「不存在才補」的形式，不無條件覆寫
- [ ] 9. （S2）§9 表格的六個可見性案例全部有對應測試且全綠
- [ ] 10. （S2）「不存在」一律以 `toBeNull()`／`exists() === false` 斷言，**全檔沒有任何 `isVisible()`**
- [ ] 11. （S3）兩段式流程測試的七個步驟全綠
- [ ] 12. （S3）`POST /api/orders` 的 body 鍵集合以**白名單**比對；`POST /api/orders/{id}/cash` 的 body 鍵集合是 `["tendered"]`
- [ ] 13. （全階段）`src/shared/testing/` 底下的檔案**沒有被任何 `.vue`、`main.ts` 或非測試 `.ts` import**（`grep -rn "shared/testing" src --include=*.vue --include=main.ts` 無輸出）
- [ ] 14. （全階段）`src/**/*.vue` **零修改**（`git diff --stat origin/feature/init-project -- 'frontend/src/**/*.vue'` 無輸出）
- [ ] 15. （全階段）`backend/` 零變更；`db/migration/` 沒有新增檔案
- [ ] 16. （全階段）`npm run build`（`vue-tsc --noEmit && vite build`）綠。**新增的 testing 工具也要通過型別檢查** —— `tsconfig.json` 的 `include` 是 `src/**`，它們在檢查範圍內
- [ ] 17. （全階段）`git ls-files -s backend/mvnw scripts/build.sh start-demo.sh` 三個都是 `100755`

---

## 11. 測試要求

本規格的產出**就是**測試，所以「測試要求」在這裡等於「測試自己的品質要求」：

1. **每個 `it` 只斷言一件事的一個面向。** 六個可見性案例不要合併成一個「矩陣」測試 —— 合併之後紅燈只會告訴你「矩陣壞了」
2. **`it` 的描述用繁體中文，寫的是行為不是實作。** 「顧客身分看不到實收金額欄位」，不是「auth.customer 為 true 時 v-if 為 false」
3. **不使用 snapshot。** 理由見 §13.4
4. **不斷言 `money()` 的輸出字面。** 要驗金額就斷言 `toContain("126")`，不要 `toContain("NT$126")`（G22 §13.8）
5. **越權測試不在本規格。** 前端測試證明不了授權（§7），後端的越權測試由各功能規格各自要求，本規格不重複
6. **測試不得依賴執行順序。** 每個 `it` 自己 `mountView`，不共用 wrapper

---

## 12. 與 G19 的並行注意（重要）

G19（PR #42，實作中）動到的前端檔案是 `BranchesView.vue`、`MenuView.vue` 與新增的 `modules/branches/overrides.ts`。

**G23 與 G19 的檔案交集是零**：G23 不修改任何 `.vue`（驗收 14），只新增測試檔與設定。兩者可以任意順序合併，不會有 git 衝突。

但有一個**語意上的**交界要避開：

> **G23 的測試不得斷言任何「打烊訊息」的文字內容。** G19 S3 會改 `MenuView.vue` 打烊訊息的文字來源（把每週時段的訊息換成能反映例外日的來源）。G23 若把現行訊息字串釘進測試，G19 S3 一合併就會無故變紅，而那個紅燈是 G23 自己造成的。

因此 §9 的案例 5 只斷言**結帳按鈕 disabled**（那是 `customerClosed` 的行為，G19 不會改變它的真假語意），不碰訊息文字。

**依「一次一份」通則（`GAP-ANALYSIS.md` 的設計決策），G23 排在 G19 之後開工。** 這不是技術閘門，是實作端一次只做一份的排程規則。

---

## 13. 設計決策

每一項都是 Claude 定案。附理由與推翻它的代價。

### 13.1 G23 升為 P1，作為 G19 之後的下一份 —— **升**

**決定**：從 P2 升到 P1。

**理由**（P2 逐項排除）：

| 編號 | 為什麼不是它 |
| --- | --- |
| G16 顧客自助註冊 | 涉及濫用防護、驗證信、個資，是產品與法遵決策，**不在 Claude 的授權範圍** |
| G05 Session 集中化 | 要引入 Redis，違反 `AGENTS.md`「不得引入新依賴」的預設，且單店營運下不是阻擋 |
| G08 庫存扣減 | 輕量版已由 G13 做掉；完整版要先有進貨與盤點模型，規模遠大於一份規格 |
| G17 常用組合 / G20 品項層折扣 / G21 會員價 | 三者都明文登記「要先有真實資料才知道做什麼」，現在做出來的一定是猜的 |
| G24 店長自設例外日 | **相依 G19 合併**。G19 還在實作中，現在寫等於寫在浮動的地基上 |
| G25 最後點餐時間 | 同上，`G19 §13.5` 登記，要先有例外日的解析器才知道 last order 掛在哪一層 |
| G12 外送、硬體印單 | 依賴外部選型（物流商、印表機協定），技術選型未定前寫規格意義不大 |
| **G23 元件層測試** | **唯一一項：零產品決策、零後端變更、零 migration、零權限、與實作中的 G19 零檔案交集，而且做法已由 G22 §13.3 寫定** |

**推翻它的代價**：把 G23 再押後，就是繼續讓 §1.2 的四個條件式只靠互審讀 diff 把關。那條防線的失效方式是安靜的 —— 沒有人會在 review 裡漏看之後收到通知。G22 §13.1 已經記過一次「押後測試基礎設施」的實際代價（PR #31 卡 draft、G09 連帶停工、Codex 兩次執行零產出）。

### 13.2 不加 `data-testid`，用使用者看得到的文字選取 —— **不改 `.vue`**

**決定**：`src/**/*.vue` 零修改（驗收 14），測試一律用 `<label>`／列標籤的文字與 `button.checkout` 選取。

**理由**：

1. **零行為風險。** 本規格若改了 `MenuView.vue`，就從「純加法的測試規格」變成「動到收銀畫面的規格」，審查成本與風險完全不同等級
2. **與 G19 零衝突。** G19 S3 正在改 `MenuView.vue`（§12）
3. **文字是比 `data-testid` 更誠實的軸。** G22 §13.3 擔心元件測試「對 DOM 結構敏感，改個 class 名就紅」。按文字選取避開的正是這件事 —— class 名是實作細節，「實收金額」這四個字是使用者看到的東西。**它變了，就該有人重新確認測試**

**這個決定放棄了什麼**：文字改寫（例如「實收金額」改成「實收」）會讓測試紅，而那不是缺陷。代價是每次改文案要順手改測試 —— 一行的事，而且那一行強迫改文案的人確認可見性條件沒被一起改壞。

**推翻它的代價**：低，而且是加法。日後真要 `data-testid`，加上去之後把 `labelByText` 換成 `find("[data-testid=...]")` 即可，測試本體不動。但要先接受「改 `.vue` 的規格」這件事本身。

### 13.3 dom 測試檔名用 `.dom.test.ts`，不用 `.dom.spec.ts` —— **`.test.ts`**

**決定**：dom project 的 `include` 是 `src/**/*.dom.test.ts`。

**理由**：node project 現行的 `include` 是 `src/**/*.spec.ts`，**它會匹配到 `foo.dom.spec.ts`**。用 `.dom.spec.ts` 就必須同時覆寫 node project 的 `exclude`，而 `exclude` 一旦自訂就會丟掉 vitest 的預設值（`node_modules`、`dist` 等），那是一個很容易在幾個月後咬人的陷阱。改用 `.test.ts` 後兩組 `include` 天然互斥，**node project 的設定逐字不必動**（驗收 4）。

**代價**：專案裡出現兩種測試檔尾綴，看起來不一致。這是刻意的 —— 不一致的尾綴正好標示了「這支跑在 jsdom 下」，比一致但要靠設定檔才能分辨好。

**推翻它的代價**：低。要統一成 `.spec.ts` 就得同時寫對兩組 `exclude`（記得帶上預設值）。

### 13.4 不用 snapshot —— **不用**

**決定**：不寫 `toMatchSnapshot()`。

**理由**：`MenuView.vue` 是 761 行、渲染出來上百個節點的元件。它的 snapshot 沒有人會逐行讀，而「更新 snapshot」會變成紅燈時的反射動作 —— 那等於把測試變成一個永遠會通過的儀式。本規格要釘的是**四個具名的條件**，那用具名的斷言表達最精確。

**推翻它的代價**：低（隨時可加），但加之前先問「誰會讀這份 snapshot」。

### 13.5 測試工具放在 `src/shared/testing/`，不開 `frontend/tests/` 樹 —— **放 `src/`**

**決定**：沿用 G22 §13.6 的 colocate 精神。

**理由**：(1) `tsconfig.json` 的 `include` 是 `src/**`，放在 `src/` 底下型別檢查自動涵蓋（驗收 16）——放外面要改 tsconfig，那是本規格不想動的東西；(2) 測試檔本來就 colocate，工具跟著在同一棵樹下，import 路徑是相對的、短的。

**風險與防線**：放在 `src/` 底下的東西看起來像產品程式碼，可能被誤 import 進 bundle。防線是驗收 13 的 grep。`vite build` 只打包從 entry 可達的模組，實際上不會進 bundle，但**驗收 13 擋的是「有人開始這樣用」**，不是 bundle 大小。

**推翻它的代價**：低。搬到 `frontend/tests/` 要同步改 tsconfig 的 `include` 與 vitest 的 `setupFiles` 路徑。

### 13.6 只測 `Modal.vue` 與 `MenuView.vue`，其餘 12 個 `.vue` 不動 —— **只做兩個**

**決定**：本規格只為這兩個元件寫測試。

**理由**：與 G22 §13.10 同一個理由，且證據相同 —— `MenuView.vue` 是**唯一**會把請求送去建立訂單與收款的前端檔案，其他元件寫錯的後果是看錯數字，不是收錯錢。`Modal.vue` 入選的理由不是風險，是它當 S1 的冒煙測試最省（§9）。

**推翻它的代價**：低，純加法。框架在主線上之後，為 `OrdersView.vue`、`ReportsView.vue` 補測試就是各加一個 `.dom.test.ts`，不需要再開規格。**要警惕的反方向是「趁有框架順手全部補一遍」** —— 那會讓一個小階段膨脹成切不開的大階段。

### 13.7 引入 `jsdom` 與 `@vue/test-utils`：`AGENTS.md` 禁止事項第 3 條的例外 —— **引入，且只引入這兩個**

`AGENTS.md`「禁止事項」第 3 條預設不引入新相依。這裡由 Claude 明確裁決為引入。

**為什麼非引入不可**：「掛載元件並檢查渲染結果」沒有零相依的做法。手寫 `createApp().mount(document.createElement("div"))` 在 node 環境下沒有 DOM，在 jsdom 下也還是要 jsdom。`@vue/test-utils` 則是 Vue 官方維護的掛載工具，替代方案是自己寫一層 `mount` / `find` / `trigger`（那就是重寫 test-utils）。

**評估過的替代方案**：

| 方案 | 為什麼不選 |
| --- | --- |
| `happy-dom` 取代 `jsdom` | 更快、更輕，確實有吸引力。不選是因為它的 API 覆蓋度較低（`<dialog>`、`Intl` 邊角），而本專案正好用到 `<dialog>`。**快幾百毫秒換一個「某個 API 不支援」的未知風險，在測試基礎設施上不划算** |
| 只用 `@vue/test-utils` + `renderToString` | 伺服器端渲染測得到「有沒有渲染出來」，測不到互動（輸入優惠碼、點按鈕）。S2 案例 3 與整個 S3 都做不了 |
| 繼續只測純函式 | 就是現狀。§1.2 已列出四個純函式測試看不到的缺陷 |

**兩個都是 devDependency，不進生產 bundle，對終端使用者的載入量是零。**

**推翻它的代價**：換 `happy-dom` 只要改 `environment` 一個字串加上補齊 `<dialog>`；拔掉 `@vue/test-utils` 則等於放棄元件測試。前者低，後者不該做。

### 13.8 jsdom 的空缺用「存在才補」的 shim，不鎖版本 —— **shim**

**決定**：見 §5.2。

**理由**：把測試的正確性綁在「裝到哪個 jsdom 版本」上，失效方式是幾個月後某次 `npm ci` 無故變紅，而且看起來像程式壞了。`if (!x) x = ...` 形式的 shim 在「jsdom 有實作」與「沒有實作」兩種情況下都對，**而且 jsdom 日後補上實作時它會自動讓路**。

**推翻它的代價**：低。若日後確認 `<dialog>` 在所有支援的 jsdom 版本都完整，刪掉 shim 即可 —— 但那要先有「所有支援版本」這個清單，目前沒有。

---

## 14. 給 Codex 的施工提醒

1. **dom project 要自己掛 `plugins: [vue()]`。** 漏了會在編譯 `.vue` 時噴看起來像語法錯誤的訊息（§5.1）
2. **不要覆寫 `exclude`。** 檔名選 `.dom.test.ts` 就是為了不必動它（§13.3）
3. **`mountView` 之後要 `await flushPromises()` 兩次。** `MenuView.vue` 的 `onMounted(load)` 有兩層 `await`，一次 flush 會停在 loading 狀態，症狀是所有斷言都找不到元素（§5.4）
4. **「不存在」用 `toBeNull()` / `exists() === false`，不要用 `isVisible()`。** `v-if` 是不渲染，`isVisible()` 驗的是 `display:none` —— 用錯那個，測試會永遠是綠的（驗收 10）
5. **`stubApi` 對未設定的路由要回 404 + 明確訊息，不要回 `{}`。** 靜默回空物件會讓「夾具漏設」變成難懂的渲染結果（§5.3）
6. **不要斷言 csrf 請求的次數。** `api.ts` 的 `csrf` 是模組層變數，同檔案的測試之間會殘留（§5.3）
7. **購物車走使用者路徑裝**（點商品→Modal→加入點餐單），不要直接改 `wrapper.vm`。真的繞不過去就留註解說明（§9 S2）
8. **不要斷言任何打烊訊息的文字。** G19 S3 會改它的來源，釘下去會讓 G19 合併時無故變紅（§12）
9. **不要斷言 `money()` 的輸出字面**，只斷言數字（`toContain("126")`）（§11.4）
10. **不要改任何 `.vue` 檔**，一個字元都不要（驗收 14）。需要選取點時用文字，不要加 `data-testid`（§13.2）
11. **不要改 `verify.yml`，不要改 `package.json` 的 `scripts`。** `npm run test` 已經在 CI 裡，`vitest run` 會自己跑兩組（驗收 2、3）
12. **不要動 `backend/`，不新增任何 Flyway migration**（驗收 15）。下一份需要 migration 的規格自 `V11` 起算
13. **推之前確認執行位元**：`git ls-files -s backend/mvnw scripts/build.sh start-demo.sh` 三個都要 `100755`
14. **每階段做完就推。** S1 單獨合併就有價值（任何人都能開始寫 `.dom.test.ts`），不要整份做完才推（`AGENTS.md`「施工階段與中斷續作」）
15. **規格有錯或做不到就講出來**（設計摘要、PR 描述、`docs/reports/`），寫明你採用了哪個做法與為什麼，**然後繼續做**。不要停下來等回覆
