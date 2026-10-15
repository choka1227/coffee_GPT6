# G02 — 綠界 B2C 電子發票（只串 stage）

版本：v1.2（2026-10-15）
作者：Claude（PM / SA）
前置：G01b（PR #87）、G04（PR #90）已合併。本規格**不依賴**任何尚未合併的工作，S1 可以立刻開工。
決策依據：PO 2026-10-14 決策「G02 電子發票、G03 退款與退單解除延後，接續金流線」，沿用 2026-10-11 決策的 stage 硬規則（GAP-ANALYSIS「PO 決策：金流解除延後，只串測試環境」）。
後續：G03（[`G03-order-refunds.md`](G03-order-refunds.md)）的 S2 依賴本規格 S2 的發票用戶端與假伺服器。

---

## 1. 背景與目標

台灣營業人每一筆銷售都要開立統一發票。系統目前完全沒有發票：沒有發票號碼、沒有買受人載具資料、也沒有任何與加值中心的串接。GAP-ANALYSIS 原本擋住它的理由是「加值中心未定」—— 金流已經選定綠界（G01／G01b），電子發票也用綠界，這個理由就不存在了。

**目標：**

1. 每一筆**已付款**訂單（現金與線上付款都算）自動向綠界 **stage** 開立一張 B2C 電子發票，發票號碼回存系統，顧客與店員看得到
2. 結帳時收集發票開立方式：**雲端發票（綠界載具，寄 email）／手機條碼載具／捐贈**
3. 開立是**非同步、可重試、不重複開立**的：連線失敗、逾時、回應不明時，下一輪先查詢再決定要不要重開；同一筆訂單絕不開出兩張發票
4. 開立失敗的發票**不沉默**：出現在店長的對帳畫面，可在修正後手動重試
5. CI 不連外：所有自動化測試打本機假伺服器

## 2. 範圍

### 2.1 在範圍內

- 訂單建立時收集發票資訊（`Orders.Create` 新增選填欄位，啟用後改為必填）
- `invoices` 表（發票狀態與號碼）、`invoice_cursors` 表（掃描游標）
- 綠界 B2C 電子發票 JSON／AES 信封的編解碼、`Issue` 與 `GetIssue` 兩支 API 的用戶端
- 開立排程：掃描新付款訂單 → 建立待開立列 → 開立 → 不明結果先查詢再重開
- 開立失敗清單與手動重試端點、訂單的發票查詢端點
- 前端：結帳表單（顧客點餐與 POS）、訂單明細顯示發票號碼、對帳頁的開立失敗區塊
- stage 防線、憑證放置、本機假伺服器、stage 實機驗證步驟、文件

### 2.2 不在範圍內

- **作廢、折讓** —— 由 G03（退款）處理。本規格的 `invoices.status` 先預留 `SKIPPED` 給 G03 用（§4）
- **紙本發票（`Print=1`）、統編（B2B，`CustomerIdentifier`）、自然人憑證與悠遊卡等其他載具** —— 理由見 §14.3
- **以 `CheckBarcode`／`CheckLoveCode` 向綠界即時驗證手機條碼與捐贈碼** —— 只做格式驗證，理由見 §14.5
- **字軌設定 API**（`InvoiceWordSetting` 等）—— stage 的共用測試特店由綠界提供模擬字軌；若實機發現需要自行設定，記錄在 §11 步驟 6，由下一輪規格處理
- **逐品項明細** —— 發票只開一行「餐飲費用」，理由見 §14.4
- **補開歷史訂單** —— 游標從啟用時刻往前推 `lag-seconds` 開始：付款時間早於「啟用時刻 − lag」的訂單永遠不開立，精確邊界與理由見 §6.5 步驟 1、§14.6
- 綠界正式環境、正式特店憑證（屬 PO 的「帳號與憑證」，**不得出現在 repo**）

## 3. 涉及模組與邊界

| 模組 | 變更 | 邊界影響 |
| --- | --- | --- |
| `coffee-orders` | `api/Orders.java`：`Create` 新增 `invoice` 欄位、`Order` 新增 `invoice` 欄位、新增 `InvoiceRequest`／`InvoiceCandidate` record 與 `invoiceCandidates`、`invoiceCandidate` 兩個方法；`internal/OrderService.java` 實作與驗證 | 無新依賴 |
| `coffee-payments` | 新增 `internal/EinvoiceCodec.java`、`internal/EcpayInvoiceClient.java`、`internal/InvoiceService.java`、`internal/InvoiceController.java`；`api/` 新增 `Invoices.java` | 無新依賴（已依賴 `orders.api`）。**只透過 `Orders` 讀訂單**，不查 `orders` 表 |
| `coffee-app` | `V16__einvoice.sql`、`application.yml`、`application-ecpay-stage.yml`、測試、新增 `ecpay/FakeEinvoiceServer.java` | — |
| `frontend` | `modules/ordering/MenuView.vue`、新增 `modules/ordering/invoice.ts`（純函式）與 `.spec.ts`、`modules/ordering/OrdersView.vue`、`modules/payments/ReconciliationView.vue`、`shared/types.ts`、`shared/api.ts`（若需要）、DOM 測試 | 無 |
| 文件 | `docs/ECPAY.md`、`docs/reports/G02-ecpay-einvoice.md` | — |

- **電子發票放在 `coffee-payments`，不另開模組。** 理由見 §14.1
- **零新依賴**（Maven 與 npm 都是）。AES 用 JDK 內建 `javax.crypto`，JSON 用 Spring Boot 已帶的 Jackson，HTTP 用 G01b 的 `EcpayTradeQuery` 同一套 JDK `HttpClient` 作法，假伺服器用 JDK `com.sun.net.httpserver.HttpServer`

## 4. DB schema —— `V16__einvoice.sql`

**Flyway 版號 `V16`**：主線最高是 `V15__payment_expiry.sql`（G04）。開工前用 `ls backend/coffee-app/src/main/resources/db/migration/` 與 open PR 再確認一次沒有人佔用 `V16`。G03 會用 `V17`。

```sql
-- 1) 訂單上的買受人發票資訊（coffee-orders 擁有）。舊訂單全為 NULL。
ALTER TABLE orders ADD COLUMN invoice_type VARCHAR(8);
ALTER TABLE orders ADD COLUMN invoice_email VARCHAR(100);
ALTER TABLE orders ADD COLUMN invoice_carrier VARCHAR(8);
ALTER TABLE orders ADD COLUMN invoice_love_code VARCHAR(7);
ALTER TABLE orders ADD CONSTRAINT ck_orders_invoice_type
  CHECK(invoice_type IS NULL OR invoice_type IN ('CLOUD','MOBILE','DONATE'));

-- 2) 發票（coffee-payments 擁有）。一張訂單最多一張發票。
CREATE TABLE invoices(
  order_id VARCHAR(20) PRIMARY KEY REFERENCES orders(id),
  branch_id VARCHAR(36) NOT NULL REFERENCES branches(id),
  amount INTEGER NOT NULL,
  status VARCHAR(12) NOT NULL,
  invoice_no VARCHAR(10),
  invoice_date VARCHAR(19),
  random_number VARCHAR(4),
  attempts INTEGER NOT NULL DEFAULT 0,
  uncertain BOOLEAN NOT NULL DEFAULT FALSE,
  claim_token VARCHAR(36),
  claimed_until BIGINT,
  last_error VARCHAR(200),
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  issued_at BIGINT,
  CONSTRAINT uq_invoices_invoice_no UNIQUE(invoice_no),
  CONSTRAINT ck_invoices_amount CHECK(amount>0),
  CONSTRAINT ck_invoices_attempts CHECK(attempts>=0),
  CONSTRAINT ck_invoices_status CHECK(status IN ('PENDING','ISSUED','FAILED','SKIPPED')),
  CONSTRAINT ck_invoices_issued CHECK(status<>'ISSUED' OR (invoice_no IS NOT NULL AND invoice_date IS NOT NULL)),
  CONSTRAINT ck_invoices_claim CHECK((claim_token IS NULL AND claimed_until IS NULL) OR (claim_token IS NOT NULL AND claimed_until IS NOT NULL AND status='PENDING'))
);
CREATE INDEX idx_invoices_status ON invoices(status,updated_at);
CREATE INDEX idx_invoices_branch_status ON invoices(branch_id,status,updated_at);

-- 3) 掃描游標。G02 只用 name='PAID'；G03 會新增 name='REFUNDED' 一列（insert，不改表）。
CREATE TABLE invoice_cursors(
  name VARCHAR(16) PRIMARY KEY,
  at_ms BIGINT NOT NULL,
  order_id VARCHAR(20) NOT NULL
);
```

