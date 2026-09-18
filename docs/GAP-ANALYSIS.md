# 缺口盤點

盤點日期：2026-09-17（Asia/Taipei）。第二次盤點：2026-09-17，依據 `feature/init-project` 於 `16f801c` 的實際程式碼。盤點依據另含 `docs/ARCHITECTURE.md`、`docs/API.md`、`docs/ECPAY.md`、`docs/VALIDATION.md`。

本檔由 PM / SA（Claude）維護。**優先順序由 Claude 定案**（PO 於 2026-09-17 授權，見下節）。規格書寫在 `docs/specs/`，檔名對應缺口編號。

## PO 決策：設計決策授權給 Claude（2026-09-17）

**PO 已把設計決策整批授權給 Claude（PM / SA），不再人工介入設計。** 規則寫在 `AGENTS.md`「設計決策的歸屬」與 `CLAUDE.md`。對本檔的影響：

- 優先順序由 Claude 定案並直接排入，不再標「建議，最終由 PO 決定」
- 規格書不再有「待 PO 決定」章節，改為「設計決策」並附理由
- Codex 的產出有問題時，**由下一輪規格修補**，不停工等人

仍須 PO 決定的只剩 `CLAUDE.md` 列的那幾項：repo 設定、帳號與憑證、不可逆操作、`main` 分支。

## PO 決策：金流整批延後（2026-09-17）

**PO 決定金流相關功能一律先以假資料（stub）運作，等其他業務完成後，最後才進行綠界測試環境的實際串接與驗證。**

因此 G01 / G02 / G03 / G04 這一整條線**整批降到 P3**，不再是阻擋項。既有的 `docs/specs/G01-payment-reconciliation.md` 保持有效，但實作排程往後移。

這個決定改變了整份盤點的優先順序：原本的 P0 清空，**G06（選項模型與加價）遞補為第一順位**。

## 現況摘要

初版是一套可運作的模組化單體：登入 / 角色 / 權限、分店、菜單、點餐、現金收款、綠界線上付款、銷售報表都已實作並有測試覆蓋（`CoffeeIntegrationTest`、`HttpWorkflowTest`、`ModuleBoundariesTest`、`CheckMacTest`）。授權、金額後端重算、冪等下單、行鎖、綠界簽章驗證這些硬規則都有落實。

第一次盤點的結論是「缺口集中在營運面」。第二次盤點推翻了一半：**核心業務模型本身也有缺口**，而且是每天營業都會踩到的那種 —— 選項不影響金額、菜單沒有分店維度、分店沒有營業時間、收了現金沒有日結。這些都不依賴任何外部服務，可以立刻做。

## 優先順序

### P0 — 直接影響營收與日常營運

| 編號 | 缺口 | 狀態 | 規格書 |
| --- | --- | --- | --- |
| G06 | 商品選項模型與加價 | 實作已合併（PR #14，2026-09-18） | [`specs/G06-product-options.md`](specs/G06-product-options.md) |
| G13 | 分店菜單可用性與售罄 | **規格書已完成，G06 已合併，可開工** | [`specs/G13-branch-menu-availability.md`](specs/G13-branch-menu-availability.md) |

**G06 商品選項模型與加價** —— `order_items.temperature` / `sugar` 是 `VARCHAR(12)` 自由字串，**完全不影響金額**（`OrderService.java:62` 的單價就是 `products.price`）。系統賣不了「加珍珠 +10」「換燕麥奶 +20」這類每天都在賣的加價品項，是唯一直接造成營收短收的缺口，且不依賴任何外部服務。另外 `validateOptions()`（`OrderService.java:100-111`）把分類字串 `"手作烘焙"` 與溫度／甜度的可選集合寫死在 `coffee-orders` 裡，但分類清單其實歸 `coffee-catalog` 管，總部新增分類就會讓點餐端的驗證默默失準。完整規格見 [`specs/G06-product-options.md`](specs/G06-product-options.md)。

