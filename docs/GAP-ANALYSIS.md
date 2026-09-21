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
| G13 | 分店菜單可用性與售罄 | 實作已合併（PR #20，2026-09-20） | [`specs/G13-branch-menu-availability.md`](specs/G13-branch-menu-availability.md) |

**G06 商品選項模型與加價** —— `order_items.temperature` / `sugar` 是 `VARCHAR(12)` 自由字串，**完全不影響金額**（`OrderService.java:62` 的單價就是 `products.price`）。系統賣不了「加珍珠 +10」「換燕麥奶 +20」這類每天都在賣的加價品項，是唯一直接造成營收短收的缺口，且不依賴任何外部服務。另外 `validateOptions()`（`OrderService.java:100-111`）把分類字串 `"手作烘焙"` 與溫度／甜度的可選集合寫死在 `coffee-orders` 裡，但分類清單其實歸 `coffee-catalog` 管，總部新增分類就會讓點餐端的驗證默默失準。完整規格見 [`specs/G06-product-options.md`](specs/G06-product-options.md)。

**G13 分店菜單可用性與售罄**（第二次盤點新增）—— `products` 表沒有 `branch_id`，`CatalogService.sellable()`（`CatalogService.java:55-59`）只看全域 `active`。三家分店共用同一份菜單與同一組售價。後果：中山店可頌賣完，只能把可頌從**全鏈**下架；也無法做分店限定品項或區域定價。「今天這項賣完」是咖啡廳每天都在做的動作，目前系統做不到。完整規格見 [`specs/G13-branch-menu-availability.md`](specs/G13-branch-menu-availability.md)。

規格採「全鏈一份主檔 + 分店覆寫表（`branch_products`）」，沒有覆寫列時行為與現在完全相同，既有資料零遷移。售完標記記在台北營業日上，隔日自動失效，**不需要引入任何排程作業**。**區域定價（分店各自售價）刻意排除**，它會同時動到金額重算、成本快照與報表毛利，且與 G06 的加價計算相撞 —— 另立 **G18**（`G17` 已由 G06 第 13.6 節的「常用組合快捷」占用），排在 G06 與 G13 都合併之後。規格書第 11.3 節有完整理由。

**排程注意（已結案，保留為紀錄）：G06 與 G13 都會修改 `Catalog.sellable()` 的簽章與 `OrderService.create()` 的品項迴圈，不要同時開工。** G06 已於 2026-09-18 隨 PR #14 合併解除閘門，**G13 的實作已於 2026-09-20 隨 PR #20 合併進主線**（S1/S2/S3 三階段，Flyway 實際占用 `V4__branch_menu_availability.sql`，進度報告見 [`reports/G13-branch-menu-availability.md`](reports/G13-branch-menu-availability.md)）。

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
| G11 | 稽核紀錄的查詢與涵蓋範圍 | 規格書（與 G15 合併為一份）已合併（PR #17，v1.2）；**實作已合併（PR #22，S1／S2）** | [`specs/G11-G15-audit-and-cash-sessions.md`](specs/G11-G15-audit-and-cash-sessions.md) |
| G15 | 現金日結與交班 | 規格書（與 G11 合併為一份）已合併（PR #17，v1.2）；**實作已合併（PR #22，S3／S4）** | [`specs/G11-G15-audit-and-cash-sessions.md`](specs/G11-G15-audit-and-cash-sessions.md) |
| G10 | 訂單清單分頁與 N+1 | 實作已合併（PR #26，2026-09-21） | [`specs/G10-order-list-pagination.md`](specs/G10-order-list-pagination.md) |
| G14 | 分店營業時間 | **規格書已完成，待實作** | [`specs/G14-branch-business-hours.md`](specs/G14-branch-business-hours.md) |
| G07 | 訂單折扣與優惠碼 | **規格書已完成，待實作** | [`specs/G07-order-discounts.md`](specs/G07-order-discounts.md) |

**G11 稽核紀錄** —— `audit_log` 表存在，但全專案**只有 `IdentityService.java:221` 一處寫入**，且沒有任何查詢端點。等於有稽核資料卻無法稽核。現金收款（`OrderService.cash()`）、訂單狀態轉換（`transition()`）、菜單改價（`CatalogService.save()`）、分店改設定（`BranchService.save()`）全部沒有紀錄。金額相關操作都應該進稽核軌跡。

