# G03 — 訂單退款與發票作廢／折讓（只串 stage）

版本：v1.3.2（2026-10-18）
作者：Claude（PM / SA）
前置：**G02（[`G02-ecpay-einvoice.md`](G02-ecpay-einvoice.md) v1.3 以上）必須整份合併後才能開工**。本規格 §6.6 依賴 G02 v1.3 §6.5 的租約認領、在途界線 `inflight_until` 與 `uncertain` 不變式。理由：本規格的 `V17` 必須排在 G02 的 `V16` 之後（Flyway 預設不接受亂序）；S2 直接擴充 G02 的發票用戶端與假伺服器。
決策依據：PO 2026-10-14 決策「G02 電子發票、G03 退款與退單解除延後」，沿用 2026-10-11 決策的 stage 硬規則。

---

## 1. 背景與目標

已付款的訂單今天**沒有任何出口**：`OrderService.transition()`（`OrderService.java:630-656`）只允許取消「現金＋待付款」的訂單，`PAID` 之後只能往 `PREPARING → READY → COMPLETED` 走。做錯飲料、顧客臨時不要、重複結帳 —— 店裡只能在系統外處理，帳面上卻永遠是一筆營收；現金班別的應有金額也不會扣掉退給客人的錢，關帳時一定短少。G02 上線後還多一件事：已開立的發票沒有人作廢或折讓。

**目標：**

1. 店長與總部可以對已付款訂單**整筆退款**，必填原因，留下稽核
2. **現金退款**從當下開著的班別扣除，班別的應有金額正確
3. **線上付款退款**：綠界的信用卡退刷 API（`DoAction`）**在 stage 不能用**（官方文件：「測試環境：因無法提供實際授權，故無法使用此 API」）。所以本規格的線上退款是「**店長先到綠界廠商後台退刷，再到系統登記**」，系統負責把帳記對、把發票處理掉，不呼叫 `DoAction`（§14.1）
4. 退款後發票自動處理：已開立 → **作廢**（還在可作廢期間）或**全額折讓**（過了期間）；還沒開 → 不開
5. 報表顯示退款金額與筆數

## 2. 範圍

### 2.1 在範圍內

- 新權限 `ORDER_REFUND`（店長、總部）
- `POST /api/orders/{id}/refund`：整筆退款
- `orders` 表新增退款欄位；退款後訂單不能再改狀態
- 現金班別應有金額扣除現金退款
- 發票作廢／折讓（G02 的發票用戶端新增兩支 API、排程新增一個步驟）
- 報表新增退款金額與筆數
- 前端：訂單明細的退款按鈕與「已退款」標示、班別頁的退款金額、報表的退款列、對帳頁的發票作廢／折讓失敗區塊
- 本機假伺服器、stage 實機驗證步驟、文件

### 2.2 不在範圍內

- **呼叫綠界 `DoAction` 自動退刷** —— stage 不支援，理由見 §14.1
- **部分退款**（只退一杯）—— 理由見 §14.2
- **顧客自行申請退款** —— 退款一律由店內人員執行
- **退款回補 G08 每日可售數量** —— 已經做出來的飲料不會回到庫存（§14.5）
- **退款退還 G07 優惠碼兌換次數** —— 沿用 G07 §11.8 與 G04 §14.9「不退還」
- **G04「作廢後收到付款」異常** —— 那些訂單是 `CANCELLED`、沒有 `paid_at`，不是本規格的退款對象；維持 G04 的人工退刷 + 對帳頁結案流程
- **ATM、超商代碼等非信用卡付款的退款** —— 系統只支援信用卡（G01b `ChoosePayment=Credit`），不存在這類訂單
- 綠界正式環境、正式特店憑證

## 3. 涉及模組與邊界

| 模組 | 變更 | 邊界影響 |
| --- | --- | --- |
| `coffee-identity` | `Identity.PERMISSIONS` 新增 `ORDER_REFUND` | — |
| `coffee-orders` | `api/Orders.java`：新增 `RefundRequest`、`refund`、`RefundedCandidate`、`refundedCandidates`；`Order` 新增三個欄位；`InvoiceCandidate` 新增 `refundedAt`。`internal/OrderService.java`：`refund`、`transition`／`cash` 拒絕已退款訂單。`internal/OrderController.java`：新端點。`internal/CashSessionService.java`：應有金額扣除退款；`api/CashSessions.java` 的 `Session` 新增兩個欄位 | 無新依賴（已依賴 `coffee-audit`） |
| `coffee-payments` | `EcpayInvoiceClient` 新增 `invalid`、`allowance`；`InvoiceService` 新增退款掃描與作廢／折讓步驟；`Invoices` 新增 record 與方法；`InvoiceController` 新增端點 | 無新依賴。只透過 `Orders` 得知退款 |
| `coffee-reporting` | `Reports.MonthlyReport` 新增 `refundAmount`、`refundOrders`；`ReportService` 查詢 | 報表本來就可直接唯讀查 `orders`（`AGENTS.md` 資料存取例外），新欄位在 `orders` 表上，不擴大例外範圍 |
| `coffee-app` | `V17__order_refunds.sql`、`InitialData.java`（若 HQ 的權限是從 `Identity.PERMISSIONS` 全量種入，則不用改；若是逐項列舉，加一項）、測試、`FakeEinvoiceServer.java`（只新增） | — |
| `frontend` | `OrdersView.vue`、`CashSessionsView.vue`、`ReportsView.vue`、`ReconciliationView.vue`、`AuditView.vue`（一個選項）、`shared/types.ts`、`shared/format.ts`、DOM 測試 | 無 |
| 文件 | `docs/ECPAY.md`、`docs/reports/G03-order-refunds.md` | — |

- **退款端點放在 `coffee-orders`**：退款的本體是「訂單狀態 + 現金班別」，兩者都屬 `orders`。發票處理在 `payments`，由排程**透過 `Orders` 介面掃描**已退款訂單得知（與 G02 掃描已付款訂單同一個模式），`orders` 不需要知道發票的存在 —— 依賴方向維持 `payments → orders`
- **零新依賴**

## 4. DB schema —— `V17__order_refunds.sql`

**Flyway 版號 `V17`**：G02 佔 `V16`。開工前確認 `V16__einvoice.sql` 已在主線、`V17` 沒有人佔用。

```sql
-- 1) 訂單退款欄位（coffee-orders 擁有）。整筆退款，所以金額就是 total，不另存。
ALTER TABLE orders ADD COLUMN refunded_at BIGINT;
ALTER TABLE orders ADD COLUMN refunded_by VARCHAR(36) REFERENCES accounts(id);
ALTER TABLE orders ADD COLUMN refund_reason VARCHAR(200);
ALTER TABLE orders ADD COLUMN refund_provider_note VARCHAR(200);
ALTER TABLE orders ADD COLUMN refund_cash_session_id VARCHAR(36) REFERENCES cash_sessions(id);
ALTER TABLE orders ADD CONSTRAINT ck_orders_refund
  CHECK(refunded_at IS NULL OR (refunded_by IS NOT NULL AND refund_reason IS NOT NULL AND paid_at IS NOT NULL));
CREATE INDEX idx_orders_refunded ON orders(refunded_at);
CREATE INDEX idx_orders_branch_refunded ON orders(branch_id,refunded_at);
CREATE INDEX idx_orders_refund_session ON orders(refund_cash_session_id);

-- 2) 發票作廢／折讓（coffee-payments 擁有）。一張發票最多一筆。
CREATE TABLE invoice_reversals(
  order_id VARCHAR(20) PRIMARY KEY REFERENCES invoices(order_id),
  branch_id VARCHAR(36) NOT NULL REFERENCES branches(id),
  kind VARCHAR(10),
  status VARCHAR(10) NOT NULL,
  allowance_no VARCHAR(20),
  attempts INTEGER NOT NULL DEFAULT 0,
  uncertain BOOLEAN NOT NULL DEFAULT FALSE,
  claim_token VARCHAR(36),
  claimed_until BIGINT,
  inflight_until BIGINT,
  last_error VARCHAR(200),
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  done_at BIGINT,
  CONSTRAINT ck_invoice_reversals_attempts CHECK(attempts>=0),
  CONSTRAINT ck_invoice_reversals_claim CHECK((claim_token IS NULL AND claimed_until IS NULL) OR (claim_token IS NOT NULL AND claimed_until IS NOT NULL AND status='PENDING')),
  CONSTRAINT ck_invoice_reversals_kind CHECK(kind IS NULL OR kind IN ('VOID','ALLOWANCE','NONE')),
  CONSTRAINT ck_invoice_reversals_status CHECK(status IN ('PENDING','DONE','FAILED')),
  CONSTRAINT ck_invoice_reversals_done CHECK(status<>'DONE' OR kind IS NOT NULL),
  CONSTRAINT ck_invoice_reversals_inflight CHECK(inflight_until IS NULL OR status IN ('PENDING','FAILED'))
);
CREATE INDEX idx_invoice_reversals_status ON invoice_reversals(status,updated_at);
CREATE INDEX idx_invoice_reversals_branch ON invoice_reversals(branch_id,status,updated_at);

-- 3) 權限
INSERT INTO role_permissions(role_code,permission) VALUES ('MANAGER','ORDER_REFUND');
INSERT INTO role_permissions(role_code,permission) VALUES ('HQ','ORDER_REFUND');
```

