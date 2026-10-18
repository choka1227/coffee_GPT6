# G03 訂單退款與發票作廢／折讓實作回報

來源：`docs/specs/G03-order-refunds.md` v1.3.2，最新同步主線
`c10041e5978868071d2086b340132b069e350482`（#98，2026-10-18T06:20:16Z 合併）。
初始開工來源為 `7a861b22c7ec6285842b544528c0cd7c2cafd25b`；工作順序為 GAP-ANALYSIS 第30項。
G02 #94 已於 2026-10-17T14:40:29Z 整份合併（7a861b2），V16 已存在。
來源交接 #96；本次執行識別 codex-pr95-g03-20261017T1536Z。

## 施工狀態

- [x] S1 退款本體：fe14fa55的兩組完整CI成功，backend432／frontend183測試及build通過。
- [x] S2a 日期與用戶端（驗收12–14）：13d95e9兩組最新head CI成功，backend470／frontend183及build通過。
- [x] S2b 發票退款掃描／作廢排程（15–23含21a–21l）：c5df720兩組CI成功，backend498／frontend183及build通過。
- [x] S3 端點／報表／前端／文件：驗收24–33完整保存；v1.3.2 解除舊契約矛盾後僅追加兩個 expected keys，01173ace 的兩組完整 CI 全部成功。

S1、S2a、S2b、S3 全部完成。01173ace 完整驗證成功；此文件收尾提交仍須重查其最新 head CI，
成功前保持 draft。無自審或直接合併；工具不能指定 merge 方式時依 AGENTS 由 Claude 審核後處理。
S2 已接上退款與發票開立競態防線；系統不呼叫 DoAction，官方 stage 實測仍待 PO。
以下各階段驗證紀錄保留當時狀態，最新結果以 PR #97 描述為準。

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

CashSessionsTest、HttpWorkflowTest 完全未改；CoffeeIntegrationTest 僅依 v1.3.2 §14.10
於 reportResponseKeepsExactContractKeys 期望集合追加 refundAmount、refundOrders，精確斷言及其餘檔案內容不變。
S3最新head驗證結果以PR描述為準；官方stage實機仍為待PO驗收，不擋合併。

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

## S3 端點、報表、畫面與文件

來源仍為7a861b2的G03；執行識別codex-g03-s3-20261017T183554Z，續同PR #97。
本輪#95的Claude回覆承認原退件矛盾、尚未推修正，head仍d28a25ed，保留REQUEST_CHANGES不重送。

- 新增作廢失敗清單與重試：沿用PAYMENT_RECONCILE／非顧客／分店範圍；最多200筆、倒序。
  404沿用「找不到這筆發票」、409依G03指定文案；寫入需CSRF。重試不呼叫provider，保留kind/inflight，
  attempts歸零、uncertain=true、清持有者，後續仍先查詢。
- Invoice mapper用payments內部LEFT JOIN補reversalKind/reversalStatus，沒有列均null；訂單授權仍先orders.get。
  §7要求顯示折讓號與失敗原因，但§6.7 record只列kind/status，故補nullable allowanceNo/reversalLastError，
  只攜帶既有作廢列已遮罩的值，不捏造單號或更改原invoice.lastError語意。NONE回應新增欄位仍全部null。
- 月報僅加refundAmount/refundOrders，獨立以refunded_at時間與同一branch篩選；舊paid_at條件／毛額／商品／折抵不改。
  為保留既有聚合「固定7次SQL」防線，退款總額／筆數用同一totals SQL中的獨立scalar subquery取得，
  不增加SQL round-trip、不讓付款區間限制跨月退款。不是在前端計算退款。
- ReportAggregationTest的手建schema追加nullable refunded_at、insert明列原欄位、完整expected map追加兩個0值；
  原完整輸出及7次SQL斷言全保留，只同步新欄位契約。另新增退款前後所有舊欄位相等、跨月與跨店案例。
- OrdersView顯示退款資訊、整筆退款對話框、線上必填退刷紀錄，送出只有reason/providerNote而無金額；
  canRefund純函式守權限／已付款／未退款／未取消，退款後隱藏推進。CashSessions/Reports顯示退款與扣除後數字，
  無退款月報整行隱藏；對帳顯示失敗與重試；Audit新增ORDER_REFUNDED選項，權限標籤追加ORDER_REFUND。
- docs/ECPAY.md新增人工退刷限制、排程界線、§11六步及空白驗收紀錄表；官方stage尚未執行，列待PO驗收。
- 無新migration／依賴／設定／模組邊界，V1–V17及AGENTS/CLAUDE不改。

### S3 驗收對照

