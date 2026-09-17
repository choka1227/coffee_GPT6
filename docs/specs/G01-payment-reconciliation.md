# G01 — 金流對帳與付款狀態修復

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G01 |
| 優先順序 | P0（阻擋正式上線） |
| 規格版本 | v1 |
| 撰寫 | Claude（PM / SA），2026-09-17 |
| 實作 | Codex（PG / SD） |
| 基準 commit | `8dd81a0` |

---

## 1. 背景與目標

### 問題

`EcpayService.callback()` 是目前**唯一**把 `paymentMethod='ECPAY'` 的訂單從 `PENDING_PAYMENT` 推進到 `PAID` 的路徑。整個後端主程式碼沒有任何對外 HTTP client，也沒有 `@Scheduled` / `@EnableScheduling`——換句話說，系統從來不會主動去問綠界「這筆到底付了沒」。

失效情境（皆為正式營運會發生的事）：

| 情境 | 結果 |
| --- | --- |
| 回呼送達時應用正在重啟 / 部署 | 綠界重送次數用盡後放棄，訂單永遠 `PENDING_PAYMENT` |
| 反向代理或 TLS 短暫故障 | 同上 |
| 回呼處理中拋出 `Problem`（例如暫時性 DB 問題） | 回傳非 `1|OK`，綠界重試耗盡後放棄 |
| 顧客付款成功但在導回前關閉瀏覽器 | 若回呼也遺失，無人察覺 |

共同後果：**顧客的信用卡已經被扣款，系統卻認為這筆沒付錢。** 訂單不會出現在製作流程裡、不列入營業額、不進報表。目前沒有任何機制偵測或修復，`docs/ECPAY.md` 也明白承認這點。

### 目標

1. 系統能**主動**向綠界查詢單筆交易的真實狀態
2. 排程定期掃描疑似卡住的訂單，自動修復真的已付款的那些
3. 營運人員看得到「付款狀態存疑」的訂單，並能手動觸發查核
4. 每一次查核都留下可追溯的紀錄
5. 修復時使用**綠界回報的實際付款時間**，而不是修復當下的時間，否則報表的營業日會歸錯

### 不是目標

本規格**不處理**退款（G03）、電子發票（G02）、線上付款訂單的逾時作廢（G04）。對帳只會把「綠界說已付款、金額也相符」的訂單改成 `PAID`，**絕不會**把任何訂單改成 `CANCELLED` 或往回退。

---

## 2. 範圍

### 在範圍

- 綠界 `QueryTradeInfo V5` 查單 API 的串接與回應解析
- 查單用 `CheckMacValue` 的產生（與現有 `CheckMac` 同演算法，但參數不同）
- 排程對帳工作
- 對帳紀錄資料表與 Flyway migration
- 三個營運端點（列出待查核訂單、手動觸發查核、查看查核歷程）
- 新權限 `PAYMENT_RECONCILE`
- `Orders` api 擴充一個方法多載，讓入帳能指定實際付款時間
- 前端 `modules/payments` 的營運畫面

### 不在範圍

- 退款 / 折讓（G03）
- 電子發票（G02）
- 未付款訂單的自動作廢（G04）——**特別注意：本規格刻意不做這件事**，因為作廢與對帳有競態風險，必須等 G04 一起設計
- 通知管道（email / Slack 告警）——見「待 PO 決定」第 3 項
- 綠界以外的金流

---

## 3. 涉及模組與邊界

| 模組 | 變更 |
| --- | --- |
| `coffee-payments` | 主要實作位置：查單 client、對帳服務、排程、Controller |
| `coffee-orders` | `api/Orders.java` 新增一個方法；`internal/OrderService` 實作 |
| `coffee-identity` | `api/Identity.java` 的 `PERMISSIONS` 清單新增 `PAYMENT_RECONCILE` |
| `coffee-app` | Flyway migration；`@EnableScheduling`；Security 設定（新端點需登入 + CSRF） |
| `frontend/src/modules/payments` | 營運畫面 |

### 邊界規則（`ModuleBoundariesTest` 會驗）

- `coffee-payments` 依賴 `coffee-orders` 的 **`api`**，這是既有且允許的依賴。**不得**引用 `com.coffee.orders.internal` 的任何 class
- 對帳服務**不得**直接 `update orders` 或讀 `orders` 表。訂單的寫入責任屬於 `coffee-orders`，必須透過 `Orders` 介面
- `payment_events` 與新的 `payment_reconciliations` 表屬於 `coffee-payments`，由它自己讀寫
- 排程元件放在 `coffee-payments` 的 `internal`；`@EnableScheduling` 放在 `coffee-app`（組裝層決定要不要啟用排程）

