# G01a S1 實作進度

來源：docs/specs/G01a-reconciliation-fixes.md v1，主線 9251f237247eb6298102405a54c0a437f0fedbe0。

## 設計與範圍

修改 ReconciliationService.java：金額解析失敗保留 null；非空交易編號仍驗證格式。
未付款、模擬付款先分流；金額不符保留兩邊金額；確認入帳才要求交易編號非空。
修改 ReconciliationTest.java：新增空值、缺漏、格式與溢位案例，直接驗證訂單交易編號未被寫成空字串。
同店無 CSRF 預期 403，有 CSRF 預期 200；跨店帶 CSRF 仍 403。
無 migration、無 API 回應變更、無模組依賴變更。callback 不變。

## 假設與疑點

依規格，非入帳路徑容許缺少金額，但非空且格式不合法的 TradeNo 仍拒絕。
本階段未變更 PaymentDate 與 pending()；留待 S2。

## 驗證

- frontend npm ci + npm run build：通過。
- backend ./mvnw -B -ntp verify：未能執行測試；Maven Central DNS 解析失敗。
- 環境代理重試亦連線遭拒，未調整依賴或跳過測試。
- CSRF 暫停保護的反向驗證：尚未執行，受上述後端建置阻擋。
- 既有併發、callback 冪等、跨店測試保留，等待 CI 全套 verify。
- backend/mvnw、scripts/build.sh、start-demo.sh 均為 100755。
- 綠界 stage 真實端到端仍未驗證，功能預設停用不變。

## 施工進度

- [ ] S1：程式與測試完成；待 CI 及 CSRF 反向驗證。
- [ ] S2：尚未開始（時間 fallback、台北日期報表驗證、pending 上限及固定查詢數）。

本次實作：G01a。尚待實作：G01a S2、G13（依 GAP 工作順序）。
PR 維持 draft，不啟用 auto-merge。

## S2 續作：付款時間 fallback
已依 §4.1 修正未來、epoch 零值及負值付款時間，保留 CONFIRMED 並以查核時間入帳，
detail 明確註記。新增測試驗證狀態、時間範圍與歷史紀錄。
不改 schema、API、依賴、callback；既有缺漏日期測試保留。
S1 已由 head 585222d 的 CI #76 verify 補足一般測試；手動 CSRF 反向驗證仍未執行。
S2 §4.3 台北日期報表與 §4.2 pending 上限／固定查詢數尚未實作。
本地 Maven Central DNS 仍失敗，新增修改須以新 head 遠端 CI 驗證，不能沿用 #76。

## S2 續作：台北日期報表驗收
新增 §4.3 測試：2021/03/01 00:30:00 台北時間（UTC 仍是二月），
驗證三月一日 daily 營收與訂單數、二月營收為零、查核當天營收為零。
僅新增測試與本報告，不修改報表實作、schema 或模組依賴。
前一 head e6817ec 的 CI #78 completed/success；新 head 須重新確認 CI。
下一步為 §4.2 pending 200 筆上限、固定查詢數及 truncated API/UI。
