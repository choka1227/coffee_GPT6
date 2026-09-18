# G11 + G15 — 稽核軌跡與現金日結交班

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G11（稽核紀錄的查詢與涵蓋範圍）+ G15（現金日結與交班） |
| 優先順序 | P1 —— 金錢控管，不依賴任何外部服務 |
| 規格版本 | v1 |
| 撰寫 | Claude（PM / SA），2026-09-18 |
| 實作 | Codex（PG / SD） |
| 基準 commit | `cf7c38c`（PR #15 合併後的 `feature/init-project`） |
| 前置 | 無。與 G13 動到完全不同的檔案，可並行（見第 12 節） |

---

## 1. 背景與目標

### 問題一：有稽核資料表，卻無法稽核（G11）

`audit_log` 表在 `V1__coffee_schema.sql:15` 就存在：

```sql
CREATE TABLE audit_log(id VARCHAR(36) PRIMARY KEY,actor_id VARCHAR(36) NOT NULL,
  action VARCHAR(40) NOT NULL,target_id VARCHAR(80) NOT NULL,created_at BIGINT NOT NULL);
```

但全專案**只有 `IdentityService.java:219` 一處私有方法會寫入**，只有兩個 action：

```java
audit(a, "ACCOUNT_SAVE", id);   // IdentityService.java:140
audit(a, "ROLE_SAVE", r.code()); // IdentityService.java:198
```

而且**沒有任何查詢端點**。這張表現在的實際用途是零：寫進去的人看不到，看得到的人查不到。

沒有紀錄的動作全部是會動到錢或動到權限邊界的：

| 動作 | 位置 | 為什麼該記 |
| --- | --- | --- |
| 現金收款 | `OrderService.cash()`（`OrderService.java:173`） | 錢進抽屜，目前只有訂單上的 `tendered`，沒有「誰收的」 |
| 訂單狀態轉換 | `OrderService.transition()`（`OrderService.java:192`） | 取消訂單會讓營收憑空消失，沒有人負責 |
| 菜單改價 | `CatalogService.save()`（`CatalogService.java:97`） | 改一次價，之後所有訂單的金額都變，沒有前後值紀錄 |
| 分店設定 | `BranchService.save()`（`BranchService.java:42`） | 關店、改月目標會直接影響報表與點餐可用性 |

### 問題二：收了一整天現金，對不起帳（G15）

`OrderService.cash()` 記了 `tendered` 與 `change_amount`，但系統沒有「班別」或「抽屜」的概念：

- 沒有開班準備金（零錢基金）
- 沒有交班點鈔
- 沒有短溢比對

所以「抽屜裡的錢跟系統對不對得起來」這個問題，系統回答不了。店長只能自己拿計算機加，加錯了也沒人知道。這是**純內部的金錢控管缺口，與綠界完全無關**，不受 PO 的金流延後決定影響。

### 為什麼兩個缺口寫成同一份規格

不是為了湊在一起。是因為 **G15 的每一個動作（開班、點鈔、交班、短溢）本身就是必須進稽核軌跡的金錢動作**。先做 G15 再回頭補稽核，等於要把剛寫好的三個 service 方法再改一次；先做 G11 的基礎建設，G15 直接用，是一次到位。

兩者在施工階段上仍然是分開的（S1/S2 是 G11，S3/S4 是 G15），**任何一個階段單獨合併都不會破壞既有行為**。

### 目標

1. 建立一個所有業務模組都能用的稽核寫入管道，**不製造新的跨模組相依**
2. 把上表四類動作納入稽核軌跡，每筆都能回答「誰、在哪一店、對什麼、做了什麼、何時」
3. 稽核紀錄可查詢，且**依資料範圍分級**：店長只看得到自己分店，總部看得到全部
4. 分店可以開班（帶準備金）、交班（點鈔），系統算出應有金額與短溢，**金額一律後端算**
5. 既有行為零破壞：現有測試不改語意即可通過；沒有開班的分店照樣能收現金

### 不是目標

- **不做班別排程、排班表、工時計算。** 這裡的「班」是現金抽屜的一段區間，不是人資概念
- **不做多抽屜／多收銀台。** 一個分店同時最多一個開啟中的班別（見第 11.3 節設計決策）
- **不做稽核紀錄的匯出、保留期限清理與告警。** 保留期限沿用 G01a §6 的決定（不清理，資料量成為問題時併入 G09 的資料生命週期）
- **不做稽核紀錄的修改或刪除端點。** 稽核軌跡只增不改，沒有任何寫後修改的路徑
- 不做現金以外的付款方式日結（ECPAY 的對帳是 G01，已完成）

---

## 2. 範圍

### 在範圍

- 新模組 `coffee-audit`（`api` + `internal`），含 Maven 模組設定與 `ModuleBoundariesTest` 的模組清單更新
- `audit_log` 的欄位擴充 migration（**只加欄位，不改既有欄位型別**）
- 稽核寫入：現金收款、訂單狀態轉換、菜單儲存、分店儲存、開班、交班
- 稽核查詢 API（游標分頁、篩選、資料範圍分級）
- `cash_sessions` 表與 `orders.cash_session_id`
- 兩個新權限：`AUDIT_VIEW`、`CASH_SESSION`，含既有資料庫的角色授權 migration 與 `InitialData` seed 同步
- 前端：稽核查詢畫面、現金開班／交班畫面
- `docs/API.md` 端點表更新