### 新依賴

**不得引入新的第三方依賴。** 使用 JDK 內建的 `java.net.http.HttpClient`（專案已是 Java 17）。這符合 `AGENTS.md` 禁止事項第 3 條。

---

## 4. DB schema 與 migration

**新增檔案：`backend/coffee-app/src/main/resources/db/migration/V2__payment_reconciliation.sql`**

**不得修改 `V1__coffee_schema.sql`**（`AGENTS.md` 禁止事項第 6 條）。

```sql
CREATE TABLE payment_reconciliations(
  id VARCHAR(36) PRIMARY KEY,
  order_id VARCHAR(20) NOT NULL REFERENCES orders(id),
  trigger_source VARCHAR(12) NOT NULL CHECK(trigger_source IN ('SCHEDULED','MANUAL')),
  actor_id VARCHAR(36),
  outcome VARCHAR(16) NOT NULL
    CHECK(outcome IN ('CONFIRMED','STILL_UNPAID','AMOUNT_MISMATCH','SIMULATED','QUERY_FAILED')),
  trade_status VARCHAR(20) NOT NULL,
  trade_amount INTEGER,
  provider_trade_no VARCHAR(64) NOT NULL,
  detail VARCHAR(200) NOT NULL,
  queried_at BIGINT NOT NULL
);
CREATE INDEX idx_reconciliations_order ON payment_reconciliations(order_id, queried_at);
CREATE INDEX idx_reconciliations_queried ON payment_reconciliations(queried_at);

-- 供排程掃描「卡住的 ECPAY 訂單」使用
CREATE INDEX idx_orders_pending_online
  ON orders(payment_method, status, created_at);
```

欄位說明：

- `trigger_source` —— `SCHEDULED`（排程）或 `MANUAL`（營運人員手動）
- `actor_id` —— 手動觸發時記錄操作者；排程時為 `NULL`
- `outcome` —— 本次查核的結論，語意見第 6 節決策表
- `trade_status` —— 綠界回傳的 `TradeStatus` 原值；查詢失敗時填 `"unknown"`
- `trade_amount` —— 綠界回傳的 `TradeAmt`；查詢失敗或無此欄位時為 `NULL`
- `provider_trade_no` —— 綠界的 `TradeNo`；無值時填空字串（比照 `payment_events` 的既有寫法）
- `detail` —— 給營運人員看的**繁體中文**說明，例如「綠界回報已付款，訂單已更新為已付款」

**不得**把綠界回應原文整份存進 `detail` 或任何欄位——比照 `EcpayService.callback()` 現有註解「Never store credentials or complete payment payloads」。

---

## 5. 設定項

沿用既有 `ecpay.*`，新增：

| property | 預設 | 說明 |
| --- | --- | --- |
| `ecpay.reconcile.enabled` | `false` | 總開關。`ecpay.enabled=false` 時本功能一律停用 |
| `ecpay.reconcile.interval-ms` | `300000`（5 分鐘） | 排程間隔 |
| `ecpay.reconcile.min-age-minutes` | `15` | **靜默期**：訂單建立未滿此時間不查。顧客可能還在綠界付款頁上，太早查只會拿到未付款 |
| `ecpay.reconcile.max-age-days` | `7` | 超過此天數不再查，避免無限累積 |
| `ecpay.reconcile.batch-size` | `50` | 單次排程最多處理幾筆 |
| `ecpay.query-timeout-ms` | `10000` | 查單的 connect + read timeout |

啟用時的啟動期檢查（比照 `EcpayService` 建構子既有作法，用 `Problem.check`）：`ecpay.reconcile.enabled=true` 時 `ecpay.enabled` 必須也是 `true`，否則啟動失敗並回報「啟用對帳前必須先啟用綠界金流」。

---

## 6. 綠界查單串接

### 端點

| 環境 | URL |
| --- | --- |
| stage | `https://payment-stage.ecpay.com.tw/Cashier/QueryTradeInfo/V5` |
| production | `https://payment.ecpay.com.tw/Cashier/QueryTradeInfo/V5` |

官方文件：https://developers.ecpay.com.tw/2878/

### 請求

`POST`，`application/x-www-form-urlencoded`，欄位：

