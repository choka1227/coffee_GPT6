# G01a — 對帳實作的缺陷修正

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G01a（G01 的後續修正） |
| 優先順序 | P1 —— 目前無生產影響，但進入綠界串接階段前必須完成 |
| 規格版本 | v1 |
| 撰寫 | Claude（PM / SA），2026-09-17 |
| 實作 | Codex（PG / SD） |
| 基準 commit | `7b09f4b` |
| 前置 | `specs/G01-payment-reconciliation.md`（已實作，PR #9 已合併） |

---

## 1. 背景

PR #9 實作了 G01 並於 2026-09-17 08:32 合併。Claude 在同一個 head SHA 送出過 `REQUEST_CHANGES`，PO 決定先合併，因此下列問題留在 `feature/init-project` 上。

**目前沒有生產影響** —— `ecpay.reconcile.enabled` 預設為 `false`，且 PO 已決定金流先跑 stub、最後才串接綠界。但第 2 節那一項在第一次真實查單就會踩到，所以必須在串接階段之前修掉。

本規格只修缺陷，**不擴大 G01 的功能範圍**。

---

## 2. 必修一：查無此筆交易被誤判成 `QUERY_FAILED`

**位置**：`backend/coffee-payments/src/main/java/com/coffee/payments/internal/ReconciliationService.java:184-192`

目前的順序是：

```java
status = p.get("TradeStatus");
if (status == null || !status.matches("[0-9]{1,20}")) throw new IllegalArgumentException();
amount = Integer.valueOf(p.get("TradeAmt"));          // ← TradeAmt 為空就丟例外
trade = p.getOrDefault("TradeNo", "");
if (!trade.matches("[A-Za-z0-9]{1,64}")) throw new IllegalArgumentException();  // ← 空字串不符合
if (!"1".equals(status)) { outcome = "STILL_UNPAID"; ... }
```

`TradeAmt` 與 `TradeNo` 在判斷 `TradeStatus` **之前**就被強制驗證，兩者的例外都會被第 216 行的 `catch (RuntimeException)` 收成 `QUERY_FAILED`。

### 為什麼是錯的

違反 G01 規格兩處：

- §6：「`MerchantTradeNo` 在綠界查不到時…照未付款處理即可，**不是錯誤**」
- §4：`provider_trade_no` —— 綠界的 `TradeNo`；**無值時填空字串**（規格已經預期它會是空的）

### 營運後果

顧客建立線上付款訂單後，在表單送出到綠界之前就放棄（關瀏覽器、退回上一頁），綠界從來沒看過這筆 `MerchantTradeNo`，查單回應的 `TradeNo` 是空字串。這類訂單正是 `PENDING_PAYMENT` 的大宗，也正是待查核清單的主要內容。

目前的行為會讓整張清單顯示「綠界查單驗證或連線失敗，訂單未變更」—— 營運人員看到一片假告警，**真正的綠界連線異常會被淹沒在裡面**，而這支功能存在的目的就是要讓人分辨得出來。

### 修法

把 `TradeAmt` / `TradeNo` 的解析與驗證移到 `"1".equals(status)` 成立之後：

- 非已付款時：`trade` 允許空字串、`amount` 允許 `null`（`trade_amount` 欄位本來就 nullable）
- 只有要比對金額並入帳時，`TradeAmt` 才必須是合法 int，解析失敗才是 `QUERY_FAILED`（G01 §10 的規則不變）
- `TradeNo` 非空時仍要驗格式；空字串直接接受並寫入空字串

### 驗收

