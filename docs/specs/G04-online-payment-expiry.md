# G04 — 線上付款訂單的逾時作廢（與對帳互斥）

版本：v1.0（2026-10-12）
作者：Claude（PM / SA）
前置：G01（PR #9）、G01a（PR #15）已合併；**G01b（`docs/specs/G01b-ecpay-stage-integration.md`）必須已合併**才能開工 S2 —— S2 的測試要用 G01b S2 的 `FakeEcpayServer`。S1 不依賴 G01b，可先做。
金流線：本規格是 PO 2026-10-11 決策（只串綠界 stage）的第二份、也是最後一份規格。

---

## 1. 背景與目標

`OrderService.transition()`（`OrderService.java:630-656`）只允許取消 `CASH` + `PENDING_PAYMENT` 的訂單。線上付款（`ECPAY`）訂單一旦建立就**沒有出口**：顧客在綠界付款頁放棄後，訂單永遠停在 `PENDING_PAYMENT`，每一輪排程對帳都會查它一次直到 `max-age-days`（7 天）後掉出視窗，然後永遠掛在那裡。它還扣著 G08 的每日可售數量（`branch_product_stock_reservation`），讓限量商品提早「售完」。

這件事不能用「建立 N 分鐘後直接取消」解決，因為**顧客可能已經付了錢，只是回呼沒送到**（G01b 讓 demo 預設就是這個狀態：`http://localhost` 收不到回呼）。直接取消會作廢一筆已扣款的訂單。

**目標：**

1. 線上付款訂單建立超過門檻且**經綠界查單確認未付款**時，系統自動作廢（`CANCELLED`），並回補 G08 的保留數量
2. 作廢與對帳互斥：同一筆訂單的「查單 → 作廢」與回呼入帳、排程對帳、手動對帳不會交錯出錯誤結果
3. 查單與作廢之間那一小段無法消除的競態（顧客剛好在那幾秒內付款）**不沉默**：一定會被偵測、記錄、出現在店長的對帳畫面，由人工到綠界廠商後台退款
4. 縮小上述競態：訂單建立後只在「付款窗口」內可以送出綠界表單，窗口與作廢門檻之間至少隔 30 分鐘

## 2. 範圍

### 2.1 在範圍內

- **付款窗口**：`POST /api/payments/ecpay/{id}` 在訂單建立超過 `checkout-window-minutes` 後回 409
- **逾時作廢排程**：查單確認未付款才作廢；已付款就入帳；查不準就保留下一輪再試
- **回呼遇到已作廢訂單**：不入帳、不復活訂單，記一筆「作廢後收到付款」異常，回 `1|OK`
- **作廢後複查**：作廢後再查一次綠界，抓到回呼沒送到的「作廢後付款」
- **異常清單與結案**：店長／總部在對帳畫面看到未結案異常，到綠界後台退款後按「已處理」並留備註
- **設定、demo profile、文件、stage 實機驗證步驟**

### 2.2 不在範圍內

- **顧客或店員手動取消線上付款訂單** —— 維持現狀（只能等逾時作廢）。理由見 §14.4
- **G03 退款**（仍延後）。異常只負責「讓人知道要退款」，退款本身在綠界廠商後台人工做
- **G02 電子發票**（仍延後）
- **訂單作廢時退還優惠碼兌換次數** —— 沿用 G07 §11.8「取消不退還」
- **同一訂單重複送出付款表單時綠界的行為** —— G01b §15.6 的觀察項，仍待 G01b §11 步驟 5 的實機結果；本規格的付款窗口不依賴它
- **G01a 第 3 項非阻斷觀察**（`pending()` 查詢次數沒有測試釘住）—— 本規格不動 `pending()`，不夾帶。理由見 §14.12
- 綠界正式環境、正式特店憑證（屬 PO 的「帳號與憑證」，**不得出現在 repo**）

## 3. 涉及模組與邊界

| 模組 | 變更 | 邊界影響 |
| --- | --- | --- |
| `coffee-orders` | `api/Orders.java` 新增兩個方法；`internal/OrderService.java` 實作 | 無新依賴（已依賴 `coffee-catalog`、`coffee-audit`） |
| `coffee-payments` | `EcpayService`（付款窗口、回呼改呼叫 `settleOnline`、異常紀錄）、`ReconciliationService`（作廢排程、複查、異常查詢與結案）、新增 `internal/PaymentAnomalyController.java`、`api/Reconciliation.java` 新增 record 與方法 | **不新增** `coffee-payments → coffee-audit` 依賴（§14.6）。只透過 `Orders` 介面改訂單狀態，不直接寫 `orders` 表 |
| `coffee-app` | `V15__payment_expiry.sql`、`application.yml`、`application-ecpay-stage.yml`、測試 | — |
| `frontend` | `modules/payments/ReconciliationView.vue`、`modules/ordering/OrdersView.vue`（一段提示）、`modules/identity/AuditView.vue`（一個選項）、`shared/types.ts`、DOM 測試 | 無 |
| 文件 | `docs/ECPAY.md` | — |

- `payments` 只透過 `Orders`（`orders.api`）讀寫訂單。`payment_expiries`、`payment_anomalies` 兩張新表屬 `coffee-payments`，`orders` 模組不讀它們
- **零新依賴**（Maven 與 npm 都是）

## 4. DB schema —— `V15__payment_expiry.sql`

**Flyway 版號 `V15`**：主線最新是 `V14__item_promotions.sql`，G20c／G20j／G20k／G01b 都零 migration。開工前用 `ls backend/coffee-app/src/main/resources/db/migration/` 與 open PR 再確認一次沒有人佔用 `V15`。