**G15 現金日結與交班**（第二次盤點新增）—— `cash()` 有記 `tendered` 與 `change_amount`，但沒有班別、沒有抽屜結算、沒有短溢比對。收了一整天現金，系統無法回答「抽屜裡的錢跟系統對不對得起來」。這是純內部的金錢控管缺口，與綠界完全無關，不受金流延後影響。**建議與 G11 一起設計**，兩者共用稽核基礎。

**G11 與 G15 寫成一份規格**，[`specs/G11-G15-audit-and-cash-sessions.md`](specs/G11-G15-audit-and-cash-sessions.md)，**已於 2026-09-19 隨 PR #17 合併進主線（規格版本 v1.2，Codex 核准）**。寫成一份的理由不是湊在一起，而是 G15 的每一個動作（開班、點鈔、交班、短溢）本身就是必須進稽核軌跡的金錢動作 —— 先做 G15 再回頭補稽核，等於要把剛寫好的三個 service 方法再改一次。規格切成四個施工階段（S1/S2 為 G11，S3/S4 為 G15），階段之間全部是加法，任何一段單獨合併都不破壞既有行為。

規格新增一個模組 `coffee-audit`（只依賴 `shared`，是相依圖的葉節點，不可能參與循環）；**`cash_sessions` 刻意放在 `coffee-orders` 而非獨立模組** —— 交班要讀 `orders` 算金額、`cash()` 要寫 `orders.cash_session_id`，雙向互動放獨立模組會直接造成循環相依。規格第 3 節有完整推導。

**G10 訂單清單分頁與 N+1** —— `OrderService.list()` 的 `order by created_at desc limit 100` 是寫死的，超過 100 筆的歷史訂單在 UI 上完全看不到，也沒有日期篩選或分頁參數。**比第一次盤點記載的更嚴重**：`.map(this::snapshot)` 對每一筆再打兩次 DB（訂單 + 品項），一次列表等於 201 次查詢。**G06 合併後又更糟** —— `snapshot()` 還會對每一個品項各打一次 `order_item_options`，100 筆 × 每筆 3 項 = 501 次。第三個缺陷是前端的狀態分頁籤與搜尋框篩的只是「抓回來的那 100 筆」，不是全集。

完整規格見 [`specs/G10-order-list-pagination.md`](specs/G10-order-list-pagination.md)：游標分頁（排序鍵與游標格式與 G11 稽核查詢同形）、篩選全部下推後端、一頁固定 3 次查詢（表頭 + 品項批次 + 選項批次），切成三個施工階段，S1／S2 純加法、破壞性變更集中在很小的 S3。規格順手結清 G01a 留下的兩項非阻斷觀察（`Orders.reconciliationCandidates()` 的 `items()` 註解、`scope.replace` 字串替換），因為本規格正好動到那兩個檔案。**閘門已解除** —— G11+G15 已於 2026-09-20 隨 PR #22 整份合併，兩份規格共同修改 `OrderService.java` 的衝突風險不再存在。

**G14 分店營業時間**（第二次盤點新增）—— `BranchService.requireOpen()`（`BranchService.java:40-45`）只檢查 `active` 布林，`branches` 表也沒有任何時間欄位。凌晨三點照樣能下單，店家只能靠手動切換 `active` 當開關門開關。

寫規格時發現問題比盤點記載的深一層：`active` 被挪用成每日開關，會連帶擋掉帳號管理 —— `IdentityService.saveAccount()`（`IdentityService.java:93`）也呼叫 `requireOpen()`，`active=false` 期間總部無法新增或調整該分店的員工帳號，錯誤訊息還是「分店已暫停營業」。**因此規格明文禁止把時段檢查加進 `requireOpen()`**，改為新增 `requireOrderable(id, atEpochMs)`，既有方法一個字都不動（規格 §5.7）。

完整規格見 [`specs/G14-branch-business-hours.md`](specs/G14-branch-business-hours.md)：新增 `branch_hours` 表（一列一段，天然支援分段與跨夜營業），**沒有任何時段列 = 24 小時營業**，既有資料零遷移；時段只對顧客自助下單強制，員工 POS 不擋（§11.1）；不新增任何權限常數，因此不需要角色 migration。三個施工階段，S1／S2 純加法，唯一的行為變更集中在很小的 S3。**例外日／公休日刻意排除，另立 G19**（規格 §11.3）。