### 不在範圍

- **不動 `V1__coffee_schema.sql`、`V2__payment_reconciliation.sql`、`V3__product_options.sql`**（`AGENTS.md` 禁止事項第 6 條）
- 不動 `coffee-payments`、`coffee-reporting` 的任何既有邏輯
- 不改 `orders` 表既有欄位，只新增一個 nullable 欄位
- 不引入任何新的第三方相依，不引入排程框架
- 不改任何既有端點的回應格式

---

## 3. 涉及模組與邊界

| 模組 | 變更 |
| --- | --- |
| `coffee-audit`（**新增**） | `api`：`Audit` interface 與 record；`internal`：`AuditService`、`AuditController` |
| `coffee-orders` | 稽核寫入；`cash_sessions` 讀寫與交班 API；`cash()` 綁班別 |
| `coffee-catalog` | `save()` 寫稽核 |
| `coffee-branches` | `save()` 寫稽核 |
| `coffee-identity` | 既有私有 `audit()` 改走 `Audit`；`PERMISSIONS` 新增兩個常數 |
| `coffee-app` | Flyway migration；`InitialData` 角色權限 seed；`ModuleBoundariesTest` 模組清單 |
| `frontend/src/modules/identity` | 稽核查詢畫面 |
| `frontend/src/modules/orders`（新資料夾，對應後端 `orders`） | 現金開班／交班畫面 |

### 新的模組相依（本規格唯一的邊界變更）

```
coffee-audit       稽核軌跡的寫入與查詢        依賴：shared
coffee-orders      ...                        依賴：catalog.api, branches.api, audit.api, shared
coffee-catalog     ...                        依賴：audit.api, shared
coffee-branches    ...                        依賴：audit.api, shared
coffee-identity    ...                        依賴：branches.api, audit.api, shared
```

**`coffee-audit` 只依賴 `shared`，所以它不可能參與任何循環。** 這是刻意的：稽核是横切關注點，必須是相依圖的葉節點，誰都可以呼叫它、它誰都不呼叫。`ModuleBoundariesTest` 的 `slices().beFreeOfCycles()` 會驗證這點。

`ModuleBoundariesTest` 第 20-21 行的模組字串陣列要加入 `"audit"`：

```java
for (String module :
    new String[] {"identity", "branches", "catalog", "orders", "payments", "reporting", "audit"})
```

加進去之後，任何模組引用 `com.coffee.audit.internal..` 都會 build fail —— 這正是我們要的，所有人只能透過 `com.coffee.audit.api.Audit` 寫稽核。

### 為什麼是新模組，不是塞進 `coffee-shared` 或 `coffee-identity`

考慮過三個放法，理由與代價都記在這裡，供下一輪推翻：

| 放法 | 問題 |
| --- | --- |
| `Audit` 介面放 `coffee-shared`、實作放 `coffee-app` | 省下一個 Maven 模組，但把「稽核查詢的資料範圍判斷」這種業務邏輯放進組裝層。`coffee-app` 的職責是「啟動、Security、Session、Flyway」，塞業務 service 與 controller 進去，下一個人就會照做，組裝層會變成雜物間 |
| 整包放 `coffee-identity` | `audit_log` 現在確實是 identity 在寫。但這會讓 `coffee-catalog`、`coffee-orders`、`coffee-branches` 全部相依 `identity.api`，而 `identity` 又相依 `branches.api` —— `catalog` 因此傳遞相依 `branches`，而 G13 規格第 3 節才剛明確決定不要建立 catalog → branches 這條線 |
| **新模組 `coffee-audit`（採用）** | 多一個 Maven 模組（pom、parent 的 `<modules>`、各消費模組的 dependency，約 6 個檔案的機械性變更）。換到的是一個永遠不會參與循環的葉節點，以及「稽核只能從一個入口寫」的強制力 |

**推翻它的代價**：如果之後決定收掉這個模組，要把 `Audit` 介面搬家並改動所有消費模組的 import 與 pom —— 機械性但涉及面廣。反過來說，現在不建模組、之後才要建，代價一模一樣。所以現在建。

### 為什麼 `cash_sessions` 放在 `coffee-orders` 而不是新模組

**這一項如果放錯會直接造成循環相依，Codex 請特別注意。**

現金班別需要兩個方向的互動：

1. 交班時要算「這個班收了多少現金」→ 需要讀 `orders`
2. `cash()` 收款時要把訂單標記到當下開啟的班別 → 需要寫 `orders.cash_session_id`

如果把 `cash_sessions` 放進獨立模組 `coffee-cash`，那 `coffee-cash` 要依賴 `orders.api`（方向 1），而 `coffee-orders` 又要依賴 `coffee-cash.api`（方向 2）—— **直接循環，`ModuleBoundariesTest` 會 build fail**。

`cash_sessions` 的生命週期與 `orders` 的現金收款完全綁在一起，本來就是同一個聚合。放在 `coffee-orders` 裡，兩個方向都是模組內呼叫，沒有循環問題。

---

## 4. DB schema 與 migration

### 4.1 版號

本規格需要**兩個 migration 檔**，分屬不同施工階段：

- S1：`V5__audit_trail.sql`
- S3：`V6__cash_sessions.sql`

