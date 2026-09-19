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
- [x] S2 可用性維護 API
- [x] S3 菜單查詢與下單套用

## S1 測試計畫與驗收

- 全新資料庫：CASHIER、MANAGER、HQ 均取得 `MENU_AVAILABILITY`。
- V3 既有資料庫升級：migration 補齊三個角色權限，重跑 migrate 不重複。
- `branch_products`：驗證主鍵、外鍵、狀態列舉與 `SOLD_OUT`／`sold_out_date` 關聯限制。
- 執行 frontend build、backend verify；遠端 CI 以本階段最新 head 為準。

## S2 設計與驗收

- 新增可用性查詢與設定端點；設定流程先鎖定一定存在的 `products` 列，再讀取現況、判斷授權、upsert 與寫入稽核，全部位於同一交易。
- `UNLISTED` 的設定與解除僅允許具 `MENU_MANAGE` 的 GLOBAL actor；一般售完切換需 `MENU_AVAILABILITY` 且限所屬分店。
- 稽核以 `MENU_AVAILABILITY_{狀態}` 記錄狀態，`target_id` 僅存 `{branchId}:{productId}`，支援兩個 36 字元識別碼。
- 查詢用單一台北日期 helper 將昨日 `SOLD_OUT` 視為 `AVAILABLE`；S3 會沿用同一 helper 套用於菜單與下單。
- `setAvailability` 回傳寫入後的 record，讓只具 GLOBAL `MENU_MANAGE` 的合法呼叫者不必再通過查詢端點的 `MENU_AVAILABILITY` 權限才能取得 POST 回應。
- 測試涵蓋跨店、顧客、店端停供、停供解除、HQ、無效狀態、404、最大識別碼、稽核原子性、CSRF 與受控併發。

## S3 設計與驗收

- `Catalog.Product` 加入有效供應狀態，點餐菜單改以 `branchId` 查詢；未指定分店回 400，總部管理查詢維持原有全鏈與成本可見行為。
- 分店覆寫以台北日期判定：當日 `SOLD_OUT` 保留在清單，隔日自動恢復；`UNLISTED` 與全鏈下架商品不出現在點餐菜單。
- 下單在原交易內以分店查核商品；冪等重放仍在查核前回傳原訂單，售完與停供不改動任何金額來源或計算。
- 點餐畫面切換分店後重載菜單，售完商品不可加入；既有購物車在新分店不可售時會提示並阻擋結帳。具 `MENU_AVAILABILITY` 權限者可標記售完或恢復供應。
- 總部菜單維護畫面可按分店設定 `UNLISTED`／`AVAILABLE`，所有請求均透過共用 API client。
- 業務測試涵蓋預設可售、跨店售完隔離、停供、隔日恢復、全鏈下架、失敗下單不落資料及售完後冪等重放。
