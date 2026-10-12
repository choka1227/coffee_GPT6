# G01b 綠界 stage 串接實作報告

來源規格：`docs/specs/G01b-ecpay-stage-integration.md` v1.1
來源主線：`feature/init-project` @ `7a2c860122f991fe5d20df4f9b3fe1d07c936896`

## 階段狀態

- [x] S1 — stage 防線與查單端點覆寫
- [ ] S2 — 本機假綠界伺服器與端到端測試
- [ ] S3 — demo profile、啟動腳本與文件

## S1 完成內容

- `ecpay.environment` 僅接受大小寫完全一致的 `stage`，即使金流停用也會驗證。
- AIO checkout 固定使用綠界 stage URL；後端、前端與 CSP 移除正式網域可執行路徑。
- 新增 `ecpay.query-url`，僅接受精確 stage URL 或具明確連接埠、無 user info/query/fragment 的 HTTP loopback URL。
- 非 HTTPS `app.public-url` 改為啟動 WARN，不阻止本機 demo；訊息不輸出金鑰。
- 排程對帳新增每輪 INFO 摘要與單筆失敗 WARN；WARN 僅含訂單編號與例外類別。
- 新增後端 stage guard／log hygiene 測試與前端付款 URL allowlist 測試。

前端測試檔使用 `ecpay.dom.test.ts`，而非規格文字中的 `ecpay.test.ts`。原因是現有 Vitest 專案以 `*.dom.test.ts` 路由到 jsdom；測試需要 `document` 與 `HTMLFormElement`，此命名讓測試納入既有測試入口，未新增依賴或改測試設定。

## S2 情境對應

尚未施工；完成 S2 時在此補上 §10.2 八個情境與實際測試方法名：

| 情境 | 測試方法 |
| --- | --- |
| `PAID` | 待補 |
| `NOT_FOUND` | 待補 |
| `AMOUNT_MISMATCH` | 待補 |
| `BAD_MAC` | 待補 |
| `TIMEOUT` | 待補 |
| `CONNECTION_REFUSED` | 待補 |
| `HTTP_500` | 待補 |
| `REDIRECT_TO_PRODUCTION` | 待補 |

## 既有測試完整性

驗收 17 指定的既有測試均未修改：

- `ReconciliationTest`
- `HttpWorkflowTest`
- `CoffeeIntegrationTest`
- `CheckMacTest`

目前本機 Maven 因 Maven Central DNS 解析失敗，尚無法執行上述後端測試；待最新 head 遠端 CI 補足驗證。

## 驗證紀錄

- `npm test -- --run src/modules/payments/ecpay.dom.test.ts`：通過，1 個檔案、3 個測試。
- `npm run build`：通過。
- Maven S1 指定測試：未執行完成；`repo.maven.apache.org` 暫時無法解析，Spring Boot parent POM 無法下載。
- `git diff --check`：通過。

## 實作中發現的規格問題

- 規格指定的前端測試檔名與現有 Vitest jsdom include 規則不一致；已採上述檔名調整，測試內容與驗收 7 不變。
- 未發現需求、模組邊界、金額、權限、CSRF、Flyway 或第三方依賴衝突。

## 下一步

施工 S2：新增 JDK `HttpServer` 假綠界、八個查單情境、回呼與遺失回呼補救端到端測試，以及 no-egress guard。