> **版號注意：`V4` 已由 `docs/specs/G13-branch-menu-availability.md` 預約。** 若 G13 尚未合併而本規格先開工，請取**當下 migration 目錄中下一個未使用的版號**，不要照抄本文的數字，並在 PR 描述註明實際用了哪個版號。撞號要重跑整個資料庫。

### 4.2 `V5__audit_trail.sql`（S1）

```sql
ALTER TABLE audit_log ADD COLUMN branch_id VARCHAR(36);
ALTER TABLE audit_log ADD COLUMN actor_name VARCHAR(80) NOT NULL DEFAULT '';
ALTER TABLE audit_log ADD COLUMN summary VARCHAR(200) NOT NULL DEFAULT '';

CREATE INDEX idx_audit_created ON audit_log(created_at);
CREATE INDEX idx_audit_branch_created ON audit_log(branch_id,created_at);
CREATE INDEX idx_audit_action_created ON audit_log(action,created_at);

INSERT INTO role_permissions(role_code,permission)
  SELECT code,'AUDIT_VIEW' FROM roles WHERE code IN ('HQ','MANAGER');
```

**只加欄位，不改既有欄位。** 三個限制的理由：

- `branch_id` **可為 null**，因為總部層級的動作（改角色、改分店主檔）不屬於任何一店。**不加 FK**：稽核紀錄必須在分店被刪除後仍然留存，FK 會讓刪店變成不可能或連帶刪稽核，兩個都是錯的
- `actor_name` / `summary` 用 `NOT NULL DEFAULT ''`，既有列自動補空字串。這個寫法在 H2（PostgreSQL 相容模式，測試用）與 PostgreSQL（正式）都成立
- **不動 `target_id VARCHAR(80)` 的型別。** `ALTER COLUMN ... TYPE` 的語法在 H2 與 PostgreSQL 之間有差異，而 CI 只跑得到 H2，改壞了要到正式環境才會發現。見下一節的 `target_id` 使用規則

### 4.3 `target_id` 的使用規則（G13 踩過的坑，不要重蹈）

`target_id` 是 `VARCHAR(80)`，**只放單一主鍵**，不放複合鍵、不做字串拼接。

G13 規格第 7.1 節記錄過一次實際事故：用 `branchId + ":" + productId` 當 `target_id`，兩個 UUID 加分隔符是 83 字元，超出 `VARCHAR(80)`，**整筆設定連同業務寫入一起回滾**——稽核把正事弄掛了。

規則：

- `target_id`：單一主鍵（訂單 id 20 字元、帳號／商品／分店 id 36 字元、角色 code 40 字元，全部安全）
- 需要第二個識別碼或前後值時，**一律寫進 `summary`**（`VARCHAR(200)`）
- `AuditService` 寫入前要**自行截斷 `summary` 到 200 字元**，不要靠資料庫擋。稽核失敗絕對不可以讓業務交易回滾（見第 5.3 節）

### 4.4 `V6__cash_sessions.sql`（S3）

```sql
CREATE TABLE cash_sessions(
  id VARCHAR(36) PRIMARY KEY,
  branch_id VARCHAR(36) NOT NULL REFERENCES branches(id),
  status VARCHAR(8) NOT NULL CHECK(status IN ('OPEN','CLOSED')),
  opening_float INTEGER NOT NULL CHECK(opening_float>=0),
  opened_by VARCHAR(36) NOT NULL,
  opened_at BIGINT NOT NULL,
  closed_by VARCHAR(36),
  closed_at BIGINT,
  counted_amount INTEGER CHECK(counted_amount>=0),
  expected_amount INTEGER,
  variance INTEGER,
  note VARCHAR(200) NOT NULL DEFAULT ''
);

CREATE UNIQUE INDEX idx_cash_sessions_open ON cash_sessions(branch_id) WHERE status='OPEN';
CREATE INDEX idx_cash_sessions_branch_opened ON cash_sessions(branch_id,opened_at);

ALTER TABLE orders ADD COLUMN cash_session_id VARCHAR(36) REFERENCES cash_sessions(id);
CREATE INDEX idx_orders_cash_session ON orders(cash_session_id);

INSERT INTO role_permissions(role_code,permission)
  SELECT code,'CASH_SESSION' FROM roles WHERE code IN ('HQ','MANAGER','CASHIER');
```

**部分索引（`WHERE status='OPEN'`）是「一店同時只能有一個開啟中班別」的資料庫層保證。** PostgreSQL 支援部分唯一索引；H2 在 PostgreSQL 相容模式下**不支援**。

所以：

> **實作時先在 H2 上驗證這一行。** 如果 H2 拒絕，改成不帶 `WHERE` 的一般索引，並在 `openSession()` 用 `select ... for update` 鎖 `branches` 該列來序列化開班（見第 5.6 節）。**兩種做法都要保留 service 層的檢查**，不要把唯一性只交給索引。PR 描述請註明實際採用哪一種、H2 的實際行為是什麼。

`closed_by` / `closed_at` / `counted_amount` / `expected_amount` / `variance` 在 `OPEN` 期間為 null，交班時一次寫入。

`orders.cash_session_id` **可為 null**，理由見第 11.2 節設計決策。

### 4.5 角色權限 migration 與 `InitialData` 的關係（容易搞混，寫清楚）

Flyway 在 `InitialData`（`ApplicationRunner`）**之前**執行。所以：

