# 綠界付款整合

## 已實作

- 全方位金流 AioCheckOut V5，信用卡一次付清。
- 後端以訂單快照建立 POST form 欄位，總額不取自客戶端；商店交易編號為 20 個英數字且唯一。
- 使用 SHA-256 CheckMacValue，參數依不分大小寫 A–Z 排序，整串 URL encode，轉小寫、對齊 .NET URL encode 字元規則、雜湊後轉大寫。
- 只有 `POST /api/payments/ecpay/callback` 免 CSRF（必須驗證金流簽章）；登入、現金收款、管理操作均需要 CSRF。
- 回呼驗證 CheckMacValue、MerchantID、MerchantTradeNo、TradeAmt、支付方式及 TradeNo。拒絕重複參數。簽章採固定時間位元組比較。
- `RtnCode=1` 且 `SimulatePaid != 1` 才更新付款成功。`SimulatePaid=1` 僅記錄並回覆，不計業績；有效重複通知回覆 `1|OK` 且不重複入帳。
- ClientBackURL 只回到訂單畫面，不能以瀏覽器返回網址或查詢參數認定付款成功。
- 支付事件僅記錄交易編號、結果碼、模擬旗標與時間，不保存卡號、CVV 或整份原始回呼。

## 啟用測試環境

在主機環境變數或 `.env` 設定：

```dotenv
ECPAY_ENABLED=true
ECPAY_ENVIRONMENT=stage
ECPAY_MERCHANT_ID=3002607
ECPAY_HASH_KEY=pwFHCqoQZGmho4w6
ECPAY_HASH_IV=EkRm7iFT261dpevs
APP_PUBLIC_URL=https://你的公開測試網域
COOKIE_SECURE=true
```

上面是綠界官方公開測試商店資料，並非正式商店憑證。網站需對外可達，TLS 終結於反向代理；綠界回呼使用公開 443 埠。不能使用 localhost、內網網址或僅本人可登入的私有網站作為付款回呼入口。

測試卡號、有效年月及 3D 驗證請以官方頁面為準。在此開發環境已驗證官方簽章向量、金額核對、偽造/模擬通知與冪等回呼；**尚未經公開網域實際完成綠界測試卡端到端付款**。

## 正式環境

切換 `ECPAY_ENVIRONMENT=production` 並以正式 MerchantID / HashKey / HashIV 取代測試值。系統拒絕在 production 使用已知測試商店 ID。金鑰只在後端環境變數中，前端建置與 Git 均不包含正式金鑰。

正式營運前需完成商店申請、HTTPS、綠界驗收及帳務對帳流程。本版沒有退款 API、主動查單或排程對帳，對於付款通知遺失須由營運人員依綠界交易紀錄查核；未實作自動對帳前不應把這套初版視為無人值守的正式收款平台。

## 官方來源

- [全方位付款建立訂單](https://developers.ecpay.com.tw/2862/)
- [檢查碼機制（含本專案採用的測試向量）](https://developers.ecpay.com.tw/2902/)
- [測試商店、測試卡與模擬通知說明](https://developers.ecpay.com.tw/2856/)
