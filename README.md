# coffee_GPT6 · MORNING POUR

咖啡廳點餐與營運系統，繁體中文、TWD、Asia/Taipei。**Java 17 / Spring Boot 3.5.16 Maven 多模組 + Vue 3 / TypeScript / Pinia / ECharts**。採一起部署的模組化單體：Vue 編譯後放進 Spring Boot JAR，由同一個服務提供畫面與 API，搭配 PostgreSQL。

## 角色與功能

| 角色 | 畫面與權限 |
| --- | --- |
| 客人 | 菜單分類、搜尋、溫度甜度、內用/外帶、點餐單、櫃台/綠界付款、自己的訂單 |
| 收銀員 | 所屬分店櫃台 POS、現金實收與找零、門市訂單、製作與取餐進度 |
| 店長 | 收銀員功能 + 所屬分店業績報表 |
| 總部 | 分店、菜單、帳號、角色功能配置，及跨店績效報表 |

報表包含月營業額、訂單量、銷售件數、平均客單價、商品毛利與毛利率、每日趨勢、今日熱銷前五名、分類佔比、24 小時消費時段、付款方式佔比、各店月營收目標達成率與商品銷售明細，支援 CSV 匯出。

## 快速體驗（開發資料）

需要 Java 17、Node.js 22；Maven Wrapper 首次執行會下載 Maven 3.9.9。

Windows PowerShell：

```powershell
.\scripts\build.ps1
java -jar backend/coffee-app/target/coffee-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

macOS / Linux：

```bash
./scripts/build.sh
java -jar backend/coffee-app/target/coffee-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

開啟 **http://localhost:8080**。開發環境使用持久化 H2 檔案 `./data/coffee`（由啟動時工作目錄決定），預載示範分店、菜單和近期兩個月的成交資料，僅供開發。正式環境不載入示範交易。

| 角色 | 開發帳號 |
| --- | --- |
| 客人 | customer@coffee.local |
| 收銀員 | cashier@coffee.local |
| 店長（中山店） | manager@coffee.local |
| 店長（江子翠店，跨店驗證） | manager2@coffee.local |
| 總部 | hq@coffee.local |

開發預設密碼均為 `CoffeeDemo!2026`，首次建立資料時可用 `DEMO_PASSWORD` 覆寫。固定示範帳號只存在 dev profile；**不要將 dev profile 對外作為正式營運環境**。

需前端 HMR 時，在另一個終端執行 `cd frontend && npm run dev`；Vite 將 `/api` 代理至 8080。此時登入頁提供開發帳號填入按鈕，真正身分仍由後端帳號密碼驗證，沒有切換角色即獲權限的機制。

## PostgreSQL 一起部署

```bash
cp .env.example .env
# Windows PowerShell: Copy-Item .env.example .env
# 編輯 .env，填入 DB_PASSWORD、BOOTSTRAP_USERNAME、BOOTSTRAP_PASSWORD
# 本機 HTTP 可 COOKIE_SECURE=false；正式 HTTPS 必須 true。
docker compose up --build -d
```

開啟 http://localhost:8080，以 `.env` 的總部帳號登入。第一次啟動建立角色與總部管理員，**不建立假業績或預設門市**。依序新增分店 → 上架菜單 → 建立門市人員及客人帳號，即可點餐。

應用只綁定主機 `127.0.0.1:8080`；正式站應由 HTTPS 反向代理對外提供，設定正確的 APP_PUBLIC_URL 與 COOKIE_SECURE=true。資料庫不對外開埠，資料保存在 `coffee-db` volume。先安排資料庫備份，勿使用 `docker compose down -v` 清除營運資料。

所有業務模組編譯為同一 Spring Boot JAR；Compose 的兩個容器是應用程式及資料庫，並非依業務拆微服務。

## 綠界

預設關閉線上付款，客人可使用櫃台付款，POS 可現金收款。啟用後會產生綠界測試/正式收銀台 POST 表單，成功入帳只信任已驗證的 server callback。

設定方式及目前驗證範圍見 [綠界整合文件](docs/ECPAY.md)。正式商店憑證、公開 HTTPS 回呼及綠界實際交易驗收須在你的環境完成。

## 專案結構與驗證

- [模組邊界、資料範圍、狀態與報表定義](docs/ARCHITECTURE.md)
- [API 端點](docs/API.md)
- [照片來源](docs/ASSETS.md)
- [下載包執行方式與 Git bundle 匯入](docs/DELIVERY.md)
- [本次驗證結果與未驗證範圍](docs/VALIDATION.md)
- `backend/coffee-app/src/test`：授權、跨店隔離、後端重算金額、冪等訂單、收款與報表、狀態機、價格快照、綠界簽章/回呼、停用與密碼重設、角色變更、ArchUnit。
- GitHub Actions：前端型別檢查及建置，後端 Maven verify。

單独驗證：

```bash
cd frontend
npm ci
npm run build
cd ../backend
./mvnw verify
# Windows 使用 .\mvnw.cmd verify
```

前端目前採兩张分類示意圖片；可依實際品牌替換 `frontend/public/images`。本版未包含電子發票、退款、庫存、折扣、外送及硬體印單。詳見架構文件中的本版界線。