- **全新資料庫**：V5／V6 執行時 `roles` 表是空的 → `INSERT ... SELECT` 一列都不插 → 隨後 `InitialData` 依 `Identity.PERMISSIONS` 建立角色，`HQ` 自動拿到全部新權限。`MANAGER` / `CASHIER` 的清單是寫死的，**必須手動加上新權限**
- **既有資料庫**：`roles` 已有資料 → migration 的 `INSERT ... SELECT` 補上授權 → `InitialData` 看到 `count(*) > 0` 直接跳過

兩條路都不會產生重複列，也都會拿到正確授權。`InitialData.java:37-47` 要改的是：

```java
role("CASHIER", "收銀員", "BRANCH",
    List.of("ORDER_CREATE", "POS_ORDER", "ORDER_MANAGE", "CASH_SESSION"));
role("MANAGER", "店長", "BRANCH",
    List.of("ORDER_CREATE", "POS_ORDER", "ORDER_MANAGE", "REPORT_STORE",
            "PAYMENT_RECONCILE", "AUDIT_VIEW", "CASH_SESSION"));
```

`HQ` 那一行不用改（它吃 `Identity.PERMISSIONS`）。

---

## 5. API、權限與資料範圍

### 5.1 新權限

`Identity.PERMISSIONS`（`Identity.java:7-18`）新增兩個常數：

| 權限 | 意義 | 角色 |
| --- | --- | --- |
| `AUDIT_VIEW` | 查詢稽核軌跡 | `HQ`（GLOBAL）、`MANAGER`（BRANCH） |
| `CASH_SESSION` | 開班、交班、查班別 | `HQ`、`MANAGER`、`CASHIER` |

**寫入稽核不需要權限**，它是業務動作的副作用，不是獨立功能。

### 5.2 `Audit` api（`coffee-audit`）

```java
public interface Audit {
  record Entry(
      String id, String actorId, String actorName, String action,
      String targetId, String branchId, String summary, long createdAt) {}

  record Page(List<Entry> items, String nextCursor) {}

  record Query(String action, String actorId, String branchId, Long from, Long to, String cursor, int limit) {}

  void record(Actor actor, String action, String targetId, String branchId, String summary);

  Page search(Actor actor, Query query);
}
```

`record(...)` 的 `actor` 可以是 `null`（系統動作，例如未來的排程作業）。`actor_id` 在 DB 是 `NOT NULL` 且**沒有 FK**，所以系統動作寫入常數字串 `"system"`，不要嘗試改欄位可空性。

### 5.3 稽核寫入絕對不可以讓業務交易失敗

**這是本規格最重要的一條實作約束。**

稽核是附加價值，業務動作是本體。一筆現金收款不可以因為「稽核字串太長」或「稽核索引衝突」而回滾——那等於為了記帳把收銀機弄壞了，比不記帳嚴重得多（G13 已經真的踩過一次，見第 4.3 節）。

`AuditService.record()` 的實作要求：

- 寫入前**自行截斷** `summary` 至 200 字元、`targetId` 至 80 字元、`actorName` 至 80 字元
- 整個寫入包在 `try/catch (RuntimeException)` 內，**吞掉例外**，不向外拋
- 例外發生時不要記錄任何金額或個資，只留一行不含 payload 的痕跡（沿用 `ReconciliationService.scheduled()` 的註解式處理風格）

> 這與 `AGENTS.md`「不確定不要默默猜」不衝突：這裡不是猜，是明確決定「稽核失敗時優先保住業務」。

**但截斷不是藉口：** 呼叫端要自己確保 `summary` 在正常情況下就在 200 字元內，截斷是最後一道防線，不是常態路徑。

### 5.4 稽核查詢端點

```
GET /api/audit?action=&actorId=&branchId=&from=&to=&cursor=&limit=
```

| 項目 | 規則 |
| --- | --- |
| 權限 | `AUDIT_VIEW` |
| 資料範圍 | `GLOBAL` → 可查全部，`branchId` 參數為選填篩選；`BRANCH` → **強制**只回傳 `branch_id = actor.branchId()` 的列，帶了別店的 `branchId` 一律 403；`SELF`（顧客）→ 一律 403 |
| `limit` | 預設 50，上限 200，超出上限一律夾到 200（不報錯） |
| 分頁 | **游標分頁**，不用 offset。游標是 `createdAt + ":" + id`，查詢條件為 `(created_at,id) < (?,?)`，排序 `order by created_at desc,id desc` |
| `from` / `to` | epoch millis，選填，`from` 含、`to` 不含 |
| 回應 | `{"items":[...],"nextCursor":"..."}`，沒有下一頁時 `nextCursor` 為 `null` |

**`BRANCH` 範圍看不到 `branch_id IS NULL` 的列。** 那些是總部層級動作（改角色、改分店主檔），店長沒有理由看到。這一條要有測試。

**為什麼是游標分頁而不是 offset**：`audit_log` 只增不改，offset 分頁在持續寫入時會漏列與重複列（第 2 頁的第一列可能已被新資料擠成第 1 頁的最後一列）。游標分頁對 append-only 表是正確解，而且成本一樣。這也順手示範了 G10 想要的分頁形狀。

### 5.5 現金班別端點

