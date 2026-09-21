# G14 分店營業時間實作紀錄

- 來源規格：`docs/specs/G14-branch-business-hours.md`
- 主線來源：`0245ff67dcb6c421f97035c5d729d7abc51e0612`（S3 開工前已 merge）
- 已完成階段：S1 資料層與判定、S2 維護 API 與總部 UI、S3 下單強制與顧客端顯示

## 設計摘要

- 新增 `V8__branch_business_hours.sql`，以一列一時段保存 ISO-8601 星期與台北當地分鐘數；migration 不新增資料列，沒有時段的既有分店維持 24 小時營業。
- `Branches` API 以加法新增 `Hours`、`hours()`、`openAt()` 與 `requireOrderable()`；既有 `requireOpen()` 未修改。
- 判定使用 `Asia/Taipei`，支援左閉右開、分段、跨夜、週日到週一繞接與 `closeMinute=1440`。
- 沒有新增模組、跨模組依賴或第三方相依。
- S2 以分店列行鎖序列化整批取代，完成全部輸入驗證後才刪寫，成功後記錄 `BRANCH_HOURS_SAVE` 稽核。
- 總部 UI 提供每週分段與跨夜時段編輯，空排程明示「視為 24 小時營業」。
- S3 僅對顧客自助下單呼叫 `requireOrderable()`；員工 POS 仍只檢查分店有效。冪等短路維持在時段檢查前，時段判定與 `created_at` 共用同一個 `now`。
- 分店清單一次批次讀取全部 `branch_hours` 並在記憶體分組，HTTP DTO 增加 `openNow`，不改 `Branch` record，也不產生 N+1。
- 顧客點餐頁顯示目前營業狀態與完整一週時段；打烊時仍可瀏覽，但不可選餐、加入或送出。員工 POS 不受前端時段限制。

## 疑點與處理

- 為讓判定演算法能以不啟動 Spring 的單元測試驗證，純函式公開為 `BranchService.isOpenAt(...)`；DB 查詢仍封裝在 `coffee-branches.internal`。
- `requireOrderable()` 的繁中打烊訊息已接入顧客下單；今日有時段時列出完整時段，今日沒有時段時回覆「分店今日未營業」。

## 驗證

- `npm run build`：成功（Vue TypeScript 檢查與 Vite production build）。
- `./mvnw -B -ntp verify`：未啟動測試；Maven Central `repo.maven.apache.org` DNS 解析失敗。
- 遠端 CI：等待 S3 最新 head 驗證；S2 head `ceeb1bda48fc26f2f3e1e0c58ba9148b106f68a8` 已全部成功。

## S2 驗收涵蓋

- 總部寫入、排序讀回、一般顧客跨店讀取、未登入讀取 401。
- 無權限、非總部範圍、CSRF 缺失、所有數值邊界與單日上限。
- 一般／跨夜／週日到週一重疊、相鄰時段、失敗不部分寫入、稽核只在成功時產生、並行整批取代不交錯。

## S3 驗收涵蓋

- 顧客打烊時阻擋並帶今日時段、今日無排程訊息、營業時可成功下單。
- 員工 POS 打烊時仍可下單；冪等重放與既有訂單收款／狀態流轉在打烊後仍可完成。
- 分店清單增加 `openNow`，打烊但 active 的分店仍顯示，inactive 的既有過濾行為不變。
- 顧客端顯示一週完整時段，打烊時停用新訂單操作；員工端維持原行為。

## 後續階段

- 無；待 S3 最新 head 的必要遠端 CI 成功後轉 Ready for review。