| 欄位 | 值 |
| --- | --- |
| `MerchantID` | `ecpay.merchant-id` |
| `MerchantTradeNo` | 訂單 id（建立付款時就是用 `o.id()` 當 `MerchantTradeNo`） |
| `TimeStamp` | 目前 UTC epoch **秒** |
| `PlatformID` | 空字串 |
| `CheckMacValue` | 以上四個欄位用 `CheckMac.sign()` 計算 |

`CheckMac.sign()` 的演算法與建立訂單完全相同（不分大小寫 A–Z 排序、前後夾 HashKey / HashIV、整串 URL encode 轉小寫、對齊 .NET 字元規則、SHA-256 後轉大寫），**直接重用 `com.coffee.payments.api.CheckMac`，不要複製一份**。

### 回應

綠界回傳的是 URL-encoded 的 `key=value&key=value` 純文字（**不是 JSON**）。需要解析的欄位：

| 欄位 | 用途 |
| --- | --- |
| `TradeStatus` | `0`=未付款、`1`=已付款、`10200095`=交易失敗。其他值一律視為未付款 |
| `TradeAmt` | 交易金額，用來比對 |
| `TradeNo` | 綠界交易編號 |
| `PaymentDate` | 付款時間，格式 `yyyy/MM/dd HH:mm:ss`，**時區為 Asia/Taipei** |
| `SimulatePaid` | `1` 代表模擬付款 |
| `CheckMacValue` | 回應簽章 |

**必須驗證回應的 `CheckMacValue`**（用 `CheckMac.valid()`），與回呼一樣。驗不過就是 `QUERY_FAILED`，不得據此入帳。

`MerchantTradeNo` 在綠界查不到時（訂單從未送出付款）回應的 `TradeStatus` 不會是 `1`，照未付款處理即可，不是錯誤。

### 決策表（實作請逐列對照）

| 條件 | `outcome` | 動作 |
| --- | --- | --- |
| HTTP 失敗 / timeout / 回應 `CheckMacValue` 驗證失敗 | `QUERY_FAILED` | 只記錄，不動訂單 |
| `TradeStatus != "1"` | `STILL_UNPAID` | 只記錄，不動訂單 |
| `TradeStatus == "1"` 且 `SimulatePaid == "1"` | `SIMULATED` | 只記錄，**不入帳**（比照回呼的既有規則） |
| `TradeStatus == "1"` 且 `TradeAmt != orders.total` | `AMOUNT_MISMATCH` | **不入帳**，記錄，`detail` 寫明兩邊金額 |
| `TradeStatus == "1"`、非模擬、金額相符 | `CONFIRMED` | 呼叫 `Orders.confirmOnline(...)` 入帳 |

金額不符是嚴重訊號（可能是竄改或綠界端設定錯誤），**絕對不可以自動入帳**，也不可以自動作廢。只記錄並讓它出現在待查核清單裡，由人處理。

---

## 7. `Orders` api 變更

現有 `void confirmOnline(String id, int amount, String providerTradeNo)` 內部寫死 `paid_at = System.currentTimeMillis()`。對帳場景下這會把三天前的付款記成今天，**報表的營業日就錯了**（`ARCHITECTURE.md`：報表以 `paid_at` 歸屬 Asia/Taipei 日期）。

在 `com.coffee.orders.api.Orders` 新增：

```java
/** paidAt 為實際付款時間（UTC epoch millis）。對帳補登時使用綠界回報的 PaymentDate。 */
void confirmOnline(String id, int amount, String providerTradeNo, long paidAt);
```

- 保留原本三參數版本，內部委派為 `confirmOnline(id, amount, providerTradeNo, System.currentTimeMillis())`，讓 `EcpayService.callback()` 不必改動
- 四參數版本的實作必須維持既有的所有保護：`lock(id)` 取行鎖、方式與金額比對、已付款時比對 `provider_trade_no` 一致則視為重複通知直接 return、狀態必須是 `PENDING_PAYMENT`
- 綠界 `PaymentDate` 是 Asia/Taipei 的 `yyyy/MM/dd HH:mm:ss`，換算成 epoch millis 時必須用 `ZoneId.of("Asia/Taipei")`，不可用系統預設時區
- `PaymentDate` 缺漏或無法解析時，退回使用目前時間，並在 `detail` 註明「綠界未提供付款時間，以查核時間入帳」

**冪等性**：對帳與回呼可能同時發生。行鎖 + 「已付款且 `provider_trade_no` 相同就直接 return」的既有邏輯已經涵蓋這點，實作時不要繞過它。

