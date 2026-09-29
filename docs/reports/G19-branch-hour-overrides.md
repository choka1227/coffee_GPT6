# G19 分店例外營業日實作回報

## 來源

- 規格：`docs/specs/G19-branch-hour-overrides.md` v1.1
- 主線基準：`feature/init-project@ec85a0c7b9d45af81f6667d09c8e89bf885f67f7`
- 實作分支：`codex/g19-branch-hour-overrides`

## 完成內容

- S1：V10 例外日資料表、服務契約、日期優先序、跨夜阻斷、稽核與後端測試。
- S2：GET／PUT／DELETE 端點、授權、CSRF、路徑日期優先與 HTTP 驗收測試。
- S3：前端型別、例外日純函式與 Vitest；總部可新增、修改、列出與刪除公休／自訂時段；顧客端在今日公休時顯示後端例外資料的公休備註。

## 安全與邊界

- 未新增權限位元或第三方相依。
- 未修改既有 migration、`SecurityConfiguration`、`AGENTS.md` 或 `CLAUDE.md`。
- 例外日備註只用 Vue 文字插值顯示，沒有 `v-html`。
- `overrides.ts` 不依賴 Vue、API 或 identity store。

## 驗證

- `npm run test`：56/56 成功。
- `npm run build`：成功。
- `git diff --check`：成功。
- 後端完整驗證與最新 head GitHub Actions 結果記錄於 PR #42。
