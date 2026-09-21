# G07 訂單折扣與優惠碼實作紀錄

- 來源規格：`docs/specs/G07-order-discounts.md` v1.1
- 主線來源：`cc1d765fcffd5e871763edf7161a9d6b0a92eac5`
- 工作順序：`docs/GAP-ANALYSIS.md` 第 8 項；G06、G10、G14 已合併

## 施工進度

- [x] S1 資料層與折扣計算（含次數上限強制）
- [x] S2 維護 API 與總部 UI
- [ ] S3 下單套用、讀路徑與報表

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

## 下一步

- S3：下單套用、訂單讀路徑、折扣稽核與報表彙總。

## S2 設計摘要與測試

- 新增登入後的 `GET /api/discounts` 與 CSRF 保護的 `POST /api/discounts`；服務層同時要求 `MENU_MANAGE` 與總部範圍。
- 總部頁面可維護兩種折扣、適用分店、期間、最低消費、使用上限與啟用狀態，並顯示已用／上限。
- `DiscountAdminTest` 覆蓋權限、未登入、CSRF、規格驗證表、重複碼 409、排序、稽核及更新時保留後端計數。