- **CHECK 與 UNIQUE 一律具名，不得寫成欄位上的 inline 約束**。本表共五個 CHECK（`ck_invoices_amount`、`ck_invoices_attempts`、`ck_invoices_status`、`ck_invoices_issued`、`ck_invoices_claim`）與一個 UNIQUE（`uq_invoices_invoice_no`），加上 `orders` 的 `ck_orders_invoice_type`。G04 §14.8 記錄過 V1／V2 的無名 CHECK 跨 H2／PostgreSQL 改不動；這次先具名，日後要擴充值域時可以 `DROP CONSTRAINT ck_… ` 再加
- **索引**：兩個 `CREATE INDEX`（`idx_invoices_status`、`idx_invoices_branch_status`）。`order_id` 與 `invoice_cursors.name` 的主鍵、`uq_invoices_invoice_no` 由資料庫自動建索引，不另建
- `claim_token`／`claimed_until` 是 §6.5 的排他認領（租約）：兩者同時為 NULL（沒有持有者）或同時有值（有持有者，且只能在 `PENDING` 時持有，`ck_invoices_claim`）。`claim_token` 存 `UUID.randomUUID().toString()`
- `SKIPPED` 在 G02 **沒有任何程式會寫入**，保留給 G03（「發票還沒開就退款了，不用開」）。先放進 CHECK 是為了讓 G03 不必動這條約束
- `invoice_carrier` 長度 8：手機條碼固定 8 碼（`/` + 7 碼）
- 游標表**不預先 insert**：第一次排程執行時才建立（§6.4 步驟 1），所以啟用前的歷史訂單不會被開立
- `invoice_date` 存綠界回傳的字串原樣（`yyyy-MM-dd HH:mm:ss`），因為 G03 的作廢與折讓 API 要原樣帶回去

## 5. 設定

### 5.1 `application.yml`（正式設定的預設值）—— 只新增，預設關閉、不含任何特店資料

```yaml
orders:
  invoice-required: ${ORDERS_INVOICE_REQUIRED:false}

ecpay:
  invoice:
    enabled: ${ECPAY_INVOICE_ENABLED:false}
    environment: ${ECPAY_INVOICE_ENVIRONMENT:stage}
    merchant-id: ${ECPAY_INVOICE_MERCHANT_ID:}
    hash-key: ${ECPAY_INVOICE_HASH_KEY:}
    hash-iv: ${ECPAY_INVOICE_HASH_IV:}
    base-url: ${ECPAY_INVOICE_BASE_URL:https://einvoice-stage.ecpay.com.tw}
    timeout-ms: 10000
    interval-ms: ${ECPAY_INVOICE_INTERVAL_MS:300000}
    batch-size: 20
    lag-seconds: 120
    max-attempts: 10
    claim-lease-ms: 900000
```

### 5.2 `application-ecpay-stage.yml`（demo profile）—— 追加

```yaml
orders:
  invoice-required: ${ORDERS_INVOICE_REQUIRED:true}

ecpay:
  invoice:
    enabled: ${ECPAY_INVOICE_ENABLED:true}
    environment: stage
    # 綠界官方公開的「電子發票」測試特店，與金流測試特店 3002607 是不同的兩組，不可混用。
    merchant-id: ${ECPAY_INVOICE_MERCHANT_ID:2000132}
    hash-key: ${ECPAY_INVOICE_HASH_KEY:ejCk326UnaZWKisg}
    hash-iv: ${ECPAY_INVOICE_HASH_IV:q9jcZX8Ib9LM8wYk}
    interval-ms: ${ECPAY_INVOICE_INTERVAL_MS:60000}
```

**為什麼可以提交預設值：** 這三個值是綠界公開的測試特店參數，不是機密；`AGENTS.md`「安全」一節明列這個例外（只限 demo／開發 profile，`application.yml` 一律留空），先例是 G01b 已合併的金流測試特店 `3002607`。正式特店憑證仍走環境變數、不得進 Git。

**憑證來源（必讀）：** `2000132`／`ejCk326UnaZWKisg`／`q9jcZX8Ib9LM8wYk` 取自綠界官方 GitHub `ECPay/ECPay-API-Skill` 的 SDK 範例 `scripts/SDK_PHP/example/Invoice/B2C/Issue.php`，官方頁面是「測試介接資訊」（developers.ecpay.com.tw/7849/）。**撰寫本規格的環境連不上 developers.ecpay.com.tw**，MerchantID 已由該頁的搜尋摘要對上，HashKey／HashIV 只由官方 SDK 範例確認。實作端若能連上官方頁面，開工時再核對一次；不一致以官方頁面為準，並寫進實作回報。**注意：同一個 `2000132` 在物流 API 用的是另一組 key（`5294y06JbISpM5x9`／`v77hoKGq4kWxNNIS`），不要拿錯。**

### 5.3 啟動檢查（`InvoiceService` 建構子）

`ecpay.invoice.enabled=true` 時，下列任一不成立就拋 `IllegalStateException`，**context 啟動失敗**。訊息**不得含任何設定值**（只說哪一項不對）：

1. `environment` 等於 `stage`（不分大小寫比對**不允許**，必須完全等於小寫 `stage`，與 `EcpayService` 一致）
2. `merchant-id`、`hash-key`、`hash-iv` 皆非空白；`hash-key`、`hash-iv` 各為 16 字元（AES-128）
3. `base-url` 是**精確的** `https://einvoice-stage.ecpay.com.tw`，或 `http://127.0.0.1:<port>`／`http://localhost:<port>`（必須有明確連接埠，給假伺服器用）。其他一律拒絕 —— 正式網域、其他主機、`https://127.0.0.1`、帶路徑或查詢字串的都拒絕。**比照 `EcpayTradeQuery.validateEndpoint` 的寫法**
4. `orders.invoice-required` 為 `true`（否則會一直累積「缺少發票資訊」的失敗列，§14.7）
5. `timeout-ms` 在 1000–30000、`interval-ms` ≥ 10000、`batch-size` 在 1–100、`lag-seconds` 在 30–3600、`max-attempts` 在 1–100、`claim-lease-ms` 在 `STALE_REQUEST_WINDOW_MS + 3 × timeout-ms + 60000` 到 3600000 之間（`STALE_REQUEST_WINDOW_MS = 660000` 是程式常數，不是設定，§6.5、§14.13）

`enabled=false` 時不做任何檢查，任何值都能啟動。

**正式網址字串 `einvoice.ecpay.com.tw` 不得出現在 `src/main`。** 只會出現在 §10.4 守門測試的允許清單檔案裡。

## 6. 後端變更

### 6.1 `Orders` 介面（`coffee-orders/api/Orders.java`）—— 只新增

```java
record InvoiceRequest(String type, String email, String carrier, String loveCode) {}

// Create 尾端新增一個欄位（record 新增元件會改到所有 new Create(...) 呼叫點，見下方說明）
record Create(
    String branchId, String fulfillment, String paymentMethod, String note,
    String discountCode, List<LineInput> items, InvoiceRequest invoice) {}

// Order 尾端新增一個欄位；舊訂單為 null
//   ... existing components ..., InvoiceRequest invoice

record InvoiceCandidate(
    String orderId, String branchId, int total, long paidAt, InvoiceRequest invoice) {}

/** 依 (paid_at, id) 遞增取 afterAt/afterId 之後、paid_at <= until 的已付款訂單，最多 limit 筆。 */
List<InvoiceCandidate> invoiceCandidates(long afterAt, String afterId, long until, int limit);

/** 單筆讀取（開立前重讀，確認仍為已付款）；不存在回 Optional.empty()。 */
Optional<InvoiceCandidate> invoiceCandidate(String orderId);
```

- **`Create` 新增元件是破壞性的原始碼變更**：所有 `new Orders.Create(...)` 呼叫點都要補一個 `null`。實作時用 `grep -rn "new Orders.Create\|new Create(" backend` 找全；**只允許補 `null`，不得改其他引數**。Jackson 反序列化時缺欄位即為 `null`，所以 HTTP 端點對舊前端是相容的
- `invoiceCandidates` 的條件：`paid_at IS NOT NULL AND paid_at <= until AND (paid_at > afterAt OR (paid_at = afterAt AND id > afterId))`，`ORDER BY paid_at, id`。**不看 `status`**（`PAID`／`PREPARING`／`READY`／`COMPLETED` 都已付款；`CANCELLED` 的線上訂單 `paid_at` 為空，自然不會出現）
- 這兩個方法**沒有 `Actor` 參數**，是系統內部用（與 `reconciliationCandidates` 同類）。不得從任何 Controller 直接暴露

### 6.2 訂單建立時的發票資訊驗證（`OrderService.create`）

在既有驗證之後、計價之前：

| 條件 | 結果 |
| --- | --- |
| `invoice == null` 且 `orders.invoice-required=false` | 通過，四個欄位寫 NULL（今天的行為） |
| `invoice == null` 且 `orders.invoice-required=true` | 400 `請選擇發票開立方式` |
| `type` 不是 `CLOUD`／`MOBILE`／`DONATE` | 400 `發票開立方式不正確` |
| `email` 空白、超過 100 字元、或不符 `^[^@\s]+@[^@\s]+\.[^@\s]+$` | 400 `請輸入正確的發票通知 email` |
| `MOBILE` 且 `carrier` 不符 `^/[0-9A-Z.+-]{7}$` | 400 `手機條碼格式不正確` |
| `DONATE` 且 `loveCode` 不符 `^[0-9]{3,7}$` | 400 `捐贈碼格式不正確` |

- `email` 先 `trim()`；`carrier` 先 `trim().toUpperCase()`；`loveCode` 先 `trim()`
- **不相關的欄位一律存 NULL**：`CLOUD` 不存 `carrier`／`loveCode`；`MOBILE` 不存 `loveCode`；`DONATE` 不存 `carrier`。前端送了也忽略，不報錯
- **email 三種方式都必填**：綠界 `Issue` 要求 `CustomerEmail` 或 `CustomerPhone` 擇一（§14.2）
- **冪等指紋**：`request_hash` 的計算**要納入** `invoice`（同一個 `Idempotency-Key` 換了發票資訊視為不同請求，409）。用既有的指紋計算方式把四個欄位串進去即可
- `orders.invoice-required` 以 `@Value("${orders.invoice-required:false}")` 注入 `OrderService`
- `preview()` 不收發票資訊，不變