- **不新增訂單狀態 `REFUNDED`**：`orders.status` 的 CHECK 是 `V1` 的無名約束，跨 H2／PostgreSQL 改不動（G04 §14.8 同一個理由）。「已退款」由 `refunded_at IS NOT NULL` 表示，`status` 保留退款當下的值（§14.3）
- `role_permissions` 的欄位名稱以 `V2__payment_reconciliation.sql` 授權 `PAYMENT_RECONCILE` 的那兩行為準，照抄它的寫法
- `invoice_reversals.kind` 在建立時可以是 NULL —— 「作廢還是折讓」在**執行時**才依當下日期決定（§6.6），不是在退款當下
- `kind='NONE'`（只會與 `status='DONE'` 一起出現）：退款當下開立結果不明而先建了作廢列，事後確認綠界**沒有**這張發票、本地已標 `SKIPPED`，不需要作廢或折讓（§6.6）
- **CHECK 一律具名，不得寫成欄位上的 inline 約束**：本支 migration 共七個 CHECK —— `orders` 的 `ck_orders_refund`，以及 `ck_invoice_reversals_attempts`、`ck_invoice_reversals_claim`、`ck_invoice_reversals_kind`、`ck_invoice_reversals_status`、`ck_invoice_reversals_done`、`ck_invoice_reversals_inflight`
- `claim_token`／`claimed_until`／`inflight_until`：與 G02 v1.3 `invoices` 的租約認領與在途界線同一套規則，用在 `reverseOne`（§6.6）
- `invoice_cursors` **不改表**，S2 由程式 insert `REFUNDED` 那一列（G02 §4 已預留）

## 5. 設定

不新增任何設定鍵。發票作廢／折讓沿用 G02 的 `ecpay.invoice.*`（同一個排程、同一組憑證、同一個 `batch-size`／`max-attempts`）。

## 6. 後端變更

### 6.1 `Orders` 介面 —— 只新增

```java
record RefundRequest(String reason, String providerNote) {}

// Order 尾端新增三個欄位；未退款為 null
//   ..., Long refundedAt, String refundedBy, String refundReason

// G02 的 InvoiceCandidate 尾端新增一個欄位；未退款為 null
record InvoiceCandidate(String orderId, String branchId, int total, long paidAt,
                        InvoiceRequest invoice, Long refundedAt) {}

record RefundedCandidate(String orderId, String branchId, long refundedAt, String invoiceEmail) {}

Order refund(Actor a, String id, RefundRequest req);

/** 依 (refunded_at, id) 遞增，語意同 G02 的 invoiceCandidates。 */
List<RefundedCandidate> refundedCandidates(long afterAt, String afterId, long until, int limit);
```

- `Order` 與 `InvoiceCandidate` 新增元件會改到所有建構呼叫點；**只允許補 `null`**（同 G02 §6.1 的處理）
- `refundedCandidates` 沒有 `Actor`，系統內部用，不得從 Controller 暴露

### 6.2 `OrderService.refund`

```text
a.require("ORDER_REFUND")
branchId = branchOf(id)            // 404 找不到訂單
a.branch(branchId)                 // 他店 403
lockBranch(branchId); lock(id)     // 與 cash() 同樣的鎖順序，避免和收現、開關班死結
o = snapshot(id)
```

| 條件 | 結果 |
| --- | --- |
| `reason` 空白或 trim 後超過 200 字 | 400 `請填寫 200 字以內的退款原因` |
| `o.refundedAt != null` | 409 `這筆訂單已經退款` |
| `o.paidAt == null`（待付款、已取消） | 409 `這筆訂單尚未付款，不能退款` |
| `ECPAY` 且 `providerNote` 空白或超過 200 字 | 400 `請先到綠界廠商後台完成退刷，並填寫退刷紀錄` |
| `CASH` | `providerNote` 忽略（存 NULL） |

通過後：

1. `CASH`：查本店 `status='OPEN'` 的班別 id（與 `cash()` 同一句 SQL），**可以為 NULL**（未開班時退款，計入「未歸班」，與收現的既有行為對稱）
2. `update orders set refunded_at=?,refunded_by=?,refund_reason=?,refund_provider_note=?,refund_cash_session_id=? where id=? and refunded_at is null` —— 更新 0 列 → 409 `這筆訂單已經退款`（行鎖之外的第二道防線）
3. `audit.record(a, "ORDER_REFUNDED", id, branchId, "整筆退款 <total> 元（<現金|線上付款>）：<reason>")`
4. 回傳 `snapshot(id)`

- **`status` 不改**（§14.3）。**不呼叫** `catalog.releaseStock`（§14.5）
- **已退款訂單**：`transition()` 與 `cash()` 在既有檢查之前加 `if (o.refundedAt() != null) throw new Problem(409, "這筆訂單已經退款");`。**不可用 `Problem.check`**：它固定拋 400（`coffee-shared` 的 `Problem.check(boolean,String)`），照用會讓驗收 7 失敗；也**不要**為此修改或新增 `Problem.check` 的 overload，既有 400 契約不動（v1.3.1）

### 6.3 端點（`OrderController`）

`POST /api/orders/{id}/refund`，body `{"reason": "...", "providerNote": "..."}`，回 200 `Order`。需要 CSRF。**請求中沒有任何金額欄位**；若前端送了 `amount` 之類的欄位，Jackson 忽略（`RefundRequest` 沒有那個元件）。

### 6.4 現金班別（`CashSessionService`）

- `cashRevenue(id)` 不變（收進來的錢）。新增 `cashRefunds(id)`：`select coalesce(sum(total),0),count(*) from orders where refund_cash_session_id=? and payment_method='CASH' and refunded_at is not null`
- **應有金額** = `opening_float + cashRevenue - cashRefunds`。`close()` 寫入 `expected_amount` 時用這個公式；開著的班別即時計算也用這個公式
- 未歸班（`unassignedCash`）同理：新增扣除 `refund_cash_session_id is null` 且 `refunded_at` 落在同一區間的現金退款
- `CashSessions.Session` 尾端新增 `int refundAmount, int refundOrders`
- **已關帳的班別不重算**：`expected_amount` 是關帳當下存的，之後的退款屬於退款當下開著的那個班別（`refund_cash_session_id`），不會回頭改已關的班
- 若班別頁目前有「應有金額」的說明文字，補一句「已扣除本班現金退款」

### 6.5 報表（`ReportService`）

- 新增一句查詢：`select coalesce(sum(o.total),0),count(*) from orders o where o.refunded_at>=? and o.refunded_at<?` + 既有的分店篩選
- `MonthlyReport` 尾端新增 `long refundAmount, long refundOrders`
- `CoffeeIntegrationTest.reportResponseKeepsExactContractKeys` 的期望 key 集合**只追加** `"refundAmount"`、`"refundOrders"` 兩個字串；同一方法的其他部分與該檔其餘測試一律不動（§14.10、驗收 11 的例外）
- **其餘所有既有欄位一律不變**（仍是依 `paid_at` 的毛額）。退款依**退款發生的月份**認列，不回頭改付款月份的數字（§14.4）
- 前端在營收旁顯示「退款 −N 元（M 筆）」與「扣除退款後 N 元」（前端減法只用於顯示，兩個數字都來自後端）

### 6.6 發票作廢／折讓（`coffee-payments`）

**用戶端新增（`EcpayInvoiceClient`）：**

```java
CallResult invalid(String invoiceNo, String invoiceDate, String reason);   // POST /B2CInvoice/Invalid
CallResult allowance(String invoiceNo, String invoiceDate, int amount, String email); // POST /B2CInvoice/Allowance
```

`Invalid` 的 `Data` 明文：`{"MerchantID","InvoiceNo","InvoiceDate":"yyyy-MM-dd"（取 invoices.invoice_date 的前 10 字元）,"Reason":"訂單退款"}`。

