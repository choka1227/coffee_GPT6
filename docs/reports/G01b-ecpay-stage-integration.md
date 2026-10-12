# G01b 綠界 stage 串接實作報告

來源規格：`docs/specs/G01b-ecpay-stage-integration.md` v1.1
來源主線：`feature/init-project` @ `7a2c860122f991fe5d20df4f9b3fe1d07c936896`

## 階段狀態

- [x] S1 — stage 防線與查單端點覆寫
- [x] S2 — 本機假綠界伺服器與端到端測試
- [x] S3 — demo profile、啟動腳本與文件

## S1 完成內容

- `ecpay.environment` 僅接受大小寫完全一致的 `stage`，即使金流停用也會驗證。
- AIO checkout 固定使用綠界 stage URL；後端、前端與 CSP 移除正式網域可執行路徑。
- 新增 `ecpay.query-url`，僅接受精確 stage URL 或具明確連接埠、無 user info/query/fragment 的 HTTP loopback URL。
- 非 HTTPS `app.public-url` 改為啟動 WARN，不阻止本機 demo；訊息不輸出金鑰。
- 排程對帳新增每輪 INFO 摘要與單筆失敗 WARN；WARN 僅含訂單編號與例外類別。
- 新增後端 stage guard／log hygiene 測試與前端付款 URL allowlist 測試。

前端測試檔使用 `ecpay.dom.test.ts`，而非規格文字中的 `ecpay.test.ts`。原因是現有 Vitest 專案以 `*.dom.test.ts` 路由到 jsdom；測試需要 `document` 與 `HTMLFormElement`，此命名讓測試納入既有測試入口，未新增依賴或改測試設定。

## S2 情境對應

S2 新增綁定 `127.0.0.1:0` 的 JDK `HttpServer` 假站。假站會先驗證我方送出的
MerchantID、20 碼訂單號、180 秒內時間戳與 CheckMac，再依情境回傳簽章表單；
`close()` 會停止伺服器、中斷自持的 daemon executor 並等待 worker 結束。

§10.2 八個情境與實際測試方法名：

| 情境 | 測試方法 |
| --- | --- |
| `PAID` | `paidQueryIsConfirmedOverRealHttp` |
| `NOT_FOUND` | `notFoundQueryRemainsUnpaidOverRealHttp` |
| `AMOUNT_MISMATCH` | `amountMismatchQueryIsRejectedOverRealHttp` |
| `BAD_MAC` | `badMacQueryIsRejectedOverRealHttp` |
| `TIMEOUT` | `timeoutQueryIsRejectedOverRealHttp` |
| `CONNECTION_REFUSED` | `connectionRefusedQueryIsRejected` |
| `HTTP_500` | `http500QueryIsRejectedOverRealHttp` |
| `REDIRECT_TO_PRODUCTION` | `redirectToProductionIsNotFollowed` |

另由 `checkoutUsesDatabaseTotalAndManualReconcileUsesRealLoginAndCsrf` 驗證 DB 金額、
真實登入／CSRF 與跨店權限；`callbacksCoverSuccessReplayMismatchMacSimulationFailureAndDuplicateParameter`
驗證七種回呼；`lostCallbackIsRecoveredThenLateCallbackIsIdempotent` 驗證排程補救、同交易遲到回呼
與不同交易編號拒絕。`EcpayNoEgressGuardTest` 掃描所有後端測試，並用內建不安全樣本證明規則有效。

## S3 完成內容

- 新增獨立 `ecpay-stage` profile，使用綠界官方公開測試特店、預設啟用金流與對帳；未污染 `dev` profile。
- `start-demo.sh` 預設啟用 `dev,ecpay-stage`，並維持 `100755`。
- 重寫 `docs/ECPAY.md`，移除 production 切換與過期的「沒有主動查單」敘述，補齊 demo 開關與六步 stage 實機驗證。
- `EcpayStageProfileTest` 以 loopback 假站啟動完整 context，驗證 profile 有效值及 yml 的 120000ms 預設值。

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
- Maven S1–S3 指定測試：未執行完成；`repo.maven.apache.org` 暫時無法解析，Spring Boot parent POM 無法下載。
- `git diff --check`：通過。
- `start-demo.sh`：`100755`。
- S2 head `57a29052314187a4a53f57f352c580eff0c9b88d`：GitHub Actions run #608 completed / success；frontend test、frontend build、backend Maven verify 全部通過。

## 實作中發現的規格問題

- 規格指定的前端測試檔名與現有 Vitest jsdom include 規則不一致；已採上述檔名調整，測試內容與驗收 7 不變。
- 未發現需求、模組邊界、金額、權限、CSRF、Flyway 或第三方依賴衝突。

## 下一步

等待最新 head 遠端 CI；全綠後依流程將 PR 轉 ready for review。