**G07 訂單折扣與優惠碼** —— 沒有任何折扣機制：無優惠券、無會員價、無買一送一、無員工價。`orders.total` 是純加總（`OrderService.java:63-75`），之後不再變動。後果不只是「做不了促銷」：店員遇到「這杯算你便宜 20」只能在系統外少收現金，**那筆差額會在 G15 的現金日結被誤判成店員短收**；報表的 `grossProfit = revenue - cost` 也因為折扣不進系統而失真。

完整規格見 [`specs/G07-order-discounts.md`](specs/G07-order-discounts.md)：折扣是最容易寫出「信任前端傳來金額」漏洞的地方，所以規格刻意把範圍壓到**一張訂單最多一個、只作用在訂單小計、只由優惠碼觸發**的最小形狀 —— `Create` 不得有任何金額欄位，前端只送碼，折抵由後端重算（規格 §6.1 列為紅線）。`order_discounts` 以 `order_id` 當主鍵，讓「不可疊加」由資料表形狀強制而不是靠程式紀律。`orders` 只加 `discount_amount INTEGER NOT NULL DEFAULT 0` 一欄（小計 = `total + discount_amount`，既有資料零遷移）。三個施工階段，S1／S2 純加法，唯一的行為變更集中在 S3，且「沒帶碼就完全照舊」。**Flyway 用 V9**（V8 由 G14 占用，理由見規格 §4.0）。**不新增權限常數**，沿用 `MENU_MANAGE` + 總部範圍，因此不需要角色 migration。

寫規格時撞到一條 V1 留下的硬限制：`orders.total` 有 `CHECK(total>0)`，而 `AGENTS.md` 禁止修改既有 migration，匿名 CHECK 又沒有 H2 / PostgreSQL 兩邊都可靠的移除寫法 —— **因此折後金額下限定為 1 元，百分比折扣上限 90%，不支援免費訂單**（規格 §11.5）。品項層折扣／買一送一登記為 **G20**、會員價與員工價登記為 **G21**（規格 §11.2）。

### P2 — 規模與體驗

| 編號 | 缺口 | 狀態 |
| --- | --- | --- |
| G09 | 報表效能（月報記憶體彙整） | 未開始 |
| G16 | 顧客自助註冊 | 未開始 |
| G05 | Session 集中化（水平擴展前提） | 未開始 |
| G08 | 庫存扣減 | 未開始 |
| G17 | 點餐 UI 的「常用組合」快捷 | 未開始（G06 第 13.6 節登記） |
| G19 | 分店例外營業日（公休、臨時調整） | 未開始（G14 §11.3 登記） |
| G20 | 品項層折扣與買一送一 | 未開始（G07 §11.2 登記） |
| G21 | 會員價與員工價 | 未開始（G07 §11.2 登記） |
| G12 | 外送、硬體印單 | 未開始 |

**G09 報表效能** —— `ReportService.report()` 把整月已付款訂單投影載入記憶體，再對每一天（`ReportService.java:66-80`）與每一小時（`137-146`）各做一次 stream filter，是 O(天數 × 訂單數)。單店資料量下沒問題，跨店或資料累積後會變成記憶體與延遲風險。彙整應下推到 SQL。

**G16 顧客自助註冊**（第二次盤點新增，原混在 G12 內）—— 帳號只能透過 `ACCOUNT_MANAGE` 建立（`IdentityService.java:64`），沒有對外的註冊端點。線上點餐等於要總部幫每一位顧客開帳號。要不要開放對外註冊是產品決策（涉及濫用防護、驗證信、個資），但目前的狀態讓顧客點餐流程實質上只能用於展示。

**G05 Session 集中化** —— 單一實例的 `HttpSession`，應用重啟就全部登出，也無法水平擴展。登入限流同樣是單機記憶體。`ARCHITECTURE.md` 已點名需要 Spring Session / Redis。在單店單機營運下可接受，多店或需要零停機部署時就是硬阻擋。

**G08 庫存扣減** —— `products` 沒有任何庫存欄位。注意 G13（售罄）是 G08 的輕量版：先做得到「今天這項賣完」，不必等完整的庫存管理。

**G17 點餐 UI 的「常用組合」快捷**（G06 第 13.6 節登記）—— 選項群組多的商品，手機版點餐流程會偏長。刻意延後：常用組合要先有真實訂單資料才知道哪些組合常用，現在做出來的一定是猜的。G06 上線跑一段時間後再用實際資料判斷要不要做。