**G13 分店菜單可用性與售罄**（第二次盤點新增）—— `products` 表沒有 `branch_id`，`CatalogService.sellable()`（`CatalogService.java:55-59`）只看全域 `active`。三家分店共用同一份菜單與同一組售價。後果：中山店可頌賣完，只能把可頌從**全鏈**下架；也無法做分店限定品項或區域定價。「今天這項賣完」是咖啡廳每天都在做的動作，目前系統做不到。完整規格見 [`specs/G13-branch-menu-availability.md`](specs/G13-branch-menu-availability.md)。

規格採「全鏈一份主檔 + 分店覆寫表（`branch_products`）」，沒有覆寫列時行為與現在完全相同，既有資料零遷移。售完標記記在台北營業日上，隔日自動失效，**不需要引入任何排程作業**。**區域定價（分店各自售價）刻意排除**，它會同時動到金額重算、成本快照與報表毛利，且與 G06 的加價計算相撞 —— 另立 **G18**（`G17` 已由 G06 第 13.6 節的「常用組合快捷」占用），排在 G06 與 G13 都合併之後。規格書第 11.3 節有完整理由。

**排程注意：G06 與 G13 都會修改 `Catalog.sellable()` 的簽章與 `OrderService.create()` 的品項迴圈，不要同時開工。** G06 已於 2026-09-18 隨 PR #14 合併，這道閘門解除：**G13 現在可以從最新主線開分支開工**。G06 已占用 Flyway `V3__product_options.sql`，G13 用 **V4**。G13 規格書寫作時 `Catalog.sellable()` 尚未帶選項，實作前請以主線上 PR #14 之後的簽章為準。

### P3 — 金流（PO 決定延後，最後才串接）

| 編號 | 缺口 | 狀態 | 規格書 |
| --- | --- | --- | --- |
| G01 | 金流對帳與付款狀態修復 | 實作已合併（PR #9） | [`specs/G01-payment-reconciliation.md`](specs/G01-payment-reconciliation.md) |
| G01a | 對帳實作的缺陷修正 | 實作已合併（PR #15，2026-09-18） | [`specs/G01a-reconciliation-fixes.md`](specs/G01a-reconciliation-fixes.md) |
| G02 | 電子發票 | 延後 | — |
| G03 | 退款與退單 | 延後 | — |
| G04 | 線上付款訂單的取消與逾時處理 | 延後（與 G01 互斥設計，必須一起做） | — |

#### G01 的兩項缺陷已由 G01a 修正（PR #15，2026-09-18 合併）

PR #9 於 2026-09-17 08:32 由 PO 合併。Claude 在同一個 head SHA（`88452da`）送出過 `REQUEST_CHANGES`，PO 決定先合併，下列兩項因此留在 `feature/init-project` 上。**兩項均已由 PR #15（`codex/g01-fixes`，S1/S2 兩階段）修正並合併，此節保留為紀錄**：

1. **查無此筆交易被誤判成 `QUERY_FAILED`** —— `ReconciliationService.java:186-188` 把 `TradeAmt` / `TradeNo` 的驗證放在判斷 `TradeStatus` 之前，空的 `TradeNo` 直接丟例外收成 `QUERY_FAILED`。違反規格 §6「綠界查不到時照未付款處理，不是錯誤」與 §4「`provider_trade_no` 無值時填空字串」。顧客在導向綠界前放棄的訂單（`PENDING_PAYMENT` 的大宗）會整片顯示「查單失敗」，真正的連線異常被假告警淹沒。
   **修法**：見 [`specs/G01a-reconciliation-fixes.md`](specs/G01a-reconciliation-fixes.md) 第 2 節。

2. **CSRF 驗收測試無效** —— `ReconciliationTest.java:233-235` 有 csrf 與無 csrf 兩次 `POST` 都打跨店訂單、都期望 403，即使 CSRF 保護被關掉也照樣通過。
   **修法**：見 [`specs/G01a-reconciliation-fixes.md`](specs/G01a-reconciliation-fixes.md) 第 3 節。

