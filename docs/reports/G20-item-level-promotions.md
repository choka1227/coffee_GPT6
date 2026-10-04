# G20 品項層促銷施工進度

來源規格：`docs/specs/G20-item-level-promotions.md` v1.0
來源主線：`5a1257e80d2a531128d6d1ce8b1531f6129e4ba2`

## 階段

- [x] S1 資料層與總部維護端點
- [x] S2 計價演算法
- [x] S3 訂單整合與前端守門
- [ ] S4 前端維護頁與顯示

## S1

- 新增 `V14__item_promotions.sql`：規則主檔、訂單促銷快照及訂單／品項折抵欄位。
- 新增 `Promotions` 公開介面與規則／計價資料型別；`apply` 尚無呼叫端，本階段固定回傳 `null`。
- 新增總部限定的 `GET`／`POST /api/promotions`，涵蓋新增、更新、停用、商品／分類目標、分店與期間驗證。
- `ITEM_PERCENT` 儲存時強制正規化 `nth=0`；更新不存在的 id 回 404。
- 新增 `PROMOTION_SAVE` 稽核，總部行為的 `branch_id` 固定為 `null`。
- 新增整合測試，覆蓋正常路徑、規格列出的欄位驗證、401／403、CSRF 與 migration 結果。

## S2

- 候選規則依啟用狀態、分店與起訖時間查詢，不取鎖、不寫使用次數。
- 逐單位以 `Math.multiplyExact` 計算折抵，逐列以 `Math.addExact` 累加。
- `NTH_PERCENT` 依單價、列索引排序，固定折抵最便宜的單位。
- 多規則擇折抵最大者；同額沿用 id 字典序較小者。
- 新增九組純計算測試，覆蓋規格 §11.1 (a)–(i)。

## S3

- 訂單計價先套品項促銷，再以品項折抵後金額套用 G07 優惠碼；`discount_amount` 固定為兩者精確加總。
- 訂單、品項與 `order_item_promotions` 同交易保存快照，並新增 `ORDER_ITEM_DISCOUNT` 稽核紀錄。
- `Orders.Order`／`Orders.Line` 加法擴充品項促銷與折抵欄位；清單與對帳路徑維持不額外查促銷快照，避免 N+1。
- POS 現金結帳一律改走兩階段，應收與實收驗證只採用建單後的後端金額。
- 新增訂單整合測試，覆蓋品項促銷與優惠碼順序、金額恆等式、期間／分店條件、快照、稽核與 idempotent replay。

## 下一步

S4：完成總部促銷維護頁與訂單／品項折抵顯示。
