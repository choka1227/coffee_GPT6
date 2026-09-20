# G11 + G15 實作進度

來源規格：`docs/specs/G11-G15-audit-and-cash-sessions.md` v1.2  
來源主線：`feature/init-project` @ `abbed9a2d25a4423a593f250fa3f62ec6049d3a6`

## 施工進度

- [x] S1 稽核基礎建設
- [x] S2 稽核涵蓋範圍與查詢 API
- [ ] S3 現金班別資料層與開班／收現
- [ ] S4 交班、歷史查詢與前端

## S1 設計與驗證

- 新增只依賴 `coffee-shared` 的 `coffee-audit` 葉節點模組。
- `AuditService.record()` 在業務交易提交後呼叫獨立 `REQUIRES_NEW` writer；非交易呼叫走相同的外層例外捕捉。
- `actorName`、`targetId`、`summary` 在建立 entry 時分別限制為 80、80、200 字元。
- Identity 的 `ACCOUNT_SAVE`／`ROLE_SAVE` action 保持不變，改由公開 `Audit` API 寫入。
- V5 只擴充既有 audit table、索引及既有角色的 `AUDIT_VIEW` 資料；S2 才加入權限常數與查詢端點。
- 驗收涵蓋空／既有 DB migration、舊資料保留、action 相容、截斷、稽核 SQL 失敗不回滾業務、業務回滾不留稽核與 ArchUnit 模組邊界。

## S2 設計與驗證

- `AUDIT_VIEW` 加入權限目錄與全新資料庫的 MANAGER seed；既有資料庫沿用 V5 補權限。
- orders、catalog、branches 僅依賴 `coffee-audit` 公開 API；現金收款、狀態轉換、商品與分店儲存均在業務提交後寫入具正確分店範圍的稽核紀錄。
- G13 的供應狀態稽核也改走公開 API，避免長複合 target id 與稽核失敗回滾業務的既有風險；action 字串保持不變。
- `/api/audit` 採 `createdAt:id` 游標、固定倒序與 200 筆上限；BRANCH 範圍由後端強制套用，無法用 query parameter 跨店。
- 前端新增稽核查詢頁與權限導覽；API 文件補上端點與授權範圍。
- 新增寫入點、游標完整性、上限夾取、資料範圍、無權限與 CSRF 反向驗收測試。
- frontend：`npm run build` 成功。
- backend：本機 Maven Central DNS 解析失敗，未能啟動測試；程式與測試已推送，由最新 head 的 GitHub Actions 驗證。

## S3 設計與驗證

- 新增 V6 `cash_sessions`、nullable `orders.cash_session_id`、查詢索引及既有角色權限升級；H2 不採 PostgreSQL 部分索引語法，唯一性由分店行鎖與 service 檢查保證。
- 新增開班、目前班別與交班端點；準備金、實點金額、預期金額與短溢全部由後端驗證或計算。
- 開班、收現、交班皆先鎖該店 `branches` 列；收現之後才重讀 OPEN 班別，避免交班定稿後仍有訂單掛入。
- 現金收入以 `SUM(total)` 計算，不採 `tendered`；未開班時既有收現流程仍成功且 `cash_session_id` 為 null。
- 新增全新／既有資料庫 migration、角色權限、重複與併發開班、收現／交班競態、跨店／無權限、CSRF、金額與重複交班測試。
- frontend：`npm run build` 成功。
- backend：本機 Maven Central DNS 解析失敗，verify 未啟動；Actions #134 找出舊版升級 fixture 會預先帶入新權限，V6 已改為冪等補登，等待修正 head 驗證。