| 驗收 | 測試方法 |
| --- | --- |
| 24 | InvoiceApiTest.reversalListAuthorizationScopeSortAndTwoHundredLimit；reversalRetryEnforcesCsrfScopeStateAndPreservesKindAndBoundary（真HTTP、Cookie、CSRF、403不變性、404/409文案、200筆上限、保留方式/界線且先查） |
| 25 | invoiceDetailsExposeNullOrFailureReversalFieldsWithoutChangingInvoiceStatus；reversalRetryEnforcesCsrfScopeStateAndPreservesKindAndBoundary（成功單號） |
| 26 | ReportAggregationTest.refundsUseRefundMonthAndPreserveEveryExistingFieldAndScope；既有完整輸出及固定7次查詢測試原斷言保留 |
| 27 | refund.spec.ts canRefund四種可退款狀態及四種拒絕條件（包含epoch0） |
| 28 | Refund.dom.test.ts 已退款資訊/推進隱藏、收銀顧客入口隱藏、現金/線上文案、200字/空白、POST URL/body無金額/CSRF/刷新、VOID/ALLOWANCE/PENDING/FAILED/NONE與顧客隱藏原因 |
| 29 | CashSessionsView.dom.test.ts 目前班別與歷史退款；ReportsView.dom.test.ts 退款兩案例（有/無） |
| 30 | ReconciliationView.dom.test.ts 顯示訂單發票方式原因及200筆提示，POST重試後刷新 |
| 31 | AuditView.dom.test.ts 稽核篩選包含退款 |
| 32 | docs/ECPAY.md「退款與發票作廢／折讓」及「待PO驗收」六步記錄表；未冒稱實測 |
| 33 | 本檔包含各階段、1–33方法映射、未改測試與疑點 |

指定既有CashSessionsTest、HttpWorkflowTest及TimeZoneGuardTest／EcpayNoEgressGuardTest全未改。
CoffeeIntegrationTest 唯一變更為 v1.3.2 §14.10 允許的兩個 expected keys，保留 containsExactlyInAnyOrder。
InvoiceApiTest僅追加案例、helper與新fixture的AfterEach清理；G02原案例／reset／斷言未改。
本地backend verify因Maven Central DNS失敗仍未編譯；前端最新結果及遠端最新head CI以PR描述為準。
首輪DOM測試抓到load只刷新開票清單、未初載作廢清單，已修正實際load流程，未放寬測試。

### S3 歷史阻擋（已由 v1.3.2 解除）：報表精確舊契約矛盾

2026-10-17，head693fb56daf042f532c59c118bd1a972c969661d2的兩組Checks均failure。
Actions39059384223/job117474033821與39059379394/job117474019325已完成；第一組log證明
backend502 tests、Failures1、Errors0、Skipped0；前端205及build成功，新API／聚合測試通過。
唯一失敗為CoffeeIntegrationTest.reportResponseKeepsExactContractKeys（CoffeeIntegrationTest.java:123）：
actual增加refundAmount/refundOrders，被containsExactlyInAnyOrder判為unexpected。

- G03 §6.5明訂MonthlyReport尾端新增long refundAmount/refundOrders，§13驗收26要求退款欄位正確。
- 同份規格驗收11又要求CoffeeIntegrationTest不修改即通過；其原精確key集合不容納任何新增欄位。
- 本輪沒有修改CoffeeIntegrationTest、沒有刪掉退款欄位、沒有跳過／放寬測試，亦不為轉綠偷偷改成
  「無退款時隱藏JSON兩欄」等未定義的序列化契約。
- 所需修正由PM/SA決定並合併規格澄清：若維持新增API欄位，明確允許只對精確expected key集合
  追加這兩欄（保留其餘所有斷言）；或明訂兼容的JSON契約及其驗收，再依已合併規格實作。
  未澄清前不可同時滿足兩個要求，S3不能標完成、PR不轉ready、不auto-merge。
- 這是需求／驗收矛盾，不是Maven DNS停工；已安全保存S3程式／測試／文件，沒有轉去另一份規格。

本段為693fb56驗證結果；追加本報告的文件提交仍須查該最新head CI，不沿用舊head綠燈。

### S3 v1.3.2 續作

2026-10-18，執行 codex-g03-s3-finish-20261018T0635Z，確認 #98 實際合併且主線含 §14.10 後，
將 c10041e merge 進原 codex/g03-order-refunds（不 rebase、不 force push）。
僅於 CoffeeIntegrationTest.reportResponseKeepsExactContractKeys 尾端追加兩個欄位名稱；原24欄、
containsExactlyInAnyOrder、同方法其他內容及該檔其餘測試逐字不變。JSON兩欄仍永遠輸出。
驗收26的退款前後舊欄位相等／跨月／跨店測試保留；未新增需求、依賴或 migration。
本次本地 frontend 205 測試與 build 通過；backend verify 因 Maven Central DNS 失敗，未進編譯。
diff --check 通過，三支腳本仍 100755。遠端最新 head Checks／Actions／jobs 結果會回填 PR #97；
01173ace 的 Checks 117608103476／117608094047 全部 completed/success；
Actions 39104757262／39104754070 與對應 jobs 全部成功，完成 S3 遠端驗證。
此後僅更新本報告完成狀態，仍以最終提交 Checks／Actions／jobs 全綠才轉 ready，不沿用前版綠燈。
官方 stage 六步未執行，依 §11 不阻擋合併，不能冒稱實測。