```sql
CREATE TABLE payment_expiries(
  order_id VARCHAR(20) PRIMARY KEY REFERENCES orders(id),
  expired_at BIGINT NOT NULL,
  recheck_attempts INTEGER NOT NULL DEFAULT 0 CHECK(recheck_attempts>=0),
  rechecked_at BIGINT,
  recheck_outcome VARCHAR(12) CHECK(recheck_outcome IN ('UNPAID','PAID','SIMULATED','GAVE_UP'))
);
CREATE INDEX idx_payment_expiries_recheck ON payment_expiries(rechecked_at,expired_at);

CREATE TABLE payment_anomalies(
  id VARCHAR(36) PRIMARY KEY,
  order_id VARCHAR(20) NOT NULL REFERENCES orders(id),
  branch_id VARCHAR(36) NOT NULL REFERENCES branches(id),
  order_total INTEGER NOT NULL,
  kind VARCHAR(24) NOT NULL CHECK(kind IN ('PAID_AFTER_EXPIRY')),
  source VARCHAR(12) NOT NULL CHECK(source IN ('CALLBACK','RECHECK')),
  provider_trade_no VARCHAR(64) NOT NULL,
  trade_amount INTEGER,
  detected_at BIGINT NOT NULL,
  resolved_at BIGINT,
  resolved_by VARCHAR(36),
  resolution_note VARCHAR(200),
  UNIQUE(order_id,kind)
);
CREATE INDEX idx_payment_anomalies_open ON payment_anomalies(resolved_at,detected_at);
CREATE INDEX idx_payment_anomalies_branch ON payment_anomalies(branch_id,resolved_at,detected_at);
```

- `payment_expiries` 一筆 = 一張被系統作廢的線上付款訂單。只有本規格的作廢路徑會寫入
- `payment_anomalies.branch_id`／`order_total` 是**寫入當下的快照**，讓清單查詢不必跨模組 join `orders`（§14.6）
- `UNIQUE(order_id,kind)`：一張訂單最多一筆「作廢後收到付款」，回呼重送與複查都不會重複（§14.10）
- 不改 `orders`、不改 `payment_reconciliations` 的 CHECK（§14.8）

## 5. 設定

### 5.1 `application.yml`（正式設定的預設值）—— 只新增，預設關閉

```yaml
ecpay:
  # …既有欄位不變…
  expire:
    enabled: ${ECPAY_EXPIRE_ENABLED:false}
    checkout-window-minutes: ${ECPAY_EXPIRE_CHECKOUT_WINDOW_MINUTES:20}
    after-minutes: ${ECPAY_EXPIRE_AFTER_MINUTES:60}
    recheck-after-minutes: ${ECPAY_EXPIRE_RECHECK_AFTER_MINUTES:30}
    interval-ms: ${ECPAY_EXPIRE_INTERVAL_MS:300000}
```

### 5.2 `application-ecpay-stage.yml`（G01b S3 新增的 demo profile）—— 追加

```yaml
ecpay:
  expire:
    enabled: ${ECPAY_EXPIRE_ENABLED:true}
    checkout-window-minutes: ${ECPAY_EXPIRE_CHECKOUT_WINDOW_MINUTES:10}
    after-minutes: ${ECPAY_EXPIRE_AFTER_MINUTES:40}
    recheck-after-minutes: ${ECPAY_EXPIRE_RECHECK_AFTER_MINUTES:10}
    interval-ms: ${ECPAY_EXPIRE_INTERVAL_MS:120000}
```

### 5.3 啟動檢查（`ReconciliationService` 建構子）

`expire.enabled=true` 時以下任一不成立就丟 `IllegalStateException`，Spring context 啟動失敗。**訊息不得包含任何設定值**（比照 G01b §6.1）：

| 條件 | 訊息 |
| --- | --- |
| `ecpay.reconcile.enabled=true` | `啟用逾時作廢前必須先啟用金流對帳` |
| `5 <= checkout-window-minutes <= 1440` | `逾時作廢設定不正確` |
| `after-minutes >= checkout-window-minutes + 30` | 同上 |
| `after-minutes > reconcile.min-age-minutes` | 同上 |
| `after-minutes <= 10080` | 同上 |
| `5 <= recheck-after-minutes <= 1440` | 同上 |
| `60000 <= interval-ms <= 86400000` | 同上 |

`expire.enabled=false` 時**不檢查**其他 `expire.*` 值（讓既有部署不因新設定而啟動失敗）。

`EcpayService` 也要讀 `ecpay.expire.enabled` 與 `ecpay.expire.checkout-window-minutes`（§6.1 的付款窗口），**不重複做**上表的檢查（檢查只在一處）。

## 6. 後端變更

### 6.1 付款窗口（`EcpayService.checkout`）

在 `orders.payable(a, id)` 之後：

```java
if (expireEnabled && System.currentTimeMillis() - o.createdAt() > checkoutWindowMs)
  throw new Problem(409, "此訂單已超過線上付款期限，請重新下單");
```

- 只在 `expire.enabled=true` 時生效。`false` 時行為與今天完全相同
- 擋在 `payments` 而不是 `Orders.payable()`：付款窗口是金流設定，`orders` 模組不該知道（§14.5）

### 6.2 `Orders` 介面新增兩個方法（`coffee-orders`）

```java
/**
 * 系統逾時作廢線上付款訂單。只在 ECPAY + PENDING_PAYMENT + paid_at 為空時作廢並回 true；
 * 其他任何狀態回 false，不丟例外。呼叫端必須已向綠界查單確認未付款。呼叫端必須已在交易內。
 */
boolean expireOnline(String id);

/**
 * 回呼入帳。回傳 CONFIRMED（本次入帳）、ALREADY_PAID（同一交易編號已入帳，冪等）、
 * CANCELLED（訂單已作廢，未入帳）。金額、付款方式、交易編號不一致時丟 Problem（同 confirmOnline）。
 */
String settleOnline(String id, int amount, String providerTradeNo, long paidAt);
```

**`expireOnline` 實作（`OrderService`，`@Transactional`）：**

