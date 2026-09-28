# G22 — 前端測試基礎設施與可測純函式抽離

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G22 |
| 優先順序 | P2 → **升為 P1**（理由見 §13.1） |
| 版本 | v1.0（2026-09-28，Claude 定案） |
| 前置相依 | G07（PR #31，已合併）、G09（PR #36，已核准待合併） |
| Flyway | **不新增任何 migration** |
| 後端變更 | **零** |
| 新增第三方相依 | **有** —— `vitest`（devDependency，唯一一個）。這是 `AGENTS.md` 禁止事項第 3 條的例外案例，理由與替代方案見 §13.2 |

---

## 1. 背景與目標

### 1.1 問題

`frontend/` **完全沒有測試框架**。`package.json` 的 `scripts` 只有 `dev` / `build` / `preview`，`devDependencies` 只有 vite、`@vitejs/plugin-vue`、typescript、vue-tsc。CI 的前端步驟是 `npm ci && npm run build`，而 `build` 是 `vue-tsc --noEmit && vite build` —— **只驗型別與可打包，不驗任何行為**。

意思是：前端只要型別對，邏輯寫反了 CI 一樣是綠的。

### 1.2 這個缺口已經咬過一次

G07（訂單折扣與優惠碼）的驗收 21b／22／23 是三條**人工**驗收：

> 員工 POS 現金 + 優惠碼：收款欄顯示「小計／折抵／應收」是否正確；店員未輸入實收時不得自動以購物車小計送出；未帶碼的單段流程不得退化。

為什麼是人工？因為沒有東西可以自動驗。G07 §11.11 已經寫明後端的驗收 21a **擋不住**那個缺陷：

> 21a 只釘住後端以折後 `total` 驗證 `tendered` 與保留 `PENDING_PAYMENT` 的契約，它擋不住「前端自動以小計 140 當實收送出」那個缺陷（請求與帳面一致，21a 全綠）。

後端收到 `tendered: 140`、`total: 140`，一切合法，找零 0。**抽屜短少 14 元，沒有任何一層會叫。** 這是純前端缺陷，只有前端測試擋得住。

這條線的代價已經付過：G07 因為這三條人工驗收沒有執行者，PR #31 三階段全綠卻卡在 draft，硬相依的 G09 連帶開不了工，Codex 有兩次執行產出零行程式碼。v1.5 只好把它們降級成「原始碼佐證 + 互審覆核」，也就是**用人眼代替測試**。G22 要做的就是把那道防線換回機器。

### 1.3 目前靠人眼守著的是什麼

`MenuView.vue` 有 752 行，`checkout()` 一個函式就 100 行，裡面混了四條付款路徑的分支、兩處實收金額驗證、冪等鍵重用的狀態機，以及三個守門條件。舉一個具體的：

```ts
const cashAtPos = !auth.customer && payment.value === "CASH";
const discountedCashAtPos = cashAtPos && discountCode.value.trim().length > 0;
let cash = tendered.value;
if (cashAtPos && !discountedCashAtPos) {
  cash ??= total.value;            // ← 店員沒輸入實收，就拿購物車小計頂上
  ...
}
```

`cash ??= total.value` 在**沒有優惠碼**時是對的（小計就是應收）。在**有優惠碼**時是災難，所以上面用 `discountedCashAtPos` 把有碼的路徑整條導去兩段式流程，永遠不會走到這行。

這個設計是對的。問題是**它的正確性完全建立在「`discountedCashAtPos` 這個布林算得對」上，而沒有任何東西在驗它**。哪天有人把 `discountCode.value.trim().length > 0` 改成 `discountCode.value.length > 0`，或是把兩個條件的順序調換，CI 照樣全綠，錯誤會在收銀台的抽屜裡出現。

模板裡還有一份同類的裸算術（`MenuView.vue:636`）：

```
(tendered ?? (pendingCashOrder?.total ?? total)) - (pendingCashOrder?.total ?? total)
```

那是找零。三層 `??` 寫在模板裡，改不對沒有人會知道。

### 1.4 目標

1. 前端有一套能跑的測試框架，`npm run test` 一行就跑得起來
2. **測試紅燈 = CI 紅燈 = 合不進主線**（否則這道防線等於不存在）
3. 把 `checkout()` 與模板裡的判斷與算術抽成**純函式**，讓它們可測
4. 把 G07 驗收 21b／22／23 三條人工項目換成自動化斷言，從「待 PO 驗收」清單移除

### 1.5 不是目標

- **不做元件掛載測試**（`@vue/test-utils` + jsdom）。理由與日後要做時的接法見 §13.3
- **不做 E2E / 瀏覽器自動化**（Playwright、Cypress）。見 §13.4
- **不做覆蓋率門檻。** 見 §13.7
- **不改任何前端行為。** 本規格從頭到尾是「把既有邏輯搬到可測的位置並釘住它」，抽出來的函式必須與原本的運算逐字等價。任何行為變更都是超出範圍
- **不動後端。** `backend/` 零變更
- **不改 CI 的 job 名稱或 required status check。** 見 §13.5 —— 這條同時是安全考量，也是流程考量

---

## 2. 範圍

### 2.1 在範圍

| 檔案 | 變更 |
| --- | --- |
| `frontend/package.json` | 新增 `vitest` devDependency 與 `test` script |
| `frontend/package-lock.json` | 隨之更新 |
| `frontend/vitest.config.ts` | **新增** |
| `frontend/src/shared/format.ts` | 抽出 `csvBody()` 純函式，`csv()` 改呼叫它 |
| `frontend/src/shared/format.spec.ts` | **新增** |
| `frontend/src/modules/ordering/checkout.ts` | **新增** —— 純函式，不 import vue |
| `frontend/src/modules/ordering/checkout.spec.ts` | **新增** |
| `frontend/src/modules/ordering/MenuView.vue` | 改為呼叫 `checkout.ts` 的純函式，**行為零變更** |
| `.github/workflows/verify.yml` | 在既有 `verify` job 新增一個 `npm run test` step |
| `docs/GAP-ANALYSIS.md` | 狀態與工作順序更新 |

