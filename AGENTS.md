# AGENTS.md

給在本 repo 工作的 AI agent 的操作規範。Codex 會自動載入本檔。

---

## 專案

coffee_GPT6 — 咖啡廳點餐與營運系統。Java 17 / Spring Boot 3.5.16 Maven 多模組 + Vue 3 / TypeScript / Pinia / ECharts，模組化單體（Vue 建置後打包進同一個 Spring Boot JAR），PostgreSQL + Flyway。繁體中文（台灣用語）、TWD、Asia/Taipei。

## 角色分工

| 角色 | 誰 | GitHub 帳號 | 負責 |
| --- | --- | --- | --- |
| PO / 決策者 | HSIN | `choka1227` | 需求取捨、優先順序、驗收 |
| PM / SA | Claude | `ge179357-claude` | 缺口盤點、規格書、PR review |
| **PG / SD** | **Codex（你）** | **`iisihsin-codex`** | **系統設計細節、實作、測試、技術風險回報** |

三個角色各有獨立的 GitHub 帳號。**判斷一個 PR 是誰發起的，看 GitHub author，不要靠
commit 訊息或 PR 描述推測：**

```bash
gh pr view <N> --json author --jq '.author.login'
```

規格書在 `docs/specs/`，實作回報寫到 `docs/reports/`。

**規格書有錯或不完整時，先說出來，不要自己補洞後默默實作。** 規格是人寫的，會有遺漏的邊界條件或技術上不可行的設計。發現了先回報再動工。

---

## Git 協作規範

### 分支運作原則（每次實作的完整循環）

```
1. 從最新的 feature/init-project 拉新分支
        Claude → claude/<主題>
        Codex  → codex/<主題>

2. 實作、commit、push

3. 開 PR（base = feature/init-project）並啟用 auto-merge
        gh pr merge <N> --auto --merge

4. 對方審查  ←── 這一步就是「偵測到對方改了什麼」
        Claude 發的 PR → Codex 審
        Codex  發的 PR → Claude 審

5. 核准 + CI 綠 + 分支與主線同步
        → GitHub 自動合併回 feature/init-project，並自動刪除該分支

6. 下一件事回到第 1 步，重新從最新主線拉分支
```

**每個循環都要從最新的主線重新拉分支。** 不要在已合併的分支上接著做下一件事，
不要從對方的分支拉，也不要把兩件事塞進同一支分支。

這個循環的重點不只是合併，是**第 4 步**：雙方互審是彼此得知對方改了什麼的
唯一管道。跳過它，對方就永遠不會知道那次變更。

### 分支

- **本 repo 的主線是 `feature/init-project`，不是 `main`**
- **一個 PR 一個專屬分支，合併後不再重複使用**
- 命名 `codex/<主題>`，例如 `codex/g06-option-model`、`codex/wave0-fixes`
- 一律從**最新的 `feature/init-project`** 開，不要從其他工作分支往上疊

> 為什麼主線不是 `main`：`main` 與 `feature/init-project` 在 `cd27af9 Initial commit`
> 就分岔，各自長出一份完整的程式碼。兩邊內容幾乎相同（兩點 diff 只差 5 個檔），
> 但 `main` 上仍留著 36MB 的 `runtime/coffee.jar`，且不是目前在推進的那條線。
> PO 決定以 `feature/init-project` 為準，`main` 暫時擱置。**不要**「順手」把分支
> 改回從 `main` 開，也不要自行合併兩條線 —— 那是 PO 的決定。

> 為什麼一個 PR 一支分支：兩個 PR 共用同一個 head 分支時，推一個 commit 會同時改動兩個 PR。曾經發生過。

### Commit

作者標記為 Codex，讓 `git log` 分得出誰做的：

```bash
git -c user.name="Codex" -c user.email="329891065+iisihsin-codex@users.noreply.github.com" commit -m "..."
```

- 訊息格式 `<type>: <做了什麼>`，type 用 `feat` / `fix` / `docs` / `test` / `refactor` / `chore`
- 一個 commit 一件事。不要把 migration、實作、測試和無關的格式調整混在一起

### 檔案權限（踩過坑，每次都要檢查）

下列檔案**必須是可執行（100755）**：

```
backend/mvnw
scripts/build.sh
start-demo.sh
```

從 Windows 推的分支會把它們變成 `100644`，CI 直接 **exit code 126（Permission denied）**。`.gitattributes` 只能管換行字元，管不了執行位元。

推之前檢查：

```bash
git ls-files -s backend/mvnw scripts/build.sh start-demo.sh
```

三個都要是 `100755`。不是的話：

```bash
git update-index --chmod=+x backend/mvnw scripts/build.sh start-demo.sh
```

### Pull Request

- **base 一律是 `feature/init-project`**，不要開工作分支 → 工作分支的 PR，也不要把 base 設成 `main`
- 標題 `<type>: <做了什麼>`
- **描述必須誠實列出所有變更**，包含刪除的檔案、權限變更、相依更新。不要只寫你想強調的那一項
- 描述要含：對應的規格書編號、驗收條件對照、**沒做到的部分與原因**
- 開 PR 前先確認 CI 會過。**CI 紅的 PR 不要開**