另有三項非阻斷建議（`PaymentDate` 時鐘偏移會讓已付款訂單反覆 `QUERY_FAILED`、`pending()` 的無上限撈取與 N+1、報表台灣日期歸屬未真正驗到）也已納入 G01a 第 4 節，同樣隨 PR #15 完成。

**影響評估（已解除）**：因為 PO 已決定金流先跑 stub、最後才串接綠界，且 `ecpay.reconcile.enabled` 預設為 `false`，這兩項從未有生產影響；現已在進入綠界串接階段之前修掉。

**G01a 合併時留下的三項非阻斷觀察**（Claude 於 PR #15 的 review 記錄，不阻擋任何人，排進後續批次）：

1. `Orders.reconciliationCandidates()` 為了讓查詢次數與候選筆數無關，改成單次投影查詢，回傳的 `Order.items()` 固定為空 `List`。目前兩個呼叫端都只用到 id 與表頭欄位，所以正確；但這是 `api` 契約的語意變更且方法上沒有註解。**下次動到 `Orders.java` 時順手補一行註解**，避免未來有人拿它去讀 `items()`。
2. `OrderService.reconciliationCandidates()` 用 `scope.replace("branch_id", "o.branch_id")` 補表別名，字串替換偏脆，建議直接寫成 `" and o.branch_id=?"`。
3. G01a §4.2 的「每次呼叫的 DB 查詢次數與候選筆數無關」目前只有 code review 認定，沒有測試釘住（要釘住得包一層計數用的 DataSource proxy）。若日後再動 `pending()`，這點值得補。

另外記一項既有架構缺口：**`coffee-reporting` 沒有 `api` package**，只有 `internal`（`ReportController` / `ReportService`），違反 `AGENTS.md`「每個業務模組固定兩個 package」。ArchUnit 因 `coffee-app` 是組裝層例外而放行，所以 CI 不會紅。這不是 G01a 造成的，排進後續規格處理。

**G01 原始問題描述**

`EcpayService.callback()` 是目前**唯一**把 ECPAY 訂單從 `PENDING_PAYMENT` 推進到 `PAID` 的路徑。後端從未主動連線到綠界（主程式碼裡沒有任何 HTTP client，也沒有 `@Scheduled` / `@EnableScheduling`）。

所以只要那一則回呼沒送達——網路中斷、應用重啟、綠界重送次數用盡——**顧客的卡已經被扣款，訂單卻永遠停在未付款**，而且沒有任何人會知道。`docs/ECPAY.md` 自己也寫了：「對於付款通知遺失須由營運人員依綠界交易紀錄查核；未實作自動對帳前不應把這套初版視為無人值守的正式收款平台。」

這是唯一一個「已經在收真錢、但缺乏補救路徑」的缺口，所以排第一。

**G02 電子發票** —— 台灣營業人開立統一發票有法規義務。目前完全未實作，也沒有發票號碼、字軌、作廢或折讓的資料模型。正式營業前必須補上，但它高度依賴 PO 選定的加值中心（綠界電子發票 / 其他），技術選型未定前寫規格書意義不大。

**G03 退款與退單** —— `ARCHITECTURE.md` 明載「付款後退款與退單不在本版範圍」。訂單狀態機 `PAID → PREPARING → READY → COMPLETED` 是單向的，已付款訂單沒有任何回退路徑；`orders` 表也沒有記錄退款金額的欄位。做錯餐、顧客反悔、店家無法供餐時，現場只能在系統外處理現金，帳就對不起來。

**G04 線上付款訂單的取消與逾時處理** —— `OrderService.transition()` 只允許取消 `PENDING_PAYMENT` 且 `paymentMethod='CASH'` 的訂單。ECPAY 訂單一旦建立就無法取消（這是刻意的，避免與異步付款競爭），但也因此沒有出口：顧客放棄付款後訂單永遠掛在未付款清單裡。需要逾時自動作廢的規則，且必須與 G01 的對帳邏輯互斥，不能作廢掉一筆其實已經付款成功的訂單。**與 G01 是同一個設計，必須一起做。**