---

## 8. API

三個端點都掛在 `coffee-payments` 的 Controller 下。**都需要登入、都需要 CSRF（寫入請求）**——唯一免 CSRF 的仍然只有 `POST /api/payments/ecpay/callback`。

### 8.1 `GET /api/payments/reconciliation/pending`

列出「付款狀態存疑」的訂單：`payment_method='ECPAY'`、`status='PENDING_PAYMENT'`、建立時間在 `min-age-minutes` 之前且在 `max-age-days` 之內。

回應 `200`：

```json
{
  "items": [
    {
      "orderId": "A2609170001",
      "branchId": "taipei",
      "branchName": "台北信義店",
      "total": 260,
      "createdAt": 1758067200000,
      "lastOutcome": "STILL_UNPAID",
      "lastQueriedAt": 1758070800000,
      "attempts": 3
    }
  ]
}
```

`lastOutcome` / `lastQueriedAt` 在從未查核過時為 `null`，`attempts` 為 `0`。依 `createdAt` 由舊到新排序。

### 8.2 `POST /api/payments/reconciliation/{orderId}`

手動對單筆訂單觸發查單。無 request body。

回應 `200`：

```json
{
  "orderId": "A2609170001",
  "outcome": "CONFIRMED",
  "detail": "綠界回報已付款 260 元，訂單已更新為已付款",
  "queriedAt": 1758070800000
}
```

### 8.3 `GET /api/payments/reconciliation/{orderId}`

該訂單的查核歷程，最近 50 筆，由新到舊：

```json
{
  "orderId": "A2609170001",
  "attempts": [
    {
      "outcome": "CONFIRMED",
      "triggerSource": "MANUAL",
      "tradeStatus": "1",
      "tradeAmount": 260,
      "detail": "綠界回報已付款 260 元，訂單已更新為已付款",
      "queriedAt": 1758070800000
    }
  ]
}
```

### 錯誤碼

| 狀態 | 情境 | 訊息（繁體中文，台灣用語） |
| --- | --- | --- |
| 400 | `orderId` 格式不正確 | 訂單編號格式不正確 |
| 401 | 未登入 | （既有全域處理） |
| 403 | 無 `PAYMENT_RECONCILE` 權限 | 沒有執行金流對帳的權限 |
| 403 | 跨越資料範圍（見第 9 節） | 沒有查看其他分店訂單的權限 |
| 404 | 訂單不存在 | 找不到這筆訂單 |
| 409 | 訂單不是 ECPAY 或已非未付款 | 這筆訂單不需要對帳 |
| 429 | 手動查核超過頻率上限 | 查核過於頻繁，請稍後再試 |
| 503 | `ecpay.reconcile.enabled=false` | 金流對帳功能尚未啟用 |

所有訊息**一律繁體中文、寫給營運人員看**（`AGENTS.md` 禁止事項第 8 條）。回應格式固定 `{"message": "..."}`。

手動查核的頻率限制：同一訂單 60 秒內只能手動查核一次，超過回 `429`。這是避免營運人員連點把綠界的查單額度打爆。

---

## 9. 權限與資料範圍

### 新權限

`com.coffee.identity.api.Identity.PERMISSIONS` 新增 `"PAYMENT_RECONCILE"`。依 `AGENTS.md`「新增權限要同步更新該清單與角色驗證規則」，角色驗證規則與預設 HQ 角色設定也要一併更新。

### 資料範圍（三個端點一致）

| Actor scope | 可見 / 可操作範圍 |
| --- | --- |
| `SELF`（顧客） | **一律 403**，連自己的訂單都不行。對帳是營運功能，顧客不該碰 |
| `BRANCH` | 只限 `branch_id` 等於自己 `branchId` 的訂單 |
| `GLOBAL` | 全部分店 |

實作用既有 `Actor`：`a.require("PAYMENT_RECONCILE")` 後再依 `a.branch(order.branchId())` / `a.global()` 決定範圍。

**`GET /pending` 的分店過濾必須在 SQL 的 WHERE 條件裡完成**，不可以先撈全部再於 Java 過濾——否則跨店訂單數量會從回應大小洩漏出去。

**排程執行時沒有 Actor**，走系統路徑，不做權限檢查（它本來就處理全部分店）。實作時要確保排程用的方法**不是**對外端點，也不接受任何外部輸入。

---

## 10. 金額規則

