# G20j — 共用限流設施與試算端點限流

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G20j |
| 版本 | v1.1（2026-10-08，同日依 Codex 在 [PR #78](https://github.com/choka1227/coffee_GPT6/pull/78) 的 `REQUEST_CHANGES` 把開工前提與並行注意同步為「G20h 已合併」） |
| 登記來源 | [`G20h-order-preview.md`](G20h-order-preview.md) §2.2、§14 |
| 分支 | `codex/g20j-rate-limit` |
| Flyway | **零 migration**（`V15` 仍然空著，本規格一個 SQL 檔都不加） |
| 新依賴 | **零**（`ConcurrentHashMap` 夠用，不引入 Bucket4j／Resilience4j／Redis） |
| 施工階段 | **三階段**（S1 設施／S2 接上試算端點／S3 把登入的那份收斂進來） |
| 開工前提 | **已滿足，S1–S3 都可以立刻開工**。[PR #77](https://github.com/choka1227/coffee_GPT6/pull/77)（G20h）已於 2026-10-08 合併進主線（`0d8f5dd`），`OrderService.preview` 已存在（`OrderService.java:232`） |

---

## 1. 背景與目標

### 1.1 G20h 開了一個沒有門閂的入口

G20h 的 `POST /api/orders/preview` 把「算這一籃多少錢」變成一個**不寫入、不消耗、不佔庫存**的端點。那三個「不」是它的優點，也正是它的問題：

**它沒有任何自然的成本上限。** 建立訂單有 `Idempotency-Key`、有 `reserveStock` 的庫存、有 `(account_id, idempotency_key)` 的唯一鍵 —— 打一百次只會成立一張單。試算什麼都不留，所以打一百次就是一百次完整的計價：`catalog.sellable` 一次、`resolveOptions` 每個品項一次（每個選項一次 `select`，見 `CatalogService.java:497`）、`promotions.apply` 一次、`discounts.quote` 一次。**一個購物車 50 個品項、每項 20 個選項，一次試算就是上千次 query。**

G20h §2.2 把限流切出去時的理由是「debounce 已把呼叫量壓到可接受」。那句話只對**走前端的人**成立。`preview.ts` 的 300ms debounce 是**前端自律**，拿 `curl` 直接打端點的人不受任何約束 —— 他需要的只是一組有效的 session cookie 與 CSRF token，而那是任何顧客帳號都有的。

### 1.2 第二個問題：它是一台優惠碼探測機

試算會把優惠碼的驗證結果**原封不動**回給呼叫端（G20h §6.3）：

| 回應 | 意思 |
| --- | --- |
| 404 `優惠碼不存在或已失效` | 這個碼不存在、停用、過期或不適用本店 |
| 400 `訂單金額未達此優惠碼的最低消費` | **這個碼存在而且現在有效**，只是這一籃太便宜 |
| 409 `此優惠碼的使用次數已達上限` | **這個碼存在**，額度用完了 |
| 200 `codeDiscountAmount > 0` | **這個碼可以用，而且折這麼多** |

G20h 的 §13.2 刻意讓 `quote` **不消耗** `redeemed_count`，這對正常使用是對的（§5.1 那個 bug），但副作用是**探測變成零成本**：以前要知道一個碼能不能用，得真的建一張訂單（會留下 `orders` 一列、會消耗一次額度、會被稽核看到）；現在可以無痕試到底。

碼空間是 `[A-Z0-9-]{4,20}`（`DiscountService.java:148`），窮舉不現實。但**行銷用的碼從來不是隨機的** —— `WELCOME10`、`SUMMER2026`、`VIP-100`、店名加年份。一份幾千筆的常見字典，在沒有限流的端點上幾分鐘就跑完，而且**系統裡一列記錄都不會留下**。

### 1.3 現在的限流長什麼樣子 —— 兩份各寫一次，沒有一份有測試

G20h §14 登記 G20j 時寫「本 repo 目前沒有任何限流基礎設施」。**這句話要修正：有兩份，但都是就地手寫的，而且形狀完全不同。**

| 位置 | 做法 | 鍵 | 額度 | 狀態存哪 | 有測試嗎 |
| --- | --- | --- | --- | --- | --- |
| `AuthController.java:22,51-61` | 固定視窗計數，成功登入後清掉該鍵 | `req.getRemoteAddr()`（IP） | 20 次 / 15 分鐘，另有 `size() > 10000` 的總量保險 | 程序內 `ConcurrentHashMap` | **沒有。** 全專案找不到任何斷言 `登入嘗試過多` 或 429 登入的測試 |
| `ReconciliationService.java:188-197` | 查 `payment_reconciliations` 的 `max(queried_at)` 比對 60 秒 | `order_id` | 1 次 / 60 秒 | **資料庫**（既有表） | 有（`ReconciliationTest.java:301`） |

這兩份不只是重複，是**兩套不同的語意**：一個程序內、重啟就歸零；一個在資料庫、重啟仍然有效。第三個端點要限流時，如果再手寫第三份，下次問「這個系統的限流規則是什麼」就沒有人答得出來。

所以本規格做兩件事，而且順序不能反：**先把限流變成一個可測的設施（S1），再用它守試算端點（S2）**，最後把登入那份收斂進來並順手補上它缺的測試（S3）。

### 1.4 目標

- `coffee-shared` 新增 `RateLimiter`：程序內、固定視窗、單鍵計數、有總量上限、**所有時間由呼叫端傳入**，可以純單元測試（S1）
- `POST /api/orders/preview` 依**帳號**限流，超過回 429（S2）
- `AuthController` 的手寫版改用同一個設施，**行為與訊息一字不變**，並補上它從來沒有過的測試（S3）

---

## 2. 範圍

### 2.1 在範圍內

- `com.coffee.shared.RateLimiter`（新類別）與它的單元測試（S1）
- `OrderService.preview` 的限流與 `OrderPreviewTest` 的新案例（S2）
- `AuthController` 改用 `RateLimiter`（行為保持）與補測試（S3）

### 2.2 不在範圍內

| 項目 | 為什麼 | 去哪裡 |
| --- | --- | --- |
| `ReconciliationService` 的 60 秒冷卻 | 它的狀態**刻意**放在資料庫（重啟後仍然有效，因為「剛剛才向綠界查過這張單」是一個關於外部系統的事實，不是關於本機的）。搬進程序內記憶體是**行為降級**，不是收斂 | §13.5 說明為什麼不動 |
| 跨實例共用的限流 | 需要 Redis 或等價物。本專案目前連 session 都還在程序內（G05 未開始），**單一實例是現況的前提**，不是本規格的缺陷 | **G20l**（§14 登記） |
| `POST /api/orders`（建立訂單）的限流 | 它有 `Idempotency-Key` + 庫存 + 唯一鍵，重複呼叫不會重複成立。濫打的症狀是浪費算力，不是資料損壞，且會留下稽核痕跡 | **G20l** 一起評估 |
| 其他唯讀端點（`/api/menu`、`/api/promotions/active`） | 它們不接受請求主體、不做 N 次 query、也不回答任何「這個碼有效嗎」。先量再說 | —— |
| `Retry-After` header | 回應格式全專案固定 `{"message": "..."}`（`Errors.java:13`），`Problem` 不帶 header 欄位。要加就得動 `Problem` 與 `Errors` 的契約，所有 429 都受影響 | §13.4、**G20l** |
| 前端的 429 處理 | **不需要。** 試算失敗在 G20h §5.7 已經是靜默降級（`settleQuote` 的 `ok: false`），429 走的就是那條路。登入的 429 既有畫面已經會顯示 `message` | §13.6 |

**本規格是純後端。** `frontend/` 一個檔都不動 —— 這是刻意的，理由見 §13.6。

---

## 3. 涉及模組與邊界

```
coffee-shared    RateLimiter（新類別，與 Actor / Problem / Ids 同層）    依賴：無
coffee-orders    OrderService.preview 用它                              依賴 shared（既有）
coffee-app       AuthController 用它（S3）                              依賴全部（組裝層）
frontend         不動
```

- `coffee-shared` **沒有** `api` / `internal` 兩層（現況只有 `Actor`、`Ids`、`Problem` 三個平放的類別），所以 `RateLimiter` 平放在 `com.coffee.shared`，**不要**為它新造 package
- `RateLimiter` 只依賴 `java.util.concurrent` 與同 package 的 `Problem`。**不可以** import 任何 Spring 類別 —— 它要能在沒有 Spring context 的單元測試裡 `new` 出來（§11.1）
- `ModuleBoundariesTest` 不需要改，也**不可以**改
- 不動 `coffee-catalog`、`coffee-branches`、`coffee-identity`、`coffee-payments`、`coffee-reporting`、`coffee-audit`

---

## 4. DB schema 與 migration

**零 migration。** 限流狀態完全在程序內記憶體，不新增、不修改任何資料表。`V15` 仍然空著。

**為什麼不用資料庫：** 每次試算都要寫一列計數才能限流 —— 那會讓一個「不寫入任何資料」的端點（G20h 驗收 7 用列數斷言釘住的那條）變成每次呼叫都寫一列。**為了限制一個唯讀端點而讓它變成寫入端點，是把問題換成一個更大的問題。** 完整理由見 §13.2。

> `ReconciliationService` 用資料庫是對的，因為它要記的事實（「剛剛查過綠界」）本來就該持久化，而且它已經有那張表要寫。試算沒有這樣的表，也不該為限流生一張。

---

## 5. 技術設計

### 5.1 S1：`RateLimiter`

新檔 `backend/coffee-shared/src/main/java/com/coffee/shared/RateLimiter.java`：

```java
package com.coffee.shared;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 程序內的固定視窗限流。視窗從某個鍵的第一次 hit 開始算，額度用完就擋到視窗結束。
 *
 * <p>所有時間由呼叫端傳入（epoch millis），所以可以純單元測試，不需要睡覺也不需要時鐘替身。
 *
 * <p>狀態在程序內，重啟歸零，也不跨實例共用 —— 本專案目前是單一實例（session 也在程序內）。
 */
public final class RateLimiter {
  private final String message;
  private final int limit;
  private final long windowMs;
  private final int maxKeys;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  private record Window(int count, long until) {}

  /**
   * @param message 額度用完時 429 的訊息（繁體中文，寫給終端使用者看）
   * @param limit 一個視窗內允許的次數，必須 >= 1
   * @param windowMs 視窗長度（毫秒），必須 >= 1
   * @param maxKeys 同時追蹤的鍵數上限；清掉過期的之後仍然超過就一律擋，必須 >= 1
   */
  public RateLimiter(String message, int limit, long windowMs, int maxKeys) { ... }

  /** 記一次並在超額時丟 429。同一個鍵的併發呼叫不會同時放行。 */
  public void hit(String key, long nowEpochMs) { ... }

  /** 清掉某個鍵的計數（例如登入成功）。鍵不存在時什麼都不做。 */
  public void clear(String key) { ... }

  /** 目前追蹤的鍵數。只給測試用。 */
  public int size() { ... }
}
```

#### `hit` 必須滿足的四件事

**1. 檢查與計數是同一個原子操作。** 不可以寫成「先 `get` 判斷、再 `put` 增加」—— 兩個併發請求會同時看到 `count == limit - 1` 然後都放行。用 `ConcurrentHashMap.compute` 在一次呼叫裡完成判斷與增加，超額時在 lambda 裡丟 `Problem`：

```java
windows.compute(key, (k, w) -> {
  if (w == null || w.until() <= nowEpochMs) return new Window(1, nowEpochMs + windowMs);
  if (w.count() >= limit) throw new Problem(429, message);
  return new Window(w.count() + 1, w.until());
});
```

`compute` 的 lambda 丟出的例外會往外傳，且 map 不會被改 —— 這正是我們要的（擋掉的請求不該讓計數繼續長，否則一個被擋的人永遠出不來）。

> **`until <= now` 用小於等於，不是小於。** 視窗結束的那一毫秒應該算新視窗。差一毫秒不影響正確性，但測試會用邊界值，寫清楚免得兩邊對不上。

**2. 過期視窗在**被存取時**就地重生，不靠全表掃描。** 上面的 `w.until() <= nowEpochMs` 已經做掉這件事：一個鍵沉睡一天後再來，看到的是全新視窗。

**3. 全表清理只在超過 `maxKeys` 時做，不是每次請求都做。**

```java
if (windows.size() > maxKeys) {
  windows.entrySet().removeIf(e -> e.getValue().until() <= nowEpochMs);
  if (windows.size() > maxKeys) throw new Problem(429, "系統忙碌，請稍後再試");
}
```

這段放在 `compute` **之前**。

> **為什麼不像 `AuthController.java:53` 那樣每次請求都 `removeIf`：** 那是一次 O(鍵數) 的掃描。登入一天幾百次，不痛；試算是**購物車每動一下就一次**，尖峰時是全站最高頻的端點之一，每次請求掃一萬個鍵就是把限流本身變成效能問題。**延後到真的滿了才掃**，觀察得到的行為完全一樣（兩者都是「清掉過期的，還是滿就擋」），成本從每次請求降到偶爾一次。S3 要保證 `AuthController` 的既有行為不變，這一點算**行為等價的最佳化**，不算行為變更（§13.3）。

**4. `系統忙碌，請稍後再試` 這個總量保險的訊息是固定的**，不走建構子參數。理由：它講的是「伺服器狀態」不是「你做了什麼」，對所有呼叫端都一樣。這也正好與 `AuthController.java:56` 現有的字串相同，S3 因此不必改任何訊息。

#### 不做的事

- **不實作滑動視窗或 token bucket。** 固定視窗的缺點是視窗交界處最多能擠進 `2 × limit` 次。對本規格的用途（擋機器人、保護計價路徑）這個精度足夠，而滑動視窗要存每次請求的時間戳，記憶體從「每鍵兩個欄位」變成「每鍵一個 list」。**精度換記憶體，這裡不值得。**
- **不做分層額度**（例如同時限「每秒」與「每分鐘」）。需要的時候 `new` 兩個 `RateLimiter` 串起來就有了，不必內建。

### 5.2 S2：試算端點接上限流

`OrderService` 新增欄位（**`internal`，不進 `api`**）：

```java
private static final int PREVIEW_LIMIT = 60;
private static final long PREVIEW_WINDOW_MS = 60_000L;
private static final int PREVIEW_MAX_KEYS = 20_000;
private final RateLimiter previewLimiter =
    new RateLimiter("試算過於頻繁，請稍後再試", PREVIEW_LIMIT, PREVIEW_WINDOW_MS, PREVIEW_MAX_KEYS);
```

`preview` 在**權限檢查之後、`price()` 之前**插入一行：

```java
@Override
@Transactional(readOnly = true)
public Quote preview(Actor a, PreviewRequest q) {
  a.require("ORDER_CREATE");
  Problem.check(q != null && q.branchId() != null && !q.branchId().isBlank(), "請選擇分店");
  Problem.check(
      q.items() != null && !q.items().isEmpty() && q.items().size() <= 50, "請選擇 1–50 個品項");
  if (!a.customer()) {
    a.require("POS_ORDER");
    a.branch(q.branchId());
  }
  previewLimiter.hit(a.id(), System.currentTimeMillis());   // ←── 新增的唯一一行
  ...
}
```

**順序是刻意的，三件事都成立才對：**

1. **在 `a.require` 之後** —— 沒有權限的人應該拿到 403，不是 429。權限錯誤要穩定可預期，不該因為額度狀態而變成另一個碼
2. **在格式驗證之後** —— 一個格式本來就錯的請求不該吃掉額度配給。它是客戶端 bug，不是濫用
3. **在 `price()` 之前** —— 限流的全部意義就是不要跑那段 query。放在後面等於沒放

> **為什麼不放在 Servlet Filter 或 `HandlerInterceptor`：** 那樣看起來更「基礎設施」，但在 filter 裡拿不到 `Actor`（`SessionActorFilter` 才剛把它放進 request attribute，順序要自己顧），而且 filter 丟的例外不會經過 `Errors`（`@RestControllerAdvice` 只處理進得了 Controller 的請求），429 的回應格式就會跟全專案不一致。**放在 Service 裡，每個端點明確地自己選要不要限流** —— 多一行程式碼，換掉整類「我以為那個 filter 有蓋到」的誤會。完整理由見 §13.1。

#### 鍵用帳號，不用 IP

`a.id()`，不是 `req.getRemoteAddr()`。理由：

- 試算端點**一律需要登入**（`/api/**` 全部 `authenticated()`），所以帳號永遠拿得到，不像登入端點只有 IP 可用
- 同一間店的 POS、同一個辦公室、同一個行動網路 NAT 後面可能有幾十個人共用一個 IP。**用 IP 限流會讓一個店員的操作擋住同店其他店員**
- 反過來，一個人換 IP 很容易（手機切 Wi-Fi／4G），換帳號要先有帳號。**帳號是比 IP 貴的資源，所以是比較好的限流鍵**

代價：攻擊者有一百個帳號就有一百份額度。那是帳號註冊端的問題（G16 顧客自助註冊目前未開始，帳號只能由管理端建立），不是限流鍵的問題。

#### 為什麼是 60 次 / 60 秒

| 使用者 | 實際速率 | 60/分鐘夠嗎 |
| --- | --- | --- |
| 顧客慢慢挑 | 每次購物車變動後 300ms debounce 才發一次，人類手速併成一次後大約每分鐘幾次到十幾次 | **很夠** |
| POS 店員連點 | 連按加號被 debounce 併成一次；一分鐘內重排一整張大單大約十幾二十次 | **夠** |
| 腳本探優惠碼 | 想要的是每秒數百次 | **擋住**（降到每分鐘 60 次，一本一萬筆的字典要跑快三小時，而且一個帳號打滿就停） |

**這三個數字都寫成具名常數**，讓它可調、可測。它們不是魔法：選 60/60s 的根據是「比最吵的真人用法高一個數量級，比最慢的腳本低兩個數量級」，中間那段空白很寬，所以不需要調校得很準。

`PREVIEW_MAX_KEYS = 20_000`：一個鍵兩個 int／long 加上 map entry 的開銷，兩萬個鍵是百 KB 級，遠低於任何一張報表投影。它的用途是**擋住記憶體被無限撐大**，不是擋使用者 —— 真的達到兩萬個不同帳號同時在試算，那是好消息，到時候調大它。

### 5.3 S3：把登入那份收斂進來

`AuthController` 的改法 —— **行為與訊息一字不變**：

```java
private final RateLimiter logins =
    new RateLimiter("登入嘗試過多，請 15 分鐘後再試", 20, 900_000L, 10_000);
```

`login` 的 `:51-61` 那六行變成兩行：

```java
String key = req.getRemoteAddr();
logins.hit(key, System.currentTimeMillis());
Actor a = identity.authenticate(input.username(), input.password());
logins.clear(key);
```

**逐項核對這是等價的：**

| 既有行為（`AuthController.java`） | `RateLimiter` 的對應 |
| --- | --- |
| `:54-55` `current.count >= 20` 才擋 → 一個視窗剛好放行 20 次 | `hit` 的 `w.count() >= limit`，`limit = 20`，同一個邊界 |
| `:58-59` 視窗 `until = now + 900000`，**從第一次嘗試起算**，不隨後續嘗試延長 | `Window(1, now + windowMs)` 只在 `w == null || 過期` 時重建，`windowMs = 900_000` |
| `:56` `size() > 10000` → `系統忙碌，請稍後再試` | `maxKeys = 10_000`，同一個訊息（§5.1 第 4 點） |
| `:61` 登入成功後 `attempts.remove(key)` | `clear(key)` |
| `:53` 每次請求 `removeIf` 清過期 | 改為鍵層就地重生 ＋ 超過 `maxKeys` 時才全表清。**觀察得到的行為相同**（§5.1 第 3 點、§13.3） |
| 鍵是 `req.getRemoteAddr()` | **不變。** 登入時還沒有帳號可以當鍵 |
| 擋掉時不增加計數 | `compute` 丟例外時 map 不變 —— 與既有的「先判斷再 compute」相同 |

**而且 S3 要補上這份限流從來沒有過的測試**（§11.3）。這是本階段真正的產出：S3 之後，「登入限流」第一次有了會紅的斷言。

> **`private record Attempts` 與 `attempts` 欄位要移除**，不要留著當死碼。這是本規格唯一的刪除動作，PR 描述要寫出來。

---

## 6. API

### 6.1 沒有新端點

本規格不新增任何端點，不改任何請求或回應的欄位。

### 6.2 既有端點的變化

| 端點 | 變化 |
| --- | --- |
| `POST /api/orders/preview` | **多一個 429 的可能**：同一帳號 60 秒內第 61 次起回 `{"message": "試算過於頻繁，請稍後再試"}`。其餘回應一字不變 |
| `POST /api/auth/login` | **完全不變**（S3 是內部重構，狀態碼與兩個訊息都相同） |
| 其他 | 不動 |

### 6.3 錯誤碼

| 狀態 | 情境 | 訊息 |
| --- | --- | --- |
| 429 | 同一帳號試算過於頻繁 | `試算過於頻繁，請稍後再試` |
| 429 | 追蹤的鍵數超過上限（伺服器自保） | `系統忙碌，請稍後再試` |
| 429 | 登入嘗試過多（**既有，不變**） | `登入嘗試過多，請 15 分鐘後再試` |

訊息都是繁體中文、寫給終端使用者看、回應格式沿用 `{"message": "..."}`（`Errors.java:13` 的 `Problem` handler 直接處理，不需要新的 handler）。

---

## 7. 權限與資料範圍

**本規格不新增端點，所以不新增任何權限或資料範圍。**

| 項目 | 規則 |
| --- | --- |
| `Identity.PERMISSIONS` | **一字不改**（驗收 12） |
| `POST /api/orders/preview` 的權限 | 沿用 G20h §7：`ORDER_CREATE`，店員另需 `POS_ORDER` + `a.branch(branchId)` |
| 限流與授權的順序 | **授權先、限流後**（§5.2）。無權限的人拿 403，不會因為額度狀態改成 429 |
| 限流鍵的資料範圍 | `a.id()`。**一個帳號的額度不會被另一個帳號消耗**，驗收 6 用兩個不同帳號交錯呼叫釘住這一條 |
| 跨店越權 | 沿用 G20h —— 店員對非所屬分店試算仍然是 403，且**在限流之前**就被擋掉（所以越權請求不會吃掉受害者的額度，也不會吃掉攻擊者自己的，這是 §5.2 順序的附帶效果，驗收 7 驗它） |

`SecurityConfiguration` 不改。CSRF 規則不改（`POST` 仍需 token，`/api/payments/ecpay/callback` 仍是唯一例外）。

---

## 8. 金額規則

**本規格不碰任何金額。** `price()` 一行不動、`Discounts.quote` / `apply` 一行不動、前端一個檔不動。

唯一與金額有關的是**時機**：限流插在 `price()` 之前，所以被擋掉的請求**完全沒有計價**，不會回出一個部分計算的金額。429 的回應主體只有 `message`，沒有任何金額欄位 —— 前端的 `settleQuote(..., { ok: false })` 會把 `quote` 設成 null、總計退回毛額（G20h §5.8），這是既有路徑，不需要任何改動。

---

## 9. 施工階段

> 三個階段，**每階段獨立 CI 綠、獨立可合併、有自己的驗收子集**。全部是加法，唯一的刪除在 S3（`AuthController` 的死碼），而那次刪除與同階段的替換是同一件事。

### S1 —— `RateLimiter` 與它的單元測試（小）

**動到：** 新檔 `coffee-shared/.../RateLimiter.java`、新檔 `coffee-app/src/test/java/com/coffee/app/RateLimiterTest.java`

**這一階段沒有任何呼叫端。** 合進主線後對外行為**完全沒有變化** —— 只是多了一個沒有人用的類別。這正是 AGENTS.md「能編譯、測試綠、但功能尚未接上」那種安全的中間狀態。

**驗收子集：** 1、2、3、4、11、13、14

### S2 —— 試算端點限流（小）

**動到：** `OrderService.java`（+3 常數、+1 欄位、+1 行）、`OrderPreviewTest.java`（新增案例）

**前提：已滿足。** G20h（[PR #77](https://github.com/choka1227/coffee_GPT6/pull/77)）已於 2026-10-08 合併進主線（`0d8f5dd`），`OrderService.preview` 與 `price()` 都在（`OrderService.java:232`、`:188`）。

**驗收子集：** 5、6、7、8、9、12、13、14

### S3 —— 收斂登入的那一份（小）

**動到：** `AuthController.java`（刪 `Attempts` record 與 `attempts` 欄位、改 6 行為 2 行）、新檔或既有檔新增登入限流的測試

**驗收子集：** 10、11、13、14

### 階段之外：給中斷續作的指示

- **S1 與 S2 之間可以停。** S1 單獨合併是安全的（沒有呼叫端）
- **S2 與 S3 之間可以停。** S2 單獨合併後試算有限流、登入仍用舊的那份，兩者並存沒有衝突
- **S3 不要拆開。** 刪掉 `attempts` 欄位與改用 `logins` 是同一件事，拆開會留下一個編譯不過或行為錯的中間狀態
- 續作前先看 PR 描述的進度檢查表，**不要重做已完成的階段**

---

## 10. 驗收條件

### S1

- [ ] 1. 一個 `RateLimiter("訊息", 3, 1000, 10)`：同一鍵連續 `hit(k, 0)` 三次都通過，第四次丟 `Problem`，`status == 429`、訊息為建構子傳入的那一句
- [ ] 2. **視窗從第一次 hit 起算，不隨後續 hit 延長**：`hit(k, 0)`、`hit(k, 500)`、`hit(k, 999)` 三次通過；`hit(k, 1000)`（視窗剛結束）**通過**且是新視窗的第一次，`hit(k, 1001)`、`hit(k, 1002)` 也通過，第四次才擋
- [ ] 3. **被擋掉的請求不增加計數**：額度用完後連打 10 次（全部 429），再把時間推到視窗結束，**立刻就能再 hit `limit` 次** —— 被擋的那 10 次沒有把視窗往後延、也沒有把計數堆高
- [ ] 4. **鍵彼此獨立**：`a` 打到額度用完後，`b` 仍然可以 hit 滿額；`clear("a")` 之後 `a` 又能從零開始，`b` 不受影響
- [ ] 11. **`maxKeys` 的保險**：`new RateLimiter("x", 1, 1000, 2)`，用 `now = 0` 塞 3 個不同鍵後，第 4 個新鍵在 `now = 0`（過期清不掉任何東西）丟 429 且訊息是 `系統忙碌，請稍後再試`；把 `now` 推到視窗之後，同一次呼叫**通過**（過期的被清掉了），且 `size()` 下降
- [ ] 13. `RateLimiter` **不 import 任何 `org.springframework.*`**，且 `RateLimiterTest` **沒有 `@SpringBootTest`**（純單元測試，跑得快）

### S2

- [ ] 5. 同一顧客帳號連續 `preview` 60 次全部 200，第 61 次丟 `Problem`，`status == 429`、訊息 `試算過於頻繁，請稍後再試`
- [ ] 6. **帳號之間不互相消耗**：A 帳號打滿 60 次之後，B 帳號的第一次 `preview` 仍然 200
- [ ] 7. **授權在限流之前**：店員對非所屬分店試算回 **403**（不是 429），且**連打 100 次之後**該店員對自己分店的第一次合法試算仍然 200 —— 被 403 擋掉的請求沒有吃掉額度
- [ ] 8. **格式錯誤在限流之前**：`items` 為空的請求回 400，連打 100 次之後合法試算仍然 200
- [ ] 9. **`POST /api/orders`（建立訂單）不受影響**：`create` 連續成立 3 張訂單都成功，不會因為試算額度而被擋
- [ ] 12. `Identity.PERMISSIONS` **一字未改**

### S3

- [ ] 10. **登入限流的行為與重構前相同**，且第一次有測試證明它：同一來源連續 20 次密碼錯誤的登入都回 401（既有的認證失敗訊息），第 21 次回 **429** 且訊息為 `登入嘗試過多，請 15 分鐘後再試`；在打滿之前**成功登入一次**，之後計數歸零（再連 20 次錯誤才會再擋）
- [ ] 11. （同 S1，`maxKeys` 的保險由 `RateLimiterTest` 覆蓋，S3 不必重測）

### 全階段

- [ ] 13. （同 S1）
- [ ] 14. **零 migration、零新依賴、零前端變更**：`db/migration/` 沒有新增檔案，`backend/pom.xml` 與各模組 `pom.xml` 的依賴清單一字未改，`frontend/` 一個檔都沒動
- [ ] 15. `cd frontend && npm ci && npm run build`、`npm test`、`cd backend && ./mvnw -B -ntp verify` 全綠
- [ ] 16. 執行位元：`backend/mvnw`、`scripts/build.sh`、`start-demo.sh` 皆 `100755`
- [ ] 17. **既有測試一條都沒改**：全專案既有的測試案例與斷言**不得修改、放寬、刪除或 `@Disabled`**。新增案例一律可以

---

## 11. 測試要求

### 11.1 S1：`RateLimiterTest`（純單元測試，**不要** `@SpringBootTest`）

放在 `backend/coffee-app/src/test/java/com/coffee/app/RateLimiterTest.java`（沿用現有測試集中在 `coffee-app` 的慣例）。

| 情境 | 驗什麼 | 驗收 |
| --- | --- | --- |
| 額度內／超出 | 前 `limit` 次通過，下一次 429 + 訊息 | 1 |
| 視窗邊界 `until <= now` | 視窗結束的那一毫秒算新視窗 | 2 |
| 被擋不計數 | 擋掉 10 次後視窗仍按原時間結束 | 3 |
| 多鍵與 `clear` | 鍵彼此獨立；`clear` 只清一個鍵 | 4 |
| `maxKeys` | 滿了就 429；過期清掉後恢復 | 11 |
| **併發** | 兩條以上執行緒對**同一鍵**同時 `hit`，`limit = 1` 時**恰好一次成功、其餘全部 429** | 1 |

> **併發那條不要用 `Thread.sleep` 當同步。** 沿用 `DiscountRedemptionTest` 的做法（`CountDownLatch` ready / start 兩段式，見該檔的 `applyAfterSignal`），執行緒先全部就位再一起放行。這條是 §5.1 第 1 點（`compute` 的原子性）唯一會紅的證據 —— 寫成「先 get 再 put」的話它就會抓到。

**時間全部用常數傳入，不要呼叫 `System.currentTimeMillis()`。** 這整個測試檔不應該出現任何真實時間 —— 那是把 `RateLimiter` 的時間設計成參數的全部目的。

### 11.2 S2：`OrderPreviewTest` **新增**案例（既有案例一條都不動）

| 情境 | 驗什麼 | 驗收 |
| --- | --- | --- |
| 同帳號 61 次 | 第 61 次 429 + 訊息 | 5 |
| 兩個帳號交錯 | A 打滿不影響 B | 6 |
| 跨店越權 100 次後合法試算 | 403 不吃額度 | **7** |
| 空 `items` 100 次後合法試算 | 400 不吃額度 | **8** |
| `create` 連續三張 | 建立訂單不受影響 | 9 |

> **驗收 7 與 8 是本規格最容易寫錯的兩條。** 它們驗的不是「有限流」而是**順序**：限流如果放到驗證之前，任何人都能用一串格式錯誤或越權的請求把別人（或自己）的額度燒掉，而那種請求**連 `price()` 都不會跑**，擋它完全沒有價值。寫這兩條時打 100 次（遠超 60 的額度），才證明得出那些請求真的沒有進計數。

**S2 的限流額度是 60/分鐘，測試要打 61 次** —— 用 `orders.preview(...)` 的 Service 層呼叫（不必走 HTTP），單品項單選項的最小購物車，避免測試變慢。

> **`@DirtiesContext(AFTER_EACH_TEST_METHOD)`（`OrderPreviewTest` 既有的）在這裡是必要的，不是裝飾**：`previewLimiter` 是 `OrderService` 的**實例欄位**，Spring context 不重建的話，前一個測試方法打滿的額度會漏到下一個。既有的 annotation 已經保證這件事，**不要拿掉它**。新增案例時如果發現額度在方法之間互相污染，先確認這個 annotation 還在，不要去改限流的數字來繞過。

### 11.3 S3：登入限流的測試（**新增**，這份限流目前完全沒有測試）

| 情境 | 驗什麼 | 驗收 |
| --- | --- | --- |
| 20 次密碼錯誤 | 每次都是既有的認證失敗狀態與訊息 | 10 |
| 第 21 次 | 429 + `登入嘗試過多，請 15 分鐘後再試` | 10 |
| 打滿前成功登入一次 | 計數歸零，再連 20 次錯誤才會擋 | 10 |

鍵是 `req.getRemoteAddr()`，所以這組要走**真實 HTTP**（`OrderPreviewTest.BrowserSession` 或 `HttpWorkflowTest` 那一類，帶 Cookie + CSRF），同一個 client 的 remote address 一致。

> 這一階段的重構**沒有既有測試保護**（§1.3 的表格最後一欄）。所以 S3 的順序是**先寫測試打在現況上、確認綠，再替換實作、確認仍綠** —— 不要先改再補測試，那樣測試只會證明新寫的那份自己跟自己一致。

### 11.4 不要做的事

- **不要**用 `Thread.sleep` 等視窗過期。`RateLimiter` 的時間是參數，S1 全部用常數；S2／S3 若需要讓視窗過期，改用更小的額度或新的 `Actor`／來源，不要睡
- **不要**為了讓測試好寫而把 `PREVIEW_LIMIT` 調小到 3 或 5。那個數字是產品行為的一部分（§5.2 的表格），不是測試參數。61 次 Service 層呼叫在 H2 上是毫秒級的
- **不要**把 `RateLimiter` 做成 Spring `@Component` 或 `@Bean`。它是一個有狀態的小工具，由使用它的 Service 自己 `new`（每個端點的額度不同，共用一個 bean 反而要再長出一層 key 命名空間）
- **不要**在 `RateLimiter` 裡記 log。被擋的請求在正常營運中會發生（有人手滑連點），每次寫一行 log 就是把限流變成日誌洪水的開關。要觀測性是另一個缺口（§14 G20l）
- **不要**修改、放寬、刪除或 `@Disabled` 任何既有測試案例（驗收 17）。S3 動到 `AuthController` 時尤其注意 `HttpWorkflowTest` 裡的登入流程 —— 它每個測試都要登入，如果新的限流把它擋掉，那是**實作錯了**（額度 20 次沒有變），不是測試該改

---

## 12. 與其他工作的並行注意

| 檔案 | G20h（PR #77） | G20j |
| --- | --- | --- |
| `OrderService.java` | 新增 `price()` / `preview()` | **S2 在 `preview()` 裡加一行** ⚠️ |
| `OrderPreviewTest.java` | 新建 | **S2 新增案例** ⚠️ |
| `coffee-shared/` | 不動 | 新檔 `RateLimiter.java` |
| `AuthController.java` | 不動 | S3 動 |
| 前端 | 動 | **不動** |

**曾經有交集，而且合併順序是硬的：G20h 先，G20j 的 S2 後。** `preview()` 這個方法在 G20h 合併前根本不存在，S2 沒有東西可以改。

**這道閘門已經解除：[PR #77](https://github.com/choka1227/coffee_GPT6/pull/77) 已於 2026-10-08 合併進主線（`0d8f5dd`）**，上表 ⚠️ 的兩個檔案都已經是主線上的既有檔案：

- `OrderService.java:232` 的 `preview()` 與 `:188` 的 `price()` 都在，S2 §5.2 的程式碼片段（`a.require` → 兩條 `Problem.check` → `if (!a.customer())` → `price()`）與主線實際的行序**逐行相符**，那一行 `previewLimiter.hit(a.id(), ...)` 插在 `if (!a.customer())` 區塊之後、`var priced = price(...)` 之前
- `OrderPreviewTest.java` 已存在（278 行），S2 只在它後面**新增**案例，既有案例一條都不動

**所以三個階段都沒有前置閘門，S1／S2／S3 可以依序一路做下去，不需要為了等 G20h 而只做 S1、也不需要為此維持 draft。** 階段之間仍然可以停（§9「階段之外」），但那是額度的考量，不再是相依的限制。

> **`codex/g20h-order-preview` 分支已完成任務**，不要從它續作；G20j 用自己的分支 `codex/g20j-rate-limit`，從合併後的主線開出。

兩份都零 migration，不可能撞 Flyway 版號。

---

## 13. 設計決策

每一項都附理由與推翻它的代價。**本節沒有「待 PO 決定」**，設計決策已整批授權給 Claude（`AGENTS.md`「設計決策的歸屬」）。

### 13.1 限流放在 Service，不放在 Filter 或 Interceptor

**決定：** 每個需要限流的端點在它的 Service 方法裡明確呼叫 `limiter.hit(key, now)`。不做全域的 filter。

**理由：** 三個都是實務上的問題，不是品味問題。

1. **Filter 裡沒有 `Actor`。** `SessionActorFilter` 把它放進 request attribute，filter 之間的順序要自己維護；而本規格選了帳號當鍵（§5.2）
2. **Filter 丟的例外不經過 `Errors`。** `@RestControllerAdvice` 只處理進得了 Controller 的請求，filter 裡丟 `Problem` 會變成容器的預設錯誤頁，429 的回應格式就跟全專案的 `{"message": "..."}` 不一致 —— 而前端的 `api.ts:37` 會先 `JSON.parse`，拿到 HTML 會變成「服務回應異常」
3. **每個端點的額度本來就不同。** 全域 filter 要嘛一個額度管全部（對登入太寬、對報表太嚴），要嘛長出一張「路徑 → 額度」的對照表 —— 那張表會和實際的端點清單漂移，而且**漏掉一個端點時完全沒有症狀**

**代價：** 新端點要限流得自己記得加那一行。漏加不會有任何警告。**這是真實的代價**，接受它的理由是：顯式的一行漏掉是「這個端點沒限流」，而對照表漏一列是「我以為它有限流」。**前者是已知的缺，後者是假的安全感。**

**推翻的代價：** 要改成 filter，得先決定回應格式怎麼和 `Errors` 一致（最省的做法是讓 filter 只做標記、由 `HandlerInterceptor` 在 Controller 前丟 `Problem`），再處理 `Actor` 的取得時機。`RateLimiter` 本身不必改 —— 它只是個計數器，**換呼叫點不必換設施**。這是把它設計成「不依賴 Spring」的附帶好處。

### 13.2 限流狀態在記憶體，不進資料庫

**決定：** `ConcurrentHashMap`，程序內，重啟歸零。

**理由：** 試算端點的核心性質是**不寫入任何資料**（G20h 驗收 7 用四張表的列數斷言釘住它）。為了限制它而讓它每次呼叫寫一列計數，等於親手推翻那條驗收 —— 而且寫入量比它防的濫用更可觀：正常顧客一次購物會產生十幾次試算，每次一列。

**代價有兩個，都要說清楚：**

- **重啟歸零。** 一個正在被打滿的攻擊者在部署後拿到全新額度。可接受：限流的目的是把速率從「每秒數百」降到「每分鐘 60」，不是永久封鎖。永久封鎖是另一件事（需要帳號狀態，屬於 G16／identity 那條線）
- **不跨實例。** 兩個實例各自放行 60 次／分鐘，實際上限變成 120。**在單一實例的現況下這不是問題**，而現況是硬的：session 也在程序內（`HttpSessionSecurityContextRepository`），G05「Session 集中化」還沒開始。**也就是說，這個系統在限流之前就已經不能水平擴展了** —— 限流沒有讓它變得更不能

**推翻的代價：** 要跨實例共用就要 Redis 或等價物，那是 G05 的前提一併解決的事，已登記為 §14 的 G20l。**不要**用資料庫表來模擬共用計數器：那是把高頻寫入放進交易路徑，會比它防的濫用先把系統弄垮。

### 13.3 S3 的「行為不變」允許一處最佳化：不再每次請求全表掃描

**決定：** `AuthController` 原本每次登入都 `attempts.entrySet().removeIf(...)`（`:53`）。`RateLimiter` 改為鍵層就地重生 ＋ 只在超過 `maxKeys` 時全表清。

**理由：** 兩者**觀察得到的行為完全相同** —— 過期的計數都不生效，鍵數滿了都回 `系統忙碌，請稍後再試`。差別只在清理的時機與成本。保留每次掃描的版本會讓這個設施不能用在高頻端點上，而**高頻端點正是本規格存在的原因**（試算）。

**為什麼這不算違反「行為不變」：** 「行為」指的是呼叫端觀察得到的結果（狀態碼、訊息、何時被擋）。清理時機觀察不到 —— 除了一種情形：**鍵數在 `maxKeys` 附近徘徊時**，新版可能先丟一次 429 再清理。但那個分支在 `:56` 的既有版本裡也是「清完還是滿就擋」，而兩萬／一萬個鍵的門檻距離正常營運很遠。

**推翻的代價：** 想要「每次都清」只要把 `removeIf` 移到 `hit` 開頭、拿掉 `size() > maxKeys` 的條件 —— 一行的事。但那時候要同時把試算端點的限流換成別的實作，否則就是把本規格最在意的那條效能問題裝回去。

### 13.4 429 不帶 `Retry-After`

**決定：** 只回 `{"message": "..."}`，不加 header。

**理由：** 全專案的錯誤回應由 `Problem` + `Errors.java:13` 固定成 `{"message": "..."}`，`Problem` 沒有攜帶 header 的欄位。要加就得動 `Problem` 的形狀（全專案所有錯誤都受影響）或在 `Errors` 裡針對 429 特判（那需要 `Problem` 多帶一個「還要等多久」的資料）。**為一個 header 動全專案的錯誤契約，不划算。**

而且本規格的兩個呼叫端都不需要它：試算的 429 在前端是靜默降級（下一次購物車變動就會重試），登入的 429 訊息裡已經寫了「15 分鐘後再試」。

**推翻的代價：** 真的需要（例如要給第三方 API 用）就讓 `Problem` 多一個 `retryAfterMs` 欄位（預設 0）並在 `Errors` 裡轉成 header。那是一次小而全域的改動，登記在 G20l。

### 13.5 不動 `ReconciliationService` 的 60 秒冷卻

**決定：** `ReconciliationService.java:188-197` 的 DB 版冷卻保持原樣，不收斂進 `RateLimiter`。

**理由：** 它們的語意不同，不是同一件事的兩種寫法。

| | 登入／試算 | 綠界對帳查核 |
| --- | --- | --- |
| 在限制什麼 | **本機**資源（計價、密碼雜湊） | **外部系統**的呼叫（綠界的查單 API） |
| 狀態該不該撐過重啟 | 不該（重啟就是新的開始） | **該**（「剛剛才向綠界查過這張單」是一個關於外界的事實，部署一次不會讓它失效） |
| 鍵 | 帳號／IP | `order_id` |
| 狀態存哪 | 程序內 | 既有的 `payment_reconciliations` 表（本來就要寫） |

把它搬進記憶體是**行為降級**：部署一次就能繞過冷卻，對外部付款服務多打一次查詢。

**代價：** 「限流」在這個 repo 裡會有兩種實作共存。**接受它，而且要寫下來**：`RateLimiter` 管程序內的速率，`payment_reconciliations` 管對外呼叫的持久冷卻。有第三種需求時先問它屬於哪一類，不要預設是前者。

**推翻的代價：** 要統一就得讓 `RateLimiter` 支援可插拔的儲存後端 —— 那會把一個 60 行的類別變成一個抽象層，而目前只有兩個使用情境，其中一個已經有現成的表。**抽象的成本現在高於它省下的重複。**

### 13.6 前端一個檔都不改

**決定：** `frontend/` 零變更。

**理由：** 兩個受影響的端點在前端都已經有正確的失敗路徑。試算失敗在 G20h §5.7 是**靜默降級**（`settleQuote(..., { ok: false })` → `quote` 設為 null → 總計退回毛額），429 與 500 走同一條路，而且它**本來就該靜默** —— 「試算過於頻繁」對一個只是在加減數量的顧客來說是噪音，他下一次動購物車就會重試。登入的 429 既有畫面會顯示 `message`，訊息本身沒變。

**附帶好處：** 本規格因此是純後端，`npm run build` 與前端測試不可能因它而紅，審查面也小一半。

**推翻的代價：** 如果日後發現顧客卡在「總計一直是毛額」而不知道為什麼（例如 debounce 被改壞、或某個客戶端在狂打），要在購物車加一行很淡的提示。那時候 `QuoteState.failed` 這個欄位已經在了（G20h §5.7 留的），**前端有現成的掛點**，不需要後端配合。

### 13.7 額度與視窗寫成常數，不進設定檔

**決定：** `PREVIEW_LIMIT` / `PREVIEW_WINDOW_MS` / `PREVIEW_MAX_KEYS` 是 `OrderService` 的 `private static final`，不走 `application.yml` 或 `@Value`。

**理由：** 設定項的成本不只是那一行 —— 它要有預設值、要在兩個 profile（`application.yml` 與 `application-dev.yml`）裡一致、要有人記得它存在、調錯了要能看出症狀。**而目前沒有任何證據顯示這三個數字需要在不運行新版本的情況下調整**（§5.2 說明了為什麼它們不需要調得很準）。

**代價：** 要調整就要改程式碼並重新部署。

**推翻的代價：** 很低 —— 改成 `@Value("${coffee.preview.rate-limit:60}")` 之類的三行，`RateLimiter` 本身不必改。**所以這個決定刻意選了容易推翻的那一邊**：先用常數，真的有人要調再外部化，而不是先造一個沒人用的設定面。

---

## 14. 登記給後續的缺口

| 編號 | 內容 | 來源 |
| --- | --- | --- |
| **G20l** | **跨實例限流與限流的觀測性**。三件事綁在一起：(1) 共用計數器（Redis 或等價物），與 G05「Session 集中化」是同一個前提；(2) `Problem` 攜帶 `Retry-After`（§13.4）；(3) 被擋的請求要有可聚合的指標（不是每次寫 log，§11.4）。要新依賴與基礎設施變更，而且三件都只有在真的要水平擴展時才有意義 | 本規格 §13.2、§13.4 |
| G20k | 可驗證的 DB 級唯讀紅線（Testcontainers + `setEnforceReadOnly(true)`） | G20h §13.4 |
| G20b | 多規則疊加與單位消耗模型（**開工前提仍是要有真實促銷方案**） | G20 §13.2 |
| G20d | 選項層促銷（加料免費、第二份加料半價） | G20 §2.2 |
| G20f | 訂單層優惠碼折抵分攤到品項（分攤演算法已寫好放在 G20c 附錄 A） | G20c §13.2 |
| G21 | 會員價與員工價 | G07 §11.2 |
| G16 | 顧客自助註冊。**本規格的限流鍵是帳號（§5.2），所以註冊一旦開放，「取得新額度的成本」就等於「註冊一個帳號的成本」** —— 做 G16 時要一併決定註冊端自己的限流與驗證 | 第二次盤點；本規格 §5.2 |

**這些都不計入規格庫存**，登記的目的是讓下一輪不用重新推導。

編號說明：`G20j` 由 G20h §14 占用（即本規格），`G20k` 亦由 G20h 占用，`G20l` 由本規格占用（上表的跨實例限流與觀測性），所以 **`G20m`** 是 `G20` 系列下一個未使用號。主序列的下一個未使用號仍是 `G29`。
