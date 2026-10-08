# G20h 後端購物車試算端點實作報告

## 來源

- 規格：`docs/specs/G20h-order-preview.md` v1.2
- 主線來源：`feature/init-project@a262dc37466314e3b04881b45b04bb5e3fa682c1`
- 工作順序：`docs/GAP-ANALYSIS.md` 第 24 項
- 交接：GitHub Issue #76

## 施工進度

- [x] S1：優惠碼的不消耗試算
  - `Discounts` 新增 `quote`
  - `DiscountService` 以同一個 `resolve` 共用所有有效性驗證
  - `quote` 不取 `for update` 行鎖，也不增加 `redeemed_count`
  - `apply` 保留行鎖與兌換計數行為
  - `DiscountRedemptionTest` 新增規格要求的四組案例，既有案例與斷言未修改
- [x] S2：共用計價方法與 `POST /api/orders/preview`
  - `OrderService.create` 與 `preview` 共用唯一的 `price()` 計價路徑
  - 試算使用 `Discounts.quote`，不消耗優惠碼、不保留庫存、不寫訂單或稽核
  - 新增精簡的 `PreviewRequest`、`Quote`、`PreviewLine` 契約與既有 Controller 端點
  - `OrderPreviewTest` 覆蓋 HTTP 金額一致性、折抵恆等式、零寫入、零庫存占用、權限與售完訊息
- [x] S3：前端 debounce、競態處理、金額顯示與 G20i 樣式
  - 新增 `preview.ts` 單調請求序號狀態機，舊回應與 reset 前在途回應不會覆寫新狀態
  - 購物車與優惠碼變動使用 300ms debounce；分店切換與清空購物車會取消計時並失效在途回應
  - 有折抵時顯示後端小計、品項促銷折抵、優惠碼折抵與試算總計；零折抵或失敗時維持毛額總計
  - POS 現金第二階段仍只顯示已建立訂單的四行真實金額
  - 補齊 G20i 促銷提示與試算明細樣式，未改 G20a 的 class、DOM 結構或顯示條件

## S1 驗收對照

- 驗收 1：同輸入的 `quote` 與 `apply` 回傳相同 `Applied` 欄位。
- 驗收 2：連續試算 20 次不消耗；之後 `apply` 一次只增加一次計數。
- 驗收 3：停用、未開始、已結束、其他分店、未達最低消費與額度已滿，兩條路徑共用驗證並回相同狀態與訊息。
- 驗收 14：既有建立訂單測試案例與斷言未修改。

## S3 驗收對照

- 驗收 11、12：`preview.spec.ts` 覆蓋亂序回應、較新失敗、reset 失效在途回應與等待期間沿用前次金額。
- 驗收 17：DOM 測試覆蓋有折抵的三行明細與後端總額，以及零折抵時維持既有毛額。
- 驗收 18：DOM 測試覆蓋試算 500 的靜默降級；既有現金第二階段測試確認四行真實金額不變且不顯示試算明細。
- 驗收 13、19：`promotions.ts` 未修改，前端與後端皆未新增依賴。

## 驗證

- `git diff --check`：通過。
- 執行位元：`backend/mvnw`、`scripts/build.sh`、`start-demo.sh` 均為 `100755`。
- 本地前端：`npm test` 通過（12 個測試檔、136 個案例）；`npm run build` 通過。
- 本地 Maven：完整 `verify` 仍因 `repo.maven.apache.org` 暫時性 DNS 解析失敗，無法下載 Spring Boot parent POM；交由最新 head 的遠端 CI 補足。
- 遠端 CI：待 S3 head 推送後更新。

## 剩餘工作

程式施工階段均已完成；待最新 head 必要 CI 全部成功後轉為 ready for review，並依專案流程啟用 auto-merge。