- 對帳**不重算任何金額**。訂單金額在建立時就已依有效菜單算好並快照，對帳只做「比對」
- 綠界回傳的 `TradeAmt` **不是**權威值，只是用來與 `orders.total` 比對的外部資料。兩者不符時以本地 `orders.total` 為準，並拒絕入帳
- 比對用整數相等，不可有任何容差
- `TradeAmt` 解析失敗（非數字、溢位）視同 `QUERY_FAILED`，不入帳
- 全程新台幣整數元，不得出現 `double` / `float` / `BigDecimal`

---

## 11. 驗收條件

實作完成後逐條勾選，PR 描述請附上這份清單的勾選結果。

**資料層**

- [ ] 新增 `V2__payment_reconciliation.sql`，未修改 `V1__coffee_schema.sql`
- [ ] `payment_reconciliations` 表與兩個索引建立成功
- [ ] `idx_orders_pending_online` 建立成功
- [ ] 空資料庫啟動後 Flyway 遷移到 V2 無錯誤

**查單**

- [ ] 查單的 `CheckMacValue` 重用 `com.coffee.payments.api.CheckMac`，未複製演算法
- [ ] 查單請求包含 `MerchantID`、`MerchantTradeNo`、`TimeStamp`、`PlatformID`、`CheckMacValue`
- [ ] 正確解析 URL-encoded 的 `key=value` 回應（非 JSON）
- [ ] 驗證回應的 `CheckMacValue`，驗不過記為 `QUERY_FAILED` 且不入帳
- [ ] connect 與 read timeout 皆套用 `ecpay.query-timeout-ms`
- [ ] stage / production 使用對應的查單 URL

**決策表**

- [ ] 第 6 節決策表五種 `outcome` 全部實作且行為相符
- [ ] `SimulatePaid=1` 不入帳
- [ ] 金額不符不入帳、不作廢，只記錄
- [ ] `CONFIRMED` 時以綠界 `PaymentDate` 換算 Asia/Taipei 後入帳，非查核當下時間
- [ ] `PaymentDate` 缺漏時退回目前時間並在 `detail` 註明

**排程**

- [ ] `@EnableScheduling` 位於 `coffee-app`，排程元件位於 `coffee-payments` 的 `internal`
- [ ] `ecpay.reconcile.enabled=false`（預設）時排程完全不執行
- [ ] `ecpay.enabled=false` 但 `ecpay.reconcile.enabled=true` 時啟動失敗並回報中文訊息
- [ ] 遵守 `min-age-minutes` 靜默期、`max-age-days` 上限、`batch-size` 單次上限
- [ ] 單筆訂單處理失敗不影響同批其他訂單（逐筆 try / catch）
- [ ] 排程**不會**把任何訂單改成 `CANCELLED`

**API**

- [ ] 三個端點皆實作，回應欄位與第 8 節一致
- [ ] 寫入端點需要 CSRF token
- [ ] 錯誤碼與中文訊息與第 8 節表格一致
- [ ] 手動查核同訂單 60 秒內第二次回 `429`
- [ ] `ecpay.reconcile.enabled=false` 時三個端點回 `503`

**權限**

- [ ] `PAYMENT_RECONCILE` 加入 `Identity.PERMISSIONS`，角色驗證規則同步更新
- [ ] `SELF` scope 一律 403
- [ ] `BRANCH` scope 的分店過濾寫在 SQL WHERE，非 Java 端過濾
- [ ] 無 `PAYMENT_RECONCILE` 權限者 403

**邊界**

- [ ] `coffee-payments` 未引用 `com.coffee.orders.internal`
- [ ] 對帳服務未直接寫 `orders` 表，全部透過 `Orders` 介面
- [ ] 未引入任何新的第三方依賴
- [ ] `ModuleBoundariesTest` 通過

**既有行為不回歸**

- [ ] `EcpayService.callback()` 行為完全不變，既有 `CoffeeIntegrationTest` / `HttpWorkflowTest` / `CheckMacTest` 全數通過
- [ ] 三參數 `confirmOnline` 仍可用且語意不變

**前端**

- [ ] `modules/payments` 新增待查核訂單畫面，有權限者才顯示入口
- [ ] HTTP 呼叫經過 `shared/api.ts`，未在元件內直接 `fetch`
- [ ] 金額顯示使用 `shared/format.ts`
- [ ] `npm run build` 通過

---

## 12. 測試要求

放在 `backend/coffee-app/src/test/java/com/coffee/app/`。

### 單元測試（不需 Spring context 優先）

