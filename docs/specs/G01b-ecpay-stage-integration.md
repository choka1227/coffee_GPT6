# G01b — 綠界測試環境（stage）串接可運作

版本：v1.1（2026-10-12）
作者：Claude（PM / SA）
前置：G01（PR #9）、G01a（PR #15）皆已合併。本規格以「補齊讓它能跑」為主，**不重寫** `EcpayService`／`ReconciliationService`／`EcpayTradeQuery`。
後續：G04（線上付款訂單的逾時作廢，與對帳互斥）為下一份規格，**不在本規格範圍**。

---

## 1. 背景與目標

PO 於 2026-10-11 解除 2026-09-17 的「金流整批延後」決策：**綠界線上付款與對帳要做到能實際運作，但只串綠界測試環境（stage），不接正式環境。**

主線上的金流程式碼其實已經相當完整（建立 AIO 表單、回呼驗簽與冪等、主動查單、排程對帳、手動對帳端點與畫面），但它從來沒有「實際跑起來」過。盤點後擋住它的是下面六件事：

| # | 現況 | 位置 | 後果 |
| --- | --- | --- | --- |
| 1 | 程式碼同時支援 `stage` 與 `production`，`production` 會指向 `payment.ecpay.com.tw` | `EcpayService.java:41,77-79`、`EcpayTradeQuery.java:37-41`、`frontend/src/modules/payments/ecpay.ts:10`、`SecurityConfiguration.java:70` | 違反 PO「只串 stage」；設錯一個環境變數就連到正式環境 |
| 2 | 查單端點寫死 `https://payment-stage.ecpay.com.tw`，沒有覆寫點 | `EcpayTradeQuery.java:36-42` | 自動化測試只能 `@MockitoBean TradeQuery`，**真正的 HTTP 傳輸、簽章送出、回應解析、逾時處理從未被測過** |
| 3 | 啟用綠界必須 `app.public-url` 是 `https://` | `EcpayService.java:43` | 本機 demo（`http://localhost:8080`）無法啟用金流，連帶對帳也無法啟用（`ReconciliationService.java:45`） |
| 4 | 對帳預設關閉，demo 設定也沒有打開 | `application.yml:46`、`application-dev.yml` | PO 要求「demo／開發設定下預設開啟」 |
| 5 | 排程對帳的例外被整個吞掉，連一行 log 都沒有 | `ReconciliationService.java:161-163` | 實機驗證時無從得知排程有沒有在跑、跑出什麼結果 |
| 6 | `docs/ECPAY.md` 仍寫「本版沒有主動查單或排程對帳」「切換 production…」 | `docs/ECPAY.md` | 文件與程式碼脫節，且鼓勵切正式環境 |

**目標：** 合併本規格後，

1. 任何設定都**不可能**讓後端連到綠界正式環境（啟動即失敗）
2. `./start-demo.sh` 預設以綠界公開測試特店啟動金流與排程對帳
3. CI 以本機假綠界伺服器（JDK 內建 `HttpServer`）**端到端**驗證查單與回呼，**零連外**
4. 有一份照著做就能在 stage 實機驗證的操作文件，列進 `GAP-ANALYSIS.md`「待 PO 驗收（不擋合併）」

## 2. 範圍

### 2.1 在範圍內

- **stage 防線**：環境只接受 `stage`；移除所有 `production` 分支與正式網域（後端、前端、CSP）
- **查單端點覆寫**：新增 `ecpay.query-url`，只接受「綠界 stage 正式網址」或「loopback（`127.0.0.1`／`localhost`）的 `http` 網址」兩種值
- **放寬 `https` 限制**：`app.public-url` 非 `https` 時仍可啟用，但啟動時記一行 WARN（回呼將無法送達，改靠對帳補救）
- **新 profile `ecpay-stage`**：放綠界官方公開測試特店參數、開啟金流與排程對帳；`start-demo.sh` 預設帶上
- **排程對帳的可觀測性**：每輪結束一行 INFO 摘要、每筆失敗一行 WARN（只含訂單編號與例外類別）
- **憑證衛生**：HashKey／HashIV 不得出現在 log、例外訊息、API 回應；以測試釘住
- **本機假綠界伺服器**與一組端到端測試（查單 8 種情境 + 回呼 7 種情境 + 「回呼遺失 → 對帳補救 → 遲到回呼」串接）
- **文件**：改寫 `docs/ECPAY.md`；stage 實機驗證步驟

### 2.2 不在範圍內

- **G04 逾時作廢**（下一份規格）。本規格**不**讓任何訂單變成 `CANCELLED`
- G02 電子發票、G03 退款與退單（仍延後）
- 綠界正式環境上線、正式特店憑證（屬「帳號與憑證」，PO 的事，**不得出現在 repo**）
- 顧客端「我付完了，幫我查一下」的觸發端點（理由見 §15.5）
- 同一訂單重複送出 AIO 表單時綠界的行為處理（列為 stage 實機觀察項，見 §11 步驟 5 與 §15.6）
- `Orders.java`／`OrderService.java` 的任何變更 —— G01a 合併時留下的三項非阻斷觀察（`GAP-ANALYSIS.md`「G01a 合併時留下的三項非阻斷觀察」）**都在 `coffee-orders`**，本規格不動那兩個檔，**改由 G04 夾帶**（G04 一定會動 `OrderService`）

## 3. 涉及模組與邊界

| 模組 | 變更 | 邊界影響 |
| --- | --- | --- |
| `coffee-payments` | `EcpayService`、`EcpayTradeQuery`、`ReconciliationService`（只加 log） | 無。不新增跨模組依賴 |
| `coffee-app` | `SecurityConfiguration`（CSP 移除正式網域）、新增 `application-ecpay-stage.yml`、測試 | 無 |
| `frontend` | `modules/payments/ecpay.ts` 的允許清單只留 stage；新增一支單元測試 | 無 |
| 腳本／文件 | `start-demo.sh`、`docs/ECPAY.md` | — |