### 6.3 AES 信封（`coffee-payments/internal/EinvoiceCodec.java`，純函式、無 Spring）

```java
final class EinvoiceCodec {
  static String encrypt(String json, String hashKey, String hashIv);   // → Base64
  static String decrypt(String base64, String hashKey, String hashIv); // → JSON
  static String phpUrlEncode(String s);
  static String phpUrlDecode(String s);
}
```

- `encrypt`：`phpUrlEncode(json)` → `AES/CBC/PKCS5Padding`（key／iv 為 `hashKey`／`hashIv` 的 UTF-8 bytes，各 16 bytes）→ 標準 Base64（有 padding、不換行）
- `decrypt`：反向，最後 `phpUrlDecode`
- `phpUrlEncode`：`URLEncoder.encode(s, UTF_8)` 之後把 `*` 換成 `%2A`（PHP `urlencode` 只保留 `-_.` 與英數，Java 另外保留了 `*`；空白兩者都是 `+`、`~` 兩者都是 `%7E`）。**不是** `CheckMac` 用的小寫 .NET 編碼，**不要重用 `CheckMac` 的編碼函式**
- 任何解密或解碼失敗都丟 `EinvoiceException`（package-private 例外），**訊息不得含 key、iv、明文或密文**

### 6.4 用戶端（`coffee-payments/internal/EcpayInvoiceClient.java`）

只實作 G02 需要的兩支；G03 會在同一個類別**新增** `invalid`、`allowance`。

```java
sealed interface CallResult permits Ok, Rejected, Unknown {}
record Ok(Map<String,Object> data) implements CallResult {}            // TransCode==1 且 RtnCode==1
record Rejected(int rtnCode, String rtnMsg) implements CallResult {}   // TransCode==1 且 RtnCode!=1
record Unknown(String reason) implements CallResult {}                 // 其餘一切：連線失敗、逾時、非 200、重新導向、TransCode!=1、解密失敗、JSON 錯誤

CallResult issue(InvoiceCandidate c);
CallResult getIssueByRelateNumber(String orderId);
```

**請求格式**（`POST {base-url}/B2CInvoice/Issue`、`/B2CInvoice/GetIssue`，`Content-Type: application/json`）：

```json
{"MerchantID":"<id>","RqHeader":{"Timestamp":<epoch 秒>,"Revision":"3.0.0"},"Data":"<EinvoiceCodec.encrypt(dataJson)>"}
```

`Issue` 的 `Data` 明文（欄位名稱大小寫照抄）：

| 欄位 | 值 |
| --- | --- |
| `MerchantID` | 特店編號（**外層與 Data 內都要有**） |
| `RelateNumber` | 訂單編號（`CF` + 18 碼，20 字元；綠界上限 30） |
| `CustomerEmail` | 發票通知 email |
| `Print` | `"0"` |
| `Donation` | `DONATE` → `"1"`，其餘 `"0"` |
| `LoveCode` | `DONATE` → 捐贈碼，其餘 `""` |
| `CarrierType` | `CLOUD` → `"1"`、`MOBILE` → `"3"`、`DONATE` → `""` |
| `CarrierNum` | `MOBILE` → 手機條碼，其餘 `""` |
| `TaxType` | `"1"`（應稅） |
| `SalesAmount` | 訂單 `total`（整數） |
| `InvType` | `"07"` |
| `vat` | `"1"`（含稅價） |
| `Items` | 一筆：`{"ItemSeq":1,"ItemName":"餐飲費用","ItemCount":1,"ItemWord":"式","ItemPrice":total,"ItemTaxType":"1","ItemAmount":total}` |

`GetIssue` 的 `Data` 明文：`{"MerchantID":"<id>","RelateNumber":"<訂單編號>"}`。

**回應判讀：** HTTP 200 → 解析外層 JSON → `TransCode == 1` → 解密 `Data` → `RtnCode == 1` 回 `Ok`，否則 `Rejected`。其餘一律 `Unknown`。
- `Issue` 的 `Ok.data` 取 `InvoiceNo`、`InvoiceDate`、`RandomNumber`
- `GetIssue` 的 `Ok.data` 取 `IIS_Number`、`IIS_Create_Date`、`IIS_Random_Number`、`IIS_Sales_Amount`、`IIS_Invalid_Status`（G03 要用）、`IIS_Remain_Allowance_Amt`（G03 要用）。**欄位名稱以 §11 步驟 3 的實機結果為準**；本規格依官方 SDK 指南撰寫，實作端若能讀到官方頁面而發現名稱不同，以官方為準並寫進回報

**連線規則：** 比照 `EcpayTradeQuery`：JDK `HttpClient`、`connectTimeout` 與請求逾時都用 `timeout-ms`、`followRedirects(NEVER)`（3xx 一律 `Unknown`）。

**Log 規則：** 只准記 `orderId`、`RtnCode`、`RtnMsg`（截到 100 字）、`Unknown` 的原因類別（`TIMEOUT`／`CONNECT`／`HTTP_<code>`／`TRANS_CODE`／`DECRYPT`／`JSON`）。**不得記** key、iv、`Data` 密文或明文、email、手機條碼、捐贈碼、例外堆疊（WARN 一行，不印 stack trace）。

### 6.5 開立排程（`coffee-payments/internal/InvoiceService.java`）

`@Scheduled(fixedDelayString="${ecpay.invoice.interval-ms:300000}")`，`enabled=false` 時方法開頭直接 return。**一輪做三件事，依序：**

**步驟 1 —— 入列。** 讀游標 `invoice_cursors` 的 `PAID` 列；不存在就 insert `(PAID, T0 - lag-seconds*1000, '')`（`T0` 為這一輪的 `now`）並結束步驟 1（第一次執行只建立游標，§14.6）。存在就以 `until = now - lag-seconds*1000` 呼叫 `orders.invoiceCandidates(at, id, until, 200)`；對每一筆 `insert into invoices … where not exists`（`status=PENDING`、`amount=total`、`branch_id`）；最後把游標推進到這批的最後一筆。**同一個交易**完成 insert 與游標推進。一輪最多入列 200 筆，剩下的下一輪繼續。

**游標的精確邊界（產品語意，實作不得另作解讀）：** 令初始化後的游標為 `(C, '')`，`C = T0 - lag`。因為訂單編號一定非空、`id > ''` 恆成立，`invoiceCandidates` 的條件化簡為 `paid_at >= C`：

| 訂單的 `paid_at` | 結果 |
| --- | --- |
| `< C`（例：`C - 1` 毫秒） | **永遠不入列** |
| `== C`（與截止點同毫秒） | 會入列 |
| `C < paid_at < T0`（啟用前、落在 lag 窗內，例：`T0 - 1` 秒） | 會入列，在第一個 `until >= paid_at` 的輪次 |
| `>= T0`（啟用後付款） | 會入列，在 `paid_at <= now - lag` 之後的第一輪 |

一句話：**「啟用時刻往前 lag 秒」之後付款的都開立，之前的都不開。** 之所以不是「啟用時刻之後付款的才開」，理由見 §14.6。

**步驟 2 —— 開立。** 取 `status='PENDING'` 依 `updated_at` 最舊的 `batch-size` 筆，逐筆 `issueOne(orderId)`。**每筆之間不共用交易**；HTTP 呼叫期間**不持任何鎖、不開交易**。

`issueOne`：

1. `orders.invoiceCandidate(orderId)` 重讀，並讀這一列 `invoices`（記下讀到的 `uncertain`，記為 `wasUncertain`）。訂單資料的 `invoice` 為 `null` → `update … set status='FAILED', uncertain=false, last_error='訂單缺少發票資訊' where order_id=? and status='PENDING' and claim_token is null`，結束（沒有發票資訊就不可能呼叫過 `Issue`，所以結果是確定的；重試端點把它設回 `true` 也一樣在這一步被改回 `false`）
2. **排他認領（租約）**：產生新的 `token`，執行

   ```sql
   update invoices
      set claim_token=?, claimed_until=?(now + claim-lease-ms), uncertain=true, updated_at=?(now)
    where order_id=? and status='PENDING'
      and (claim_token is null or claimed_until < ?(now))
   ```

   **更新 0 列就結束、不呼叫綠界**（已有未過期的持有者，或這一列已被別的實例、重試端點或 G03 的退款掃描改走了）。只有這個 `update` 會設定 `claim_token`；`claimed_until < now` 才能接手，所以**同一時間最多一個持有者**。認領成功後，下列 3–5 步都以「持有者」身分進行
