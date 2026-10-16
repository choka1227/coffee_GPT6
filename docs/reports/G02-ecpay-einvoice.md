# G02 電子發票實作進度

來源：`docs/specs/G02-ecpay-einvoice.md` v1.3.1；主線
`671df5c127560094b1effd626b4597a9c4b421d5`（PR #92，2026-10-16 合併）。
工作順序：GAP-ANALYSIS 第 29 項；G01b #87、G04 #90 已合併。
交接：[Issue #93](https://github.com/choka1227/coffee_GPT6/issues/93)。

## 施工進度

- [x] S1 完成；26f5963 兩組遠端 CI 綠（後端327 tests）
- [ ] S2a AES、用戶端、假伺服器、驗收7–11 已實作，等待最新 head CI
- [ ] S2b 開立排程與驗收12–22（尚未開始，依規格§12於用戶端／排程間切分）
- [ ] S3 端點、前端、demo profile、完整操作文件（尚未開始）

## S1 設計與範圍

- V16 新增訂單四個發票欄位、invoices、invoice_cursors、七個具名 CHECK、
  一個具名 UNIQUE、兩個索引。V1–V15 不修改；游標不初始化。
- Orders.Create／Order 追加 InvoiceRequest；內部 paid candidate API 使用
  `(paid_at,id)` 鍵集分頁，不依狀態篩選，不暴露 HTTP 端點。
- 訂單建立驗證／正規化發票；無關欄位清空，指紋包含有效發票資訊。
- invoice-required 預設 false，不啟用任何發票排程或連外功能。
- ReconciliationService 的 verify 接受呼叫端 now，兩個呼叫點皆傳入既有 now；
  原有測試斷言不改。ReconciliationTest 唯一修改是 Create 尾端補 null，
  與規格 §6.1／驗收 2 的原始碼相容例外一致。
- 模組相依、金額計價、權限與 CSRF 規則不變；零新第三方相依。

## 實作補充與規格疑點

1. 舊 Create.toString() 是 request_hash 的輸入。新增 record 欄位即使為 null，
   也會改變舊 hash。因此 invoice=null 時保留原字串表示，確保部署前既有重試鍵仍可重用；
   invoice 非 null 時採新完整表示。新增舊指紋回歸案例。
2. 原 fingerprint 衝突使用 Problem.check（400），且 CoffeeIntegrationTest 明確釘住。
   發票請求衝突依 G02 驗收 5 回 409（新舊任一請求帶發票皆算）；兩者均不帶發票時
   保留 400，符合 S1 舊行為不變的要求，不修改 Problem.check 或舊斷言。
3. OrderPaginationTest 手建最小 orders 表，必須追加四個 nullable 發票欄位；
   僅擴充 fixture schema，不變更任何原有斷言或查詢次數限制。
4. Orders.Order 直接建構的三支既有測試也只在尾端補 null。
5. 規格驗收 2 寫 HTTP 201，但主線 OrderController 實際回 200，既有測試亦要求 200。
   S1 保留既有 200（不改端點契約），新增測試驗證舊 payload 成功與 invoice=null。
   此為規格描述與主線的差異，請 Claude 覆核並於後續規格更正。

## S1 驗收對照

| 驗收 | 測試方法／證據 |
| --- | --- |
| 1 | OrderInvoiceRequestTest.migrationCreatesNamedConstraintsAndIndexesWithoutInitializingCursor；全部 Spring context 經 Flyway 套用 V16 |
| 2 | legacyOrdersAndFingerprintsRemainCompatible；oldHttpPayloadWorksWhileRequiredInvoiceReturnsExact400；既有 Create 呼叫只補 null |
| 3 | rejectsEveryInvalidInvoice（16 組參數）；HTTP 必填錯誤精確 400；InvoiceRequestValidationTest 純單元測試覆蓋正規化、必填旗標及「非必填仍須驗證已提供欄位」 |
| 4 | persistsNormalizedInvoiceInAllOrderProjections（4 組，涵蓋三類及邊界） |
| 5 | invoiceIsPartOfIdempotencyFingerprint；legacyOrdersAndFingerprintsRemainCompatible |
| 6 | paidCandidateCursorUsesTimeThenIdAndInclusiveUpperBound |
| 6a | 原 ReconciliationTest；verify 方法不再自行讀系統時間 |

## 本地驗證（2026-10-16）

- frontend npm ci、npm run test：14 檔／143 tests 全數通過。
- frontend npm run build：通過；僅既有 chunk 大小警告。
- backend ./mvnw -B -ntp verify：Maven Central DNS 暫時解析失敗，
  Spring Boot parent 3.5.16 無法下載，未進入編譯／測試；不視為程式測試成功。
- git diff --check 通過；三支腳本保持 100755；未提交產物、node_modules 或 .env。
- 推送前遠端主線 migration 最高仍 V15，無其他 open PR／codex 分支佔用 V16。

## 遠端 CI

首個 S1 head b919cde 的兩組 CI 均完成編譯，但新增測試的反射工具呼叫因泛型推導
選到 Class overload 而產生 ClassCastException。已將解代理結果先存入 Object，再呼叫
物件 overload；未放寬／跳過測試。最新修正 head 仍需 Checks API 與 Actions jobs 驗證；
不採用主線或舊 head 的成功代替。
整份規格未完成，PR 維持 draft，不啟用 auto-merge。

## 後續驗證與選擇

§5.2 公開stage憑證已於2026-10-16對照官方測試介接頁（https://developers.ecpay.com.tw/7849/）：
一般特店MerchantID及HashKey／HashIV與規格一致；本階段不使用它們，測試使用自訂假伺服器值。
EinvoiceCodec 採 public internal（見下）；stage Timestamp 假設與實機驗證尚未執行。
S2 依 §12 可在用戶端與排程之間切分，排程不可推半成品。G03 必須等整份 G02 合併。

## S2a 設計與驗收對照（2026-10-16）

新增 public final internal EinvoiceCodec，讓組裝層假伺服器直接引用（§10.1 選項二），
不新增業務模組跨界依賴。AES-128-CBC/PKCS5Padding、PHP urlencode（*→%2A）；
任何編解碼錯誤只回固定中文訊息，不保留原始例外 cause。

新增 EcpayInvoiceClient（Issue/GetIssue），但尚無排程或端點呼叫。JSON 欄位、
整數金額、Timestamp 由呼叫端傳入；拒絕重新導向，未知結果用原因類別表示。
不印原文、密文、例外堆疊；RtnMsg 限100字且清除換行及已知敏感參數。
設定預設停用，正式設定特店／key／iv留空，demo profile本階段不動。

設定防線在 client 建構子先落地（驗收11），下一階段 InvoiceService 注入同一個
client即可保證未通過設定檢查不能啟動；不另複製一份驗證。停用時不建立HttpClient，
不解析endpoint／timeout，方法直接DISABLED。AES設定另檢查UTF-8長度16 bytes，
避免16個非ASCII字元通過後才執行期失敗。

FakeEinvoiceServer 支援所有§10.1情境、各路徑掛勾、外層Timestamp／解密Data記錄、
applyDelayed/dropDelayed；遲到生效由測試明確驅動，不靠sleep。關閉時中斷executor後
以共同deadline bounded join實際worker thread，不以executor狀態推定執行緒已結束。

| 驗收 | 測試方法 |
| --- | --- |
| 7 | EinvoiceCodecTest.roundTrip、encodingUsesPhpRulesAndAesEncryptsTheEncodedText、failuresNeverCarrySecretsOrInputInMessageOrCause |
| 8 | EinvoiceClientTest.issueAndQueryUseEncryptedEnvelopeAndDiscountedOrderTotal（三種類型） |
| 9 | failuresAreUnknownAndRedirectsAreNotFollowed、rejectionDuplicateMissingQueryAndConnectionFailureAreDistinct |
| 10 | failuresAreUnknownAndRedirectsAreNotFollowed（HTTP_302且假伺服器沒有額外請求） |
| 11 | EinvoiceStageGuardTest.invalidConfigurationFailsContextWithoutEchoingValues（33組）、exactStageAndExplicitLoopbackAreAcceptedWithoutConnecting、numericBoundsAreInclusive、disabledClientAcceptsInvalidSettingsAndCannotSend |
| 22部分 | EcpayNoEgressGuardTest新增四個守門／反向測試；ClientTest各情境輸出衛生斷言；完整排程log驗收仍屬S2b |

EcpayNoEgressGuardTest 只追加常數／方法，不修改原方法；src/main掃描無既存正式網域例外。
兩支原金流profile測試只追加本機invoice.base-url覆寫，原斷言不變，避免未來demo profile
啟用發票時測試連外。沒有修改既有migration、AGENTS.md或CLAUDE.md。

本地：前端143 tests與build通過；後端verify仍因Maven Central DNS失敗未進編譯。
最新提交必須由遠端Checks／Actions補足，不能沿用26f5963的S1綠燈。
本階段不使用公開發票stage憑證，也未執行官方stage介接實測；公開值的核對結果見上節。
