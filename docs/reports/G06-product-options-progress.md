# G06 實作進度

來源：`docs/specs/G06-product-options.md`。分支已 merge 最新主線
`6ba8f57c59db4d539ac1e9ef7ea8c31fc618db9c`（含 PR #13）。
本次實作：G06。尚待實作：G06 S3、G01a；G13 需等 G06 完成。

## 設計摘要與假設

- S1 新增 `V3__product_options.sql`：四張表、索引、既有飲品群組綁定、歷史訂單欄位預設值；V1/V2 不變。
- `InitialData` 僅在首次 demo seed 補預設綁定，重啟不恢復管理員刻意解除的綁定。
- S2 擴充 `Catalog` 公開契約，商品帶啟用中的選項樹；`resolveOptions` 集中執行規格七項選擇規則。
- 菜單與 `productOptions` 一律遮蔽選項成本；只有總部管理 API 與模組內解析結果保留真實成本。
- 維護 API 沿用 `MENU_MANAGE` 並強制 GLOBAL；寫入受既有 CSRF 保護。未新增權限、第三方相依或模組依賴。
- `coffee-orders` 尚未接入新選項模型；此破壞性整合依規格留在 S3 一次完成。

## 施工進度

- [x] S1 資料層與初始化：遠端 Actions run #57 全部成功。
- [x] S2 Catalog 選項讀取與維護：API、解析規則、成本隔離、總部維護 UI 與測試完成；待本次遠端 CI 確認。
- [ ] S3 點餐整合與報表：未開始。

## 測試與驗收對照

- `ProductOptionsMigrationTest`：fresh demo／production、重啟冪等、V2 升級、售價與歷史資料不變、schema 約束。
- `CatalogOptionsTest`：請求空值與上限、重複、停用、未綁定、SINGLE 上限、必選與 min/max；成本遮蔽；CRUD、GLOBAL 範圍與 409。
- `CoffeeIntegrationTest`：顧客／店長越權、總部正常路徑、巢狀 `costDelta` 遮蔽、管理端點 CSRF。

驗證狀態：

- frontend `npm run build`：成功。
- backend 主程式與測試編譯成功；`CoffeeIntegrationTest` 與 `CatalogOptionsTest` 共 14 項成功。
- 完整本機 verify 受損壞的 ArchUnit 下載快取限制；沒有修改、跳過或放寬 repo 測試。`ModuleBoundariesTest` 留待乾淨遠端 CI 執行。
- `git diff --check`：成功；`backend/mvnw`、`scripts/build.sh`、`start-demo.sh` 維持 100755。
- 未驗證：PostgreSQL 實測，以及 S3 的點餐、報表、冪等與併發驗收。

## 續作

維持同一支 `codex/g06-product-options` 與 draft PR。S2 推送後先確認完整遠端 CI，
再進行 S3；整份規格完成前不轉 ready、不啟用 auto-merge。
