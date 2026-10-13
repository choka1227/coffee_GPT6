# 綠界測試環境付款整合

本系統目前**只允許綠界 stage 測試環境**。後端啟動時會拒絕任何非 `stage` 的環境值與綠界正式查單網址；前端與 CSP 也只接受 stage 付款頁。正式商店憑證不得放進 repository。

## 已實作

- AioCheckOut V5 信用卡一次付清；表單金額取自後端訂單快照，不採信客戶端金額。
- SHA-256 CheckMacValue；參數依不分大小寫 A–Z 排序，使用相容 .NET 的 URL encode 規則。
- 只有 `POST /api/payments/ecpay/callback` 免登入與 CSRF，並驗證簽章、特店、訂單、金額及 TradeNo。
- `RtnCode=1` 且 `SimulatePaid != 1` 才入帳；有效重送保持冪等。
- 主動查單、店長手動對帳與排程對帳；回呼遺失時可由對帳補救。
- 支付事件只保存交易編號、結果碼、模擬旗標與時間，不保存卡號、CVV、金鑰或完整回呼。

## Demo 預設啟用

`./start-demo.sh` 會啟用 `dev,ecpay-stage` profiles，使用綠界官方公開測試特店資料（不是正式憑證）：

- MerchantID：`3002607`
- HashKey：`pwFHCqoQZGmho4w6`
- HashIV：`EkRm7iFT261dpevs`
- 排程每 2 分鐘執行，查核建立至少 3 分鐘的待付款訂單
- 線上付款需在建立後 10 分鐘內開啟付款；40 分鐘仍未付款會先查單再作廢，作廢後 10 分鐘再複查一次

先執行 `./scripts/build.sh`，再執行 `./start-demo.sh`。若只是開發其他功能而不想啟用金流：

```bash
ECPAY_ENABLED=false ECPAY_RECONCILE_ENABLED=false ./start-demo.sh
```

本機預設 `APP_PUBLIC_URL=http://localhost:8080`，綠界無法把回呼送到本機；啟動時會記錄 WARN，付款狀態可由排程對帳補上。要驗證回呼，需自行準備公開 HTTPS 通道；本專案不指定通道工具，也不新增相關依賴或設定檔。

## Stage 實機驗證

### 準備

