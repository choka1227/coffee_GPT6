# G25 最後點餐時間施工進度

## 來源

- 規格：`docs/specs/G25-last-order-time.md` v1.1
- 主線來源：`feature/init-project` @ `ee746085182e4eeb3293199f035fb45f8b0d5bed`
- 工作順序：`docs/GAP-ANALYSIS.md` 第 15 項

## 階段

- [x] S1 — 資料層與 `windowAt`
- [ ] S2 — 截止點強制與總部設定
- [ ] S3 — 前端設定與提示

## S1 驗收對照

- [x] 新增 `V11__branch_last_order.sql`，只有新增欄位與具名 CHECK 兩條 `ALTER TABLE`。
- [x] `windowAt` 是不需 Spring context 的 `public static` 純函式；`isOpenAt` 改為包裝它。
- [x] 新增六種排程、每日 1440 分鐘的寫死期望值等價性掃描，另釘住跨夜剩餘分鐘。
- [x] `Branches` 新增 `OpenState`、`lastOrderMinutes` 與兩支 `stateAt`；既有簽章保留。
- [x] `BranchResponse`／`HoursResponse` 只新增欄位；`coffee-orders` 零變更。
- [x] `BranchHoursTest`、`BranchHourOverrideTest`、`BranchHoursAdminTest` 內容零修改。
- [x] demo 初始資料與兩支使用全量 schema 的測試改用明確欄位清單，避免新增預設欄位後的無欄位名 INSERT 失效。
- [x] `backend/mvnw`、`scripts/build.sh`、`start-demo.sh` 維持 `100755`。

## 驗證

- `git diff --check`：通過。
- 保護測試檔內容差異：零。
- 本機 backend 測試：未進入編譯；Maven Central `repo.maven.apache.org` DNS 解析失敗，屬外部環境問題。
- 遠端 CI：等待最新 head GitHub Actions。

## 下一步

S2：新增四參數 `saveHours`、選填的 `lastOrderMinutes` API、截止點強制、同日下一時段訊息、交易性與五條越權測試。