`Allowance` 的 `Data` 明文：`{"MerchantID","InvoiceNo","InvoiceDate":"yyyy-MM-dd","AllowanceNotify":"E","NotifyMail":<訂單發票 email>,"AllowanceAmount":amount,"Items":[{"ItemSeq":1,"ItemName":"餐飲費用","ItemCount":1,"ItemWord":"式","ItemPrice":amount,"ItemTaxType":"1","ItemAmount":amount}]}`。回應取 `IA_Allow_No`。

`InvoiceDate` 格式（日期 vs. 日期時間）**以 §11 步驟 3 的實機結果為準**；本規格依官方 SDK 指南寫 `yyyy-MM-dd`。回應判讀、逾時、不跟隨重新導向、log 規則全部與 G02 §6.4 相同。

**可作廢期限（純函式 `InvoiceDeadline.canVoid(String invoiceDate, Instant now)`）：**

- 發票日期（Asia/Taipei）落在雙月期 `[奇數月 1 日, 下一個偶數月底]`
- 該期的作廢期限是**期末次月 13 日 23:59:59**（例：1–2 月的發票 → 3 月 13 日；11–12 月 → 隔年 1 月 13 日）
- **系統只在期限前一天（12 日 23:59:59）之前作廢**，之後一律折讓。留一天緩衝，避免排程剛好跨過期限時送出注定失敗的作廢（§14.6）
- 附單元測試：每個雙月期的邊界（12 日 23:59:59 可作廢、13 日 00:00:00 不可）、跨年、閏年 2 月 29 日的發票

**排程（`InvoiceService` 的同一個 `@Scheduled` 方法）—— 一輪的順序改為：**

1. **退款掃描**（新）：讀 `invoice_cursors` 的 `REFUNDED` 列；不存在就 insert `(REFUNDED, T0 - lag-seconds*1000, '')` 並跳過。邊界語意與 G02 §6.5 步驟 1 的邊界表**完全相同**（把 `paid_at` 換成 `refunded_at`）：`refunded_at >= C` 的處理，`< C` 的永遠不處理。存在就 `orders.refundedCandidates(at, id, now - lag, 200)`，每一筆依該訂單 `invoices` 列的狀態：
   - **沒有 `invoices` 列** → insert 一列 `status='SKIPPED'`、`uncertain=false`、`amount` 取 `invoiceCandidate(orderId)` 的 `total`、`last_error='訂單已退款，不需開立'`（之後 G02 的入列 `where not exists` 自然跳過它）。沒有列就不可能被認領過，所以綠界一定沒有這張發票。若與另一個實例的 G02 入列撞主鍵，整個交易回滾、游標不推進，下一輪重來
   - **`SKIPPED`** → 不動
   - **`ISSUED`** → `ensureReversal(orderId)`
   - **`PENDING` 或 `FAILED`** → 先試 `update invoices set status='SKIPPED', last_error='訂單已退款，不需開立', updated_at=? where order_id=? and status in ('PENDING','FAILED') and uncertain=false and claim_token is null and inflight_until is null`
     - 更新 1 列 → 完成。依 G02 v1.3 §6.5 的不變式，這一列沒有持有者、沒有還可能在綠界成立的請求，綠界也沒有這張發票
     - 更新 0 列（`uncertain=true`、有持有者、在途界線未清，或剛好被別人改了）→ 在同一個交易重讀該列：`ISSUED`、或 `PENDING`／`FAILED` 且（`uncertain=true`、`claim_token` 有值或 `inflight_until` 有值）→ `ensureReversal(orderId)`；`SKIPPED` → 不動。**不得**把這些列改成 `SKIPPED` —— 綠界可能已經開了，或稍後才成立
   - 游標推進與上述寫入在同一個交易

   `ensureReversal(orderId)`：`insert into invoice_reversals(order_id, branch_id, kind, status, …) values (?, ?, NULL, 'PENDING', …) where not exists`。已存在時：若為 `DONE`／`kind='NONE'`（之前判定綠界沒有這張發票，但 G02 §6.5 第 7 步（v1.3 編號）的遲到開立回應推翻了這個判定）→ `update … set status='PENDING', kind=NULL, uncertain=false, attempts=0, done_at=null, last_error=null where order_id=? and status='DONE' and kind='NONE'`（`NONE` 的列從未送出過作廢／折讓，`inflight_until` 依 `ck_invoice_reversals_inflight` 必為 NULL）；其餘狀態不動。
2. **入列**（G02 步驟 1，不變）
3. **開立**（G02 步驟 2）—— `issueOne` 第 1 步重讀 `invoiceCandidate` 之後，依序加入三條退款規則：
   - (a) `invoice == null` 且 `refundedAt != null` → 條件更新為 `SKIPPED`、`uncertain=false`（條件同 G02 v1.3 §6.5 第 1 步：`status='PENDING' and claim_token is null and inflight_until is null`；沒有發票資訊不可能開過），結束；`invoice == null` 而未退款 → 照 G02 標 `FAILED`
   - (b) `refundedAt != null` → 先試 `update … set status='SKIPPED' … where order_id=? and status='PENDING' and uncertain=false and claim_token is null and inflight_until is null`（v1.3：不再依據認領前讀到的 `uncertain`，直接以條件更新判定），**不呼叫綠界**；更新 0 列 → 重讀後依 (c) 處理（不得強行 `SKIPPED`）；更新 1 列時，若該訂單有 `kind IS NULL` 的 `PENDING` 作廢列，改為 `DONE`／`kind='NONE'`，結束
   - (c) (b) 更新 0 列而重讀後仍為 `PENDING` → **照 G02 v1.3 §6.5 第 2–7 步把開立結果確定下來**（排他認領並重讀；`uncertain` 先 `GetIssue`；查無且界線已過才 `Issue`；送出前寫界線；`10000009` 轉查詢；一律以 token 寫回），只有兩處不同：結果「拒絕」時持有者寫回 `SKIPPED`、`uncertain=false`、`inflight_until=null`（不是 `FAILED`），並把作廢列改為 `DONE`／`NONE`；寫成 `ISSUED` 時呼叫 `ensureReversal`。認領失敗（別人持有中）→ 結束，不呼叫綠界。理由見 §14.9
   - 另外，**`issueOne` 任何一條寫成 `ISSUED` 的路徑**（不論訂單當時是否已退款），寫回成功後都再呼叫一次 `orders.invoiceCandidate(orderId)`；`refundedAt != null` → `ensureReversal`。這一條接住「退款在開立的 HTTP 呼叫進行中提交、而退款掃描已經走過這筆」的情況
4. **作廢／折讓**（新）：取 `invoice_reversals r join invoices i on i.order_id=r.order_id where r.status='PENDING' and i.status='ISSUED'`，依 `r.updated_at` 最舊 `batch-size` 筆，逐筆 `reverseOne(orderId)`。**發票還沒確定（`PENDING`／`FAILED`）的作廢列不取**：它們等發票確定後再處理，而且不會因為永遠是最舊的而把批次塞滿（兩張表都是 `coffee-payments` 擁有，可以 join）
5. INFO 摘要加上 `作廢 <n>、折讓 <n>、作廢折讓失敗 <n>`

**開立與退款的競態 —— 每一種交錯都有人接住：**

