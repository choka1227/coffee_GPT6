# G06 實作進度

來源：`docs/specs/G06-product-options.md`，主線 `ef65034a057f0eb95b8ebc34d979ed06ea338148`（含 PR #11）。
本次實作：G06。尚待實作：G06 S2/S3、G01a；G13 待規格核准合併及 G06 完成。

## 設計摘要與假設

- S1 新增 `V3__product_options.sql`：四張表、索引、既有飲品群組綁定、歷史訂單新增欄位預設 0，保留溫度甜度；V1/V2 不變。
- 依已合併第 13 節決策，選項加價不得負數；群組選擇類型為 SINGLE/MULTI。
- `InitialData` 首次 demo 商品 seed 後補綁定，訂單明細使用明確欄位清單。
- 重啟不恢復管理員刻意解除的綁定；既有商品升級由 migration 處理，而非每次啟動重新強加預設值。
- 無 API、權限、模組依賴或第三方相依變更。此階段新表尚未接入點餐定價。

## 施工進度

- [ ] S1 資料層與初始化：程式及測試已完成，backend verify 尚未驗證成功。
- [ ] S2 Catalog 選項讀取與維護：未開始。
- [ ] S3 點餐整合與報表：未開始。

## 測試與驗收對照

新增 `ProductOptionsMigrationTest` 三項案例：

1. 新資料庫 demo 初始化：6 飲品共 12 個綁定、烘焙無綁定；歷史明細選項金額為零、溫度甜度保留；重跑不重複且不恢復已解除綁定。
2. 非 demo 初始化與重跑：只建 bootstrap 帳號，不生成商品綁定。
3. V2 升級：既有商品價格不變、飲品綁定、烘焙無綁定、8 個零加價選項、負加價與 min/max 約束。

驗證狀態：

- frontend `npm run build`：成功（使用既有相同 lockfile 的 node_modules 快取；乾淨 npm ci 未完成）。
- backend `./mvnw -B -ntp verify`：下載 parent POM 遇 DNS 錯誤；使用既有 Maven 快取離線重試，又遇 resources plugin 缺少 `org.apache.commons.lang3.StringUtils`，尚未執行到測試。
- `git diff --check`：成功；三個執行檔維持 100755。
- 未驗證：新增測試的執行結果、PostgreSQL 實測、S2/S3 新端點的越權/CSRF/併發驗收。不得將本階段宣稱完整或整份規格宣稱完成。

## 續作

維持同一支 `codex/g06-product-options` 及 draft PR；先取得可靠的 backend verify/CI 結果並修正任何失敗，再進行 S2。不啟用 auto-merge。