### P1 — 金錢控管與營運必要

| 編號 | 缺口 | 狀態 | 規格書 |
| --- | --- | --- | --- |
| G11 | 稽核紀錄的查詢與涵蓋範圍 | **規格書已完成（與 G15 合併為一份），待實作** | [`specs/G11-G15-audit-and-cash-sessions.md`](specs/G11-G15-audit-and-cash-sessions.md) |
| G15 | 現金日結與交班 | **規格書已完成（與 G11 合併為一份），待實作** | [`specs/G11-G15-audit-and-cash-sessions.md`](specs/G11-G15-audit-and-cash-sessions.md) |
| G10 | 訂單清單分頁與 N+1 | 未開始 | — |
| G14 | 分店營業時間 | 未開始 | — |
| G07 | 折扣與促銷 | 未開始 | — |

**G11 稽核紀錄** —— `audit_log` 表存在，但全專案**只有 `IdentityService.java:221` 一處寫入**，且沒有任何查詢端點。等於有稽核資料卻無法稽核。現金收款（`OrderService.cash()`）、訂單狀態轉換（`transition()`）、菜單改價（`CatalogService.save()`）、分店改設定（`BranchService.save()`）全部沒有紀錄。金額相關操作都應該進稽核軌跡。

**G15 現金日結與交班**（第二次盤點新增）—— `cash()` 有記 `tendered` 與 `change_amount`，但沒有班別、沒有抽屜結算、沒有短溢比對。收了一整天現金，系統無法回答「抽屜裡的錢跟系統對不對得起來」。這是純內部的金錢控管缺口，與綠界完全無關，不受金流延後影響。**建議與 G11 一起設計**，兩者共用稽核基礎。

**G11 與 G15 已合併為一份規格** [`specs/G11-G15-audit-and-cash-sessions.md`](specs/G11-G15-audit-and-cash-sessions.md)（2026-09-18，PR #17）。合併的理由不是湊在一起，而是 G15 的每一個動作（開班、點鈔、交班、短溢）本身就是必須進稽核軌跡的金錢動作 —— 先做 G15 再回頭補稽核，等於要把剛寫好的三個 service 方法再改一次。規格切成四個施工階段（S1/S2 為 G11，S3/S4 為 G15），階段之間全部是加法，任何一段單獨合併都不破壞既有行為。

規格新增一個模組 `coffee-audit`（只依賴 `shared`，是相依圖的葉節點，不可能參與循環）；**`cash_sessions` 刻意放在 `coffee-orders` 而非獨立模組** —— 交班要讀 `orders` 算金額、`cash()` 要寫 `orders.cash_session_id`，雙向互動放獨立模組會直接造成循環相依。規格第 3 節有完整推導。

**G10 訂單清單分頁與 N+1** —— `OrderService.list()` 的 `order by created_at desc limit 100` 是寫死的，超過 100 筆的歷史訂單在 UI 上完全看不到，也沒有日期篩選或分頁參數。**比第一次盤點記載的更嚴重**：`.map(this::snapshot)` 對每一筆再打兩次 DB（訂單 + 品項），一次列表等於 201 次查詢。

**G14 分店營業時間**（第二次盤點新增）—— `BranchService.requireOpen()`（`BranchService.java:36-40`）只檢查 `active` 布林，`branches` 表也沒有任何時間欄位。凌晨三點照樣能下單，店家只能靠手動切換 `active` 當開關門開關。

**G07 折扣與促銷** —— 沒有任何折扣機制：無優惠券、無會員價、無買一送一、無員工價。`orders.total` 是純加總。導入時要特別小心，折扣是最容易出現「信任前端傳來金額」漏洞的地方。**G06 完成後再做**，兩者都動到金額計算，同時做會撞在一起。

