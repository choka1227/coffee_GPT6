# 前端視覺一致性整理（2026-10-23）

## 需求與設計摘要

依 PO 本次「整理整個專案前端、統一縮排與字體、改善排版」指示施工；本次沒有獨立 G 編號規格，視為單一完整交付階段。

- 範圍：14 個路由畫面（登入、客人／櫃台點餐共用頁、訂單、報表、分店、本店營業設定、菜單、優惠碼、促銷、帳號、角色、現金班別、稽核、金流對帳）。
- 保留 MORNING POUR 深綠、暖白與咖啡色品牌，建立共用字體、12/14/16px 基礎字級、頁面留白、卡片內距與圓角變數。
- 調整 frontend/src/style.css、共用 Modal／Chart，及需要結構補齊的營運頁；其他頁面沿用共用樣式改善。
- 無 DB schema／migration、API、權限或跨模組依賴變更；不引入框架或相依套件。

## 盤點與處理

1. 金流對帳頁沒有頁面容器、標準按鈕與分區：套用標題、待辦卡片、可捲動表格、查核歷程與空白狀態，保留所有原有 API 操作與錯誤文字。
2. 優惠碼與促銷使用未定義的 card-grid／admin-card／badge：補齊卡片網格，使用共用狀態標籤；增加無資料提示與沿用既有新增入口。
3. 彈窗只有 form-stack／receipt／product-detail 有內距：由 Modal 統一包覆 modal-body；所有表單與營業時間編輯器均有一致留白。
4. 表單核取方塊被通用 width:100% 影響：統一核取方塊尺寸、明確套用 checkbox-label；手機表單單欄、儲存按鈕獨立整列。
5. 現金班別與稽核篩選內容貼齊卡片邊緣：套用 panel-body／section-heading／panel-footer，統一段落間距。
6. 分店全寬按鈕另有左外距：移除造成右側溢出的外距；營業時段輸入欄位允許縮小、單日設定操作允許換行。
7. 側欄項目增加後低高度螢幕可能遮住登出：導航獨立捲動，品牌與帳號區維持可見。
8. 報表五張指標卡原先固定四欄：依可用寬度自動排列；ECharts 主題繼承頁面字體。
9. 點餐溫度／甜度選項原先沿用瀏覽器預設群組外觀：統一圓角、兩欄選項與選取狀態；商品供應操作允許換行，手機通知寬度避免被縮成半頁。

## 驗證計畫與紀錄

- 前端 Vitest：20 個檔案、206 項測試全數通過（包含新增的圖表字體繼承測試）。以本機 bundled Node 24.22.1 執行；原系統 Node 25 會遇到現有 jsdom 引擎版本警告。
- TypeScript 與 Vite build 通過；保留既有 ECharts chunk 超過 500 kB 提醒，不為本次視覺整理擴大到相依或分包調整。
- 後端 Maven verify：502 項測試全數通過、10 個模組 BUILD SUCCESS，並成功產生 Spring Boot JAR。
- Windows 預設主控台編碼造成第一次七項中文日誌斷言失敗，使用 JAVA_TOOL_OPTIONS 的 file.encoding、stdout.encoding、stderr.encoding、sun.stdout.encoding、sun.stderr.encoding=UTF-8 重跑；第二次測試全綠但示範程式鎖住 JAR。停止該 dev 程式後第三次完整 verify 成功，再以原路徑與 dev profile 重啟。沒有修改測試或後端原始碼。
- 瀏覽器：12 個總部路由在 1440／390／320 px 視窗逐頁等待載入，量測頁面 scrollWidth 均未超過可視寬度（Windows 垂直捲軸可能佔 15 px）；寬表格留在自己的 table-wrap 裡。
- 店長：本店營業設定在桌機／320 px 檢查；分店營業時間彈窗在 320 px 新增未儲存的時段，開始、結束與刪除操作完整可見。
- 顧客：登入、桌機／手機點餐、商品選項彈窗、加入點餐單與展開點餐單檢查完成；未送出訂單、未付款、未儲存營業設定。
- 三個 shell 腳本執行權限均維持 100755；git diff --check 通過。

## 施工進度

- [x] S1 全站樣式與頁面整理、回歸驗證。
- [ ] 遠端 CI、PR 與互審交付。

## 中斷續作位置

- 分支：codex/frontend-visual-consistency；基底為 feature/init-project 的 9d0c248。
- 修改與本機測試已完成，請勿重做或另開實作分支。
- 下一步：檢查遠端目前 head 的 Checks API；成功後建立／更新此分支的 PR（base=feature/init-project），並確認附加到 chat。
- GitHub connector 的 auto-merge 工具無法指定 merge 方式，不可改用 squash；在 PR 描述註明，由 Claude 依 AGENTS.md 的目前 head APPROVE／CI／無衝突條件處理。
- frontend/ui-review.html 是本機暫存的 viewport 檢查頁，不屬於交付檔案；交付前移除。正式預覽為 http://localhost:5173/，整包預覽為 http://localhost:8080/。

## 限制

- 共用字體採系統字型堆疊，不新增遠端字型下載；不同作業系統的實際字型仍由可用字體決定。
- 開發環境未啟用金流，失敗發票及對帳動作由既有元件測試覆蓋。
