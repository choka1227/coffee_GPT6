# G24 店長本店營業設定實作回報

來源規格：`docs/specs/G24-branch-manager-day-settings.md`  
來源主線：`b194eeb4a11961c39f0ab7dc0c270444f4dc2337`

## 施工進度

- [x] S1 權限常數與資料層
- [x] S2 店長本店例外日授權與 14 天範圍
- [x] S3 每日最後點餐欄位與解析
- [x] S4 前端本店營業設定

## S1 設計與驗收

- `Identity.PERMISSIONS` 新增 `BRANCH_HOURS_OVERRIDE`，總數為 14。
- 新站台由 `InitialData` 把新權限預設授予 `MANAGER`；`HQ` 仍取得完整權限集合。
- `V12__branch_day_settings.sql` 為既有站台新增 nullable 的
  `branch_day_overrides.last_order_minutes`，並以具名 CHECK 限制在 0–120。
- migration 僅授予 `MANAGER` 與 `HQ`；`CASHIER`、`CUSTOMER` 不取得權限。
- 新增 migration 測試，涵蓋 fresh install、V11 upgrade、NULL 語意、上下界反例與角色矩陣。

本階段不新增端點、不改授權行為、不修改模組依賴或既有 migration。

## S2 設計與驗收

- `saveOverride`／`deleteOverride` 改用 `BRANCH_HOURS_OVERRIDE` 與
  `Actor.branch(branchId)`；總部照常跨店，店長只可操作所屬分店。
- 非 GLOBAL 使用者僅能設定台北今日至今日加 14 天的閉區間；總部不受限制。
- 每週固定時段仍維持 `BRANCH_MANAGE` + GLOBAL，並新增「店長即使被臨時授予
  `BRANCH_MANAGE` 仍為 403」的保護測試。
- HTTP 測試涵蓋兩店店長、本店／跨店、無權限角色、日期兩端邊界與 DELETE 對應矩陣。

## S3 設計與驗收

- `DayOverride`、API request/response 與三支查詢完整帶入 nullable `lastOrderMinutes`，保留 `NULL` 與 `0` 的差異。
- 新增「只覆寫最後點餐」形狀：空例外時段沿用每週時段，不再把當日誤判為沒有營業。
- `TimedWindow` 攜帶營業視窗所屬日期的有效最後點餐值；跨夜尾段使用前一天的設定。
- `stateAt` 單店、列表與 `requireOrderable` 共用相同解析，且列表路徑沒有新增查詢。
- 測試涵蓋形狀 B/C、欄位 round-trip、互斥與範圍驗證、跨夜所屬日、列表一致性及既有訊息格式。

## S4 設計與驗收

- 新增 `/branch-day` 與 `BRANCH_HOURS_OVERRIDE` 路由保護；側欄只對沒有
  `BRANCH_MANAGE` 的店長顯示「本店營業設定」，總部仍只看既有分店管理入口。
- 新頁只使用登入者的 `branchId` 讀取本店 `/hours` 與 `/hour-overrides`，不呼叫
  `/branches?manage=true`，也不開放每週時段寫入。
- 單日編輯支援整天公休、自訂時段、沿用每週時段只覆寫最後點餐三種合法形狀，
  日期選擇器限制台北今日至今日加 14 天。
- 總部分店管理的例外日表單可設定或清空每日最後點餐，清單摘要也會顯示每日值。
- 純函式測試涵蓋摘要、今日／第 14 天／第 15 天，以及跨月、跨年；DOM 測試涵蓋
  店長／總部／收銀員入口可見性、API 呼叫範圍與形狀 C 的送出內容。

## 驗證

- Frontend：80/80 tests 通過，TypeScript 檢查與 production build 通過。
- Backend：`./mvnw -B -ntp verify` 因 Maven Central DNS 無法解析而未進入編譯；
  S1 已由 GitHub Actions run `37036263578` 完整驗證成功；S2 已由 run
  `37036824918` 完整驗證成功；S3 已由 run `37075188096` 完整驗證成功；
  S4 等待最新 head 遠端驗證。
- 靜態檢查：`git diff --check`、腳本 `100755` 與無參數 `now()` 掃描通過。
