# G20a 菜單與購物車促銷提示施工報告

- 規格：`docs/specs/G20a-promotion-hints.md`
- 主線來源：`feature/init-project@fea0ef498e5170d45b8ed164f4d3b52f459f66bc`
- 分支：`codex/g20a-promotion-hints`
- PR：#72

## 階段

- [x] S1 — 顧客安全促銷投影與 `GET /api/promotions/active`
- [x] S2 — 菜單商品卡促銷提示、折扣文字純函式與端點降級
- [x] S3 — 購物車規則提示、件數門檻與分類跨商品累加

## 驗收對照

- S1：任何已登入者可依分店讀取當下有效規則；回應固定為七個安全欄位。既有總部促銷清單與寫入權限未放寬。
- S2：商品規則優先於分類規則，同一 target 取第一條；售完商品不顯示提示；促銷端點失敗不阻斷菜單。
- S3：購物車依後端相同的件數語意計算門檻，分類規則可跨商品累加；後端回傳真實折抵後隱藏預測提示。
- `promotions.ts` 不含價格、總額欄位或乘法，前端只解釋規則與件數，不計算折抵金額。

## 測試

- 後端真實 HTTP：有效期間、全店／分店規則、未登入、缺少／空白分店、未知分店、精確 JSON key，以及既有總部端點越權回歸。
- 前端純函式：折扣顯示邊界、兩種規則文字、N 件門檻與三種購物車提示。
- 前端 DOM：商品／分類提示、售完優先序、404 降級、一件／兩件門檻、分類跨商品累加、現金第二階段隱藏預測。
- 本地驗證：執行環境未提供 repository checkout，未直接執行。
- 遠端驗證：S1 run #512 completed/success；整份規格以最新 head 的 Verify run 為完成依據。

## 變更邊界

沒有 migration、沒有新第三方相依、沒有修改 `Identity.PERMISSIONS`、`OrderService` 或建立訂單契約。