### 2.2 不在範圍

- 其他 13 個 `.vue` 檔的邏輯抽離。本規格只處理 `MenuView.vue`，理由見 §13.10
- `shared/api.ts` 的測試（需要 `fetch` 攔截，屬於 §13.3 的下一階段）
- `ecpay.ts`、`Chart.vue`、`notice.ts`
- 任何後端變更

### 2.3 為什麼排在 G09 之後

G09（PR #36）不動 `frontend/` 一個字（那是它的驗收條件 1），所以嚴格說沒有檔案衝突。排在後面是因為「一次一份」的通則，以及 G09 已經在審查中、合併在即。**G22 可以在 G09 合併後立刻開工，不需要等任何其他東西。**

---

## 3. 涉及模組與邊界

前端 `frontend/src/modules/` 的模組名稱對應後端模組名稱（`AGENTS.md`「模組邊界」）。本規格新增的 `checkout.ts` 放在 `modules/ordering/`，與 `MenuView.vue` 同目錄，**不跨模組**。

`checkout.ts` 的硬規則：

- **不得 import `vue`**（沒有 `ref` / `computed` / `watch`）。它收純值、回純值
- **不得 import `../../shared/api`**。它不發 HTTP
- **不得 import `../identity/store`**。呼叫端把 `auth.customer` 當參數傳進去
- 可以 import `../../shared/types` 的 `type`（僅型別，不含執行期程式碼）

這四條合起來就是「純」的操作型定義，而且**是可機器驗證的** —— 見驗收 9。

後端模組邊界完全不受影響（後端零變更）。

---

## 4. DB schema 與 migration

**本規格不新增任何 migration。** 前端測試基礎設施與資料庫無關。

Flyway 版號現況：`V1`–`V8` 已在主線，`V9__order_discounts.sql` 隨 PR #31 合併。**下一份需要 migration 的規格自 `V10` 起算，G22 不佔用任何版號。**

驗收 11 會驗這件事。

---

## 5. 測試基礎設施的形狀

### 5.1 選 Vitest

```json
"devDependencies": {
  "vitest": "^3.2.0"
}
```

版本以「與 Vite 6 相容的最新穩定版」為準。Codex 安裝後**把實際鎖定的版本寫進 PR 描述**，不要只寫 `^3`。

### 5.2 `frontend/vitest.config.ts`

```ts
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "node",
    include: ["src/**/*.spec.ts"],
    globals: false,
  },
});
```

三個設定各自有理由：

- **`environment: "node"`** —— S1／S2／S3 測的全是純函式，不碰 `document`、`window`、`fetch`。不裝 jsdom 就少一個相依、少一段啟動時間，也少一種「測試環境與瀏覽器行為不一致」的缺陷來源。日後要做元件測試時再加，見 §13.3
- **`include: ["src/**/*.spec.ts"]`** —— 測試與被測檔案同目錄（`format.ts` 旁邊放 `format.spec.ts`），對齊後端「測試放在看得到被測程式碼的地方」的習慣。用 `.spec.ts` 而非 `.test.ts` 是為了與 `test` script 名稱區隔，減少 glob 寫錯時的歧義
- **`globals: false`** —— 測試檔一律明寫 `import { describe, it, expect } from "vitest"`。這樣就**不必**在 `tsconfig.json` 的 `types` 陣列加 `"vitest/globals"`，應用程式的全域型別空間不被測試框架污染，`vue-tsc` 對 `src/` 的檢查維持原狀

**不要建獨立的 `tsconfig.test.json`。** 現有 `tsconfig.json` 的 `include` 是 `["src/**/*.ts", "src/**/*.vue"]`，已經涵蓋 `*.spec.ts`，所以 `npm run build` 的 `vue-tsc --noEmit` 會連測試檔一起型別檢查 —— **這是要的**，測試裡的型別錯誤應該讓 build 紅。

### 5.3 `package.json` 的 script

```json
"test": "vitest run"
```

**一定要帶 `run`。** 不帶的話 `vitest` 預設進 watch 模式，在 CI 上會掛住直到 timeout。這是這類設定最常見的一個坑，寫在這裡就不要再踩。

### 5.4 測試檔不會被打包進產物

Vite 的 build 只收 `index.html` → `main.ts` 可達的模組。`*.spec.ts` 沒有任何生產程式碼 import，所以不會進 bundle。**不需要**在 `vite.config.ts` 加 `exclude`。

驗收 10 會用 `dist/` 的內容驗這件事。

---

## 6. 要抽出來的純函式

這一節是本規格的核心。每個函式都附**目前程式碼的位置**與**必須逐字等價**的運算。

### 6.1 `shared/format.ts` —— `csvBody()`

目前 `csv()`（`format.ts:59-84`）一個函式做兩件事：組字串、觸發下載。組字串的那半是純的，而且**有資安意義** —— 那個 `/^[=+@\-\t\r]/` 前綴單引號的處理是 CSV 注入（公式注入）防護。它現在沒有任何測試。

抽成：

```ts
export function csvBody(rows: (string | number)[][]): string
```

內容就是目前 `const body = ...` 那一整段運算，**一個字元都不要改**（含開頭的 `"﻿"` BOM、`""` 逸出、`\r\n` 換行）。`csv()` 改成：