| 方法 | 路徑 | 權限 | 說明 |
| --- | --- | --- | --- |
| `POST` | `/api/cash-sessions` | `CASH_SESSION` | 開班。body `{"branchId":"...","openingFloat":2000,"note":""}` |
| `GET` | `/api/cash-sessions/current?branchId=` | `CASH_SESSION` | 取得該分店目前開啟中的班別，沒有時回 `null` 而**不是 404** |
| `POST` | `/api/cash-sessions/{id}/close` | `CASH_SESSION` | 交班。body `{"countedAmount":8450,"note":""}` |
| `GET` | `/api/cash-sessions/{id}` | `CASH_SESSION` | 班別明細（含金額拆解） |
| `GET` | `/api/cash-sessions?branchId=&from=&to=&cursor=&limit=` | `CASH_SESSION` | 歷史班別，分頁規則同 5.4 |

資料範圍：`BRANCH` 的 actor 一律只能操作與查詢自己分店（`a.branch(branchId)`）；`GLOBAL` 可跨店但 `branchId` 為**必填**（總部不會有「當下這一店」的概念，讓它猜是錯的）。`SELF`（顧客）全部 403。

### 5.6 開班

```
POST /api/cash-sessions  {"branchId":"taipei","openingFloat":2000,"note":"早班"}
```

1. `a.require("CASH_SESSION")`；`Problem.check(!a.customer(), ...)`；`a.branch(branchId)`
2. `openingFloat` 驗證：`0 <= openingFloat <= 1000000`，**整數元**
3. 序列化檢查：該分店已有 `status='OPEN'` 的班別 → `409 此分店已有開啟中的班別，請先交班`
4. 寫入 `cash_sessions`，`status='OPEN'`，`opened_by = a.id()`，`opened_at = now`
5. 寫稽核：`action="CASH_OPEN"`、`targetId=` 班別 id、`branchId=` 分店、`summary="開班準備金 2000 元"`

**第 3 步的競態**：兩個收銀員同時按開班，兩邊的檢查都看到「沒有開啟中的班別」，然後各插一列。防法依第 4.4 節：

- H2 支援部分唯一索引 → 靠索引擋，第二筆插入失敗，捕捉後轉成 `409`
- H2 不支援 → 在檢查前 `select id from branches where id=? for update` 鎖住該分店列，把同一分店的開班序列化

**不要只靠 service 層的 if 判斷。** 這是先讀後寫，沒有鎖就一定有競態窗口（G13 規格 §5.5 記過同型的問題）。

### 5.7 收現金時綁班別

`OrderService.cash()`（`OrderService.java:173-189`）在既有邏輯**之後**追加：

```java
// 既有：狀態檢查、金額檢查、update orders set status='PAID',...
```

改為在同一個 `update` 內帶上 `cash_session_id`，取值為「該訂單分店當下 `status='OPEN'` 的班別 id，沒有則 `null`」。

**這一查詢在 `cash()` 已持有訂單行鎖（`lock(id)`）之後執行**，且只讀 `cash_sessions`，不會與開班互鎖（開班鎖的是 `branches`）。

追加稽核：`action="ORDER_CASH"`、`targetId=` 訂單 id、`branchId=` 訂單分店、`summary="現金收款 320 元，實收 500 元，找零 180 元"`。

**沒有開啟中的班別時 `cash()` 照樣成功**，`cash_session_id` 留 null。理由見第 11.2 節。

### 5.8 交班

```
POST /api/cash-sessions/{id}/close  {"countedAmount":8450,"note":""}
```

1. 權限與範圍檢查同 5.6
2. `select ... for update` 鎖住該班別列
3. `Problem.check(status.equals("OPEN"), "此班別已交班")` → 409
4. `countedAmount` 驗證：`0 <= countedAmount <= 10000000`
5. **後端計算**（見 5.9），寫入 `expected_amount`、`variance`、`counted_amount`、`closed_by`、`closed_at`、`status='CLOSED'`
6. 寫稽核：`action="CASH_CLOSE"`、`summary="應有 8500 元，實點 8450 元，短少 50 元"`

### 5.9 金額規則（後端計算，完全忽略前端）

**前端只能送 `openingFloat` 與 `countedAmount` 這兩個「人去數出來的數字」。其餘一律後端算。**

```
cashRevenue = SUM(orders.total)
              WHERE cash_session_id = ? AND payment_method = 'CASH' AND paid_at IS NOT NULL
expectedAmount = openingFloat + cashRevenue
variance       = countedAmount - expectedAmount      // 負數為短少，正數為溢收
```

實作要求：

- **新台幣整數元**，不使用 `double` / `float` / `BigDecimal` 小數
- 加總與相減用 `Math.addExact` / `Math.subtractExact`，不要裸算
- 用 `SUM(total)`，**不是** `SUM(tendered)`。抽屜實際淨收是 `tendered - change_amount`，恆等於 `total`；用 `tendered` 會把找零重複計算
- `CANCELLED` 訂單不需要特別排除：`transition()` 只允許從 `PENDING_PAYMENT` 取消，已收現金的訂單不可能變成 `CANCELLED`。**但仍要用 `paid_at IS NOT NULL` 當條件**，讓這個推論在未來 G03（退款退單）加入後不會默默失效
- 一次 `SUM` 查詢，不要撈出訂單再在記憶體加總

### 5.10 班別明細回應