3. **送出期限**：持有者在**每一次** HTTP 呼叫之前檢查 `now + timeout-ms <= claimed_until - STALE_REQUEST_WINDOW_MS`（`STALE_REQUEST_WINDOW_MS = 660000`，11 分鐘）。不成立 → **不呼叫**，以持有者寫回（第 5 步）`attempts+1`、維持 `uncertain=true`，結束。這一條保證：任何持有者送出的請求，其 `RqHeader.Timestamp` 都早於 `claimed_until - 11 分鐘`；綠界拒收 `Timestamp` 超過 10 分鐘的請求（§14.13），所以**租約到期、別人可以接手的那一刻起，舊持有者的請求都不可能再在綠界成立**
4. 呼叫綠界（全程不開交易、不持鎖）：
   - 若 `wasUncertain` 或 `attempts > 0`：先 `getIssueByRelateNumber`
     - `Ok` 且 `IIS_Sales_Amount == amount` → 結果「開立」（號碼、日期、隨機碼取自查詢結果），跳到第 5 步
     - `Ok` 但金額不符 → 結果「失敗」、`last_error='綠界已有同編號發票但金額不符'`、**`uncertain` 維持 `true`**，跳到第 5 步（**不重開**）
     - `Rejected` → 查無此發票，繼續呼叫 `issue`（**不寫回任何中間狀態**，`uncertain` 在資料庫裡維持 `true`）
     - `Unknown` → 結果「不明」，跳到第 5 步
   - 呼叫 `issue`：
     - `Ok` → 結果「開立」
     - `Rejected` 且 `rtnCode == 10000009`（RelateNumber 重複）→ 同一個持有期間內**再查一次** `getIssueByRelateNumber`（只回一次，避免迴圈；仍要先過第 3 步的送出期限），依上面的查詢規則得出結果；查詢 `Rejected` → 結果「不明」
     - `Rejected` 其他 → 結果「拒絕」、`last_error='綠界拒絕開立（<RtnCode>）：<RtnMsg 截 100 字>'`
     - `Unknown` → 結果「不明」
5. **持有者寫回**：一律 `update invoices set …, claim_token=null, claimed_until=null, updated_at=now where order_id=? and claim_token=?(token)`，也就是**只有仍持有這個 token 的人寫得回去**：
   - 「開立」→ `status='ISSUED'`、`uncertain=false`、`issued_at=now`
   - 「拒絕」→ `status='FAILED'`、`uncertain=false`
   - 「失敗」（金額不符）→ `status='FAILED'`、`uncertain=true`
   - 「不明」→ `status` 維持 `PENDING`、`uncertain=true`、`attempts+1`；若 `attempts+1 >= max-attempts` → `status='FAILED'`、`last_error='連續 <n> 次無法確認開立結果'`，仍保留 `uncertain=true`（重試時一定先查詢）
6. **失去認領後的回應（更新 0 列）**：代表租約已到期且被別人接手，或這一列已被改走。
   - 結果是「拒絕」或「不明」→ 什麼都不做（現任持有者或下一輪會自己確定結果）
   - 結果是「開立」→ 這是「綠界確定有這張發票」的實證，**不能丟掉**。執行補償：`update invoices set status='PENDING', uncertain=true, claim_token=null, claimed_until=null, last_error='收到遲到的開立回應，待確認', updated_at=now where order_id=? and status in ('FAILED','SKIPPED')`；`status='PENDING'` 時改為 `update … set uncertain=true where order_id=? and status='PENDING'`（不動別人的認領）；`ISSUED` 時不動。WARN 一行（只記 `orderId` 與「遲到的開立回應」）。下一輪 `issueOne` 會照常認領、先查詢、寫成 `ISSUED`（G03 會在那時建立作廢列）。依第 3 步與 §14.13，這條路徑在正常運作下不應發生；它是時間假設失效時的第二道防線

**`uncertain` 與認領的不變式（G03 依賴這一條）：**

- `uncertain` 從 `false` 變成 `true` 只有兩條路：認領（第 2 步）與重試端點（§6.6）。從 `true` 變成 `false` **只有持有者寫回**（第 5 步的「開立」「拒絕」）與第 1 步的「缺少發票資訊」（該路徑不可能呼叫過綠界，且要求 `claim_token is null`）
- 因此 **`status in ('PENDING','FAILED') and uncertain=false and claim_token is null`** 的列，代表「綠界確定沒有這張發票，沒有任何持有者，也沒有任何還可能在綠界成立的舊請求」：
  - 寫入 `uncertain=false` 的持有者是當下唯一的持有者（第 2 步的排他條件）
  - 它之前的每一個持有者，其請求的 `Timestamp` 都早於自己的 `claimed_until - 11 分鐘`，而現任持有者是在那個 `claimed_until` 之後才接手的，所以那些請求在綠界都已過了 10 分鐘的有效期（第 3 步、§14.13）
  - 持有者寫回同時清掉 `claim_token`，所以「沒有持有者」與「`uncertain=false`」在同一個條件更新裡同時成立
- 任何人只要以 `where … and uncertain=false and claim_token is null` 的條件更新搶到這一列（G03 的退款掃描），就保證之後也不會有人呼叫 `Issue`：認領的條件要求 `status='PENDING'`，而那個條件更新已把它改成 `SKIPPED`；兩個條件更新作用在同一列，資料庫保證只有一個先成立
- 行程在持有期間當掉時，這一列留在 `PENDING`／`uncertain=true`／`claim_token` 有值；`claimed_until` 過後下一輪接手，先查詢，不會盲目重開

**步驟 3 —— INFO 摘要一行：** `綠界發票：入列 <n>、開立 <n>、失敗 <n>、待確認 <n>`。全為 0 時不印。

**為什麼不重開之前一定要先查：** 開立請求可能已經在綠界成功、只是回應沒回來。直接重開會因 `RelateNumber` 重複被拒（最好的情況），或在 `RelateNumber` 規則改變時開出第二張（最壞的情況）。先查是唯一不依賴對方行為的做法。

### 6.6 `Invoices` 介面與端點（`coffee-payments/api/Invoices.java`、`internal/InvoiceController.java`）

```java
public interface Invoices {
  record Invoice(String orderId, String branchId, int amount, String status,
                 String invoiceNo, String invoiceDate, String randomNumber,
                 int attempts, String lastError, long updatedAt) {}
  record InvoicePage(List<Invoice> items, boolean truncated) {}

  Optional<Invoice> forOrder(Actor a, String orderId);
  InvoicePage failed(Actor a, String branchId);
  Invoice retry(Actor a, String orderId);
}
```

| 方法 | 路徑 | 權限與資料範圍 | 回應 |
| --- | --- | --- | --- |
| GET | `/api/payments/invoices/order/{orderId}` | 先呼叫 `orders.get(a, orderId)`（它已經處理 SELF／BRANCH／GLOBAL 與 404），通過才查 `invoices` | 200 `Invoice`；尚無發票列 → 200 `{"status":"NONE"}`（其餘欄位 null） |
| GET | `/api/payments/invoices/failed?branchId=` | `PAYMENT_RECONCILE`、非顧客；非 GLOBAL 只看本店（帶他店 `branchId` → 403） | 200 `InvoicePage`，`updated_at` 新到舊，最多 200 筆，超過 `truncated=true` |
| POST | `/api/payments/invoices/order/{orderId}/retry` | `PAYMENT_RECONCILE`、非顧客、`a.branch(invoice.branchId)` | `FAILED` → 以 `where order_id=? and status='FAILED'` 條件更新為 `PENDING`、`attempts=0`、**`uncertain=true`**、`claim_token=null`、`claimed_until=null`、`last_error=null`，200（`FAILED` 列依 `ck_invoices_claim` 本來就沒有持有者）；不存在 404 `找不到這筆發票`；不是 `FAILED` → 409 `這張發票目前不需要重試` |

- **權限重用 `PAYMENT_RECONCILE`**，不新增權限（§14.8）
- `retry` 不在請求內呼叫綠界，只改狀態，交給下一輪排程。理由：避免顧客端以外又多一個可觸發連外呼叫的端點
- `retry` 寫一筆稽核？**不寫**（同 G04 §14.6：`coffee-payments` 不依賴 `coffee-audit`）。`updated_at` 與 `attempts` 歸零就是紀錄
- `GET /api/payments/config` 的回應新增 `invoice: {"enabled": bool}`，既有欄位不變；**不得**回傳 merchant id、key、iv、base-url
- 所有 POST 照常需要 CSRF

### 6.7 錯誤訊息

全部台灣用語繁體中文、寫給終端使用者。`last_error` 也是給店長看的，同樣是中文；綠界原始 `RtnMsg` 只附在括號後面。

## 7. 前端

- **`modules/ordering/invoice.ts`（純函式）**：`validateInvoice(input): string | null`（回錯誤訊息或 null，規則與 §6.2 **完全相同**）、`toInvoiceRequest(form): InvoiceRequest`（不相關欄位清成 `undefined`）。附 `invoice.spec.ts`，每條規則至少一正一反
- **`MenuView.vue`**：`config.invoice.enabled` 為 true 時，結帳區顯示「發票」欄位組：三選一（雲端發票／手機條碼／捐贈），email 必填；選手機條碼時出現條碼欄（自動轉大寫），選捐贈時出現捐贈碼欄。送出前呼叫 `validateInvoice`，不通過就顯示訊息、不送出。`checkout()` 的 payload 加 `invoice`。**POS（店員）與顧客點餐用同一個表單**。`enabled=false` 時完全不顯示、payload 不帶 `invoice`
- **email 記憶**：顧客上次用的 email 與手機條碼存在 `localStorage`（鍵 `coffee.invoice.last`，try/catch 包起來），下次預填。POS 模式**不記憶**（店員替不同客人結帳）
- **`OrdersView.vue` 訂單明細**：已付款訂單呼叫 `GET /api/payments/invoices/order/{id}`，顯示「發票號碼 `AB12345678`（隨機碼 `1234`）」；`PENDING` 顯示「發票開立中」；`FAILED` 對顧客顯示「發票開立中」、對店員顯示「發票開立失敗：<last_error>」；`NONE` 不顯示
- **`ReconciliationView.vue`**：新增「發票開立失敗」區塊（訂單編號、金額、失敗原因、最後更新時間、「重試」按鈕）；沒有失敗列時不顯示
- `shared/types.ts`：`InvoiceRequest`、`Invoice`、`InvoicePage`、`Order.invoice`、`PaymentConfig.invoice`

