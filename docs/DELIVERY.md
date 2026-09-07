# 下載包使用方式

本次交付的 ZIP 包含完整原始碼、已編譯的 `runtime/coffee.jar`，以及保存本次提交的 `feature-init-project.bundle`。

## 直接體驗

安裝 Java 17 後解壓整包，Windows 執行 `start-demo.cmd`；macOS / Linux 執行 `bash start-demo.sh`。不需另外安裝 Node.js、Maven 或資料庫，即可用已編譯的版本體驗。

開啟 http://localhost:8080。帳號及開發密碼見 README。此模式只綁定本機，包含明確的示範交易資料；資料會保存在解壓資料夾的 `data` 子目錄。按 Ctrl+C 停止服務。

## 匯入你的 GitHub 專案

本次 GitHub 整合回傳 `403 Resource not accessible by integration`，因此遠端 `feature/init-project` **沒有收到本次程式碼**。ZIP 的 Git bundle 保留了完整提交，可由你現有的 Git 登入身分推送。

在本機 `coffee_GPT6` repository 中，先確認 `git status` 沒有尚未處理的修改，再執行：

```bash
git switch feature/init-project
git fetch "解壓資料夾/feature-init-project.bundle" feature/init-project
git merge --ff-only FETCH_HEAD
git push origin feature/init-project
```

將範例路徑換成實際位置。`--ff-only` 若拒絕，代表本機或遠端已有其他提交，請先比較差異，不要強制覆蓋。若尚未有本機 repository，亦可從 bundle clone，再加入你的 GitHub remote。

## 正式部署

原始碼提供 Dockerfile、PostgreSQL Compose、環境變數範本與 Maven Wrapper。正式部署流程見 README；綠界憑證與公開 HTTPS 回呼設定見 ECPAY.md。下載包中的 JAR 沒有嵌入正式商店金鑰。