1. `lock(id)`（既有的 `select … for update`；找不到丟 404）
2. `snapshot(id)`；若 `paymentMethod != ECPAY` 或 `status != PENDING_PAYMENT` 或 `paidAt != null` → 回 `false`
3. `update orders set status='CANCELLED' where id=?`
4. `catalog.releaseStock(o.branchId(), id)`（與 `transition()` 的取消同一個呼叫）
5. `audit.record(null, "ORDER_EXPIRED", id, o.branchId(), "線上付款逾時未付款，系統作廢（作廢前已向綠界查單確認未付款）")`（`actor=null` 在 `AuditService` 記為「系統」，既有行為）
6. 回 `true`

**`settleOnline` 實作（`OrderService`，`@Transactional`）：** 與既有 `confirmOnline(id, amount, trade, paidAt)` 相同，差別只有兩處：

- `lock(id)` 與 `snapshot(id)` 之後，若 `status == CANCELLED` **且** `paymentMethod == ECPAY` **且** `total == amount` → 回 `"CANCELLED"`，**不改任何資料**
- 已入帳且交易編號相同 → 回 `"ALREADY_PAID"`；本次入帳 → 回 `"CONFIRMED"`

`confirmOnline` 兩個多載**保留不動**（`ReconciliationService.perform()` 仍在用，`HttpWorkflowTest` 等既有測試也在用）。為什麼新增而不是改它，見 §14.11。

### 6.3 回呼（`EcpayService.callback`）

把 `orders.confirmOnline(id, amount, trade)` 改成：

```java
String settled = orders.settleOnline(id, amount, trade, System.currentTimeMillis());
if ("CANCELLED".equals(settled)) recordAnomaly(order, "CALLBACK", trade, amount);
```

- 只有 `RtnCode=1` 且非 `SimulatePaid` 才呼叫（與今天相同）
- `recordAnomaly`（`EcpayService` 與 `ReconciliationService` 共用，放在新的 package-private 類別 `internal/PaymentAnomalies.java`）：
  ```sql
  insert into payment_anomalies(id,order_id,branch_id,order_total,kind,source,provider_trade_no,trade_amount,detected_at)
  select ?,?,?,?,'PAID_AFTER_EXPIRY',?,?,?,? where not exists
    (select 1 from payment_anomalies where order_id=? and kind='PAID_AFTER_EXPIRY')
  ```
  實際新增一列時記一行 WARN：`綠界作廢後收到付款：訂單 {orderId}，來源 {source}，需人工退款`（**不得**印交易編號以外的回呼內容，**不得**印 key／iv）
- `payment_events` 照常寫入一列（與今天相同）
- 回 `1|OK`（綠界停止重送；資料已持久化）
- 回呼與作廢的互斥靠 `settleOnline` 的 `lock(id)`：回呼先拿到鎖 → 入帳 → 作廢那邊看到 `PAID` 回 `false`；作廢先拿到鎖 → 回呼看到 `CANCELLED` → 異常。兩條路徑都在同一列 `for update` 上序列化，**多個應用程式實例也成立**

### 6.4 逾時作廢排程（`ReconciliationService`）

```java
@Scheduled(fixedDelayString = "${ecpay.expire.interval-ms:300000}",
           initialDelayString = "${ecpay.expire.interval-ms:300000}")
public synchronized void expireScheduled()
```

- `synchronized` 與既有 `scheduled()` 同一個 monitor（`this`）→ 排程對帳與逾時作廢在同一個 JVM 內不會同時跑
- `expire.enabled=false` → 直接 return
- 候選：`orders.reconciliationCandidates(null, 0, now - afterMs, batch, expireCursor)`（重用既有方法：`ECPAY` + `PENDING_PAYMENT`，`since=0` 表示不設下限，連 7 天前的舊單也會被處理）。`expireCursor` 輪轉方式與 `scheduled()` 的 `cursor` 相同（反覆查單失敗的舊單不能餓死後面的訂單）
- 每筆呼叫 `expireOne(id)`，`RuntimeException` 記 WARN（`綠界逾時作廢單筆失敗：訂單 {id}，{例外類別簡名}`，不帶 `e`、不印 message），計入「略過」
- 每輪結束記一行 INFO（候選 0 筆也要）：
  `綠界逾時作廢完成：候選 {n} 筆，作廢 {a}、已付款 {b}、保留 {c}、略過 {d}`

**`expireOne(id)`：**

```
synchronized (locks[stripe(id)]) {                // 與 perform() 同一組 stripe：手動對帳同一筆訂單會排隊
  Result r = perform(null, id);                    // 既有邏輯：查單、驗簽、驗金額、寫 payment_reconciliations(SCHEDULED)
  switch (r.outcome()) {
    case "STILL_UNPAID", "SIMULATED" ->
        tx { if (orders.expireOnline(id)) insert payment_expiries(order_id, expired_at=now); }
        → 作廢（expireOnline 回 false 也算「保留」：期間被回呼入帳了）
    case "CONFIRMED"  → 已付款（perform 已入帳）
    default           → 保留（QUERY_FAILED、AMOUNT_MISMATCH：不作廢，下一輪再試）
  }
}
```

- `perform()` 的判斷邏輯**不改**；`perform()` 對非 `PENDING_PAYMENT` 丟 409 → 計入「略過」
- 「查單」與「作廢」**不在同一個 DB 交易**：查單是外部 HTTP（最長 `query-timeout-ms`），不能抱著訂單列鎖等它（會卡住回呼）。中間那段競態由 §6.3 與 §6.5 收尾，理由見 §14.2
- `expireOnline` 與 `payment_expiries` 的寫入**在同一個交易**（`tx.executeWithoutResult`）

### 6.5 作廢後複查（`ReconciliationService`）

在 `expireScheduled()` 的作廢迴圈**之後**、同一次呼叫內執行：

