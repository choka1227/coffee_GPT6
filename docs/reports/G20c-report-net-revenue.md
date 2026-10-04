# G20c 報表淨營收一致化施工報告

- 規格：`docs/specs/G20c-report-net-revenue.md`
- 主線來源：`feature/init-project@e1f4a4df6103b0119610697a82e752e27ef0cefe`
- 分支：`codex/g20c-report-net-revenue`
- PR：#69

## 階段

- [x] S1 — 對帳投影的優惠碼折抵別名修正與回歸測試
- [x] S2 — 報表 API 新增品項／優惠碼折抵、商品淨營收與分類淨額
- [x] S3 — 前端表格、摘要、分類圖、今日排行、CSV、型別、fixture 與 DOM 測試

## 驗收對照

- S1：對帳候選同時命中品項促銷與優惠碼時，優惠碼折抵與訂單總折抵分別投影。
- S2：保留毛額欄位；新增 `net_revenue`、`item_discount`、`categoriesNet` 與淨額毛利，未增加 SQL 往返。
- S3：商品表使用淨營收，折抵為零顯示 `—`；摘要拆分兩種折抵；分類圖改用 `categoriesNet`；CSV 標頭明記口徑。

## 審查修正

- 補上 `topToday[]` 的實際今日資料契約測試：固定驗證 `id`、`name`、`quantity`、`revenue`、`net_revenue`，明確排除沒有消費端的 `item_discount`，並釘住毛額 200、品項促銷折抵 50、淨額 150。
- 既有測試資料有三處算術修正：`A-1` 的 `discount` 由 10 改為 0、`B-2` 由 20 改為 0，API 契約的 `discount` 預期值由 30 改為 0。原 fixture 的 `total` 與「商品毛額 − 折抵」不相等，會使規格 §5.2 的淨額恆等式在主 fixture 上無法成立。
- 上述 fixture 修正偏離 G20c v1.1 驗收 10 原本的「既有案例原封不動」文字；規格已在 v1.2 明確校正這項錯誤。非零折抵覆蓋沒有流失：折抵案例仍釘住 `itemDiscount=150` 與 `codeDiscount=20`。

## 驗證

- S1 最新遠端驗證：Verify run #479 completed/success。
- S2 最新遠端驗證：Verify run #487 completed/success。
- S3：包含 `ReportsView.dom.test.ts`；提交後以最新 head 的前端測試、建置與 Maven verify 為完成依據。
- 本地驗證：執行環境未提供 repository checkout，未重複執行；以 GitHub Actions 最新 head 為主要證據。

## 變更邊界

沒有 migration、沒有新第三方相依、沒有改動既有毛額欄位語意、權限或資料範圍。
