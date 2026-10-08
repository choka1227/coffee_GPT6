# G20j 共用限流設施與試算端點限流施工報告

## 來源

- 規格：`docs/specs/G20j-rate-limit.md` v1.1
- 主線來源：`feature/init-project` @ `d1eabae360a3c8c69a2ab1abc8dade4062d8e87f`
- 工作順序：`docs/GAP-ANALYSIS.md` 第 25 項
- 交接：Issue #79

## 施工進度

- [x] S1 — `RateLimiter` 與純單元測試
- [x] S2 — 試算端點限流
- [ ] S3 — 登入限流收斂與測試

## S1 驗收證據

- 固定視窗使用 `ConcurrentHashMap.compute`，同鍵的檢查與計數是同一個原子操作。
- 時間全部由呼叫端傳入；視窗邊界採 `until <= now`。
- 被 429 擋下的請求不會更新視窗；`clear` 只移除指定鍵。
- 只在鍵數超過 `maxKeys` 時掃描過期視窗，清理後仍超限則回固定的系統忙碌訊息。
- 純單元測試涵蓋額度、邊界、被擋不計數、多鍵與 `clear`、總鍵數保險及同鍵併發。

## S2 驗收證據

- 試算限流鍵為帳號 ID，額度為每 60 秒 60 次，最大追蹤鍵數 20,000。
- 限流在權限與格式驗證之後、`price()` 之前執行；403 與 400 請求不消耗額度。
- 新增測試覆蓋第 61 次回 429、帳號隔離、跨店與格式錯誤不計數，以及建立訂單不受試算額度影響。

## 驗證

- `cd frontend && npm test -- --run`：通過，12 個測試檔、136 個案例。
- `cd frontend && npm run build`：通過；只有既有的 chunk-size 警告。
- `cd backend && ./mvnw -B -ntp -pl coffee-app -am -Dtest=RateLimiterTest -Dsurefire.failIfNoSpecifiedTests=false test`：Maven Central DNS 解析失敗，未能在本地啟動 Maven；此為外部環境問題，推送後由遠端 CI 驗證。
- 遠端 CI：等待 S1 head 推送。

## 下一步

S3：先用既有登入限流實作補上真實 HTTP 測試，再改用共用 `RateLimiter` 並確認行為不變。