- **零 migration**（`V15` 仍空著）
- **零新依賴**：假伺服器用 `com.sun.net.httpserver.HttpServer`（JDK 內建，`jdk.httpserver` 模組）；log 擷取用 Spring Boot 既有的 `OutputCaptureExtension`（`spring-boot-test`，已在測試 classpath）。**不得**引入 WireMock、MockWebServer 等（實作端環境曾解析不到 Maven Central，見 G20j 報告）
- `TradeQuery`（`payments.api`）介面**不變**

## 4. DB schema

**無變更。** 既有 `payment_events`（回呼紀錄）與 `payment_reconciliations`（查單紀錄）已足夠。

## 5. 設定

### 5.1 `application.yml`（正式設定的預設值）—— 只改一行、加一行

```yaml
ecpay:
  enabled: ${ECPAY_ENABLED:false}          # 不變
  environment: ${ECPAY_ENVIRONMENT:stage}  # 不變；但非 stage 一律拒絕啟動（§6.1）
  merchant-id: ${ECPAY_MERCHANT_ID:}       # 不變：正式設定不放任何特店代號
  hash-key: ${ECPAY_HASH_KEY:}             # 不變：不放任何金鑰
  hash-iv: ${ECPAY_HASH_IV:}               # 不變
  query-url: ${ECPAY_QUERY_URL:https://payment-stage.ecpay.com.tw/Cashier/QueryTradeInfo/V5}  # 新增
  query-timeout-ms: ${ECPAY_QUERY_TIMEOUT_MS:10000}
  reconcile:
    enabled: ${ECPAY_RECONCILE_ENABLED:false}   # 不變：正式設定預設仍關閉
    ...
```

**`application.yml` 不得出現任何特店代號、HashKey、HashIV 的非空預設值**（驗收 3）。

### 5.2 新增 `application-ecpay-stage.yml`（demo／開發專用）

```yaml
# 綠界官方公開的測試特店參數（https://developers.ecpay.com.tw/2856/），不是正式憑證。
# 只在 ecpay-stage profile 生效；正式設定（application.yml）不含任何特店資料。
ecpay:
  enabled: ${ECPAY_ENABLED:true}
  environment: stage
  merchant-id: ${ECPAY_MERCHANT_ID:3002607}
  hash-key: ${ECPAY_HASH_KEY:pwFHCqoQZGmho4w6}
  hash-iv: ${ECPAY_HASH_IV:EkRm7iFT261dpevs}
  reconcile:
    enabled: ${ECPAY_RECONCILE_ENABLED:true}
    interval-ms: ${ECPAY_RECONCILE_INTERVAL_MS:120000}
    min-age-minutes: ${ECPAY_RECONCILE_MIN_AGE_MINUTES:3}
```

- 三個測試特店值與 `docs/ECPAY.md` 現有的公開值相同，不是新增的機密
- `environment: stage` **不給環境變數覆寫點**（就算給了，§6.1 也會擋，但不給更清楚）
- demo 的對帳節奏（每 2 分鐘、訂單滿 3 分鐘才查）理由見 §15.4

### 5.3 `start-demo.sh`

```bash
exec java -jar "$coffee_jar" --spring.profiles.active=dev,ecpay-stage --server.address=127.0.0.1
```

想在 demo 關掉金流：`ECPAY_ENABLED=false ECPAY_RECONCILE_ENABLED=false ./start-demo.sh`（寫進 `docs/ECPAY.md`）。

### 5.4 為什麼不直接寫進 `application-dev.yml`

**有 5 支以上的測試**（`LoginRateLimitTest`、`DiscountRedemptionTest`、`BranchProductStockOrderingTest`、`BranchLastOrderTest`、`OrderPreviewTest` …）用 `@ActiveProfiles("dev")`。若 `dev` 開了金流與排程對帳，這些測試的 Spring context 會帶著一個**真的會連到 `payment-stage.ecpay.com.tw` 的排程**。這正是 PO 硬規則 4（CI 不得連外到綠界）要擋的事。所以金流設定獨立成 `ecpay-stage` profile，只由 `start-demo.sh` 帶上。見設計決策 §15.3。

## 6. 後端變更

### 6.1 stage 防線（`EcpayService`、`EcpayTradeQuery` 建構子）

**不論 `ecpay.enabled` 為何**，以下任一不成立就在建構子丟例外，讓 Spring context 啟動失敗：

| 條件 | 錯誤訊息（繁中） |
| --- | --- |
| `ecpay.environment` 等於 `stage`（大小寫完全一致） | `本系統只允許串接綠界測試環境（stage）` |
| `ecpay.query-url` 通過 §6.2 的檢查 | `綠界查單網址只允許測試環境或本機測試伺服器` |

- 用 `IllegalStateException`（啟動期設定錯誤，不是 HTTP 錯誤；`Problem` 的 HTTP 狀態碼在這裡沒有意義）。既有的 `Problem.check(...)` 啟動檢查**可以保留**原樣，新增的兩條用 `IllegalStateException`；不強制改舊的
- **錯誤訊息不得包含任何設定值**（不得把 `environment`、`merchant-id`、`hash-key`、`hash-iv`、`query-url` 的實際值串進訊息）
- 移除：`EcpayService` 建構子裡 `if (environment.equals("production")) ...` 那段（已不可能成立）、`checkout()` 的三元運算子（`action` 固定為 stage 網址）、`EcpayTradeQuery` 選網域的三元運算子
- `EcpayService` 內新增常數 `static final String STAGE_CHECKOUT_URL = "https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5";`；`EcpayTradeQuery` 內新增 `static final String STAGE_QUERY_URL = "https://payment-stage.ecpay.com.tw/Cashier/QueryTradeInfo/V5";`
- `GET /api/payments/config` 的回應**不變**（`{"enabled": ..., "environment": "stage"}`）

