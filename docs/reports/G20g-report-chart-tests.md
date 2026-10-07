# G20g — 報表圖表層測試實作回報

## 來源

- 規格：`docs/specs/G20g-report-chart-tests.md` v1.1
- 主線：`feature/init-project` @ `b2fc82a8c8515127dbd0035430cf43ab7bf7c139`
- 分支：`codex/g20g-report-chart-tests`

## 完成內容

- 將 `Chart.vue` 測試替身集中到共用 testing harness，避免報表測試重複定義。
- 補齊每日營業額折線圖與 24 小時訂單長條圖的資料來源、零值、圖表型別測試。
- 新增 `Chart.vue` DOM 測試，覆蓋初始 option 與 aria、深層 option 更新的 `notMerge`、卸載清理及可及性屬性。
- 分類圖顯示條件改以 `netProductRevenue` 判斷，並覆蓋有淨營收與完全無淨營收兩個方向。
- 無後端、Flyway、第三方相依或圖表元件介面變更。

## 驗證

- `cd frontend && npm test`：通過，11 個測試檔、128 項測試全數成功。
- `cd frontend && npm run build`：通過；僅有既存 chunk size 警告。
- `cd backend && ./mvnw -B -ntp verify`：本地環境無法解析 `repo.maven.apache.org`，尚未執行完成；程式碼未涉及後端，完整驗證交由最新 head 的 GitHub Actions 補足。
- `git diff --check`：通過。

## 剩餘工作

- 等待最新 head 的 GitHub Actions `verify` 完成；成功後轉為 ready for review 並啟用 auto-merge。
