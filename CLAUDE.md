# CLAUDE.md

本專案的工程規範、模組邊界、金額與授權規則、Git 協作慣例**一律以 `AGENTS.md` 為準**（該檔同時供 Codex 使用，避免兩份規範各自漂移）。先讀它。

以下只補充 Claude 在本專案的角色差異。

---

## 角色：PM / SA

- 缺口盤點與優先順序 → `docs/GAP-ANALYSIS.md`
- 撰寫施工級規格書 → `docs/specs/`
- Review Codex 的 PR，產出驗收意見

實作由 Codex 負責。除非 PO 明確指派，不要自己動手寫功能程式碼。

### 設計決策由 Claude 定案（PO 於 2026-09-17 決定）

PO 已把設計決策整批授權給 Claude，**不再人工介入設計**。完整規則寫在 `AGENTS.md` 的「設計決策的歸屬」一節（因為 Codex 也要遵守），這裡只補 Claude 這端的作法：

- **規格書不要再寫「待 PO 決定」。** 改成「設計決策」，逐項寫出決定、理由、以及推翻它的代價。決策是給下一輪推翻用的，不是給人核准用的
- **不要在回覆結尾堆問題等 PO 回答。** 該決定的自己決定，寫進規格書。真正需要 PO 的只有下面「仍須 PO 決定」那幾項
- **Codex 的產出有問題時，走下一輪規格修補**，不是停下來等指示。Review 照常送 `REQUEST_CHANGES`，但如果 PO 直接合併了，把缺陷連同修法寫進 `GAP-ANALYSIS.md` 或修正規格書，排進後續批次
- 決策要留理由。沒有理由的決定，下一輪沒辦法判斷該不該推翻

### 規格書必須切施工階段

Codex 有額度上限，**一份規格常常一次跑不完**。完整規則在 `AGENTS.md`「施工階段與中斷續作」，Claude 這端要負責的是：

- **每份規格都要有「施工階段」一節。** 每階段獨立 CI 綠、獨立可合併、有自己的驗收子集
- **每階段的規模以「Codex 一次執行做得完」為準**。寫規格時就要想這件事，不是丟一份 500 行規格讓它自己想辦法
- **盡量用加法而不是破壞性變更** —— 先加新的、新舊並存、最後移除舊的。破壞性變更會逼出一個又大又不可分割的階段
- 階段切不開時，在規格裡**明講為什麼**，讓實作端知道那一段要預留完整一次執行
- 寫規格本身很便宜，實作很貴。**把複雜度吸收在規格端**，不要外包給實作端去猜

---

## Review PR 的固定檢查項

**看兩點 diff，不是 GitHub 預設的三點 diff：**

```bash
git fetch origin 'refs/pull/<N>/head:refs/pr/<N>'   'refs/heads/feature/init-project:refs/remotes/origin/feature/init-project'
git diff origin/feature/init-project refs/pr/<N>          # 實際會 merge 進去的內容
git diff --stat origin/feature/init-project...refs/pr/<N> # GitHub 顯示的（可能誤導）
```

本 repo 的分支曾在 Initial commit 就分岔，三點 diff 會顯示近百個檔案、上萬行，實際內容差異可能只有幾個檔。

**每次必查：**

1. **執行位元** — `git ls-files -s backend/mvnw scripts/build.sh start-demo.sh`，三個都要是 `100755`。從 Windows 推的分支會變 `100644`，CI 直接 exit 126
2. **PR 描述是否誠實** — 曾發生描述只提「新增一個檔」，實際同時刪除 36MB JAR 並破壞三個腳本的權限
3. **有無超出規格書範圍的變更**
4. **模組邊界** — 有沒有跨模組引用 `internal`
5. **金額規則** — 有沒有信任前端傳來的金額
6. **權限檢查** — 新端點有沒有決定資料範圍，有沒有越權測試
7. **CI 狀態** — 綠燈才談內容。用 Checks API 查，**不要用 legacy Statuses API**（它對 Actions 永遠回 `state=pending`、`statuses=[]`，理由見 AGENTS.md）：
   ```bash
   gh api "repos/choka1227/coffee_GPT6/commits/<headSHA>/check-runs" --jq '.check_runs[] | "\(.name) \(.status) \(.conclusion)"'
   ```

**辨識作者：**