### 6.2 `ecpay.query-url` 的允許規則（`EcpayTradeQuery`）

用 `URI.create(...)` 解析後，只接受下列**兩種之一**：

1. 字串與 `STAGE_QUERY_URL` **完全相等**
2. `scheme` 為 `http`、`host` 為 `127.0.0.1` 或 `localhost`、有明確 `port`、沒有 `userInfo`、沒有 `query`、沒有 `fragment`

其他一律拒絕（含 `https://payment.ecpay.com.tw/...`、`http://payment-stage.ecpay.com.tw/...`、`https://127.0.0.1/...`、`http://127.0.0.1.evil.com:80/...`、`http://user@127.0.0.1:8080/...`、無法解析的字串）。

- `followRedirects(NEVER)` 維持不變 —— loopback 假伺服器即使回 302 指向正式網域也不會被跟隨（驗收 9 的情境 f）
- 判斷 host 用 `URI.getHost()` 精確比對，**不可**用 `startsWith`／`contains`

### 6.3 放寬 `https` 限制（`EcpayService` 建構子）

移除 `Problem.check(base.startsWith("https://"), ...)`。改為：`enabled` 且 `app.public-url` 不是 `https://` 開頭時，記一行 WARN：

```
綠界付款通知（ReturnURL）需要可從外網連線的 HTTPS 網址；目前設定無法收到通知，付款狀態將由排程對帳補救
```

WARN 內容**不得**包含 `public-url` 以外的任何設定值（`public-url` 本身可以印，它不是機密，但也不強制印）。

### 6.4 排程對帳的 log（`ReconciliationService.scheduled()`）

- 每輪結束（`enabled` 為 `true` 時）記**一行 INFO**：`綠界排程對帳完成：候選 {n} 筆，CONFIRMED {a}、STILL_UNPAID {b}、SIMULATED {c}、AMOUNT_MISMATCH {d}、QUERY_FAILED {e}、略過 {f}`。「略過」= `perform()` 丟出例外的筆數（例如 409 這筆訂單不需要對帳）
- `catch (RuntimeException e)` 內記**一行 WARN**：`綠界排程對帳單筆失敗：訂單 {orderId}，{e.getClass().getSimpleName()}`。**不得**傳入 `e` 本身（避免 stack trace 夾帶回應內容）、**不得**印 `e.getMessage()`
- `perform()` 本身的邏輯**不改**。候選為 0 筆時也要記那一行 INFO（實機驗證時才看得出排程有在跑）
- logger 用 SLF4J（`org.slf4j.LoggerFactory`，Spring Boot 已帶，不是新依賴）

### 6.5 HashKey／HashIV 的衛生規則（全模組）

- 不得進任何 log（任何等級）、任何 `Problem`／例外訊息、任何 API 回應、任何 `toString()`
- `EcpayService`／`EcpayTradeQuery`／`ReconciliationService` 若日後被改成 record 或加 `toString()`，必須排除 `key`／`iv` —— 本規格不要求改型別，只要求驗收 10、11 的測試釘住

### 6.6 `SecurityConfiguration`

CSP 的 `form-action` 移除 ` https://payment.ecpay.com.tw`，只留 `'self' https://payment-stage.ecpay.com.tw`。

## 7. 前端變更

`frontend/src/modules/payments/ecpay.ts`：允許清單只留 `https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5`。

新增 `frontend/src/modules/payments/ecpay.test.ts`（vitest，沿用 G22／G23 既有的測試基礎設施，**不新增 devDependency**）：

- 後端回 stage 網址 → 建立 `<form method="POST" action=stage>` 並帶齊所有 hidden 欄位、呼叫 `submit()`（`HTMLFormElement.prototype.submit` 用 `vi.spyOn` 擋掉）
- 後端回 `https://payment.ecpay.com.tw/Cashier/AioCheckOut/V5` → 丟 `付款網址不正確`，**不建立表單、不 submit**
- 後端回任意其他網址 → 同上

`api` 的 mock 方式比照既有 `*.test.ts` 的作法（`vi.mock("../../shared/api", ...)`）。

## 8. API

**無新端點、無契約變更。** 既有端點的權限與資料範圍不變：

| 端點 | 權限 | 資料範圍 | 本規格的變更 |
| --- | --- | --- | --- |
| `GET /api/payments/config` | 公開（已登入） | — | 無 |
| `POST /api/payments/ecpay/{id}` | 顧客本人（`orders.payable`） | SELF | `action` 固定 stage |
| `POST /api/payments/ecpay/callback` | 免登入、免 CSRF，靠 CheckMacValue | — | 無 |
| `GET /api/payments/reconciliation/pending` | `PAYMENT_RECONCILE` | BRANCH／GLOBAL | 無 |
| `GET/POST /api/payments/reconciliation/{id}` | `PAYMENT_RECONCILE` | BRANCH／GLOBAL | 無 |

越權測試已由 G01／G01a 的 `ReconciliationTest.branchSelfPermissionAndCsrfEnforced` 覆蓋，本規格不重做；但**端到端測試裡的手動對帳請求必須用真實的店長帳號 + CSRF**（不是 mock actor），以免假伺服器測試繞過授權層。

## 9. 金額規則

不變，但端到端測試必須釘住：

- 建立綠界表單的 `TotalAmount` 來自後端訂單 `total`，不是請求內容（既有行為；驗收 13 檢查）
- 回呼 `TradeAmt` 與訂單 `total` 不符 → 不入帳（驗收 14-c）
- 查單 `TradeAmt` 與訂單 `total` 不符 → `AMOUNT_MISMATCH`、不入帳（驗收 12-c）
- 金額比對的對象一律是**後端 DB 裡的訂單金額**，不是前一個步驟（表單、回呼）傳來的金額

## 10. 本機假綠界伺服器（測試設計）

### 10.1 位置與形狀

