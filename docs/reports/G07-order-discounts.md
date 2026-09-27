# G07 訂單折扣與優惠碼實作紀錄

- 來源規格：`docs/specs/G07-order-discounts.md` v1.5
- 主線來源：`eb38891029b0d34923f8707b854614b6450ead4d`
- 工作順序：`docs/GAP-ANALYSIS.md` 第 8 項；G06、G10、G14 已合併

## 施工進度

- [x] S1 資料層與折扣計算（含次數上限強制）
- [x] S2 維護 API 與總部 UI
- [x] S3 下單套用、讀路徑與報表

## S1 設計摘要

- `V9__order_discounts.sql` 新增規則主檔、訂單快照表與 `orders.discount_amount`，既有訂單以預設值 0 保持原語意。
- `Discounts` 是 `coffee-catalog` 對外介面；`DiscountService` 封裝規則維護、適用條件與折扣計算。
- 百分比使用整數乘法後除以 100；折抵上限為小計減 1，維持既有 `orders.total > 0` 約束。
- `apply()` 先以 `SELECT ... FOR UPDATE` 鎖規則，再檢查及遞增 `redeemed_count`，避免最後一個名額被並行超用；無上限規則仍會計數。
- S1 沒有 Controller 或下單呼叫端，屬純加法。

## S1 測試

- `DiscountMigrationTest`：全新資料庫結構、索引與 V8 升級後既有訂單預設折扣 0。
- `DiscountCalculationTest`：百分比無條件捨去、定額折扣最低保留 1 元。
- `DiscountRedemptionTest`：null／空白碼、單次上限、無上限計數與兩交易併發競爭。

## S2 設計摘要與測試

- 新增登入後的 `GET /api/discounts` 與 CSRF 保護的 `POST /api/discounts`；服務層同時要求 `MENU_MANAGE` 與總部範圍。
- 總部頁面可維護兩種折扣、適用分店、期間、最低消費、使用上限與啟用狀態，並顯示已用／上限。
- `DiscountAdminTest` 覆蓋權限、未登入、CSRF、規格驗證表、重複碼 409、排序、稽核及更新時保留後端計數。

## S3 設計摘要與測試

- 下單請求只接受優惠碼；後端正規化後納入冪等指紋，再由 `Discounts.apply()` 重算並在同一交易寫入訂單與不可變快照。
- 訂單單筆、分頁、付款及對帳讀路徑都回傳小計、折抵與規則快照；分頁以固定一批查詢載入折扣，不隨訂單數增加查詢次數。
- 套用成功寫入 `ORDER_DISCOUNT` 稽核；取消訂單不退回使用次數。
- 月報以折後金額計入營業額與毛利，另回傳當月折抵總額；前端支援輸入優惠碼、顯示訂單折抵及報表折抵 KPI。
- `OrderDiscountTest` 覆蓋正規化與冪等、快照不隨規則變更、最低消費、使用上限、取消不退次數及報表關係；`OrderPaginationTest` 驗證不同頁面大小維持固定四次查詢。
- POS 帶碼現金訂單改為兩段式收款：先以後端回應顯示小計、折抵與應收，再要求店員明確輸入實收；未帶碼的既有單段流程不變。
- `OrderDiscountTest` 另覆蓋驗收 21a：折後應收 126 元時，實收 130 元找零 4 元；實收 120 元失敗後訂單仍待付款、兌換次數不變且可以補收。

## §6.6 原始碼佐證

以下行號以本 PR 最新版 `frontend/src/modules/ordering/MenuView.vue` 為準。它們證明程式碼的分支與欄位來源；依規格 v1.5，瀏覽器實機操作由 PO 於合併後驗收，不擋本 PR。

### 21b：帶碼 POS 現金兩段式收款

建單時先辨識帶碼現金 POS，建立訂單後把後端回傳的 `Order` 保存為待收款訂單，清空實收欄位並停止本次流程（281–282、312–328）：

```ts
const cashAtPos = !auth.customer && payment.value === "CASH";
const discountedCashAtPos = cashAtPos && discountCode.value.trim().length > 0;
// ...
order = await send<Order>("/orders", body, "POST", {
  "Idempotency-Key": retryKey,
});
// ...
if (discountedCashAtPos) {
  pendingCashOrder.value = order;
  tendered.value = undefined;
  notify("訂單已建立，請輸入實收金額完成收款");
  return;
}
```