**G20 品項層折扣與買一送一** / **G21 會員價與員工價**（G07 §11.2 登記）—— G07 只做訂單層、只由優惠碼觸發的折扣。買一送一要決定「折的是哪一件」，那是品項層的規則引擎，會動到 `order_items` 快照結構與報表的品項營收歸屬；會員價與員工價要先有會員／員工身分模型（`accounts` 目前只有角色與分店）。兩者各自的規模都與 G07 相當，合進去會逼出一個切不開的大階段。**排在 G07 上線並累積一段真實 `order_discounts` 資料之後**，屆時看實際用了哪幾種促銷再決定要不要做 —— 現在做的一定是猜的（與 G17 同一個理由）。兩者都是加法，不需要回頭改 G07。

**G19 分店例外營業日**（G14 §11.3 登記）—— G14 只做每週固定時段，國定假日、臨時公休、提早打烊、颱風天全部不在範圍。例外日需要自己的資料表、日曆 UI 與優先序規則（例外覆蓋固定時段），規模與 G14 本身相當，合在一起會讓 G14 的 S1 失去「合併後零影響」的性質。現階段的替代方案是切 `active` 一天，不精緻但臨時公休是低頻事件。**資料形狀（一段時間 + 一個日期）與 `branch_hours` 同構，日後補一張 `branch_hours_overrides` 是純加法**，不必回頭改 G14。編號說明：`G17` 由 G06 第 13.6 節占用、`G18` 由 G13 第 11.3 節的區域定價占用，G19 是下一個未使用號。

## 排定的工作順序