## 8. 權限與資料範圍

| 端點 | 顧客 | 收銀 | 店長 | 總部 |
| --- | --- | --- | --- | --- |
| 訂單發票查詢 | 自己的訂單 | 本店 | 本店 | 全部 |
| 開立失敗清單 | 403 | 403（沒有 `PAYMENT_RECONCILE`） | 本店 | 全部（可帶 `branchId` 篩選） |
| 重試 | 403 | 403 | 本店 | 全部 |

- 訂單發票查詢的資料範圍**完全交給 `orders.get(a, id)`**，不自己再寫一次判斷（同一條規則兩份實作會漂移）
- `Order.invoice`（含 email）隨訂單回應給「看得到這筆訂單的人」—— 與訂單其他欄位同一個範圍，不另行遮罩（§14.9）

## 9. 金額規則

- 發票金額 = 訂單 `total`（後端已依有效菜單與折扣重算的應收），由 `orders.invoiceCandidates` 提供，**`coffee-payments` 不自行計算任何金額**
- `invoices.amount` 在入列時快照；開立時以 `invoiceCandidate` 重讀的 `total` 為準，兩者不同 → 標 `FAILED`、`last_error='訂單金額已變動，請人工確認'`（正常情況不會發生，訂單付款後金額不可變；這是防線）
- `Issue` 的 `SalesAmount`、`ItemPrice`、`ItemAmount` 三者都等於 `total`，`ItemCount=1`
- 前端不送任何金額欄位；`invoice` 物件裡沒有金額

## 10. 本機測試設計

### 10.1 `FakeEinvoiceServer`（`coffee-app/src/test/java/com/coffee/app/ecpay/`，新檔）

JDK `HttpServer` 綁 `127.0.0.1:0`，提供 `/B2CInvoice/Issue` 與 `/B2CInvoice/GetIssue`。**它自己用 `EinvoiceCodec` 解密請求、加密回應**（測試同時驗證雙向編碼）。`EinvoiceCodec` 是 package-private 的話，假伺服器另寫一份 30 行的 AES 實作，或把 `EinvoiceCodec` 改成 `public final` 放在 `internal`（測試在 `coffee-app` 可以引用 —— `ModuleBoundariesTest` 只管業務模組之間，組裝層例外）。二選一，回報裡寫明。

**認領方法的可見性**：驗收 21b 要直接呼叫 §6.5 第 2 步的認領 SQL，所以把它寫成 `InvoiceService` 的 `public Optional<String> tryClaim(String orderId, long now)`（回傳 token），與 `public void issueOne(String orderId)` 同樣是 `public`、放在 `internal`。理由同上：測試在組裝層，可以引用 `internal`；業務模組之間仍不得引用。

**它會記錄**每一筆收到的請求（路徑 + 外層 `RqHeader.Timestamp` + 解密後的 Data JSON），供測試斷言 `RelateNumber`、`SalesAmount`、`CarrierType` 等欄位。

**掛勾**：每支 API 可設一個 `Consumer<Request>`，在**決定回應之前**同步執行（測試在裡面讀寫 DB、等 `CountDownLatch`）。驗收 21、21a–21e 用它製造交錯。

情境（`enum Scenario`，**每支 API 各自可設定**）：

| 情境 | `Issue` 的行為 | `GetIssue` 的行為 |
| --- | --- | --- |
| `OK` | 開立成功，回 `InvoiceNo`（`AB` + 8 位遞增）、`InvoiceDate`、`RandomNumber`；**同時記住這張發票** | 有記住的 → 回 `IIS_*`；沒有 → `RtnCode=1600003` |
| `DUPLICATE` | `RtnCode=10000009`（但若之前沒開過，先默默記住一張，模擬「上次其實成功了」） | 同 `OK` |
| `REJECTED` | `RtnCode=4000001`、`RtnMsg="參數錯誤"` | `RtnCode=1600003` |
| `SUCCEED_THEN_TIMEOUT` | **記住發票**，然後睡到逾時才回（模擬「成功但回應沒回來」） | 同 `OK` |
| `TIMEOUT` | 不記住，睡到逾時 | 睡到逾時 |
| `HTTP_500` | 回 500 | 回 500 |
| `TRANS_CODE_ERROR` | `TransCode=999`、`Data` 空 | 同 |
| `BAD_CIPHER` | `TransCode=1`、`Data` 是無法解密的亂碼 | 同 |
| `AMOUNT_MISMATCH` | — | 有記住的 → 回 `IIS_Sales_Amount` 為原金額 +1 |
| `REDIRECT_TO_PRODUCTION` | 302 到正式網址 | 同 |

「連線失敗」以 `base-url` 指向一個已關閉的連接埠模擬（測試裡先開一個 `ServerSocket` 拿連接埠再關掉）。

### 10.2 端到端測試（`EinvoiceEndToEndTest.java`，新檔）

比照 `EcpayExpiryEndToEndTest`：`@SpringBootTest(RANDOM_PORT, properties = {ecpay.invoice.enabled=true, environment=stage, 測試 merchant/key/iv, interval-ms=3600000（排程不自己跑，測試直接呼叫方法）, orders.invoice-required=true})`、`@ActiveProfiles("dev")`、`@DynamicPropertySource` 把 `ecpay.invoice.base-url` 指到假伺服器。**不得使用 `@ActiveProfiles("ecpay-stage")`**（§10.4）。

### 10.3 純單元測試

- `EinvoiceCodecTest`：round-trip（含中文、空白、`~`、`*`、`+`、`/`、`=`）；`phpUrlEncode(" ~*")` 等於 `"+%7E%2A"`；錯誤 key 解密丟例外且訊息不含 key
- `InvoiceRequestValidationTest`（或併入既有 orders 單元測試）：§6.2 表格每一列

### 10.4 守門測試（擴充 `EcpayNoEgressGuardTest`，**只准新增方法與常數，不改既有方法**）

1. 新增方法：`src/test` 下含 `einvoice.ecpay.com.tw`（正式網域）字串的檔案，只能在新的允許清單裡（`EinvoiceStageGuardTest.java`、`FakeEinvoiceServer.java`、`EcpayNoEgressGuardTest.java`）
2. 新增方法：`src/main` 下**任何檔案**（含 yml）都不得含 `einvoice.ecpay.com.tw` 或 `payment.ecpay.com.tw`（後者目前若有既存的合法出現處，以實際掃描結果為準，把那幾個檔案列為例外並在回報說明；**不得**為了讓測試過而刪除 G01b 的 stage 防線字串）
3. 新增方法：使用 `ecpay-stage` 字串的測試檔，除了既有的 `ecpay.query-url` 之外，也必須含 `ecpay.invoice.base-url`
4. 每一條規則都要附一個「嵌入式不安全樣本會被抓到」的反向測試（比照既有 `ruleDetectsEmbeddedUnsafeSample`）

新增 `EinvoiceStageGuardTest.java`：§5.3 每一條不成立時 context 啟動失敗、訊息正確且**不含設定值**（`@ParameterizedTest`）；`base-url` 設成正式網址 → 啟動失敗。

## 11. stage 實機驗證步驟（追加到 `docs/ECPAY.md`，列入 GAP-ANALYSIS「待 PO 驗收」，不擋合併）

前提：`./scripts/build.sh` 後 `./start-demo.sh`（`ecpay-stage` profile 已含發票設定）。**電子發票不需要回呼**，本機就能完整驗證，不需要公開網址。

1. **雲端發票**：顧客建立現金訂單、店員收現。1–2 分鐘後（`lag-seconds` + `interval-ms`）訂單明細出現發票號碼。記錄號碼格式
2. **手機條碼與捐贈**：各做一筆。手機條碼使用綠界測試文件提供的測試條碼（若無，記錄實際被接受／拒絕的結果）；捐贈碼用任一 3–7 位數字。記錄是否被拒、`RtnCode`
3. **欄位名稱核對**：在綠界 stage 廠商後台找到步驟 1 的發票，比對號碼、日期、隨機碼與系統顯示一致；另以 `GetIssue` 的實際回應（暫時把 log 等級調成 DEBUG 不可行時，就用 curl 依 §6.4 格式自行呼叫）核對 `IIS_*` 欄位名稱。**這一步的結果是 G03 S2 的輸入**
4. **線上付款也開立**：完成一筆 stage 信用卡付款，確認同樣開出發票
5. **失敗與重試**：以 `ECPAY_INVOICE_HASH_KEY=wrongwrongwrong1` 啟動，建立並付款一筆訂單；預期 10 輪後（或直接觀察）出現在對帳頁「發票開立失敗」。改回正確 key 重啟，按「重試」，下一輪開出發票
6. **字軌**：若步驟 1 被拒且訊息與字軌有關，記錄 `RtnCode` 與訊息 —— 這代表共用測試特店也需要自行設定字軌，由下一輪規格處理
7. **email 是否必填**：用 curl 依 §6.4 送一筆 `MOBILE` 但 `CustomerEmail` 空白的 `Issue`，記錄是否被接受（決定 §14.2 是否可放寬）
8. **`Timestamp` 有效期**：用 curl 依 §6.4 送兩筆內容正確的 `Issue`（不同 `RelateNumber`），`RqHeader.Timestamp` 分別設為「現在 − 11 分鐘」與「現在 − 9 分鐘」。預期前者被拒（記錄 `TransCode`／`RtnCode` 與訊息）、後者成功。**這一步的結果是 §14.13 時間假設的實證**：若前者也成功，依 §14.13 的推翻代價開下一輪規格修補

