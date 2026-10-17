# G03 訂單退款與發票作廢／折讓實作回報

來源：`docs/specs/G03-order-refunds.md`（修訂紀錄 v1.3.1），主線
`7a861b22c7ec6285842b544528c0cd7c2cafd25b`。工作順序為 GAP-ANALYSIS 第30項。
G02 #94 已於 2026-10-17T14:40:29Z 整份合併（7a861b2），V16 已存在。
來源交接 #96；本次執行識別 codex-pr95-g03-20261017T1536Z。

## 施工狀態

- [ ] S1 退款本體：程式與驗收1–11測試已完成，等待最新 head 遠端 verify。
- [ ] S2 發票作廢／折讓：未開始；先12–14，再15–23不可拆排程。
- [ ] S3 端點／報表／前端／文件：未開始。

整份保持 draft，不啟用 auto-merge，不能以 S1 當作完整退款上線。
S2 才會接上退款與發票開立競態防線；本階段未呼叫任何綠界 API 或 DoAction。

## S1 設計與變更

- V17 新增五個 orders 退款欄位、invoice_reversals、五索引、七具名 CHECK；不改 V1–V16。
- ORDER_REFUND 預設只給 MANAGER/HQ；InitialData 的店長清單同步新增，HQ 既有全量清單不用改。
- 退款取 branch→order 鎖；先權限及分店授權，再驗證並原子條件更新；金額僅取 total。
  現金忽略 providerNote；線上退款必填人工退刷紀錄。保留 status、不回補庫存／兌換次數。
- 稽核沿用既有 afterCommit/best-effort Audit，action ORDER_REFUNDED；摘要用既有 summary 欄位。
- 所有 Order 讀取路徑（列表／明細／付款快照）帶退款欄位；paid/refunded 內部掃描為鍵集分頁，無 Controller。
- 班別的 cashRevenue 保留毛額，expectedAmount 使用 addExact/subtractExact 扣本班退款；
  關帳數字不重算。未歸班金額／筆數扣退款時間區間內未歸班退款，筆數可能為負（跨期／跨班退款）。
- 無新依賴、模組邊界變更、CSRF 例外；不改 AGENTS／CLAUDE。

## 規格疑點與處理

1. 頁首仍標 v1.3，以修訂紀錄 v1.3.1 明確的409規則為準，未更動規格。
2. V17 授權以 V2 的 SELECT roles 形式為基礎，加 NOT EXISTS：既有升級測試會先在舊 schema
   執行最新版 InitialData，可能已帶新權限。不防重會違反主鍵；不擴大授權角色。
3. OrderPaginationTest 手建 schema 必須補 nullable 退款欄位（否則新增 mapper 讀取不存在的欄位）；
   只追加欄位、未改斷言。三個 Order fixture 與一個 InvoiceCandidate fixture 只補 null。
4. 規格未要求未歸班獨立退款欄位；現有 Page 的金額／筆數按同一區間扣除，不新增未要求的 API 欄位。

## 驗收對照

| 驗收 | 測試方法 |
| --- | --- |
| 1 | OrderRefundTest.migrationAndDefaultRolesMatchContract；OrderRefundMigrationTest.upgradeFromV16PreservesOrdersAndGrantsExactlyTwoRoles（既有／預先授權兩種） |
| 2、7 | managerRefundsEveryPaidStatusWithoutChangingIt（四狀態、稽核、重複、cash/transition409、列表讀回） |
| 3、6 | onlineRefundRequiresManualProviderNoteAndNeverTrustsAmount；managerRefundsEveryPaidStatusWithoutChangingIt |
| 4 | authorizationCsrfAndCrossBranchDenialsLeaveEverythingUnchanged（真 HTTP／Cookie／CSRF、完整列與班別與audit數量不變） |
| 5 | invalidReasonAndUnpaidCancelledOrdersAreRejected（含G04 expireOnline、200/201字）；四狀態案例的重複退款409 |
| 8 | concurrentHttpRefundsHaveExactlyOneWinnerAndOneAudit（兩HTTP請求、CountDownLatch、恰好200/409） |
| 9、10 | refundDeductsCurrentSessionAndClosedAmountsStayFrozen；unassignedRefundsUseRefundTimeRatherThanOriginalPaymentTime |
| 11 | refundsNeverRestoreStockOrDiscountRedemptions |
| S2前置掃描 | internalRefundCursorIsInclusiveAtInitialBoundaryAndKeysetPaginated |

CashSessionsTest、CoffeeIntegrationTest、HttpWorkflowTest 完全未改。
S2 驗收12–23（含21a–21l）與 S3 驗收24–33未實作／未驗證；不冒稱完成。

## 驗證

本地 backend verify：Maven Central DNS失敗，未進編譯；不是測試通過。
本地 frontend：183測試及build通過。diff --check通過；三支腳本仍100755。
遠端：本提交保存後才查最新 head Checks／Actions／jobs，結果記錄在PR描述。
不以舊 head 或G02的CI代表本次S1通過。