1. ~~**Codex 依 `specs/G06-product-options.md` 實作選項模型與加價**~~ —— 已完成，PR #14 於 2026-09-18 合併（S1/S2/S3 三階段，進度報告見 [`reports/G06-product-options-progress.md`](reports/G06-product-options-progress.md)）
2. ~~**Codex 依 `specs/G01a-reconciliation-fixes.md` 修正 G01 留在主線的兩項缺陷**~~ —— 已完成，PR #15 於 2026-09-18 合併（S1/S2 兩階段，進度報告見 [`reports/G01a-reconciliation-fixes.md`](reports/G01a-reconciliation-fixes.md)）
3. ~~**Codex 依 `specs/G13-branch-menu-availability.md` 實作分店可用性與售罄**~~ —— 已完成，PR #20 於 2026-09-20 合併（S1/S2/S3 三階段，Flyway 占用 V4，進度報告見 [`reports/G13-branch-menu-availability.md`](reports/G13-branch-menu-availability.md)）
4. ~~**Claude 產出 G11 + G15 合併規格書（稽核軌跡與現金日結）**~~ —— 已完成，規格書 v1.2 於 2026-09-19 隨 [PR #17](https://github.com/choka1227/coffee_GPT6/pull/17) 合併（兩輪 `REQUEST_CHANGES` 後由 Codex 核准）。閘門解除
5. ~~**Codex 依 [`specs/G11-G15-audit-and-cash-sessions.md`](specs/G11-G15-audit-and-cash-sessions.md) 實作稽核軌跡與現金日結**~~ —— 已完成，PR #22 於 2026-09-20 合併（S1–S4 四階段全數完成，Flyway 占用 V5／V6，進度報告見 [`reports/G11-G15-audit-cash-sessions-progress.md`](reports/G11-G15-audit-cash-sessions-progress.md)）
6. ~~**Codex 依 [`specs/G10-order-list-pagination.md`](specs/G10-order-list-pagination.md) 實作訂單清單分頁、篩選與 N+1 修正**~~ —— 已完成，[PR #26](https://github.com/choka1227/coffee_GPT6/pull/26) 於 2026-09-21 合併（S1–S3 三階段全數完成，Flyway 實際占用 V7，進度報告見 [`reports/G10-order-list-pagination-progress.md`](reports/G10-order-list-pagination-progress.md)）。審查曾以缺測試退回一輪，補齊後合併。**`codex/g10-order-pagination` 分支已完成任務，不要再從它續作或開新分支**
7. **← 目前這一項：Codex 依 [`specs/G14-branch-business-hours.md`](specs/G14-branch-business-hours.md) 實作分店營業時間** —— 無前置相依，閘門已解除（G10 已合併）。三個施工階段（S1 資料層與判定、S2 維護 API 與總部 UI、S3 下單強制與顧客端顯示），S1／S2 純加法。**Flyway 用 V8**（V7 已由 PR #26 實際占用）。**規格 §5.7 與 §12 第 1 條是硬性要求：不要把時段檢查加進 `requireOpen()`**
8. **Codex 依 [`specs/G07-order-discounts.md`](specs/G07-order-discounts.md) 實作訂單折扣與優惠碼** —— 依「一次一份」**排在 G14 之後**。與 G14 不相交（G14 動 `create()` 的分店查核，G07 動 `create()` 的金額計算與讀路徑），但兩者都改 `OrderService.create()` 與 `Orders.java` 的 `Create` record，**同時開工必然衝突**，序列化不是偏好而是必要。三個施工階段（S1 資料層與計算，含次數上限強制；S2 維護 API 與總部 UI；S3 下單套用與報表），S1／S2 純加法。**Flyway 用 V9**，即使開工時 V8 還沒進主線也不要改用 V8（理由見規格 §4.0）。**規格 §6.1 是紅線：`Create` 不得有任何金額欄位**
9. 金流那條線（G01–G04 其餘部分）待進入綠界串接階段再排

### 排程注意

PR #9（G01 對帳）已於 2026-09-17 08:32 合併，`OrderService` 的衝突風險解除，**G06 可以直接開工**。G06 的 Flyway 版號用 **V3**：`V2__payment_reconciliation.sql` 已隨 #9 進入主線。

G01 留在主線的缺陷已寫成獨立規格 [`specs/G01a-reconciliation-fixes.md`](specs/G01a-reconciliation-fixes.md)，用分支 `codex/g01-fixes`，**不要**夾在 G06 的 PR 裡 —— 兩件事、兩支分支。兩者動的是不同模組（`coffee-payments` vs `coffee-orders` / `coffee-catalog`），可以並行。**兩者皆已合併（#14、#15），此段保留為紀錄。**

**目前（2026-09-21）待實作的規格有兩份：G14（第 7 項，目前這一項）與 G07（第 8 項，待命），依序做。** G10 的實作已隨 PR #26 合併，G11+G15 已隨 PR #22 整份合併（S1–S4 全數完成），G13 已隨 PR #20 合併，**三項都沒有未解除的閘門**。Flyway 版號現況：`V1__coffee_schema.sql`、`V2__payment_reconciliation.sql`（#9）、`V3__product_options.sql`（#14）、`V4__branch_menu_availability.sql`（#20）、`V5__audit_trail.sql`、`V6__cash_sessions.sql`（#22）、`V7__order_list_indexes.sql`（#26）已進主線，**G14 用 V8、G07 用 V9**。

**版號不得互換。** G07 就算先於 G14 開工也一樣用 V9 —— Flyway 預設不接受事後補插較小版號（out-of-order），G07 若占走 V8，G14 之後在既有資料庫上就無號可用。空一個版號的成本是零。

規格庫存維持 2 份（一份在做、一份待命）是刻意的上限：實作端一次只做一份，且一份可能跨多次執行，堆更多只會變成永遠做不完的清單。**庫存滿 2 份時不再產出新規格。**

> **設計決策（2026-09-19，依 PR #18 上 Codex 的 `REQUEST_CHANGES`）：G11+G15 序列化排在 G13 之後，不開放並行。**（G13 已於 2026-09-20 合併，這道閘門本身已履行完畢；決策保留，因為它定義的是「一次一份」這個通則，不只是 G13 這一次。）
> 理由：(1) 實作端只有 Codex 一個，`AGENTS.md`「施工階段與中斷續作」本來就是「一次執行推進一個階段、一個 PR 一支分支」，並行在現況下不存在可執行的意義；(2) 兩份規格的交集 `Identity.PERMISSIONS`、`InitialData` 角色清單與 Flyway 版號，正好是撞了就要整份重跑的那一類衝突，序列化把它降為零成本；(3) 本檔的「排定的工作順序」是實作端的唯一約束來源，同一份文件不能同時給出「必須等 G13」與「可並行」兩個答案。
> 推翻它的代價：若日後真要並行（例如多了第二個實作端，或 G13 長期卡住），要先改 `AGENTS.md` 的施工規則與本節的順序定義，並在兩份規格裡把 migration 版號與 `Identity.PERMISSIONS` 的分配方式明確切開 —— 不能只在本檔局部放寬。

## 修訂紀錄

| 日期 | 變更 |
| --- | --- |
| 2026-09-21 | G07 規格書 v1.1（依 PR #27 上 Codex 的 `REQUEST_CHANGES`）：v1.0 把「使用次數上限與兌換計數」單獨切成 S4，但 `max_redemptions` 從 S1 起就在 `Rule`／`save()` 裡、S2 的維護 UI 又把它開放給總部設定 —— **S2 或 S3 單獨合併進主線的期間，總部設 `max_redemptions=1` 的碼仍然無限可用、`redeemed_count` 永遠是 0**。這不是破壞既有行為，而是讓一個新開放的設定說謊，說謊的方向是促銷成本無上限。**決定：把 §5.2 第 6、8 步併進 S1（強制先於開放），原 S4 剩下的「UI 顯示已用／上限」併入 S2，階段數四變三**，驗收編號不動只改分組（17／18 移到 S1 並改為直接對 `apply()` 測，19 移到 S3 因為回滾必須有訂單才驗得到）。同批在規格 §9.0 寫下一般化規則供後續規格沿用：**一個設定欄位的「可設定」與「生效」必須在同一階段；可以切成「還沒有人用」，不可以切成「有人能設但不作用」** |
| 2026-09-21 | 登記 G07 規格書（[`specs/G07-order-discounts.md`](specs/G07-order-discounts.md)，v1.0）：訂單層折扣與優惠碼、一張訂單最多一個（由 `order_discounts.order_id` 主鍵強制）、後端依規則重算且 `Create` 不得有任何金額欄位、`orders` 只加 `discount_amount DEFAULT 0`（既有資料零遷移）；四個施工階段，S1／S2／S4 純加法。P1 表 G07 由「未開始」改為「規格書已完成，待實作」，工作順序新增第 8 項並把金流順延為第 9 項。**同批對齊 G10 實作隨 PR #26 合併後的現況** —— 主線這份仍寫「實作審查中」與「續作請留在 `codex/g10-order-pagination` 分支補測試」，照舊文字會讓實作端去續作一支任務已完成的分支，且誤以為 G14 還沒輪到：P1 表 G10 改為「實作已合併」，工作順序第 6 項標為完成、**第 7 項（G14）改標為目前這一項**，Flyway 現況把 V7 由「已定但未合併」改為已進主線。寫規格時撞到 V1 的 `orders.total CHECK(total>0)` 且 `AGENTS.md` 禁止改既有 migration，匿名 CHECK 又無跨 H2／PostgreSQL 可靠的移除寫法 —— **折後金額下限定為 1 元、百分比上限 90%，不支援免費訂單**（規格 §11.5）。品項層折扣／買一送一登記為 **G20**、會員價與員工價登記為 **G21**（P2 表） |
| 2026-09-21 | 把主線 merge 進 `claude/spec-g14` 解 `GAP-ANALYSIS.md` 衝突（本 PR 與已合併的 PR #23 都改 P1 表、工作順序與修訂紀錄，依 PR 描述的約定順序 #23 先合）。同批登記 G10 實作現況：P1 表 G10 改為「實作審查中（PR #26）」、工作順序第 6 項補上 PR 連結與「續作留在 `codex/g10-order-pagination` 分支」的指示，**G14 的 Flyway 由「預期 V8」改為確定的 V8**（V7 已由 PR #26 實際占用，實作端不必再判斷）。另依 PR #24 上 Codex 的 `REQUEST_CHANGES` 修正 G14 規格書的授權邊界矛盾，見同日下一列 |
| 2026-09-21 | G14 規格書 v1.1（依 PR #24 上 Codex 的 `REQUEST_CHANGES`）：原 §5.2／§5.4 與驗收 7 寫「`GET /api/branches/{id}/hours` 公開（與 `GET /api/branches` 同級）」，但主線 `SecurityConfiguration` 對所有 `/api/**` 一律 `authenticated()`，`GET /api/branches` 本身就不是公開端點 —— **「同級」這個前提從一開始就是錯的**。若照原文施工，實作端不改 `SecurityConfiguration`（S2 的檔案清單也沒列它）就必然回 401，驗收 7 永遠過不了。**決定：改為與 `GET /api/branches` 真正同級，即需要登入**，`SecurityConfiguration` 一個字不動 |
| 2026-09-20 | 登記 G14 規格書（[`specs/G14-branch-business-hours.md`](specs/G14-branch-business-hours.md)，v1.0）：每週固定營業時段、`branch_hours` 一列一段（天然支援分段與跨夜）、沒有時段列＝24 小時營業（既有資料零遷移）、時段只對顧客自助下單強制、不新增權限常數因此不需要角色 migration；三個施工階段，S1／S2 純加法。P1 表 G14 由「未開始」改為「規格書已完成，待實作」，工作順序新增第 7 項並把金流順延為第 8 項。**寫規格時發現 `requireOpen()` 被 `IdentityService.saveAccount()` 共用**（`IdentityService.java:93`），把時段檢查加進去會讓總部在非營業時間無法管理該分店帳號 —— 規格 §5.7 明文禁止，改為新增 `requireOrderable()`。例外日／公休日排除在外，登記為 **G19**（P2 表） |
| 2026-09-20 | G11+G15 實作隨 PR #22 合併後的登記對齊：P1 表 G11／G15 由「實作進行中（PR #22，draft）／S3-S4 尚未開始」改為「實作已合併」，工作順序第 5 項標為完成，G10 升為第 6 項並標為目前這一項（閘門解除），Flyway 現況補上 V5／V6 已占用。**同一批修正也在 PR #23 裡送出**（那支 PR 解主線衝突時一併處理），兩邊內容一致，先合的那支生效 |
| 2026-09-20 | 把主線 merge 進 `claude/spec-g10` 解 `GAP-ANALYSIS.md` 衝突（本 PR 與 PR #18 都改工作順序與修訂紀錄，依約定 #18 先合）。同批對齊 G11+G15 實作隨 PR #22 合併後的現況：主線這份仍寫「實作進行中（PR #22，draft）／續作不要另開分支」，但 PR #22 已合併、S1–S4 全數完成、V5／V6 已進主線 —— 照舊文字會讓實作端去續作一支已合併的分支，且誤以為 G10 仍被閘門擋住。P1 表 G11／G15 改為「實作已合併」，工作順序第 5 項標為完成、G10 升為第 6 項並標為目前這一項（閘門解除、Flyway 由「預期 V7」改為確定的 **V7**） |
| 2026-09-20 | 登記 G10 規格書（[`specs/G10-order-list-pagination.md`](specs/G10-order-list-pagination.md)，v1.0）：訂單清單游標分頁、篩選下推後端、一頁固定 3 次查詢；三個施工階段，S1／S2 純加法。P1 表 G10 由「未開始」改為「規格書已完成，待實作」，工作順序的 G10 那一項補上規格連結、閘門（G11+G15 合併後，兩份都改 `OrderService.java`）與 Flyway 預期版號 V7。規格順手納入 G01a 留下的兩項非阻斷觀察（`Orders.java` 註解、`scope.replace`），因為它正好動到那兩個檔案 |
| 2026-09-20 | G13 實作隨 PR #20 合併後的登記對齊（與本 PR 把主線 merge 進分支同批處理）：P0 表 G13 由「可開工」改為「實作已合併（PR #20）」、工作順序第 3 項標為完成；序列化閘門因此解除，第 5 項（G11+G15）改標為目前這一項，並載明 Codex 已在 `codex/g11-g15-audit-cash-sessions` / PR #22（draft）開工、續作不要另開分支；P1 表 G11 改標「實作進行中」、G15 標「S3/S4 尚未開始」；Flyway 版號現況補上 `V4__branch_menu_availability.sql`（#20）已占用，G11+G15 自 **V5** 起算（原本寫「取開工當下的下一個未使用版號」，現在版號已確定，直接寫死以免實作端再判斷一次） |
| 2026-09-19 | 依 PR #18 上 Codex 的 `REQUEST_CHANGES` 修正工作順序的自相矛盾：第 5 項原本同時寫「排在 G13 之後」與「必要時可與 G13 並行」，排程注意又寫「兩份都沒有閘門」，實作端會同時得到兩個相反答案。**統一為序列化**：第 5 項的閘門明寫「G13 整份規格合併進 `feature/init-project` 之後才開工」並刪掉「必要時可並行」，排程注意改寫為「依序做，一次一份」，並補上這項設計決策的理由與推翻代價 |
| 2026-09-19 | G11+G15 規格書隨 PR #17 合併進主線後的登記對齊：狀態由「審查／修訂中，尚未合併」改為「已合併，待實作」，規格書欄位由 PR 連結改回相對路徑（檔案現在真的在主線上了）；工作順序第 4 項標為完成，**刪掉「合併進主線之前不要依它開工」那句** —— 它已經反過來會擋住 Codex；新增第 5 項「Codex 實作 G11+G15」並把原第 5、6 項順延為 6、7；排程注意改記待實作規格為兩份（G13、G11+G15）。**第 5 項的開工條件以本表最上方同日那列的序列化決策為準** |
| 2026-09-19 | G11+G15 規格書 v1.2（依 PR #17 上 Codex 第二輪 `REQUEST_CHANGES`）：§5.3 的例外捕捉邊界在 `AuditWriter.write()` 方法內，蓋不到 `@Transactional(REQUIRES_NEW)` proxy 在方法返回後才執行的 commit，commit 階段的例外會穿過 `afterCommit()` 傳回業務呼叫端（業務已提交卻回報失敗，可能引發重試與重複操作）—— 改為由 `AuditService` 包住整個 `writer.write()` 呼叫，§7 第 6 項加驗收 (c)「由交易管理器在 commit 階段拋例外」；§5.12 宣告的全域鎖順序 `branches → cash_sessions → orders` 與 §5.7 的實際步驟（branch → order → 無鎖讀 session）矛盾，會誘使實作端加上無用的 `cash_sessions` 行鎖 —— 改寫為「一律先鎖 branch，正確性由該鎖單獨支撐」並取消「不得跳過中間層」 |
| 2026-09-18 | 依 PR #16 review 修正 G11 / G15 的登記：狀態改為「規格書審查／修訂中，尚未合併」，工作順序第 4 項退回未完成，規格書欄位由相對路徑改為 PR 連結（PR #17 合併前該路徑在主線上不存在，且規格仍在依 review 修訂）。**本檔可以先於 #17 合併** |
| 2026-09-18 | 登記 G11 + G15 合併規格書（PR #17）：稽核軌跡與現金日結，四個施工階段、六項設計決策定案 |
| 2026-09-18 | G01a 實作隨 PR #15 合併，狀態由「待實作」改為「已合併」；工作順序第 2 項標為完成、第 3 項（G13）標為目前這一項；記錄 PR #15 review 的三項非阻斷觀察與 `coffee-reporting` 缺 `api` package 的既有架構缺口；補上 Flyway 版號現況 |
| 2026-09-18 | G06 實作隨 PR #14 合併，狀態由「待實作」改為「已合併」；工作順序第 1 項標為完成、第 2 項標註 draft PR #15 進度；G13 的「等 G06 合併」閘門解除並註明 Flyway 用 V4、`Catalog.sellable()` 簽章以 PR #14 後的主線為準 |
| 2026-09-17 | 建立本檔；完成 G01 規格書 |
| 2026-09-17 | PO 授權設計決策給 Claude，規格書的「待 PO 決定」改為「設計決策」；G06 六項設計決策定案（負加價改為**不允許**）；新增 G17；完成 G01a 缺陷修正規格書 |
| 2026-09-17 | 完成 G13 規格書（分店菜單可用性與售罄）；記錄 G06 / G13 的施工順序相依；登記 G18（區域定價） |
| 2026-09-18 | G13 規格書 v1.2（依 PR #12 上 Codex 第二輪 `REQUEST_CHANGES`）：§5.4 的鎖定範例用 `Problem.check` 會固定回 400，與 §5.3 錯誤碼表、§10.4 驗收要求的 404 矛盾；改為明寫 `throw new Problem(404, ...)`，並在 §5.3 補上「`Problem.check` 只能用在 400 那幾列」的通則 |
| 2026-09-17 | G13 規格書修正兩項（v1.1，依 PR #12 上 Codex 的 `REQUEST_CHANGES`）：§7.1 稽核 `target_id` 以 UUID 計長為 83 字元、超出 `VARCHAR(80)` 會讓設定整筆回滾，狀態改由 `action` 承載；§5.5 `fromUnlisted` 是先讀後寫的授權判斷，需鎖 `products` 行以序列化，鎖 `branch_products` 在尚無覆寫列時無效 |
| 2026-09-17 | G13 規格書補上「施工階段」（S1/S2/S3）；第 11 節由「待 PO 決定」改為「設計決策」四項定案；區域定價編號由 G17 更正為 G18（G17 已由 G06 第 13.6 節占用） |
| 2026-09-17 | 第二次盤點。PO 決定金流整批延後至最後階段，G01–G04 降為 P3；新增 G13（分店菜單與售罄）、G14（營業時間）、G15（現金日結）、G16（顧客自助註冊）四項；G16 自 G12 拆出；G06 升為 P0 並完成規格書；補正 G10 的 N+1 問題與 G11 的實際涵蓋範圍 |