- 候選：`select order_id,expired_at from payment_expiries where rechecked_at is null and expired_at<=? order by expired_at,order_id limit ?`（`now - recheckAfterMs`、`batch`）
- 每筆：`orders.paymentSnapshot(id)` 取金額與分店 → 走 `verify(id, total)`（見下）→ 依結果：

| 查單結果 | 動作 | `recheck_outcome` |
| --- | --- | --- |
| 未付款（`TradeStatus != 1`） | `rechecked_at=now` | `UNPAID` |
| 模擬付款 | `rechecked_at=now` | `SIMULATED` |
| 已付款（不論金額是否相符） | `recordAnomaly(…,"RECHECK",trade,amount)`；`rechecked_at=now` | `PAID` |
| 查單失敗（連線、驗簽、格式） | `recheck_attempts+1`，`rechecked_at` 維持 null，下一輪再試 | — |
| 查單失敗且 `expired_at < now - max-age-days` | `rechecked_at=now`，記 WARN `綠界作廢後複查放棄：訂單 {id}` | `GAVE_UP` |

- 每輪結束記一行 INFO（候選 0 筆也要）：`綠界作廢後複查完成：候選 {n} 筆，未付款 {a}、已付款 {b}、模擬 {c}、查單失敗 {d}、放棄 {e}`
- **`verify(id, expectedTotal)`**：把 `perform()` 裡「`query.query(id)` → 驗 CheckMac／MerchantID／MerchantTradeNo → 解析 TradeStatus／TradeAmt／TradeNo／PaymentDate」那一段**原封不動抽成私有方法**，回傳一個私有 record（outcome、tradeStatus、amount、tradeNo、paidAt、detail）。`perform()` 改呼叫它，**行為不變**（G01／G01a／G01b 的既有測試全數不改即通過，驗收 4 釘住）。抽出是為了讓複查重用同一套驗證，不准另寫一份
- 複查**不寫** `payment_reconciliations`（那張表的語意是「待付款訂單的查單紀錄」，且 `outcome` 的 CHECK 不含作廢後的狀態），結果記在 `payment_expiries`

### 6.6 異常清單與結案（`Reconciliation` 介面 + `PaymentAnomalyController`）

`api/Reconciliation.java` 新增：

```java
record Anomaly(String id, String orderId, String branchId, int orderTotal, Integer tradeAmount,
               String providerTradeNo, String source, long detectedAt) {}
record AnomalyPage(List<Anomaly> items, boolean truncated) {}

AnomalyPage anomalies(Actor actor);
Anomaly resolveAnomaly(Actor actor, String anomalyId, String note);
```

實作在 `ReconciliationService`（重用既有 `authorize(actor)`：顧客 403、無 `PAYMENT_RECONCILE` 403、對帳未啟用 503）。

## 7. API

| 方法與路徑 | 權限 | 資料範圍 | 說明 |
| --- | --- | --- | --- |
| `POST /api/payments/ecpay/{id}` | 既有（顧客本人） | SELF | **變更**：超過付款窗口回 409 `此訂單已超過線上付款期限，請重新下單` |
| `POST /api/payments/ecpay/callback` | 既有（免登入、靠 CheckMacValue） | — | **變更**：訂單已作廢時記異常、回 `1|OK` |
| `GET /api/payments/config` | 既有 | — | **加法**：回應多一個 `expire` 物件，見下 |
| `GET /api/payments/anomalies` | `PAYMENT_RECONCILE` | BRANCH／GLOBAL | **新增**：未結案異常 |
| `POST /api/payments/anomalies/{id}/resolve` | `PAYMENT_RECONCILE` + CSRF | BRANCH／GLOBAL | **新增**：結案 |

**`GET /api/payments/config` 回應（加法，既有兩個 key 不變）：**

```json
{ "enabled": true, "environment": "stage",
  "expire": { "enabled": true, "checkoutWindowMinutes": 10, "afterMinutes": 40 } }
```

`expire.enabled=false` 時仍回三個欄位（`enabled:false` 與設定值），前端以 `enabled` 判斷。

**`GET /api/payments/anomalies` 回應：**

```json
{ "items": [ { "id": "…", "orderId": "…20 碼…", "branchId": "…", "orderTotal": 280,
               "tradeAmount": 280, "providerTradeNo": "…", "source": "CALLBACK",
               "detectedAt": 1760000000000 } ],
  "truncated": false }
```

- 只回 `resolved_at is null`；`order by detected_at desc, id desc`；取 201 筆、回前 200 筆，第 201 筆存在則 `truncated=true`（比照 `pending()`）
- 非 GLOBAL：SQL 加 `and branch_id=?`（actor 的分店），**在 SQL 層過濾**，不是撈全部再濾
- 純讀取，不得有任何寫入（G20k 的唯讀精神）

**`POST /api/payments/anomalies/{id}/resolve`：**

- 請求：`{"note": "已於綠界後台退款，退款單號 …"}`。`note` trim 後 1–200 字，否則 400 `請填寫處理說明（200 字以內）`
- `id` 不存在 → 404 `找不到這筆異常`
- 非 GLOBAL 且異常的 `branch_id` 不是 actor 分店 → 403 `沒有處理其他分店異常的權限`（**先判 404 再判 403** 與既有 `order()` 的順序一致）
- 已結案 → 409 `這筆異常已處理`
- 成功：`update payment_anomalies set resolved_at=?,resolved_by=?,resolution_note=? where id=? and resolved_at is null`；影響 0 列（併發結案）→ 409 同上。回傳結案後的 `Anomaly`（200）
- **不呼叫綠界**、**不改訂單狀態**（訂單維持 `CANCELLED`）

## 8. 權限與資料範圍

- 新端點沿用既有權限 `PAYMENT_RECONCILE`（`V2` 已授予 `HQ`、`MANAGER`）。**不新增權限常數**
- `HQ`（GLOBAL）看全部分店；`MANAGER`（BRANCH）只看與只能結案自己分店；顧客（SELF）一律 403
- 逾時作廢與複查是系統動作（`actor=null`），不經權限檢查；稽核紀錄記為「系統」
- **越權測試（必做）**：見 §12

