# G27 無參數 `now()` 自動化防線

## 來源

- 主線：`feature/init-project`，來源 SHA `b80d4025b9d8eb5492be401e90c0764c25e4b50a`
- 施工依據：`docs/GAP-ANALYSIS.md` 的 G27 段與「排定的工作順序」第 16 項
- 規格階段：單一階段

## 設計摘要

新增 `TimeZoneGuardTest`，用 ArchUnit 掃描 `com.coffee` 的正式程式與測試程式，禁止 `YearMonth`、`LocalDate`、`LocalDateTime`、`ZonedDateTime`、`LocalTime` 的無參數 `now()`。匯入器刻意不排除測試，確保測試碼中的時區風險也會被擋下。另由測試在暫存目錄即時編譯隔離 fixture，反向斷言同一條規則確實會拒絕無參數 `LocalDate.now()`；fixture 不會進入 repository、正式程式或既有測試。

`Instant.now()` 不在黑名單中，因為它表示絕對時刻、不依賴 runner 預設時區。本次不修改正式程式、既有測試、資料庫、Flyway migration、模組邊界或第三方相依。

## 驗收對照

- [x] 新增 `TimeZoneGuardTest`，涵蓋五種日期／時間類別的無參數 `now()`。
- [x] 新測試在目前程式碼上通過（最新 head GitHub Actions backend verify 成功）。
- [x] 反向驗收：執行期編譯含 `LocalDate.now()` 的隔離 fixture，確認同一條守門規則拋出 `AssertionError`；效果等同手工暫時注入並還原，且不留下危險程式碼。
- [x] 既有 `Instant.now()` 呼叫維持不變且測試通過。
- [x] 既有測試沒有持久修改。
- [x] `cd backend && ./mvnw -B -ntp verify` 通過（GitHub Actions）。
- [x] `backend/mvnw`、`scripts/build.sh`、`start-demo.sh` 均維持 `100755`。

## 驗證

- 全 repo 掃描五種日期／時間類別的無參數 `now()`：零命中。
- `Instant.now()`：保留於 `BranchController`、`BranchHourOverrideTest` 與 `BranchLastOrderTest`，本次未修改。
- 既有測試差異：零；`git diff --check` 通過。
- `cd frontend && npm test -- --run`：70/70 通過。
- `cd frontend && npm run build`：通過。
- 本機 backend targeted test／verify：預設 Maven cache 因 `repo.maven.apache.org` DNS 解析失敗，未進入編譯；嘗試既有 cache 時又遇到既存 JAR／POM 截斷（`zip END header not found`），屬環境／依賴快取問題。
- 遠端 CI：head `27a26eb5399e29e43bb88d87dc2df84cc406c592` 的 Actions run `36886318569` 全部成功；動態反向 fixture、frontend test/build 與 `./mvnw -B -ntp verify` 均為 success。