### P2 — 規模與體驗

| 編號 | 缺口 | 狀態 |
| --- | --- | --- |
| G09 | 報表效能（月報記憶體彙整） | 未開始 |
| G16 | 顧客自助註冊 | 未開始 |
| G05 | Session 集中化（水平擴展前提） | 未開始 |
| G08 | 庫存扣減 | 未開始 |
| G17 | 點餐 UI 的「常用組合」快捷 | 未開始（G06 第 13.6 節登記） |
| G12 | 外送、硬體印單 | 未開始 |

**G09 報表效能** —— `ReportService.report()` 把整月已付款訂單投影載入記憶體，再對每一天（`ReportService.java:66-80`）與每一小時（`137-146`）各做一次 stream filter，是 O(天數 × 訂單數)。單店資料量下沒問題，跨店或資料累積後會變成記憶體與延遲風險。彙整應下推到 SQL。

**G16 顧客自助註冊**（第二次盤點新增，原混在 G12 內）—— 帳號只能透過 `ACCOUNT_MANAGE` 建立（`IdentityService.java:64`），沒有對外的註冊端點。線上點餐等於要總部幫每一位顧客開帳號。要不要開放對外註冊是產品決策（涉及濫用防護、驗證信、個資），但目前的狀態讓顧客點餐流程實質上只能用於展示。

**G05 Session 集中化** —— 單一實例的 `HttpSession`，應用重啟就全部登出，也無法水平擴展。登入限流同樣是單機記憶體。`ARCHITECTURE.md` 已點名需要 Spring Session / Redis。在單店單機營運下可接受，多店或需要零停機部署時就是硬阻擋。

**G08 庫存扣減** —— `products` 沒有任何庫存欄位。注意 G13（售罄）是 G08 的輕量版：先做得到「今天這項賣完」，不必等完整的庫存管理。

**G17 點餐 UI 的「常用組合」快捷**（G06 第 13.6 節登記）—— 選項群組多的商品，手機版點餐流程會偏長。刻意延後：常用組合要先有真實訂單資料才知道哪些組合常用，現在做出來的一定是猜的。G06 上線跑一段時間後再用實際資料判斷要不要做。

## 排定的工作順序

1. ~~**Codex 依 `specs/G06-product-options.md` 實作選項模型與加價**~~ —— 已完成，PR #14 於 2026-09-18 合併（S1/S2/S3 三階段，進度報告見 [`reports/G06-product-options-progress.md`](reports/G06-product-options-progress.md)）
2. ~~**Codex 依 `specs/G01a-reconciliation-fixes.md` 修正 G01 留在主線的兩項缺陷**~~ —— 已完成，PR #15 於 2026-09-18 合併（S1/S2 兩階段，進度報告見 [`reports/G01a-reconciliation-fixes.md`](reports/G01a-reconciliation-fixes.md)）
3. **← 目前這一項：Codex 依 `specs/G13-branch-menu-availability.md` 實作分店可用性與售罄** —— 閘門已解除（G06 已合併），**沒有任何前置相依，從最新主線開 `codex/g13-*` 分支即可開工**。Flyway 用 V4
4. ~~Claude 產出 G11 + G15 合併規格書（稽核軌跡與現金日結）~~ —— 已完成，PR #17。**Codex 做完 G13 後接這一份**，四個施工階段（S1/S2 為 G11，S3/S4 為 G15），Flyway 取 G13 之後的下一個未使用版號
5. G10 訂單分頁與 N+1（小、確定，可穿插）
6. 金流那條線（G01–G04 其餘部分）待進入綠界串接階段再排

### 排程注意

PR #9（G01 對帳）已於 2026-09-17 08:32 合併，`OrderService` 的衝突風險解除，**G06 可以直接開工**。G06 的 Flyway 版號用 **V3**：`V2__payment_reconciliation.sql` 已隨 #9 進入主線。