| 情況 | 誰接住 | 結果 |
| --- | --- | --- |
| 退款時還沒入列 | 退款掃描：沒有 `invoices` 列 → `SKIPPED` | 不開立 |
| 退款時 `PENDING`、`uncertain=false`（沒人在呼叫） | 退款掃描或 `issueOne` (b) 的條件更新 | `SKIPPED`，不開立 |
| 退款時 `Issue` 在途（已認領，`uncertain=true`） | 退款掃描建作廢列；開立寫回 `ISSUED` | 開立完成後作廢 |
| 上次 `Issue` 逾時（`SUCCEED_THEN_TIMEOUT`，`uncertain=true`）後才退款 | 退款掃描建作廢列；下一輪 `issueOne` (c) 查到 → `ISSUED` | 作廢那張，`Issue` 不重送 |
| `uncertain=true`、查詢查無 | `issueOne` (c) 認領並 `Issue`（`RelateNumber` 唯一，遲到的舊請求只會被拒） | 開出唯一一張後作廢 |
| 退款提交得太晚，退款掃描的游標已經走過這筆（正常情況會被 `lag` 擋住，這是第二道防線） | `issueOne` 寫回 `ISSUED` 後的重讀 → `ensureReversal` | 作廢 |
| 兩個 worker 同時處理同一筆，A 的 `Issue` 被拒、B 的請求在途（Codex review 5526548535 的交錯） | G02 排他認領（v1.2 起）：B 認領失敗、**根本不送出請求**；A 是唯一持有者，寫回 `FAILED`／`uncertain=false` 時不會有別的在途請求 | 退款掃描 `SKIPPED`，綠界確實沒有發票 |
| A 的 `Issue` 逾時（`Unknown`）並釋放認領，請求仍在綠界處理中；B 立刻接手（Codex review 5529132842 的交錯） | G02 v1.3 在途界線：A 送出前已提交 `inflight_until`，`Unknown` 不清除它。B 在界線前不取這一列、也不能從「查無」下結論；退款掃描因 `uncertain=true`／`inflight_until` 有值只建作廢列 | 界線過後查到 → `ISSUED` → 作廢；查無 → B 才開立 → 作廢 |
| 持有者當掉，請求可能已在綠界成立 | 該列留在 `uncertain=true`、有 `claim_token`：退款掃描不能 `SKIPPED`，只建作廢列；租約到期後 `issueOne` (c) 接手、先查詢 | 查到 → `ISSUED` → 作廢；確定沒開 → `SKIPPED`／`NONE` |
| 時間假設失效：本地已 `SKIPPED`／`NONE`，舊持有者才收到「開立」回應 | G02 §6.5 第 7 步（v1.3 編號）補償把發票列拉回 `PENDING`／`uncertain=true`；下一輪 (c) 查到 → `ISSUED` → `ensureReversal` 把 `DONE`／`NONE` 重設為 `PENDING` | 作廢 |
| 發票 `FAILED` 且 `uncertain=true`（例：連續 10 次不明）後退款 | 退款掃描建作廢列；作廢列等待。發票照常出現在 G02「發票開立失敗」清單，店長重試後走 (c) | 確定後作廢或 `NONE` |

**不變式：本地發票列一旦是 `SKIPPED`，綠界就一定沒有這張發票（在 G02 §14.14 的 `Timestamp` 假設下；假設失效而舊持有者仍收到回應時，由補償拉回）；綠界有發票的訂單一旦退款，一定有一筆未標 `NONE` 的作廢列。**

**`reverseOne(orderId)`（v1.3 改寫）：** 與 G02 v1.3 `issueOne` 同一套「租約認領 + 認領後重讀 + 持久在途界線」。作廢與折讓都會改變綠界上的發票狀態，所以和 `Issue` 一樣，送出前先寫 `invoice_reversals.inflight_until`，界線過後才能從查詢結果下結論。v1.2 在 `Unknown` 後釋放認領、接手者立刻重送，會出現「A 的 `Invalid` 在途、B 判定未作廢而再送 `Invalid` 被拒、改走折讓又被拒 → `FAILED`」這類假失敗（Codex review 5529132842 要求一併檢查的交錯）；綠界本身不會作廢兩次或折讓超過餘額，所以那不是帳務錯誤，但會讓店長收到一筆不必處理的失敗。

批次條件（§6.6 排程第 4 步）加上 `and (r.inflight_until is null or r.inflight_until <= now)`。

1. **認領並重讀**：`update invoice_reversals set claim_token=?, claimed_until=?(now + claim-lease-ms), updated_at=? where order_id=? and status='PENDING' and (claim_token is null or claimed_until < ?(now))`，0 列就結束；1 列 → 以 token 重讀 `kind, attempts, uncertain, inflight_until` 與 `invoices` 的 `invoice_no, invoice_date, amount, status`（記為 `snap`）。`snap` 的發票不是 `ISSUED` → 以 token 釋放（只清持有者），結束。認領**不**設 `uncertain`
2. 若 `snap.uncertain`：記下 `qAt = now`，`getIssueByRelateNumber(orderId)`
   - `Ok` 且 `IIS_Invalid_Status == "1"` → 結果 `DONE`／`VOID`
   - `Ok` 且 `IIS_Remain_Allowance_Amt == 0` → 結果 `DONE`／`ALLOWANCE`（`allowance_no` 維持 NULL，查詢不回折讓號）
   - `Ok` 但兩者皆否，且 `snap.inflightUntil != null && qAt < snap.inflightUntil` → 結果「等待」（之前送出的作廢／折讓仍可能成立；批次已排除這種列，這是防線）
   - `Ok` 但兩者皆否，且界線已過 → 繼續第 3 步（不寫回中間狀態）
   - `Rejected` → 結果 `FAILED`、`last_error='查不到這張發票，請人工確認'`
   - `Unknown` → 結果「不明」
3. 決定方式：`snap.kind` 已有值就沿用；否則 `canVoid(invoice_date, now)` ? `VOID` : `ALLOWANCE`
4. **寫入在途界線，然後送出**：`ts = now / 1000`；`update invoice_reversals set kind=?, uncertain=true, inflight_until=?(ts*1000 + STALE_REQUEST_WINDOW_MS), updated_at=? where order_id=? and claim_token=?(token)`（**不清除**持有者），0 列 → 結束、不送出。然後以同一個 `ts` 呼叫 `invalid` 或 `allowance`（金額 = `invoices.amount`、email 取 `RefundedCandidate.invoiceEmail`；email 為 NULL 時改 `AllowanceNotify="N"`、不帶 `NotifyMail`）。用戶端兩個方法比照 G02 v1.3 改為 `invalid(…, long timestampSec)`、`allowance(…, long timestampSec)`
   - `Ok` → 結果 `DONE`（折讓存 `allowance_no`）
   - `Rejected` 且 `kind='VOID'` → **自動改走折讓一次**：重做第 4 步（`kind='ALLOWANCE'`、新的 `ts` 與界線），呼叫 `allowance`。作廢被拒是確定的回應，而第 2 步已保證沒有更早的請求在途，所以改走折讓不會與舊的作廢請求重疊。折讓再被拒 → 結果 `FAILED`
   - `Rejected` 且 `kind='ALLOWANCE'` → 結果 `FAILED`、`last_error='綠界拒絕折讓（<RtnCode>）：<RtnMsg>'`
   - `Unknown` → 結果「不明」
5. **持有者寫回**一律 `where order_id=? and claim_token=?(token)` 並清除 `claim_token`／`claimed_until`：
   - `DONE` → `status='DONE'`、`done_at=now`、`uncertain=false`、`inflight_until=null`
   - `FAILED`（被拒、查不到）→ `status='FAILED'`、`uncertain=false`、`inflight_until=null`
   - 「不明」→ `PENDING`、`uncertain=true`、`attempts+1`、`inflight_until` 不動；`attempts+1 >= max-attempts` → `FAILED`，保留 `uncertain=true` 與 `inflight_until`
   - 「等待」→ `PENDING`、`attempts` 不加、`inflight_until` 不動、`last_error='等待前次作廢／折讓請求失效'`
6. **失去認領後的回應**一律丟棄：現任持有者看得到第 4 步已提交的界線，界線過後的查詢會反映舊請求的結果（作廢或折讓若成立，`IIS_Invalid_Status`／`IIS_Remain_Allowance_Amt` 會顯示）

**為什麼折讓重試前先查 `IIS_Remain_Allowance_Amt`：** 折讓請求可能已成功只是回應沒回來。重送一次全額折讓會被綠界以「超過可折讓餘額」拒絕（不會折兩次），但那會被誤判成 `FAILED`。先查就知道其實已完成；界線則保證「先查」看到的是所有舊請求的最終結果。

### 6.7 `Invoices` 介面與端點 —— 新增

```java
record Reversal(String orderId, String branchId, String invoiceNo, int amount, String kind,
                String status, String allowanceNo, int attempts, String lastError, long updatedAt) {}
record ReversalPage(List<Reversal> items, boolean truncated) {}

ReversalPage failedReversals(Actor a, String branchId);
Reversal retryReversal(Actor a, String orderId);
```

- `GET /api/payments/invoices/reversals/failed?branchId=`、`POST /api/payments/invoices/order/{orderId}/reversal/retry`
- 權限、資料範圍、200 筆上限、404／409 訊息與 G02 §6.6 的 `failed`／`retry` **完全相同**（409 訊息為 `這筆作廢／折讓目前不需要重試`）。重試設 `PENDING`、`attempts=0`、`uncertain=true`、`claim_token=null`、`claimed_until=null`、`last_error=null`，**`kind` 與 `inflight_until` 保留**（同 G02 v1.3 §6.6）
- G02 的 `Invoice` record 尾端新增 `String reversalKind, String reversalStatus`（無作廢列為 null），讓訂單明細可以顯示「發票已作廢」／「已折讓」

## 7. 前端