- [ ] 查單 `CheckMacValue` 產生：用綠界官方文件的測試向量驗證
- [ ] 回應解析：正常、缺欄位、URL-encoded 中文、多餘欄位
- [ ] 決策表五種 `outcome` 的判定邏輯，逐列一個案例
- [ ] `PaymentDate` → epoch millis 的時區換算：驗證 `2026/09/17 08:30:00` 換算結果對應 Asia/Taipei 而非 UTC
- [ ] `TradeAmt` 非數字、超出 int 範圍時視為 `QUERY_FAILED`

### 整合測試

綠界查單以測試替身（stub）模擬，**不得在測試中連外**。

- [ ] 卡住的訂單經對帳後變成 `PAID`，`paid_at` 等於綠界回報時間
- [ ] 該訂單隨後出現在對應台灣日期的報表營業額中（驗證第 7 節的時區問題確實修掉）
- [ ] 金額不符：訂單維持 `PENDING_PAYMENT`，留下 `AMOUNT_MISMATCH` 紀錄
- [ ] `SimulatePaid=1`：訂單維持 `PENDING_PAYMENT`，留下 `SIMULATED` 紀錄
- [ ] 查單失敗：訂單維持 `PENDING_PAYMENT`，留下 `QUERY_FAILED` 紀錄
- [ ] **重複入帳防護**：對同一訂單連續對帳兩次，只入帳一次，`payment_reconciliations` 有兩筆但訂單 `paid_at` 不變
- [ ] **與回呼競態**：訂單已由回呼入帳後再跑對帳，不重複入帳、不拋錯
- [ ] 靜默期：建立未滿 `min-age-minutes` 的訂單不被排程挑中
- [ ] 逾期：超過 `max-age-days` 的訂單不被排程挑中
- [ ] `CASH` 訂單永遠不被對帳挑中

### 越權測試（`AGENTS.md` 明列必要項）

- [ ] 顧客（`SELF`）呼叫三個端點皆得 403
- [ ] A 店店長對 B 店訂單呼叫 `POST /api/payments/reconciliation/{id}` 得 403
- [ ] A 店店長的 `GET /pending` 回應中**不含**任何 B 店訂單
- [ ] 有 `ORDER_MANAGE` 但無 `PAYMENT_RECONCILE` 的帳號得 403
- [ ] 未登入得 401
- [ ] 缺 CSRF token 的 `POST` 被擋

### HTTP 流程測試

- [ ] 於 `HttpWorkflowTest` 補一段真實 HTTP + Cookie + CSRF 的對帳流程

---

## 13. 待 PO 決定

Codex 實作前如果這幾項未定，請照括號內的**預設值**做，並在 PR 描述標明，等 PO 回覆後再調整。

1. **排程間隔與靜默期** —— 5 分鐘 / 15 分鐘是我依「顧客在綠界付款頁停留時間」推估的。（預設：5 分鐘、15 分鐘）
2. **`max-age-days`** —— 超過幾天就不再查？牽涉綠界查單 API 的資料保留期限。（預設：7 天）
3. **告警管道** —— `AMOUNT_MISMATCH` 是可能的竄改訊號，目前只會躺在清單裡等人看。要不要接 email / Slack？本規格暫不含通知。（預設：不做，另開缺口）
4. **哪些角色該有 `PAYMENT_RECONCILE`** —— 建議只給總部（GLOBAL）與店長（BRANCH），一般收銀員不給。（預設：HQ 與店長角色）
5. **對帳紀錄保留期限** —— `payment_reconciliations` 會持續累積，要不要定期清理？（預設：不清理，另開缺口）
6. **綠界 stage 環境的查單 API 是否可用** —— `docs/ECPAY.md` 記載尚未經公開網域實際完成端到端付款測試。若 stage 查單無法驗證，本功能只能靠 stub 測試，正式上線前必須安排一次真實環境驗證。**這是本規格最大的未驗證風險。**

---

## 14. 給 Codex 的提醒

- `AGENTS.md` 工作流程第 1 步：**先輸出設計摘要，等 PO 確認再動工**
- 這份規格若有錯、不完整或技術上做不到（特別是第 6 節的綠界查單細節——我是依官方文件寫的，沒有實際打過那支 API），**先回報，不要自己補洞後默默實作**
- 對帳邏輯碰的是真錢。寧可「該入帳的沒入帳，留紀錄等人處理」，也不要「不該入帳的入了帳」
- 實作回報寫到 `docs/reports/`