```ts
export function csv(name: string, rows: (string | number)[][]) {
  const url = URL.createObjectURL(
    new Blob([csvBody(rows)], { type: "text/csv;charset=utf-8" }),
  );
  ...  // 其餘不動
}
```

`csv()` 本身碰 `Blob` / `URL` / `document`，留給 §13.3 的階段，本規格不測它。

### 6.2 `shared/format.ts` —— `minuteTime()` 直接測，不用抽

`minuteTime`（`format.ts:8-12`）已經是純函式，且不依賴 `Intl`。直接寫測試，不要改它。

**注意：`money()`、`number()`、`dateTime()`、`currentMonth()` 不要測輸出字串。** 它們全部走 `Intl`，輸出隨 Node 內建的 ICU 版本變動（例如 `Intl.NumberFormat("zh-TW", {style:"currency", currency:"TWD"})` 在不同 ICU 下可能給 `NT$100` 或 `$100`）。把那種字串釘進測試，Node 一升版 CI 就無故變紅，而且看起來像真的壞了。這是刻意不測的，不是遺漏 —— 見 §13.8。

### 6.3 `modules/ordering/checkout.ts` —— 付款路徑判定

目前散在 `MenuView.vue:281-282` 與 `:319-334` 的四條路徑分支。

```ts
export type CheckoutPath =
  | "CUSTOMER_PENDING"    // 顧客自助：建單後導向 /orders，櫃台付款
  | "ECPAY"               // 線上付款：建單後開綠界
  | "POS_CASH_SINGLE"     // 員工 POS、現金、無優惠碼：建單與收款同一次
  | "POS_CASH_TWO_STAGE"; // 員工 POS、現金、有優惠碼：先建單顯示應收，再收款

export function checkoutPath(input: {
  isCustomer: boolean;
  paymentMethod: string;
  discountCode: string;
}): CheckoutPath;
```

必須逐字等價於目前的：

```ts
const cashAtPos = !auth.customer && payment.value === "CASH";
const discountedCashAtPos = cashAtPos && discountCode.value.trim().length > 0;
```

加上 `:320` 的 `payment.value === "ECPAY"` 優先分支。判定順序：`ECPAY` → `POS_CASH_TWO_STAGE` → `POS_CASH_SINGLE` → `CUSTOMER_PENDING`。

**`.trim()` 不可省。** 店員在優惠碼欄位打了一個空白就送出，`" ".length > 0` 是 `true` 但 `" ".trim().length > 0` 是 `false`；後端拿到全空白的碼視同沒帶碼、不折扣，前端若判成「有碼」就會走兩段式、顯示未折扣的應收 —— 帳是對的但流程多一步。反過來把 `.trim()` 拿掉更糟：有碼卻判成沒碼，就會掉進 `cash ??= total` 那條路，直接回到 §1.2 的抽屜短少。**驗收 4 要同時釘住這兩個方向。**

### 6.4 `modules/ordering/checkout.ts` —— 實收金額驗證

目前**有兩份**，`MenuView.vue:243-250`（兩段式的第二段，比對 `order.total`）與 `:284-288`（單段式，比對 `total.value`）。條件相同，資料來源不同。合併成一份：

```ts
export function validateTendered(input: {
  tendered: number | undefined;
  amountDue: number;
}): { ok: true; tendered: number } | { ok: false; message: string };
```

規則（與目前逐字等價）：

- `tendered === undefined` → 失敗
- `!Number.isInteger(tendered)` → 失敗
- `tendered < amountDue` → 失敗
- `tendered > 1000000` → 失敗
- 其餘成功

失敗訊息一律 `"請輸入足夠的實收金額"`（`AGENTS.md`：終端使用者看到的訊息用繁體中文）。

**`amountDue` 是參數，由呼叫端決定要傳 `pendingCashOrder.total` 還是 `total`。** 這個函式本身不知道也不該知道折扣的存在 —— 把「該用哪個數字」的決定留在呼叫端並用 §6.5 釘住它，比在這裡多塞一個 `discountCode` 參數乾淨。

### 6.5 `modules/ordering/checkout.ts` —— 應收金額的來源

這一支是 §1.2 那個缺陷的正面防線：

```ts
export function amountDue(input: {
  path: CheckoutPath;
  cartTotal: number;
  pendingOrderTotal: number | null;
}): number;
```

規則：

- `pendingOrderTotal !== null` → 回 `pendingOrderTotal`（後端算的折後應收）
- 否則 `path === "POS_CASH_SINGLE"` → 回 `cartTotal`（無優惠碼，小計即應收）
- 否則 → **丟 `Error`**。`POS_CASH_TWO_STAGE` 在還沒有 `pendingOrderTotal` 之前**沒有**「應收」可言，拿購物車小計頂上就是 §1.2 的缺陷。讓它爆炸，不要讓它回一個看起來合理的數字

> 這是本規格唯一一處「新增執行期行為」。它不改變任何既有的正確路徑（`POS_CASH_TWO_STAGE` 的第一段本來就不驗實收，根本不會呼叫到 `amountDue`），只是把「若有人日後改壞了分支」從靜默短少變成立即失敗。驗收 5 會用測試釘住這個 throw。

### 6.6 `modules/ordering/checkout.ts` —— 找零

目前是模板裡的裸算術（`MenuView.vue:636`）。

```ts
export function changeAmount(input: {
  tendered: number | undefined;
  amountDue: number;
}): number;
```

等價於 `(tendered ?? amountDue) - amountDue`，也就是未輸入實收時顯示 0。模板改成呼叫它。

### 6.7 `modules/ordering/checkout.ts` —— 下單前的守門條件

目前 `MenuView.vue:268-280` 四個連續的 early return。