`backend/coffee-app/src/test/java/com/coffee/app/ecpay/FakeEcpayServer.java`（測試輔助類別，`AutoCloseable`）。

```java
final class FakeEcpayServer implements AutoCloseable {
  static FakeEcpayServer start(String merchantId, String hashKey, String hashIv);  // 綁 127.0.0.1:0
  String queryUrl();                 // "http://127.0.0.1:<port>/Cashier/QueryTradeInfo/V5"
  void respond(Scenario scenario);   // 設定下一次（以及之後每次）查單的回應情境
  List<Map<String, String>> receivedQueries();  // 收到的查單請求（已解析的表單欄位）
  Map<String, String> signedCallback(Map<String, String> checkoutFields, Map<String, String> overrides);
  void close();                      // server.stop(0)，再 executor.shutdownNow() + awaitTermination(5s)
}
```

- 用 `com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)`，**明確綁 IPv4 loopback、隨機埠**。**不要**用 `InetAddress.getLoopbackAddress()`：JVM 偏好 IPv6（`java.net.preferIPv6Addresses=true`）時它會回 `::1`，伺服器綁 IPv6、`queryUrl()` 卻是 `127.0.0.1`，所有查單情境都會 connection refused。`queryUrl()` 的埠一律取自 `server.getAddress().getPort()`
- `FakeEcpayServer` **自己持有** `ExecutorService`（`Executors.newCachedThreadPool` 搭配把 thread 設為 daemon、名稱前綴 `fake-ecpay-` 的 `ThreadFactory`），交給 `setExecutor`，避免 `TIMEOUT` 情境的 sleep 卡住後續請求。`HttpServer.stop()` **不會**關閉呼叫者傳入的 executor，所以 `close()` 必須依序 `server.stop(0)` → `executor.shutdownNow()`（中斷 `TIMEOUT` 情境 sleep 中的 worker）→ `awaitTermination(5, SECONDS)`。測試類別以 `@AfterEach`／try-with-resources 保證 `close()` 一定被呼叫
- 收到查單請求時，**先驗證我方送出的請求**：`MerchantID` 等於設定值、`MerchantTradeNo` 為 20 碼英數、`TimeStamp` 與現在相差 ≤ 180 秒、`CheckMacValue` 以同一組 key／iv 驗得過。不成立就回 HTTP 400 並記下，讓測試斷言失敗 —— 這是**目前唯一能驗證我方「送出去」的簽章正確**的地方（既有測試全部 mock 掉 `TradeQuery`）
- 回應以 `application/x-www-form-urlencoded` 字串回傳，格式與綠界 QueryTradeInfo 相同（`key=value&...`，含 `CheckMacValue`），用 `CheckMac.sign` 簽

### 10.2 查單情境（`Scenario`）

| 情境 | 假伺服器回應 | 預期 `outcome` | 訂單狀態 |
| --- | --- | --- | --- |
| `PAID` | `TradeStatus=1`、`TradeAmt=<訂單金額>`、`TradeNo=<20 碼>`、`PaymentDate=<台北時間，過去 1 分鐘>`、正確簽章 | `CONFIRMED` | `PAID` |
| `NOT_FOUND` | `TradeStatus=10200047`、`TradeAmt=`（空）、`TradeNo=`（空）、正確簽章 | `STILL_UNPAID` | 不變 |
| `AMOUNT_MISMATCH` | 同 `PAID` 但 `TradeAmt=<訂單金額 + 1>` | `AMOUNT_MISMATCH` | 不變 |
| `BAD_MAC` | 同 `PAID` 但 `CheckMacValue` 以錯誤 key 簽 | `QUERY_FAILED` | 不變 |
| `TIMEOUT` | handler `Thread.sleep` 超過 `ecpay.query-timeout-ms`（測試設 `500`，sleep `1500`） | `QUERY_FAILED` | 不變 |
| `CONNECTION_REFUSED` | 不用假伺服器；`query-url` 指向一個已關閉的 loopback 埠 | `QUERY_FAILED` | 不變 |
| `HTTP_500` | 回 HTTP 500 | `QUERY_FAILED` | 不變 |
| `REDIRECT_TO_PRODUCTION` | 回 HTTP 302、`Location: https://payment.ecpay.com.tw/...` | `QUERY_FAILED`（不跟隨） | 不變 |

`NOT_FOUND` 的 `TradeStatus` 值以綠界文件為準；`10200047` 是本規格採用的代表值，§11 步驟 4 會在 stage 實機確認。實作只依賴「`TradeStatus != "1"` → `STILL_UNPAID`」，與值無關。

### 10.3 Spring context 的接線

```java
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {
    "ecpay.enabled=true", "ecpay.environment=stage",
    "ecpay.merchant-id=3002607", "ecpay.hash-key=test-key-g01b", "ecpay.hash-iv=test-iv-g01b",
    "ecpay.query-timeout-ms=500",
    "ecpay.reconcile.enabled=true", "ecpay.reconcile.interval-ms=86400000",
    "ecpay.reconcile.min-age-minutes=0", "app.public-url=http://localhost"})
class EcpayStageEndToEndTest {
  static final FakeEcpayServer FAKE = FakeEcpayServer.start("3002607", "test-key-g01b", "test-iv-g01b");
  @DynamicPropertySource
  static void ecpay(DynamicPropertyRegistry r) { r.add("ecpay.query-url", FAKE::queryUrl); }
  ...
}
```