## 9. 金額規則

- 作廢不涉及金額計算
- 回呼遇到已作廢訂單：`settleOnline` 仍先比對 `amount == 訂單 total`（DB 值），不符照舊丟 Problem → `0|Invalid notification`（與今天相同，**不**記異常：金額不符的回呼本來就不可信）
- 複查發現「已付款但金額不符」**仍記異常**（`trade_amount` 記綠界回的金額）：錢已經進來了，不論多少都要退。異常的 `order_total` 與 `trade_amount` 並列給店長判斷
- 異常清單的金額**一律來自 DB 快照或綠界查單結果**，不接受前端傳入

## 10. 本機測試設計（沿用 G01b 的 `FakeEcpayServer`）

- 新測試放在 `backend/coffee-app/src/test/java/com/coffee/app/ecpay/`（與 `FakeEcpayServer` 同 package，它是 package-private）
- Spring context 接線比照 G01b §10.3：`@DynamicPropertySource` 把 `ecpay.query-url` 指向假伺服器；另加 `ecpay.expire.enabled=true`、`checkout-window-minutes=5`、`after-minutes=35`、`recheck-after-minutes=5`、`interval-ms=86400000`（排程由測試直接呼叫 `expireScheduled()`）、`ecpay.reconcile.min-age-minutes=0`
- **「訂單建立超過 N 分鐘」用改 DB 的方式製造**：`update orders set created_at=? where id=?`（測試輔助，不改生產碼）。**不得** sleep 等真實時間；不得用無參數 `now()`（G27 `TimeZoneGuardTest`）
- 作廢後複查同理：`update payment_expiries set expired_at=? where order_id=?`
- 若 G01b 合併時 `FakeEcpayServer` 的介面與 G01b §10.1 不同（例如 `respond` 多了金額參數），**以主線上的實際介面為準**，不要另寫一個假伺服器
- `FakeEcpayServer` 只准做**加法**（新增情境、新增查詢方法）；既有方法與情境的行為不改，G01b 的 `EcpayStageEndToEndTest` 不修改即通過
- `EcpayNoEgressGuardTest`（G01b）照樣涵蓋新測試檔；新測試若用 `ecpay-stage` profile 必須覆寫 `ecpay.query-url`
- **不得 `@MockitoBean TradeQuery`**（同 G01b：要走真的 HTTP）

## 11. stage 實機驗證步驟（追加到 `docs/ECPAY.md` G01b §11 之後，列入 GAP-ANALYSIS「待 PO 驗收」，不擋合併）

前置：G01b §11 的準備步驟。demo profile 的作廢設定：付款窗口 10 分鐘、作廢門檻 40 分鐘、複查 10 分鐘、每 2 分鐘一輪。

**步驟 7 — 綠界付款頁可停留多久：** 建立線上付款訂單 → 進綠界 stage 付款頁 → 不操作，每 5 分鐘試著繼續付款一次。**記錄**付款頁失效的時間（或 60 分鐘後仍可付款）。這是 §14.7 預設值的輸入：若付款頁可停留超過「作廢門檻 − 付款窗口」（正式預設 40 分鐘、demo 30 分鐘），要回報 Claude 加大門檻。

**步驟 8 — 正常逾時作廢：** 建立線上付款訂單、不付款。約 40–44 分鐘後：log 出現 `綠界逾時作廢完成：…作廢 1…`；訂單頁顯示「已取消」；稽核紀錄有 `ORDER_EXPIRED`（操作者「系統」）；`payment_reconciliations` 有一列 `SCHEDULED`／`STILL_UNPAID`；限量商品的剩餘數量回補。約 10–14 分鐘後 log 出現 `綠界作廢後複查完成：…未付款 1…`。

**步驟 9 — 付款窗口：** 建立線上付款訂單，等 11 分鐘後在訂單頁按付款。**預期**：提示「此訂單已超過線上付款期限，請重新下單」，不會導到綠界。

**步驟 10 — 作廢後付款（只有步驟 7 顯示付款頁可停留超過 30 分鐘時才做，否則記「不適用」）：** 建立訂單 → 第 9 分鐘進綠界付款頁 → 停在頁面直到訂單被作廢（約第 40 分鐘）→ 用測試卡付款。**預期**：有通道時回呼記一筆異常（來源 `CALLBACK`）；沒通道時約 10–14 分鐘後複查記一筆異常（來源 `RECHECK`）；店長的對帳頁出現「作廢後收到付款」；到綠界 stage 廠商後台對該筆交易退款後，在對帳頁按「已處理」並填說明，異常消失。

**驗收紀錄格式**沿用 G01b §11。

## 12. 施工階段

三個階段都是加法：S1 新表與新方法無人呼叫、付款窗口預設關閉；S2 的作廢預設關閉（只有測試打開）；S3 才在 demo profile 打開。每段單獨合併都不改變任何既有行為。

### S1 — schema、`Orders` 新方法、設定與付款窗口（不依賴 G01b）

**動到：** `V15__payment_expiry.sql`、`Orders.java`、`OrderService.java`、`EcpayService.java`（只加付款窗口與讀設定，**回呼不動**）、`ReconciliationService.java`（只加建構子參數與 §5.3 檢查）、`application.yml`、新增 `OrderExpiryTest.java`、`PaymentExpiryConfigTest.java`。

**驗收子集：** 1、2、3、4（S1 部分）、5、6、7。

### S2 — 逾時作廢、回呼異常、作廢後複查（需要 G01b 已合併）

**動到：** `EcpayService.java`（回呼改 `settleOnline` + 異常）、`ReconciliationService.java`（`expireScheduled`、`expireOne`、複查、`verify` 抽出）、新增 `internal/PaymentAnomalies.java`、新增 `EcpayExpiryEndToEndTest.java`、`FakeEcpayServer.java`（只准加法，§10）、擴充 log 衛生檢查。