```ts
export type CheckoutBlock =
  | { blocked: false }
  | { blocked: true; message: string };

export function checkoutBlock(input: {
  cartLineCount: number;
  branchId: string;
  customerClosed: boolean;
  unavailableCount: number;
}): CheckoutBlock;
```

**順序必須與目前一致**（空車 → 無分店 → 未營業 → 有售罄品項），因為順序決定使用者看到哪一則訊息。訊息逐字沿用：

| 條件 | 訊息 |
| --- | --- |
| `cartLineCount === 0` | （無訊息，靜默 return） |
| `!branchId` | `"請先選擇分店"` |
| `customerClosed` | `"分店目前未營業，請選擇其他分店或於營業時間再下單"` |
| `unavailableCount > 0` | `"點餐單中有商品已售完或本店未供應，請先移除"` |

空車那條目前是 `if (!cart.value.length) return;`，沒有 `notify`。**保留這個行為** —— 回 `{ blocked: true, message: "" }`，呼叫端看到空字串就不 notify。不要順手「補」一則訊息上去，那是行為變更。

### 6.8 `modules/ordering/checkout.ts` —— 冪等鍵重用

目前 `MenuView.vue:289-307` 的 `retryBody` / `retryKey` 兩個模組層級的 `let`。這是「同一張訂單重試不會重複建單」的契約，`api.ts` 的錯誤訊息還明寫了「訂單不會因重試而重複建立」。它現在沒有測試。

```ts
export function nextIdempotency(
  previous: { body: string; key: string },
  serializedBody: string,
  newKey: () => string,
): { body: string; key: string };
```

規則：`serializedBody === previous.body` → 回 `previous`（沿用舊 key）；否則回 `{ body: serializedBody, key: newKey() }`。

`newKey` 是參數而不是直接呼叫 `crypto.randomUUID()`，測試才能傳一個計數器進去、斷言它**被呼叫幾次**。這比斷言回傳值長得像 UUID 有意義得多 —— 要驗的是「內容沒變就不要換 key」，不是 UUID 的格式。

---

## 7. CI 整合

`.github/workflows/verify.yml` 目前的前端步驟：

```yaml
      - run: npm ci --no-audit --no-fund && npm run build
        working-directory: frontend
```

改成兩步：

```yaml
      - run: npm ci --no-audit --no-fund
        working-directory: frontend
      - run: npm run test
        working-directory: frontend
      - run: npm run build
        working-directory: frontend
```

三個決定：

1. **加在既有的 `verify` job 裡，不開新 job。** 分支保護的 required status check 名稱是 `verify`；開新 job 就要改 repo 設定才會被強制，而 repo 設定是 PO 的決定（`CLAUDE.md`），不是 Codex 或 Claude 能動的。塞進同一個 job，防線**當天生效、零設定變更**
2. **`test` 排在 `build` 之前。** 測試幾秒就跑完，`vue-tsc --noEmit && vite build` 慢得多。邏輯壞了就不必等打包
3. **測試紅 = CI 紅 = 合不進去。** 不做 `continue-on-error`、不做「僅警告」模式。一道擋不住東西的閘門，維護成本照付、效果是零

---

## 8. 權限與資料範圍

**不適用。** 本規格不新增任何 API 端點，前端測試不經過授權層。

但有一條相關的紀錄要留下：`AGENTS.md` 寫明「**前端導航只是體驗，真正的授權一律在後端**」。G22 讓前端有了測試能力之後，**不要**因此開始把權限判斷的責任往前端搬，也不要寫「前端權限測試」來取代後端的越權測試。前端測試能證明的只有「畫面與送出的數字對不對」，證明不了「越權會不會被擋」。

---

## 9. 金額規則

`AGENTS.md`：新台幣整數元、後端一律依有效菜單重算、**完全忽略前端送來的任何金額欄位**。

G22 不改變這條，而且**加強**它。抽出來的函式裡：

- `validateTendered` 用 `Number.isInteger` 守整數元，上限 `1000000` 逐字沿用
- `amountDue` 在有 `pendingOrderTotal` 時**一律優先採用它**（後端算的折後金額），只有無優惠碼的單段路徑才用前端小計 —— 而那個情境下前後端的數字必然相同（沒有折扣可算）
- `amountDue` 對 `POS_CASH_TWO_STAGE` 且無後端金額時直接丟錯，就是在拒絕「用前端的數字冒充應收」

**建立訂單的 request body 裡不得出現任何金額欄位。** 這是 G07 §6.1 的紅線，目前 `MenuView.vue:295-307` 守住了（只送 `productId` / `quantity` / `optionIds`）。G22 不碰那段，但驗收 8 會加一條測試把它釘住 —— 有了測試框架之後這條紅線終於可以是機器在守。

---

## 10. 施工階段

三個階段。每階段獨立 CI 綠、獨立可合併、有自己的驗收子集。**全部是加法**：先加新檔與新測試，再讓 `MenuView.vue` 改呼叫，沒有任何一步需要先拆掉既有的東西。

### S1 — 裝框架、進 CI、第一批測試

**規模**：`package.json`、`package-lock.json`、`vitest.config.ts`（新）、`format.ts`（抽 `csvBody`）、`format.spec.ts`（新）、`verify.yml`。六個檔。

**做什麼**

1. `npm i -D vitest`，加 `"test": "vitest run"`
2. 寫 `vitest.config.ts`（§5.2 原樣）
3. 從 `csv()` 抽出 `csvBody()`（§6.1），`csv()` 改呼叫
4. 寫 `format.spec.ts`：`csvBody` 的逸出與注入防護、`minuteTime` 的邊界
5. `verify.yml` 拆成三步（§7）

**驗收子集**：1、2、3、10、11、12