畫面三個數字都直接來自後端訂單欄位，而不是購物車重算值（600–609）：小計取 `pendingCashOrder.subtotal`、折抵取 `pendingCashOrder.discountAmount`、應收取 `pendingCashOrder.total`。

```vue
<div class="change-row">
  <span>小計</span><b>{{ money(pendingCashOrder.subtotal) }}</b>
</div>
<div class="change-row">
  <span>優惠折抵</span><b>-{{ money(pendingCashOrder.discountAmount) }}</b>
</div>
<div class="cart-total">
  <span>應收</span><strong>{{ money(pendingCashOrder.total) }}</strong>
</div>
```

第二次按下收款時，實收取自店員輸入的 `tendered.value`，並與後端回傳的折後 `order.total` 比較；未輸入、非整數、低於應收或超過上限都提示後直接 `return`，`pendingCashOrder` 不會清除（241–266）。只有通過才呼叫 `/cash`。收款回應的收據總額、實收與找零分別取 `receipt.total`、`receipt.tendered`、`receipt.changeAmount`（738–746），因此 140／14／126、實收 130、找零 4 的欄位鏈可追溯到後端回應。

```ts
const order = pendingCashOrder.value;
const cash = tendered.value;
if (
  cash === undefined ||
  !Number.isInteger(cash) ||
  cash < order.total ||
  cash > 1000000
) {
  notify("請輸入足夠的實收金額");
  return;
}
receipt.value = await send<Order>("/orders/" + order.id + "/cash", {
  tendered: cash,
});
```

### 22：帶碼訂單未輸入實收不得自動收款

帶碼第一階段會把 `tendered.value` 明確清為 `undefined` 並返回；待收款分支只讀 `tendered.value`，沒有購物車小計的預設值。未輸入時命中 `cash === undefined`，在任何 `/cash` 呼叫前返回（241–266）。模板的實收欄在待收款狀態以後端 `pendingCashOrder.total` 作為 `min`，placeholder 明示輸入實收（611–619）。

```vue
<input
  v-model.number="tendered"
  type="number"
  :min="pendingCashOrder?.total ?? 0"
  max="1000000"
  step="1"
  :placeholder="pendingCashOrder ? '請輸入實收金額' : String(total)"
/>
```

購物車小計預設實收只留在明確的「未帶碼」分支；帶碼分支不會計算或送出該預設值（283–291）：

```ts
let cash = tendered.value;
if (cashAtPos && !discountedCashAtPos) {
  cash ??= total.value;
  if (!Number.isInteger(cash) || cash < total.value || cash > 1000000) {
    notify("請輸入足夠的實收金額");
    return;
  }
}
```

### 23：未帶碼 POS 現金維持單段流程

分支條件 `cashAtPos && !discountedCashAtPos` 專門保留既有未帶碼檢核與 `tendered.value ?? total.value` 的預設語意；建單完成後只有 `discountedCashAtPos` 會進待收款並返回，未帶碼則直接進 `cashAtPos` 呼叫 `/cash`（281–291、324–335）。

```ts
if (discountedCashAtPos) {
  pendingCashOrder.value = order;
  tendered.value = undefined;
  notify("訂單已建立，請輸入實收金額完成收款");
  return;
}
if (cashAtPos) {
  receipt.value = await send<Order>("/orders/" + order.id + "/cash", {
    tendered: cash,
  });
  tendered.value = undefined;
  mobileCart.value = false;
}
```

因此未帶碼的建單與收款仍在同一次 `checkout()` 完成；收據依舊從後端回應的 `receipt.total`、`receipt.tendered`、`receipt.changeAmount` 顯示合計、實收與找零（738–746）。

## 驗證

- 本地 backend：Maven Central DNS 無法解析，未能啟動測試。
- 本地 frontend：待執行 production build。
- 靜態檢查：文件內的引用行與最新 `MenuView.vue` 分支、欄位來源逐條核對。
- 遠端 CI：待最新 head 推送後確認。