- **不得 `@MockitoBean TradeQuery`** —— 這支測試的意義就是走真的 `EcpayTradeQuery`
- 測試用的 key／iv 用**不像真值**的字串（`test-key-g01b`），讓驗收 10、11 的「log 裡找不到 key」斷言有鑑別力（真實測試特店的 `pwFHCqoQZGmho4w6` 也可能出現在別的地方，例如文件）
- 排程對帳由測試直接呼叫 `ReconciliationService.scheduled()` 觸發（`interval-ms` 設一天，避免背景排程與測試互相干擾）；`ReconciliationService` 是 `coffee-payments.internal`，`coffee-app` 的測試本來就可以引用（既有 `ReconciliationTest` 已引用 `EcpayTradeQuery`）
- 回呼請求以**真實 HTTP**（`TestRestTemplate` 或 `java.net.http.HttpClient`）打 `POST /api/payments/ecpay/callback`，不帶 CSRF、不帶 cookie —— 綠界就是這樣打的

### 10.4 「CI 不連外」的防線

1. 端到端測試的 `query-url` 一律由 `@DynamicPropertySource` 指向假伺服器
2. **`EcpayNoEgressGuardTest`**（純檔案掃描，不需要 Spring context）：
   - 掃 `backend/**/src/test/**` 所有 `.java`：出現 `ecpay-stage`（profile 名稱）的檔案，必須同時出現 `ecpay.query-url`；否則失敗並列出檔名
   - 掃同一範圍：不得出現 `payment.ecpay.com.tw`（正式網域）字面值，**例外**是 §6.2 拒絕規則的測試檔（以檔名白名單 `EcpayStageGuardTest.java`、`FakeEcpayServer.java`、`EcpayStageEndToEndTest.java`、`EcpayNoEgressGuardTest.java`（自身含該字面值）列出，白名單寫在測試內）
3. 既有用 `@MockitoBean TradeQuery` 的測試（`ReconciliationTest`、`HttpWorkflowTest`）保持不動；它們的 `EcpayTradeQuery` bean 被替換，不會連外

## 11. stage 實機驗證的操作步驟（寫進 `docs/ECPAY.md`，列入 GAP-ANALYSIS「待 PO 驗收」）

CI 不可能驗到「綠界真的打到我們的回呼」，因為那需要一個外網連得到的 HTTPS 網址。以下步驟由 PO（或任何有可對外環境的人）執行，**不擋合併**。

**準備：**

1. `./scripts/build.sh`
2. 準備一條公開 HTTPS 通道，把外網流量轉到本機 `127.0.0.1:8080`。任何能提供「公開 HTTPS 網址 → 本機埠」的通道工具都可以（例如 Cloudflare Tunnel、ngrok）；**本專案不指定、不加入任何依賴或設定檔**。取得網址後設 `APP_PUBLIC_URL=https://<通道網址>`
3. `APP_PUBLIC_URL=https://<通道網址> ./start-demo.sh`（`ecpay-stage` profile 已帶入測試特店）

**步驟 1 — 回呼路徑（主要路徑）：** 以 demo 顧客登入 → 點餐選線上付款 → 導到綠界 stage 付款頁 → 用綠界官方文件的測試信用卡付款（卡號、有效年月、安全碼、3D 驗證碼以 <https://developers.ecpay.com.tw/2856/> 為準）→ 回到訂單頁。**預期**：幾秒內訂單變「已付款」；DB `payment_events` 多一列 `result_code=1`、`simulated=false`。

**步驟 2 — 對帳補救路徑（不需要通道）：** 不設 `APP_PUBLIC_URL`（預設 `http://localhost:8080`）直接 `./start-demo.sh`。啟動 log 應出現 §6.3 的 WARN。付款流程同步驟 1。**預期**：付款後訂單仍是「待付款」（回呼送不到）；**3–5 分鐘內**（min-age 3 分鐘 + 間隔 2 分鐘）log 出現 §6.4 的 INFO 且 `CONFIRMED 1`，訂單變「已付款」，`payment_reconciliations` 多一列 `trigger_source=SCHEDULED`、`outcome=CONFIRMED`。也可在對帳頁面以店長帳號按「查核」立即觸發（`trigger_source=MANUAL`）。

**步驟 3 — 綠界是否接受非 HTTPS 的 ReturnURL：** 步驟 2 若在綠界付款頁就被拒（綠界不接受 `http://localhost` 的 ReturnURL），記錄錯誤碼與訊息，並以步驟 1 的通道網址重做步驟 2（把通道關掉讓回呼失敗即可）。這個結果決定 §15.2 要不要推翻。

**步驟 4 — 查無交易：** 建立一張線上付款訂單，在綠界付款頁直接關掉。等 3–5 分鐘。**預期**：`outcome=STILL_UNPAID`，記下綠界回的 `TradeStatus` 實際值（本規格的假伺服器用 `10200047`，若不同回報給 Claude 修正 §10.2）。

**步驟 5 — 同一訂單第二次付款：** 建立訂單 → 進付款頁 → 不付款直接返回 → 再按一次付款。**記錄**綠界是否回「訂單編號重複」類錯誤。這是 G04 設計的輸入（§15.6），本規格不處理。

**步驟 6 — 遲到的回呼：** 步驟 2 完成（已由對帳轉 `PAID`）後，把通道打開、在綠界廠商後台對該筆交易「重送付款通知」（若後台有此功能）。**預期**：回呼回 `1|OK`、訂單維持 `PAID`、`paid_at` 不變、不重複入帳。

**驗收紀錄格式**（PO 回報用）：`| 步驟 | 日期 | 結果（通過／不通過／不適用） | 觀察（錯誤碼、TradeStatus 實際值等） |`

## 12. 施工階段

三個階段**全部是加法或收斂既有分支**，每段單獨合併都不破壞既有行為。預估每段都在 Codex 一次執行的規模內。

### S1 — stage 防線與查單端點覆寫（後端 + 前端各一小處）

**動到：** `EcpayService.java`、`EcpayTradeQuery.java`、`ReconciliationService.java`（只加 log）、`SecurityConfiguration.java`（一行）、`application.yml`（加一行 `query-url`）、`frontend/src/modules/payments/ecpay.ts`、新增 `ecpay.test.ts`、新增 `EcpayStageGuardTest.java`、新增 `EcpayLogHygieneTest.java`（S1 只做啟動期與既有 mock 情境；S2 補假伺服器情境）。