**為什麼獨立可合併**：`csvBody` 是純抽取，`csv()` 的對外行為不變；其餘都是新增。合進主線不影響任何既有行為。

> 這一階段就算後面兩階段永遠沒做，也已經是永久的進度：框架在、CI 在擋、資安相關的 CSV 注入防護有測試了。

### S2 — 抽出 `checkout.ts` 的決策純函式

**規模**：`checkout.ts`（新）、`checkout.spec.ts`（新）、`MenuView.vue`（改呼叫）。三個檔。

**做什麼**

1. 建 `modules/ordering/checkout.ts`，實作 §6.3–§6.7 五支函式
2. 寫 `checkout.spec.ts` 覆蓋它們
3. `MenuView.vue` 的 `checkout()` 改成呼叫這些函式；模板 `:636` 的找零改呼叫 `changeAmount`

**行為必須零變更。** 改完之後 `checkout()` 會短很多，但每一條路徑走到的結果要和改之前完全一樣。

**驗收子集**：4、5、6、7、9

**為什麼獨立可合併**：`checkout.ts` 是新檔；`MenuView.vue` 的改動是等價替換。

### S3 — 冪等鍵與 G07 三條人工驗收的自動化

**規模**：`checkout.ts`（加 `nextIdempotency`）、`checkout.spec.ts`（加測試）、`MenuView.vue`（改呼叫）、`docs/GAP-ANALYSIS.md`。四個檔。

**做什麼**

1. 實作 §6.8 的 `nextIdempotency`，`MenuView.vue` 的 `retryBody` / `retryKey` 改用它
2. 補上對應 G07 驗收 21b／22／23 的三條斷言（§12.3）
3. 從 `GAP-ANALYSIS.md` 的「待 PO 驗收」表移除 G07 那一列，改記「已由 G22 驗收 13 自動化」

**驗收子集**：8、13、14

### 10.1 為什麼切得開

三個階段動的是不相交的檔案集合（S1 碰 `shared/` 與設定，S2 碰 `ordering/`，S3 只加函式），而且每一階段的新增都不被後面的階段依賴。S1 合併後 S2 從主線重新拉分支也不會衝突。

S2 是三者中最大的一個（一個新檔 + `MenuView.vue` 的等價替換），但它不含任何設定或相依變更，也不需要跑安裝，**單次執行做得完**。真的做不完時，`checkout.ts` 可以只先實作 §6.3 與 §6.4 兩支加測試、`MenuView.vue` 暫不改呼叫（新檔沒人用一樣編譯得過、測試一樣綠），下一次執行再接。

### 10.2 PR 描述請維護這張表

```markdown
## 施工進度（G22）
- [ ] S1 裝框架、進 CI、format 純函式測試
- [ ] S2 抽出 checkout.ts 決策純函式
- [ ] S3 冪等鍵與 G07 21b/22/23 自動化
```

---

## 11. 驗收條件

逐條可勾選。每一條都要有對應的測試或可執行的檢查。

**S1**

1. [ ] `npm run test` 在 `frontend/` 跑得起來並**自己結束**（不進 watch 模式掛住）；`package.json` 的 script 是 `vitest run`
2. [ ] `vitest` 是本規格新增的**唯一**第三方相依；`dependencies`（非 dev）零變更；PR 描述寫出實際鎖定的版本號
3. [ ] `csvBody()` 的測試涵蓋：BOM `﻿` 開頭、`"` 逸出成 `""`、`\r\n` 列分隔、以及**五種公式注入前綴（`=`、`+`、`@`、`-`、Tab）都會被加上單引號前綴**；數字型別不受注入前綴影響
10. [ ] `npm run build` 產出的 `dist/` 中**不含任何 `.spec.ts` 的內容**（測試檔沒有被打包）
11. [ ] `backend/` 零變更；`db/migration/` 零新增檔案；`git diff --name-only` 不含任何 `backend/` 路徑
12. [ ] `.github/workflows/verify.yml` 的 job 名稱仍是 **`verify`**（required status check 不變，不需要改 repo 設定）；`npm run test` 排在 `npm run build` 之前；沒有 `continue-on-error`

**S2**

4. [ ] `checkoutPath()` 的測試涵蓋四條路徑，且**同時釘住 `.trim()` 的兩個方向**：優惠碼為 `"  "`（純空白）時判為 `POS_CASH_SINGLE`；優惠碼為 `" X10 "`（有內容帶空白）時判為 `POS_CASH_TWO_STAGE`
5. [ ] `amountDue()` 的測試涵蓋：有 `pendingOrderTotal` 時一律回它（即使 `cartTotal` 不同）；`POS_CASH_SINGLE` 且無 `pendingOrderTotal` 時回 `cartTotal`；**`POS_CASH_TWO_STAGE` 且無 `pendingOrderTotal` 時丟 `Error`**
6. [ ] `validateTendered()` 的測試涵蓋：`undefined`、非整數（如 `100.5`）、小於應收、等於應收、大於應收、超過 `1000000`；失敗訊息為 `"請輸入足夠的實收金額"`
7. [ ] `checkoutBlock()` 的測試涵蓋四個條件**與它們的優先順序**（同時成立時回傳順序在前的那則訊息）；空車回 `{ blocked: true, message: "" }`；四則訊息逐字比對
9. [ ] `checkout.ts` **不 import `vue`、不 import `shared/api`、不 import `identity/store`**。寫一條測試用 `import` 的靜態檢查或讀檔斷言釘住這件事（做法見 §12.4）
   - [ ] `MenuView.vue` 改呼叫後，`npm run build` 仍綠；`checkout()` 的四條路徑行為與改之前一致

**S3**

