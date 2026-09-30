# G23 前端元件層測試實作報告

來源規格：`docs/specs/G23-frontend-component-tests.md` v1.1  
來源主線：`feature/init-project@181e12ef4893b3dfff76ff44fa00e7d0239fc135`

## 施工進度

- [x] S1 — 元件測試基礎設施
  - 僅新增 `jsdom` 與 `@vue/test-utils` 兩個 devDependency，scripts 未變
  - Vitest 分成既有 `node` 與新增 `dom` project
  - 新增條件式 dialog／UUID shim、fetch／Pinia／router 掛載工具與文字選取工具
  - `Modal.dom.test.ts` 覆蓋標題、slot 與 close emit
- [x] S2 — `MenuView` 可見性矩陣
  - 夾具照抄 CUSTOMER、CASHIER、HQ 的真實 scope 與權限
  - 六個案例皆經由商品卡與 Modal 加入購物車，覆蓋現金、顧客、優惠碼、綠界、打烊與售完狀態
- [ ] S3 — 兩段式收款流程與送出內容

## 設計與範圍

- 沿用規格的 `.dom.test.ts` 命名，使 node 與 dom project 的 include 天然互斥；未覆寫 Vitest `exclude`。
- `setup.dom.ts` 的 `showModal`、`close`、`crypto.randomUUID` 都只在缺少時補上。
- 測試 helper 位於 `src/shared/testing/`，沒有被產品 `.vue`、`main.ts` 或非測試程式引用。
- 不修改任何 `.vue`、後端、Flyway migration 或 GitHub Actions workflow，也不改模組邊界。
- 本階段未發現規格矛盾；Vitest 3.2.7 可直接使用 `test.projects`，不需要 workspace 替代方案。

## S1 驗證

- `npm run test`：成功，node 57 項、dom 3 項，共 60 項。
- `npm run build`：成功。
- `git diff --check`：成功。
- `grep` 檢查產品程式引用 `shared/testing`：無輸出。
- `.vue`、`backend/`、migration 與 workflow：零變更。
- `backend/mvnw`、`scripts/build.sh`、`start-demo.sh`：皆為 `100755`。
- `./mvnw -B -ntp verify`：本地 Maven Central DNS 解析失敗，等待最新 head 的 GitHub Actions 補足。

## S2 驗證

- `npm run test`：成功，六個 `MenuView` 可見性案例全綠。
- `npm run build`：成功。
- 不存在斷言使用 `toBeNull()`／`exists() === false`，未使用 `isVisible()`。
- 案例 5 只斷言結帳按鈕 disabled，未釘住打烊訊息文字。

## 下一步

S3 新增兩段式收款流程，白名單驗證訂單 body 與現金收款 body。
