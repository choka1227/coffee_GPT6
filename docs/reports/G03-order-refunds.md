# G03 訂單退款與發票作廢／折讓實作回報

來源：`docs/specs/G03-order-refunds.md`（修訂紀錄 v1.3.1），主線
`7a861b22c7ec6285842b544528c0cd7c2cafd25b`。工作順序為 GAP-ANALYSIS 第30項。
G02 #94 已於 2026-10-17T14:40:29Z 整份合併（7a861b2），V16 已存在。
來源交接 #96；本次執行識別 codex-pr95-g03-20261017T1536Z。

## 施工狀態

- [x] S1 退款本體：fe14fa55的兩組完整CI成功，backend432／frontend183測試及build通過。
- [x] S2a 日期與用戶端（驗收12–14）：13d95e9兩組最新head CI成功，backend470／frontend183及build通過。
- [ ] S2b 發票退款掃描／作廢排程（15–23含21a–21l）：程式與測試已保存，等待最新head遠端verify；整段一併驗證。
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
   BranchDaySettingsMigrationTest 的權限總數14改15並明確斷言ORDER_REFUND，原BRANCH_HOURS_OVERRIDE斷言保留，
   這是新增權限的精確契約同步，未刪除或放寬測試。
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
| 9、10 | refundDeductsCurrentSessionAndClosedAmountsStayFrozen；unassignedRefundsUseRefundTimeRatherThanOriginalPaymentTime；refundAndCloseSerializeOnBranchInEitherOrder（兩種鎖取得順序） |
| 11 | refundsNeverRestoreStockOrDiscountRedemptions |
| S2前置掃描 | internalRefundCursorIsInclusiveAtInitialBoundaryAndKeysetPaginated |

CashSessionsTest、CoffeeIntegrationTest、HttpWorkflowTest 完全未改。
S2b最新head驗證結果以PR描述為準；S3驗收24–33尚未實作／未驗證。

## 驗證

本地 backend verify：Maven Central DNS失敗，未進編譯；不是測試通過。
本地 frontend：183測試及build通過。diff --check通過；三支腳本仍100755。
遠端：本提交保存後才查最新 head Checks／Actions／jobs，結果記錄在PR描述。
不以舊 head 或G02的CI代表本次S1通過。

首輪遠端已完成編譯並跑430 tests，發現精確權限數量契約及兩處新測試前置錯誤：匿名POST未先取CSRF，
因此先被CSRF擋成403；付款掃描從0開始只取200筆，拿不到數千筆demo資料後的新訂單。
已保持401／單筆退款欄位的原斷言，分別補匿名CSRF及精確paid_at範圍fixture，未放寬安全或分頁條件。
另補退款／交班兩種鎖順序測試；修正後結果一律以最新head CI為準。

## S2a 日期與作廢／折讓用戶端

來源仍為主線7a861b2的G03；本次執行識別codex-g03-s2a-20261017T1635Z。
補掃#95沒有新提交或新實質證據，保留既有REQUEST_CHANGES、不重送審查；它不阻擋G03施工。

- `InvoiceDeadline.canVoid(invoiceDate, now)`使用Asia/Taipei，雙月期結束後次月13日00:00為排他界線，
  即12日23:59:59.999可作廢、13日起走折讓。可解析DB日期時間的前10碼；無效日期／缺時間／未來日期保守false。
- `EcpayInvoiceClient.invalid/allowance`帶呼叫端timestampSec（依v1.3 §6.6第4步），共用現有AES信封、
  stage限制、HTTP逾時及不跟隨redirect。作廢日期取前10碼，折讓三處金額均使用呼叫端的發票快照amount。
  null／空白email不放NotifyMail並設AllowanceNotify=N；有email設E。
- `NotifyMail`與`Reason`加入provider拒絕訊息遮罩欄位，既有金鑰／MerchantID遮罩保留。
- FakeEinvoiceServer保留Issue/GetIssue情境行為，只追加Invalid/Allowance路徑與獨立作廢狀態／剩餘額。
  支援VOID_PAST_DEADLINE、SUCCEED_THEN_TIMEOUT、DELAYED_EFFECT及套用／丟棄延後結果，
  兩個新路徑皆支援掛勾。超額／已作廢不再折讓；新情境ECHO_SENSITIVE驗證回應衛生。
- 無新migration／依賴／端點／設定／權限變更，不修改InvoiceService，不接上排程，不呼叫DoAction。

### 驗收方法

| 驗收 | 測試方法 |
| --- | --- |
| 12 | InvoiceDeadlineTest.allBimonthlyPeriodsAcceptTwelfthButNeverThirteenth（12個月）；leapDayAndCrossYearUseTaipeiRatherThanUtcCalendar；malformedDateNeverAuthorizesVoid；missingClockAndFutureInvoiceNeverAuthorizeVoid |
| 13 | InvoiceReversalClientTest.encryptedRequestsMatchSpecAndQueryReflectsProviderState（兩路徑）；nullEmailOmitsNotificationMailAndUsesSameFullAmount |
| 14 | bothOperationsReturnUnknownOnUncertainTransportOrEnvelope（七情境×兩路徑）；disabledInvalidInputAndConnectionFailuresDoNotLeakOrSendUnexpectedRequests |
| 假伺服器與衛生補強 | successThenTimeoutIsVisibleToQueryWithoutResending；delayedEffectCanBeAppliedOrDroppedWithoutChangingLegacyIssueScenarios；voidDeadlineRefusalAndSensitiveEchoAreSafe |

### 疑點與下一階段