```json
{
  "id": "...", "branchId": "taipei", "status": "CLOSED",
  "openingFloat": 2000, "cashRevenue": 6500, "orderCount": 23,
  "expectedAmount": 8500, "countedAmount": 8450, "variance": -50,
  "openedBy": "...", "openedByName": "王小明", "openedAt": 1758..., 
  "closedBy": "...", "closedByName": "王小明", "closedAt": 1758..., "note": ""
}
```

`OPEN` 中的班別也回這個形狀，但 `countedAmount` / `variance` / `closedAt` / `closedBy` 為 `null`，`cashRevenue` 與 `expectedAmount` **即時計算**（讓店員交班前就能看到應有金額）。

### 5.11 錯誤碼

| 狀況 | 碼 | 訊息（繁體中文，寫給終端使用者） |
| --- | --- | --- |
| 未登入 | 401 | （既有機制） |
| 無 `AUDIT_VIEW` / `CASH_SESSION` | 403 | `沒有此功能的操作權限`（`Actor.require` 既有訊息） |
| 跨店存取 | 403 | `只能存取所屬分店資料`（`Actor.branch` 既有訊息） |
| 班別不存在 | 404 | `找不到這個班別` |
| 該店已有開啟中班別 | 409 | `此分店已有開啟中的班別，請先交班` |
| 班別已交班 | 409 | `此班別已交班` |
| 金額超出範圍 | 400 | `金額不正確` |

> **`Problem.check` 只能用在回 400 的那幾列。** `Problem.check` 固定丟 400，404 與 409 要寫成 `throw new Problem(404, "...")` / `throw new Problem(409, "...")`。這一條是 G13 規格 v1.2 修正過的實際錯誤，不要再犯。

---

## 6. 施工階段

四個階段，**每個階段獨立 CI 綠、獨立可合併、有自己的驗收子集**。依 `AGENTS.md`「施工階段與中斷續作」推進，每完成一個階段就 push。

階段之間全部是**加法**：S1 不改任何既有行為，S2 只加寫入與新端點，S3 新增資料表與 nullable 欄位，S4 只加查詢與前端。任何一個階段停在那裡都是安全的。

### S1 — 稽核基礎建設（不改任何既有行為）

| 項目 | 內容 |
| --- | --- |
| 動到 | `backend/coffee-audit/**`（新模組）、`backend/pom.xml`、`coffee-app/pom.xml`、`coffee-identity/pom.xml`、`V5__audit_trail.sql`、`IdentityService.java`、`ModuleBoundariesTest.java` |
| 規格章節 | 第 3、4.2、4.3、5.2、5.3 節 |
| 規模 | 中偏小。新模組的 pom 設定是機械性的；唯一的邏輯是 `AuditService.record()` |

內容：建立 `coffee-audit` 模組與 `Audit` api、`AuditService`；V5 migration；把 `IdentityService` 的私有 `audit()` 改為呼叫 `Audit.record()`，**兩個既有 action 字串 `ACCOUNT_SAVE` / `ROLE_SAVE` 保持不變**，補上 `actorName` 與 `summary`。

**這一階段沒有任何新端點、沒有新權限、沒有任何使用者可見的變化。** 既有測試應該原封不動通過。

驗收子集：
- [ ] `ModuleBoundariesTest` 通過，且模組清單已含 `"audit"`
- [ ] 任何模組引用 `com.coffee.audit.internal..` 會 build fail（實作時自行驗證一次，不要留在程式碼裡）
- [ ] V5 在空資料庫與有資料的資料庫上都能執行
- [ ] `ACCOUNT_SAVE` / `ROLE_SAVE` 仍然寫得進去，`action` 字串不變
- [ ] `summary` 超過 200 字元時被截斷，**且業務動作照常成功**
- [ ] `AuditService.record()` 內部丟例外時，呼叫端的業務交易不回滾（用一個會爆的 stub 驗）

### S2 — 稽核涵蓋範圍與查詢 API（G11 完成）

| 項目 | 內容 |
| --- | --- |
| 動到 | `Identity.java`（`PERMISSIONS`）、`InitialData.java`、`AuditController.java`、`AuditService.java`、`OrderService.java`、`CatalogService.java`、`BranchService.java`、各模組 pom、前端稽核畫面、`docs/API.md` |
| 規格章節 | 第 5.1、5.4 節 |
| 規模 | 中。四處寫入點 + 一支帶游標分頁與資料範圍的查詢 |

驗收子集：
- [ ] `AUDIT_VIEW` 進入 `Identity.PERMISSIONS`，`HQ` / `MANAGER` 在**全新**與**既有**資料庫都拿得到
- [ ] `cash()`、`transition()`、`CatalogService.save()`、`BranchService.save()` 各寫一筆稽核，`branchId` 正確（總部層級動作為 null）
- [ ] `GET /api/audit` 游標分頁：連續翻頁不重複、不漏列，最後一頁 `nextCursor` 為 `null`
- [ ] `limit` 超過 200 被夾到 200，不報錯
- [ ] **越權測試**：`MANAGER` 帶別店 `branchId` → 403；`MANAGER` 不帶參數時回傳結果中**沒有任何** `branch_id` 為 null 或別店的列；顧客 → 403；無 `AUDIT_VIEW` 的角色 → 403
- [ ] 寫入端點都需要 CSRF token（用該帳號**有權限**的目標驗，無 token → 403、有 token → 2xx，不要用跨店目標驗 —— 見 G01a 第 3 節的教訓）