8. [ ] 建立訂單的 request body 組裝**不含任何金額欄位**（只有 `branchId` / `fulfillment` / `paymentMethod` / `note` / `discountCode` / `items`，且 `items` 只有 `productId` / `quantity` / `optionIds`）—— G07 §6.1 紅線，由測試釘住
13. [ ] **G07 驗收 21b／22／23 的自動化替代**（§12.3）三條斷言全部通過
14. [ ] `docs/GAP-ANALYSIS.md` 的「待 PO 驗收」表不再有 G07 那一列；G22 狀態與工作順序已更新

---

## 12. 測試要求

### 12.1 一律用 `import { describe, it, expect } from "vitest"`

`globals: false`（§5.2），沒有全域 `describe`。忘了 import 會是型別錯誤，`vue-tsc` 在 build 階段就會抓到。

### 12.2 測試名稱用繁體中文或英文皆可，但要描述行為

`it("優惠碼只有空白時視為未帶碼", ...)` 勝過 `it("trim", ...)`。這是給下一個讀的人看的。

### 12.3 G07 驗收 21b／22／23 的自動化替代（驗收 13）

用 G07 §6.6 那組實際數字（小計 140、折抵 14、應收 126）：

| 原驗收 | 自動化斷言 |
| --- | --- |
| **21b** 收款欄顯示「小計／折抵／應收」正確 | 給 `pendingOrderTotal = 126`、`cartTotal = 140`，`amountDue()` 回 `126`（不是 140）；`changeAmount({ tendered: 130, amountDue: 126 })` 回 `4` |
| **22** 未輸入實收不得自動以購物車小計送出 | `checkoutPath({ isCustomer:false, paymentMethod:"CASH", discountCode:"X10" })` 回 `POS_CASH_TWO_STAGE`；且 `amountDue({ path:"POS_CASH_TWO_STAGE", cartTotal:140, pendingOrderTotal:null })` **丟 `Error`** —— 小計 140 沒有任何路徑能變成實收 |
| **23** 未帶碼的單段流程不得退化 | `checkoutPath({ isCustomer:false, paymentMethod:"CASH", discountCode:"" })` 回 `POS_CASH_SINGLE`；`amountDue({ path:"POS_CASH_SINGLE", cartTotal:140, pendingOrderTotal:null })` 回 `140`；`validateTendered({ tendered: undefined, amountDue: 140 })` 失敗（單段式允許 `cash ??= total` 的那一步由呼叫端做，但驗證函式本身不接受 `undefined`） |

**這三條取代人工驗收的前提是：`MenuView.vue` 確實改成呼叫這些函式**（S2 做的事）。函式綠但沒人用等於沒驗，所以驗收 9 的第二個子項與驗收 13 是綁在一起的。

### 12.4 驗收 9 的「純度」檢查怎麼寫

最省事而且不會誤判的做法：在 `checkout.spec.ts` 裡讀原始碼檔案，斷言它不含那三個 import。

```ts
import { readFileSync } from "node:fs";
it("checkout.ts 維持純函式，不依賴 vue、api 或 store", () => {
  const source = readFileSync(
    new URL("./checkout.ts", import.meta.url),
    "utf8",
  );
  expect(source).not.toMatch(/from ["']vue["']/);
  expect(source).not.toMatch(/shared\/api/);
  expect(source).not.toMatch(/identity\/store/);
});
```

`environment: "node"` 下 `node:fs` 直接可用，不需要額外設定。這是刻意選 `node` 環境的附帶好處之一。

> 這條測試的用途不是「證明現在是純的」（讀 diff 就知道），是**擋住日後有人為了省事在裡面 import 一個 `ref`**。純度一旦破了，這一整份規格建立的可測性就漏光了。

### 12.5 不要為了讓測試通過而放寬測試

`AGENTS.md` 禁止事項第 7 條。抽出來的函式如果和原本的行為對不起來，**要改的是抽出來的函式**，不是把斷言改成符合新行為 —— 那就變成「行為變更」，超出本規格範圍。

---

## 13. 設計決策

每一項都是 Claude 定案。附理由與推翻它的代價。

### 13.1 G22 升為 P1，排在 G09 之後 —— **升**

**決定**：從 P2 升到 P1，作為 G09 之後的下一份規格。

**理由**：G07 §11.11 已經把「前端顯示／流程缺陷 → 抽屜短少」這一整個缺陷類別的防線押在 G22 上。在它完成之前，這類缺陷只靠原始碼佐證與互審把關 —— 也就是靠人眼。而且這個代價已經具體發生過一次（PR #31 卡 draft、G09 連帶停工、Codex 兩次執行零產出）。缺口清單上其他 P2 項目（G16 顧客自助註冊、G05 Session 集中化、G08 庫存扣減）都是「新功能」，沒有一個在**擋住既有功能的驗收流程**。

**推翻它的代價**：把 G22 押後，就等於接受「每一份動到 `MenuView.vue` 的規格都要再開一次人工驗收，而且沒有執行者」。G07 已經示範過那條路的終點是停工。

### 13.2 引入 `vitest`：`AGENTS.md` 禁止事項第 3 條的例外 —— **引入，且只引入一個**

`AGENTS.md`「禁止事項」第 3 條：**不得引入新框架或新依賴。真的需要時寫明理由與替代方案，由 Claude 在審查時裁決 —— 預設是不引入。** 這裡由 Claude 明確裁決為引入，理由與替代方案如下。

**為什麼非引入不可**：前端目前沒有任何測試執行器。「不引入相依」在這裡的等價說法是「前端永遠不會有測試」，而那正是 G22 要修的缺口。沒有替代路徑 —— TypeScript 的型別檢查（已經有了）證明不了執行期行為。

**評估過的替代方案**：