1. 執行 `./scripts/build.sh`。
2. 準備「公開 HTTPS 網址 → 本機 `127.0.0.1:8080`」通道。
3. 執行 `APP_PUBLIC_URL=https://<通道網址> ./start-demo.sh`。
4. 測試卡號、有效年月、安全碼與 3D 驗證碼以[綠界官方測試資料](https://developers.ecpay.com.tw/2856/)為準。

### 驗證步驟

1. **回呼主路徑**：以 demo 顧客登入、建立線上付款訂單並在 stage 完成付款。預期數秒內變成「已付款」，`payment_events` 新增 `result_code=1`、`simulated=false`。
2. **對帳補救**：不設 `APP_PUBLIC_URL` 直接啟動並完成付款。預期先維持待付款，3–5 分鐘內排程 log 出現 `CONFIRMED 1`，訂單轉已付款，`payment_reconciliations` 新增 `trigger_source=SCHEDULED`、`outcome=CONFIRMED`。也可由店長在對帳頁按「查核」立即觸發。
3. **非 HTTPS ReturnURL**：若步驟 2 在付款頁即被拒，記錄錯誤碼／訊息；改用步驟 1 的通道網址並關閉通道，重做對帳補救。
4. **查無交易**：建立線上付款訂單後關閉付款頁。3–5 分鐘後應為 `STILL_UNPAID`；記錄綠界實際 `TradeStatus`（自動化假站採 `10200047`）。
5. **同訂單再次付款**：第一次進付款頁後不付款返回，再按一次付款；記錄是否出現訂單編號重複。此結果是 G04 的設計輸入，本規格不處理。
6. **遲到回呼**：訂單已由對帳轉為 `PAID` 後，在綠界後台重送同一交易通知（若後台支援）。預期回覆 `1|OK`、狀態與 `paid_at` 不變、不重複入帳。
7. **綠界付款頁可停留多久**：建立線上付款訂單，進入 stage 付款頁後不操作，每 5 分鐘嘗試繼續付款一次；記錄失效時間，或記錄 60 分鐘後仍可付款。若可停留超過 30 分鐘，回報以便調高作廢門檻。
8. **正常逾時作廢**：建立線上付款訂單後不付款。約 40–44 分鐘後，訂單應為「已取消」，log 應有 `綠界逾時作廢完成` 且「作廢 1」，稽核應有系統的 `ORDER_EXPIRED`，查單紀錄應為 `SCHEDULED`／`STILL_UNPAID`，限量庫存應回補。再過約 10–14 分鐘，log 應有作廢後複查「未付款 1」。
9. **付款窗口**：建立線上付款訂單，等待 11 分鐘後從訂單頁按付款。預期顯示「此訂單已超過線上付款期限，請重新下單」，不前往綠界。
10. **作廢後付款**：只有步驟 7 顯示付款頁可停留超過 30 分鐘時執行，否則記「不適用」。第 9 分鐘進付款頁，停留到訂單約第 40 分鐘作廢後再付款。預期回呼或作廢後複查建立一筆異常；店長在對帳頁完成 stage 後台退款、填寫處理說明並按「已處理」後，異常消失。

驗收紀錄：

| 步驟 | 日期 | 結果（通過／不通過／不適用） | 觀察（錯誤碼、TradeStatus 實際值等） |
| --- | --- | --- | --- |
| 1–10 |  |  |  |

## 逾時作廢與作廢後付款

啟用 `ecpay.expire.enabled` 後，系統只會在訂單超過作廢門檻、且向綠界查單確認「未付款」或「模擬付款」時作廢；查單逾時、回應驗證失敗或金額不符都會保留訂單，下輪重試。作廢會釋放保留庫存並留下 `ORDER_EXPIRED` 稽核紀錄。

作廢後若收到有效付款回呼，或後續複查發現款項，訂單維持 `CANCELLED`，系統建立「作廢後收到付款」異常，不會自動退款。總部可查看全部分店，店長只能查看及結案自己分店的異常；請先在綠界 stage 後台退款，再於對帳頁填入退款或處理說明並結案。

demo profile 可用下列環境變數調整：

```dotenv
ECPAY_EXPIRE_ENABLED=true
ECPAY_EXPIRE_CHECKOUT_WINDOW_MINUTES=10
ECPAY_EXPIRE_AFTER_MINUTES=40
ECPAY_EXPIRE_RECHECK_AFTER_MINUTES=10
ECPAY_EXPIRE_INTERVAL_MS=120000
```

## 環境變數

正式設定預設不含任何特店資料。若不使用 demo profile，可明確提供：

```dotenv
ECPAY_ENABLED=true
ECPAY_ENVIRONMENT=stage
ECPAY_MERCHANT_ID=3002607
ECPAY_HASH_KEY=pwFHCqoQZGmho4w6
ECPAY_HASH_IV=EkRm7iFT261dpevs
ECPAY_RECONCILE_ENABLED=true
APP_PUBLIC_URL=https://你的公開測試網域
COOKIE_SECURE=true
```

`ECPAY_QUERY_URL` 僅供測試覆寫：只接受精確綠界 stage 查單 URL，或具明確連接埠的 `http://127.0.0.1`／`http://localhost`。任何正式網域、非 loopback 或重新導向都不會被跟隨。

## 官方來源

- [全方位付款建立訂單](https://developers.ecpay.com.tw/2862/)
- [檢查碼機制](https://developers.ecpay.com.tw/2902/)
- [測試商店、測試卡與模擬通知](https://developers.ecpay.com.tw/2856/)
