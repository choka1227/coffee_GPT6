# API 快速索引

所有 `/api`（除 CSRF、登入及綠界回呼）都需要登入。呼叫端先 GET `/api/auth/csrf` 取得 `{token,headerName}`，保留 Cookie，對所有寫入請求帶入該 header。登入與登出會更新 CSRF，完成後須重新 GET `/api/auth/csrf`。Session 使用 HttpOnly Cookie，請勿將密碼或金流憑證放入瀏覽器儲存空間。

| Method | Path | 用途 / 授權 |
| --- | --- | --- |
| GET | /api/auth/csrf | 取得 CSRF token |
| POST | /api/auth/login | username, password；限制登入嘗試頻率 |
| GET | /api/auth/me | 目前帳號、角色、功能權限與資料範圍 |
| POST | /api/auth/logout | 登出 |
| POST | /api/auth/password | oldPassword, newPassword |
| GET | /api/branches | 營業中分店清單；manage=true 需 BRANCH_MANAGE |
| POST | /api/branches | 新增 / 更新分店；BRANCH_MANAGE + GLOBAL |
| GET | /api/menu | 上架菜單；manage=true 需 MENU_MANAGE，可取得成本及已下架商品 |
| POST | /api/menu | 新增 / 更新商品；MENU_MANAGE + GLOBAL |
| GET | /api/orders | 本人 / 所屬分店 / 所有分店，依身分授權；最近 100 筆 |
| POST | /api/orders | 建立訂單；Idempotency-Key header 必填 |
| GET | /api/orders/{id} | 訂單明細，檢查擁有者或分店權限 |
| POST | /api/orders/{id}/cash | tendered；POS_ORDER / ORDER_MANAGE + 分店範圍 |
| PATCH | /api/orders/{id}/status | status；狀態機及分店權限 |
| GET | /api/payments/config | 線上付款是否開放及 stage/production，不回傳金鑰 |
| POST | /api/payments/ecpay/{id} | 產生付款 action 與 fields，需可存取該訂單 |
| POST | /api/payments/ecpay/callback | 綠界通知，form-urlencoded、驗證簽章，純文字 1\|OK |
| GET | /api/reports?month=YYYY-MM&branchId=... | REPORT_STORE / REPORT_ALL，分店參數不能繞過範圍 |
| GET / POST | /api/admin/accounts | ACCOUNT_MANAGE + GLOBAL |
| GET / POST | /api/admin/roles | 查詢需 ACCOUNT_MANAGE 或 ROLE_MANAGE；寫入 ROLE_MANAGE + GLOBAL |
| GET | /actuator/health | 僅服務健康狀態 |

新增資料使用 `id: null`；修改帶原有 id。角色新增以新的 code、修改以既有 code。驗證錯誤回傳 `{message}`，HTTP 400/401/403/404/409/429/503 等狀態由錯誤類型決定。

建立訂單範例：

```json
{
  "branchId": "taipei",
  "fulfillment": "TAKEAWAY",
  "paymentMethod": "CASH",
  "note": "請稍後一起出餐",
  "items": [
    {"productId": "latte", "quantity": 2, "temperature": "熱", "sugar": "無糖"}
  ]
}
```

非飲料使用 temperature / sugar = `不適用`。價格、成本、總金額均由後端決定。服務契約與完整欄位定義位於各後端模組的 `api` package 及前端 `shared/types.ts`。
