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
- [ ] S2：共用計價方法與 `POST /api/orders/preview`
- [ ] S3：前端 debounce、競態處理、金額顯示與 G20i 樣式

## S1 驗收對照

- 驗收 1：同輸入的 `quote` 與 `apply` 回傳相同 `Applied` 欄位。
- 驗收 2：連續試算 20 次不消耗；之後 `apply` 一次只增加一次計數。
- 驗收 3：停用、未開始、已結束、其他分店、未達最低消費與額度已滿，兩條路徑共用驗證並回相同狀態與訊息。
- 驗收 14：既有建立訂單測試案例與斷言未修改。

## 驗證

- `git diff --check`：通過。
- 執行位元：`backend/mvnw`、`scripts/build.sh`、`start-demo.sh` 均為 `100755`。
- 本地 Maven：未完成。解析 `repo.maven.apache.org` 時發生暫時性 DNS 失敗，無法下載 Spring Boot parent POM；程式與測試仍已保存並交由最新 head 的遠端 CI 驗證。
- 遠端 CI：推送後更新。

## 剩餘工作

下一階段為 S2；本 PR 在整份 G20h 完成前維持 draft，不啟用 auto-merge。
