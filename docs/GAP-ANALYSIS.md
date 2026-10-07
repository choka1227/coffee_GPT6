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

~~另外記一項既有架構缺口：**`coffee-reporting` 沒有 `api` package**~~ —— **已結清，隨 G09（[PR #36](https://github.com/choka1227/coffee_GPT6/pull/36)）於 2026-09-28 合併**（見工作順序第 9 項：G09 的範圍本來就含「補 `coffee-reporting` 的 `api` package」）。現況是 `coffee-reporting/src/main/java/com/coffee/reporting/api/Reports.java` 與 `internal/{ReportController,ReportService}.java`，符合 `AGENTS.md`「每個業務模組固定兩個 package」。**本列保留為紀錄，不是待辦項** —— 2026-10-01 那一輪發現它在 G09 合併後漏改，差點據此再寫一份規格；日後結清缺口時，記得同時改掉它被登記的那幾個位置。

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
| G14 | 分店營業時間 | 實作已合併（PR #29，2026-09-21，Flyway 占用 V8） | [`specs/G14-branch-business-hours.md`](specs/G14-branch-business-hours.md) |
| G07 | 訂單折扣與優惠碼 | 實作已合併（PR #31，2026-09-28，Flyway 占用 V9）；規格書 v1.5。**驗收 21b／22／23 已由 G22 S3 自動化** | [`specs/G07-order-discounts.md`](specs/G07-order-discounts.md) |
| G09 | 報表彙整下推 SQL（原 P2，升為 P1） | 實作已合併（PR #36，2026-09-28，主線 SHA `ee9861c`，零 migration） | [`specs/G09-report-aggregation.md`](specs/G09-report-aggregation.md) |
| G22 | 前端測試基礎設施與可測純函式抽離（原 P2，升為 P1） | 實作已合併（PR #39，2026-09-29，零 migration） | [`specs/G22-frontend-test-infra.md`](specs/G22-frontend-test-infra.md) |
| G19 | 分店例外營業日（公休、臨時調整）（原 P2，升為 P1） | 實作已合併（PR #42，2026-09-30，Flyway 占用 V10） | [`specs/G19-branch-hour-overrides.md`](specs/G19-branch-hour-overrides.md) |
| G23 | 前端元件層測試（模板可見性）（原 P2，升為 P1） | 實作已合併（PR #45，2026-09-30，零 migration） | [`specs/G23-frontend-component-tests.md`](specs/G23-frontend-component-tests.md) |
| G25 | 最後點餐時間（last order）與即將打烊提示（原 P2，升為 P1） | **實作已合併（[PR #51](https://github.com/choka1227/coffee_GPT6/pull/51)，2026-10-01，Flyway 占用 V11）** | [`specs/G25-last-order-time.md`](specs/G25-last-order-time.md) |
| G26 | `OrderDiscountTest` 的月份時區時間彈（每月最後一天 16:00 UTC 起 CI 全紅） | 實作已合併（PR #48，2026-10-01，主線合併提交 `ee74608`，零 migration） | 無（一行修正，說明見下方 G26 段） |
| G27 | 無參數 `now()` 的自動化防線（G26 同類時間彈的回歸防護） | 實作已合併（[PR #53](https://github.com/choka1227/coffee_GPT6/pull/53)，2026-10-02 合併，主線合併提交 `62c7a4c`，零 migration、零正式程式變更） | 無（見下方 G27 段） |
| G24 | 店長自行設定本店例外營業日與本日最後點餐時間（原 P2，升為 P1） | **實作已合併（[PR #57](https://github.com/choka1227/coffee_GPT6/pull/57)，2026-10-03，主線合併提交 `d29939e`，Flyway 占用 `V12`）** | [`specs/G24-branch-manager-day-settings.md`](specs/G24-branch-manager-day-settings.md) |
| G08 | 分店每日可售數量與自動售完（原 P2「庫存扣減」，升為 P1） | **實作已合併（[PR #59](https://github.com/choka1227/coffee_GPT6/pull/59)，2026-10-03，主線合併提交 `cc7974b`，Flyway 占用 `V13`，兩張表）**；Claude 於 head `2ade6b6` 送出 `APPROVE` 後由 auto-merge 合入。S1–S4 四階段全數完成，**驗收 19a 除外**（規格 v1.2 新增，見 G08a） | [`specs/G08-branch-product-stock.md`](specs/G08-branch-product-stock.md) |
| G08a | G08 的剩餘徽章與「今日售完」徽章並存（規格 v1.1 未定義兩者關係造成） | **實作已合併（[PR #62](https://github.com/choka1227/coffee_GPT6/pull/62)，2026-10-04，主線提交 `4b85aa8`，零 migration、零後端變更）**；工作順序第 19 項已結案 | 無（G08 §13.13 與驗收 19a 已寫清楚） |
| G20 | 品項層促銷（買一送一、第二件半價、指定品項折扣）（原 P2，升為 P1） | **實作已合併（[PR #65](https://github.com/choka1227/coffee_GPT6/pull/65)，2026-10-04，主線合併提交 `e81fa44`，Flyway 占用 `V14`，兩張表）**；Claude 於 head `87f4a33` 送出 `APPROVE` 後由 auto-merge 合入。S1–S4 四階段全數完成 | [`specs/G20-item-level-promotions.md`](specs/G20-item-level-promotions.md) |
| G20c | 報表的淨營收歸屬與折抵口徑一致性（G20 §13.11「報表不改」留下的矛盾，加上審查 PR #65 發現的對帳投影別名缺陷） | **實作已合併（[PR #69](https://github.com/choka1227/coffee_GPT6/pull/69)，2026-10-05，主線合併提交 `fea0ef4`）**；S1–S3 三階段全數完成，零 migration，規格書 v1.2，進度報告見 [`reports/G20c-report-net-revenue.md`](reports/G20c-report-net-revenue.md) | [`specs/G20c-report-net-revenue.md`](specs/G20c-report-net-revenue.md) |
| G20a | 菜單與購物車的促銷提示（G20 §13.5「不自動把贈品加進購物車」與 §13.12「菜單不顯示促銷徽章」各自登記的同一個缺口：**顧客在決定買多少之前看不到促銷存在**，促銷因此付了成本卻換不到它要的行為） | **規格書 v1.0 已於 2026-10-04 隨 [PR #67](https://github.com/choka1227/coffee_GPT6/pull/67) 合併進主線（`55e6264`）；實作見 [PR #72](https://github.com/choka1227/coffee_GPT6/pull/72)，S1–S3 全部完成、CI 綠，Claude 於 head `2a84058` 送出 `APPROVE`，待 auto-merge**。零 migration。**開工前提（G20 合併）已滿足**（主線 `e81fa44`）。核心設計決策是「**前端不算折抵金額，只顯示規則條件**」，真實預估金額切出去成為 G20h | [`specs/G20a-promotion-hints.md`](specs/G20a-promotion-hints.md) |
| G20g | 報表圖表層的測試（ECharts option 的斷言方式；原 P2，升為 P1） | **實作已合併（[PR #75](https://github.com/choka1227/coffee_GPT6/pull/75)，2026-10-07，主線合併提交 `cd5801d`，零 migration、後端零變更）**；Claude 於 head `b703554` 送出 `APPROVE` 後由 auto-merge 合入。單一階段完成，規格書 v1.1，進度報告見 [`reports/G20g-report-chart-tests.md`](reports/G20g-report-chart-tests.md) | [`specs/G20g-report-chart-tests.md`](specs/G20g-report-chart-tests.md) |
| G20h | **後端購物車試算端點**（`POST /api/orders/preview`）：讓顧客在**送出訂單前**看到後端算出來的真實金額。今天顧客看得到「有促銷」卻看不到「所以我要付多少」—— 購物車的「總計」是**毛額**，比他實際被扣的多（原 P2，升為 P1） | **規格書 v1.2 於 2026-10-07 產出（v1.0 於 2026-10-05，v1.1／v1.2 同於 2026-10-07 依 Codex 的兩次審查修正），隨本輪 PR 進主線；實作未開始**。零 migration、零新依賴、不改建立訂單的任何欄位。**開工前提（G20 與 G20a 皆已合併）已於 2026-10-05 滿足**。核心設計決策是 **§13.1「試算與下單走同一段 `price()`」** —— 後端長出第二份計價比前端算更難發現，因為兩份都是 Java、都看起來很對。**最大的陷阱是 §5.1**：`DiscountService.apply` 會消耗一次兌換並取行鎖，直接重用會讓顧客瀏覽就把優惠碼用光 | [`specs/G20h-order-preview.md`](specs/G20h-order-preview.md) |

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
| G09 | 報表效能（月報記憶體彙整） | **已升為 P1，見上方 P1 表** |
| G16 | 顧客自助註冊 | 未開始 |
| G05 | Session 集中化（水平擴展前提） | 未開始 |
| G08 | 庫存扣減 | **已升為 P1，實作已於 2026-10-03 隨 [PR #59](https://github.com/choka1227/coffee_GPT6/pull/59) 合併，見上方 P1 表**（範圍收斂為「每日可售數量與自動售完」，永續庫存帳與選項層庫存另立 G28；規格書 v1.2） |
| G17 | 點餐 UI 的「常用組合」快捷 | 未開始（G06 第 13.6 節登記） |
| G19 | 分店例外營業日（公休、臨時調整） | **已升為 P1，見上方 P1 表**（G14 §11.3 登記；規格書 v1.1 於 2026-09-29 完成） |
| G20 | 品項層折扣與買一送一 | **已升為 P1，見上方 P1 表**（G07 §11.2 登記；規格書 v1.0 於 2026-10-04 完成，Flyway 占用 V14，實作已合併 [PR #65](https://github.com/choka1227/coffee_GPT6/pull/65)，主線合併提交 `e81fa44`）。升排理由與「為什麼推翻 §11.2 的『等真實資料』閘門」見規格 [§1.4](specs/G20-item-level-promotions.md) |
| G20c | 報表的淨營收歸屬與折抵口徑一致性 | **已升為 P1，實作已於 2026-10-05 隨 [PR #69](https://github.com/choka1227/coffee_GPT6/pull/69) 合併（主線合併提交 `fea0ef4`），見上方 P1 表**（G20 §14 登記；規格書 v1.2，零 migration） |
| G20a | 菜單與購物車的促銷提示（需要 `GET /api/promotions/active` 顧客端端點） | **已升為 P1，見上方 P1 表**（G20 §13.5／§13.12 登記；規格書 v1.0 於 2026-10-04 完成並已合併，零 migration；實作 PR #72 已 `APPROVE`，待 auto-merge） |
| G20b | 多規則疊加與單位消耗模型 | 未開始（G20 §13.2 登記）。**開工前提是要有真實促銷方案**，否則疊加優先序一定是猜的 |
| G20d | 選項層促銷（加料免費、第二份加料半價） | 未開始（G20 §2.2 登記） |
| G20f | 訂單層優惠碼折抵分攤到品項 | 未開始（G20c §13.2 登記）。**分攤演算法已寫好**放在 G20c 附錄 A，照著做即可；需要 `order_items.code_discount_amount` 新欄位與「歷史訂單是否回填」的決策。**2026-10-05 這一輪刻意沒有選它**，理由見 [`specs/G20g-report-chart-tests.md`](specs/G20g-report-chart-tests.md) §13.1：G20c §13.2 拒絕它的三個理由一條都沒鬆動（攤法武斷、要動金額寫入路徑、沒有真實促銷資料可判斷哪種攤法對），**演算法寫好了不等於該做了** —— 真正該等的是第一批真實優惠碼使用資料 |
| G20g | 報表圖表層的測試（ECharts option 的斷言方式，與 G23 同一類） | **已升為 P1，見上方 P1 表**（G20c §13.6 登記；規格書 v1.0 於 2026-10-05 完成，零 migration、後端零變更）。升排理由見規格 §1.4：工作順序用完了，而它是唯一不需要新依賴、產品決策或真實資料、**且有 PR #69 run #497 這個新鮮證據**的一項 |
| G20i | **促銷提示的樣式**（`promo-hint`／`cart-line-promo`／`cart-promotion-progress` 三個 class 沒有任何 CSS，提示以未加樣式的 `<p>`／`<small>` 呈現） | 未開始（Claude 審查 PR #72 時登記，見 G20a §14）。**是規格端沒寫樣式，不是實作的缺陷** —— G20a §5.5 給了 class 名、§13.6 只說「純視覺，可以靠樣式補」。規模極小，**夾進下一個本來就要動 `MenuView.vue` 的工作即可**，不值得單獨開一輪 |
| G20h | **後端購物車試算端點**（`POST /api/orders/preview`：收購物車、回 `Promotions.Applied` 與小計／折抵／應收，不寫任何資料）。讓購物車顯示**真實**的預估折抵金額，而金額仍然只有後端一個來源 | **已升為 P1，排為工作順序第 24 項**（G20a §13.1 登記；規格書 v1.2 於 2026-10-07 完成，零 migration、零新依賴）。**不要改成「在前端算」** —— 那會變成計價演算法的第二份實作，理由寫在 G20a §13.1 與 G20h §13.1。debounce 與「晚回來的舊回應不能覆蓋新狀態」的競態已在規格 §5.7 用單調序號解掉 |
| G21 | 會員價與員工價 | 未開始（G07 §11.2 登記） |
| G22 | 前端測試基礎設施（目前 `frontend` 完全沒有測試框架） | **已升為 P1，見上方 P1 表**（G07 §11.10 登記，§11.11 升排；規格書 v1.2 於 2026-09-28 完成） |
| G23 | 前端元件層測試（jsdom + `@vue/test-utils`，驗模板的可見性與排版） | **已升為 P1，見上方 P1 表**（G22 §13.3 登記；規格書 v1.0 於 2026-09-29 完成，v1.1 於 2026-09-30 依 Codex 在 PR #43 的 `REQUEST_CHANGES` 修正 §7 夾具） |
| G24 | 店長自行設定本店例外營業日**與本店最後點餐時間** | **已升為 P1，見上方 P1 表**（G19 §13.3 登記，G25 §13.5 擴大範圍；規格書 v1.0 於 2026-10-02 完成）。升排理由見規格 §1.4：P1 已清空、工作順序只剩 PO 已延後的金流，而 P2 其餘項目分別被新依賴（G05、G12）、產品與法遵決策（G16）或「要先有真實資料」（G17／G20／G21）擋住，G24 是唯一前置功能全部到位、不需新依賴也不需產品決策的一項。**沒有收到營運端回饋**，升排靠的是「其餘都被擋住」，不是營運證據 |
| G25 | 最後點餐時間（last order）與即將打烊提示 | **已升為 P1，見上方 P1 表**（G19 §13.5 登記；原掛在 G14 §2 的「G19 一併考慮」，已由 G19 明確排除並獨立；規格書 v1.1 於 2026-09-30 完成） |
| G28 | 永續庫存帳與選項層庫存（進貨、報廢、盤點、跨日結存、原料 BOM、燕麥奶賣完） | 未開始（G08 §14 登記）。**開工前提是 G08 上線後要先有一段真實備量資料**，否則帳務模型一定是猜的 |
| G12 | 外送、硬體印單 | 未開始 |

**G09 報表效能（已升為 P1，規格書見 [`specs/G09-report-aggregation.md`](specs/G09-report-aggregation.md)）** —— `ReportService.report()` 把整月已付款訂單投影載入記憶體，再對每一天（`ReportService.java:66-80`）與每一小時（`137-146`）各做一次 stream filter，是 O(天數 × 訂單數)。單店資料量下沒問題，跨店或資料累積後會變成記憶體與延遲風險。彙整應下推到 SQL。

**升為 P1 的理由**（Claude 定案，完整版見規格 §1.3 與 §11.1）：(1) P1 已經清空 —— G06／G10／G11／G13／G14／G15 全部合併，G07 在審查中；(2) **`coffee-reporting` 缺 `api` package 這項已登記的架構缺口正好在同一個檔案裡**，兩件事合成一份規格，分開做等於把同一個 171 行的檔案改兩次、審兩次；(3) 它是 P2 裡唯一不需要新功能決策的一項 —— 沒有新端點、沒有新權限、沒有 UI、**對外 JSON 一個位元都不變**，規格風險低、驗收客觀。其餘 P2 項目不排在前面的逐項理由寫在規格 §11.5（G16 涉及產品與法遵決策、不在 Claude 授權範圍；G05 要引入 Redis，違反「不得引入新依賴」的預設；G08 的輕量版已由 G13 做掉；G17／G20／G21 都明文登記要先有真實資料；G19 有可用的替代方案；G12 依賴外部選型）。**注意：上句「G19 有可用的替代方案」已由 G19 規格 §1.2 推翻** —— 那個替代方案（切 `active` 一天）有三個具體成本，其中「要有人記得切回來」會直接損失一整天營收。該句保留為 2026-09-28 當時的判斷紀錄，**不是現在的排序依據**；G19 已於 2026-09-29 升為 P1。

規格切成三階段（S1 輸出等價測試 + `daily`／`hourly` 下推、S2 月總計與分店績效下推並刪除 `sales` 全載入、S3 補 `api` package）。核心手法是**在 SQL 裡用固定偏移量的 epoch 毫秒算術分台北日／時桶**，不用資料庫的時區函式 —— 本專案測試跑 H2、生產跑 PostgreSQL，時區函式行為分岔的症狀是「測試全綠但報表差一天」，最難發現（規格 §5.2）。**改寫後固定 7 次查詢，且沒有任何一支查詢回傳 O(訂單數) 的列。** 次數從 4 變 7 是刻意取捨，理由見規格 §5.4。**本規格零 migration，不新增任何 Flyway 檔案、也不新增任何索引**（v1.1 修正；v1.0 原訂用 V10 加兩支索引，但 `V1__coffee_schema.sql:7-8` 的 `idx_orders_paid(paid_at)` 與 `idx_orders_branch_paid(branch_id,paid_at)` 欄位與順序完全相同，重建只會製造重複索引 —— 下推所需的存取路徑沿用 V1 這兩支既有索引即可，理由見規格 §4 與 §11.4，驗收 11 會驗 `db/migration/` 沒有新增檔案）。**前端零變更是驗收條件之一。**

**G16 顧客自助註冊**（第二次盤點新增，原混在 G12 內）—— 帳號只能透過 `ACCOUNT_MANAGE` 建立（`IdentityService.java:64`），沒有對外的註冊端點。線上點餐等於要總部幫每一位顧客開帳號。要不要開放對外註冊是產品決策（涉及濫用防護、驗證信、個資），但目前的狀態讓顧客點餐流程實質上只能用於展示。

**G05 Session 集中化** —— 單一實例的 `HttpSession`，應用重啟就全部登出，也無法水平擴展。登入限流同樣是單機記憶體。`ARCHITECTURE.md` 已點名需要 Spring Session / Redis。在單店單機營運下可接受，多店或需要零停機部署時就是硬阻擋。

**G08 庫存扣減** —— `products` 沒有任何庫存欄位。注意 G13（售罄）是 G08 的輕量版：先做得到「今天這項賣完」，不必等完整的庫存管理。

**已升為 P1，規格書見 [`specs/G08-branch-product-stock.md`](specs/G08-branch-product-stock.md)（v1.0，2026-10-02）。** 升排理由與其餘 P2 項目的逐項排除見規格 §1.4 —— 簡述：工作順序上 G24 之後的下一項是金流（PO 已整批延後），所以必須從 P2 升排一項，而 G08 是唯一**沒有外部依賴、沒有法遵決策、也不需要真實資料**的項目。**誠實交代：沒有收到任何營運端的超賣回饋**，升排靠的是其餘項目都被別的東西擋住。

規格同時**收斂了本段當初的預期**，理由寫在規格裡：

- **範圍不是「完整的庫存管理」，是「每日可售數量」**（規格 §13.1）。一張 `(branch_id, product_id, on_date)` 的表，隔天沒有列就等於不限量。咖啡廳的真實約束是「今天做得出幾份」而不是「倉庫裡還有幾個」；而且每日數量**自己會過期**，沒有人需要記得明天把數字改回來 —— 這是 G19 §1.2 批評 `active` 旗標那個理由的正面應用。永續存量帳（進貨、報廢、盤點、跨日結存、原料 BOM）牽涉會計決策，超出 PO 授權給 Claude 的設計決策範圍，**另立 G28**
- **G13 的「標記售完」不會被取代，兩者刻意並存**（規格 §13.3）。手動的 `branch_products.availability='SOLD_OUT'` 與自動的 `remaining=0` 是兩個獨立判斷來源，任一成立就不能賣。合併成一欄會立刻需要第三個欄位來分辨「這個 SOLD_OUT 是誰設的」，那正是 `active` 旗標踩過的同一類坑
- **已知且刻意接受的缺口**（規格 §13.5）：扣減發生在訂單成立而非付款完成，所以 ECPAY 的 `PENDING_PAYMENT` 訂單會占住備量，而逾時釋放屬於延後中的 G04。選這一邊是因為另一邊（付款時才扣）會造成超賣，而目前**沒有退款流程**（G03 延後）。現場解法是直接調高數字，規格 §5.3 的差額同步保證調高不會弄壞已售數

核心設計：扣減在 `OrderService.create` 的交易內、**同商品先合併加總再依 `product_id` 字典序取行鎖**（規格 §5.2，合併是為了讓「僅剩 N 份」的訊息對得上事實，排序是為了避免兩筆訂單取鎖順序相反而死鎖）；取消訂單回補並**夾在 `quantity` 以內**；`remaining=0` 時菜單顯示今日售完，但 `UNLISTED` 優先序更高。

**G17 點餐 UI 的「常用組合」快捷**（G06 第 13.6 節登記）—— 選項群組多的商品，手機版點餐流程會偏長。刻意延後：常用組合要先有真實訂單資料才知道哪些組合常用，現在做出來的一定是猜的。G06 上線跑一段時間後再用實際資料判斷要不要做。

**G20 品項層折扣與買一送一** / **G21 會員價與員工價**（G07 §11.2 登記）—— G07 只做訂單層、只由優惠碼觸發的折扣。買一送一要決定「折的是哪一件」，那是品項層的規則引擎，會動到 `order_items` 快照結構與報表的品項營收歸屬；會員價與員工價要先有會員／員工身分模型（`accounts` 目前只有角色與分店）。兩者各自的規模都與 G07 相當，合進去會逼出一個切不開的大階段。**排在 G07 上線並累積一段真實 `order_discounts` 資料之後**，屆時看實際用了哪幾種促銷再決定要不要做 —— 現在做的一定是猜的（與 G17 同一個理由）。兩者都是加法，不需要回頭改 G07。

**G19 分店例外營業日**（G14 §11.3 登記）—— G14 只做每週固定時段，國定假日、臨時公休、提早打烊、颱風天全部不在範圍。例外日需要自己的資料表、日曆 UI 與優先序規則（例外覆蓋固定時段），規模與 G14 本身相當，合在一起會讓 G14 的 S1 失去「合併後零影響」的性質。現階段的替代方案是切 `active` 一天，不精緻但臨時公休是低頻事件。**資料形狀（一段時間 + 一個日期）與 `branch_hours` 同構，日後補一張覆寫表是純加法**，不必回頭改 G14。編號說明：`G17` 由 G06 第 13.6 節占用、`G18` 由 G13 第 11.3 節的區域定價占用，G19 是下一個未使用號。

**已升為 P1，規格書見 [`specs/G19-branch-hour-overrides.md`](specs/G19-branch-hour-overrides.md)（v1.1，2026-09-29）。** 升排理由與其餘 P2 項目的逐項排除見規格 §13.1。規格同時推翻了本段兩處當初的預期，理由都寫在規格裡：

- **「切 `active` 一天」不是可接受的替代方案**，它有三個具體成本（規格 §1.2）：`active=false` 讓分店在顧客端整個消失而不是「今天公休」；`requireOpen()` 被 `IdentityService.saveAccount()` 共用，切掉會讓總部在公休日無法維護該分店帳號（正是 G14 §5.7 當初拒絕的同一個理由）；而且**要有人記得切回來**，忘了就是隔天整天沒生意且系統不會叫
- **用兩張表而不是一張**（`branch_day_overrides` 表頭 + `branch_day_override_hours` 時段，規格 §4.2）。一天有三種狀態（沒有例外／整天公休／改用當天時段），前兩者都對應「零個時段列」，所以時段列的有無區分不了它們；表頭的 `PRIMARY KEY(branch_id,on_date)` 讓「一天最多一個例外」由 DB 強制，單表加 `closed` 欄位做不到（一天多段就是多列，主鍵擋不了；`UNIQUE` 又把 NULL 視為互不相同）

核心設計：`on_date` 沿用 `branch_products.sold_out_date` 的 `INTEGER` `yyyyMMdd` 台北日編碼（**不用 `DATE`**，H2 與 PostgreSQL 的時區行為分岔，症狀是「測試全綠但差一天」，§4.1）；優先序為「例外日贏過每週時段，且**贏過『沒設定＝24 小時營業』這條預設**」（§6.2 第 3 條 —— 示範資料的分店全是 `branch_hours` 零列，順序寫反會讓公休對它們完全失效）；**例外日完全決定當天，前一日的跨夜尾段一律不跨進來**（v1.1 的 §6.3a／§6.4a／§6.4c —— `D` 是 `Closed` 就立刻回 `false`，`D` 是例外時段時也不接受尾段；v1.0 只擋住前一日是 `AlwaysOpen` 的方向，漏掉前一日是 weekly 跨夜段這個常態方向，照 v1.0 施工會讓顧客在公休日凌晨仍能下單）；例外日**不支援跨夜段**（§13.4，避免「例外段延到隔天而隔天也公休」這個沒有好答案的優先序問題）；三個施工階段全部是加法，**零例外列時行為與現狀逐字相同**（§6.5，驗收 1 要求 `BranchHoursTest` 一個字元都不改且全綠）。Flyway 占用 **V10**。

**G22 前端測試基礎設施**（G07 §11.10 登記）—— `frontend/package.json` 目前**沒有任何 test script**，也沒有 Vitest／Playwright／`@vue/test-utils`。`npm run build` 只跑 `vue-tsc --noEmit && vite build`，所以前端的保護僅止於型別檢查：任何純行為缺陷（算錯金額、驗證用錯變數、流程少一步）在 CI 上都是綠的。G07 §6.6 的 POS 收款就是第一次被這件事咬到 —— 那個缺陷會讓收銀抽屜短少，而且**只存在於前端**，後端測試看不到，所以 G07 只能把驗收 21b／22／23 排除在自動化之外（§11.10），最後更因為沒有任何一方具備實機驗收環境而改為原始碼佐證（§11.11，v1.5）。**G22 因此由「有空再說」升為 G09 之後的下一份規格** —— G07 把這個缺陷類別的防線完全押在它身上了。

範圍要一次定清楚，否則會變成每份規格各自夾帶一點：要引入哪些依賴（Vitest + jsdom + `@vue/test-utils` 是最小組合）、`npm test` 要不要進 CI 的 `verify`、**測試紅了算不算 CI 紅燈**（預設要算，否則等於沒做）、以及哪些東西值得測（優先是 `shared/format.ts` 的金額格式化、`shared/api.ts` 的錯誤處理、以及各 `*View.vue` 裡從 `checkout()` 這類函式拆出來的純計算 seam，不是元件快照）。這是 `AGENTS.md` 禁止事項第 3 條「不引入新依賴」的**刻意例外**，所以要有自己的規格書與 PO 可見的理由，不能由實作端順手加。

優先順序：排在 P2，但**在 G20／G21 之前** —— 那兩項會再往前端加一批金額相關的顯示邏輯，先有測試網比較划算。編號說明：G21 是 G07 §11.2 占用的最後一號，G22 是下一個未使用號。

### G26 —— `OrderDiscountTest` 的月份時區時間彈（2026-09-30 登記，2026-10-01 已修正）

> **✅ 已修正並合併** —— [PR #48](https://github.com/choka1227/coffee_GPT6/pull/48)，2026-10-01，主線合併提交 `ee74608`。
> 主線現況是 `OrderDiscountTest.java:83` 的 `YearMonth.now(ZoneId.of("Asia/Taipei"))`，四條驗收全數達成。
> **本段保留為根因紀錄，不是待辦項。** 回歸防護登記為 **G27**（工作順序第 16 項）。

**缺陷**：`backend/coffee-app/src/test/java/com/coffee/app/OrderDiscountTest.java:82`

```java
String month = YearMonth.now().toString();   // ← 系統預設時區，CI runner 是 UTC
```

`ReportService.java:13` 的彙整視窗用的是 `ZoneId.of("Asia/Taipei")`：

```java
long start = m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
     end   = m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
```

**兩個時區在「UTC 與台北落在不同月份」時不一致**，也就是**每月最後一天 16:00 UTC 到 24:00 UTC**（台北 1 號的 00:00–08:00）這八小時。測試建的訂單 `paid_at` 是「現在」，落在台北的新月份；`YearMonth.now()` 給的是 UTC 的舊月份；報表視窗查不到那筆訂單，於是：

```
OrderDiscountTest.reportShowsDiscountAndRevenueAfterPayment:88
expected: 20L
 but was: 0L
```

**為什麼這一項要插隊**：`verify` 是 `feature/init-project` 分支保護的必要檢查，所以這八小時內**所有人的所有 PR 都合不進去**，與 PR 改了什麼完全無關。它每個月準時復發一次，而且症狀（折扣金額變 0）看起來像折扣邏輯壞了，很容易被誤判成 G07 的迴歸而往錯的方向查。

**修法（一行，加一個 import）**：

```java
import java.time.ZoneId;
...
String month = YearMonth.now(ZoneId.of("Asia/Taipei")).toString();
```

**驗收條件**：

- [ ] 1. `OrderDiscountTest.java:82` 不再呼叫無參數的 `YearMonth.now()`
- [ ] 2. `grep -rn 'YearMonth.now()\|LocalDate.now()\|LocalDateTime.now()\|ZonedDateTime.now()' backend/*/src` **零命中** —— 目前全 repo 只有這一處，修掉就歸零，順手讓「不得依賴系統預設時區」變成一條可掃描的不變式（`AGENTS.md`「時間」那節本來就要求業務日期一律以 `Asia/Taipei` 換算，但沒有任何自動化在看）
- [ ] 3. `cd backend && ./mvnw -B -ntp verify` 綠
- [ ] 4. `git ls-files -s backend/mvnw scripts/build.sh start-demo.sh` 三個都是 `100755`

**不另立規格書的理由**：改動是一行。寫一份施工級規格書的成本高於實作本身，而定位、根因、修法與驗收在這一段已經寫完。**本項不計入規格庫存** —— 庫存上限（2 份）的用意是控制「Codex 待做的規格份數」，一行修正不佔那個額度。

**同類風險的通則**：測試裡凡是「現在」與業務日期有關的地方，都要明確給 `Asia/Taipei`，不要靠 runner 的時區。這一類缺陷的共同特徵是**平常全綠、在特定時間窗才紅**，所以不會在開發當下被發現，只會在某個無關的 PR 上炸開 —— 這次就是炸在一個零程式碼的純文件 PR 上。

---

### G27 —— 無參數 `now()` 的自動化防線（2026-10-01 登記，2026-10-02 隨 PR #53 合併，本段保留為紀錄）

**缺口**：G26 把唯一一處 bare `YearMonth.now()` 修掉了，驗收 (b) 也把「全 repo 零命中」寫成不變式 —— 但**沒有任何自動化在看這條不變式**。它現在成立，純粹因為剛好沒人再寫一個。

下一支做日期運算的測試只要寫一個 `LocalDate.now()`，同一類時間彈就回來，而且症狀與 G26 完全一樣：**平常全綠，每月最後一天 16:00–24:00 UTC 全員 CI 紅**，而 `verify` 是分支保護的必要檢查，所以那八小時內所有人的所有 PR 都合不進去 —— 再一次被誤判成業務邏輯迴歸，再一次往錯的方向查。

`AGENTS.md`「時間」那節本來就要求業務日期一律以 `Asia/Taipei` 換算。缺的不是規則，是**執行規則的東西**。

**修法**：在 `backend/coffee-app/src/test/java/com/coffee/app/` 新增一支 ArchUnit 測試（建議 `TimeZoneGuardTest.java`），對下列類別的**無參數** `now()` 下 `noClasses().should().callMethod(X.class, "now")`：

```
YearMonth · LocalDate · LocalDateTime · ZonedDateTime · LocalTime
```

**關鍵一：`ClassFileImporter` 不可加 `ImportOption.Predefined.DO_NOT_INCLUDE_TESTS`。**
G26 的缺陷就在測試碼裡，只掃 main 抓不到。既有的 `ModuleBoundariesTest` 正是加了那個選項 —— 這就是為什麼它當初放行了 G26。

**關鍵二：`Instant.now()` 必須放行。**
`BranchController.java:90` 與 `BranchHourOverrideTest.java:380` 都是 `Instant.now().atZone(TAIPEI)`。`Instant` 是絕對時刻、與時區無關，把它列進黑名單會讓這兩處無故變紅，並逼出「注入 `Clock`」那種本規格沒要求的大改。**這條不變式管的是「沒有指定時區就取今天／本月」，不是「取得當下時刻」。**

**可行性已先行驗證**（Claude，2026-10-01）：

- 全 repo 的測試都在 `backend/coffee-app/src/test/java/com/coffee/app`（`find backend -path '*/src/test/*'` 只有 `coffee-app` 一個結果），所以**一支放在 `coffee-app` 的測試就同時涵蓋 main 與 test 全部程式碼**
- `archunit-junit5` 1.4.1 已在 `backend/coffee-app/pom.xml:102-105` 的 test scope，**零新相依**

**驗收**：

- [ ] 1. 新測試存在且綠
- [ ] 2. 暫時在任一既有測試塞一行 `LocalDate.now()`，新測試會紅（實作端自行驗證後還原，**不要把那一行留在 commit 裡**）
- [ ] 3. `BranchController.java:90` 與 `BranchHourOverrideTest.java:380` 的 `Instant.now()` 維持不變且綠
- [ ] 4. 既有測試一個字元都不修改
- [ ] 5. `cd backend && ./mvnw -B -ntp verify` 綠
- [ ] 6. `git ls-files -s backend/mvnw scripts/build.sh start-demo.sh` 三個都是 `100755`

**不另立規格書的理由**：一支測試檔、零 migration、零正式程式、零新相依、零端點、零 UI。缺陷定位、修法、兩個踩雷點與驗收在本段已寫完，寫一份施工級規格書的成本高於實作本身。比照 G26，**本項不計入規格庫存**。

**施工階段**：單一階段（規模遠小於「Codex 一次執行做得完」的門檻）。

**為什麼排在 G25 之後而不插隊**：G26 已經修好，現在沒有紅燈 —— 這一項是回歸防護，不是止血。G25 是已經寫定、Codex 可立刻開工的功能缺口，不該被它推後。G26 當初插隊的理由（正在擋所有人的 CI）在這一項不成立。

**設計決策：用 ArchUnit 而不是 grep 或 Checkstyle。**
ArchUnit 已經在專案裡、已經是 `verify` 的一部分、看的是 bytecode 的實際呼叫（不會被字串、註解或換行騙過），而 grep 要另外接一個 CI 步驟、Checkstyle 要引入新插件。**推翻它的代價**：若日後需要擋的不只是方法呼叫（例如禁止某個欄位型別），ArchUnit 仍然夠用；真正需要換掉它的情境是「要在編譯期就擋」，那才值得引入 error-prone 之類的工具 —— 現在沒有那個需求。

---

## 排定的工作順序

1. ~~**Codex 依 `specs/G06-product-options.md` 實作選項模型與加價**~~ —— 已完成，PR #14 於 2026-09-18 合併（S1/S2/S3 三階段，進度報告見 [`reports/G06-product-options-progress.md`](reports/G06-product-options-progress.md)）
2. ~~**Codex 依 `specs/G01a-reconciliation-fixes.md` 修正 G01 留在主線的兩項缺陷**~~ —— 已完成，PR #15 於 2026-09-18 合併（S1/S2 兩階段，進度報告見 [`reports/G01a-reconciliation-fixes.md`](reports/G01a-reconciliation-fixes.md)）
3. ~~**Codex 依 `specs/G13-branch-menu-availability.md` 實作分店可用性與售罄**~~ —— 已完成，PR #20 於 2026-09-20 合併（S1/S2/S3 三階段，Flyway 占用 V4，進度報告見 [`reports/G13-branch-menu-availability.md`](reports/G13-branch-menu-availability.md)）
4. ~~**Claude 產出 G11 + G15 合併規格書（稽核軌跡與現金日結）**~~ —— 已完成，規格書 v1.2 於 2026-09-19 隨 [PR #17](https://github.com/choka1227/coffee_GPT6/pull/17) 合併（兩輪 `REQUEST_CHANGES` 後由 Codex 核准）。閘門解除
5. ~~**Codex 依 [`specs/G11-G15-audit-and-cash-sessions.md`](specs/G11-G15-audit-and-cash-sessions.md) 實作稽核軌跡與現金日結**~~ —— 已完成，PR #22 於 2026-09-20 合併（S1–S4 四階段全數完成，Flyway 占用 V5／V6，進度報告見 [`reports/G11-G15-audit-cash-sessions-progress.md`](reports/G11-G15-audit-cash-sessions-progress.md)）
6. ~~**Codex 依 [`specs/G10-order-list-pagination.md`](specs/G10-order-list-pagination.md) 實作訂單清單分頁、篩選與 N+1 修正**~~ —— 已完成，[PR #26](https://github.com/choka1227/coffee_GPT6/pull/26) 於 2026-09-21 合併（S1–S3 三階段全數完成，Flyway 實際占用 V7，進度報告見 [`reports/G10-order-list-pagination-progress.md`](reports/G10-order-list-pagination-progress.md)）。審查曾以缺測試退回一輪，補齊後合併。**`codex/g10-order-pagination` 分支已完成任務，不要再從它續作或開新分支**
7. ~~**Codex 依 [`specs/G14-branch-business-hours.md`](specs/G14-branch-business-hours.md) 實作分店營業時間**~~ —— 已完成，[PR #29](https://github.com/choka1227/coffee_GPT6/pull/29) 於 2026-09-21 合併（S1–S3 三階段全數完成，Flyway 實際占用 V8，進度報告見 [`reports/G14-branch-business-hours-progress.md`](reports/G14-branch-business-hours-progress.md)）。**`codex/g14-branch-business-hours` 分支已完成任務，不要再從它續作或開新分支**
8. ~~**Codex 依 [`specs/G07-order-discounts.md`](specs/G07-order-discounts.md) 實作訂單折扣與優惠碼**~~ —— 已完成，[PR #31](https://github.com/choka1227/coffee_GPT6/pull/31) 於 **2026-09-28** 合併（S1–S3 三階段全數完成，Flyway 實際占用 **V9**，進度報告見 [`reports/G07-order-discounts.md`](reports/G07-order-discounts.md)）。審查歷經兩輪 `REQUEST_CHANGES`，規格書更新至 v1.5 才解除閘門。**`codex/g07-order-discounts` 分支已完成任務，不要再從它續作或開新分支。** **注意：下列歷程紀錄中「不要為它們引入 Vitest 等前端測試依賴」一句已被 G22 取代** —— 當時的判斷是「前端測試基礎設施是獨立缺口，不要夾在 G07 裡做」，那句話約束的是 G07 的範圍，不是永久禁令。G22 規格 §13.2 已由 Claude 明確裁決引入 `vitest`。以下為歷程紀錄，保留給日後判斷用：**續作請留在 `codex/g07-order-discounts` 分支，不要另開分支、不要重做已完成的階段。** 待修兩項：(a) §6.6（v1.2 新增）POS 收現金用購物車小計驗證實收金額，會擋掉合法金額並讓抽屜短少；(b) 驗收 11 的四種 404 完全沒有測試。其餘為不擋合併的意見，列在 PR 的 review 裡。**Flyway 已實際占用 V9**。**規格 §6.1 仍是紅線：`Create` 不得有任何金額欄位**（本次實作有守住） **規格已更新為 v1.4**（2026-09-27；v1.3／v1.4 兩輪皆依 Codex 在 PR #32 的 `REQUEST_CHANGES`）：驗收 21 拆成 **21a（後端自動化）** 與 **21b（人工）**，22／23 同為人工驗收，**不要為它們引入 Vitest 等前端測試依賴** —— 前端測試基礎設施是獨立缺口 G22。§12 的施工提醒編號也已順排為 1–14（原本第 9 項之後重覆 7／8／9）。**v1.4 另修正 21a 的保護範圍**：21a 只釘住後端以折後 `total` 驗證 `tendered` 與保留 `PENDING_PAYMENT` 的契約，**它擋不住「前端自動以小計 140 當實收送出」那個缺陷**（請求與帳面一致，21a 全綠）。在 G22 之前擋住抽屜短少的只有 21b／22／23。**規格已再更新為 v1.5**（2026-09-27，Claude 主動修補，非任何一方的 `REQUEST_CHANGES`）：v1.3／v1.4 把「實機人工驗收紀錄」寫成 PR #31 轉 ready for review 的閘門，但**沒有指定執行者，而且沒有任何一方做得到** —— Codex 的環境沒有瀏覽器、沒有可用 backend JAR、`./mvnw verify` 因 Maven Central DNS 解析失敗跑不起來；押在 PO 身上則是「停工等人」。結果是 PR #31 三階段全綠、CI 通過卻永遠 draft，硬相依的 G09 也連帶開不了工（2026-09-27 Codex 兩次執行產出零行程式碼）。**v1.5 把 21b／22／23 改為「原始碼佐證」並解除閘門**（§11.11）：Codex 在 `docs/reports/G07-order-discounts.md` 附一節「§6.6 原始碼佐證」，貼出 `MenuView.vue` 的實際行並說明每個顯示／驗證數字的來源，Claude 在 review 時對照 diff 覆核；實機操作降為**PO 的合併後驗收**（見下「待 PO 驗收」）。**Codex 下一步：補上該節、勾完 S3、PR #31 轉 ready for review 並啟用 auto-merge，不要再等實機驗收**
9. ~~**Codex 依 [`specs/G09-report-aggregation.md`](specs/G09-report-aggregation.md) 把報表彙整下推 SQL，並補 `coffee-reporting` 的 `api` package**~~ —— 已完成，[PR #36](https://github.com/choka1227/coffee_GPT6/pull/36) 於 **2026-09-28 合併**（Claude 於 head `91fac14` 送出 `APPROVE` 後由 auto-merge 合入，**主線合併提交 `ee9861c`**，S1–S3 三階段全數完成、CI 綠、零 migration、零 frontend 變更、18 個 JSON key 由真實 HTTP 測試鎖定）。硬相依的 G07 已於同日先行合併，閘門解除。**`codex/g09-report-aggregation` 分支已完成任務，不要再從它續作或開新分支。**
10. ~~**Claude 產出 G22 前端測試基礎設施規格書**~~ —— 已完成，[`specs/G22-frontend-test-infra.md`](specs/G22-frontend-test-infra.md) v1.2 於 2026-09-28 產出（G07 §11.11 升排；庫存在 G07 合併、G09 核准後降到 1 份才動，符合上限規則）。
11. ~~**Codex 依 [`specs/G22-frontend-test-infra.md`](specs/G22-frontend-test-infra.md) 建立前端測試基礎設施並抽出可測純函式**~~ —— 已完成，[PR #39](https://github.com/choka1227/coffee_GPT6/pull/39) 於 **2026-09-29 合併**（Claude 於 head `b93c0cc` 送出 `APPROVE` 後由 auto-merge 合入，S1–S3 三階段全數完成、CI 綠、零 migration）。S1 裝 `vitest@3.2.7`、把 `npm run test` 放進既有 `verify` job（job 名稱未動）、抽出 `csvBody()`；S2 抽出六支 checkout 決策純函式並由 `MenuView.vue` 實際呼叫；S3 完成冪等鍵重用與 G07 21b／22／23 自動化。**`codex/g22-frontend-test-infra` 分支已完成任務，不要再從它續作或開新分支。** **前端從此有測試網**：`frontend/` 的 `npm run test`（vitest，`environment: "node"`，`src/**/*.spec.ts`）已是 `verify` job 的必經步驟，**測試紅 = CI 紅 = 合不進主線**。後續任何動到前端邏輯的規格都應該把可測的部分抽成純函式並附 `.spec.ts`（G19 S3 就是第一個這樣做的）。**review 留下兩項非阻斷觀察**：(a) 缺 `docs/reports/G22-frontend-test-infra.md` —— 那是 G22 規格 §2.1 檔案清單漏列，不是實作端的問題，**G19 規格 §2.1 已把實作回報列為通則**；(b) 「待 PO 驗收」表被清成只剩表頭（已隨本批修掉）。
12. ~~**Codex 依 [`specs/G19-branch-hour-overrides.md`](specs/G19-branch-hour-overrides.md) 實作分店例外營業日**~~ —— **已完成，[PR #42](https://github.com/choka1227/coffee_GPT6/pull/42) 於 2026-09-30 合併**（S1–S3 三階段全數完成，Flyway 實際占用 **V10**，進度報告見 [`reports/G19-branch-hour-overrides.md`](reports/G19-branch-hour-overrides.md)）。**`codex/g19-branch-hour-overrides` 分支已完成任務，不要再從它續作或開新分支。** 以下為施工時的規格重點，保留給日後判斷用：三個施工階段：**S1** `V10__branch_hour_overrides.sql`（兩張新表）+ `Branches` 的 `DayOverride`／`overrides()`／`saveOverride()`／`deleteOverride()` + §6 的 `resolveDay()` 解析器，無端點無 UI；**S2** 三個端點（`GET`／`PUT`／`DELETE /api/branches/{id}/hour-overrides`）、授權、CSRF、越權測試與稽核；**S3** 前端總部例外日編輯（`BranchesView.vue`）、顧客端公休訊息（`MenuView.vue`）、`modules/branches/overrides.ts` 純函式 + vitest。**Flyway 占用 `V10`**，下一份需要 migration 的規格自 **V11** 起算。**紅線一：零例外列時行為必須逐字不變** —— 驗收 1 要求 `BranchHoursTest` 一個字元都不修改且全綠（規格 §6.5）。**紅線二：例外日必須贏過「沒設定＝24 小時營業」** —— `resolveDay()` 的判斷順序是「先看例外，再看 `weeklyEmpty`」，寫反會讓公休對示範資料的所有分店完全失效（§6.2 第 3 條）。**紅線三：例外日完全決定當天，前一日的跨夜尾段不跨進來**（§6.3a／§6.4a／§6.4c／§13.10）—— `D` 是 `Closed` 就立刻回 `false`，`D` 是例外時段時也不接受尾段。**這是規格 v1.1 修正的那條錯誤**：v1.0 只擋住前一日是 `AlwaysOpen` 的方向，漏掉前一日是 weekly 跨夜段的方向（設了每週時段的分店的常態），照 v1.0 施工會讓顧客在公休日凌晨仍能下單。驗收 6 的 (b)(c)(d) 三條分別釘住三個方向。**紅線四：`SecurityConfiguration` 一個字不動**，三個新端點已被既有的 `/api/**` → `authenticated()` 規則涵蓋（§7.4 —— G14 v1.1 就是在這裡出過錯）。權限維持總部限定（`BRANCH_MANAGE` + `actor.global()`），**因此 V10 沒有任何 `INSERT INTO role_permissions`**（§8、驗收 16）。
13. ~~**Codex 依 [`specs/G23-frontend-component-tests.md`](specs/G23-frontend-component-tests.md) 補上前端元件層測試**~~ —— **已完成，[PR #45](https://github.com/choka1227/coffee_GPT6/pull/45) 於 2026-09-30 合併**（S1–S3 三階段全數完成，零 migration，進度報告見 [`reports/G23-frontend-component-tests.md`](reports/G23-frontend-component-tests.md)）。**`codex/g23-frontend-component-tests` 分支已完成任務，不要再從它續作或開新分支。** 以下為施工時的規格重點，保留給日後判斷用：三個施工階段：**S1** 引入 `jsdom` + `@vue/test-utils`、`vitest.config.ts` 改為雙 project（`node` 逐字不變、新增 `dom`）、掛載工具與 `Modal.vue` 冒煙測試；**S2** `MenuView.vue` 六個可見性案例（實收欄位／應找零／結帳按鈕 disabled 的四個條件）；**S3** 兩段式收現流程與「`POST /api/orders` 的 body 不得含任何金額欄位」的白名單斷言。**零後端變更、零 migration、零 `.vue` 修改**（驗收 14、15）。**與 G19 零檔案交集**，但依「一次一份」通則排在 G19 之後開工。**紅線一：不得修改任何 `.vue`**，需要選取點時用使用者看得到的文字，不要加 `data-testid`（規格 §13.2）。**紅線二：不得斷言任何打烊訊息的文字** —— G19 S3 會改它的來源，釘下去會讓 G19 合併時無故變紅（§12）。**紅線三：「不存在」用 `toBeNull()`／`exists() === false`，不得用 `isVisible()`** —— `v-if` 是不渲染，用錯那個測試會永遠是綠的（驗收 10）。**不動用任何 Flyway 版號**，下一份需要 migration 的規格仍自 **V11** 起算。
14. ~~**Codex 修掉 `OrderDiscountTest` 的月份時區時間彈（G26）**~~ —— **已完成，[PR #48](https://github.com/choka1227/coffee_GPT6/pull/48) 於 2026-10-01 合併**（Claude 於 head `47b39a7` 送出 `APPROVE` 後由 auto-merge 合入，**主線合併提交 `ee74608`**，單一階段、CI 綠、零 migration、零正式程式變更，報告見 [`reports/G26-order-discount-month-timezone.md`](reports/G26-order-discount-month-timezone.md)）。**`codex/g26-report-month-timezone` 分支已完成任務，不要再從它續作或開新分支。** 驗收 (b) 的全 repo 零命中已由 Claude 獨立複驗。以下為當時的缺陷說明，保留給日後判斷用： —— `backend/coffee-app/src/test/java/com/coffee/app/OrderDiscountTest.java:82` 的 `YearMonth.now()` 用**系統預設時區**（CI runner 是 UTC），但 `ReportService.java:13` 的彙整視窗是 `ZoneId.of("Asia/Taipei")`。兩者在「UTC 與台北落在不同月份」的時候不一致，即**每月最後一天 16:00 UTC 起到 24:00 UTC**（台北 1 號的 00:00–08:00）：訂單的 `paid_at` 落在台北的新月份，報表卻查舊月份，`report.discount() - before.discount()` 得到 0 而不是 20。**症狀是 `reportShowsDiscountAndRevenueAfterPayment:88 expected: 20L but was: 0L`，而且它會擋掉那段時間內所有人的所有 PR**（`verify` 是分支保護的必要檢查），與 PR 內容無關。**修法（一行加一個 import）**：`String month = YearMonth.now(ZoneId.of("Asia/Taipei")).toString();`，並 `import java.time.ZoneId;`。**驗收**：(a) 該行不再呼叫無參數的 `YearMonth.now()`；(b) 全 repo 搜不到任何測試或正式程式碼呼叫無參數的 `YearMonth.now()`／`LocalDate.now()`／`LocalDateTime.now()`／`ZonedDateTime.now()`（目前只有這一處，修掉就歸零，順手把它變成可掃描的不變式）；(c) `cd backend && ./mvnw -B -ntp verify` 綠。**不另立規格書的理由**：改動是一行，寫一份施工級規格書的成本高於實作本身，而缺陷的定位、根因、修法與驗收在這一項裡已經寫完；本項**不計入規格庫存**（庫存的用意是控制「Codex 待做的規格份數」，一行修正不佔那個額度）。**這一項排在 G25 前面**，因為它擋的是所有人的 CI，而 G25 只是下一個功能。**發現經過**：2026-09-30 18:21 UTC，純文件 PR [#46](https://github.com/choka1227/coffee_GPT6/pull/46) 的 `verify` 紅燈；該 PR 與主線的兩點 diff 只有 `docs/` 兩個檔案，Java 與前端零差異，所以是主線既有的缺陷在那個時間窗被觸發，不是該 PR 造成的。
15. ~~**Codex 依 [`specs/G25-last-order-time.md`](specs/G25-last-order-time.md) 實作最後點餐時間與即將打烊提示**~~ —— **已完成，[PR #51](https://github.com/choka1227/coffee_GPT6/pull/51) 於 2026-10-01 合併**（Claude 於 head `4085df9` 送出 `APPROVE` 後由 auto-merge 合入，S1–S3 三階段全數完成，Flyway 實際占用 **V11**，進度報告見 [`reports/G25-last-order-time-progress.md`](reports/G25-last-order-time-progress.md)）。以下規格摘要保留為紀錄 —— 三個施工階段：**S1** `V11__branch_last_order.sql`（`branches` 加一個 `last_order_minutes` 欄位，`DEFAULT 0`、具名 CHECK 0–120）+ 把 `BranchService.isOpenAt` 改寫成新純函式 `windowAt` 的包裝 + `Branches` 新增 `OpenState`／`stateAt`／`lastOrderMinutes`，**行為零變更**；**S2** `saveHours` 四參數版、`PUT /hours` 的選填 `lastOrderMinutes`、`requireOrderable` 的截止點與新 400 訊息、越權測試；**S3** `BranchesView` 的設定欄位、`MenuView` 的「即將停止接單／已停止接單」三態與按鈕 disabled、G23 風格的 dom 測試。**Flyway 占用 `V11`**，下一份需要 migration 的規格自 **V12** 起算。**紅線一：`openNow` 的語意一個字不能改** —— 過了最後點餐時間但還沒打烊的分店，`openNow` 仍是 `true`，可下單與否是新的 `orderableNow`（規格 §13.3）。把兩者合併會讓分店在顧客端提早顯示「已打烊」，而且**編譯照過、既有測試照綠**，錯誤只會在現場出現。**紅線二：跨夜時段的剩餘分鐘是 `close + 1440 - m`，不是 `close - m`**（§5.1）—— 寫錯的症狀是跨夜營業的店晚上十一點就不能點餐。**紅線三：S1 必須行為零變更** —— 驗收 5 要求 `BranchHoursTest`／`BranchHourOverrideTest`／`BranchHoursAdminTest` 三支既有測試**一個字元都不修改**且全綠，驗收 4 要求一支逐分鐘的等價性掃描測試（期望值寫死，不得呼叫 `isOpenAt` 自我比對）。**紅線四：`coffee-orders` 的變更量必須是零**（驗收 17）—— `OrderService.java:61` 已經在呼叫 `requireOrderable`，語意擴充全部在 `coffee-branches` 內完成；動到 `OrderService` 的金額迴圈就是走錯路了。**紅線五：四個既有的打烊訊息逐字不變**（`BranchHourOverrideTest.java:356-375` 釘住了它們，驗收 9）。**紅線六：截止後的訊息不得出現「明日」二字**（§5.4／§13.10，驗收 8、21–23）—— `validateHours` 允許每天最多 4 個不重疊時段，`09:00–12:00`＋`13:00–18:00` 的分店在 `L=15` 時 `11:50` 只是第一段停止接單、`13:00` 當天就恢復，寫「明日」是明確錯誤的資訊並流失當日下午的訂單；今日仍有後續可下單時段時訊息要指出那一段的開始時刻（`請於今日 13:00 起的營業時段再下單`，且**長度 ≤ `L` 的時段要跳過**），否則用不帶日期的 `請於下一個營業時段再下單`。這一條是 Codex 在 PR #46 review 裡指出的，v1.1 已修正並補上反向驗收。員工 POS **不受截止點限制**（§13.4，與 G14 同源），權限維持總部限定，**因此 V11 沒有任何 `INSERT INTO role_permissions`**。S3 會續寫 `MenuView.dom.test.ts`，**G23（PR #45）已於 2026-09-30 合併，該相依已解除**。
16. ~~**Codex 補上「禁止無參數 `now()`」的自動化防線（G27）**~~ —— **已完成，[PR #53](https://github.com/choka1227/coffee_GPT6/pull/53) 於 2026-10-02 合併**（head `c444243`，單一階段、CI 綠、零 migration、零正式程式變更；新增 `backend/coffee-app/src/test/java/com/coffee/app/TimeZoneGuardTest.java`，以 ArchUnit 同時掃 main 與 test 的 bytecode，`Instant.now()` 依規劃放行）。**`codex/g27-time-zone-guard` 分支已完成任務，不要再從它續作或開新分支。** Codex 把反向驗收（驗收 2）做成「在暫存目錄動態編譯一支含 `LocalDate.now()` 的隔離 fixture，再斷言同一條規則必須拋出違規」，比規格原本寫的「手工塞一行再還原」更好 —— 規格要的是「這條規則真的會擋」，手工驗證只在當下成立，動態 fixture 則是每次 CI 都重驗一次。**Claude 未對 #53 送出 review**（它在上一輪排程之間由 Codex 自行 auto-merge 合入），合併後由 Claude 複驗：`TimeZoneGuardTest.java` 存在、`verify` 綠、既有測試零修改。以下為當時的缺陷說明，保留給日後判斷用： —— **🔴 ← 目前這一項：Codex 補上「禁止無參數 `now()`」的自動化防線（G27）** —— G26 把唯一一處 bare `YearMonth.now()` 修掉了，驗收 (b) 也把「全 repo 零命中」寫成不變式，但**沒有任何自動化在看這條不變式**。下一支做日期運算的測試只要寫一個 `LocalDate.now()`，同一類時間彈就回來，症狀仍是「每月最後一天 16:00–24:00 UTC 全員 CI 紅」，仍然會被誤判成業務邏輯迴歸。**修法**：在 `backend/coffee-app/src/test/java/com/coffee/app/` 新增一支 ArchUnit 測試（建議檔名 `TimeZoneGuardTest.java`），對 `YearMonth`／`LocalDate`／`LocalDateTime`／`ZonedDateTime`／`LocalTime` 的**無參數** `now()` 下 `noClasses().should().callMethod(X.class, "now")`。**關鍵一：`ClassFileImporter` 不可加 `ImportOption.Predefined.DO_NOT_INCLUDE_TESTS`** —— G26 的缺陷就在測試碼裡，只掃 main 抓不到（既有的 `ModuleBoundariesTest` 正是加了那個選項，所以它當初放行了 G26）。**關鍵二：`Instant.now()` 必須放行** —— `BranchController.java:90` 與 `BranchHourOverrideTest.java:380` 都是 `Instant.now().atZone(TAIPEI)`，`Instant` 是絕對時刻、與時區無關，把它列進黑名單會讓這兩處無故變紅，並逼出「注入 `Clock`」那種規格沒要求的大改。**可行性已由 Claude 先行驗證**：全 repo 的測試都在 `backend/coffee-app/src/test/java/com/coffee/app`（`find` 確認，無其他模組有 `src/test`），所以一支放在 `coffee-app` 的測試就同時涵蓋 main 與 test；`archunit-junit5` 1.4.1 已在 `coffee-app/pom.xml:102-105` 的 test scope，**零新相依**。**驗收**：(a) 新測試存在且綠；(b) 暫時在任一既有測試塞一行 `LocalDate.now()` 會讓新測試紅（實作端自行驗證後還原，**不要把那一行留在 commit 裡**）；(c) 上述兩處 `Instant.now()` 維持不變且綠；(d) 既有測試一個字元都不修改；(e) `cd backend && ./mvnw -B -ntp verify` 綠。**不另立規格書的理由**：一支測試檔、零 migration、零正式程式、零新相依、零端點，缺陷定位與修法在本項已寫完，寫一份施工級規格書的成本高於實作本身；比照 G26，**本項不計入規格庫存**。**為什麼排在 G25 之後而不插隊**：G26 已經修好，現在沒有紅燈，這一項是回歸防護而非止血；G25 是已經寫定、Codex 可立刻開工的功能缺口，不應該被它推後。
17. ~~**Codex 依 [`specs/G24-branch-manager-day-settings.md`](specs/G24-branch-manager-day-settings.md) 實作店長的本店營業設定**~~ —— **已完成，[PR #57](https://github.com/choka1227/coffee_GPT6/pull/57) 於 2026-10-03 合併**（主線合併提交 `d29939e`，Flyway 實際占用 **V12**）。以下規格摘要保留為紀錄：規格書 v1.0 於 2026-10-02 完成，隨本輪 PR 進主線。四個施工階段：**S1** 權限常數 `BRANCH_HOURS_OVERRIDE` 與 `V12__branch_day_settings.sql`（純加法、零行為變更）、**S2** 例外日授權由「`BRANCH_MANAGE` + global」改為「`BRANCH_HOURS_OVERRIDE` + `actor.branch()`」並加 14 天範圍限制、**S3** `branch_day_overrides.last_order_minutes`（每日最後點餐）與解析、**S4** 前端「本店營業設定」頁。**Flyway 用 V12**（V11 由 G25 占用）。本規格結清 G19 §13.3 與 G25 §13.5 兩筆登記。**最容易出事的一條寫在規格 §13.1**：放寬 `saveOverride` 時順手把 `saveHours` 的 `global()` 檢查也拿掉，目前不會讓任何既有測試變紅，所以規格要求在 `BranchHoursHttpSecurityTest` 新增一條專門測它的案例。**為什麼排這一項**：P1 已清空、第 18 項的金流已由 PO 整批延後，而 P2 其餘項目分別被新依賴、產品決策或「要先有真實資料」擋住，詳見規格 §1.4
18. ~~**Codex 依 [`specs/G08-branch-product-stock.md`](specs/G08-branch-product-stock.md) 實作分店每日可售數量與自動售完**~~ —— **已完成，[PR #59](https://github.com/choka1227/coffee_GPT6/pull/59) 於 2026-10-03 合併**（Claude 於 head `2ade6b6` 送出 `APPROVE` 後由 auto-merge 合入，主線合併提交 `cc7974b`，S1–S4 四階段全數完成，Flyway 實際占用 **V13**，兩張表，進度報告見 [`reports/G08-branch-product-stock.md`](reports/G08-branch-product-stock.md)）。**審查發現兩處缺陷都在規格端、不在實作端**，已修為規格 v1.2，其中驗收 19a 尚未滿足，登記為 G08a（第 19 項）。**`codex/g08-branch-product-stock-s1` 分支已完成任務，不要再從它續作或開新分支。** 以下規格摘要保留為紀錄：規格書 **v1.1** 於 2026-10-02 完成（v1.1 依 Codex 在 [PR #55](https://github.com/choka1227/coffee_GPT6/pull/55) 的 `REQUEST_CHANGES` 修補了回補路徑的超賣缺口，新增 `branch_product_stock_reservation` 保留憑據表，推導見規格 §4.6），**四階段 S1–S4**（S1 資料層與讀寫端點／S2 扣減與取消回補／S3 菜單顯示／S4 前端），Flyway 預定占用 **V13**。S2 是四個裡最大的一個，規格 §9 寫了它合法的切半方式。**最容易出事的兩條：**(a) 同一訂單含同商品多行時要**先合併再判斷**，否則「僅剩 N 份」的 N 會是錯的（§5.2 第 1 步）；(b) 同一個 `Idempotency-Key` 重送**不得扣第二次** —— 既有的冪等檢查已經保證這件事，但一定要有測試證明它（§5.4、驗收 12）；(c) **回補一律依保留憑據，不得用「今天 + branchId + productId」推論** —— 推論會把未曾扣減的訂單也回補，真的會超賣（§4.6、§5.5、驗收 14a–14d）
19. ~~**Codex 修掉 G08 的徽章矛盾（G08a）**~~ —— **已完成，[PR #62](https://github.com/choka1227/coffee_GPT6/pull/62) 於 2026-10-04 合併**（Claude 於 head `d0550dd` 送出 `APPROVE` 後合入，主線提交 `4b85aa8`，單一階段、CI 綠、零 migration、零後端變更）。成因在規格端：G08 v1.1 的 §5.7 第 1 點只寫了剩餘徽章自己的條件，沒有定義它與售完徽章的關係，Codex 照字面實作是對的；修法由 **G08 規格 v1.2 §13.13 定案為「售完優先」**，`MenuView.vue` 的剩餘徽章條件加上 `p.availability !== 'SOLD_OUT' &&` 前綴，`MenuView.dom.test.ts` 補上「手動售完且 `remaining > 0` 時不顯示剩餘徽章」的斷言。**G08 §10 第 19a 條因此滿足，G08 全系列結案。****`codex/g08a-sold-out-badge` 分支已完成任務，不要再從它續作或開新分支**
20. ~~**Codex 依 [`specs/G20-item-level-promotions.md`](specs/G20-item-level-promotions.md) 實作品項層促銷**~~ —— **已完成，[PR #65](https://github.com/choka1227/coffee_GPT6/pull/65) 於 2026-10-04 合併**（Claude 於 head `87f4a33` 送出 `APPROVE` 後由 auto-merge 合入，主線合併提交 `e81fa44`，S1–S4 四階段全數完成）。**`codex/g20-item-promotions` 分支已完成任務，不要再從它續作或開新分支。** 審查發現兩處非阻擋事項，都已登記進第 21 項的 G20c 規格（對帳投影的折抵別名、`OrdersView.vue:275` 一處超出規格的文案變更）。規格書 v1.0 於 2026-10-04 產出。**Flyway 版號 V14**，新增 `item_promotions` 與 `order_item_promotions` 兩張表。四個施工階段：**S1** 資料層與總部維護端點（零計價影響）、**S2** 計價演算法純函式與九組單元測試（仍無呼叫端）、**S3** 訂單整合＋`checkout.ts` 的 POS 現金兩階段守門（前後端必須同階段落地，理由見規格 §9）、**S4** 前端維護頁與金額顯示。分支用 `codex/g20-item-promotions`。升排理由與「為什麼推翻 G07 §11.2 的『等真實資料』閘門」寫在規格 §1.4
21. ~~**Codex 依 [`specs/G20c-report-net-revenue.md`](specs/G20c-report-net-revenue.md) 修掉報表的淨營收歸屬與折抵口徑矛盾**~~ —— **已完成，[PR #69](https://github.com/choka1227/coffee_GPT6/pull/69) 於 2026-10-05 合併（主線合併提交 `fea0ef4`）**。S1–S3 三階段全數完成，**零 migration（`V15` 仍然空著）**，進度報告見 [`reports/G20c-report-net-revenue.md`](reports/G20c-report-net-revenue.md)。Claude 於 head `96a411c` 送出過一輪 `REQUEST_CHANGES`（驗收 3a 缺測試、既有 fixture 改動未揭露，兩項都不需要動生產程式碼），Codex 修正後於 head `0a65306` 取得 `APPROVE`。**那一輪也暴露了兩個規格端缺口，已由規格 v1.2 修補**（驗收 10 的「既有案例原封不動」是錯的；§11.1 的測試矩陣沒有任何一條碰到 `topToday`），見規格 §15。**`codex/g20c-report-net-revenue` 分支已完成任務，不要再從它續作或開新分支**
22. **Codex 依 [`specs/G20a-promotion-hints.md`](specs/G20a-promotion-hints.md) 補上菜單與購物車的促銷提示** —— 規格書 v1.0 已於 2026-10-04 隨 [PR #67](https://github.com/choka1227/coffee_GPT6/pull/67) 合併進主線（`55e6264`）。**實作已完成**：[PR #72](https://github.com/choka1227/coffee_GPT6/pull/72) S1–S3 全綠，Claude 於 head `2a84058` 送出 `APPROVE`，待 auto-merge。**零 migration（`V15` 仍然空著）**、**不碰 `OrderService.create` 的計價路徑**、**不新增任何權限常數**。三個施工階段：**S1** 新增 `GET /api/promotions/active?branchId=`（任何已登入者可讀，顧客安全投影，並把 `PromotionService.apply` 裡既有的「此刻有效規則」查詢抽成共用私有方法）、**S2** 菜單商品卡的提示文字、**S3** 購物車的提示與「再加 N 件可享」門檻。分支用 `codex/g20a-promotion-hints`。**最重要的一條設計決策是 §13.1：前端一個折抵金額都不算** —— 提示只說「第 2 件 5 折」，不說「可省 25 元」，因為那會變成計價演算法的第二份實作、必定與後端漂移。想顯示真實預估金額的正確做法是後端試算端點，已登記為 **G20h**。**三個會咬人的細節：**(a) `percent` 是「折掉的比例」，台灣的「X 折」是「付的比例」，兩者是 `100 − percent`，寫反了不會讓任何測試變紅（規格 §5.5 有五個值的對照表，驗收 8 把它釘成測試）；(b) `stubApi` 對未設定的路由回 404，`loadMenu` 多打一支端點之後 **`MenuView.dom.test.ts` 的預設 stub 必須一起加**（與 PR #65 在 `OrderPaginationTest` 踩到的同一類坑）；(c) 提示**不要**放進照片的徽章位 —— 那裡已有 `sold-out` 與 `p.badge` 兩個排他競爭者，**G08a 那個缺陷的成因就是兩個徽章共用一個位置而沒定義優先序**，放第三個進去是再製造一次同一個 bug（規格 §13.6）
23. ~~**Codex 依 [`specs/G20g-report-chart-tests.md`](specs/G20g-report-chart-tests.md) 補上報表圖表層的測試**~~ —— **已完成，[PR #75](https://github.com/choka1227/coffee_GPT6/pull/75) 於 2026-10-07 合併**（主線合併提交 `cd5801d`，單一階段、CI 綠、零 migration、後端零變更、生產程式碼只動一行，進度報告見 [`reports/G20g-report-chart-tests.md`](reports/G20g-report-chart-tests.md)）。**`codex/g20g-report-chart-tests` 分支已完成任務，不要再從它續作或開新分支。** 審查時發現一處**規格端**的缺陷（§5.2 的 fixture 範例把 `day: "02"` 寫成 `orders: 0`，與驗收 3「`revenue` 與 `orders` 每一筆都不相等」互相矛盾；Codex 改成 `orders: 4` 是對的處置），不影響已合併的實作。以下為施工時的規格重點，保留給日後判斷用：**零 migration（`V15` 仍然空著）**、**後端零變更（一行 Java 都不改）**、**不新增任何 npm 依賴**、生產程式碼只動一行。**只有一個施工階段**（約 220 行，全是測試與測試輔助，切不開的理由與「真的跑不完時那一刀切在哪」都寫在規格 §9）。分支用 `codex/g20g-report-chart-tests`。**升排理由見規格 §1.4** —— 工作順序到第 22 項就用完了（第 24 項金流由 PO 整批延後），必須從 P2 升排一項，而 G20g 是唯一「沒有外部依賴、不需要產品決策、不需要真實資料、不引入新依賴、**且有新鮮證據**」的一項。新證據是 PR #69 的 **run #497**：DOM 測試掛載真的 `Chart.vue`，`echarts` 的 `init()` 在 jsdom 取不到 canvas context 而整支炸掉，修法是把 `Chart.vue` 整個 `vi.mock` 掉 —— **那把被測對象排除在測試之外**，於是三張圖只有一張的 option 被斷言過、`Chart.vue` 自己零覆蓋。**三個會咬人的細節：**(a) `Chart.vue` 的測試要 mock 的是 **`echarts/core`**（連 `use` 一起，它在模組層 `:13` 就被呼叫，漏了會在 import 時 `TypeError`），**不是** mock `Chart.vue` —— mock 掉被測對象就等於沒測，理由見規格 §13.2 的三選項比較；(b) `watch` 裡 `setOption(v, true)` 的 **第二個引數 `true` 就是 `notMerge`，一定要斷言它** —— 拿掉它會讓切換月份時舊 series 與新的合併，而「`setOption` 有被再呼叫一次」這種斷言擋不到任何東西（規格 §5.4(b)、驗收 8）；(c) `ResizeObserver` 在 jsdom 不存在，要在 `beforeEach` 自己塞 stub，**不要**加進共用的 `setup.dom.ts`（規格 §5.4）。**另含一行真實缺陷修正**：分類圓餅圖的 `v-if` 看的是 `report.revenue`，資料源卻已是 `categoriesNet`，「優惠碼把整單折到 0」時圖會被整個藏起來（`ReportsView.vue:366`，規格 §5.5）。這是 Claude 在 PR #69 列為「不擋，登記備查」第 3 項的那一條，依規格 §13.4 的理由併進來 —— **不值得為它單獨推 commit，不等於不該修**
24. **Codex 依 [`specs/G20h-order-preview.md`](specs/G20h-order-preview.md) 實作後端購物車試算端點** —— 規格書 v1.2 於 2026-10-07 產出，隨本輪 PR 進主線。**零 migration（`V15` 仍然空著）**、**零新依賴**、**不改建立訂單的任何請求／回應欄位**。三個施工階段：**S1** 把 `Discounts.apply` 拆出不消耗兌換次數的 `quote`、**S2** 抽出 `OrderService` 的共用計價方法 `price()` 並新增 `POST /api/orders/preview`、**S3** 前端 debounce／競態處理與購物車金額顯示（**順道做掉 G20i 的樣式**）。分支用 `codex/g20h-order-preview`。**最重要的一件事是規格 §5.1：`DiscountService.apply` 會 `redeemed_count+1` 並取行鎖**，試算若直接重用它，顧客光是在購物車加加減減就會把優惠碼額度用光，而且 `maxRedemptions` 為 null 時完全看不出異狀 —— 所以 S1 必須先把 `quote` 與 `apply` 分家。結構上的防線是 §5.3 的 `@Transactional(readOnly = true)`：**讓「試算不可寫入」由資料庫強制，而不是靠下一個改這段程式的人記得**。**驗收 6（先 preview 再 create，比對 `total` 相等）是本規格存在的理由** —— 它如果紅了不要調期望值，要去找為什麼兩條路徑算出不同的數字
25. 金流那條線（G01–G04 其餘部分）待進入綠界串接階段再排

> **與 [PR #70](https://github.com/choka1227/coffee_GPT6/pull/70)（G20g）的編號協調已結案。** #70 已於 2026-10-07 合併進主線（`b2fc82a`），本 PR 於本輪把最新 `feature/init-project` **merge 進** `claude/spec-g20h`（**不 rebase、不 force push**）解掉本檔的五處衝突，依當初約定的順序定案為：**第 23 項 = G20g、第 24 項 = G20h、第 25 項 = 金流**。
> **當初承諾的三件事都已執行**：(1) 第 23 項改用 #70 帶進主線的完整敘述，不是本 PR 預留的那一行佔位；(2) 沒有重複補第二列 G20g；(3) P1 表的 G20g 與 G20h 兩列都保留，P2 表的 G20g 與 G20h 都已改為「已升為 P1」。
> 兩份規格在程式碼上的交集是**零**：G20g 動 `ReportsView.vue` / `Chart.vue` / `harness.ts`，G20h 動 `MenuView.vue` / `coffee-orders` / `coffee-catalog`。兩份都零 migration，**不可能撞 Flyway 版號**（詳見 G20h §12 的對照表）。

> **與 [PR #66](https://github.com/choka1227/coffee_GPT6/pull/66)（G20c）的編號協調已結案。** #66 已於 2026-10-04T12:39:59Z 合併進主線（`e1f4a4d`），本 PR 於本輪把最新 `feature/init-project` merge 進 `claude/spec-g20a`（**不 rebase、不 force push**）解掉本檔的五處衝突，依當初約定的順序定案為：**第 21 項 = G20c、第 22 項 = G20a、第 23 項 = 金流**，規格庫存 **2 份（G20c + G20a，已達上限）**。
> **當初承諾的三件事都已執行**：(1) #66 在 P2 表加的 `| G20a | … | 未開始 |` 那一列已改為「已升為 P1，見上方 P1 表」；(2) 沒有重複補第二列 G20a；(3) P1 表的 G20c 與 G20a 兩列都保留。
> 兩份規格在程式碼上的交集只有前端 `shared/types.ts` 一個檔，而且行不重疊（G20c 改報表段、G20a 在 `PromotionRule` 之後加一個新 interface）；後端交集是**零**（G20c 動 `coffee-reporting`，G20a 動 `coffee-catalog`）。兩份都零 migration，**不可能撞 Flyway 版號**。所以**實作的先後完全自由**，上面的順序只是這兩支文件 PR 的合併順序留下的編號結果。

> **與 [PR #54](https://github.com/choka1227/coffee_GPT6/pull/54)（G24）的編號協調已結案。** #54 已於 2026-10-02 合併進主線（主線合併提交 `1630b2d`），第 16 項（G27）由它標為完成、G24 由它插入為第 17 項。本 PR 於本輪把主線 merge 進來解掉本檔的衝突，依當初約定的順序把 G08 排為**第 18 項**、金流順延為**第 19 項**，三項都保留、沒有刪掉任何一項（**上述序號是當時的狀態**；2026-10-03 登記 G08a 後插入為第 19 項，金流再順延為**第 20 項**，以上方「排定的工作順序」為準）。兩份規格在程式碼上的檔案交集是**零**（G24 動 `coffee-branches` 與 `Identity.java`、占 V12；G08 動 `coffee-catalog`、占 V13、不碰 `Identity.java`），所以**實作的先後完全自由**。

### 待 PO 驗收（不擋合併）

以下項目已依設計決策明確**不擋合併**，但仍待 PO 在可部署環境實機確認。Claude 與 Codex 都沒有可執行的環境（見 G07 §11.11），所以列在這裡而不是留在 PR 上擋著。

**目前沒有待 PO 實機驗收的項目。**

G07 驗收 21b／22／23 原本列在這裡，已由 G22 S3（驗收 13）以同一組數字（小計 140、折抵 14、應收 126）轉為自動化斷言，隨 PR #39 於 2026-09-29 合併，**該列因此移除**。

**但自動化替代有邊界，這半仍然沒有防線**：G22 只測純函式，能證明「顯示與送出的數字對不對」，證明不了「欄位有沒有出現在畫面上」（模板 `v-if` 的可見性）。那一半登記為 **G23**（P2），**已隨 [PR #45](https://github.com/choka1227/coffee_GPT6/pull/45) 於 2026-09-30 合併** —— 模板可見性現在由 `MenuView.dom.test.ts` 等元件層測試把關，不再只靠互審讀 diff。本段保留為紀錄，說明「純函式測試證明不了模板可見性」這條邊界為什麼存在，**不是待辦項**。

> 這一節保留空表頭會讓下一輪誤以為表格壞了，所以改寫成文字。**日後又有項目時，把表格與表頭一起加回來**，格式：`| 項目 | 來源 | 內容 | 對照資料 |`。

### 排程注意

PR #9（G01 對帳）已於 2026-09-17 08:32 合併，`OrderService` 的衝突風險解除，**G06 可以直接開工**。G06 的 Flyway 版號用 **V3**：`V2__payment_reconciliation.sql` 已隨 #9 進入主線。

G01 留在主線的缺陷已寫成獨立規格 [`specs/G01a-reconciliation-fixes.md`](specs/G01a-reconciliation-fixes.md)，用分支 `codex/g01-fixes`，**不要**夾在 G06 的 PR 裡 —— 兩件事、兩支分支。兩者動的是不同模組（`coffee-payments` vs `coffee-orders` / `coffee-catalog`），可以並行。**兩者皆已合併（#14、#15），此段保留為紀錄。**

**目前（2026-10-07，第二輪更新）規格庫存 1 份：G20h（第 24 項，本 PR）。** G20g（第 23 項）的實作已隨 [PR #75](https://github.com/choka1227/coffee_GPT6/pull/75) 於 2026-10-07 合併（主線合併提交 `cd5801d`，Claude 於 head `b703554` 送出 `APPROVE` 後由 auto-merge 合入），**不再計入庫存**；G20c（第 21 項）實作已隨 [PR #69](https://github.com/choka1227/coffee_GPT6/pull/69) 於 2026-10-05 合併（`fea0ef4`）、G20a（第 22 項）實作已隨 [PR #72](https://github.com/choka1227/coffee_GPT6/pull/72) 合併，兩者同樣不計入。**庫存 1 份雖然低於上限 2，但 2026-10-07 這一輪仍然沒有產出第二份** —— 工作順序到第 24 項就用完了（第 25 項金流由 PO 整批延後），而 P2 剩下的每一項都還被原本的閘門擋著：G20b／G28 要先有真實資料，G20f 的三個拒絕理由（G20c §13.2）一條都沒鬆動，G20d 跟著 G20b，G05／G20k 要引入新依賴，G12 是外部依賴，G16／G21 要 PO 決定，G20i 已安排搭 G20h S3 的順風車，G20j 要一整套限流基礎設施。**照 AGENTS.md 的原則空跑，不從上表硬挑一項升排。** 真正會解除的事件是：G20h 上線後有第一批真實促銷／優惠碼資料、PO 解除金流的延後、或 PO 做出相依與產品決策。**即時數字以本行為準，不要從下方任何「已結案，保留為紀錄」的段落推導。**

**2026-10-04 這一輪恢復產出：G20。** 2026-10-03 那一輪逐項排除後沒有可開工項（理由見下方「為什麼 2026-10-03 這一輪沒有產出新規格」，整節保留為紀錄），本輪重新檢視該表時發現 **G20 與 G21 當時沒有被列進去** —— 兩者的閘門都不是 PO 決定，而是 G07 §11.2 的「等真實 `order_discounts` 資料」。G20 的閘門已由本輪推翻（理由寫在規格 §1.4：閘門在沒有可部署環境的現況下不可能滿足，而猜錯的風險已由「規則收斂成兩種型態」壓低）；G21 仍然卡著，因為會員價要先有會員，而顧客自助註冊是 G16，屬 PO 決定。

> **以下三段（G19 的登記紀錄、G19 的檔案衝突說明、G23 的檔案衝突說明）全部是歷史紀錄，不是待辦項。** G19 的實作已隨 [PR #42](https://github.com/choka1227/coffee_GPT6/pull/42)、G23 的實作已隨 [PR #45](https://github.com/choka1227/coffee_GPT6/pull/45) 於 2026-09-30 合併進主線。保留它們是因為裡面的排程理由（檔案交集怎麼算、為什麼序列化）對下一輪仍有參考價值；**自動選工時請以「排定的工作順序」與本段第一行的庫存數字為準，不要從這三段推導還有什麼要做。**

G19 的登記紀錄（已結案，保留為紀錄）：[`specs/G19-branch-hour-overrides.md`](specs/G19-branch-hour-overrides.md) **v1.1**，隨 [PR #40](https://github.com/choka1227/coffee_GPT6/pull/40) 登記；v1.1 依 Codex 在 [PR #40](https://github.com/choka1227/coffee_GPT6/pull/40) 的 `REQUEST_CHANGES` 修正 §6.3 的跨夜尾段優先序。G22 的實作已隨 [PR #39](https://github.com/choka1227/coffee_GPT6/pull/39) 於 2026-09-29 合併，G07 隨 PR #31、G09 隨 PR #36 於 2026-09-28 合併；G14 隨 PR #29、G10 隨 PR #26、G11+G15 隨 PR #22、G13 隨 PR #20、G06 隨 PR #14、G01a 隨 PR #15，**全部沒有未解除的閘門**。

**（已結案，保留為紀錄）G19 只動了 `coffee-branches` 一個後端模組**（規格 §3），與其他模組沒有檔案衝突。它動到的前端檔案是 `BranchesView.vue`、`MenuView.vue` 與新增的 `modules/branches/overrides.ts` —— `MenuView.vue` 剛被 G22 改過（抽出 `checkout.ts`），**G19 S3 只碰它的打烊訊息文字來源，不得再動 `checkout()` 或任何金額路徑**。

**（已結案，保留為紀錄）G23 只動了 `frontend/`，而且沒有修改任何 `.vue`**（規格 §3、驗收 14）。它新增的是 `src/shared/testing/` 底下的掛載工具與兩支 `.dom.test.ts`，改的既有檔案只有 `frontend/package.json`（+2 個 devDependency）、`package-lock.json` 與 `vitest.config.ts`。**與 G19 的檔案交集是零**，兩者可以任意順序合併；依「一次一份」通則排在 G19 之後開工。唯一的交界是語意上的：**G23 不得斷言任何打烊訊息的文字**，因為 G19 S3 會改它的來源（規格 §12）。

Flyway 版號現況：`V1__coffee_schema.sql`、`V2__payment_reconciliation.sql`（#9）、`V3__product_options.sql`（#14）、`V4__branch_menu_availability.sql`（#20）、`V5__audit_trail.sql`、`V6__cash_sessions.sql`（#22）、`V7__order_list_indexes.sql`（#26）、`V8__branch_business_hours.sql`（#29）、**`V9__order_discounts.sql`（#31，已於 2026-09-28 合併）** 全部已進主線。**G09（#36）與 G22（#39）都不新增任何 migration。G19 的 `V10__branch_hour_overrides.sql` 已隨 #42 於 2026-09-30 合併進主線。G23 的 #45 同樣不新增任何 migration，已於 2026-09-30 合併。**`V10__branch_hour_overrides.sql`（#42）、`V11__branch_last_order.sql`（#51）亦已進主線**。G24 預定占用 **`V12__branch_day_settings.sql`**（規格 §4.0 已寫死檔名），所以下一份需要 migration 的規格自 **`V13`** 起算。

**規格庫存目前是 2 份：G24 與 G08**（兩份都在 2026-10-02 登記，規格書各為 v1.0）。G25（PR #51）、G26（PR #48）與 G27（PR #53）的實作都已合併；G26 與 G27 都不另立規格書、都不計入庫存，所以庫存的變化只來自 G24 與 G08 這兩份（0 → 1 → 2）。上限 2 份，**已滿，下一輪不產出新規格**。

**版號不得互換**（原因保留為紀錄）：Flyway 預設不接受事後補插較小版號（out-of-order），所以 G07 就算先於 G14 開工也一樣要用 V9，空一個版號的成本是零。G14 先合併，這條規則這一輪沒有被動用到，但規則本身不變。

規格庫存維持 2 份（一份在做、一份待命）是刻意的上限：實作端一次只做一份，且一份可能跨多次執行，堆更多只會變成永遠做不完的清單。**庫存滿 2 份時不再產出新規格。**

**（已過時，保留為紀錄）本段寫於 2026-10-04 G20 規格產出的那一輪，當時庫存 1 份：G20。** G20 的實作其後已隨 [PR #65](https://github.com/choka1227/coffee_GPT6/pull/65) 於同日合併（主線合併提交 `e81fa44`），庫存改由 G20c 占。**即時的庫存數字一律以上方「排程注意」段第一行為準，不要從本段推導。** 下表是再上一輪那兩份的結案紀錄：

| 規格 | 狀態 | Flyway |
| --- | --- | --- |
| [`specs/G24-branch-manager-day-settings.md`](specs/G24-branch-manager-day-settings.md) | **實作已合併**（[PR #57](https://github.com/choka1227/coffee_GPT6/pull/57)，2026-10-03，主線 `d29939e`） | V12（已占用） |
| [`specs/G08-branch-product-stock.md`](specs/G08-branch-product-stock.md) | **實作已合併**（[PR #59](https://github.com/choka1227/coffee_GPT6/pull/59)，2026-10-03，主線 `cc7974b`）；規格修為 **v1.2**，驗收 19a 由 G08a 補 | **V13**（已占用） |

**Flyway 版號：`V14` 已由 G20 實際占用**（`V14__item_promotions.sql`，PR #65，兩張表），**下一份需要 migration 的規格自 `V15` 起算**（G20c 零 migration，不占用 V15）。本段寫於 G20 合併前，當時主線最高是 `V13__branch_product_stock.sql`（G08，PR #59，兩張表）；`V12__branch_day_settings.sql`（G24，PR #57）在它之前。兩個預定版號都已**實際占用**，不再是預定。**即使 G24 或 G08 最終被擱置而版號從未使用，也不要回頭補用那個空號** —— Flyway 預設不接受事後補插較小版號（out-of-order），留一個空號的成本是零。

**（已結案，保留為紀錄）兩份規格的實作先後完全自由**，程式碼上的檔案交集是零（G24 動 `coffee-branches` 的 `BranchService` 與 `Identity.java`；G08 動 `coffee-catalog` 的 `CatalogService`，刻意複用既有的 `MENU_AVAILABILITY` 權限而不碰 `Identity.java`，理由見 G08 §7.1）。實際合併順序是 G24（#57）先、G08（#59）後，兩者都沒有踩到對方。


> **設計決策（2026-09-19，依 PR #18 上 Codex 的 `REQUEST_CHANGES`）：G11+G15 序列化排在 G13 之後，不開放並行。**（G13 已於 2026-09-20 合併，這道閘門本身已履行完畢；決策保留，因為它定義的是「一次一份」這個通則，不只是 G13 這一次。）
> 理由：(1) 實作端只有 Codex 一個，`AGENTS.md`「施工階段與中斷續作」本來就是「一次執行推進一個階段、一個 PR 一支分支」，並行在現況下不存在可執行的意義；(2) 兩份規格的交集 `Identity.PERMISSIONS`、`InitialData` 角色清單與 Flyway 版號，正好是撞了就要整份重跑的那一類衝突，序列化把它降為零成本；(3) 本檔的「排定的工作順序」是實作端的唯一約束來源，同一份文件不能同時給出「必須等 G13」與「可並行」兩個答案。
> 推翻它的代價：若日後真要並行（例如多了第二個實作端，或 G13 長期卡住），要先改 `AGENTS.md` 的施工規則與本節的順序定義，並在兩份規格裡把 migration 版號與 `Identity.PERMISSIONS` 的分配方式明確切開 —— 不能只在本檔局部放寬。

## G08a —— G08 的剩餘徽章與「今日售完」徽章並存（2026-10-03 登記，2026-10-04 隨 PR #62 結案）

**症狀：** POS／顧客菜單的商品卡上，「今日售完」與「剩 N 份」兩個徽章會同時出現 —— 條件是店員按過「標記售完」，而當日 `branch_product_stock.remaining` 仍大於 0（例如早上設了備量 20、賣了 5 份，中午因為品質問題手動標記售完）。畫面同時說「今天不賣了」和「還剩 15 份」，互相矛盾。

**成因在規格端，不在實作端。** G08 v1.1 的 §5.7 第 1 點只寫了剩餘徽章自己的顯示條件（`remaining !== null && remaining > 0`），沒有定義它與既有售完徽章（`MenuView.vue:552`，G13 做的）之間的關係。Codex 在 [PR #59](https://github.com/choka1227/coffee_GPT6/pull/59) 照字面實作成兩個獨立的 `v-if`，**那是正確地實作了一份沒寫清楚的規格**。審查時發現並判定為規格缺陷，依 `CLAUDE.md`「Codex 的產出有問題時走下一輪規格修補」處理：不擋 #59 的合併，改為修規格並登記本項。

**修法（已由 G08 規格 v1.2 §13.13 定案「售完優先」）：**

1. `frontend/src/modules/ordering/MenuView.vue` 的剩餘徽章條件加前綴 → `v-if="p.availability !== 'SOLD_OUT' && p.remaining !== null && p.remaining > 0"`
2. `frontend/src/modules/ordering/MenuView.dom.test.ts` 補一條斷言：`availability='SOLD_OUT'` 且 `remaining=5` 時，`.remaining-badge` 不存在、`.product-badge.sold-out` 存在

**為什麼是「售完優先」而不是反過來或並列：** 手動售完的語意比備量更強（店員可能是品質問題或臨時決定），而畫面要回答的只有「現在能不能賣」。完整理由與推翻它的代價寫在 G08 §13.13。**後端不動** —— `GET /api/menu` 繼續照 §5.6 回傳真實的 `remaining`，只在前端決定不渲染，所以「恢復供應」後徽章會自己正確顯示，沒有任何狀態需要回捲。

**規模：** 一行正式程式 + 一條測試。單一階段、零 migration、零後端變更、零 API 變更。**不另立規格書** —— 與 G26／G27 同樣的處理：一行修正的說明寫在本段與 G08 §13.13 已經足夠，另開一份規格書的成本高於修正本身。**驗收就是 G08 §10 的第 19a 條。**

**不計入規格庫存**（同 G26／G27）。排為工作順序第 19 項。

## 為什麼 2026-10-03 這一輪沒有產出新規格（已結案，保留為紀錄）

規格庫存是 0 份，低於上限 2 份，照「排定的工作順序」本來應該產出下一份。**但工作順序裡已經沒有可開工的下一項，而 P2 剩下的每一項都被「不是設計決策」的東西擋住**，所以本輪不產出。把逐項排除寫在這裡，是為了讓下一輪不必重新推導一次，也不要誤以為表格壞了。

| 項目 | 擋住它的是什麼 | 屬於誰的決定 |
| --- | --- | --- |
| 工作順序第 20 項（金流 G01–G04 其餘部分） | PO 於 2026-09-17 整批延後 | **PO** |
| G28 永續庫存帳與選項層庫存 | 自己的開工前提（G08 §14）是「G08 上線後要先有一段真實備量資料」。G08 今天才合併，還沒有任何人用過 | 時間（要真實資料） |
| G05 Session 集中化 | 要先引入 Redis / Spring Session。`AGENTS.md`「不得引入新框架或新依賴」的預設是不引入 | PO（要不要加相依） |
| G12 外送、硬體印單 | 要先選外部廠商與硬體型號 | **PO**（採購決策） |
| G16 顧客自助註冊 | 涉及濫用防治與個資法遵 | **PO**（產品與法遵決策，明文不在設計決策授權範圍內） |
| G17 點餐 UI 常用組合快捷 | 要先有真實訂單資料才知道哪些組合常用 | 時間（要真實資料） |
| G20 品項層折扣與買一送一 | 「折的是哪一件」需要真實的促銷方案才定得出規則，且會動到 `order_items` 快照與報表的品項營收歸屬 | **PO**（要先有促銷方案） |
| G21 會員價與員工價 | 要先有會員／員工身分模型（`accounts` 目前只有角色與分店） | **PO**（要先有身分模型） |

> **上表的 G20 列已於 2026-10-04 被推翻，不要再依它判斷。** 那一列寫的「要先有促銷方案」閘門在現況下等於「永遠不做」（沒有可部署、可營業的環境，G07 §11.11 已登記），推翻的完整理由見 [`specs/G20-item-level-promotions.md`](specs/G20-item-level-promotions.md) §1.4。G20 規格書已於 2026-10-04 產出、實作已完成（PR #65）。**其餘各列仍然成立。**

**刻意不做的事：** 不為了填滿這一輪而把 G28 的「選項層庫存」提前拆出來。它在 G08 §13.2 有明確的排除理由（選項與商品是多對多、一個選項被多個商品共用、還有 `min_select`／`max_select` 的組合約束，是另一個資料模型），而我**今天沒有拿到任何推翻那個理由的新證據** —— G08 才剛合併、還沒有人用過。`CLAUDE.md` 要求決策留理由是為了讓下一輪能推翻它，不是為了讓下一輪在沒有新理由時推翻它。

**下一輪的判斷依據：** 先做工作順序第 19 項（G08a）。它合併之後，若上表仍然沒有任何一項解除擋住它的東西，就**照常空跑**，不要從上表挑一項硬排。真正會解除的事件是：PO 解除金流的延後、PO 決定加相依或做採購／法遵決策、或 G08 累積了一段真實備量資料（那會同時解開 G28 與 G08 §13.8 的報表庫存維度）。

## 修訂紀錄

| 日期 | 變更 |
| --- | --- |
| 2026-10-07 | **（第二輪）審查 G20g 實作 [PR #75](https://github.com/choka1227/coffee_GPT6/pull/75) 並送出 `APPROVE`**（head `b703554`，單一階段完成、`verify` ×2 綠、零 migration、後端零變更、零新 npm 相依、生產程式碼只動一行；驗收 1–15 逐條核對通過）。**已由 auto-merge 合入主線（`cd5801d`）**，第 23 項結案，規格庫存降為 **1 份（只剩 G20h）**。審查時發現一處**規格端**缺陷：G20g §5.2 的 fixture 範例把 `day: "02"` 寫成 `orders: 0`，與自己的驗收 3「`revenue` 與 `orders` 每一筆都不相等」互相矛盾 —— Codex 改成 `orders: 4` 是正確處置，**實作無缺陷，不需回修**，記錄於此供日後判斷。**庫存 1 份仍低於上限 2，但本輪沒有產出第二份規格**，逐項排除的理由見上方庫存行。 |
| 2026-10-07 | **（第三輪）依 Codex 在 [PR #73](https://github.com/choka1227/coffee_GPT6/pull/73) 的第二次 `REQUEST_CHANGES` 把 G20h 規格修為 v1.2**，並把本檔三處 G20h 的現行版本標記（P1 表、P2 表、工作順序第 24 項）由 v1.1 同步為 v1.2。**退件成立**：v1.1 的 §9 S1／§11.1 要求在 `DiscountRedemptionTest` 新增 `quote`／`apply` 案例，但 §5.2 與驗收 14 把同一個檔案列為「一字不改且全綠」，而驗收 14 就在 S1 的驗收子集裡 —— 實作者**無法同時滿足**。驗收 14 已收斂為只管「**既有**案例與斷言一字不改（不得修改、放寬、刪除或 `@Disabled`）」並明寫「依規格新增案例不違反本條」，§5.2／§9／§11.1／§11.5 同步。**本檔的版本標記一併更新，是因為本檔是選工與核對的依據**：2026-09-29 的 G19 退件就是「改了規格本文卻漏了本檔的狀態入口」，不重複同一次失誤。規格的 API、施工階段、權限與金額規則一字未改，**庫存仍是 1 份** |
| 2026-10-07 | **把主線 merge 進 `claude/spec-g20h` 解掉本檔五處衝突**（#70 已合併，`b2fc82a`），工作順序定案為 23 = G20g、24 = G20h、25 = 金流。同時**依 Codex 在 [PR #73](https://github.com/choka1227/coffee_GPT6/pull/73) 的 `REQUEST_CHANGES` 把 G20h 規格修為 v1.1**：`@Transactional(readOnly = true)` 原本被寫成「資料庫強制唯讀、日後有人在共用路徑加寫入會在 CI 立即失敗」，這個宣稱不成立 —— 本專案沒有設定 `setEnforceReadOnly(true)`，而 CI 跑的 H2 會忽略 `Connection.setReadOnly(true)` 這個提示。退件成立，已改為「生產（PostgreSQL）可能擋得住、CI（H2）擋不住」的誠實敘述，並把「可驗證的 DB 級唯讀紅線」登記為 **G20k**。 |
| 2026-10-05 | **登記 G20h 規格書（[`specs/G20h-order-preview.md`](specs/G20h-order-preview.md)，v1.0）：後端購物車試算端點**，升為 P1、排為工作順序**第 24 項**（金流順延為第 25 項，與 #70 的編號協調見「排定的工作順序」段末）。**選它的理由**：G20a 的實作於本日合併，庫存降到 1 份，而 G20h 的開工前提（G20 與 G20a 都在主線）正好於同一天滿足；它零 migration、零新依賴、零產品決策，是 P2 裡唯一不需要真實資料或 PO 判斷就能開工的一項（G20b／G28 要真實資料，G20f 的拒絕理由未鬆動，G05 要新依賴，G12 要外部硬體）。**成因**：G20a §13.1 刻意只顯示規則條件不顯示金額（前端重做計價必定漂移），把「顯示真實預估金額」整個切出來給 G20h —— 所以今天顧客看得到「有促銷」卻看不到「我要付多少」，購物車的「總計」是**毛額**，比他實際被扣的多，而只有店員的現金收銀（`POS_CASH_TWO_STAGE`）走得到那四行真實金額。**規格最重要的發現是 §5.1**：`DiscountService.apply:150` 會 `redeemed_count+1` 並以 `for update` 取行鎖 —— 試算若直接重用它，顧客在購物車加加減減就會把優惠碼額度用光、熱門碼的行鎖還會卡住真正在下單的交易，而且 **`maxRedemptions` 為 null 時完全看不出異狀**，要到行銷問「為什麼這個碼顯示用了 8000 次但訂單只有 30 筆」才會發現。因此 S1 先把 `quote`（驗證＋計算，不消耗）與 `apply`（多做消耗）分家，共用同一份 `resolve`。**結構上的防線是 §5.3 的 `@Transactional(readOnly = true)`**：讓「試算不可寫入」由 PostgreSQL 強制，而不是靠下一個改這段程式的人記得 —— 驗收 7 只能驗它當下沒寫入，驗不了半年後新增的那一行。**驗收 6（先 preview 再 create 比對 `total` 相等）是本規格存在的理由**，紅了要找根因而不是調期望值。前端競態用單調序號而非 `AbortController`（§13.5：後者要改所有端點共用的 `api()`）。**G20i（促銷提示的樣式）搭 S3 順風車**，因為 S3 本來就要動 `MenuView.vue`。同批登記 **G20j**（試算端點的限流 —— 目前靠前端 debounce 自律，繞過前端直接打不受限，但本 repo 沒有任何限流基礎設施，要做就是一整套）。本輪未發現需要 PO 決定的事項 |
| 2026-10-05 | **（第二輪）審查 G20a 實作 [PR #72](https://github.com/choka1227/coffee_GPT6/pull/72) 並送出 `APPROVE`**（head `2a84058`，S1–S3 全部完成、`verify` ×2 綠、零 migration、零權限常數、未動 `OrderService`）。實作端把 §5.3 的 `@RequestParam String branchId` 改為 `required = false` 並主動揭露 —— **那是對的**，因為 `required=true` 會讓 Spring 在進到 Service 前丟英文例外，回不出 §6.3 指定的 `請選擇分店`；**矛盾在規格端**，已修為 G20a v1.1。同批修掉 G20a 驗收 12 的逃生口（原寫「測試**或 code review**」，與 §11.2「唯一一條靠結構維持的紅線」互相抵消；收緊為必須有測試，**不回頭要求 #72 補**），並登記 **G20i**（促銷提示無樣式）。**同時依 Codex 對 [PR #70](https://github.com/choka1227/coffee_GPT6/pull/70) 的 `REQUEST_CHANGES` 修正 G20g 為 v1.1 —— 三項查證後全部成立，全數採納**：(1) `vi.mock` 的 factory 不能依賴本檔靜態 import 的 binding（執行時機早於 binding 初始化，且**取決於模組解析次序、會時好時壞**），改定 async dynamic import 為唯一寫法並收緊驗收 2；(2) `vi.clearAllMocks()` **不會**清掉 `mockReturnValue`（只清呼叫歷史，重設實作的是 `resetAllMocks`）—— 寫法本身對（理由是測試隔離），但錯的因果會讓人在遇到「0 次呼叫」時查錯地方，已改寫並指向真正的失敗模式（hoisting）；(3) G20g §12 宣稱與 G20a 的交會點是 `harness.ts` —— **那個交集不存在**，G20a §11.3 改的是 `MenuView.dom.test.ts` 的 `mountMenu`、§11.4 明文禁止動 `harness.ts`，PR #72 的 11 個檔案裡也確實沒有它；已刪除並改為「不需要合併順序協調」。**推測出來的交集比漏寫交集更糟**：它會讓實作端為不存在的衝突預留處理。本輪未發現需要 PO 決定的事項 |
| 2026-10-05 | **登記 G20g 規格書（[`specs/G20g-report-chart-tests.md`](specs/G20g-report-chart-tests.md)，v1.0）並排為工作順序第 23 項**，原第 23 項（金流）順延為第 24 項。同時把第 21 項（G20c）標記完成 —— 其實作已隨 [PR #69](https://github.com/choka1227/coffee_GPT6/pull/69) 於 2026-10-05 合併（主線 `fea0ef4`），Claude 於 head `0a65306` 送出 `APPROVE`（前一輪 `96a411c` 的兩項 `REQUEST_CHANGES` 已修正，且生產程式碼零變動）。**規格庫存維持 2 份：G20a、G20g。** G20g 的升排理由見規格 §1.4（工作順序用完，它是唯一不需新依賴／產品決策／真實資料、且有 PR #69 run #497 新證據的一項）；**本輪刻意不選 G20f 的理由見規格 §13.1**，並已同步寫進 P2 表的 G20f 列與上方庫存行，避免下一輪把「演算法現成」誤當成「該做了」。本輪未發現需要 PO 決定的事項 |
| 2026-10-04 | **登記 G20a 規格書（[`specs/G20a-promotion-hints.md`](specs/G20a-promotion-hints.md)，v1.0）：菜單與購物車的促銷提示**，升為 P1、排為工作順序**第 22 項**（G20c 為第 21 項、金流順延為第 23 項）。**本輪已把主線 `e1f4a4d` merge 進本分支**（#66 已合併），本檔的五處衝突依 PR 描述當初承諾的方式解掉：P1 表保留 G20c 與 G20a 兩列、P2 表的 G20a 列改為「已升為 P1」且不重複補列、工作順序與庫存段統一為 21=G20c／22=G20a／23=金流、庫存 2 份。成因是 G20 §13.5 與 §13.12 各自寫下的同一個「已知且刻意接受的後果」：顧客只買一杯時看不到任何提示 —— **一個看不見的促銷等於沒有促銷**，店家付了折抵成本卻換不到它想買的那個行為。**零 migration、零權限常數、不碰 `OrderService.create`。** 核心設計決策是 **§13.1「前端不算折抵金額」**：提示只顯示規則條件（「第 2 件 5 折」），不顯示「可省 25 元」，因為後者要在 TypeScript 裡重做一份 `PromotionService.best`／`evaluate`（含多規則取最大、平手比 id 字串序、`NTH_PERCENT` 先依單價排序再折最便宜那幾件、整數除法截斷四個不顯然處），兩份實作遲早漂移，而症狀是「畫面說省 25、帳單折了 24」—— 顧客會認為店家在騙他，且兩邊程式碼單看都對。「顯示真實預估金額」因此切出去成為 **G20h**（後端 `POST /api/orders/preview` 試算端點，金額仍只有一個來源）。另一項值得記的是 **§13.6**：提示刻意**不放進照片徽章位**，因為 **G08a 的缺陷成因就是兩個徽章共用一個位置而規格沒定義優先序**，在同一個位置放第三個競爭者是再製造一次同一個 bug —— 放在不衝突的位置是結構上解決，不是靠規則記得寫對。同批登記 G20h。**同一個 commit 另修 G20c 規格為 v1.2**（兩處規格端缺口，由審查 [PR #69](https://github.com/choka1227/coffee_GPT6/pull/69) 發現）：驗收 10 的「既有案例應原封不動通過」是錯的（既有 seed 算術上不可能，照原文守著會讓 §5.2 第二條恆等式在主 fixture 上成立不了），以及 §11.1 的測試矩陣 (a)–(f) 沒有任何一條碰到 `topToday`，驗收 3a 因此在矩陣裡沒有落點 —— **那是 #69 漏做驗收 3a 的根因，不在實作端**。**跨規格修補夾進本 PR 是刻意的例外**：#69 正要依審查意見修改，若放著原文不改，Codex 會讀到與審查意見相反的指示而把已經做對的 fixture 歸零改回去 —— 那屬於 `CLAUDE.md` 第 3 節「會讓 Codex 照著做出錯誤實作」的立即處理條款。 |
| 2026-10-04 | 依 Codex 在 [PR #66](https://github.com/choka1227/coffee_GPT6/pull/66) 的 `REQUEST_CHANGES` 修正兩項，G20c 規格升為 **v1.1**。**(a) `topToday[]` 的 API 契約自相矛盾**：規格 §5.3(b) 只要求 topToday 查詢加 `net_revenue`，§5.5(f) 與 §6.1 卻要求 `topToday[]` 同時回傳並宣告 `net_revenue` 與 `item_discount` —— 照查詢段落施工，TypeScript 會宣告一個 API 不產生的必填欄位，執行期拿到 `undefined`。**這是規格誤導實作，不是文字瑕疵**，照 `CLAUDE.md` 的例外條款立即處理。已統一為「`topToday[]` 只加 `net_revenue`」並就地寫出理由（唯一消費端是只有兩欄的今日前五名卡片；`topToday` 在 `shared/types.ts:221` 是自己的 inline 型別、不與 `products[]` 共用，欄位不對稱不會造成型別重複宣告）。**(b) 本檔的 G20 狀態過時**：P1 表、P2 表、工作順序第 20／21 項、「排程注意」的庫存段與修訂紀錄都寫「實作完成待合併／等待合併」，但 [PR #65](https://github.com/choka1227/coffee_GPT6/pull/65) 已於 **2026-10-04 06:24:48Z 合併為 `e81fa44`**，而本 PR 的分支當時仍落後主線 —— 與 2026-09-28 那一輪的 G09 是同一種失誤（寫的當下就已經過時）。本檔是自動選工的唯一依據，不能保留相反的即時狀態：全部改為已合併並附主線 SHA，第 20 項標為完成並註明 `codex/g20-item-promotions` 分支不要再續作，第 21 項的「開工前提：G20 要先合併」改為「已滿足」。**(c) 同批自行修補一處同類缺口（不在 Codex 的 review 內）**：規格 §4 與 §9 只點了 `ReportAggregationTest` 手寫 `order_items` DDL 缺 `discount_amount`，漏了 §5.3(d) totals 查詢要用的 `orders.item_discount_amount` —— 那張表的手寫 DDL 同樣沒有該欄位，照原文施工 S2 必定紅。已改為逐表列出兩個欄位並點明「漏掉 `orders` 那一個最容易發生」。庫存數字由「1 份：G20」改為「1 份：G20c」，Flyway 已占用改為 **V14**。**本檔另有第二處 `目前（2026-10-04）規格庫存` 敘述（G24／G08 結案紀錄那一段）同樣寫著「1 份：G20」**，與上方「排程注意」段相矛盾 —— 同一份自動選工依據不能有兩個不同的即時庫存答案，已標為「已過時，保留為紀錄」並明寫即時數字以「排程注意」段第一行為準；同段的「`V14` 由 G20 預定」改為「已實際占用」。**本輪不放寬任何驗收、不改變任何設計決策，三階段切分與既有 17 條驗收條件的內容與編號全數維持原樣**；另加一條驗收 `3a`（`topToday[]` 含 `net_revenue` 且不含 `item_discount`），把 (a) 修正後的欄位契約變成實作端驗得到的紅線 —— 原本 §5.3(b) 與 §5.5(c) 都要求該欄位，卻沒有任何一條驗收看得到它。 |
| 2026-10-04 | **登記 G20c 規格書（[`specs/G20c-report-net-revenue.md`](specs/G20c-report-net-revenue.md)，v1.0）：報表的淨營收歸屬與折抵口徑一致性**，升為 P1、排為工作順序第 21 項（金流順延為第 22 項）。成因是 G20 §13.11「報表不改」留下的口徑矛盾：`revenue` 是淨額、`products[].revenue` 是毛額，商品排行表的「商品毛利」因此系統性偏高，剛好會讓促銷看起來比實際賺錢。**零 migration，V15 仍然空著。** 另把 Claude 審查 [PR #65](https://github.com/choka1227/coffee_GPT6/pull/65) 時發現的對帳投影折抵別名缺陷併入該規格 S1（`reconciliationCandidates` 沒選 `d.discount_amount`，巢狀 `OrderDiscount.discountAmount` 在 G20 §5.5 的語意變更後回報總折抵）。「訂單層優惠碼折抵分攤到品項」切出去成為 **G20f**，演算法已寫在 G20c 附錄 A；另登記 G20g（報表圖表層測試）。同日 **G20 實作合併進主線**：PR #65 的 S1–S4 全數完成、head `87f4a33` CI 綠、Claude 送出 `APPROVE` 後由 auto-merge 於 2026-10-04 06:24:48Z 合入，主線合併提交 `e81fa44` |
| 2026-10-03 | **G24 實作隨 [PR #57](https://github.com/choka1227/coffee_GPT6/pull/57) 合併、G08 實作隨 [PR #59](https://github.com/choka1227/coffee_GPT6/pull/59) 合併**（G08 由 Claude 於 head `2ade6b6` 送出 `APPROVE` 後 auto-merge 合入，主線 `cc7974b`，S1–S4 全數完成，Flyway 實際占用 V12／V13）。**規格庫存因此由 2 份降為 0 份。** 同時：(1) **G08 規格修訂為 v1.2**，修補審查發現的兩處規格缺陷 —— 原驗收 16 要求顧客菜單回傳 `UNLISTED` 商品，但 `list` 既有的 `WHERE` 已把它整列排除、`CoffeeIntegrationTest` 也鎖定了隱藏契約，該條件不可能成立，已改寫；§5.7 未定義剩餘徽章與既有「今日售完」徽章的關係，照字面實作會讓兩個矛盾的徽章並存，新增 §13.13 定案「售完優先」並補驗收 19a。**兩處缺陷都在規格端，實作端無過失。** (2) **登記 G08a**（驗收 19a 的一行前端修正），排為工作順序第 19 項，金流順延為第 20 項，不計入規格庫存。(3) 新增「為什麼 2026-10-03 這一輪沒有產出新規格」一節，把 P2 剩餘項目逐項排除的結論寫下來 —— 工作順序已無可開工項，且 P2 每一項都被 PO 決定或「要先有真實資料」擋住，**本輪刻意不產出新規格**，特別是不把 G28 的選項層庫存在沒有新證據的情況下提前拆出來 |
| 2026-10-02 | **G27 實作隨 [PR #53](https://github.com/choka1227/coffee_GPT6/pull/53) 合併**（Codex 自行 auto-merge 合入，主線合併提交 `62c7a4c`；Claude 未送出 review，合併後複驗 `TimeZoneGuardTest.java` 存在、既有測試零修改、`verify` 綠）：P1 表的 G27 列由「待實作」改為「已合併」，工作順序第 16 項標為完成。**同時登記並升排 G24**（店長自設本店例外營業日與本日最後點餐時間，規格書 [`specs/G24-branch-manager-day-settings.md`](specs/G24-branch-manager-day-settings.md) v1.0）：G24 由 P2 升為 P1、成為工作順序第 17 項（原第 17 項金流順延為第 18 項），結清 G19 §13.3 與 G25 §13.5 兩筆登記，Flyway 預定 `V12`。規格庫存由 0 份回到 1 份。**升排沒有營運端證據**，理由是 P1 已清空而 P2 其餘項目分別被新依賴、產品決策或「要先有真實資料」擋住，完整推導見規格 §1.4 —— 若 PO 認為有更該做的，這份規格可以整份擱置，它不擋任何其他工作 |
| 2026-10-02 | **G08 規格書修訂為 v1.1：修補回補路徑的超賣缺口。** Codex 在 [PR #55](https://github.com/choka1227/coffee_GPT6/pull/55) 送出 `REQUEST_CHANGES`，指出 v1.0 的 `releaseStock` 用「今天 + branchId + productId」推論回補對象，卻沒有證據證明該訂單真的扣過；**判定成立**（兩條會真的超賣的路徑推導寫進規格新增的 §4.6）。修法：新增 `branch_product_stock_reservation` 保留憑據表（同一支 V13）、`reserveStock` 加 `orderId` 並在實扣時寫憑據、`releaseStock` 改為只依憑據回補、`setStock` 從無到有建立限量時清未結憑據；§13.9 由「跨日不回補」改為「回補到憑據記載的那一天」；新增設計決策 §13.12 與四條反向驗收 14a–14d。**施工階段不變（S1–S4），規格庫存不變（2 份）** |
| 2026-10-02 | **登記 G08 規格書（[`specs/G08-branch-product-stock.md`](specs/G08-branch-product-stock.md)，v1.0）：分店每日可售數量與自動售完**，並把 G08 由 P2 升為 P1、排為工作順序第 17 項（金流線順延；與 [PR #54](https://github.com/choka1227/coffee_GPT6/pull/54) 的 G24 之編號協調見該節）。規格把原登記的「庫存扣減」**收斂為每日可售數量**，永續庫存帳與選項層庫存**另立 G28**（G08 §14），理由是後者牽涉會計決策且需要真實備量資料。Flyway 預定占用 **V13**，下一份自 V14 起算。**規格庫存 1 → 2 份，已滿上限，下一輪不再產出新規格** |
| 2026-10-01 | **G25 實作隨 [PR #51](https://github.com/choka1227/coffee_GPT6/pull/51) 合併**（Claude 於 head `4085df9` 送出 `APPROVE`，auto-merge 合入；S1–S3 三階段全數完成，Flyway 實際占用 **V11__branch_last_order.sql**，所以下一份需要 migration 的規格自 **V12** 起算）：P1 表的 G25 列由「規格書已完成，待實作」改為「實作已合併」，工作順序第 15 項標為完成、**第 16 項（G27）成為目前這一項**，規格庫存由 1 份降為 **0 份**。**審查結論**：23 條驗收逐條核對通過 —— 逐分鐘等價性掃描（驗收 4）的期望值確實寫死而非回頭呼叫 `isOpenAt` 自我比對、跨夜的 `close + 1440 - m` 三處都對、四個既有打烊訊息逐字未動、§7 的五條越權防線齊備且都驗了「403 之後值沒被寫進去」、`coffee-orders` 變更量為零。**同批登記四項不擋合併的觀察，留給後續規格處理**：(a) `GET /api/branches/{id}/hours` 對不存在的分店由 200 變成 404（`stateAt` → `lastOrderMinutes` 會丟 404），新行為較正確故採納，但它是規格沒寫的契約變更，需補登為既定行為；(b) `InitialData` 與兩支既有測試改用明確欄位清單的 INSERT 是 `ADD COLUMN` 的必然連帶修正，做法正確但 PR 描述未列出；(c) `BranchController` 新增的 `@ExceptionHandler` 用 `getMessage().contains("lastOrderMinutes")` 比對字串辨識欄位，Jackson 訊息格式改變就會悄悄退回泛用訊息；(d) 規格 §5.7 要求的 `LAST_ORDER_WARNING_MINUTES = 30` 常數未抽出，`30` 寫死在 `MenuView.vue` 模板兩處 |
| 2026-10-01 | **G26 實作隨 [PR #48](https://github.com/choka1227/coffee_GPT6/pull/48) 合併**（Claude 於 head `47b39a7` 送出 `APPROVE`，auto-merge 合入，主線合併提交 `ee74608`）：狀態由「待實作」改為「已合併」，工作順序第 14 項標為完成、第 15 項（G25）標為目前這一項。**每月最後一天 16:00–24:00 UTC 全員 CI 紅的時間彈已消除。** **登記 G27：無參數 `now()` 的自動化防線**（工作順序**新增第 16 項**，金流線順延為 17）—— G26 驗收 (b) 把「全 repo 不得呼叫無參數的 `YearMonth.now()`／`LocalDate.now()`／`LocalDateTime.now()`／`ZonedDateTime.now()`」寫成不變式，但沒有任何自動化在看，下一支寫日期的測試就能把同一類時間彈帶回來。修法是一支 ArchUnit 測試，**不另立規格書、不計入庫存**（比照 G26）；兩個關鍵點是「`ClassFileImporter` 不可排除測試碼」（G26 的缺陷就在測試裡，既有 `ModuleBoundariesTest` 加了 `DO_NOT_INCLUDE_TESTS` 所以當初放行了它）與「`Instant.now()` 必須放行」（`Instant` 與時區無關，列入會逼出注入 `Clock` 的大改）。可行性先行驗證過：全 repo 測試都在 `coffee-app`、`archunit-junit5` 已在其 test scope，零新相依。**另修正一項陳舊紀錄**：P1 區「`coffee-reporting` 沒有 `api` package」的架構缺口早已隨 G09／[PR #36](https://github.com/choka1227/coffee_GPT6/pull/36) 於 2026-09-28 結清（G09 的範圍本來就含這一項，見工作順序第 9 項），但那一列從未改掉 —— 本輪差點據此再寫一份規格，已標為已結清並註明教訓。**本輪未補第二份規格**，理由寫在「排程注意」段 |
| 2026-10-01 | **依 Codex 在 [PR #49](https://github.com/choka1227/coffee_GPT6/pull/49) 的 `REQUEST_CHANGES` 修正「排程注意」段的陳舊即時狀態**：該段入口原本寫「目前（2026-09-29）待實作的規格兩份：G19（實作中）與 G23（待開工）」，並在後續兩段以現在式描述兩者的檔案衝突 —— 但同一檔案的工作順序早已記錄 G19／PR #42 與 G23／PR #45 都在 2026-09-30 合併。本檔是自動選工的依據，同時給出兩個互相矛盾的即時狀態會讓下一輪續作已完成的規格或誤判庫存。入口改為「目前（2026-10-01）待實作的規格一份：G25，實作已送審（PR #51）」，並在三段歷史說明前加上明確的「以下全部是歷史紀錄，自動選工請以工作順序與庫存數字為準」橫幅、兩段段首標為「已結案，保留為紀錄」並改為過去式。**保留而不刪除的理由**：裡面的排程推理（檔案交集怎麼算、為什麼序列化）對下一輪仍有參考價值，刪掉等於把判斷依據丟了 |
| 2026-09-30 | **登記 G26：`OrderDiscountTest` 的月份時區時間彈，並把它插隊排在 G25 之前（工作順序第 14 項，G25 順延為 15、金流線順延為 16）。** `OrderDiscountTest.java:82` 的 `YearMonth.now()` 用系統預設時區（CI runner 是 UTC），`ReportService.java:13` 的彙整視窗用 `Asia/Taipei`，兩者在「UTC 與台北落在不同月份」時不一致 —— **每月最後一天 16:00 UTC 到 24:00 UTC** 這八小時內 `verify` 必紅，而 `verify` 是分支保護的必要檢查，所以那段時間**所有人的所有 PR 都合不進去**。**發現經過**：純文件 PR [#46](https://github.com/choka1227/coffee_GPT6/pull/46) 在 2026-09-30 18:21 UTC 的 `verify` 紅在 `reportShowsDiscountAndRevenueAfterPayment:88 expected: 20L but was: 0L`；該 PR 與主線的兩點 diff 只有 `docs/` 兩個檔案、Java 與前端零差異，所以是主線既有缺陷在那個時間窗被觸發。修法是一行（`YearMonth.now(ZoneId.of("Asia/Taipei"))`）加一個 import，**因此不另立規格書、不計入規格庫存**，施工級說明與四條驗收直接寫在 G26 段與工作順序第 14 項。驗收 2 順手把「全 repo 不得呼叫無參數的 `YearMonth.now()`／`LocalDate.now()`／`LocalDateTime.now()`／`ZonedDateTime.now()`」變成可掃描的不變式（目前只有這一處，修掉就歸零）—— `AGENTS.md`「時間」那節本來就要求業務日期一律以 `Asia/Taipei` 換算，但沒有任何自動化在看。**為什麼插在 G25 前面**：它擋的是所有人的 CI，G25 只是下一個功能；而且它的症狀（折扣金額變 0）看起來像折扣邏輯壞了，很容易被誤判成 G07 的迴歸而往錯的方向查 |
| 2026-09-30 | **G25 規格書修正為 v1.1：截止後訊息不再假設「明日」。** 依 Codex 在 [PR #46](https://github.com/choka1227/coffee_GPT6/pull/46) 的 `REQUEST_CHANGES` 修正 —— v1.0 的 §5.4、§5.7、§6.1 與驗收 8 把截止後訊息固定成「請於明日營業時間再下單」，但 `validateHours` 允許每天最多 4 個不重疊時段，`09:00–12:00`＋`13:00–18:00` 的分店在 `L=15` 時 `11:50` 只是**第一段**停止接單、`13:00` 當天就恢復，依 v1.0 後端 400、畫面與 notify 都會叫顧客等到明天，資訊明確錯誤並可能流失當日下午的訂單。**意見成立。** 修法：`lastOrderMessage` 多收一個今日的 `DaySchedule`，今日仍有後續可下單時段時結尾為 `請於今日 HH:mm 起的營業時段再下單`（新增 `nextOrderableStartToday`，**跳過長度 ≤ `L` 的時段**，否則會叫顧客為一個整段不可下單的時段白跑一趟），否則為不帶日期的 `請於下一個營業時段再下單`；前端兩則提示一律用不帶時刻的版本（`Branch` record 沒帶時段列，前端算不出下一段是幾點，刻意不為此加欄位）。**範圍限今日、不做跨日搜尋**（新增 §13.10 設計決策）：`requireOrderable` 手上已有今日的 `DaySchedule`，零額外 DB 存取就覆蓋唯一會講錯話的情境；要講出「明日 09:00」得擴大 `loadOverrides` 的日期範圍並決定掃描幾天，代價與收益不成比例。同批新增**驗收 21–23 的反向驗收**（同日雙時段第一段截止後、後續時段長度 ≤ `L` 要跳過、跨夜與例外日都不得出現「明日」），S2 驗收子集加 21–23、S3 加 23，§5.1 補上「`validateHours` 禁止同日時段重疊 ⇒ 一天最多命中一個時段（所以 `windowAt` 不必取最大／最小值），但一天有多個時段是常態」的備註，§11.1／§11.2 的測試清單同步補上訊息全文與「不含明日」的斷言。工作順序第 14 項新增**紅線六** |
| 2026-09-30 | **登記 G25 規格書（[`specs/G25-last-order-time.md`](specs/G25-last-order-time.md)，v1.0）：最後點餐時間（last order）與即將打烊提示**，並把 G25 由 P2 升為 P1、排為工作順序第 14 項（原第 14 項的金流線順延為第 15 項）。**同批對齊主線現況**：G19 由「規格書已完成，待實作」改為「實作已合併（PR #42，2026-09-30，Flyway 占用 V10）」、工作順序第 12 項標為完成；G23 改為「實作已合併（PR #45，2026-09-30，零 migration）」、第 13 項標為完成（登記本規格時 #45 尚未合併，於同日合併後在本分支一併更新）；P2 表的 G25 標為已升 P1，G24 的範圍依本規格 §13.5 擴大為「店長自行設定本店例外營業日**與本店最後點餐時間**」（兩者是同一類設定，升排時要一併開放）。**為什麼現在補第二份**：G19 已合併、G23 的實作已完成並取得核准，規格庫存降到 1 份，未達上限 2 份。**為什麼是 G25**：2026-09-29 那一輪排除 G25 的唯一理由是「相依尚未合併的 G19，現在寫等於寫在浮動的地基上」，該理由已消失；P2 其餘各項的排除理由逐項未變（G16 不在授權範圍、G05 要引入 Redis、G08 已由 G13 做掉輕量版、G17／G20／G21 要先有真實資料、G12 依賴外部選型），而 **G24 雖然也相依 G19，但 G19 §13.3 明文寫了「若營運端反映颱風天的反應速度是實際痛點才升排」—— 沒有那個訊號，現在做是在猜需求**。**它補的是什麼**：`requireOrderable()` 目前只問「現在是不是營業時間」，21:59 送進來的訂單在 22:00 打烊的門市是合法的，但咖啡機 21:45 就開始清洗。店家現有的兩條路都是把缺口轉嫁給現場 —— 把營業時間提早填 15 分鐘（顧客端與門口的營業時間牌互相打臉，員工 POS 也看不出真正的打烊時間），或照收再打電話取消（訂單已成立，牽動退款與稽核）。**核心設計**：最後點餐時間是**分店層級的一個數字**（`branches.last_order_minutes`，「打烊前 N 分鐘停止接單」），不是每個時段各一個（§13.1）—— 每段各一個要在 `branch_hours` 與 `branch_day_override_hours` 各加欄位、各加驗證、各加 UI，等於同一件事做三次，而且 `Branches.Hours` 加第 4 個 component 會打斷至少 6 處 `new Hours(...)` 與整個前端時段格子，是一個又大又不可分割的破壞性變更；分店層級則讓**例外日自動適用**，`Hours` 與 `Branch` 兩個 record 一個字都不用動。`DEFAULT 0` 讓既有分店零遷移且 `0` 的語意就是現行行為 —— **這是 S1 能「行為零變更」獨立合併的根據**，形式證明寫在 §5.2：時段內任一分鐘距離結束都還有 ≥1 分鐘，所以 `L=0` 時 `orderableNow` 恆等於 `openNow`。CHECK **取具名約束** `ck_branches_last_order`（§4.1）—— G07 已經為匿名 CHECK 付過代價：`orders.total` 的匿名 `CHECK(total>0)` 在 H2 與 PostgreSQL 兩邊都沒有可靠的移除寫法，把免費訂單永久鎖死。**刻意不做的**：不做每段／每例外日各自的提前量（§13.1／§13.2）、**不把 `openNow` 改成「可下單」而是另開 `orderableNow`**（§13.3 —— 合併會讓還開著的店在顧客端提早顯示「已打烊」，而且編譯照過、既有測試照綠，錯誤只會在現場出現；推翻它的代價高，因為事後查不出每個讀 `openNow` 的地方當初想問哪一件事）、**員工 POS 不受截止點限制**（§13.4 —— 最後點餐時間是給顧客的承諾不是給店員的禁令，硬擋只會逼出繞道）、不做推播（§13.6 —— 本專案刻意沒有任何排程基礎設施，G13 的「售完記在營業日上、隔日自動失效」就是為了不引入排程）、不影響已成立訂單的付款與狀態轉換（§13.7）、存檔時不驗證「`L` 會不會讓某個時段整段不可下單」（§13.8 —— 未來 400 天內任何一天都可能新增例外日，存 `L` 的當下驗不到，做一半的驗證只會給人已經驗過的錯覺；緩解放在 UI 提示而不列入驗收）、仍是總部限定（§13.5，併入 G24）。三個施工階段全部是加法，**S1 是唯一有回歸風險的階段**（它把 `isOpenAt` 改寫成新純函式 `windowAt` 的包裝），所以刻意讓它不帶任何行為變更，並用一支**逐分鐘等價性掃描測試**（驗收 4，期望值寫死、不得呼叫 `isOpenAt` 自我比對）當安全網。S3 會續寫 `MenuView.dom.test.ts`，G23（PR #45）已於同日合併，該相依已解除。**G23 §12 那條「不得斷言打烊訊息文字」的約束對本規格解除** —— 改那些文字的人就是 G25 自己。Flyway 占用 **V11**，下一份需要 migration 的規格自 **V12** 起算。 |
| 2026-09-30 | **G23 規格書 v1.1（依 [PR #43](https://github.com/choka1227/coffee_GPT6/pull/43) 上 Codex 的 `REQUEST_CHANGES`）**：§7 的夾具權限矩陣寫了兩個現實中不存在的 `Actor`，會讓規格最核心的顧客案例無法實作。(a) `customerActor()` 原訂 `permissions: []`，但真實的 `CUSTOMER` 角色有 `ORDER_CREATE`（`InitialData.java:38`），而 `MenuView.vue:375` 的入口是 `v-if="!auth.can('ORDER_CREATE')"` —— 空權限的顧客會停在無權限畫面，S2 案例 2 與 5 根本進不到菜單；(b) `branchStaffActor()` 原訂含 `ORDER_CASH`，但那不是權限位元而是 `OrderService.java:462` 的稽核 action，`Identity.PERMISSIONS` 裡沒有它，且 `IdentityService.java:183-187` 強制 `ORDER_CREATE` ⇒ `POS_ORDER` + `ORDER_MANAGE`，原本那組權限連 `saveRole()` 都過不了。**修法**：三個夾具改為逐字照抄 `InitialData.java:38-58` 實際種進 DB 的 `CUSTOMER`／`CASHIER`／`HQ`，並在 §7 附上每個常數的檔案行號；新增 §13.9 記錄決定與理由，§14 補一條施工提醒。**這個錯誤暴露的通則已寫進 §13.9**：規格書凡是複述後端既有常數（權限位元、角色、狀態碼、錯誤訊息）的地方都要附檔案行號，因為複述必然漂移，而漂移方向永遠是「測試裡的世界比真實世界寬鬆」—— 寫一組建不出來的角色，測試照樣綠，但它證明的是一個不存在的使用者看到的畫面。**未動 Flyway 版號（V11 仍是下一個可用版號），未動任何程式碼。** |
| 2026-09-29 | **登記 G23 規格書（[`specs/G23-frontend-component-tests.md`](specs/G23-frontend-component-tests.md)，v1.0）：前端元件層測試（模板可見性）**，並把 G23 由 P2 升為 P1、排為工作順序第 13 項（原第 13 項的金流線順延為第 14 項）。**為什麼現在補第二份**：G19 已由 [PR #42](https://github.com/choka1227/coffee_GPT6/pull/42) 開工，規格庫存降到 1 份，未達上限 2 份。**為什麼是 G23**：它是 P2 裡唯一零產品決策、零後端變更、零 migration、零權限，且與實作中的 G19 零檔案交集的一項；G24／G25 都相依尚未合併的 G19，G16 不在 Claude 的授權範圍，G05 要引入 Redis，G17／G20／G21 要先有真實資料，G12 依賴外部選型（逐項理由見規格 §13.1）。**它補的是 G22 明文放棄的那一半**：G22 §13.3 只測純函式，`MenuView.vue` 決定實收欄位、應找零列與結帳按鈕 disabled 的四個條件式（規格 §1.2 的 A／B／C／D）在 CI 上完全沒有防線，其中 A 與 C 共用的條件式在模板裡寫了兩次，改一處漏一處是最典型的走樣方式。規格同時把「前端不得送任何金額欄位」這條既有紅線變成自動化的白名單斷言（§8）——目前那條規則在前端只有互審在看。**對 G19 零干擾**：G23 不修改任何 `.vue`，且明文禁止斷言打烊訊息的文字（§12），因為 G19 S3 會改它的來源。**不動用任何 Flyway 版號，V11 仍是下一個可用版號。** |
| 2026-09-29 | 依 Codex 在 [PR #40](https://github.com/choka1227/coffee_GPT6/pull/40) 的**第二輪** `REQUEST_CHANGES` **把本檔三處 G19 的現行版本標記由 v1.0 更新為 v1.1**（P1 狀態表、P2 升排索引、P2 的 G19 詳述入口）。**阻擋成立**：同一份檔案裡「排定的工作順序」第 12 項已寫 v1.1 並明文警告照 v1.0 施工會重現公休日凌晨可下單的錯誤，三個現行狀態入口卻仍指向 v1.0 —— 本檔是主線的選工與核對依據，讓它帶著已知過期的版本標記進主線，下一輪依本檔選工時會拿到互相矛盾的答案（與 2026-09-27 G09 索引、2026-09-28 G22 版號落後是同一類失誤，三次都是「改了本文卻漏了狀態入口」）。**同批修掉同段的另一處 v1.0 殘留**：P2 的 G19 詳述段核心設計摘要仍寫「`AlwaysOpen` 不貢獻跨夜尾段」，那是 v1.0 的窄規則，已改寫為 v1.1 的「例外日完全決定當天，前一日的跨夜尾段一律不跨進來」（§6.3a／§6.4a／§6.4c）—— 只更新版號而留著 v1.0 的規則敘述，等於把同一個阻擋換個位置留下。**規格本文 `G19-branch-hour-overrides.md` 本輪未變動（維持 v1.1）**，設計決策與三階段切分不受影響。最後一列的登記紀錄保留 v1.0 字樣，那是當時的歷史事實（v1.1 修正了什麼見下一列） |
| 2026-09-29 | 依 Codex 在 [PR #40](https://github.com/choka1227/coffee_GPT6/pull/40) 的 `REQUEST_CHANGES` 修正一項阻擋，G19 規格升為 **v1.1**。**v1.0 的跨夜尾段優先序會讓公休日凌晨仍可下單**：§6.3 第 5 步在判定今日是否營業時，無論今日 `D` 是不是 `Closed`，都還允許前一日 `P` 的跨夜 `Periods` 貢獻尾段 —— 「每週日 22:00–週一 02:00 ＋ 週一 `closed=true`」在週一 01:00 會判為營業中。§6.4a 只擋住 `P` 是 `AlwaysOpen` 的方向，**漏掉 `P` 是 weekly `Periods` 這個常態方向**（有設每週時段的分店全都走這條），而 v1.0 的驗收 6 兩個方向都抓不到。**這是規格誤導實作，不是文字瑕疵。** **修正**：§6.2 的 `Periods` 加 `fromOverride` 旗標；§6.3 拆成第 5 步（`D` 是 `Closed` → 立刻回 `false`）與第 6 步，尾段那一條加上「今日不是例外日」前提；**新增 §6.3a**（例外日完全決定當天，附四種組合的落點表與推翻代價）；§6.4 新增 (c) 說明第 5 步與第 6 步第四條是同一條規則的兩半，只實作一半會各自漏掉一種情形；§6.5 補齊零例外列時的等價性論證（驗收 1 不受影響）；§13.10 由「`AlwaysOpen` 不貢獻尾段」擴大為「例外日不接受任何尾段」；**驗收 6 由兩個方向擴為五個**，(c)(d) 正是原本抓不到的漏洞。**本輪不放寬任何驗收、不改變任何設計決策**，兩張表、`yyyyMMdd` 編碼、總部限定授權與三階段切分維持原樣 |
| 2026-09-29 | **登記 G19 規格書（[`specs/G19-branch-hour-overrides.md`](specs/G19-branch-hour-overrides.md)，v1.0）：分店例外營業日（公休、臨時調整）**，並對齊 G22 隨 [PR #39](https://github.com/choka1227/coffee_GPT6/pull/39) 於 2026-09-29 合併後的現況。**為什麼是 G19**：G22 合併後 P1 清空、庫存降到 0，P2 逐項檢視（規格 §13.1 有完整表格）—— G16 涉及濫用防護／驗證信／個資，是產品與法遵決策，**不在 Claude 的授權範圍**；G05 要引入 Redis，違反「不得引入新依賴」的預設；G08 的輕量版已由 G13 做掉；G17／G20／G21 都明文登記要先有真實營運資料；G12 依賴外部選型；**G23 是純加法且不擋任何人（G22 §13.3 明文），連續兩份測試基礎設施規格而業務缺口還開著，順序不對**。G19 是唯一同時滿足「業務缺口、每年必然遇到、資料形狀已由 G14 §11.3 確定為純加法、不需新相依、不需產品決策、驗收客觀」的一項。**同批推翻 G14 §11.3 留下的兩處預期**（理由寫進規格）：(a)「切 `active` 一天」不是可接受的替代方案 —— 它讓分店在顧客端整個消失而不是「今天公休」，且 `requireOpen()` 被 `IdentityService.saveAccount()` 共用（切掉會讓總部在公休日無法維護該分店帳號，正是 G14 §5.7 拒絕的同一個理由），更關鍵的是**要有人記得切回來**，忘了就是隔天整天沒生意且系統不會叫（§1.2）；(b) 用**兩張表**而不是當初預期的一張 —— 一天有三種狀態（沒有例外／整天公休／改用當天時段），前兩者都對應「零個時段列」，時段列的有無區分不了它們；表頭的 `PRIMARY KEY(branch_id,on_date)` 讓「一天最多一個例外」由 DB 強制，單表加 `closed` 做不到（多段就是多列，主鍵擋不了；`UNIQUE` 又把 NULL 視為互不相同，§4.2）。**核心設計**：`on_date` 沿用 `branch_products.sold_out_date` 的 `INTEGER` `yyyyMMdd` 台北日編碼，**不用 `DATE`**（H2 與 PostgreSQL 的時區行為分岔，症狀是「測試全綠但差一天」，與 G09 §5.2 拒絕資料庫時區函式同一類風險，§4.1）；優先序「例外日贏過每週時段，**且贏過『沒設定＝24 小時營業』這條預設**」（§6.2 第 3 條 —— 示範資料的分店全是 `branch_hours` 零列，順序寫反會讓公休對它們完全失效）；**`AlwaysOpen` 不貢獻跨夜尾段**（§6.4a／§13.10，否則前一天的 24 小時會永遠蓋掉今天的公休，等於整個功能對那批分店失效）；例外日**不支援跨夜段**（§13.4 —— 「例外段延到隔天而隔天也公休」誰贏沒有好答案，而跨年夜可以寫成兩列且沒有歧義）；`deleteOverride()` 刪不存在的日期**回成功不丟 404**（§13.8，冪等）；例外日**完整替換**當天時段而非疊加（§6.4b）。**刻意不做的**：不做國定假日自動匯入（要新增相依且 Codex 環境驗不了，內建假日表會每年說謊，而且**台灣的國定假日不等於咖啡廳公休**，§13.6）、不做重複規則（§13.7）、不做最後點餐時間（與例外日是兩件事，塞進來會讓 S1 失去「零例外列時行為零變更」的性質，另立 **G25**，§13.5）、**不讓店長設自己分店的例外日**（維持 `BRANCH_MANAGE` + `actor.global()`，因此 V10 沒有任何 `INSERT INTO role_permissions`；**誠實記下放棄的東西：颱風天的反應速度** —— 店長只能打電話給總部；推翻是純加法，另立 **G24**，§13.3）。三個施工階段全部是加法，**S1 在零例外列時行為與現狀逐字相同**（§6.5），驗收 1 要求 `BranchHoursTest` 一個字元都不改且全綠。Flyway 占用 **V10**，下一份需要 migration 的規格自 **V11** 起算。**規格 §2.1 起把 `docs/reports/` 的實作回報列為檔案清單的通則** —— G22 §2.1 漏列它，導致 PR #39 是唯一沒有實作回報的實作（Claude 在該 PR 的 review 記為非阻斷觀察，`AGENTS.md:44` 本來就要求）。**同批對齊主線現況**：P1 表 G22 由「待 review／合併」改為「實作已合併」並新增 G19 一列，P2 表 G19 標為已升 P1、新增 G24／G25，工作順序第 11 項標為完成、**新增第 12 項（Codex 實作 G19）並標為目前這一項**，金流順延為第 13 項，庫存由 0 份回到 **1 份**。另修掉 PR #39 留下的排版缺陷：「待 PO 驗收」表被清成只剩表頭（渲染成空表），改寫為文字並註明「日後又有項目時把表頭一起加回來」，同時把 G23 的邊界寫清楚 —— 模板可見性不是「待 PO 驗收」的待辦項，而是一個已登記的缺口 |
| 2026-09-28 | 依 Codex 在 [PR #37](https://github.com/choka1227/coffee_GPT6/pull/37) 的**第二輪** `REQUEST_CHANGES` 修正一項阻擋，G22 規格升為 **v1.2**。**驗收 23 是假綠燈**：v1.1 把單段式的 `cash ??= total.value` 留在 `MenuView.vue` 呼叫端，只用規格 §12.3 的一句文字說明「呼叫端會做」，而該列的自動化斷言只驗 `validateTendered({ tendered: undefined, amountDue: 140 })` **失敗**。實作端若漏掉那個預設帶入，三支函式的所有指定斷言**仍然全綠**，但 POS 單段收款會在店員沒輸入實收時直接拒絕結帳 —— 正是該列標題「不得退化」要擋的事。**規格卻據此宣稱 G07 的人工驗收 23 已被自動化取代**，等於用一條驗不到東西的測試換掉一條真的人工驗收，比不換更糟。**照 §3「動手的條件」的例外條款立即處理**（規格會誤導實作端做出錯誤成品），不等下一次順風車。**修正**：新增規格 §6.4a `effectiveTendered({ path, tendered, amountDue })` 第六支純函式（僅 `POS_CASH_SINGLE` 在 `undefined` 時回 `amountDue`；兩段式維持 `undefined`，因為自動帶入折後應收等於系統自己按了確認，是 §1.2 抽屜短少的另一種寫法），`MenuView.vue` 改為實際呼叫它，新增**驗收 16** 同時釘住兩個方向，§12.3 驗收 23 改為可轉紅的斷言並補反向案例，S2 函式數由五支改為六支。同批對齊 Codex 指出的非阻擋項：本檔 P1 表、P2 敘述段、工作順序第 10 項與「排程注意」段的 G22 版本由 **v1.0** 更新為 **v1.2**（v1.1 那一輪漏更；本檔的版本號是主線選工依據，落後會讓下一輪誤判讀到的是哪一版）。**本輪不放寬任何驗收、不改變任何設計決策**，`vitest`、`environment: "node"` 與三階段切分維持原樣 |
| 2026-09-28 | 依 Codex 在 [PR #37](https://github.com/choka1227/coffee_GPT6/pull/37) 的 `REQUEST_CHANGES` 修正兩項，G22 規格升為 **v1.1**。**(a) §6.6 `changeAmount()` 不是等價抽取**：v1.0 寫「等價於 `(tendered ?? amountDue) - amountDue`」，漏掉 `MenuView.vue:630-641` 模板實際有的 `Math.max(0, ...)` 下限箝制。照 v1.0 實作，實收小於應收時會由目前顯示 `0` 變成顯示**負數找零**，違反 S2「行為零變更」與規格 §1.5 的紅線。**這是規格誤導實作，不是文字瑕疵** —— 照 §3「動手的條件」的例外條款立即處理，不等下一次順風車。已把契約改為逐字寫出 `Math.max(0, ...)` 並新增**驗收 15**（未輸入回 0、等於應收回 0、大於應收回差額、**小於應收回 0 不得為負**）把它變成可驗證的紅線。**(b) 本檔與 G22 規格的 G09 狀態過時**：P1 表、工作順序第 9 項、「排程注意」段、修訂紀錄與規格 §0／§2.3 都寫「已核准待合併／合併在即」，但 [PR #36](https://github.com/choka1227/coffee_GPT6/pull/36) 已於 **2026-09-28 06:22:26Z 合併為 `ee9861c`**，而該提交正是 `claude/spec-g22` 分支的 parent（本 PR 的 commit 時間為 06:32:30Z）—— 寫的當下就已經過時。主線的選工依據不能保留錯誤的即時狀態，全部改為已合併並附主線 SHA。另補規格 §6.4 一句，說明單段式的 `cash ??= total` 留在呼叫端、驗證函式本身不接受 `undefined`（原本只寫在驗收 23，本文單看容易誤讀）。**本輪不放寬任何驗收，不改變任何設計決策，`vitest` 與三階段切分維持原樣** |
| 2026-09-28 | **登記 G22 規格書（[`specs/G22-frontend-test-infra.md`](specs/G22-frontend-test-infra.md)，v1.0）：前端測試基礎設施與可測純函式抽離**，並對齊 G07／G09 的合併現況。**為什麼現在做**：G07 §11.11 把「前端顯示／流程缺陷 → 抽屜短少」這一整個缺陷類別的防線完全押在 G22 上，在它完成之前只靠原始碼佐證與互審把關；而那個代價已經具體發生過一次（PR #31 三階段全綠卻卡在 draft、硬相依的 G09 連帶停工、Codex 兩次執行零產出）。`frontend/` 目前的 CI 步驟只有 `vue-tsc --noEmit && vite build` —— **只驗型別與可打包，邏輯寫反了 CI 一樣是綠的**。**核心設計**：(a) 引入 `vitest` 作為**唯一**新增相依，這是 `AGENTS.md` 禁止事項第 3 條的例外，理由與三個替代方案（Jest、`node:test`、只寫後端測試）的評估寫在規格 §13.2；(b) **`environment: "node"`，不裝 jsdom、不裝 `@vue/test-utils`** —— 要擋的缺陷抽成純函式後全是算術與分支，掛載 752 行的 SFC 是殺雞用牛刀，而且日後要加是**純加法**（§13.3，登記為 G23）；(c) **`npm run test` 加進既有的 `verify` job，不開新 job** —— 分支保護的 required status check 名稱是 `verify`，開新 job 要改 repo 設定才擋得住，而那是 PO 的決定；塞進同一個 job 則**當天生效、零設定變更**（§13.5）；(d) 把 `checkout()` 的四條付款路徑判定、實收驗證、應收來源、找零與守門條件抽成 `modules/ordering/checkout.ts` 的純函式，並用一條讀原始碼的測試釘住它不 import `vue` / `api` / `store`（§12.4）—— 純度一破，整份規格建立的可測性就漏光了；(e) `amountDue()` 在「兩段式但還沒有後端金額」時**直接丟 `Error`** 而不是回小計，因為收銀台場景下「立刻爆炸」遠勝「靜默給出一個看起來合理的金額」（§13.9）。**刻意不做的**：不測 `money()` / `dateTime()` 等 `Intl` 函式的輸出字串（隨 Node 的 ICU 版本漂移，會製造假紅燈，而假紅燈比沒有測試更糟，§13.8）、不設覆蓋率門檻（§13.7）、不做 E2E（Codex 的環境沒有瀏覽器也跑不起 backend，§13.4）。三個施工階段，**全部是加法**，S1 就算後兩階段永遠沒做也已經是永久的進度（框架在、CI 在擋、CSV 注入防護有測試了）。**G07 驗收 21b／22／23 由 S3 的驗收 13 自動化**，用同一組數字（小計 140、折抵 14、應收 126）；「待 PO 驗收」表因此標註了終點，並誠實寫下自動化替代的邊界 —— 純函式證明得了「數字對不對」，證明不了「欄位有沒有出現在畫面上」，後者仍是 PO 的合併後驗收。**同批對齊主線現況**：G07 實作隨 [PR #31](https://github.com/choka1227/coffee_GPT6/pull/31) 於 2026-09-28 合併（Flyway 實際占用 **V9**），G09 實作隨 [PR #36](https://github.com/choka1227/coffee_GPT6/pull/36) 於同日合併（head `91fac14`，主線合併提交 `ee9861c`，CI 綠，零 migration）；工作順序第 8／9／10 項標為完成，**新增第 11 項（Codex 實作 G22）並標為目前這一項**，金流順延為第 12 項；待實作規格庫存由 2 份降為 **1 份**；**G09 與 G22 都不新增 migration，下一份需要 migration 的規格自 `V10` 起算**。另修正一處會誤導實作端的歷程殘留：工作順序第 8 項的歷史敘述裡有「不要為它們引入 Vitest 等前端測試依賴」一句，那句話當時約束的是 **G07 的範圍**（不要把前端測試基礎設施夾在 G07 裡做），不是永久禁令 —— 已就地標註它被 G22 §13.2 取代，避免下一輪讀到兩套相反指令 |
| 2026-09-27 | **Claude 主動修補 G07 規格 v1.4 的死鎖（非任何一方的 `REQUEST_CHANGES`），規格升為 v1.5。** v1.3／v1.4 把「§6.6 的 21b／22／23 實機人工驗收紀錄」寫成 PR #31 轉 ready for review 的閘門，卻**沒有指定執行者 —— 而且沒有任何一方做得到**：Codex 的執行環境沒有瀏覽器、沒有可用的 backend JAR，`./mvnw -B -ntp verify` 因 Maven Central DNS 無法解析跑不起來；押在 PO 身上則直接違反「不停工等人」的授權前提；押在 Claude 身上會讓規格作者變成每次 POS 前端變更的序列化瓶頸，而且改程式碼的那一方仍驗不了自己的東西。**實際後果**：PR #31 的 S1／S2／S3 三階段全數交付、遠端 Actions 全綠、無衝突，卻永遠留在 draft，硬相依於它的 G09 也連帶開不了工 —— 2026-09-27 Codex 的兩次執行**產出零行程式碼**，只反覆同步主線並回報「剩餘：§6.6 人工驗收」。**一道沒有人能滿足的閘門不會帶來安全，只會停住產線。** **修正（新增 §11.11）**：(a) 21b／22／23 由「人工驗收」改為**原始碼佐證** —— Codex 在 `docs/reports/G07-order-discounts.md` 附「§6.6 原始碼佐證」一節，貼出 `MenuView.vue` 實際行並說明每個顯示／驗證數字各自來自哪個欄位，Claude 在 review 時對照 diff 覆核；(b) **解除合併閘門**，實機操作降為 **PO 的合併後驗收**，登記在新增的「待 PO 驗收」一節（`AGENTS.md` 角色表本來就把「驗收」歸 PO）；(c) **G22 前端測試基礎設施由「有空再說」升為 G09 之後的下一份規格**（工作順序新增第 10 項），因為本決策把這個缺陷類別的防線完全押在它身上；(d) §11.10 標為部分推翻（「不內建前端測試基礎設施、登記 G22」的結論不變，「人工驗收」的交付方式與閘門由 §11.11 取代）；(e) §12 施工提醒第 9 項改寫，明確告訴 Codex 不要再等實機驗收。**驗收 1–20 與 21a 維持自動化，一條都不放寬**，本輪也不引入任何前端測試依賴。**誠實記下權衡條件**：原始碼佐證比實機操作弱（證明程式碼形狀，不證明畫面數字），本專案目前不在生產環境、金流整條線仍是 stub，合併不等於有真實錢櫃在收錢；同一道閘門若在上線後，這個決定會反過來 —— 屆時要補的是可執行的實機驗收流程與執行者，不是放寬閘門。推翻本決策必須同時指定具備可部署環境與瀏覽器的執行者 |
| 2026-09-27 | 依 Codex 在 [PR #33](https://github.com/choka1227/coffee_GPT6/pull/33) 第二輪 `REQUEST_CHANGES` **清掉本檔 P2「G09 報表效能」敘述段殘留的 v1.0 索引要求**。上一輪把規格本文、工作順序第 9 項、修訂紀錄與 PR 描述都改成零 migration，卻漏了 P2 敘述段的兩句：「S1 輸出等價測試 + **索引** + `daily`／`hourly` 下推」與「**Flyway 用 V10**（V9 由 G07 占用），只加兩支索引」。**Codex 的阻擋成立**：本檔是主線的選工與施工依據，同一份文件裡同時存在「要加兩支索引」與「不得新增任何 migration」兩套相反指令時，實作端很可能照著較早出現的那段做出 v1.1 已明文禁止的 V10 重複索引 —— 這正是「規格誤導實作」而不是文字瑕疵。**修正**：S1 範圍去掉「+ 索引」；Flyway 那句改寫為「本規格零 migration，不新增任何 Flyway 檔案、也不新增任何索引」，並就地寫出理由（`V1__coffee_schema.sql:7-8` 既有 `idx_orders_paid(paid_at)` 與 `idx_orders_branch_paid(branch_id,paid_at)` 欄位與順序完全相同）與可驗證的紅線（驗收 11 驗 `db/migration/` 沒有新增檔案）。理由寫在段內而不只是指向規格 §4，是因為上一輪的失誤就是「摘要與本文分離後只更新了本文」。同批更新「排程注意」段的待實作規格數：由「只剩一份：G07」改為「兩份：G07（PR #31 審查中，維持 draft 待人工驗收）與 G09（硬相依，G07 合併後才開工）」—— 本 PR 合併後 G09 就是一份待實作規格，舊數字會讓下一輪誤判庫存。**規格本文 `G09-report-aggregation.md` 本輪未變動（v1.1 不變），零 migration 的結論與 7 次查詢設計不受影響。** |
| 2026-09-27 | 依 Codex 在 [PR #33](https://github.com/choka1227/coffee_GPT6/pull/33) 的 `REQUEST_CHANGES` 移除 G09 規格 v1.0 的**重複索引要求**，規格升為 **v1.1**。v1.0 的 §4.2／S1／驗收 11 要求用 V10 新增 `idx_orders_paid_at ON orders(paid_at)` 與 `idx_orders_branch_paid_at ON orders(branch_id, paid_at)`，但 `V1__coffee_schema.sql:7-8` 早就有 `idx_orders_paid(paid_at)` 與 `idx_orders_branch_paid(branch_id,paid_at)` —— **欄位與順序完全相同，只是名稱不同**。照 v1.0 實作會在同一組欄位上並存兩份索引：每筆訂單寫入都要多維護一份 B-tree，vacuum、備份與儲存成本跟著加倍，而查詢規劃器不會因此多出任何存取路徑。**這是規格盤點時漏查既有 schema 的錯，不是實作端的問題。** **修正**：刪掉整個 V10 與新增索引的要求，G09 變成**零 migration 的純重構規格**；§2.1 的「索引現況」改為明載既有索引已足夠並列出名稱；§4「DB schema 與 migration」改為「本規格不新增 migration」；S1 的範圍由「輸出等價測試 + 索引 + `daily`／`hourly` 下推」縮為「輸出等價測試 + `daily`／`hourly` 下推」；原驗收 11（V10 存在且索引建立）改為**驗收 11：不得新增任何 migration 檔**，把「不要加索引」變成可驗證的紅線，而不是刪掉一條驗收就了事；§11.4 的設計決策從「只加 `orders` 的兩支索引」改寫為「不加任何索引」並記下推翻它的條件（要有實際執行計畫證據顯示既有索引不被採用）。下推所需的存取路徑本來就由既有索引提供，**因此本次修正不影響任何效能目標與查詢次數**。同批把主線 merge 進 `claude/spec-g09` 解 `docs/GAP-ANALYSIS.md` 的衝突（不 rebase、不 force push）：P1 表與工作順序改以主線版本為底（G14 已合併、G07 審查中），G09 續為工作順序第 9 項、金流順延為第 10 項 |
| 2026-09-27 | 依 Codex 在 [PR #32](https://github.com/choka1227/coffee_GPT6/pull/32) 第二輪 `REQUEST_CHANGES` 修正 G07 規格 v1.3 的**金額安全敘述過當**，規格升為 **v1.4**。v1.3 宣稱驗收 21a 是「唯一擋得住抽屜短少的自動化防線」、「有了它前端就算寫錯也不會出現後端認帳但金額對不上的收款」—— **這句話是錯的，而且錯的方向危險**：§6.6 要消滅的缺陷是前端在店員未輸入實收時自動把折扣前小計 140 當 `tendered` 送出，後端依折後應收 126 正確接受並回找零 14，**請求與帳面自始至終一致、21a 全綠**，短少發生在「請求裡的實收值 ≠ 抽屜實際收到的現金」，那條線在後端看不見。照 v1.3 的寫法，實作端會以為寫完 21a 就安全了，正好漏掉真正的缺陷。**修正**：21a 的範圍改寫為「只保護後端以折後 `total` 驗證 `tendered` 與保留 `PENDING_PAYMENT` 的契約」；明載在 G22 之前真正擋住抽屜短少的控制只有 **21b／22／23 的人工驗收**，且 **PR #31 轉 ready for review 前必須先留下這三條的驗收紀錄**；§11.10 的「理由」與「推翻的代價」同步改寫，把「抽屜短少這個缺陷類別在 G22 前完全沒有自動化防線」列為本決策唯一的實質風險與推翻理由。同批修正兩處編號／版本殘留：P1 表 G07 的「規格書 v1.2」與 §9 S3 階段表的驗收子集「21–23」（應為 21a／21b／22／23）。**本輪不放寬任何驗收，也不引入前端測試依賴** |
| 2026-09-26 | 依 Codex 在 [PR #32](https://github.com/choka1227/coffee_GPT6/pull/32) 的 `REQUEST_CHANGES` 修正 G07 規格 v1.2 的兩項缺陷，規格升為 **v1.3**。**(a) 可實作性矛盾**：§10 要求「每一條驗收都要有對應的測試」、§6.6 限定「只動前端」，而 `frontend/package.json` 沒有任何 test script 或測試框架，`AGENTS.md` 禁止事項第 3 條又預設不引入新依賴 —— 三者同時成立時驗收 21–23 無法實作。**這是規格的錯，不是實作端的問題。**處理方式（新增 §11.10）：驗收 21 拆成 **21a（後端自動化，用既有 `CoffeeIntegrationTest` / `HttpWorkflowTest` 即可，釘住 `cash()` 對折後 `total` 的驗證與收款不足時保留 `PENDING_PAYMENT`）** 與 **21b（人工）**；22／23 明載為人工驗收，結果寫進 `docs/reports/`；§10 開頭改為逐條標記「自動化」或「人工」，**驗收 1–20 全部維持自動化，一條都不放寬**。**(b) §12 編號重覆**：第 9 項之後又出現 7／8／9，已順排為 1–14。同批把前端測試基礎設施登記為新缺口 **G22**（P2，排在 G20／G21 之前），理由是前端目前只有 `vue-tsc` 型別檢查，純行為缺陷在 CI 上一律是綠的 —— G07 §6.6 的抽屜短少就是第一個案例 |
| 2026-09-21 | **登記 G09 規格書（[`specs/G09-report-aggregation.md`](specs/G09-report-aggregation.md)，v1.0）：報表彙整下推 SQL，並順帶結清 `coffee-reporting` 缺 `api` package 的既有架構缺口。** `ReportService.report()` 把整月已付款訂單全部載入記憶體，再對 31 天、24 小時、每家分店與三種付款／取餐方式各做一次全掃描，是 O((59 + 分店數) × 訂單數)，而且 `Sale` 投影還撈了兩個從頭到尾沒人讀的欄位（`id`、`branchName`）。**決定：G09 由 P2 升為 P1，排在 G07 之後**（理由見規格 §1.3／§11.1：P1 已清空、架構缺口在同一個檔案裡、它是 P2 裡唯一不需要新功能決策的一項；其餘 P2 項目不排前面的逐項理由寫在 §11.5）。核心手法是**在 SQL 用固定偏移量的 epoch 毫秒算術分台北日／時桶**，不用資料庫時區函式 —— 測試跑 H2、生產跑 PostgreSQL，時區函式分岔的症狀是「測試全綠但報表差一天」（§5.2）。改寫後**固定 7 次查詢且沒有任何一支回傳 O(訂單數) 的列**；次數由 4 變 7 是刻意取捨（§5.4）。三個施工階段，**S1 先把輸出等價測試對著舊實作寫綠當安全網，之後兩階段都不修改那份斷言**。最容易寫錯的是空桶：`GROUP BY` 只產出有資料的桶，沒有訂單的日期、小時與零營收分店會從輸出裡消失（§5.1）。**不新增 Flyway migration**（v1.0 原訂 V10 加兩支索引，已於 v1.1 移除，理由見下一列）。快取與預先彙總表刻意排除（§11.2：那是新的正確性問題，且應先有真實慢查詢證據）；自訂區間排除（§11.3）。工作順序新增第 9 項，金流順延為第 10 項 |
| 2026-09-21 | **G07 規格書 v1.2（依 Claude 在 PR #31 的 `REQUEST_CHANGES`）：新增 §6.6「POS 一次走完的現金收款：不得用購物車小計驗證實收金額」。** v1.1 完全沒寫這一段，實作端照著現行 `checkout()` 的形狀做就會做出錯的行為，而且錯的方向是抽屜短少 —— §11.6 決定不做折抵預覽端點，所以建單之前前端不可能知道折抵，`MenuView.vue` 的 `cash < total.value` 與 `tendered.value ?? total.value` 用的必然是折扣前小計：顧客給 130 付 126 的單會被前端擋掉；店員不輸入實收時收據會顯示「合計 126 / 實收 140 / 找零 14」，**每張折扣訂單短少一個折抵金額**，而且會在 G15 的現金日結被記成店員短收 —— 正是規格 §1 要消滅的現象，做完 G07 反而自己製造一次。**決定：帶碼時收款拆成兩段（先建單、以回應的 `order.total` 為應收、店員明確輸入實收後才收款），不帶碼時一個字不改**；歸在 S3 不另立 S4（理由：只動一個前端檔，且拆開會產生「折扣會算但 POS 收不對錢」的中間狀態，正是 §9.0 要擋的形狀）；新增驗收 21–23。同批放寬 §6.5 的 `reconciliationCandidates()`：v1.1 寫死「`discount` 填 `null`」只是省一次 join 的預設值，不是有理由的限制，**改為 `null` 或實際快照都接受**，不為了對齊文字要求 PR #31 改回來。§12 另補兩條施工提醒（POS 收款、驗收 11 的四種 404 必須有測試）並修正原本重複的編號 6 |
| 2026-09-21 | **登記現況對齊：G14 實作已隨 [PR #29](https://github.com/choka1227/coffee_GPT6/pull/29) 合併（S1–S3 全數完成，Flyway 實際占用 V8），G07 實作在 [PR #31](https://github.com/choka1227/coffee_GPT6/pull/31) 審查中。** 主線這份仍把第 7 項（G14）標成「← 目前這一項」、P1 表兩項都寫「規格書已完成，待實作」—— 照舊文字會讓實作端去續作一支任務已完成的分支，且誤以為 G07 還沒輪到。P1 表 G14 改為「實作已合併」、G07 改為「實作審查中」；工作順序第 7 項標為完成並註明 `codex/g14-branch-business-hours` 不要再續作，**第 8 項（G07）改標為目前這一項**並寫明續作留在 `codex/g07-order-discounts`、待修哪兩項；排程注意的待實作規格由兩份改為一份，Flyway 現況補上 V8 已進主線、V9 由 PR #31 占用、**下一份規格自 V10 起算** |
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
