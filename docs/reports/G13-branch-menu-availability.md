# G13 分店菜單可用性與售罄 — 實作進度

來源規格：`docs/specs/G13-branch-menu-availability.md` v1.2  
來源主線：`feature/init-project` @ `89f5710f2d26bd1e179da87539fac7860e71bf7f`

## 設計摘要

- S1 新增 `V4__branch_menu_availability.sql`，建立分店商品覆寫表與索引。
- `MENU_AVAILABILITY` 同步加入權限常數、全新資料 seed 與既有資料庫 migration。
- 未新增模組相依；S1 沒有程式讀取新表，既有行為保持不變。

## 疑點與假設

- migration 目錄開工時最高為 V3，因此依規格採用 V4。
- 規格施工進度區塊中的勾選內容是 PR 描述範例，不代表 S1 已在程式碼完成；以主線檔案與測試為準。

## 施工進度

- [x] S1 資料層與權限常數
- [ ] S2 可用性維護 API
- [ ] S3 菜單查詢與下單套用

## S1 測試計畫與驗收

- 全新資料庫：CASHIER、MANAGER、HQ 均取得 `MENU_AVAILABILITY`。
- V3 既有資料庫升級：migration 補齊三個角色權限，重跑 migrate 不重複。
- `branch_products`：驗證主鍵、外鍵、狀態列舉與 `SOLD_OUT`／`sold_out_date` 關聯限制。
- 執行 frontend build、backend verify；遠端 CI 以本階段最新 head 為準。