### S3 — 現金班別資料層與開關班（G15 前半）

| 項目 | 內容 |
| --- | --- |
| 動到 | `V6__cash_sessions.sql`、`Identity.java`、`InitialData.java`、`coffee-orders` 的 `CashSessionService` / controller / `Orders` api、`OrderService.cash()` |
| 規格章節 | 第 4.4、4.5、5.5、5.6、5.7、5.8、5.9 節 |
| 規模 | 中。核心是開班的併發控制與交班的金額計算 |

驗收子集：
- [ ] `CASH_SESSION` 進入 `Identity.PERMISSIONS`，三個角色在全新與既有資料庫都拿得到
- [ ] 開班：重複開班回 409；**併發開班只有一個成功**（兩執行緒同時打，斷言 `cash_sessions` 中該店 `OPEN` 的列數為 1）
- [ ] `cash()` 在有開啟班別時綁上 `cash_session_id`；**沒有班別時照樣成功**且欄位為 null
- [ ] 交班：`expected = openingFloat + SUM(total)`，`variance = counted - expected`，短少為負、溢收為正
- [ ] 交班用 `SUM(total)` 而非 `SUM(tendered)`（造一筆 `tendered > total` 的訂單，斷言金額不受找零影響）
- [ ] 重複交班回 409
- [ ] **越權測試**：跨店開班／交班 → 403；顧客 → 403；無 `CASH_SESSION` → 403
- [ ] 既有的 `cash()` 相關測試全部原封不動通過

### S4 — 日結明細、歷史查詢與前端（G15 完成）

| 項目 | 內容 |
| --- | --- |
| 動到 | `CashSessionService`（明細與列表）、controller、前端 `modules/orders` 的開班／交班畫面、`modules/identity` 的稽核畫面、`docs/API.md` |
| 規格章節 | 第 5.10 節與前端 |
| 規模 | 中偏小。後端只剩投影與分頁，主要成本在前端兩個畫面 |

驗收子集：
- [ ] `GET /api/cash-sessions/{id}` 回傳第 5.10 節的完整形狀
- [ ] `OPEN` 中的班別即時算出 `cashRevenue` 與 `expectedAmount`，`variance` 為 null
- [ ] 歷史列表分頁規則與 `/api/audit` 一致
- [ ] 前端：開班／交班畫面顯示應有金額與短溢，**短溢用紅色標示且文字寫明「短少」或「溢收」**
- [ ] 前端金額顯示一律經 `shared/format.ts` 的 `money`
- [ ] `docs/API.md` 端點表包含本規格所有新端點

---

## 7. 測試要求

放在 `backend/coffee-app/src/test/java/com/coffee/app/`，新增 `AuditTrailTest` 與 `CashSessionTest`。

**每個新端點至少涵蓋**：正常路徑、權限不足、跨店越權、邊界值、重複或併發請求。

特別要求：

1. **業務規則優先寫不需要 Spring context 的單元測試**（`expected` / `variance` 的計算、游標編解碼），跑得快才有人跑
2. **併發開班**必須有真的併發測試（兩個執行緒），不是兩次循序呼叫
3. **稽核不可拖垮業務**：注入一個 `record()` 必定丟例外的 `Audit`，斷言 `cash()` 仍然成功且訂單確實變成 `PAID`
4. **游標分頁**：寫入 > `limit` 筆資料後連續翻頁，把所有頁的 id 收集起來，斷言「沒有重複」且「等於全集」
5. **資料範圍**：`MANAGER` 查稽核時，結果集合中不得出現 `branch_id` 為 null 或別店的列。用 `assertThat(...).allMatch(...)`，不要只斷言筆數
6. 既有 `CoffeeIntegrationTest` / `HttpWorkflowTest` / `ModuleBoundariesTest` / `CheckMacTest` / `ReconciliationTest` / `CatalogOptionsTest` 必須全數通過

---

## 8. 設計決策（PM / SA 定案）

依 `AGENTS.md`「設計決策的歸屬」，以下由 Claude 定案，不需 PO 確認。每一項附理由與推翻代價。

### 8.1 稽核獨立成模組 `coffee-audit`

**決定**：新增 Maven 模組，只依賴 `shared`。

**理由**：稽核是横切關注點，必須是相依圖的葉節點，否則遲早參與循環。另外兩個放法（塞 `shared` + `app`、塞 `identity`）的具體問題已列在第 3 節表格。

**推翻的代價**：收掉模組要搬 `Audit` 介面並改所有消費模組的 import 與 pom。機械性但涉及面廣。反向（現在不建、之後建）代價相同，所以先建。

### 8.2 `orders.cash_session_id` 可為 null，沒開班照樣能收現金

**決定**：`cash()` 在沒有開啟中班別時**不報錯**，`cash_session_id` 留 null；未歸班的現金在日結畫面獨立顯示。

**理由**：反過來做（強制先開班才能收錢）在現金控管上更嚴謹，但它會讓「店員忘記開班」變成**收銀機收不了錢**。一間收不了錢的店比一筆對不上的帳嚴重得多。而且那是破壞性變更，會弄紅所有既有的 `cash()` 測試，逼出一個又大又不可分割的階段。

留 null 並在畫面上標出來，讓它**看得見**而不是消失——這已經解決了 G15 的核心問題（「抽屜裡的錢跟系統對不對得起來」）。