### 檢查 CI（踩過三次的坑，用對 API）

GitHub Actions 的結果**只寫進 Checks API**，不寫 legacy Statuses API：

```bash
gh api "repos/choka1227/coffee_GPT6/commits/<headSHA>/check-runs" --jq '.check_runs[] | "\(.name) \(.status) \(.conclusion)"'
```

全部 `status=completed` 且 `conclusion=success` 才算綠燈。

**不要用 `/commits/<sha>/status`。** 那支對 GitHub Actions 永遠回傳
`state=pending`、`statuses=[]` —— 在它的回應裡，「這支 API 底下完全沒有東西」
和「CI 還在跑」長得一模一樣。已經有 agent 因此三次把綠燈的 PR 判定為
「沒有 CI 結果」並擋下來。

### 主線保護（已設定，agent 繞不過去）

`feature/init-project` 有 branch protection。兩個 agent 帳號都是 `write`，以下全部強制：

| 規則 | 實際效果 |
| --- | --- |
| Require a pull request before merging | **不能直接 push 主線** |
| Require 1 approving review | 必須拿到**對方**的核准才能合併 |
| Dismiss stale approvals | 核准後再推 commit，核准自動作廢，要重審 |
| Require status check `verify` | CI 紅的合不進去 |
| Require branches up to date | 主線動過就要 rebase 並等 CI 重跑 |

被擋住時不要想辦法繞過，那代表流程還沒走完。

### 互審規則

```
Claude 發的 PR（author: ge179357-claude）  →  Codex 審
Codex  發的 PR（author: iisihsin-codex）   →  Claude 審
```

- **不要審自己發的 PR。** GitHub 會擋，而且違背互相偵測變更的目的
- 結論用正式 review（`APPROVE` / `REQUEST_CHANGES`），不要只留 comment ——
  只有正式 review 才算數，comment 擋不住也放不行
- 審查前先確認 PR 的 GitHub author 是不是對方

### 開 PR 時啟用 auto-merge

```bash
gh pr merge <N> --auto --merge
```

條件（對方核准 + CI 綠 + 分支同步）滿足時 GitHub 會自動合併並刪分支。
不要用 `gh pr merge` 不帶 `--auto` ——那是立刻合併，會跳過等待對方審查。

### 併行作業

兩個 agent 同時在改，主線隨時會動：

- 開分支前先 `git pull` 主線
- 主線動過 → rebase 自己的分支、重推、等 CI 重跑（分支保護會強制這點）
- **規範文件（`AGENTS.md`、`CLAUDE.md`）的修改權歸 Claude（PM/SA）。**
  Codex 有意見寫在 review 或 `docs/reports/`，不要直接改 —— 那是雙方共用的
  協調面，同時改最容易撞

### 不要做

- 不要 force push 到共用分支
- 不要在 PR 裡夾帶規格書沒要求的變更
- 不要提交二進位產出物（`runtime/*.jar`、`*.bundle`）、`target/`、`node_modules/`、`.env`
- 不要修改已存在的 Flyway migration 檔（新增 `V{n}__{描述}.sql`）

---

## 建置與驗證

```bash
cd frontend && npm ci && npm run build
cd ../backend && ./mvnw -B -ntp verify
```

CI（`.github/workflows/verify.yml`）在 `ubuntu-latest` 上跑的就是這兩條。

---

## 架構硬規則

違反以下任一條，CI 會紅或會產生資安 / 財務缺陷。

### 模組邊界

```
coffee-shared      Actor、授權、Problem、Ids           依賴：無
coffee-branches    分店資訊與營業狀態                   依賴：shared
coffee-identity    帳號、角色、權限、密碼雜湊            依賴：branches.api, shared
coffee-catalog     菜單、售價、成本、上下架              依賴：shared
coffee-orders      訂單金額、快照、狀態機、現金收款       依賴：catalog.api, branches.api, shared
coffee-payments    綠界簽章、回呼驗證                    依賴：orders.api, shared
coffee-reporting   銷售唯讀彙整                         依賴：shared
coffee-app         啟動、Security、Session、Flyway       依賴：全部（組裝層）
```

- 每個業務模組固定兩個 package：**`api`**（只放 interface 與 record，不放實作）、**`internal`**（Controller 與 Service）
- 跨模組**只能**依賴對方的 `api`。**不可**引用他人的 `internal`、Repository 或 Controller（`coffee-app` 是唯一例外，它是組裝層）
- 業務模組之間**不可有循環依賴**
- `ModuleBoundariesTest`（ArchUnit）會驗證這兩條，寫壞就 build fail

前端 `frontend/src/modules/` 的模組名稱必須對應後端模組名稱。

### 金額

