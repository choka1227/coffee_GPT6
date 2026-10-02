# G24 店長本店營業設定實作回報

來源規格：`docs/specs/G24-branch-manager-day-settings.md`  
來源主線：`1630b2d835553b180f1877c7f00b6eeb90a797e0`

## 施工進度

- [x] S1 權限常數與資料層
- [x] S2 店長本店例外日授權與 14 天範圍
- [ ] S3 每日最後點餐欄位與解析
- [ ] S4 前端本店營業設定

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

## 驗證

- Frontend：70/70 tests 通過，production build 通過。
- Backend：`./mvnw -B -ntp verify` 因 Maven Central DNS 無法解析而未進入編譯；
  S1 已由 GitHub Actions run `37036263578` 完整驗證成功；S2 將由新 head 重新驗證。
- 靜態檢查：`git diff --check`、腳本 `100755` 與無參數 `now()` 掃描通過。