- **`OrdersView.vue`**
  - 訂單列與明細：`refundedAt != null` 時狀態徽章顯示「已退款」（取代原狀態文字），明細另列退款時間、原因、執行人
  - 明細的「退款」按鈕：只在 `can('ORDER_REFUND')`、已付款、未退款時顯示。按下開對話框：原因（必填）；線上付款訂單另有「綠界退刷紀錄」欄（必填）與一行提示「請先到綠界廠商後台完成退刷，再回來登記。系統不會自動退刷」；現金訂單提示「請從錢櫃退還 N 元給顧客」。送出成功後刷新明細
  - 已退款訂單不顯示任何狀態推進按鈕
  - 發票區：`reversalStatus=DONE` → 「發票已作廢」或「發票已折讓（折讓單號 …）」；`reversalKind=NONE` 時不顯示作廢資訊（發票狀態本身就是「未開立」）；`PENDING` → 「發票作廢處理中」；`FAILED` 對店員顯示原因
- **`CashSessionsView.vue`**：班別列與明細新增「現金退款 −N 元（M 筆）」；應有金額的說明加「已扣除現金退款」
- **`ReportsView.vue`**：營收卡片下方新增「退款 −N 元（M 筆）」與「扣除退款後 N 元」；`refundOrders == 0` 時整行不顯示
- **`ReconciliationView.vue`**：新增「發票作廢／折讓失敗」區塊（訂單編號、發票號碼、方式、原因、重試）
- **`AuditView.vue`**：動作篩選新增 `ORDER_REFUNDED`「退款」
- `shared/types.ts`、`shared/format.ts`（「已退款」標籤）
- 判斷「是否顯示退款按鈕」寫成純函式（例：`canRefund(order, permissions)`）放在 `modules/ordering/` 並附 `.spec.ts`

## 8. 權限與資料範圍

| 動作 | 顧客 | 收銀 | 店長 | 總部 |
| --- | --- | --- | --- | --- |
| 退款 | 403 | 403（沒有 `ORDER_REFUND`） | 本店 | 全部 |
| 作廢／折讓失敗清單、重試 | 403 | 403 | 本店 | 全部 |

- **收銀不能退款**（§14.7）
- 他店店長退款 → 403，**且訂單、班別、稽核完全不變**（測試要斷言三者）
- `Identity.PERMISSIONS` 加 `ORDER_REFUND`；若有「角色可擁有的權限」驗證規則或測試（例如 HQ 必須擁有全部權限），一併更新

## 9. 金額規則

- 退款金額 = 訂單 `total`，**由後端讀取**；請求本文沒有金額欄位
- 現金班別、報表的退款數字都是 `sum(total)` 的後端查詢
- 折讓金額 = `invoices.amount`（G02 入列時的快照，即訂單 `total`）
- 前端唯一的算術是報表的「扣除退款後」顯示（`revenue - refundAmount`），兩個運算元都是後端數字

## 10. 本機測試設計

### 10.1 `FakeEinvoiceServer` —— 只新增

- 新增路徑 `/B2CInvoice/Invalid`、`/B2CInvoice/Allowance`，情境列舉**只新增值、不改 G02 既有值的行為**；G02 §10.1 的掛勾機制同樣適用於兩條新路徑
- 假伺服器記住每張發票的作廢狀態與剩餘可折讓金額，`GetIssue` 的 `IIS_Invalid_Status`、`IIS_Remain_Allowance_Amt` 依此回應
- 新增情境：

| 情境 | `Invalid` | `Allowance` |
| --- | --- | --- |
| `OK`（沿用） | 標記已作廢 | 扣除剩餘可折讓金額，回 `IA_Allow_No`（`AL` + 遞增）；超過餘額 → `RtnCode` 非 1 |
| `VOID_PAST_DEADLINE` | `RtnCode=5070450`、`RtnMsg="已超過作廢期限"` | 同 `OK` |
| `SUCCEED_THEN_TIMEOUT`（沿用名稱、擴充到兩條新路徑） | 先標記再逾時 | 先扣除再逾時 |
| `REJECTED`（沿用） | 拒絕 | 拒絕 |
| `DELAYED_EFFECT`（沿用 G02 v1.3 的名稱、擴充到兩條新路徑） | 不標記，逾時；`applyDelayed()` 時才標記已作廢 | 不扣除，逾時；`applyDelayed()` 時才扣除 |
| `TIMEOUT`、`HTTP_500`、`BAD_CIPHER`（沿用） | 同 G02 | 同 G02 |

（`5070450` 只是假伺服器的代碼，實作不得依賴特定代碼判斷「過期」—— §6.6 第 4 步對所有 `Rejected` 的作廢都改走折讓。）

### 10.2 測試檔

- `OrderRefundTest.java`（Spring context、真實 HTTP + CSRF）：§13 S1 全部
- `InvoiceDeadlineTest.java`（純單元）：§6.6 的邊界
- `InvoiceReversalEndToEndTest.java`：§13 S2 全部，比照 G02 的 `EinvoiceEndToEndTest` 設定方式；**不得使用 `@ActiveProfiles("ecpay-stage")`**
- 時間：`canVoid` 吃參數 `now`，不呼叫無參數 `now()`（G27 `TimeZoneGuardTest` 會擋）。端到端測試要模擬「過期」時，直接把 `invoices.invoice_date` 寫成兩個月前，不要改系統時鐘

## 11. stage 實機驗證步驟（追加到 `docs/ECPAY.md`，列入「待 PO 驗收」，不擋合併）

前提同 G02 §11。

1. **現金退款**：開班 → 現金訂單收現 → 等發票開出 → 店長退款。預期：訂單「已退款」、班別「現金退款 −N」、應有金額扣除、1–2 分鐘後發票顯示「已作廢」，stage 廠商後台該發票為作廢狀態
2. **線上退款**：stage 信用卡付款一筆 → 等發票開出 → 到 **綠界 stage 金流廠商後台** 嘗試退刷，記錄 stage 後台是否允許退刷（`DoAction` 不能用，但後台人工退刷在 stage 是否可行未知）→ 不論結果都在系統登記退款。預期：訂單「已退款」、發票作廢
3. **`InvoiceDate` 格式**：步驟 1 的作廢若被拒且訊息與日期格式有關，改用完整日期時間重試（curl），記錄哪一種被接受 —— 結果回饋到 §6.6
4. **折讓**：stage 無法等到真的過期，以 curl 依 §6.6 格式對一張已開立發票直接呼叫 `Allowance`，記錄回應欄位名稱（`IA_Allow_No`、`IA_Remain_Allowance_Amt`）與 email 通知是否寄出
5. **開立前退款**：建立訂單、收現、**立刻**（`lag-seconds` 內）退款。預期：永遠不會開出發票，`invoices` 為 `SKIPPED`
6. **報表**：當月報表出現退款列，數字與步驟 1、2 相符

## 12. 施工階段

三個階段都是加法：S1 的新欄位全為 NULL 時行為與今天相同；S2 只在發票排程開啟時有作用；S3 是顯示層。

### S1 — 退款本體（`orders`、`identity`、現金班別）

**動到：** `V17__order_refunds.sql`、`Identity.java`、（必要時）`InitialData.java`、`Orders.java`、`OrderService.java`、`OrderController.java`、`CashSessionService.java`、`CashSessions.java`、所有 `Order`／`InvoiceCandidate`／`Session` 建構呼叫點（只補 `null`／`0`）、新增 `OrderRefundTest.java`、`CashSessionsTest.java`（**只新增**案例）。

**驗收子集：** 1–11。

### S2 — 發票作廢／折讓

**動到：** `EcpayInvoiceClient.java`（新增兩個方法）、新增 `InvoiceDeadline.java`、`InvoiceService.java`（退款掃描、`issueOne` 的退款檢查、`reverseOne`）、`FakeEinvoiceServer.java`（只新增）、新增 `InvoiceDeadlineTest.java`、`InvoiceReversalEndToEndTest.java`。

**驗收子集：** 12–23（含 21a–21l）。

### S3 — 端點、報表、前端、文件

**動到：** `Invoices.java`、`InvoiceController.java`、`Reports.java`、`ReportService.java`、`OrdersView.vue`、`CashSessionsView.vue`、`ReportsView.vue`、`ReconciliationView.vue`、`AuditView.vue`、`shared/types.ts`、`shared/format.ts`、新增 `canRefund` 純函式與 `.spec.ts`、DOM 測試、`InvoiceApiTest.java`（**只新增**案例）、報表的既有測試（**只新增**案例）、`docs/ECPAY.md`、`docs/reports/G03-order-refunds.md`。

**驗收子集：** 24–33。

**規模估計：** S2 最重（與 G02 S2 同級）。若一次做不完，切點在 `InvoiceDeadline` + 用戶端兩個新方法（驗收 12–14，無人呼叫，可獨立合併）與排程（15–23）之間。排程這一段（退款掃描、`issueOne` 的三條規則、`reverseOne`）**不可再切**：任何一條單獨上線都會留下 §6.6 競態表裡沒人接住的一格，要預留完整一次執行。