- 日期格式依已合併規格yyyy-MM-dd，未執行官方stage實機驗證；不能將假伺服器結果當官方驗收。
- 無效／未來發票日期保守不授權作廢；用戶端無效發票欄位或非正金額回Unknown且不連外。
  `allowance`的Ok保留IA_Allow_No原欄位；S2b須依規格驗證成功欄位並寫回，這裡不假造作廢排程成功。
- S2b完整處理退款掃描、issueOne三條規則與reverseOne；驗收15–23全部尚未完成。
- 本地frontend183測試與build通過；backend verify仍因Maven Central DNS失敗未編譯。
  本次remote verify尚待push後查最新head，不沿用fe14fa55的綠燈。

首輪S2a遠端完成編譯並執行470 tests；三個新測試錯把Unknown當成「沒有副作用」。
BAD_CIPHER／BAD_DATA_JSON／TRANS_CODE_ERROR假伺服器在操作成立後破壞回應，發票實際已作廢，
因此原本查詢預期未作廢不正確。修正測試明確驗證三情境已作廢、其他四情境未作廢，
並追加剩餘金額未變（後續折讓因發票已作廢被拒）；Unknown斷言保留，不改假伺服器或生產行為去掩蓋不明結果。

## S2b 退款與發票排程

執行識別codex-g03-s2b-20261017T174225Z；同一主線來源與PR #97，沒有開另一份規格。

- REFUNDED初始游標為T0-lag，之後退款掃描和游標推進同一短交易；主鍵競態回滾、不跳過訂單。
  確定沒有發票才SKIPPED；ISSUED或持有者／不明／在途列建立作廢列。
- issueOne以條件更新判定退款可否跳過，不信任認領前的快照。拒絕且退款改SKIPPED/NONE；
  所有ISSUED路徑提交後再重讀退款狀態，確保作廢列存在。保留G02遲到Ok補償與NONE重設。
- 作廢批次join僅取ISSUED，避免FAILED/PENDING發票堵住批次。reverseOne使用NOT_SUPPORTED包住HTTP、
  REQUIRES_NEW短交易、UUID持有者、認領後重讀、送出前持久化timestamp相同的inflight_until。
  不明先查GetIssue，界線未過不重送且不加attempts；作廢拒絕只改走折讓一次。
  DONE/確定拒絕清界線；Unknown保留界線與實際送出kind，達上限保留uncertain；失去token丟棄回應。
- 額外防守性檢查：查詢的發票號及金額必須符合快照，作廢狀態／餘額需可判讀；
  折讓成功必須有非空且<=20碼的IA_Allow_No，否則當Unknown、下一輪查詢，不能假造成功。
- INFO摘要只增加作廢／折讓／失敗數量；無新migration、依賴、權限、設定或端點，不呼叫DoAction。
  email仍從Orders API取得，不跨模組查orders表。S3失敗清單／重試／前端仍未接上。

### S2b 驗收對照（InvoiceRefundSchedulerTest）

| 驗收 | 測試方法 |
| --- | --- |
| 15 | refundCursorBoundaryAndLagAreInclusiveAndDurable |
| 16 | issuedRefundVoidsAndReportsAggregate |
| 17 | expiredInvoiceUsesFullAllowanceWithoutVoid |
| 18 | refusedVoidFallsBackOnceInSameRound |
| 19 | reversalSucceedThenTimeoutQueriesInsteadOfResending（作廢／折讓） |
| 20 | refundBeforeEnqueueSkipsWithoutProviderRequest；certainPendingAndRejectedInvoiceSkipWithoutAnyNewRequest；missingInvoiceInformationOnRefundSkipsWithoutNetwork |
| 21、21a | refundDuringIssueIsCaughtEvenWhenRefundCursorAlreadyPassed（掃描／游標已走過兩種） |
| 21b、21c | uncertainIssueAfterRefundConfirmsOrCreatesUniqueInvoice（成功逾時／查無後開立） |
| 21d | uncertainRefundRejectedIssueEndsAsNone |
| 21e | unresolvedFailedInvoicesDoNotStarveIssuedReversal（batch=2，三筆FAILED在前） |
| 21f | staleWorkerCannotSendWhileOwnerIsInFlightThenRefundSkipsRejectedInvoice |
| 21g | deadOwnerRefundWaitsForLeaseThenQueriesExistingInvoice |
| 21h | lateSuccessfulIssuePullsSkippedNoneBackIntoReversal |
| 21i | reversalClaimExcludesConcurrentWorkerAndHttpDoesNotHoldTransaction |
| 21j | delayedIssueRefundNeverSkipsAndOnlyResendsAfterBound（apply/drop） |
| 21k、21l | delayedReversalPreservesKindAndBoundWithoutEarlyResend（作廢／折讓） |
| 22 | deterministicRejectionsAndUnknownAttemptCapHaveDistinctCertainty |
| 23 | 全案例cleanLogs；queryRejectedFailsWithoutMutationAndSensitiveProviderMessagesStaySanitized；既有TimeZoneGuardTest／EcpayNoEgressGuardTest未改 |
| 額外回歸 | lostReversalOwnerDiscardsResponseAndNextOwnerQueriesEffect；malformedAllowanceSuccessRemainsUncertainUntilQueryProvesEffect；malformedQueryDoesNotAuthorizeProviderMutation |

本地frontend183測試通過，build另列最新結果；backend verify仍因Maven Central DNS在解析parent POM前失敗，
未進編譯，不宣稱backend本地通過。將整段實作與測試push交最新head Checks、Actions與jobs驗證。
官方stage實機尚未驗證，仍留S3文件驗收紀錄，不把假伺服器冒充官方結果。
