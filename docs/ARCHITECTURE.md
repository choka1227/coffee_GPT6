# 模組化單體架構

## 部署模型

Vue 3 編譯成靜態檔後，納入 Spring Boot 的同一個可執行 JAR。對外同一網域、同一入口，`/api` 為 JSON API，其餘為 Vue Router。整套由一個應用程序及一個 PostgreSQL 資料庫運行。不是微服務、沒有服務發現或分散式交易。

## 後端模組

| Maven module | 職責 | 允許引用的業務模組 |
| --- | --- | --- |
| coffee-shared | Actor、授權、錯誤、ID | 無 |
| coffee-branches | 分店資訊與營業狀態 | shared |
| coffee-identity | 帳號、角色、功能配置、密碼雜湊 | branches 的 api、shared |
| coffee-catalog | 全店菜單、售價、成本、上下架 | shared |
| coffee-orders | 訂单金額、快照、狀態、現金收款 | catalog / branches 的 api、shared |
| coffee-payments | 綠界表單簽章、回呼驗證 | orders 的 api、shared |
| coffee-reporting | 銷售資料唯讀彙整 | shared |
| coffee-app | 啟動、Security、Session、例外、Flyway、開發 fixture | 所有模組，組裝層 |

每個業務模組的公開契約位於 `api`，實作和 Controller 位於 `internal`。跨模組只依賴 `api`，不直接呼叫別人的 Repository 或 Controller。ArchUnit 驗證業務模組無循環依賴，且不得引用其他模組的 internal。

本版採 JDBC 與資料轉移物件，不使用 JPA Entity 隱含關聯或跨模組 ORM 載入。報表是明確的唯讀投影例外：直接查詢 orders / order_items / branches 的讀取模型，沒有反向寫入權限或相依到訂單內部 class。共用同一資料庫，模組寫入責任清楚，跨模組付款確認仍使用 Spring 的本地交易。

## 前端模組

`modules/identity`、`ordering`、`payments`、`catalog`、`branches`、`reporting` 各自包含功能畫面或客戶端整合。`shared` 放 HTTP 封裝、資料型別、格式化及共用元件。App.vue 只負責角色導航與外框。畫面 lazy load，ECharts 位於報表路由的獨立 chunk。

同一套帳號系統決定可見選單。前端導航只改善體驗，真正授權在後端：Session 每次請求重新載入有效帳號、角色與權限。停用、權限變更立即反映於下一次請求；密碼變更與重設會提升 session_version，使既有 Session 失效（本人改密碼的當前 Session 更新版本後保留）。

## 資料範圍與功能權限

- SELF：只有自己建立的訂單；點餐時可選開放中的分店。
- BRANCH：收銀與訂單操作必須符合帳號的 branchId，報表不能自行傳另一家分店 ID。
- GLOBAL：具對應管理權限時可管理各分店與跨店報表。
- 總部預設 HQ 角色不可改寫，避免把自己鎖在系統外；可以新增自訂角色。至少保留一個啟用的 HQ 帳號。
- 公開分店清單只含營業資訊；商品成本只在菜單管理 API 與授權報表中提供。

## 訂單與金額

貨幣為新台幣整數元，價格含稅（未計算獨立稅額）。後端依有效菜單重算金額，忽略前端金額。建立訂單時快照商品名稱、分类、單價、成本、選項與數量；之後改菜單不回寫歷史。

`PENDING_PAYMENT → PAID → PREPARING → READY → COMPLETED`。未付款的現金訂單可以取消；已送線上付款的訂單不能直接取消，避免與異步付款競爭。付款後退款與退單不在本版範圍。狀態更新與收款使用行鎖，禁止跳階或重複列帳。

客戶端每次 checkout 使用 Idempotency-Key。後端對 (account_id, idempotency_key) 建唯一鍵，並保存請求指紋。同一請求重試回傳原訂單，識別碼對應不同內容則拒絕。併發競爭時可能先收到 409，再以相同內容與 key 重試可取得已建立訂單。

## 報表定義

以 paid_at（UTC epoch milliseconds）歸屬 Asia/Taipei 日期。月報顯示整個選定月份，沒有資料的日子補零。今日前五名獨立以台灣「今天」計算，不受選定月份影響，但遵守相同分店範圍。只計已確認付款，未付款及取消不計入。

商品毛利 = 成交金額 − 成交時成本，不含稅費、人力、租金、支付費用。客單價 = 已付款金額 / 已付款訂單數。分店達成率 = 月營業額 / 月目標；目標為零顯示未設定。

目前月報會把所選月份的已付款訂單投影載入記憶體，以 Java 彙整日與時段；商品依 SQL 聚合。大型連鎖可進一步改成資料庫分桶聚合或每日彙總表。訂單清單顯示最近 100 筆，月報不受此上限影響。

## 本版界線

單一应用實例的 HttpSession；應用重啟需重新登入，資料庫訂單不會遺失。若將來水平擴展，需要 Spring Session / Redis 等集中式 Session，登入限流也需集中化。未實作會員自助註冊、電子發票、退款、庫存扣減、折扣、外送及硬體印單，這些應由後續業務模組擴充。