G01 留在主線的缺陷已寫成獨立規格 [`specs/G01a-reconciliation-fixes.md`](specs/G01a-reconciliation-fixes.md)，用分支 `codex/g01-fixes`，**不要**夾在 G06 的 PR 裡 —— 兩件事、兩支分支。兩者動的是不同模組（`coffee-payments` vs `coffee-orders` / `coffee-catalog`），可以並行。**兩者皆已合併（#14、#15），此段保留為紀錄。**

**目前（2026-09-18）主線上沒有進行中的實作 PR，G13 是唯一待實作的規格，沒有任何閘門。** Flyway 版號現況：`V1__coffee_schema.sql`、`V2__payment_reconciliation.sql`（#9）、`V3__product_options.sql`（#14）已占用，**G13 用 V4**。

## 修訂紀錄

| 日期 | 變更 |
| --- | --- |
| 2026-09-18 | 完成 G11 + G15 合併規格書（PR #17）：稽核軌跡與現金日結，四個施工階段、六項設計決策定案；G11 / G15 狀態由「未開始」改為「規格書已完成，待實作」；工作順序第 4 項標為完成 |
| 2026-09-18 | G01a 實作隨 PR #15 合併，狀態由「待實作」改為「已合併」；工作順序第 2 項標為完成、第 3 項（G13）標為目前這一項；記錄 PR #15 review 的三項非阻斷觀察與 `coffee-reporting` 缺 `api` package 的既有架構缺口；補上 Flyway 版號現況 |
| 2026-09-18 | G06 實作隨 PR #14 合併，狀態由「待實作」改為「已合併」；工作順序第 1 項標為完成、第 2 項標註 draft PR #15 進度；G13 的「等 G06 合併」閘門解除並註明 Flyway 用 V4、`Catalog.sellable()` 簽章以 PR #14 後的主線為準 |
| 2026-09-17 | 建立本檔；完成 G01 規格書 |
| 2026-09-17 | PO 授權設計決策給 Claude，規格書的「待 PO 決定」改為「設計決策」；G06 六項設計決策定案（負加價改為**不允許**）；新增 G17；完成 G01a 缺陷修正規格書 |
| 2026-09-17 | 完成 G13 規格書（分店菜單可用性與售罄）；記錄 G06 / G13 的施工順序相依；登記 G18（區域定價） |
| 2026-09-18 | G13 規格書 v1.2（依 PR #12 上 Codex 第二輪 `REQUEST_CHANGES`）：§5.4 的鎖定範例用 `Problem.check` 會固定回 400，與 §5.3 錯誤碼表、§10.4 驗收要求的 404 矛盾；改為明寫 `throw new Problem(404, ...)`，並在 §5.3 補上「`Problem.check` 只能用在 400 那幾列」的通則 |
| 2026-09-17 | G13 規格書修正兩項（v1.1，依 PR #12 上 Codex 的 `REQUEST_CHANGES`）：§7.1 稽核 `target_id` 以 UUID 計長為 83 字元、超出 `VARCHAR(80)` 會讓設定整筆回滾，狀態改由 `action` 承載；§5.5 `fromUnlisted` 是先讀後寫的授權判斷，需鎖 `products` 行以序列化，鎖 `branch_products` 在尚無覆寫列時無效 |
| 2026-09-17 | G13 規格書補上「施工階段」（S1/S2/S3）；第 11 節由「待 PO 決定」改為「設計決策」四項定案；區域定價編號由 G17 更正為 G18（G17 已由 G06 第 13.6 節占用） |
| 2026-09-17 | 第二次盤點。PO 決定金流整批延後至最後階段，G01–G04 降為 P3；新增 G13（分店菜單與售罄）、G14（營業時間）、G15（現金日結）、G16（顧客自助註冊）四項；G16 自 G12 拆出；G06 升為 P0 並完成規格書；補正 G10 的 N+1 問題與 G11 的實際涵蓋範圍 |