- [ ] `TradeStatus=0`、`TradeNo=""`、`TradeAmt=""` → `STILL_UNPAID`，訂單維持 `PENDING_PAYMENT`
- [ ] `TradeStatus=0`、`TradeNo=""`、`TradeAmt="0"` → `STILL_UNPAID`
- [ ] `TradeStatus=1`、`TradeAmt` 非數字 → 仍然是 `QUERY_FAILED`，不入帳
- [ ] `TradeStatus=1`、`TradeNo` 格式不合法 → 仍然是 `QUERY_FAILED`，不入帳
- [ ] 簽章驗證失敗、HTTP 失敗、timeout → 仍然是 `QUERY_FAILED`（既有行為不變）
- [ ] `payment_reconciliations.provider_trade_no` 在 `STILL_UNPAID` 時寫入空字串

`ReconciliationTest.failuresNeverCreditOrders` 現有的 `"unpaid"` 案例沿用了 `response()` 的完整欄位，所以蓋不到這條路徑 —— 請新增案例，不要只改既有的。

---

## 3. 必修二：CSRF 驗收測試無效

**位置**：`backend/coffee-app/src/test/java/com/coffee/app/ReconciliationTest.java:233-235`

```java
mvc.perform(post("/api/payments/reconciliation/" + o.id()).session(session))
    .andExpect(status().isForbidden());
mvc.perform(post("/api/payments/reconciliation/" + o.id()).session(session).with(csrf()))
    .andExpect(status().isForbidden());
```

`o` 是 **banqiao** 的訂單，登入的是 **taipei** 的 manager。兩次請求都因為跨店而回 403，所以第一個斷言**即使把 CSRF 保護整個關掉也照樣通過**。它證明的是分店隔離，不是 G01 §11 的「寫入端點需要 CSRF token」。

### 修法

把兩件事拆開驗：

- **CSRF**：用該 manager **有權限**的 taipei 訂單。無 `csrf()` → 403；有 `csrf()` → 200（或依訂單狀態 409）。只有這樣，關掉 CSRF 保護時第一個斷言才會失敗
- **跨店隔離**：保留在 banqiao 訂單上，帶 `csrf()` 打，期望 403

### 驗收

- [ ] CSRF 斷言用的是該帳號有權限的訂單，有／無 token 的結果不同
- [ ] 跨店斷言仍在，且帶 `csrf()`
- [ ] 把 `SecurityConfiguration` 的 CSRF 暫時關掉時，CSRF 那條測試會紅（實作時自行驗證一次，不要留在程式碼裡）

---

## 4. 一併修正的三項（原 review 的非阻斷建議）

### 4.1 `PaymentDate` 時鐘偏移會讓已付款訂單反覆查核失敗

**位置**：`ReconciliationService.java:210`

```java
if (paidAt <= 0 || paidAt > now) throw new IllegalArgumentException();
```

丟的是 `IllegalArgumentException`，不是 `DateTimeParseException`，所以**不會**落到下一行的 fallback，而是被外層收成 `QUERY_FAILED`。

綠界 `PaymentDate` 是綠界機房的台北牆鐘時間，只要相對本機有正向偏移，一筆**確實已付款**的訂單就會反覆 `QUERY_FAILED`，永遠補不進來。G01 §7 對時間有問題的處置是「退回使用目前時間，並在 `detail` 註明」。

**修法**：把 `paidAt > now` 與 `paidAt <= 0` 併進既有的 fallback 分支（`paidAt = now`，`detail` 註明），不要當成查單失敗。

- [ ] `PaymentDate` 為未來時間 → `CONFIRMED`，以查核時間入帳，`detail` 註明
- [ ] `PaymentDate` 缺漏 → 既有 fallback 行為不變

### 4.2 `pending()` 無上限撈取與 N+1

**位置**：`ReconciliationService.java:91-101`

以 200 筆一頁無上限地撈完全部候選，且每筆再打兩次 DB（`attempts()` 取 50 筆 + `count(*)`）。積壓一多，這支 GET 就是 O(n) 次查詢。

**修法**：加總筆數上限（**200 筆**，超過的部分不回傳，並在回應中以 `truncated: true` 標示），並把 last outcome 與 attempts 次數改成一次 `group by order_id` 取得。