## 13. 驗收條件

**S1**

- [ ] 1. `V17__order_refunds.sql` 依 §4 建立欄位、表、五個 `CREATE INDEX`、七個以 §4 名稱具名的 CHECK（**SQL 裡沒有任何 inline 的 `CHECK(...)`**）與兩筆授權；`V1`–`V16` 一字未改
- [ ] 2. 店長退本店現金訂單（`PAID`、`PREPARING`、`READY`、`COMPLETED` 各一條）→ 200、`refundedAt` 有值、`status` 不變、`audit_log` 一列 `ORDER_REFUNDED`
- [ ] 3. 店長退本店線上訂單：有 `providerNote` → 200；沒有 → 400 且訊息逐字相符；現金訂單帶 `providerNote` → 存 NULL
- [ ] 4. 總部退任一分店 → 200；他店店長 → 403、**訂單、班別、稽核皆不變**；收銀 → 403；顧客 → 403；未登入 → 401；不帶 CSRF → 403；不存在 → 404
- [ ] 5. 已退款再退 → 409；待付款 → 409；已取消（含 G04 逾時作廢）→ 409；原因空白／201 字 → 400
- [ ] 6. 請求本文帶 `"amount": 1` 等多餘欄位 → 照常以 `total` 退款（金額不受影響）
- [ ] 7. 已退款訂單呼叫狀態推進 → 409；呼叫收現 → 409
- [ ] 8. 併發：同一訂單兩個退款請求同時送出 → 恰好一個 200、一個 409，稽核一列（以兩條執行緒 + `CountDownLatch` 驗證）
- [ ] 9. 現金班別：開班 → 收現 100 → 退款 → 班別即時應有金額 = 開班金 + 0，`refundAmount=100`、`refundOrders=1`；關帳 → `expected_amount` 同上
- [ ] 10. 跨班退款：A 班收現、A 班關帳、B 班開班後退款 → A 班 `expected_amount` 不變、B 班應有金額扣 100；未開班時退款 → 計入未歸班並扣除
- [ ] 11. G08 的可售數量**不回補**、G07 的兌換次數**不退還**（各一條斷言）；`CashSessionsTest`、`CoffeeIntegrationTest`、`HttpWorkflowTest` 等既有測試**不修改**即通過。**唯一例外**：`CoffeeIntegrationTest.reportResponseKeepsExactContractKeys` 的期望 key 集合追加 `refundAmount`、`refundOrders` 兩個字串（§14.10），不得改動該方法的其他部分、不得改用 `contains`／`containsAll` 等較寬鬆的斷言

**S2**

- [ ] 12. `InvoiceDeadlineTest`：六個雙月期各自的 12 日 23:59:59（可作廢）與 13 日 00:00:00（不可）、11–12 月跨年、閏年 2/29
- [ ] 13. `invalid`／`allowance` 送出的明文經假伺服器解密後欄位與 §6.6 一致；`allowance` 的三個金額都等於 `invoices.amount`；email 為 NULL 時 `AllowanceNotify="N"` 且無 `NotifyMail`
- [ ] 14. 兩個新方法對 `TIMEOUT`、`HTTP_500`、`BAD_CIPHER`、重新導向 → `Unknown`
- [ ] 15. 第一次執行只建立 `REFUNDED` 游標（`at_ms == T0 - lag`、`order_id == ''`）；以 SQL 把四筆已退款訂單的 `refunded_at` 設成 `C - 1`、`C`、`T0 - 1000`、`T0 + 1000`（`C` 取自游標）→ 只有 `C - 1` 那筆永遠不處理（比照 G02 驗收 12）
- [ ] 16. 已開立 → 退款 → 一輪 → `invoice_reversals` `DONE`／`VOID`；假伺服器該發票為已作廢
- [ ] 17. 已開立、`invoice_date` 改成兩個月前（過期）→ 退款 → 一輪 → `DONE`／`ALLOWANCE`、`allowance_no` 有值、**沒有呼叫 `Invalid`**
- [ ] 18. `VOID_PAST_DEADLINE`：未過期但綠界拒絕作廢 → 同一輪改走折讓 → `DONE`／`ALLOWANCE`
- [ ] 19. `SUCCEED_THEN_TIMEOUT`（作廢與折讓各一）：第一輪 `PENDING`／`uncertain`；第二輪先 `GetIssue` → `DONE`，**`Invalid`／`Allowance` 各只被呼叫一次**
- [ ] 20. 開立前退款：付款後在 `lag` 內退款 → 跑兩輪 → `invoices` 為 `SKIPPED`，**假伺服器從未收到 `Issue`**；`PENDING`／`uncertain=false` 與 `FAILED`／`uncertain=false`（`REJECTED` 造成）的發票列遇到退款 → `SKIPPED`，**假伺服器沒有收到任何新請求**
- [ ] 21. 開立呼叫進行中訂單被退款：假伺服器 `Issue` 掛勾內提交退款，並在另一條執行緒跑完退款掃描 → 掃描結束時 `invoices` **不是** `SKIPPED`、作廢列已建立 → 開立寫回 `ISSUED` → 下一輪 `DONE`／`VOID`；`Issue` 只被呼叫一次
- [ ] 21a. 同 21，但掛勾內只提交退款、**不**跑退款掃描，然後把 `REFUNDED` 游標推過這筆訂單（模擬掃描已走過）→ 開立寫回後的重讀建立作廢列 → 下一輪 `DONE`
- [ ] 21b. **成功後逾時再退款**：`Issue` 為 `SUCCEED_THEN_TIMEOUT` → 第一輪 `PENDING`／`uncertain=true` → 退款 → 退款掃描後 `invoices` 仍為 `PENDING`、作廢列 `PENDING` → 下一輪 `GetIssue` 查到 → `ISSUED` → `DONE`／`VOID`；**`Issue` 只被呼叫一次、`Invalid` 只被呼叫一次，假伺服器上該發票為已作廢**
- [ ] 21c. `uncertain=true` 且綠界查無（`Issue` 為 `TIMEOUT`、不記住）→ 退款 → 下一輪 `GetIssue` 查無 → 認領並 `Issue`（改為 `OK`）→ `ISSUED` → `DONE`／`VOID`；假伺服器上**恰好一張**發票且已作廢
- [ ] 21d. `uncertain=true` 的退款訂單，`Issue` 被 `REJECTED` → `invoices` 為 `SKIPPED`、作廢列 `DONE`／`NONE`，沒有呼叫 `Invalid`／`Allowance`
- [ ] 21e. 不餓死：`batch-size`（測試設 2）+ 1 筆「發票 `FAILED`／`uncertain=true` 的退款訂單」的作廢列，加上一筆發票 `ISSUED` 的作廢列（`updated_at` 最新）→ 一輪後後者 `DONE`，前者全部仍為 `PENDING` 且沒有呼叫綠界
- [ ] 21f. **Codex review 5526548535 的交錯**（`CountDownLatch`）：一筆 `PENDING`／`uncertain=false`／無持有者的發票。(1) 執行緒 B 先讀該列並記下（`PENDING`／`false`），停在 latch 前；(2) 執行緒 A 執行 `issueOne`，在 `Issue` 掛勾內等待（A 已認領）；(3) 放行 B，B 以讀到的舊狀態呼叫 G02 的 `tryClaim` → 回 empty，再呼叫完整的 `issueOne` → **不送出任何請求**；(4) 放行 A 回 `REJECTED` → 發票 `FAILED`／`uncertain=false`／無持有者；(5) 提交退款、跑退款掃描 → `SKIPPED`。**最終斷言**：假伺服器上該訂單沒有任何發票、`Issue` 只被呼叫一次，**不存在「本地 `SKIPPED`、綠界有效發票、且無作廢列」**
- [ ] 21g. **持有者當掉後退款**：以 SQL 把發票設為 `PENDING`／`uncertain=true`／`claim_token='dead'`／`claimed_until=now+60000`，假伺服器預先記住該張發票（模擬請求已成立）→ 退款 → 退款掃描：發票**不是** `SKIPPED`、作廢列 `PENDING`；`issueOne` 不送出請求；改 `claimed_until=now-1` → 下一輪 `GetIssue` 查到 → `ISSUED` → 作廢 → `DONE`／`VOID`，`Issue` 從未被呼叫
- [ ] 21h. **遲到開立回應推翻 `NONE`**：一筆已退款訂單的發票為 `SKIPPED`、作廢列 `DONE`／`NONE`，模擬 G02 §6.5 第 7 步（v1.3 編號）的補償（以 `OK` 情境、`Issue` 掛勾內把列改成 `SKIPPED` 的方式觸發）→ 發票回到 `PENDING`／`uncertain=true` → 下一輪 `ISSUED` → 作廢列重設為 `PENDING`／`kind=NULL` → `DONE`／`VOID`；假伺服器上該發票已作廢
- [ ] 21j. **`Unknown` 後早釋放 + 退款**（Codex review 5529132842 第一個交錯的端到端版）：`Issue` 設 `DELAYED_EFFECT`。(1) A 跑 `issueOne` → `Unknown`；(2) 提交退款、跑退款掃描 → 發票**不是** `SKIPPED`、作廢列 `PENDING`；(3) B 立刻跑一輪 → `Issue` 總數仍為 1；(4) `applyDelayed()`；(5) 以 SQL 把 `invoices.inflight_until` 改成 `now-1`，跑兩輪 → `ISSUED` → `DONE`／`VOID`。**最終斷言：不存在「本地 `SKIPPED`／`NONE`、綠界有效發票」**；假伺服器上恰好一張發票且已作廢。變體：(4) 改 `dropDelayed()` → B 開立 → 作廢，同樣恰好一張且已作廢
- [ ] 21k. **作廢延後成立**：`Invalid` 設 `DELAYED_EFFECT`。A 跑 `reverseOne` → `Unknown` → 作廢列 `PENDING`／`uncertain=true`／`inflight_until` 有值、`kind='VOID'`；B 立刻跑一輪 → `Invalid`／`Allowance` 總數不變；`applyDelayed()`；把 `inflight_until` 改成 `now-1` 跑一輪 → `GetIssue` 顯示已作廢 → `DONE`／`VOID`，`Allowance` 從未被呼叫
- [ ] 21l. **折讓延後成立**：同 21k，但 `invoice_date` 設成兩個月前（走折讓）、`Allowance` 設 `DELAYED_EFFECT` → 最終 `DONE`／`ALLOWANCE`，假伺服器剩餘可折讓金額為 0、`Allowance` 總數 1
- [ ] 21i. **作廢排他**：作廢列在 `Invalid` 掛勾內等待時，另一個 worker 對同一筆呼叫 `reverseOne` → 不送出任何請求；放行後 `DONE`／`VOID`，`Invalid` 只被呼叫一次、`Allowance` 從未被呼叫
- [ ] 22. `REJECTED`（折讓也被拒）→ `FAILED`；`attempts` 達上限 → `FAILED`、`uncertain=true`
- [ ] 23. log 衛生同 G02 驗收 22（含 email）；`TimeZoneGuardTest`、`EcpayNoEgressGuardTest` 不修改即通過