**驗收子集：** 1、2、3、4、5、6、7、8、10（S1 部分）、16、17。

**為什麼 S1 可單獨合併：** 既有測試全部用 `environment=stage` 或預設值（已查證：`HttpWorkflowTest`、`CoffeeIntegrationTest`、`ReconciliationTest` 都是 stage 或不設），移除 `production` 不會讓任何既有測試變紅；`query-url` 預設值與今天寫死的網址相同。

### S2 — 本機假綠界伺服器與端到端測試（只加測試）

**動到：** 新增 `FakeEcpayServer.java`、`EcpayStageEndToEndTest.java`、`EcpayNoEgressGuardTest.java`；擴充 `EcpayLogHygieneTest.java`。**生產程式碼零變更**（若 S2 寫測試時發現 S1 的程式有缺陷，修正可以夾帶，但要在 PR 描述列出）。

**驗收子集：** 9、10（S2 部分）、11、12、13、14、15、18。

### S3 — demo profile、啟動腳本與文件

**動到：** 新增 `application-ecpay-stage.yml`、`start-demo.sh`（一行）、改寫 `docs/ECPAY.md`、新增 `EcpayStageProfileTest.java`。

**驗收子集：** 19、20、21、22。

**注意 `start-demo.sh` 的執行位元**：它必須維持 `100755`。改完用 `git ls-files -s start-demo.sh` 確認。

## 13. 驗收條件

**S1**

- [ ] 1. `ecpay.environment=production` 時 Spring context 啟動失敗，例外訊息為 `本系統只允許串接綠界測試環境（stage）`；`ecpay.enabled=false` 時同樣失敗
- [ ] 2. `ecpay.environment` 為 `Stage`、`STAGE`、空字串、`prod` 時同樣啟動失敗
- [ ] 3. `application.yml` 中 `ecpay.merchant-id`、`hash-key`、`hash-iv` 的預設值皆為空字串（以測試讀取 classpath 上的 `application.yml` 斷言，不是人工檢查）
- [ ] 4. `ecpay.query-url` 為 §6.2 列出的每一個拒絕樣本時啟動失敗，訊息為 `綠界查單網址只允許測試環境或本機測試伺服器`；為 `STAGE_QUERY_URL` 或 `http://127.0.0.1:<port>/...`、`http://localhost:<port>/...` 時啟動成功
- [ ] 5. `POST /api/payments/ecpay/{id}` 回應的 `action` 恆為 `https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5`
- [ ] 6. `main` 程式碼（`backend/**/src/main/**`、`frontend/src/**` 非測試檔）中不再出現 `payment.ecpay.com.tw` 字面值（以 `EcpayStageGuardTest` 掃檔斷言）
- [ ] 7. 前端 `ecpay.test.ts` 三個情境通過；正式網域被拒且不 submit
- [ ] 8. `app.public-url=http://localhost` 且 `ecpay.enabled=true` 時可以啟動，log 出現 §6.3 的 WARN
- [ ] 10. （S1 部分）啟動失敗的例外訊息（含 cause 鏈）中不含 `hash-key`、`hash-iv` 的值

**S2**

- [ ] 9. §10.2 的 8 個查單情境逐一通過，訂單狀態與 `payment_reconciliations.outcome` 符合表格；`REDIRECT_TO_PRODUCTION` 情境下假伺服器的 302 沒有被跟隨（測試以「沒有任何對外連線」為前提：`Location` 指向的網域若被連線，測試在 CI 會因 DNS／egress 而變慢或失敗 —— 以 `outcome=QUERY_FAILED` 且耗時 < 2 秒斷言）
- [ ] 9a. 假伺服器生命週期：跑完 `TIMEOUT` 情境並 `close()` 後，`Thread.getAllStackTraces()` 中沒有任何名稱以 `fake-ecpay-` 開頭且仍存活的 thread；`queryUrl()` 的 host 為 `127.0.0.1`、埠等於伺服器實際綁定的埠
- [ ] 10. （S2 部分）跑完 §10.2 所有情境與 §13 驗收 14 所有回呼情境後，`OutputCaptureExtension` 擷取的完整輸出中**找不到** `test-key-g01b` 與 `test-iv-g01b`
- [ ] 11. 所有情境下，API 回應本文（checkout、callback、reconcile、history、pending、config）都不含 `test-key-g01b`、`test-iv-g01b`
- [ ] 12. 假伺服器記錄的每一筆查單請求都通過 §10.1 的四項檢查（我方簽章正確）
- [ ] 13. 端到端：顧客建單 → `POST /api/payments/ecpay/{id}` 取得表單 → `TotalAmount` 等於 DB `orders.total`（即使建單請求夾帶了偽造的金額欄位）
- [ ] 14. 回呼（以真實 HTTP、無 CSRF、無 cookie 打 callback），表單欄位由 `FakeEcpayServer.signedCallback(checkout 回傳的 fields, overrides)` 產生：
  - a. 正確簽章、`RtnCode=1`、金額相符 → `1|OK`、訂單 `PAID`、`payment_events` 一列
  - b. 同一則回呼再送一次 → `1|OK`、訂單仍 `PAID`、`paid_at` 不變（冪等）
  - c. `TradeAmt` 與訂單不符（簽章正確）→ `0|Invalid notification`、訂單不變
  - d. `CheckMacValue` 錯誤 → `0|Invalid notification`、訂單不變、`payment_events` 不增加
  - e. `SimulatePaid=1` → `1|OK`、訂單不變
  - f. `RtnCode=10100058`（付款失敗）→ `1|OK`、訂單不變
  - g. 重複參數（同名欄位出現兩次）→ `0|Invalid notification`