**驗收子集：** 4（S2 部分）、8–20。

**為什麼複查放在 S2 而不是 S3：** 沒有複查的話，demo（`http://localhost` 收不到回呼）下的「作廢後付款」是**完全沉默**的 —— 這正是目標 3 要擋的事。作廢與複查必須一起進主線。

### S3 — 異常清單／結案端點、前端、demo profile、文件

**動到：** `Reconciliation.java`（record 與方法）、`ReconciliationService.java`（`anomalies`、`resolveAnomaly`）、新增 `PaymentAnomalyController.java`、`EcpayService.config()`、`application-ecpay-stage.yml`、`ReconciliationView.vue`、`OrdersView.vue`、`AuditView.vue`、`shared/types.ts`、DOM 測試、新增 `PaymentAnomalyApiTest.java`、`docs/ECPAY.md`。

**驗收子集：** 21–30。

**規模估計：** S2 最重（一個排程方法、一個抽出重構、一支端到端測試約 10 個情境），仍在一次執行內。若 S2 做不完，**先推作廢 + 回呼異常（驗收 8–15），複查（16–19）另開一個 commit**，但兩者必須在同一個 PR 合併前都完成（理由同上：不能讓「作廢」單獨進主線而沒有複查）。

## 13. 驗收條件

**S1**

- [ ] 1. `V15__payment_expiry.sql` 建立兩張表與三個索引，欄位、CHECK、UNIQUE 與 §4 一致；Flyway 在 H2 與 CI 用的資料庫上都能套用
- [ ] 2. `expireOnline`：`ECPAY`＋`PENDING_PAYMENT` → 回 `true`、訂單 `CANCELLED`、`branch_product_stock_reservation` 該訂單的列被刪且 `remaining` 回補、`audit_log` 一列 `ORDER_EXPIRED`（`actor_id='system'`）；同一筆再呼叫一次 → 回 `false`、不再寫稽核；`PAID`、`CASH`、不存在（404）各一條
- [ ] 3. `settleOnline`：`PENDING_PAYMENT` → `CONFIRMED` 且入帳；同交易編號重送 → `ALREADY_PAID`、`paid_at` 不變；不同交易編號 → `Problem`；`CANCELLED` 且金額相符 → `CANCELLED`、訂單完全不變；`CANCELLED` 且金額不符 → `Problem`
- [ ] 4. （S1 部分）`confirmOnline` 兩個多載的簽名與行為不變；`ReconciliationTest`、`HttpWorkflowTest`、`CoffeeIntegrationTest`、`CheckMacTest` 與 G01b 的所有測試**不修改**即通過
- [ ] 5. §5.3 每一條不成立時 context 啟動失敗、訊息正確且不含設定值（`@ParameterizedTest`）；`expire.enabled=false` 時任意 `expire.*` 值都能啟動
- [ ] 6. 付款窗口：`expire.enabled=true` 時，建立超過 `checkout-window-minutes` 的訂單 `POST /api/payments/ecpay/{id}` 回 409 與指定訊息；窗口內回 200；`expire.enabled=false` 時任何年齡都回 200
- [ ] 7. `application.yml` 的 `expire.enabled` 預設為 `false`（讀 classpath 上的檔案斷言）

**S2**

- [ ] 8. 假伺服器 `NOT_FOUND`：逾時訂單被作廢；`payment_reconciliations` 一列 `SCHEDULED`／`STILL_UNPAID`；`payment_expiries` 一列；INFO 摘要 `作廢 1`
- [ ] 9. 假伺服器 `PAID`：逾時訂單**不作廢**、轉 `PAID`；`payment_expiries` 無列；INFO 摘要 `已付款 1`
- [ ] 10. 假伺服器 `TIMEOUT`、`HTTP_500`、`BAD_MAC`、`CONNECTION_REFUSED`、`AMOUNT_MISMATCH`：逾時訂單**不作廢**、仍 `PENDING_PAYMENT`；INFO 摘要 `保留 1`；下一輪改回 `NOT_FOUND` 後被作廢
- [ ] 11. 假伺服器回模擬付款（`TradeStatus=1`、`SimulatePaid=1`）：逾時訂單被作廢。`FakeEcpayServer` 沒有此情境時，**只准新增**一個 `SIMULATED` 列舉值（同 `PAID` 但多 `SimulatePaid=1`），不得改 G01b 已驗收的既有情境行為
- [ ] 12. 未逾時（建立未滿 `after-minutes`）的訂單不被查單、不被作廢；`CASH` 訂單永遠不在候選內
- [ ] 13. 順序一（回呼先到）：有效付款回呼 → `PAID`；接著 `expireScheduled()` → 不查單（不在候選）、訂單維持 `PAID`
- [ ] 14. 順序二（作廢先到）：作廢後送有效付款回呼（真實 HTTP、無 CSRF、無 cookie）→ `1|OK`；訂單維持 `CANCELLED`、`paid_at` 為空；`payment_anomalies` 一列（`CALLBACK`）；`payment_events` 一列；同一回呼再送 → `1|OK`、異常仍一列。作廢後送**金額不符**的回呼 → `0|Invalid notification`、無異常
- [ ] 15. 已作廢訂單手動對帳（店長真實登入 + CSRF）→ 409 `這筆訂單不需要對帳`，假伺服器 `receivedQueries()` 不增加
- [ ] 16. 複查未付款：`expired_at` 早於 `recheck-after` → `recheck_outcome=UNPAID`、`rechecked_at` 有值；再跑一輪不再查它
- [ ] 17. 複查已付款：假伺服器 `PAID` → 異常一列（`RECHECK`）、`recheck_outcome=PAID`、訂單仍 `CANCELLED`；若同一訂單已有 `CALLBACK` 異常 → 仍只有一列
- [ ] 18. 複查查單失敗：`recheck_attempts` 加 1、`rechecked_at` 仍為空；`expired_at` 早於 `max-age-days` 時 → `GAVE_UP` 與 WARN
- [ ] 19. 複查 `AMOUNT_MISMATCH`（綠界已付款、金額不符）→ 仍記異常，`trade_amount` 為綠界金額
- [ ] 20. 作廢、複查、回呼異常的所有情境跑完後，`OutputCaptureExtension` 擷取的完整輸出不含測試 key／iv（**以 `@AfterEach` 每個情境斷言一次**，見 G01b PR #87 的審查）；WARN 行之後沒有 `\tat ` 開頭的行

