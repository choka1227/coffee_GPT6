# G14 分店營業時間實作紀錄

- 來源規格：`docs/specs/G14-branch-business-hours.md`
- 主線來源：`1e1fb6e39db344e5b70233a07a597ad0213ff0df`
- 已完成階段：S1 資料層與判定

## 設計摘要

- 新增 `V8__branch_business_hours.sql`，以一列一時段保存 ISO-8601 星期與台北當地分鐘數；migration 不新增資料列，沒有時段的既有分店維持 24 小時營業。
- `Branches` API 以加法新增 `Hours`、`hours()`、`openAt()` 與 `requireOrderable()`；既有 `requireOpen()` 未修改。
- 判定使用 `Asia/Taipei`，支援左閉右開、分段、跨夜、週日到週一繞接與 `closeMinute=1440`。
- 沒有新增模組、跨模組依賴或第三方相依。

## 疑點與處理

- 為讓判定演算法能以不啟動 Spring 的單元測試驗證，純函式公開為 `BranchService.isOpenAt(...)`；DB 查詢仍封裝在 `coffee-branches.internal`。
- `requireOrderable()` 已先實作規格要求的繁中打烊訊息，但 S1 尚無呼叫端，不改變既有下單行為。

## 驗證

- `npm ci --no-audit --no-fund && npm run build`：成功。
- `./mvnw -B -ntp verify`：未啟動測試；Maven Central DNS 解析失敗。
- 遠端 CI：等待最新 head 驗證。

## 後續階段

- S2：營業時間維護 API、總部 UI、授權、CSRF、整批驗證與稽核。
- S3：顧客下單時段強制與 `openNow` 顯示。