**S3**

- [ ] 24. 作廢／折讓失敗清單與重試：權限矩陣同 §8，訊息逐字相符；重試保留 `kind`、下一輪先查詢
- [ ] 25. `GET /api/payments/invoices/order/{id}` 回傳 `reversalKind`／`reversalStatus`
- [ ] 26. 報表：當月有一筆退款 → `refundAmount`、`refundOrders` 正確；**其餘所有既有欄位與退款前完全相同**（同一份資料退款前後各取一次報表比對）；付款在 9 月、退款在 10 月 → 9 月報表不變、10 月 `refundAmount` 有值；店長只看到本店退款
- [ ] 27. `canRefund` 的 `.spec.ts`：無權限、未付款、已退款、已取消各回 false，其餘回 true
- [ ] 28. `OrdersView.vue` DOM 測試：已退款顯示「已退款」與退款資訊、無狀態推進按鈕；退款對話框現金與線上兩種文案、線上未填退刷紀錄不能送出；送出的 URL／method／body 正確且**body 沒有金額**
- [ ] 29. `CashSessionsView.vue`、`ReportsView.vue` DOM 測試：退款列顯示正確；無退款時報表不顯示該行
- [ ] 30. `ReconciliationView.vue` DOM 測試：作廢／折讓失敗區塊與重試
- [ ] 31. `AuditView.vue` 有 `ORDER_REFUNDED` 選項
- [ ] 32. `docs/ECPAY.md` 新增「退款與發票作廢／折讓」一節：**明寫系統不會自動退刷，線上退款必須先到綠界後台**；附 §11 步驟與驗收紀錄表
- [ ] 33. 實作回報 `docs/reports/G03-order-refunds.md`：各階段狀態、每條驗收對應的測試方法名、驗收 11 的既有測試是否全數未改、實作中發現的規格問題。**缺檔即退回**

## 14. 設計決策

### 14.1 線上退款不呼叫 `DoAction`，改為「後台退刷 + 系統登記」

**決定：** 系統不呼叫綠界信用卡退刷 API。店長在綠界廠商後台退刷，回系統填退刷紀錄登記。
**理由：** 官方文件明寫 `DoAction` 在測試環境無法使用（「因無法提供實際授權」），而 PO 規定只能串 stage、CI 不得連外。寫一段**在任何允許的環境都無法實機驗證**的退刷程式，風險是「正式上線那天第一次真正執行」—— 退錯金額或重複退刷都是實際的錢。G04 已經用同樣的「人工後台退刷 + 系統記錄」處理作廢後付款，店長的操作習慣一致。
**推翻的代價：** 正式環境上線那一輪新增 G03a：`DoAction` 用戶端（`R` 退刷；未關帳時 `E` + `N`）、依 `QueryTradeInfo` 判斷關帳狀態、退刷不明結果的查詢補救，約一個施工階段；`providerNote` 改為選填（自動退刷時由系統填入）。本規格的資料模型與發票處理不需要改。

### 14.2 只做整筆退款

**決定：** 不支援部分退款。
**理由：** 部分退款要決定「退哪幾項、折扣怎麼攤回去」（G07 整單折扣、G20 品項折扣），發票要部分折讓並帶品項明細，班別與報表要存退款金額而不是用 `total`。咖啡廳最常見的情況（做錯、整單不要、重複結帳）都是整筆。整筆退款讓金額永遠是後端的 `total`，前端沒有任何金額可以動手腳。
**推翻的代價：** 新增 `order_refunds` 明細表（多筆、各自金額）、報表與班別改加總那張表、折讓帶明細，約兩個施工階段。`orders.refunded_at` 可保留為「已全額退款」的旗標。

### 14.3 不新增 `REFUNDED` 狀態，用 `refunded_at` 表示

**決定：** `status` 保留退款當下的值，「已退款」看 `refunded_at`。
**理由：** `V1` 的 status CHECK 是無名約束，H2 與 PostgreSQL 的自動命名不同，改它的 migration 要分資料庫處理（G04 §14.8 同一個問題）。而且保留原狀態有資訊價值：「做好了才退」與「還沒做就退」對營運是不同的事。所有需要排除已退款訂單的地方（狀態推進、收現）都已在 §6.2 明確處理。
**推翻的代價：** 一支針對兩種資料庫的 migration 重建 CHECK，`transition` 加一條轉換，前端改用狀態判斷。

### 14.4 報表依退款月份認列，不回頭改付款月份

**決定：** 既有欄位不變，新增 `refundAmount`／`refundOrders`。
**理由：** 已經看過、可能已經據以發獎金或對帳的月報，不應該因為下個月的一筆退款而改變。這也與現金班別「已關帳不重算」一致。品項、分類、毛利等細項維持毛額，因為整筆退款下要扣回細項就得逐項重算，而那正是部分退款才需要的複雜度。
**推翻的代價：** 改成「依付款月份扣除」是每句查詢加 `and refunded_at is null`，但過去月份的數字會變動，要先由 PO 確認可以接受。

### 14.5 退款不回補每日可售數量

**決定：** 不呼叫 `releaseStock`。
**理由：** 已付款的訂單通常已經在做或做好了，退款不代表那份東西回到可售狀態。回補會讓限量商品被超賣。
**推翻的代價：** 依 `status == PAID`（還沒開始做）決定是否回補，一個條件加一個呼叫。

### 14.6 作廢期限留一天緩衝，作廢被拒一律改走折讓

**決定：** 12 日 23:59:59 前作廢，之後折讓；作廢被綠界拒絕時同一輪改折讓。
**理由：** 排程每分鐘到五分鐘一輪，查單與呼叫之間可能跨過 13 日午夜；作廢被拒的原因不一定能從 `RtnCode` 準確判斷（實機才知道代碼）。對 B2C 全額退款而言，作廢與全額折讓的帳面效果相同，折讓是永遠可走的路，所以「拒了就折讓」比「判斷代碼」穩。
**推翻的代價：** 若實機發現某些拒絕不該改折讓（例如發票號碼錯），把第 4 步的條件縮為特定 `RtnCode`。