**S3**

- [ ] 21. `GET /api/payments/anomalies`：HQ 看到兩家分店的異常；店長只看到本店；顧客 403；未登入 401；已結案的不出現；超過 200 筆時 `truncated=true`
- [ ] 22. `POST /api/payments/anomalies/{id}/resolve`：店長結案本店異常 → 200、`resolved_by` 為店長 id、備註正確；他店店長 → 403 且資料不變；顧客 → 403；不帶 CSRF → 403；`note` 空白或超過 200 字 → 400；不存在 → 404；重複結案 → 409
- [ ] 23. 端點的回應本文不含測試 key／iv
- [ ] 24. `GET /api/payments/config` 多了 `expire` 物件，既有 `enabled`、`environment` 不變
- [ ] 25. `ReconciliationView.vue`：有未結案異常時顯示「作廢後收到付款，需人工退款」區塊（訂單編號、訂單金額、綠界金額、來源、偵測時間），可填說明並結案；沒有異常時不顯示該區塊。原本「也不會自動取消訂單」的說明改為描述逾時作廢規則（DOM 測試釘住兩種狀態與結案呼叫的 URL、method、body）
- [ ] 26. `OrdersView.vue`：`ECPAY`＋`PENDING_PAYMENT` 訂單且 `config.expire.enabled` 時，提示文字含付款窗口與作廢門檻分鐘數（DOM 測試）；`expire.enabled=false` 時提示與今天相同
- [ ] 27. `AuditView.vue` 的動作篩選多一個 `ORDER_EXPIRED`「逾時作廢」選項
- [ ] 28. `application-ecpay-stage.yml` 含 §5.2 五個值；`EcpayStageProfileTest`（G01b）若需擴充，只**新增**斷言，不改既有斷言
- [ ] 29. `docs/ECPAY.md` 新增「逾時作廢與作廢後付款」一節（規則、設定、異常怎麼處理）與 §11 的步驟 7–10
- [ ] 30. 實作回報 `docs/reports/G04-online-payment-expiry.md`：各階段狀態、每條驗收對應的測試方法名、驗收 4 的既有測試是否全數未改、實作中發現的規格問題。**缺檔即退回**（同 G01b）

## 14. 設計決策

### 14.1 作廢前一定先查單；只有「經驗證的未付款或模擬付款」才作廢

**決定：** 作廢的唯一條件是 `perform()` 回 `STILL_UNPAID` 或 `SIMULATED`。查單失敗、驗簽失敗、金額不符一律保留，下一輪再試。
**理由：** PO 規則「作廢前先查單，不能作廢掉其實已付款的訂單」。「查不到」與「確認沒付」是兩件事，只有後者可以作廢。模擬付款沒有實際金流，作廢不會讓任何人損失錢，留著只會永遠掛在待付款清單。
**推翻的代價：** 若要讓「連續查單失敗 N 次」也作廢，改 `expireOne` 的 switch 一行，但必須同時確保複查會抓到作廢後付款（本規格已有）。

### 14.2 查單與作廢不在同一個交易；競態用「窗口間距 + 回呼異常 + 複查」收尾

**決定：** 先查單（不持鎖）→ 再開交易鎖列作廢。不嘗試讓兩者原子化。
**理由：** 查單是最長 10 秒的外部 HTTP，抱著訂單列鎖等它會讓同一筆的回呼卡住。更根本的是，**即使持鎖，綠界那邊仍可能在我們查完之後才收到顧客付款** —— 這個競態在綠界不提供「取消未付款交易」的前提下無法由我方消除。所以設計目標從「消除」改成「縮小 + 不沉默」：(a) 付款窗口與作廢門檻至少差 30 分鐘，作廢時顧客最後一次進付款頁已是 30 分鐘前；(b) 回呼若在作廢後到達，記異常；(c) 回呼送不到時，複查會抓到。
**推翻的代價：** 若日後綠界提供交易取消 API，可在 `expireOne` 作廢前呼叫它，複查與異常仍保留作為防線。

### 14.3 作廢後收到付款：記異常、不復活訂單

**決定：** 訂單維持 `CANCELLED`，人工到綠界後台退款，在對帳頁結案。
**理由：** 作廢時已回補 G08 的保留數量，復活訂單可能超賣（那些份數可能已被別人買走）；而且顧客 30 分鐘前就離開了付款頁，此時出餐不一定是顧客要的。退款是唯一不會讓帳與庫存出錯的處理。G03 延後，所以退款在系統外做，系統只負責「一定讓人看到」。
**推翻的代價：** 要復活的話需要「重新保留庫存，保留不到怎麼辦」的規則，並把 `CANCELLED → PAID` 加進狀態機，約一個施工階段；G03 進場時再評估。

### 14.4 顧客與店員仍不能手動取消線上付款訂單

**決定：** 維持 `transition()` 只允許取消 `CASH` 訂單。
**理由：** 手動取消一樣要「先查單再作廢」，而 `orders` 模組不能依賴 `payments`（`payments → orders` 已存在，反向會成環）。把它做成 `payments` 的端點可行，但會多一個顧客可觸發的連外查詢（濫用面，同 G01b §15.5）。逾時作廢已經給了出口；顧客要重下單不需要先取消舊單。
**推翻的代價：** 新增 `POST /api/payments/ecpay/{id}/cancel`（SELF、G20j 限流、內部走 `expireOne`），一個端點加一組越權測試，約半個施工階段。