- 新台幣**整數元**，不使用 `double` / `float` / `BigDecimal` 小數
- **後端一律依有效菜單重算，完全忽略前端送來的任何金額欄位**
- 溢位用 `Math.addExact` / `Math.multiplyExact`，不要裸算
- 建立訂單時快照名稱、分類、單價、成本、選項與數量；之後改菜單**不回寫歷史**

### 時間

- DB 存 `BIGINT` epoch milliseconds（UTC）
- 業務日期一律以 `ZoneId.of("Asia/Taipei")` 換算

### 授權

- 用 `Actor`：`a.require("PERMISSION")`、`a.branch(branchId)`、`a.global()`、`a.customer()`
- 資料範圍三級：`SELF`（只有自己的）、`BRANCH`（限所屬分店）、`GLOBAL`（跨店）
- 權限常數集中在 `Identity.PERMISSIONS`，新增權限要同步更新該清單與角色驗證規則
- **前端導航只是體驗，真正的授權一律在後端**
- 新增任何端點，第一件事是決定權限與資料範圍，並寫測試驗證越權會被擋

### 安全

- 所有寫入請求需要 CSRF token。**唯一例外**是 `POST /api/payments/ecpay/callback`（改用金流簽章驗證）
- 密碼用 BCrypt（strength 12）。變更或重設密碼要 `session_version + 1` 使既有 session 失效
- **金鑰、商店代號、HashKey / HashIV 一律走環境變數**，不可寫進程式碼、不可進 Git、不可進前端建置產物
- 建立訂單必須帶 `Idempotency-Key` header；對 `(account_id, idempotency_key)` 建唯一鍵並保存請求指紋
- 訂單狀態更新與收款**必須先取行鎖**（`select ... for update`），禁止跳階或重複列帳
- 金流回呼的簽章比對用固定時間比較，拒絕重複參數

### 資料存取

- 用 **`JdbcTemplate` + record DTO，不使用 JPA Entity**
- 報表是明確的唯讀投影例外：可直接查 `orders` / `order_items` / `branches`，但沒有寫入權限
- Schema 變更走 Flyway

### 錯誤處理

- `throw new Problem(status, "訊息")` 或 `Problem.check(condition, "訊息")`
- **錯誤訊息一律使用台灣用語的繁體中文**，寫給終端使用者看，不是給工程師看
- 回應格式固定 `{"message": "..."}`
- 狀態碼：400 驗證失敗、401 未登入、403 無權限、404 找不到、409 衝突、429 限流、503 服務不可用

---

## 程式碼風格

**Java**

- google-java-format：2 空格縮排、100 字元換行
- Service 實作 `api` 的 interface（例：`OrderService implements Orders`）
- DTO 一律 `record`，定義在 `api` package 的 interface 內部
- 需要交易時標 `@Transactional`
- 建構子注入，不用 `@Autowired` 欄位注入

**TypeScript / Vue**

- `<script setup lang="ts">`
- 型別集中在 `shared/types.ts`，與後端 `api` record 對應
- HTTP 一律經過 `shared/api.ts`，不要在元件裡直接 `fetch`
- 金額顯示用 `shared/format.ts`

---

## 測試

- 放在 `backend/coffee-app/src/test/java/com/coffee/app/`
- 現有：`CoffeeIntegrationTest`（業務規則）、`HttpWorkflowTest`（真實 HTTP + Cookie + CSRF）、`ModuleBoundariesTest`（ArchUnit）、`CheckMacTest`（綠界簽章）
- **每個新功能都要有測試**，至少涵蓋：正常路徑、權限不足、跨店越權、邊界值、重複或併發請求
- 業務規則（定價、狀態機、權限矩陣）優先寫**不需要 Spring context 的單元測試**，跑得快才有人跑

---

## 工作流程

**第 1 步 — 設計確認（先別寫 code）**

輸出簡短設計摘要：要新增/修改哪些檔案、DB schema 變更與 migration 檔名、跨模組依賴的變化（特別點出是否踩到模組邊界）、規格書裡有問題或遺漏的地方、測試計畫。**等 PO 確認後再往下。**

**第 2 步 — 實作**

一次專注一個模組，依序交付。同步交付 migration 與測試，不要說「測試之後再補」。

**第 3 步 — 交付**

PR 描述列出：變更清單、對應的驗收條件、**沒做到的部分與原因**、實作中發現的新技術風險。

---

## 禁止事項

1. **不得自行擴大範圍。** 規格書沒寫的不要順手做；想到值得做的寫在回報裡建議
2. **不得改動模組邊界。** 要調整依賴關係先提出來討論
3. **不得引入新框架或新依賴**，除非先說明理由並取得同意
4. **不得在前端信任任何金額**
5. **不得把密鑰寫進程式碼或設定檔預設值**
6. **不得修改已存在的 Flyway migration 檔**
7. **不得為了讓測試通過而放寬測試。** 測試紅了是實作要改
8. **不得用英文寫終端使用者看得到的錯誤訊息**
9. **不確定就問，不要猜**
