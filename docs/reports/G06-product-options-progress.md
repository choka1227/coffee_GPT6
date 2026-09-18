# G06 實作進度

來源：`docs/specs/G06-product-options.md`。分支已 merge 最新主線
`534fdb1`（含 PR #12、#13）。本次實作：G06。尚待實作：G01a、G13。

## 設計摘要與假設

- S1 新增 `V3__product_options.sql`：四張表、索引、既有飲品群組綁定、歷史訂單欄位預設值；V1/V2 不變。
- `InitialData` 僅在首次 demo seed 補預設綁定，重啟不恢復管理員刻意解除的綁定。
- S2 擴充 `Catalog` 公開契約，商品帶啟用中的選項樹；`resolveOptions` 集中執行規格七項選擇規則。
- 菜單與 `productOptions` 一律遮蔽選項成本；只有總部管理 API 與模組內解析結果保留真實成本。
- 維護 API 沿用 `MENU_MANAGE` 並強制 GLOBAL；寫入受既有 CSRF 保護。未新增權限、第三方相依或模組依賴。
- S3 將請求改為只接受排序正規化後的 `optionIds`；後端解析並以 exact arithmetic 重算每份售價、成本與總額。
- 訂單同時保存本體價格／成本、選項合計與逐項歷史快照；API 回應不含任何選項成本。
- 報表收入與成本改採本體加選項快照，既有訂單的 V3 預設 0 行為不變。
- 點餐 UI 依 Catalog 的群組、SINGLE/MULTI 與 min/max 動態渲染，不再硬編溫度與甜度。

## 施工進度

- [x] S1 資料層與初始化：遠端 Actions run #57 全部成功。
- [x] S2 Catalog 選項讀取與維護：Actions run #66 全部成功。
- [x] S3 點餐整合與報表：訂單 API、快照、定價、報表與動態點餐 UI 完成；待本次遠端 CI 確認。

## 測試與驗收對照

- `ProductOptionsMigrationTest`：fresh demo／production、重啟冪等、V2 升級、售價與歷史資料不變、schema 約束。
- `CatalogOptionsTest`：請求空值與上限、重複、停用、未綁定、SINGLE 上限、必選與 min/max；成本遮蔽；CRUD、GLOBAL 範圍與 409。
- `CoffeeIntegrationTest`：後端選項加價與成本、快照、成本欄位遮蔽、選項順序無關的冪等重放。
- `ReconciliationTest`／`HttpWorkflowTest`：破壞性請求格式同步為 `optionIds`，清理順序涵蓋快照 FK。

驗證狀態：

- frontend `npm run build`：成功。
- backend 主程式編譯成功；`CoffeeIntegrationTest` 11 項與 `CatalogOptionsTest` 3 項成功。
- `CoffeeIntegrationTest`、`CatalogOptionsTest`、`ReconciliationTest` 合計 22 項成功；清理順序已涵蓋 `order_item_options` FK。
- 完整本機 verify 受損壞的 ArchUnit 下載快取限制；沒有修改、跳過或放寬 repo 測試。`ModuleBoundariesTest` 留待乾淨遠端 CI 執行。
- `git diff --check`：成功；`backend/mvnw`、`scripts/build.sh`、`start-demo.sh` 維持 100755。
- 未驗證：PostgreSQL 實測；完整本機 verify 仍受 ArchUnit 快取限制，待乾淨遠端 CI。

## 續作

維持同一支 `codex/g06-product-options` 與 draft PR。S3 完整測試重跑並推送後，
以乾淨遠端 CI 判定；只有全部成功才轉 ready 並啟用 auto-merge。