**推翻的代價**：小。改成強制只要在 `cash()` 加一個 `Problem.check` 與對應的錯誤訊息，加上更新既有測試。真實營運跑一段時間、確認店員都會開班之後，可以用一個 config flag 漸進切換。

### 8.3 一店同時只有一個開啟中班別

**決定**：`cash_sessions` 對 `(branch_id, status='OPEN')` 唯一。

**理由**：多抽屜／多收銀台是真實需求，但它會讓 `cash()` 無法自動判斷這筆錢進了哪個抽屜——必須由前端指定收銀台，而前端指定的東西就要驗證、要有收銀台主檔、要有裝置綁定。那是一整個獨立缺口，不是本規格能順手做完的。

單抽屜假設下，`cash()` 能自動綁定，整條路徑不需要任何前端輸入，也就沒有信任前端的空間。

**推翻的代價**：中。要加收銀台主檔、`cash()` 的收銀台參數與驗證、前端的收銀台選擇。**但資料模型不用改**：`cash_sessions` 加一個 `register_id` 欄位，唯一索引改成 `(branch_id, register_id)` 即可。所以這個決定不會把未來鎖死。

### 8.4 游標分頁而非 offset 分頁

**決定**：`/api/audit` 與 `/api/cash-sessions` 都用 `(created_at, id)` 游標。

**理由**：`audit_log` 是 append-only 且持續寫入，offset 分頁在翻頁過程中會漏列與重複列。成本與 offset 相同。

**推翻的代價**：低，但沒有理由推翻。這個形狀之後 G10（訂單清單分頁）可以直接照抄。

### 8.5 稽核寫入失敗時吞掉例外

**決定**：`AuditService.record()` 捕捉所有 `RuntimeException` 並吞掉。

**理由**：見第 5.3 節。G13 已經有一次「稽核字串超長導致業務整筆回滾」的實際事故。

**推翻的代價**：低。但推翻前要先想清楚「稽核寫不進去時，業務應該停擺嗎」——對咖啡廳的現金收款，答案明確是否。若之後要做合規等級的稽核（不可遺漏），正確做法是加 outbox 表與重送，不是讓業務失敗。

### 8.6 不做稽核紀錄的保留期限清理

**決定**：沿用 G01a §6 第 5 項的決定，不清理。

**理由**：資料量在單店單機營運下不是問題，而清理策略需要先知道法規保留年限與實際成長速率。現在訂的規則一定是猜的。

**推翻的代價**：低。資料量成為問題時併入 G09 的資料生命週期一起處理。

---

## 9. 待驗證的風險（不是待決事項）

**H2 對部分唯一索引（`CREATE UNIQUE INDEX ... WHERE`）的支援情況未經驗證。** 第 4.4 節已給出兩條路與判斷方式，實作時實測一次即可，不需要回報等待。PR 描述請寫明實際採用哪一條。

這是本規格唯一的技術未知。其餘都是既有模式的延伸。

---

## 10. 給 Codex 的提醒

- **不需要等確認**（見 `AGENTS.md`「設計決策的歸屬」）。輸出設計摘要留紀錄，然後直接開工
- **一次推進一個階段，做完就 push**。四個階段都獨立可合併，不要等全部做完才推
- 本規格與 `docs/specs/G13-branch-menu-availability.md` **動到的檔案幾乎不重疊**，唯一的交集是 `Identity.PERMISSIONS`、`InitialData` 的角色清單與 migration 版號。真的要並行時：**先確認 G13 用掉的版號**，本規格往後取；`PERMISSIONS` 那一行合併時注意不要覆蓋對方新增的常數
- 第 3 節末的「`cash_sessions` 為什麼放 `coffee-orders`」請先讀完再動手。放錯會直接造成循環相依，`ModuleBoundariesTest` 會 build fail，而且要退回來重做
- 第 5.3 節（稽核不可拖垮業務）與第 5.9 節（金額後端算）是本規格的兩條硬規則，其餘都可以依你的判斷調整實作形狀
- 規格有錯或不完整時**先講出來再繼續做**，寫在設計摘要與 PR 描述裡。不要停下來等回覆
- 實作回報寫到 `docs/reports/`

---

## 11. 附錄：action 字串清單

`audit_log.action` 是 `VARCHAR(40)`。本規格定義的字串：

| action | 觸發點 | `target_id` | `branch_id` |
| --- | --- | --- | --- |
| `ACCOUNT_SAVE` | `IdentityService`（既有，不改） | 帳號 id | 帳號所屬分店（可 null） |
| `ROLE_SAVE` | `IdentityService`（既有，不改） | 角色 code | null |
| `ORDER_CASH` | `OrderService.cash()` | 訂單 id | 訂單分店 |
| `ORDER_TRANSITION` | `OrderService.transition()` | 訂單 id | 訂單分店 |
| `PRODUCT_SAVE` | `CatalogService.save()` | 商品 id | null（菜單是全鏈的） |
| `BRANCH_SAVE` | `BranchService.save()` | 分店 id | 該分店 id |
| `CASH_OPEN` | 開班 | 班別 id | 該分店 id |
| `CASH_CLOSE` | 交班 | 班別 id | 該分店 id |

新增 action 時沿用 `<名詞>_<動詞>` 的大寫底線格式，並更新本表。