- [ ] 15. 串接情境「回呼遺失 → 排程對帳補救 → 遲到回呼」：建單、取表單、**不送回呼**；假伺服器設 `PAID`；呼叫 `scheduled()` → 訂單 `PAID`、`trigger_source=SCHEDULED`、`outcome=CONFIRMED`；接著送一則與查單結果相同 `TradeNo` 的有效回呼 → `1|OK`、`paid_at` 維持對帳寫入的值。再加一則 `TradeNo` 不同的有效回呼 → `0|Invalid notification`、訂單不變
- [ ] 18. `EcpayNoEgressGuardTest` 通過；並以一個臨時字串樣本（測試內建，不是真的檔案）證明規則會抓到「用了 `ecpay-stage` 卻沒設 `query-url`」

**S1 + S2 共同**

- [ ] 16. 排程對帳每輪記一行 §6.4 的 INFO（候選 0 筆時也要），單筆失敗記一行 WARN 且不含 stack trace（以 `OutputCaptureExtension` 斷言 WARN 那一行之後沒有 `\tat ` 開頭的行）
- [ ] 17. 既有的 `ReconciliationTest`、`HttpWorkflowTest`、`CoffeeIntegrationTest`、`CheckMacTest` 不修改即全數通過

**S3**

- [ ] 19. `EcpayStageProfileTest`：以 `@ActiveProfiles({"dev", "ecpay-stage"})` 啟動（`query-url` 指向假伺服器、`interval-ms` 設一天），斷言 `ecpay.enabled=true`、`environment=stage`、`merchant-id=3002607`、`reconcile.enabled=true`、`reconcile.min-age-minutes=3`；另以直接讀取 classpath 上 `application-ecpay-stage.yml` 的方式斷言 `reconcile.interval-ms` 的預設值為 `120000`（context 裡的值已被測試覆寫成一天，不能拿來斷言）
- [ ] 20. `start-demo.sh` 帶 `--spring.profiles.active=dev,ecpay-stage`，執行位元 `100755`
- [ ] 21. `docs/ECPAY.md` 改寫完成：移除「正式環境」切換段落與「本版沒有主動查單或排程對帳」；新增「只允許 stage」「demo 預設啟用與如何關閉」「§11 實機驗證步驟」三節；保留官方來源連結
- [ ] 22. `application-ecpay-stage.yml` 的三個測試特店值與 `docs/ECPAY.md` 一致，且檔內有註明「綠界官方公開測試特店，不是正式憑證」

## 14. 測試要求

| 測試檔 | 階段 | 需要 Spring context | 重點 |
| --- | --- | --- | --- |
| `EcpayStageGuardTest` | S1 | 部分（啟動失敗用 `SpringApplicationBuilder` 或 `ApplicationContextRunner`） | 驗收 1、2、4、5、6、8；拒絕樣本以 `@ParameterizedTest` 列出 |
| `EcpayLogHygieneTest` | S1／S2 | 是 | 驗收 10、11、16 |
| `ecpay.test.ts` | S1 | — | 驗收 7 |
| `FakeEcpayServer` | S2 | — | 測試輔助，不是測試 |
| `EcpayStageEndToEndTest` | S2 | 是（`RANDOM_PORT`） | 驗收 9、12–15 |
| `EcpayNoEgressGuardTest` | S2 | 否 | 驗收 18 |
| `EcpayStageProfileTest` | S3 | 是 | 驗收 19 |

- 手動對帳請求要用 seed 的店長帳號、真實登入與 CSRF（§8）
- 越權：本規格無新端點；端到端測試加一條「他店店長對本店訂單手動對帳 → 403，且假伺服器 `receivedQueries()` 沒有增加」（權限檢查必須在連外之前）
- 併發：既有 `ReconciliationTest.concurrentManualQueriesAreThrottled` 已覆蓋，不重做
- 時間：`PaymentDate` 一律以 `ZoneId.of("Asia/Taipei")` 產生；不得使用無參數 `now()`（G27 的 `TimeZoneGuardTest` 會擋）
- **實作回報（必交）**：`docs/reports/G01b-ecpay-stage-integration.md`，至少含：各階段完成狀態、§10.2 每個情境對應的測試方法名、驗收 17 列出的既有測試是否全數未改、實作中發現的規格問題。G20k 漏交回報（見 `GAP-ANALYSIS.md`），本規格起缺檔即退回
- 分支：`codex/g01b-ecpay-stage`

## 15. 設計決策

### 15.1 只允許 stage：在後端啟動時擋，而不是只靠設定預設值

**決定：** `ecpay.environment` 不是 `stage` 就拒絕啟動，不論金流是否啟用；同時把程式碼裡所有 `production` 分支與正式網域刪掉（後端、前端允許清單、CSP）。
**理由：** PO 硬規則 1。只改預設值的話，設錯一個環境變數就會連到正式環境；只擋 `enabled=true` 的話，對帳的 `EcpayTradeQuery` 是獨立 bean，各自讀 `environment`，容易漏一個。刪掉程式碼分支讓「連到正式環境」在這個版本裡**不存在可執行路徑**，而不只是被擋住。
**推翻它的代價：** 正式上線那一輪要 (1) 拿掉這道防線、(2) 把正式網域加回後端兩處、前端一處、CSP 一處、(3) 重新設計正式憑證的注入方式（屬 PO 的帳號與憑證）。四處都是單行常數，成本低；刻意不保留「先關掉的 production 分支」，因為留著就會有人把它打開。

### 15.2 `https` 限制改為 WARN，而不是維持拒絕啟動

**決定：** `app.public-url` 不是 `https` 時仍可啟用金流，只記 WARN。
**理由：** PO 要求 demo 預設開啟對帳，而 demo 是 `http://localhost`。主動查單不依賴回呼，是完整的補救路徑；擋住啟動等於連補救路徑都關掉。原本的限制是為了「正式收款時回呼一定要送得到」，在只允許 stage 的版本裡這個風險不存在。
**推翻它的代價：** §11 步驟 3 若發現綠界拒絕非 HTTPS 的 ReturnURL，demo 就必須設通道網址才能付款；屆時可以改成「非 HTTPS 時 ReturnURL 改填一個固定的佔位 HTTPS 網址」——一行改動。正式上線那一輪應恢復為拒絕啟動。