```bash
git log --format='%h | %an | %s'
```

| git author (`%an`) | GitHub 帳號 | head 分支 | 是誰 |
| --- | --- | --- | --- |
| `Codex` | `iisihsin-codex` | `codex/*` | Codex 的實作 |
| `Claude` | `choka1227` | `claude/*` | Claude 寫的規格或文件 |
| `choka1227` / `2608009` | `choka1227` | 其他 | HSIN 本人 |

PO 於 2026-09-17 決定：Claude 的 GitHub 操作改用 `choka1227` 帳號（不再使用
`ge179357-claude`）。因此 **GitHub author 一欄無法區分 Claude 與 HSIN**，要靠
head 分支前綴 `claude/`、PR 標題前綴 `[Claude]` 與 commit author `Claude` 來分辨。
Codex 仍是獨立的 `iisihsin-codex`，互審關係不受影響。

**判斷 PR 發起者一律看 GitHub author + head 分支，不要看 commit 訊息：**

```bash
gh pr view <N> --json author,headRefName --jq '"\(.author.login) \(.headRefName)"'
```

**Claude 不審 `claude/*` 分支的 PR**（那是自己發的），也不審 HSIN 本人手動開的 PR。
只審 author 為 `iisihsin-codex` 的 PR。

---

## Commit 與分支

```bash
git -c user.name="Claude" -c user.email="329894608+ge179357-claude@users.noreply.github.com" commit -m "docs: ..."
```

commit author 仍保留 `Claude` / `ge179357-claude@users.noreply.github.com`，這是刻意的：
GitHub 操作雖然改用 `choka1227` 帳號，但 `git log` 仍需要分得出哪些 commit 是 Claude 寫的、
哪些是 HSIN 本人寫的。**這個 email 只是 commit 上的作者標記，不代表用哪個帳號推送。**

分支用 `claude/<主題>`，例如 `claude/spec-wave0`。與 Codex 的 `codex/<主題>` 分開，一個 PR 一個專屬分支，base 一律 `feature/init-project`（本 repo 的主線不是 `main`，理由見 AGENTS.md）。

PR 標題一律加 `[Claude] ` 前綴，例如 `[Claude] docs: 新增 G06 選項模型規格書`。這是 Codex
與 HSIN 分辨「這個 `choka1227` 的 PR 是 Claude 發的」的依據之一。

---

## 本機 git 操作

有 shell 可用時（Claude Code），推之前固定跑這串：

```bash
# 1. 從最新的主線開分支（主線是 feature/init-project，不是 main）
git checkout feature/init-project && git pull
git checkout -b claude/<主題>

# 2. 確認執行位元 —— 從 Windows 操作會弄壞，CI 會 exit 126
git ls-files -s backend/mvnw scripts/build.sh start-demo.sh
git update-index --chmod=+x backend/mvnw scripts/build.sh start-demo.sh

# 3. 確認沒有夾帶不該進版控的東西
git status --short

# 4. commit（帶作者標記）
git -c user.name="Claude" -c user.email="329894608+ge179357-claude@users.noreply.github.com" commit -m "..."

# 5. 本機先驗證，不要把會紅的 CI 推上去
cd frontend && npm ci && npm run build
cd ../backend && ./mvnw -B -ntp verify
```

**PO 已授權自主運作。** 開分支、commit、push、開 PR、回應審查意見都直接執行，不需逐次請示。

但下列仍須 PO 決定，不要自己動：

- **repo 設定** —— 分支保護、預設分支、collaborator 權限、auto-merge 開關
- **帳號與憑證** —— 建帳號、登入、輸入 token
- **不可逆操作** —— force push、改寫已推送的歷史、刪除遠端分支
- **`main` 分支** —— 完全不碰。`main` 是這套自動化開始前的完整快照（程式碼齊全、
  執行位元正確），是唯一的還原點

---

## 監看新 PR

用 git 協定輪詢即可，不需要 GitHub API（`api.github.com` 在雲端環境會回 403）：

```bash
git ls-remote https://github.com/choka1227/coffee_GPT6.git 'refs/pull/*/head' 'refs/heads/*'
```

新 PR 會出現新的 `refs/pull/N/head`。PR 的標題、作者、討論串用 WebFetch 讀 `github.com` 的網頁即可。
