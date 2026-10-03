# G08 分店每日可售數量施工報告

## 來源

- 規格：`docs/specs/G08-branch-product-stock.md` v1.1
- 主線來源：`d29939ef07ce6a81fe78c4afce5d19d5bfa079e2`
- 工作順序：`docs/GAP-ANALYSIS.md` 第 18 項（G24 已由 PR #57 合併）

## 施工進度

- [x] S1 — 資料層與讀寫端點
- [ ] S2 — 扣減、保留憑據與取消回補
- [ ] S3 — 菜單剩餘數量與自動售完
- [ ] S4 — POS 備量設定與剩餘徽章

## S1 完成內容

- 新增 V13 migration，建立 `branch_product_stock` 與
  `branch_product_stock_reservation`，包含規格要求的主鍵、檢查約束與索引。
- `Catalog` 新增 `ProductStock`、`StockLine`、`stock` 與 `setStock`。
- 新增 `GET /api/menu/stock` 與 `POST /api/menu/stock`。
- 複用 `MENU_AVAILABILITY` 並限制分店資料範圍；沒有新增權限常數或角色權限。
- `setStock` 支援 0–9999、解除限量、差額同步、稽核，以及從無到有時清除同一庫存列的舊保留憑據。
- 新增 migration 與管理端測試，涵蓋 schema、約束、權限、越權、邊界、讀寫、差額同步與稽核。

## 後續

下一階段是 S2。S1 刻意不把備量接入訂單建立流程，因此目前設定備量不會改變下單行為。

## 驗證

- `git diff --check`：通過
- `npm test`：通過（80 tests）
- `npm run build`：通過
- `./mvnw -B -ntp -Dtest=BranchProductStockMigrationTest,BranchProductStockAdminTest test`：
  本機因 Maven Central DNS 解析失敗而未能啟動；以最新推送 head 的 GitHub Actions 為主要後端驗證證據。