### 14.5 付款窗口擋在 `EcpayService.checkout`，不擋在 `Orders.payable`

**決定：** 窗口檢查放 `payments`。
**理由：** 窗口長度是金流設定（`ecpay.expire.*`），`orders` 模組讀不到也不該讀。`payable()` 仍只負責「這筆訂單是不是本人的、是不是待付款的線上訂單」。
**推翻的代價：** 搬到 `payable()` 要把窗口長度當參數傳入，一行簽名變更。

### 14.6 不新增 `coffee-payments → coffee-audit` 依賴；異常表自己存快照

**決定：** 異常的結案紀錄存在 `payment_anomalies.resolved_by／resolution_note`，不寫 `audit_log`；`branch_id`／`order_total` 寫入時快照。
**理由：** 加依賴是模組邊界變更（`AGENTS.md`：要由 Claude 裁決），而這裡不需要：異常表本身就是完整的處理紀錄。存快照讓清單查詢不必 join `orders`（`orders` 表屬 `coffee-orders`），分店過濾能在 SQL 層完成。訂單作廢本身仍由 `coffee-orders` 寫 `ORDER_EXPIRED` 稽核（它本來就依賴 `coffee-audit`）。
**推翻的代價：** 若稽核頁要統一顯示異常結案，再加依賴與一個 `audit.record` 呼叫。

### 14.7 預設值：窗口 20 分鐘、門檻 60 分鐘、複查 30 分鐘（demo：10／40／10）

**決定：** 如 §5。
**理由：** 門檻 − 窗口 ≥ 30 分鐘是「顧客最後一次進付款頁後還能付款的時間」的保守上限，需由 §11 步驟 7 實機確認。複查 30 分鐘涵蓋綠界回呼重送的常見時間。demo 縮短到「一次 demo 內看得到」的長度，但仍保留 30 分鐘間距（§5.3 的檢查強制）。正式設定預設關閉，與對帳一致。
**推翻的代價：** 改 yml；若步驟 7 顯示付款頁可停留超過 30 分鐘，同時把 §5.3 的 `+ 30` 加大。

### 14.8 作廢前的查單記為 `SCHEDULED`，不新增 `trigger_source`

**決定：** 重用 `perform(null, id)`，`payment_reconciliations.trigger_source='SCHEDULED'`。
**理由：** `V2` 的 CHECK 是無名約束，跨 H2／PostgreSQL 改它要先查約束名，成本高於價值；「這筆查單是為了作廢」可由 `payment_expiries` 的存在推知。
**推翻的代價：** 一支 migration 重建 CHECK，加一個 `EXPIRY` 值。

### 14.9 作廢不退還優惠碼兌換次數

**決定：** 沿用 G07 §11.8。
**理由：** 與 `CASH` 取消的現行行為一致；退還要防重複，應與 G03 一起設計。
**推翻的代價：** 見 G07 §11.8。

### 14.10 一張訂單最多一筆「作廢後收到付款」異常

**決定：** `UNIQUE(order_id,kind)` + `insert … where not exists`。
**理由：** 回呼會重送、複查與回呼可能各抓到一次；店長只需要處理一次退款。極少數的併發（回呼與複查同一毫秒）撞到 UNIQUE 時：回呼那邊回 `0|Invalid` 讓綠界重送（重送時已存在，回 `1|OK`），複查那邊交易回滾、下一輪再查（已存在，只更新 `payment_expiries`）。兩邊都不會漏記也不會重記。
**推翻的代價：** 若要保留每一次偵測，改成 `payment_anomaly_events` 子表。

### 14.11 新增 `Orders.settleOnline`，而不是改 `confirmOnline`

**決定：** 回呼改用新方法；`confirmOnline` 不動。
**理由：** 回呼要在**同一把列鎖內**分辨「已作廢」。在 `confirmOnline` 外面 catch 它丟出的 `Problem` 不可行：它是加入外層交易的 `@Transactional` 方法，例外穿過代理就會把外層交易標成 rollback-only，`payment_events` 與異常都寫不進去。改 `confirmOnline` 的回傳型別則會動到 `ReconciliationService` 與既有測試。新增一個方法是純加法。
**推翻的代價：** 日後可讓 `perform()` 也改用 `settleOnline`，再刪掉 `confirmOnline`，一次小重構。

### 14.12 G01a 三項非阻斷觀察的處置

**決定：** 第 1、2 項**已不存在**，不需夾帶：`Orders.reconciliationCandidates()` 的註解與 `" and o.branch_id=?"` 都已由 G10（PR #26，`1e1fb6e`，2026-09-21）改掉。第 3 項（`pending()` 查詢次數沒有測試）本規格不動 `pending()`，**不夾帶**，留在 GAP-ANALYSIS。
**理由：** 夾帶的前提是「本來就要動同一處」。為了一條與作廢無關的測試多包一層計數 DataSource，會讓 S2 的審查混入無關變更。
**推翻的代價：** 下一份動到 `pending()` 的規格夾帶，或單獨一支小 PR。

## 15. 與其他工作的關係

- **G01b**：S2 依賴其 `FakeEcpayServer` 與 `ecpay-stage` profile（S3 追加設定）。G01b 尚未合併前只能做 S1
- **G08**：作廢呼叫既有 `Catalog.releaseStock`，不改它
- **G07**：作廢不退兌換次數（§14.9）
- **G03**：異常結案目前是「人工在綠界後台退款」，G03 進場時應把它接成系統退款（§14.3）
- **Flyway**：本規格佔用 `V15`

## 修訂紀錄

| 版本 | 日期 | 變更 |
| --- | --- | --- |
| v1.0 | 2026-10-12 | 初版。金流線第二份規格（PO 2026-10-11 決策） |