| 方案 | 為什麼不選 |
| --- | --- |
| **Jest** | 要另外裝 `ts-jest` 或 babel transform、另一份 transform 設定、與 Vite 的模組解析各走各的。相依數量與設定面積都比 Vitest 大，而收益相同 |
| **Node 內建 `node:test`** | 零相依，很有吸引力。但它不處理 TypeScript（要先編譯或掛 loader）、不解析 `.vue`、不吃 `vite.config.ts` 的 alias。省下一個 devDependency 換來一整套自製的膠水，那才是真的技術債 |
| **只寫後端測試** | 就是現狀。§1.2 已經說明後端測得再完整也擋不住前端把小計當實收送出 |

**為什麼是 Vitest**：它與 Vite 同一套模組解析與 transform，TypeScript 開箱即用，設定檔十行。**而且它是 devDependency —— 不進生產 bundle，對終端使用者的載入量是零。**

**推翻它的代價**：換成 Jest 要重寫設定與所有 import；測試本體（`describe` / `it` / `expect`）大致可移植，所以代價集中在設定，不算高。真正不可逆的是「決定要有前端測試」這件事，而那一步不該推翻。

### 13.3 S1 不裝 jsdom、不裝 `@vue/test-utils` —— **只測純函式**

**決定**：本規格三個階段全部用 `environment: "node"`，不做元件掛載測試。

**理由**：本規格要擋的缺陷（哪個數字進 `tendered`、走哪條付款路徑、找零算多少）抽成純函式之後，**全部是算術與分支**，掛載一個 752 行的 SFC 才能驗它們是殺雞用牛刀。元件測試還有兩個現實成本：對 DOM 結構敏感（改個 class 名就紅）、每次要啟動 jsdom（慢上一個數量級）。先用最小的東西把最大的風險蓋住。

**這個決定放棄了什麼**（要誠實寫下來）：模板的**排版與可見性**仍然沒有防線 —— 例如 `MenuView.vue:610` 那個 `v-if="!auth.customer && payment === 'CASH' && (!discountCode.trim() || pendingCashOrder)"` 決定實收欄位出不出現，寫壞了純函式測試抓不到。本規格的做法是把**數字**的來源（§6.5、§6.6）釘死，讓「顯示的數字是錯的」不可能發生；「欄位根本沒出現」則仍留給互審與 PO 的合併後驗收。

**推翻它的代價**：**很低，而且是純加法。** 日後要做元件測試，加 `jsdom` + `@vue/test-utils` 兩個 devDependency，在 `vitest.config.ts` 用 `projects` 開第二組設定（`environment: "jsdom"`、`include: ["src/**/*.dom.spec.ts"]`），既有的 node 測試一個字都不用改。**這正是先選 `node` 的理由 —— 它不擋路。** 這件事登記為 **G23（前端元件層測試）**，排在 P2。

### 13.4 不做 E2E —— **不做**

**決定**：不引入 Playwright / Cypress。

**理由**：E2E 需要跑得起來的 backend + DB + 打包好的前端。`AGENTS.md` 與 G07 §11.11 已經記錄：Codex 的環境沒有瀏覽器、沒有可用的 backend JAR、`./mvnw verify` 因 Maven Central DNS 解析失敗跑不起來。在那個環境裡寫 E2E 等於寫一組永遠跑不了的測試，然後在 CI 上多燒好幾分鐘。

**推翻它的代價**：要先解決執行環境（能跑 backend、能開瀏覽器），那是基礎設施問題，不是測試框架問題。等環境具備了再開一份規格，不要在這裡預支。

### 13.5 `npm run test` 加進既有 `verify` job，不開新 job —— **同一個 job**

**決定**：在 `.github/workflows/verify.yml` 既有的 `verify` job 裡加一個 step。

**理由**：分支保護的 required status check 名稱是 `verify`（`AGENTS.md`「主線保護」）。開一個新 job 叫 `frontend-test`，它會跑、會顯示，但**不會擋合併**，除非有人去改 repo 設定把它加進 required checks —— 而 repo 設定是 PO 的決定（`CLAUDE.md`「仍須 PO 決定」），Claude 與 Codex 都不能動。加在同一個 job 裡，紅了整個 `verify` 就紅，**防線當天生效、零設定變更、不需要任何人核准**。

**代價**：前端測試與後端 verify 綁在同一個 job，不能並行，PR 的 CI 總時長會多幾秒（vitest 跑純函式是秒級）。以及 CI 介面上看不到「前端測試」這個獨立的綠勾。兩者都遠小於「防線存在但擋不住東西」。

**推翻它的代價**：要拆成獨立 job 並真的擋得住，必須請 PO 改 required status checks。那不是不能做，是要走 PO 那一關 —— 等測試多到值得並行時再說。

### 13.6 測試檔與被測檔同目錄，用 `.spec.ts` —— **colocate**

**決定**：`format.ts` 旁邊放 `format.spec.ts`，不開 `frontend/tests/` 樹。

**理由**：(1) 現有 `tsconfig.json` 的 `include` 是 `src/**/*`，colocate 直接被涵蓋，不必改 tsconfig；(2) 改一個函式時測試就在隔壁，比較不會忘記更新；(3) 本專案前端按 `modules/<模組名>` 分目錄，把測試抽到另一棵樹會讓模組不再是自足的單位。

**推翻它的代價**：低。搬檔案 + 改 `include` glob。

### 13.7 不設覆蓋率門檻 —— **不設**

**決定**：不裝 `@vitest/coverage-*`，不設 threshold。

**理由**：覆蓋率門檻在專案只有個位數測試檔時，唯一的效果是逼人寫「呼叫一次、不斷言」的充數測試來過線。本規格的品質閘門是**逐條列舉的驗收條件**（§11 的 14 條），那比一個百分比精確得多 —— 它指名了哪些行為必須有測試。