驗收紀錄表沿用 `docs/ECPAY.md` 既有格式，新增 G02 一張。

## 12. 施工階段

三個階段都是加法：S1 只加欄位與無人呼叫的方法（`invoice-required` 預設 false，行為不變）；S2 的排程預設關閉；S3 才在 demo profile 打開。

### S1 — schema、訂單發票資訊、`Orders` 新方法

**動到：** `V16__einvoice.sql`、`Orders.java`、`OrderService.java`、所有 `new Orders.Create(...)` 呼叫點（只補 `null`）、`application.yml`（只加 `orders.invoice-required`）、新增 `OrderInvoiceRequestTest.java`。

**驗收子集：** 1–6。

### S2 — 編解碼、用戶端、開立排程、假伺服器

**動到：** 新增 `EinvoiceCodec.java`、`EcpayInvoiceClient.java`、`InvoiceService.java`（只有排程與 `issueOne`，**還沒有** `Invoices` 介面）、`application.yml`（`ecpay.invoice.*`）、新增 `FakeEinvoiceServer.java`、`EinvoiceCodecTest.java`、`EinvoiceEndToEndTest.java`、`EinvoiceStageGuardTest.java`、`EcpayNoEgressGuardTest.java`（只新增方法）。

**驗收子集：** 7–22（含 21a–21f）。

**規模估計：** 本規格最重的一段（一個編解碼器、一個用戶端、一個排程、一支約 12 個情境的端到端測試）。**若一次做不完，切點在用戶端與排程之間**：先推 `EinvoiceCodec` + `EcpayInvoiceClient` + 假伺服器 + 驗收 7–11（此時沒有人呼叫用戶端，可以獨立合併），排程與驗收 12–22 下一次執行接續。**不要**把排程做一半就推。

### S3 — 端點、前端、demo profile、文件

**動到：** 新增 `Invoices.java`、`InvoiceController.java`、`InvoiceService.java`（實作 `Invoices`）、`EcpayService.config()`、`application-ecpay-stage.yml`、`MenuView.vue`、新增 `invoice.ts` 與 `invoice.spec.ts`、`OrdersView.vue`、`ReconciliationView.vue`、`shared/types.ts`、DOM 測試、新增 `InvoiceApiTest.java`、`EcpayStageProfileTest.java`（只新增斷言）、`docs/ECPAY.md`、`docs/reports/G02-ecpay-einvoice.md`。

**驗收子集：** 23–34。

## 13. 驗收條件

**S1**

- [ ] 1. `V16__einvoice.sql` 建立 §4 的欄位、兩張表、兩個 `CREATE INDEX`；六個 CHECK（含 `ck_orders_invoice_type`、`ck_invoices_claim`）與 `uq_invoices_invoice_no` 皆以 §4 的名稱具名，**SQL 裡沒有任何 inline 的 `CHECK(...)` 或 `UNIQUE`**；Flyway 在 H2 與 CI 用的資料庫上都能套用；`V1`–`V15` 一字未改
- [ ] 2. `orders.invoice-required=false`：不帶 `invoice` 建立訂單 → 201、四個欄位為 NULL、`Order.invoice == null`（**既有的建立訂單測試全部不修改即通過**，`new Create(...)` 補 `null` 不算修改測試邏輯）
- [ ] 3. `orders.invoice-required=true`：不帶 `invoice` → 400 `請選擇發票開立方式`；§6.2 表格每一列各一條（`@ParameterizedTest`），訊息逐字相符
- [ ] 4. 三種類型各一條成功案例，DB 只存相關欄位（`CLOUD` 的 `carrier`／`loveCode` 為 NULL 等）；`carrier` 小寫輸入存成大寫
- [ ] 5. 同一個 `Idempotency-Key`、同樣品項但不同 `invoice.email` → 409（指紋納入發票資訊）；完全相同 → 回同一筆訂單
- [ ] 6. `invoiceCandidates`：未付款、已取消不出現；`(paid_at, id)` 嚴格遞增；`paid_at == afterAt` 時以 `id > afterId` 續接（造兩筆同 `paid_at` 的訂單驗證不漏不重）；`until` 之後的不出現；`limit` 生效。`invoiceCandidate` 不存在回 empty

**S2**

- [ ] 7. `EinvoiceCodecTest`：§10.3 全部通過
- [ ] 8. `EcpayInvoiceClient.issue` 送出的請求，經假伺服器解密後：外層與 Data 內都有 `MerchantID`、`RqHeader.Revision == "3.0.0"`、`RelateNumber == 訂單編號`、`SalesAmount == total`、`Items` 一筆且三個金額等於 `total`；三種類型的 `CarrierType`／`CarrierNum`／`Donation`／`LoveCode` 各與 §6.4 表格一致
- [ ] 9. 假伺服器 `TIMEOUT`、`HTTP_500`、`TRANS_CODE_ERROR`、`BAD_CIPHER`、`REDIRECT_TO_PRODUCTION`、連線失敗 → `Unknown`；`REJECTED` → `Rejected(4000001, …)`
- [ ] 10. 302 重新導向**沒有被跟隨**（假伺服器斷言沒有第二個請求，且測試不連外）
- [ ] 11. §5.3 每一條不成立時啟動失敗（含 `claim-lease-ms` 低於下限與高於上限），訊息不含任何設定值（`EinvoiceStageGuardTest`）；`enabled=false` 時任意值都能啟動
- [ ] 12. 第一次執行排程：只建立 `PAID` 游標（`at_ms == T0 - lag`、`order_id == ''`），不開立任何發票。之後以 SQL 直接把四筆已付款訂單的 `paid_at` 設成 `C - 1`、`C`、`T0 - 1000`、`T0 + 1000`（`C` 取自游標的 `at_ms`，不靠系統時鐘），推進時間跑到入列完成：**只有 `C - 1` 那筆永遠不入列**，其餘三筆各入列一次（§6.5 步驟 1 的邊界表）
- [ ] 13. 正常路徑：付款（現金與線上各一）→ 經過 `lag` 後執行一輪 → `invoices` 為 `ISSUED`、號碼／日期／隨機碼正確、`uncertain=false`
- [ ] 14. `lag` 之內付款的訂單本輪不入列，下一輪才入列
- [ ] 15. 入列冪等：同一批訂單被兩輪掃到（手動把游標往回調）→ `invoices` 仍各一列
- [ ] 16. `SUCCEED_THEN_TIMEOUT`：第一輪 `PENDING`／`uncertain=true`；第二輪先 `GetIssue` 找到 → `ISSUED`，**假伺服器斷言 `Issue` 只被呼叫一次**
- [ ] 17. `DUPLICATE`：同一輪內轉查詢 → `ISSUED`，不再呼叫 `Issue`
- [ ] 18. `GetIssue` 回 `AMOUNT_MISMATCH` → `FAILED`、訊息正確、**不呼叫 `Issue`**
- [ ] 19. `REJECTED` → `FAILED`、`last_error` 含 `4000001`；`attempts` 達 `max-attempts`（測試設 3）→ `FAILED`、`uncertain=true`
- [ ] 20. 訂單沒有發票資訊（`invoice-required` 關閉時建立、之後才付款）→ `FAILED`、`訂單缺少發票資訊`，不呼叫綠界
- [ ] 21. 持有者寫回：`REJECTED` 情境下，在 `Issue` 掛勾內以 SQL 把該列的 `claim_token` 改成另一個值（模擬被接手）→ 回來後寫回更新 0 列、該列維持 `PENDING`／`uncertain=true`、`claim_token` 仍是測試設的值（**`Rejected` 沒有把別人的狀態清成 `uncertain=false`**）
- [ ] 21a. 認領：假伺服器收到 `Issue` 的當下（掛勾內讀 DB），該列 `uncertain=true`、`claim_token` 有值、`claimed_until == 認領時的 now + claim-lease-ms`；入列後直接改 `FAILED` 再呼叫 `issueOne` → 不呼叫綠界。持有者寫回之後 `claim_token`／`claimed_until` 皆為 NULL；`Rejected` 之後 `uncertain=false`；`訂單缺少發票資訊` 之後 `uncertain=false`
- [ ] 21b. **兩 worker 排他**（`CountDownLatch`）：兩條執行緒先各自讀到同一列（`PENDING`／`uncertain=false`／無持有者），在 latch 放行後同時執行第 2 步的認領 SQL（測試直接呼叫 `InvoiceService` 的認領方法；該方法為 `public`，見 §10.1 的可見性說明）→ **恰好一個**更新 1 列。重複 20 次皆成立
- [ ] 21c. **在途期間另一個 worker 跑 `issueOne`**：worker A 的 `Issue` 掛勾內等 latch；此時 worker B 對同一筆呼叫 `issueOne` → B **沒有送出任何請求**（假伺服器的請求數不變）、該列 `claim_token` 仍是 A 的；放行 A（`OK`）→ `ISSUED`、`Issue` 總共只被呼叫一次
- [ ] 21d. **接手當掉的持有者**：以 SQL 把一列設為 `PENDING`／`uncertain=true`／`claim_token='dead'`／`claimed_until=now+60000` → `issueOne` 不送出請求；改 `claimed_until=now-1` → `issueOne` 接手、**先 `GetIssue`**（假伺服器斷言呼叫順序），`SUCCEED_THEN_TIMEOUT` 先前記住的發票 → `ISSUED`，`Issue` 沒有再被呼叫
- [ ] 21e. **送出期限**：`uncertain=true` 的列，在 `GetIssue` 掛勾內以 SQL 把 `claimed_until` 改成 `now + 1000` → 查詢回 `Rejected` 後**不送出 `Issue`**，該列 `attempts+1`、`uncertain=true`、持有者已清除
- [ ] 21f. **遲到的開立回應**：`OK` 情境，在 `Issue` 掛勾內以 SQL 把該列改成 `FAILED`／`uncertain=false`／無持有者（模擬時間假設失效）→ 回應回來後該列被補償為 `PENDING`／`uncertain=true`、`last_error='收到遲到的開立回應，待確認'`；下一輪先 `GetIssue` → `ISSUED`，`Issue` 總共只被呼叫一次。同一情境但改成 `PENDING` 且 `claim_token='other'` → 只把 `uncertain` 設 `true`、`claim_token` 仍為 `'other'`
- [ ] 22. **log 衛生**：`OutputCaptureExtension` 擷取的完整輸出不含測試 key、iv、任何測試 email、手機條碼、捐贈碼，也沒有 `\tat ` 開頭的行（`@AfterEach` 每個情境斷言一次，比照 G04 驗收 20）；`EcpayNoEgressGuardTest` 新增的三個方法與反向樣本全綠

