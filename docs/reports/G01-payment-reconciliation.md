# G01 金流對帳實作報告

來源：PR #8、規格 v1；主線基準 `16f801c91ead702bb331fb14fa91aa9dc1e23f01`。
PO 已授權輸出設計後直接實作，不等待額外設計確認。沒有修改 AGENTS.md 或 CLAUDE.md。

## 設計摘要

- payments：`TradeQuery` 傳輸界面、JDK HttpClient 查單、`Reconciliation` DTO／服務、三個營運端點與排程。
- orders：新增指定 paidAt 的 confirmOnline 多載與候選訂單查詢；付款模組不直接存取 orders 表，仍走公開 API。查詢在 SQL 內套用分店範圍。
- identity：新增 PAYMENT_RECONCILE；既有 SELF 角色驗證仍禁止配置此權限。預設 HQ／MANAGER 新增權限，收銀員不給。
- app：啟用 scheduling、新增 V2 migration、對帳設定與整合／真實 HTTP 測試。V1 不變，沒有新增第三方依賴。
- frontend：金流對帳路由、權限入口、待查核清單、手動查核、最近 50 筆歷程；沿用 shared/api 與 format。

## 規格疑點與採用方式

1. 正確綠界查單文件是 https://developers.ecpay.com.tw/2890/ ，原規格連結不正確。已核對查單端點、請求欄位、TradeStatus 與回應檢查碼要求。未對綠界發出交易或付款請求。
2. 回應除簽章／金額外，再比對 MerchantID、MerchantTradeNo，拒絕重複參數（含大小寫變體）及無效 TradeNo。不能用同金額的其他交易補登本單。
3. 官方查單回應欄位未列 SimulatePaid；若出現 1，仍遵循規格拒絕入帳。缺欄位或無法認證的錯誤回應保守記 QUERY_FAILED，不冒充已確認未付款。
4. Orders 原有 API 無法列出全範圍候選訂單，故增加公開候選查詢，維持原依賴方向。null Actor 僅供非 HTTP 排程使用。
5. 手動 POST 對已付款訂單依第 8 節回 409；查詢期間回呼先入帳則沿用行鎖與交易編號冪等，不覆寫回呼 paid_at。這與「重複查核一定新增兩筆」測試敘述有差異：已付款單不再主動查詢。
6. PaymentDate 缺漏／格式錯誤採規格所定查核時間並明示；可解析但未來或非正值則不入帳。
7. 預設採規格的 5 分鐘、15 分鐘、7 天、50 筆；不做通知、退款、取消、紀錄清理。
8. 排程分批輪巡，避免最舊未付訂單永久占滿第一批。單筆失敗不阻擋整批；入帳與查核紀錄在同一 DB 交易，失敗回滾後另記安全錯誤摘要。

## 測試計畫與驗收對照

- 前端 TypeScript 檢查與 production build。
- Flyway 空資料庫 V1→V2，既有 migration 不變。
- 已付款、未付款、模擬付款、金額不符、簽章錯誤、商店／訂單錯配、溢位、timeout；不合法回應不入帳。
- 台灣付款時間、缺時間 fallback、callback 冪等、查單中 callback 競態、同訂單並行手動請求限流。
- 排程靜默期／過期／CASH 排除，分店 SQL 範圍、SELF／收銀員／跨店拒絕、401 與 CSRF。
- HttpWorkflowTest 新增真實 HTTP + Cookie + CSRF 的對帳、歷程與報表流程；綠界使用 stub，不連外。
- 保留既有 CoffeeIntegrationTest、CheckMacTest 與 ModuleBoundariesTest；不降低原斷言。

## 驗證範圍與尚未完成的驗證

- 本地 `npm ci --no-audit --no-fund && npm run build` 通過；`./mvnw -B -ntp verify`（以環境代理取得依賴後離線重跑）通過，共 21 個測試、0 失敗、0 錯誤。
- 尚未完成綠界 stage 真實端到端驗證，也未使用正式憑證；功能預設關閉。
- 測試 DB 是 H2 PostgreSQL 模式；仍應在部署前驗證真實 PostgreSQL 升級路徑與既有角色資料遷移。
- 沿用單實例部署前提：同單查核互斥是 process-local；多實例排程協調／分散式限流不在此次範圍。DB 入帳行鎖與交易編號唯一性仍跨實例保護重複入帳。
- 官方提醒高頻查詢可能被 403 限制；正式啟用前需與綠界確認店家額度並調整批量／間隔。尚未加入全域 403 冷卻策略。
- 尚未有專屬 QueryTradeInfo 官方簽章測試向量；沿用已驗證的 CheckMac 實作與既有官方測試向量。
- 待查核清單依規格回傳完整時間窗，尚無前端分頁；營運資料量成長後應另立規格。

## 啟用

先完成 stage 驗證；在既有 ECPAY_* 憑證與 HTTPS 設定之外，設定 `ECPAY_RECONCILE_ENABLED=true`。
其餘設定：`ECPAY_QUERY_TIMEOUT_MS`、`ECPAY_RECONCILE_INTERVAL_MS`、`ECPAY_RECONCILE_MIN_AGE_MINUTES`、`ECPAY_RECONCILE_MAX_AGE_DAYS`、`ECPAY_RECONCILE_BATCH_SIZE`。
不要將正式金鑰放入 Git 或前端。未完成真實金流驗證前，不應將本 PR 視為正式收款上線驗收。