### 14.7 收銀不能退款

**決定：** `ORDER_REFUND` 只給店長與總部。
**理由：** 退款直接動到錢櫃與營收，是最常見的內部舞弊手法（收現後退款、錢進口袋）。收銀能收款但不能退款，是零售業的基本職責分離。
**推翻的代價：** 一支 migration 授權給 `CASHIER`。

### 14.8 發票處理由 `payments` 排程掃描，不由退款請求同步觸發

**決定：** 退款端點只改訂單；發票作廢在下一輪排程。
**理由：** `orders` 不能依賴 `payments`（會成環）；而且退款請求不應該因為綠界的延遲或失敗而失敗 —— 錢已經退給客人了，帳一定要先記上。用游標掃描與 G02 同一個模式，實作端不需要學第二套機制。
**推翻的代價：** 改成 Spring 事件（`orders` 發布、`payments` 監聽）可以縮短延遲，但仍需要掃描作為漏接的補救，等於兩套。

### 14.9 退款不終結結果不明的發票：先確定開立結果，再作廢

**決定：** 只有 `uncertain=false` 的發票列可以因退款直接標 `SKIPPED`；`uncertain=true` 的一律照 G02 的流程把結果確定下來（查詢，查無就以同一個 `RelateNumber` 開立），開出來就作廢，確定沒開才標 `SKIPPED`／`NONE`。
**理由：** v1.0 把所有 `PENDING`／`FAILED` 直接改成 `SKIPPED`，Codex 在 PR #92 review 5522929018 指出兩個漏洞：(1) 上次 `Issue` 在綠界成功但回應逾時的發票被標 `SKIPPED` 後，沒有人再查它、也沒有人作廢，綠界留下一張有效發票；(2) 多實例下退款掃描在 HTTP 進行中改 `SKIPPED`，開立的寫回更新 0 列，同樣的結果。「查無就直接 `SKIPPED`」也不夠：遲到的舊 `Issue` 請求仍可能在查詢之後才在綠界落地。以同一個 `RelateNumber` 開立是唯一不怕遲到請求的做法——綠界保證同一個 `RelateNumber` 只有一張，遲到的那個只會被拒。代價是罕見路徑（退款時開立結果不明）會多開一張再作廢一張，作廢在稅務上是正常流程。
**推翻的代價：** 要避免「開了再作廢」，得假設遲到的 `Issue` 有時間上限（例如認領後超過 `2 × timeout-ms` 查無就視為不會再落地），把 (c) 的「查無就開立」改成「查無且已過安靜期就 `SKIPPED`」。這個假設無法在 stage 驗證，所以本輪不採用。

**v1.2 補充：** v1.1 依賴的「`uncertain=false` 代表沒有在途請求」在 v1.1 的認領下並不成立（認領不排他，Codex review 5526548535）。v1.2 改為依賴 G02 §14.13 的租約認領與 `Timestamp` 時間界線，退款掃描的 `SKIPPED` 條件因此多了 `claim_token is null`。注意 §14.13 用的是「綠界拒收 10 分鐘前的請求」這條**綠界自己執行、stage 可實測**的規則（G02 §11 步驟 8），與上面被否決的「我們自訂的安靜期」不同。

**v1.3 補充：** v1.2 的時間界線只涵蓋「等舊租約到期才接手」，`Unknown` 後立即釋放認領的路徑不在其中（Codex review 5529132842）。v1.3 依賴 G02 §14.14 的持久在途界線 `inflight_until`：退款掃描的 `SKIPPED` 條件多了 `inflight_until is null`，`reverseOne` 也改用同一套界線。界線的長度仍來自綠界的 `Timestamp` 規則，不是自訂的安靜期。

### 14.10 報表精確 key 契約測試只追加兩個欄位名

**決定：** v1.3.1 以前的 §6.5（新增兩欄）與驗收 11（`CoffeeIntegrationTest` 不修改）互相矛盾 —— `reportResponseKeepsExactContractKeys` 用 `containsExactlyInAnyOrder` 鎖死月報的 key 集合，任何新欄位都會讓它失敗（Codex 在 PR #97 head `693fb56` 以 CI 證明）。決定：該方法的期望集合追加 `refundAmount`、`refundOrders`，斷言方式維持 `containsExactlyInAnyOrder`，其餘不動。
**理由：** 那支測試的用途是攔「**非預期**的欄位增減」；本規格的新增是預期內、寫在 §6.5 的，正是這種測試應該同步更新的情境。保留精確比對，往後別人誤加或誤刪欄位仍會被攔下。其他選項都更差：無退款時不輸出這兩欄會讓前端要處理欄位缺席、JSON 形狀隨資料變動；把欄位移到別的端點則多一支 API 與一次前端請求，只為了繞過一支測試。驗收 11 的原意（既有行為不變）由驗收 26「其餘所有既有欄位與退款前完全相同」保證，不依賴這支測試不改。
**推翻的代價：** 改成另開端點：新增一支報表 API、權限與分店篩選測試、`ReportsView.vue` 多一次請求，並把這兩個字串從期望集合移除。

## 15. 與其他工作的關係

- **G02**：硬前置。S2 擴充 `EcpayInvoiceClient`、`InvoiceService`、`FakeEinvoiceServer`，使用 `invoices.status='SKIPPED'`、`invoice_cursors` 的第二列
- **G04**：「作廢後收到付款」異常不屬於本規格；那些訂單 `CANCELLED`、無 `paid_at`，`refund` 會回 409
- **G07／G08**：退款不退兌換次數、不回補可售數量（§14.5、§2.2）
- **G27**：`InvoiceDeadline.canVoid` 吃 `now` 參數

## 修訂紀錄

| 版本 | 日期 | 變更 |
| --- | --- | --- |
| v1.3.2 | 2026-10-18 | 回應 Codex PR #97（S3 blocked，head `14cf58e`）：§6.5 新增兩欄與驗收 11「`CoffeeIntegrationTest` 不修改」互相矛盾。新增 §14.10：`reportResponseKeepsExactContractKeys` 的期望集合只追加兩個欄位名，斷言方式不變；§6.5、驗收 11 同步註明例外。頁首版本一併更正（v1.3.1 時未更新） |
| v1.3.1 | 2026-10-15 | 回應 Codex review 5533221990：§6.2「已退款訂單」改為明確 `throw new Problem(409, …)`，`Problem.check` 固定回 400，照原寫法驗收 7 必然失敗；`Problem.check` 的 400 契約不動 |
| v1.3 | 2026-10-15 | 回應 Codex review 5529132842（配合 G02 v1.3 的在途界線）：前置改為 G02 v1.3；§4 `invoice_reversals` 新增 `inflight_until` 與 `ck_invoice_reversals_inflight`；§6.6 退款掃描與 (b) 的 `SKIPPED` 條件加上 `inflight_until is null`，(b) 不再依據認領前讀到的 `uncertain`，(c) 改依 G02 v1.3 第 2–7 步，競態表新增一列，`reverseOne` 改寫為認領後重讀、送出前寫界線、新增「等待」；§6.7 重試保留界線；§10.1 新增 `DELAYED_EFFECT`；§14.9 補充；驗收 1 修訂、新增 21j–21l |
| v1.2 | 2026-10-15 | 回應 Codex review 5526548535（配合 G02 v1.2 的排他認領）：§4 `invoice_reversals` 新增 `claim_token`／`claimed_until` 與 `ck_invoice_reversals_claim`；§6.6 退款掃描與 (b) 的 `SKIPPED` 條件加上 `claim_token is null`、(c) 改用 G02 v1.2 的租約認領、`ensureReversal` 可把 `DONE`／`NONE` 重設、競態表新增三列、`reverseOne` 改用租約認領與 token 寫回；§6.7 重試清除持有者；§14.9 補充；驗收 1 修訂、新增 21f–21i |
| v1.1 | 2026-10-15 | 回應 Codex review 5522929018：(1) §6.6 退款掃描不再把 `uncertain=true` 的發票改 `SKIPPED`，改為建作廢列、由 `issueOne` 確定結果，新增競態表、`kind='NONE'`、作廢批次只取已開立的發票、§14.9，驗收 20–21 改寫並新增 21a–21e；(2) `REFUNDED` 游標邊界與 G02 v1.1 統一，驗收 15 改寫；(3) §4 的 CHECK 全部具名 |
| v1.0 | 2026-10-14 | 初版。PO 2026-10-14 解除 G03 延後，沿用 2026-10-11 stage 硬規則；`DoAction` 不支援 stage，線上退款改為後台退刷 + 系統登記 |
