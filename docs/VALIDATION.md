# 本次驗證紀錄

日期：2026-09-05（Asia/Taipei）。

| 驗證項目 | 結果 |
| --- | --- |
| Vue / TypeScript 型別檢查及 Vite production build | 通過 |
| Maven 多模組 verify、編譯、JAR 打包 | 通過 |
| 整合、HTTP、模組架構、綠界檢查碼測試 | 13 項通過，0 失敗、0 錯誤、0 跳過 |
| 真實 HTTP 與 Cookie / CSRF 流程 | 空資料庫啟動、總部建分店 / 商品 / 帳號、客人下單、收銀、毛利報表均通過 |
| 同一 Spring Boot 應用提供 Vue 路由與靜態資源 | HTTP 測試通過 |
| Shell 啟動 / 建置腳本語法 | 通過 |
| Docker / 真實 PostgreSQL 執行 | 本環境沒有 Docker，未執行；資料庫測試採 H2 PostgreSQL mode |
| 瀏覽器視覺與互動自動測試 | 未執行 |
| 綠界真實测试卡與公開回呼 | 未執行，需商店及公開 HTTPS 網域 |
| GitHub 寫入 | 被整合權限拒絕，403；改提供原始碼及 Git bundle |

整合測試驗證登入與 CSRF、客人管理權限限制、店長跨店隔離、後端計價、冪等訂單、現金找零與重複收款、報表增量、訂單狀態機、歷史價格快照、綠界竄改 / 金額錯誤 / 模擬通知 / 重複通知、帳號停用 / 密碼版本更新，以及既有 Session 的角色權限撤銷。

HTTP 驗證發現並修正登入後 CSRF 輪替問題：登入明確保存 Spring Security context，並清除舊 CSRF；Vue 在登入成功後重新取得驗證碼。

ECharts 報表使用按需元件及獨立延遲載入 chunk。建置仍提示該 chunk 約 523 KB（gzip 約 176 KB）；不阻擋建置，客人點餐頁不需要載入報表模組。
