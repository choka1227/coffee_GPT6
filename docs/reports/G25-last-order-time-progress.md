# G25 最後點餐時間施工進度

## 來源

- 規格：`docs/specs/G25-last-order-time.md` v1.1
- 主線來源：`feature/init-project` @ `ee746085182e4eeb3293199f035fb45f8b0d5bed`
- 工作順序：`docs/GAP-ANALYSIS.md` 第 15 項

## 階段

- [x] S1 — 資料層與 `windowAt`
- [x] S2 — 截止點強制與總部設定
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
- 遠端 CI：head `08fa1742` 的 GitHub Actions run `36826615490` 全部成功（frontend test/build、backend verify）。

## S2 驗收對照

- [x] 新增四參數 `saveHours`；三參數版保留並沿用分店既有提前分鐘數。
- [x] `PUT /hours` 接受選填 `lastOrderMinutes`，省略時保留原值，0–120 以外與非整數均回指定繁中錯誤。
- [x] 顧客下單在截止點後回 400；員工 POS 維持可建單。
- [x] 同日後續時段、過短後續時段、跨夜與例外日時段均使用規格指定訊息，且不出現「明日」。
- [x] 補齊 `L=0` 逐分鐘等價、短時段、交易保留、基本資料不覆寫與五條越權防線。
- [x] `coffee-orders` 零變更，既有四個未營業訊息的實作零修改。

## 驗證

- `git diff --check`：通過。
- `backend/mvnw`、`scripts/build.sh`、`start-demo.sh`：均為 `100755`。
- 本機 Maven（預設 cache）：Maven Central DNS 解析失敗，未進入編譯。
- 本機 Maven（借用既有 cache）：cache 內多個 POM/JAR 不完整（`zip END header not found`），無法完成編譯與測試；不是程式失敗。
- 遠端 CI：等待本次 head push 後驗證。

## 下一步

S3：前端型別、總部設定 UI、顧客停止接單／即將停止接單提示與元件測試。