**S3**

- [ ] 23. `GET /api/payments/invoices/order/{id}`：顧客看自己的 → 200；看別人的 → 依 `orders.get` 的既有行為（403 或 404，以既有測試為準，**不得另外規定**）；他店店長 → 403；總部 → 200；無發票列 → `status=NONE`；未登入 401
- [ ] 24. `GET /api/payments/invoices/failed`：總部看到兩店；店長只看本店；店長帶他店 `branchId` → 403；收銀與顧客 → 403；超過 200 筆 `truncated=true`
- [ ] 25. `POST …/retry`：店長重試本店 `FAILED` → 200、`PENDING`、`attempts=0`、`uncertain=true`；他店店長 → 403 且資料不變；收銀 → 403；不帶 CSRF → 403；`ISSUED` → 409；不存在 → 404
- [ ] 26. 重試後下一輪排程：先 `GetIssue`、再決定是否 `Issue`（假伺服器斷言呼叫順序）
- [ ] 27. 所有端點的回應本文不含 key、iv、merchant id、base-url；`GET /api/payments/config` 多 `invoice.enabled`，既有欄位不變
- [ ] 28. `invoice.spec.ts`：§6.2 每條規則一正一反，訊息與後端逐字相同
- [ ] 29. `MenuView.vue` DOM 測試：`invoice.enabled=false` 時沒有發票欄位且 payload 無 `invoice`；`true` 時三種類型切換顯示正確欄位、驗證失敗不送出、成功時 payload 的 `invoice` 只含相關欄位
- [ ] 30. `OrdersView.vue` DOM 測試：`ISSUED` 顯示號碼與隨機碼；`PENDING` 顯示開立中；`FAILED` 對顧客顯示開立中、對店員顯示失敗原因
- [ ] 31. `ReconciliationView.vue` DOM 測試：有失敗列時顯示區塊與重試按鈕，按下呼叫正確 URL／method；無失敗列不顯示
- [ ] 32. `application-ecpay-stage.yml` 含 §5.2 的值；`EcpayStageProfileTest` 只**新增**斷言（發票特店是 `2000132`、與金流特店不同、`orders.invoice-required=true`）
- [ ] 33. `docs/ECPAY.md` 新增「電子發票」一節（規則、設定、失敗怎麼處理、測試特店的來源）與 §11 的步驟與驗收紀錄表
- [ ] 34. 實作回報 `docs/reports/G02-ecpay-einvoice.md`：各階段狀態、每條驗收對應的測試方法名、§5.2 憑證是否已對照官方頁面、`EinvoiceCodec` 的可見性選擇（§10.1）、實作中發現的規格問題。**缺檔即退回**

## 14. 設計決策

### 14.1 電子發票放在 `coffee-payments`，不另開 `coffee-invoicing` 模組

**決定：** 用戶端、排程、端點都放 `coffee-payments/internal`，介面放 `coffee-payments/api/Invoices.java`。
**理由：** 它與金流共用同一家加值中心、同一套 stage 防線思路、同一個假伺服器目錄與守門測試，而且都只需要 `orders.api`。開新模組要多一份 pom、一組 ArchUnit 規則、一次 `AGENTS.md` 模組表的修訂，換來的只是名稱上的整齊。
**推翻的代價：** 搬成獨立模組是純搬家（package 改名 + pom），約半個施工階段；`invoices`、`invoice_cursors` 表不用動。

### 14.2 email 三種開立方式都必填

**決定：** 雲端、手機條碼、捐贈都要 email。
**理由：** 官方 SDK 指南寫 `Issue` 的 `CustomerEmail`／`CustomerPhone` 擇一必填，沒有寫有載具或捐贈時可免。收 email 比收手機號碼少一層個資敏感度。寧可多收一欄，也不要讓一整類訂單在 stage 開不出來。
**推翻的代價：** §11 步驟 7 若顯示可免填，把 §6.2 的 email 規則改為「僅 `CLOUD` 必填」，前後端各改一行，驗收 3、28 各調一列。

### 14.3 不做紙本發票、統編、其他載具

**決定：** 只做雲端（綠界載具）、手機條碼、捐贈。
**理由：** 紙本（`Print=1`）需要收據印表機整合與列印格式，這個系統沒有任何硬體層；統編（B2B）會牽動稅額拆分（`TaxType`、未稅價）與買受人名稱，是另一套規則。這三種涵蓋咖啡廳線上與自取的主要情境，而且全部不需要硬體。
**推翻的代價：** 統編約一個施工階段（新增 `CustomerIdentifier`／`CustomerName` 欄位與未稅金額計算）；紙本要先決定印表機方案，屬產品決策。

### 14.4 發票只開一行「餐飲費用」

**決定：** `Items` 固定一筆，金額 = 訂單 `total`。
**理由：** 訂單有整單折扣（G07）與品項折扣（G20），逐品項開立就得決定折扣怎麼攤（負數品項列？按比例分攤且處理餘數？），而綠界是否接受負數品項未經實機確認。單行「餐飲費用」是餐飲業常見的開立方式，金額必然與 `SalesAmount` 相等，沒有捨入問題，也讓 G03 的折讓明細簡單到只有一行。
**推翻的代價：** 改成逐品項需要訂單品項快照與折扣攤提規則，約一個施工階段；`invoices` 表不用動。

### 14.5 手機條碼、捐贈碼只做格式驗證，不即時查綠界

**決定：** 不呼叫 `CheckBarcode`／`CheckLoveCode`。
**理由：** 下單路徑上多一個外部 HTTP 呼叫，會讓綠界的延遲與失敗直接變成「不能點餐」；而且那會是一個顧客可以觸發的連外呼叫（濫用面，同 G01b §15.5）。格式錯誤在下單時就擋；格式對但不存在的條碼，會在開立時被綠界拒絕，進入「開立失敗」清單由店長處理。
**推翻的代價：** 在 `create` 前加一個帶快取與逾時降級的檢查，約半個施工階段，並要接 G20j 限流。

### 14.6 游標從啟用當下開始，不補開歷史訂單

**決定：** 第一次執行只建立游標 `(T0 - lag, '')`。付款時間 `>= T0 - lag` 的訂單開立，`< T0 - lag` 的永遠不開（§6.5 步驟 1 的邊界表）。
**理由：** 不補開遠期歷史：啟用前的訂單大多沒有發票資訊（`invoice` 為 NULL），補開只會全部進「缺少發票資訊」的失敗清單，對 stage 測試也沒有意義。截止點取 `T0 - lag` 而不是 `T0`，是因為它就是「第一輪若早一輪跑會用的 `until`」——游標與 `until` 用同一個基準，初始化不需要特例，條件也只剩一個 `paid_at >= C`；lag 窗內那幾分鐘的訂單多開一張也是正確的發票（它們都已付款）。v1.0 寫「游標建立前付款的永遠不處理」與這個算法矛盾，v1.1 以本條為準。
**推翻的代價：** 要改成「`T0` 之後付款才開」，把初始化改成 `(T0, '')`、邊界表與驗收 12 各改一列。要補開歷史，手動把 `PAID` 列往回調即可（入列是冪等的），不需要改程式。

### 14.7 `invoice-required` 屬於 `orders`，啟用發票時強制為 true

**決定：** 由 `orders.invoice-required` 決定建立訂單時是否必填；`ecpay.invoice.enabled=true` 而它是 false 時拒絕啟動。
**理由：** 「建立訂單要驗證什麼」是 `orders` 的事，`orders` 不能讀 `payments` 的設定（依賴方向）。但兩者必須一致，不一致的唯一結果是一直累積失敗列，所以在啟動時擋。預設 false 讓 S1 合併後既有行為與既有測試完全不變。
**推翻的代價：** 若日後允許「可不開發票」的訂單（例如員工餐），在 `Create` 加一個明確的豁免旗標並讓排程標 `SKIPPED`。