### 15.3 demo 金流設定獨立成 `ecpay-stage` profile，不放進 `dev`

**決定：** 新增 `application-ecpay-stage.yml`，只由 `start-demo.sh` 帶上；`application-dev.yml` 不改。
**理由：** 至少 5 支測試用 `@ActiveProfiles("dev")`，放進 `dev` 會讓它們的 context 帶著會連外的排程（§5.4）。獨立 profile 讓「demo 預設開啟」與「CI 不連外」兩條硬規則同時成立，而且 `EcpayNoEgressGuardTest` 只需要追蹤一個 profile 名稱。
**推翻它的代價：** 若日後要把金流併回 `dev`，必須先讓所有 `@ActiveProfiles("dev")` 的測試覆寫 `ecpay.query-url` 或關掉 `ecpay.enabled`，並改 `EcpayNoEgressGuardTest` 的規則。

### 15.4 demo 對帳節奏：每 2 分鐘、訂單滿 3 分鐘才查

**決定：** `ecpay-stage` profile 設 `interval-ms=120000`、`min-age-minutes=3`；正式設定的預設值（5 分鐘、15 分鐘）不變。
**理由：** demo 的目的是讓人看得到「回呼送不到也會自己補上」，等 15–20 分鐘沒有人會等。3 分鐘是綠界付款頁走完一次的合理上限，避免顧客還在付款頁時就被查成 `STILL_UNPAID` 灌滿紀錄。每 2 分鐘、每批最多 50 筆，對 stage 的查單量在 demo 規模下可以忽略。
**推翻它的代價：** 改 yml 兩個值。G04 進場時要重看：逾時作廢的門檻必須大於 `min-age`，否則會作廢一張還沒被查過的訂單（G04 規格會寫這條互斥規則）。

### 15.5 不做顧客端「幫我查一下付款」端點

**決定：** 顧客從綠界返回後，訂單頁不主動觸發查單；仍靠回呼或排程對帳。
**理由：** 顧客可觸發的連外查詢是新的濫用面（需要限流、需要 SELF 範圍的權限設計），而它解決的只是 demo 時「回呼送不到的那幾分鐘」。手動對帳端點（店長）與排程已能覆蓋，規格不為了 demo 的等待時間新增一個對外攻擊面。
**推翻它的代價：** 新增 `POST /api/orders/{id}/payment-check`（SELF、沿用 G20j 的限流設施、內部呼叫 `Reconciliation`），一個端點加一組越權測試，約半個施工階段。

### 15.6 同一訂單重複送出付款表單，本規格不處理

**決定：** `MerchantTradeNo` 維持等於訂單編號；顧客第二次按付款時綠界的行為（可能回「訂單編號重複」）列為 §11 步驟 5 的觀察項。
**理由：** 改成「每次付款嘗試產生新的 `MerchantTradeNo`」會牽動回呼與查單的對應鍵（`MerchantTradeNo` 目前就是訂單編號，回呼與對帳都靠它找訂單），需要新的對應表與 migration，而且與 G04 的逾時作廢直接相關（作廢前要查**所有**付款嘗試）。在不知道綠界實際行為前就設計它，是猜。
**推翻它的代價：** G04 規格依步驟 5 的結果決定；若需要付款嘗試表，那是 G04 的 `V15`。

### 15.7 假伺服器用 JDK `HttpServer`，放在 `coffee-app` 的測試裡

**決定：** 不引入 WireMock／MockWebServer；假伺服器是一個測試輔助類別。
**理由：** PO 硬規則 4 指定優先零新依賴；實作端環境曾解析不到 Maven Central（G20j 報告）。綠界的查單協定是單一 POST、表單編碼、無狀態，JDK `HttpServer` 綽綽有餘。放在 `coffee-app` 測試是因為所有整合測試都在那裡（`AGENTS.md`「測試」一節）。
**推翻它的代價：** 若日後要模擬的綠界 API 變多（退款、電子發票），可以把它升格為獨立的 test-fixtures 模組；介面不變。

### 15.8 不改 `TradeQuery` 介面、不改 `perform()` 的判斷邏輯

**決定：** 本規格只改傳輸層（端點）與防線，不碰對帳的判斷流程。
**理由：** G01／G01a 的判斷邏輯已有完整單元測試且經兩輪審查；這次缺的是「真的走一次 HTTP」。改判斷邏輯會讓兩種風險混在同一個 PR 裡，審查時分不清紅燈是傳輸還是邏輯。
**推翻它的代價：** 無；判斷邏輯若在 §11 實機驗證時發現問題，另立修正規格。

## 16. 與其他工作的關係

- **G04（下一份規格）**：依賴本規格的假伺服器（S2）驗證「作廢前先查單」。G04 開工前提是本規格 **S2 已合併**
- **G01a 三項非阻斷觀察**：都在 `coffee-orders`，由 G04 夾帶（§2.2）
- **G20j 限流設施**：本規格不使用；§15.5 若被推翻才會用到
- **Flyway**：本規格零 migration，`V15` 留給下一份需要的規格

## 修訂紀錄

| 版本 | 日期 | 變更 |
| --- | --- | --- |
| v1.0 | 2026-10-11 | 初版。PO 於同日解除金流延後、只串 stage |
| v1.1 | 2026-10-12 | 依 Codex 審查修正 §10.1：假伺服器明確綁 `127.0.0.1`（不用 `getLoopbackAddress()`，避免 IPv6 `::1` 不一致）；`close()` 必須關閉自持的 daemon executor；新增驗收 9a；§2 查單情境數統一為 8 種 |
