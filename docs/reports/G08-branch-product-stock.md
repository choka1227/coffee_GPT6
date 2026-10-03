# G08 分店每日可售數量施工報告

## 來源

- 規格：`docs/specs/G08-branch-product-stock.md` v1.1
- 主線來源：`d29939ef07ce6a81fe78c4afce5d19d5bfa079e2`
- 工作順序：`docs/GAP-ANALYSIS.md` 第 18 項（G24 已由 PR #57 合併）

## 施工進度

- [x] S1 — 資料層與讀寫端點
- [x] S2 — 扣減、保留憑據與取消回補
- [x] S3 — 菜單剩餘數量與自動售完
- [ ] S4 — POS 備量設定與剩餘徽章

## S1 完成內容

- 新增 V13 migration，建立 `branch_product_stock` 與
  `branch_product_stock_reservation`，包含規格要求的主鍵、檢查約束與索引。
- `Catalog` 新增 `ProductStock`、`StockLine`、`stock` 與 `setStock`。
- 新增 `GET /api/menu/stock` 與 `POST /api/menu/stock`。
- 複用 `MENU_AVAILABILITY` 並限制分店資料範圍；沒有新增權限常數或角色權限。
- `setStock` 支援 0–9999、解除限量、差額同步、稽核，以及從無到有時清除同一庫存列的舊保留憑據。
- 新增 migration 與管理端測試，涵蓋 schema、約束、權限、越權、邊界、讀寫、差額同步與稽核。

## S2 完成內容

- `reserveStock` 先依商品合併數量、再按 `productId` 排序取行鎖；不限量商品略過，
  限量不足則以規格訊息拒絕，成功扣減時同步寫入合併後的 reservation。
- `OrderService.create` 在寫入訂單前、同一交易內保留庫存；既有 idempotency
  提前回傳使重送不會重扣，任一品項失敗會回滾整筆扣減。
- `releaseStock` 只依 reservation 記載的日期與數量回補，回補值夾在目前
  `quantity` 內；限量列不存在時只刪憑據，不重建庫存列。
- 訂單轉為 `CANCELLED` 後在同一交易內回補；刪除 reservation 使回補冪等。
- 新增訂單路徑、同商品多行、整筆回滾、重送、取消、跨日、解除／重建限量、
  不限量反向驗收，以及最後一份競爭與反向品項順序的併發測試。

## S3 完成內容

- `Catalog.Product` 新增 nullable `remaining`；顧客／POS 菜單會帶出今日分店剩餘量，
  未設定備量時維持 `null`，管理模式則一律遮蔽為 `null`。
- 菜單查詢以同日 `branch_product_stock` 判定自動售完；`remaining=0` 時回傳
  `SOLD_OUT`，手動 `UNLISTED` 與同日手動 `SOLD_OUT` 的優先序保持不變。
- `sellable` 同步檢查今日剩餘量，為 0 時以既有售完訊息拒絕，正數與不限量
  商品則照常回傳可售商品。
- 新增 S3 驗收測試，涵蓋剩餘量、扣到 0 後自動售完、售完拒絕、未限量、
  管理模式遮蔽，以及 `UNLISTED` 不洩漏剩餘量。
- 規格驗收 16 要求顧客菜單回傳 `UNLISTED` 商品，但既有
  `CoffeeIntegrationTest` 明確要求顧客菜單不包含 `UNLISTED`，且規格禁止放寬
  既有斷言。本階段保留既有隱藏契約；商品不出現在回應中，因此也不會洩漏
  `remaining`，`sellable` 仍回覆「本店未供應此商品」。

## 後續

下一階段是 S4：在 POS 加入備量設定與剩餘數量徽章。

## 驗證

- `git diff --check`：通過
- `npm test`：通過（80 tests）
- `npm run build`：通過
- S2 本地 `npm test`：通過（80 tests）
- S2 本地 `npm run build`：通過
- `./mvnw -B -ntp -Dtest=BranchProductStockMigrationTest,BranchProductStockAdminTest test`：
  本機因 Maven Central DNS 解析失敗而未能啟動；以最新推送 head 的 GitHub Actions 為主要後端驗證證據。
- S2 `./mvnw -B -ntp -Dtest=BranchProductStockOrderingTest,BranchProductStockMigrationTest,BranchProductStockAdminTest test`：
  本機仍因 Maven Central DNS 解析失敗而未進入編譯；推送後由最新 head 的 GitHub Actions 驗證。
- S3 `./mvnw -B -ntp -Dtest=BranchProductStockOrderingTest,CoffeeIntegrationTest,CatalogOptionsTest,AuditTrailTest test`：
  本機仍因 Maven Central DNS 解析失敗而未進入編譯；推送後由最新 head 的 GitHub Actions 驗證。
- S3 本地 `npm test`：通過（80 tests）
- S3 本地 `npm run build`：通過
