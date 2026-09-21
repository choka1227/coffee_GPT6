# G14 分店營業時間實作紀錄

- 來源規格：`docs/specs/G14-branch-business-hours.md`
- 主線來源：`1e1fb6e39db344e5b70233a07a597ad0213ff0df`
- 已完成階段：S1 資料層與判定、S2 維護 API 與總部 UI

## 設計摘要

- 新增 `V8__branch_business_hours.sql`，以一列一時段保存 ISO-8601 星期與台北當地分鐘數；migration 不新增資料列，沒有時段的既有分店維持 24 小時營業。
- `Branches` API 以加法新增 `Hours`、`hours()`、`openAt()` 與 `requireOrderable()`；既有 `requireOpen()` 未修改。
- 判定使用 `Asia/Taipei`，支援左閉右開、分段、跨夜、週日到週一繞接與 `closeMinute=1440`。
- 沒有新增模組、跨模組依賴或第三方相依。
- S2 以分店列行鎖序列化整批取代，完成全部輸入驗證後才刪寫，成功後記錄 `BRANCH_HOURS_SAVE` 稽核。
- 總部 UI 提供每週分段與跨夜時段編輯，空排程明示「視為 24 小時營業」。

## 疑點與處理

- 為讓判定演算法能以不啟動 Spring 的單元測試驗證，純函式公開為 `BranchService.isOpenAt(...)`；DB 查詢仍封裝在 `coffee-branches.internal`。
- `requireOrderable()` 已先實作規格要求的繁中打烊訊息，但 S1 尚無呼叫端，不改變既有下單行為。

## 驗證

- `npm run build`：成功（Vue TypeScript 檢查與 Vite production build）。
- `./mvnw -B -ntp verify`：未啟動測試；Maven Central `repo.maven.apache.org` DNS 解析失敗。
- 遠端 CI：等待最新 head 驗證。

## S2 驗收涵蓋

- 總部寫入、排序讀回、一般顧客跨店讀取、未登入讀取 401。
- 無權限、非總部範圍、CSRF 缺失、所有數值邊界與單日上限。
- 一般／跨夜／週日到週一重疊、相鄰時段、失敗不部分寫入、稽核只在成功時產生、並行整批取代不交錯。

## 後續階段

- S3：顧客下單時段強制與 `openNow` 顯示。