### 14.8 權限重用 `PAYMENT_RECONCILE`

**決定：** 失敗清單與重試用 `PAYMENT_RECONCILE`。
**理由：** 會處理發票失敗的人，就是今天在對帳頁處理付款異常的人（店長、總部），畫面也在同一頁。新增權限要改 `Identity.PERMISSIONS`、加 migration 授權、補角色驗證測試，換不到任何實際的權限區隔。
**推翻的代價：** 新增 `INVOICE_MANAGE` 並授權給同一組角色，一支 migration 加三處引用。

### 14.9 `Order.invoice` 的 email 不遮罩

**決定：** 與訂單其他欄位同一個可見範圍。
**理由：** 看得到這筆訂單的人（本人、本店店員、總部）本來就要處理這筆發票；email 打錯時店員需要看得到才能協助。
**推翻的代價：** 在 `snapshot()` 對非本人遮罩，一個函式。

### 14.10 不重開之前一定先查；以 `uncertain` 旗標記住「上次結果不明」

**決定：** 見 §6.5。
**理由：** 「重複開立」與「漏開」都是稅務問題，但重複開立比較難收拾（要作廢一張）。`uncertain` 讓「先查」只發生在需要的時候，正常路徑每筆只有一次呼叫。重試端點一律設 `uncertain=true`，因為店長按重試時，系統並不知道上一次失敗前有沒有其實成功。
**推翻的代價：** 無。這是安全側的預設。

### 14.11 排程用條件更新取代行鎖

**決定：** `update … where order_id=? and status='PENDING'`。
**理由：** 綠界呼叫最長 10 秒，抱著列鎖等它會卡住重試端點；條件更新讓「晚到的寫回」自然失效（驗收 21）。多實例同時開立同一筆，最壞情況是兩個都呼叫 `Issue`，第二個會收到 `10000009`、轉查詢後得到同一張發票 —— 不會開出兩張。
**推翻的代價：** 改成 `select … for update skip locked` 認領模式，需要分「認領」與「寫回」兩個交易。

**v1.2 補充：** 「多實例最壞情況是兩個都呼叫 `Issue`」在 v1.2 已不成立 —— §14.13 的排他認領讓同一時間只有一個持有者會呼叫。本條的「不持鎖等 HTTP」仍然成立：認領是一個短的條件更新，HTTP 期間不持任何鎖。

### 14.12 呼叫 `Issue` 之前先以條件更新「認領」並設 `uncertain=true`

**決定：** §6.5 第 3 步。每次呼叫 `Issue` 前多一次 `update`，更新 0 列就不呼叫。
**理由：** v1.0 只在 `Unknown` 之後才設 `uncertain=true`，所以「`PENDING` 且 `uncertain=false`」既可能是「確定沒開」，也可能是「另一個實例的 `Issue` 正在途中」。G03 的退款要把沒開過的發票標成 `SKIPPED`，必須能區分這兩種；區分不了，就會出現「綠界已開立、本地卻是 `SKIPPED`、永遠沒人作廢」（Codex 在 PR #92 review 5522929018 指出的缺口）。先認領讓 `uncertain=false` 成為可依賴的不變式，順帶解決行程在呼叫中途當掉後下一輪盲目重開的問題。代價是正常路徑每張發票多一次單列 `update`。
**推翻的代價：** 拿掉認領，G03 的退款掃描就不能動任何 `PENDING` 列，只能等它開完再作廢 —— 等於每筆開立前退款的訂單都要先開一張再作廢一張。

**v1.2 更正：** v1.1 的認領條件只有 `status='PENDING'`，不是排他的：兩個實例都會認領成功、都呼叫 `Issue`，先回來的 `Rejected` 會把列寫成 `FAILED`／`uncertain=false`，而另一個請求仍在途（Codex 在 PR #92 review 5526548535 指出的交錯）。v1.2 改為 §14.13 的租約認領。

### 14.13 排他認領用「租約 + token」，並以綠界的 `Timestamp` 有效期界定舊請求

**決定：** §6.5 第 2–6 步。認領寫入 `claim_token` 與 `claimed_until = now + claim-lease-ms`（預設 15 分鐘），只有沒有持有者或租約已過期才能認領；所有寫回以 `claim_token = 自己的 token` 為條件；持有者只在 `claimed_until - 11 分鐘` 之前送出請求；失去認領後收到的「開立」回應走補償。

**理由：**
- **排他**：`claimed_until < now` 才能接手，所以同一時間只有一個持有者；以 token 寫回，被接手的舊持有者寫不回任何東西，不會用 `Rejected` 清掉別人的 `uncertain`（v1.1 的缺口）
- **可接手**：行程當掉時不需要人工解鎖，租約到期自動由下一輪接手，接手後先查詢
- **舊請求的時間界線**：只有「租約」還不夠 —— 舊持有者的請求可能在租約到期前一刻送出、在綠界晚一點才成立，而新持有者剛好拿到確定的 `Rejected`，就又回到「本地以為沒開、綠界其實開了」。綠界的電子發票 API 以 `RqHeader.Timestamp` 驗證請求時間，**超過 10 分鐘的請求不受理**（官方 SDK 指南與 B2C 電子發票文件的 `Timestamp` 說明；以 §11 步驟 8 的實機結果為準）。持有者只在 `claimed_until - 11 分鐘` 之前送出，所以租約一到期，所有舊請求在綠界都已失效。這把「舊請求最晚何時可能成立」從無法驗證的網路延遲，換成一個綠界自己會執行、而且 stage 可以實測的規則
- **第二道防線**：萬一時間假設失效（例如實機發現綠界不檢查 `Timestamp`），只要舊持有者還活著收到「開立」，§6.5 第 6 步的補償會把列拉回 `PENDING`／`uncertain=true`，下一輪查到就寫 `ISSUED`，G03 再補作廢。剩下沒蓋到的只有「舊持有者在請求送出後當掉、而請求在 10 分鐘外才成立」，這正是 `Timestamp` 規則排除的情況
- **代價**：當掉後的接手最慢 15 分鐘；發票的法定開立期限以天計，這個延遲無關緊要。正常路徑多寫兩個欄位，不多一次 SQL（認領本來就有）

**推翻的代價：**
- 若 §11 步驟 8 實測發現綠界**不檢查** `Timestamp`：把 `STALE_REQUEST_WINDOW_MS` 的依據改為「`timeout-ms` × 3 + 安全邊際」，並在 G03 把「退款時 `uncertain=false` 直接 `SKIPPED`」改為「先建作廢列、等租約到期加一個安靜期後以 `GetIssue` 確認查無才 `SKIPPED`」（G03 §14.9 的推翻方案），約半個施工階段
- 改用資料庫的 `select … for update skip locked` 持鎖跨 HTTP：要放棄 §14.11「HTTP 期間不持鎖」，重試端點會被卡住最長 `3 × timeout-ms`，而且行程當掉時鎖會隨連線釋放，舊請求的時間界線問題仍然存在 —— 不比租約安全

## 15. 與其他工作的關係

- **G03**：S2 依賴本規格 S2 的 `EcpayInvoiceClient`（新增 `invalid`、`allowance`）、`FakeEinvoiceServer`（新增路徑）、`invoices.status='SKIPPED'`、`invoice_cursors` 的第二列。**G03 必須在本規格合併後才開工**（G03 的 `V17` 依賴 `V16` 先存在）
- **G01b／G04**：不改它們的任何行為；`EcpayNoEgressGuardTest` 只新增方法
- **G07／G20**：發票金額取訂單 `total`，已含所有折扣，不需要知道折扣細節

## 修訂紀錄

| 版本 | 日期 | 變更 |
| --- | --- | --- |
| v1.0 | 2026-10-14 | 初版。PO 2026-10-14 解除 G02 延後，沿用 2026-10-11 stage 硬規則 |
| v1.2 | 2026-10-15 | 回應 Codex review 5526548535：v1.1 的認領不是排他的（兩個實例都會認領成功，先回來的 `Rejected` 會清掉另一個在途請求的 `uncertain`）。§4 新增 `claim_token`／`claimed_until` 與 `ck_invoices_claim`；§5 新增 `claim-lease-ms` 與啟動檢查；§6.5 `issueOne` 改寫為租約認領、送出期限、以 token 寫回、失去認領後的開立回應補償，查詢與開立之間不再寫回中間狀態；§6.6 重試清除持有者；§10.1 假伺服器新增掛勾與 `Timestamp` 記錄、`tryClaim` 可見性；§11 新增步驟 8；§14.11、§14.12 補充，新增 §14.13；驗收 1、11、21、21a 修訂，新增 21b–21f |
| v1.1 | 2026-10-15 | 回應 Codex review 5522929018：(1) §6.5 新增「認領」步驟與 `uncertain` 不變式、§14.12（G03 退款不漏作廢的前提）；(2) 游標截止點的精確邊界表，§2.2、§14.6、驗收 12 統一為 `paid_at >= T0 - lag`；(3) §4 的 CHECK／UNIQUE 全部具名、驗收 1 的索引數更正為兩個 `CREATE INDEX`；新增驗收 21a |
