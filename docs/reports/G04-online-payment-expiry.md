# G04 線上付款逾時作廢實作回報

來源規格：`docs/specs/G04-online-payment-expiry.md` v1.0；來源主線：`feature/init-project@53879832c8e91d7d092a46f9544c1745ea15bb15`。

## 階段狀態

- [x] S1 — `V15` schema、`Orders.expireOnline／settleOnline`、設定檢查與付款窗口
- [x] S2 — 作廢排程、回呼異常、作廢後複查與端到端測試
- [x] S3 — 異常清單／結案 API、前端、demo profile、文件與測試
- [ ] 最新 head 的必要 GitHub Actions 全數成功
- [ ] PO 依 `docs/ECPAY.md` 步驟 7–10 完成 stage 實機驗收（不擋合併）

## 驗收與測試對照

| 驗收 | 自動化／證據 |
| --- | --- |
| 1 | Flyway 啟動測試及 `V15__payment_expiry.sql` |
| 2 | `OrderExpiryTest.expireOnlineCancelsOnceAndRestoresReservedStock`、`expireOnlineRejectsIneligibleOrdersAndMissingId` |
| 3 | `OrderExpiryTest.settleOnlineIsIdempotentAndNeverRevivesCancelledOrder` |
| 4 | 既有 `ReconciliationTest`、`HttpWorkflowTest`、`CoffeeIntegrationTest`、`CheckMacTest` 與 G01b 測試均未修改；由完整 verify 覆核 |
| 5 | `PaymentExpiryConfigTest.invalidEnabledConfigurationFailsWithoutLeakingValues`、`disabledExpiryIgnoresExpiryValues` |
| 6 | `PaymentExpiryConfigTest.checkoutWindowAppliesOnlyWhenEnabled` |
| 7 | `PaymentExpiryConfigTest.defaultConfigurationKeepsExpiryDisabled` |
| 8、9、11 | `EcpayExpiryEndToEndTest.unpaidAndSimulatedOrdersExpireButPaidOrderSettles` |
| 10 | `EcpayExpiryEndToEndTest.uncertainQueryKeepsPendingThenLaterNotFoundExpires`、`connectionRefusalKeepsPendingAndRecentOrCashOrdersAreNotQueried` |
| 12 | `EcpayExpiryEndToEndTest.connectionRefusalKeepsPendingAndRecentOrCashOrdersAreNotQueried` |
| 13 | `EcpayExpiryEndToEndTest.callbackBeforeExpiryWinsWithoutAnotherQuery` |
| 14 | `EcpayExpiryEndToEndTest.callbackAfterExpiryIsAcknowledgedAndCreatesOneRefundAnomaly` |
| 15 | `EcpayExpiryEndToEndTest.cancelledManualReconciliationReturnsConflictWithoutQuery` |
| 16–19 | `EcpayExpiryEndToEndTest.recheckRecordsUnpaidPaidMismatchAndGiveUpOutcomes` |
| 20 | `EcpayExpiryEndToEndTest.logsAreSafe`（每個測試後執行） |
| 21 | `PaymentAnomalyApiTest.listScopesUnresolvedAnomaliesAndCapsAtTwoHundred` |
| 22 | `PaymentAnomalyApiTest.resolveValidatesScopeCsrfNoteExistenceAndState` |
| 23 | `PaymentAnomalyApiTest.resolveValidatesScopeCsrfNoteExistenceAndState` |
| 24 | `PaymentAnomalyApiTest.paymentConfigAddsExpiryWithoutRemovingExistingKeys` |
| 25 | `ReconciliationView.dom.test.ts` 兩種清單狀態與結案請求測試 |
| 26 | `OrdersView.dom.test.ts` 的兩個逾時提示測試 |
| 27 | `AuditView.vue` 的 `ORDER_EXPIRED` 選項與前端 build |
| 28 | `EcpayStageProfileTest.profileEnablesOnlyStagePaymentAndReconciliation` |
| 29 | `docs/ECPAY.md`「逾時作廢與作廢後付款」及步驟 7–10 |
| 30 | 本報告 |

## 驗證與規格觀察

- 本機借用同 repository 既有、與 lockfile 相符的 `node_modules` 驗證：`npm test` 通過（14 個檔案、143 個測試），`npm run build` 通過。
- 本機 Maven 解析 Maven Central DNS 失敗，未取得可用的完整 backend verify；同樣以最新 head 的 Actions 為主要證據。
- 實作中未發現需要變更規格的矛盾。異常清單只查 `payment_anomalies` 快照，不跨模組 join；結案不呼叫綠界、不改訂單，也不寫 `audit_log`，符合規格的模組邊界。