**推翻它的代價**：低，隨時可加。但加之前要先有足夠多的真實測試，否則門檻只會製造假測試。

### 13.8 `Intl` 相關的格式化函式刻意不測 —— **不測**

**決定**：`money()`、`number()`、`dateTime()`、`currentMonth()` 不寫輸出字串的斷言。

**理由**：它們的輸出由 Node 內建的 ICU 資料決定，跨 Node 版本會變（貨幣符號是 `NT$` 還是 `$`、有沒有不斷行空格）。把那種字串釘進測試，下次 CI 的 Node 從 22 升到 24 就會無故變紅，而且看起來像是程式壞了 —— 那種「假紅燈」會很快讓人開始無視測試結果，比沒有測試更糟。

**這不是說它們不重要**，是說**測試不是守它們的正確工具**。它們的正確性由瀏覽器實際顯示決定，屬於 PO 的合併後驗收。

**推翻它的代價**：若真要測，正確做法是斷言**結構**而非字面（例如「包含 `140` 這個數字」、「不含小數點」），而不是比對整串。日後有人想加，照這個方向加。

### 13.9 `amountDue()` 在不該被呼叫時丟錯，而不是回 0 或回小計 —— **丟錯**

**決定**：見 §6.5。

**理由**：這是本規格唯一新增的執行期行為，值得單獨說明。回 `0` 會讓 `validateTendered` 對任何實收都放行；回 `cartTotal` 就是把 §1.2 那個缺陷重新請回來。**在「靜默給出一個看起來合理的金額」與「立刻爆炸」之間，收銀台的場景毫無疑問選後者** —— 前者的症狀是月底盤點對不上，事後查不出是哪一筆；後者的症狀是當下就知道。

**為什麼不影響既有行為**：`POS_CASH_TWO_STAGE` 的第一段（建單）本來就不驗實收，走不到 `amountDue`；第二段一定有 `pendingCashOrder`，`pendingOrderTotal` 必非 null。這個 throw 只有在**有人日後改壞了分支**時才會觸發，那正是它存在的意義。

**推翻它的代價**：若日後真有「兩段式但還沒建單就要顯示預估應收」的需求，要另外開一支 `estimatedDue()` 並明確標示它是預估值、不得用於驗證實收 —— 而不是把這裡的 throw 拿掉。

### 13.10 只抽 `MenuView.vue`，其他 13 個 `.vue` 檔不動 —— **只做一個**

**決定**：本規格只把 `MenuView.vue` 與 `shared/format.ts` 的邏輯抽成純函式，`OrdersView.vue`、`ReportsView.vue`、`DiscountsView.vue` 等其餘 12 個元件一律不動。

**理由**：(1) `MenuView.vue` 是**唯一**會把金額寫進送出請求的前端檔案，其他元件是讀取與顯示，寫錯的後果是看錯數字，不是收錯錢；(2) 一次抽 14 個檔會變成一個又大又不可分割的階段，正好違反 `AGENTS.md`「施工階段與中斷續作」要求的「每階段以一次執行做得完為準」；(3) 框架一旦在主線上，任何人要為別的元件補測試都只是加一個 `.spec.ts`，不需要再開規格。

**推翻它的代價**：低，而且是純加法 —— 就是再挑一個元件重複 S2 的手法。真正該警惕的反方向是「趁有框架順手全部重構一遍」：那會讓一個純加法的規格變成大範圍的行為風險，而本規格 §1.5 已明文禁止任何行為變更。

---

## 14. 給 Codex 的施工提醒

1. **`vitest run`，不要漏 `run`。** 漏了在 CI 上會掛到 timeout（§5.3）
2. **`csvBody()` 的字串運算一個字元都不要改**，含 `﻿` BOM 與 `\r\n`。那是抽取，不是重寫（§6.1）
3. **`.trim()` 不可省**，兩個方向都會出事（§6.3）
4. **`checkoutBlock()` 的四個條件順序不可調**，順序決定使用者看到哪一則訊息（§6.7）
5. **空車那條保留「不 notify」的既有行為**，不要順手補訊息（§6.7）
6. **`nextIdempotency` 的 `newKey` 要當參數傳**，不要在函式裡直接呼叫 `crypto.randomUUID()`（§6.8）
7. **`verify.yml` 的 job 名稱維持 `verify`。** 改了名字，分支保護的 required check 就找不到它，所有 PR 會卡住等一個永遠不會出現的檢查（§13.5）
8. **S2 改完 `MenuView.vue` 後要逐條走過四條付款路徑**，確認行為與改之前一致。這是等價替換，不是重構機會
9. **不要順手改其他 `.vue` 檔。** 本規格只碰 `MenuView.vue` 與 `format.ts`（§2.2）
10. **不要動 `backend/`。** 零變更是驗收 11
11. **不新增任何 Flyway migration。** 下一份需要 migration 的規格自 `V10` 起算（§4）
12. **推之前確認執行位元**：`git ls-files -s backend/mvnw scripts/build.sh start-demo.sh` 三個都要 `100755`
13. **規格有錯或做不到就講出來**（設計摘要、PR 描述、`docs/reports/`），寫明你採用了哪個做法與為什麼，**然後繼續做**。不要停下來等回覆（`AGENTS.md`「設計決策的歸屬」）
14. **每個階段完成就推一次**，不要整份做完才推（`AGENTS.md`「施工階段與中斷續作」）

---

## 15. 修訂紀錄

| 版本 | 日期 | 變更 |
| --- | --- | --- |
| v1.0 | 2026-09-28 | 初版。Claude 定案。G07 §11.10／§11.11 登記的缺口，由 §11.11 升排為 G09 之後的下一份規格 |
