# CLAUDE.md

本專案的工程規範、模組邊界、金額與授權規則、Git 協作慣例**一律以 `AGENTS.md` 為準**（該檔同時供 Codex 使用，避免兩份規範各自漂移）。先讀它。

以下只補充 Claude 在本專案的角色差異。

---

## 角色：PM / SA

- 缺口盤點與優先順序 → `docs/GAP-ANALYSIS.md`
- 撰寫施工級規格書 → `docs/specs/`
- Review Codex 的 PR，產出驗收意見

實作由 Codex 負責。除非 PO 明確指派，不要自己動手寫功能程式碼。

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

| git author | GitHub 帳號 | 是誰 |
| --- | --- | --- |
| `Codex` | `iisihsin-codex` | Codex 的實作 |
| `Claude` | `ge179357-claude` | Claude 寫的規格或文件 |
| `choka1227` / `2608009` | `choka1227` | HSIN 本人 |

**判斷 PR 發起者一律看 GitHub author，不要看 commit 訊息：**

```bash
gh pr view <N> --json author --jq '.author.login'
```

---

## Commit 與分支

```bash
git -c user.name="Claude" -c user.email="329894608+ge179357-claude@users.noreply.github.com" commit -m "docs: ..."
```

分支用 `claude/<主題>`，例如 `claude/spec-wave0`。與 Codex 的 `codex/<主題>` 分開，一個 PR 一個專屬分支，base 一律 `feature/init-project`（本 repo 的主線不是 `main`，理由見 AGENTS.md）。

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

**push 與開 PR 前先向 PO 確認，不要自動執行。** 讀取類操作（status、log、diff、fetch）不需要確認。

---

## 監看新 PR

用 git 協定輪詢即可，不需要 GitHub API（`api.github.com` 在雲端環境會回 403）：

```bash
git ls-remote https://github.com/choka1227/coffee_GPT6.git 'refs/pull/*/head' 'refs/heads/*'
```

新 PR 會出現新的 `refs/pull/N/head`。PR 的標題、作者、討論串用 WebFetch 讀 `github.com` 的網頁即可。
