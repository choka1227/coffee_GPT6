# G26 OrderDiscountTest 月份時區修正

## 來源

- 主線：`feature/init-project`，來源 SHA `84af4f4c94c313d6f47297a6bcbc855843bfb8a7`
- 施工依據：`docs/GAP-ANALYSIS.md` 的 G26 段與「排定的工作順序」第 14 項
- 規格階段：單一階段

## 設計摘要

`ReportService` 以 `Asia/Taipei` 計算報表月份，測試也必須用相同業務時區建立月份參數。因此將 `OrderDiscountTest` 的無參數 `YearMonth.now()` 改為 `YearMonth.now(ZoneId.of("Asia/Taipei"))`。

本次只修正測試的時間來源，不修改正式程式、資料庫、Flyway migration、模組邊界或第三方相依。

## 驗收對照

- [x] `OrderDiscountTest` 不再呼叫無參數的 `YearMonth.now()`。
- [x] 測試月份與 `ReportService` 同樣使用 `Asia/Taipei`。
- [x] 全 repo 掃描無參數的 `YearMonth.now()`、`LocalDate.now()`、`LocalDateTime.now()`、`ZonedDateTime.now()` 為零命中。
- [ ] `cd backend && ./mvnw -B -ntp verify`：本機因 Maven Central DNS 解析失敗，交由最新 head 的 GitHub Actions 驗證。
- [x] `cd frontend && npm ci && npm run build` 通過。
- [x] `backend/mvnw`、`scripts/build.sh`、`start-demo.sh` 維持 `100755`。

## 未完成與風險

目前沒有額外需求或設計疑點。本機 backend verify 未進入編譯階段，原因是執行環境無法解析 `repo.maven.apache.org`；這是外部依賴下載問題，不是程式失敗，最新 head 仍需由 GitHub Actions 完成驗證。
