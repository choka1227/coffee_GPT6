# G20 品項層促銷施工進度

來源規格：`docs/specs/G20-item-level-promotions.md` v1.0
來源主線：`5a1257e80d2a531128d6d1ce8b1531f6129e4ba2`

## 階段

- [x] S1 資料層與總部維護端點
- [ ] S2 計價演算法
- [ ] S3 訂單整合與前端守門
- [ ] S4 前端維護頁與顯示

## S1

- 新增 `V14__item_promotions.sql`：規則主檔、訂單促銷快照及訂單／品項折抵欄位。
- 新增 `Promotions` 公開介面與規則／計價資料型別；`apply` 尚無呼叫端，本階段固定回傳 `null`。
- 新增總部限定的 `GET`／`POST /api/promotions`，涵蓋新增、更新、停用、商品／分類目標、分店與期間驗證。
- `ITEM_PERCENT` 儲存時強制正規化 `nth=0`；更新不存在的 id 回 404。
- 新增 `PROMOTION_SAVE` 稽核，總部行為的 `branch_id` 固定為 `null`。
- 新增整合測試，覆蓋正常路徑、規格列出的欄位驗證、401／403、CSRF 與 migration 結果。

## 下一步

S2：實作候選規則查詢、逐單位折抵、最便宜單位排序、同額 id 決勝，並完成九組純計算測試。