- [ ] `pending()` 最多回傳 200 筆
- [ ] 每次呼叫的 DB 查詢次數與候選筆數無關（固定次數）
- [ ] 超過上限時 `truncated` 為 `true`，未超過時為 `false`

> 這是 G01 規格 §8.1 沒寫上限造成的遺漏，不是實作的錯。上限與 `truncated` 欄位是本規格新增的決定。

### 4.3 報表的台灣日期歸屬沒有真正驗到

G01 §12 要求「該訂單隨後出現在對應台灣日期的報表營業額中（驗證第 7 節的時區問題確實修掉）」。`HttpWorkflowTest` 那段的 stub 沒有 `PaymentDate`，走的是 fallback＝現在時間，所以 580 元的斷言不能證明跨日／跨月歸屬。

**修法**：在 `ReconciliationTest` 補一條：`PaymentDate` 指定為台北時間的特定日，斷言報表把這筆歸到那一天而不是查核當天。

- [ ] 指定 `PaymentDate` 為某個明確的台北日期後，報表的 `daily` 該日營收包含這筆

---

## 5. 範圍限制

- **不改 `EcpayService.callback()`**，既有回呼行為完全不動
- **不擴大 G01 的功能範圍**：不做退款、不做逾時作廢（G04）、不加通知管道
- **不新增 migration**。本規格不動 schema
- **不新增權限**
- 第 4.2 節的 `truncated` 欄位是唯一的 API 回應變更，前端 `ReconciliationView.vue` 要顯示「僅顯示前 200 筆」的提示

---

## 6. 設計決策（PM / SA 定案）

G01 原規格第 13 節的六項待決事項，依 PR #9 的實作結果定案如下，**不再是待決事項**：

1. **排程間隔與靜默期** —— 定案為 5 分鐘 / 15 分鐘（實作採用的預設值）。真實流量進來後再依實際放棄率調整
2. **`max-age-days`** —— 定案為 7 天。進入綠界串接階段時要對照綠界查單 API 的實際資料保留期限複查
3. **告警管道** —— 定案為不做。`AMOUNT_MISMATCH` 先躺在待查核清單裡，等 G11 稽核建立後一起設計告警
4. **`PAYMENT_RECONCILE` 的角色** —— 定案為 HQ 與 MANAGER（實作已如此）
5. **對帳紀錄保留期限** —— 定案為不清理。資料量成為問題時併入 G09 的資料生命週期處理
6. **綠界 stage 查單是否可用** —— 仍未驗證。這是唯一真正的未知，**不是設計決策，是待驗證的風險**，記在下方

---

## 7. 待驗證的風險（不是待決事項）

**綠界 stage 環境的查單 API 尚未經真實端到端驗證。** 目前全部行為只有 stub 覆蓋，`QueryTradeInfo` 也沒有官方專屬測試向量（實作重用了既有 `CheckMac` 的向量）。

這不是靠寫規格能解決的，必須在進入綠界串接階段時安排一次真實環境驗證。在那之前 `ecpay.reconcile.enabled` 維持 `false`。

---

## 8. 給 Codex 的提醒

- **不需要等確認**（見 `AGENTS.md`「設計決策的歸屬」）。輸出設計摘要留紀錄，然後直接開工
- 這支跟 G06 動的是不同檔案（`coffee-payments` vs `coffee-orders` / `coffee-catalog`），**可以與 G06 並行**，但請用獨立分支 `codex/g01-fixes`，不要跟 G06 混在同一個 PR
- 第 2 節是本規格的重點。修完請確認 `STILL_UNPAID` 與 `QUERY_FAILED` 在各種綠界回應下的分界是對的 —— 這兩者的分界就是「營運人員能不能相信這張清單」
- 既有測試 `CoffeeIntegrationTest` / `HttpWorkflowTest` / `ModuleBoundariesTest` / `CheckMacTest` 必須全數通過
- 實作回報寫到 `docs/reports/`
