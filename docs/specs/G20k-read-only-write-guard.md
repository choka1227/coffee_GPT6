# G20k — 唯讀路徑的寫入紅線（靜態規則 + 執行期偵測器）

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G20k（由 [`G20h-order-preview.md`](G20h-order-preview.md) §13.4／§14 登記，[`G20j-rate-limit.md`](G20j-rate-limit.md) §14 重列） |
| 優先順序 | P1（本輪由 P2 的登記項升排，理由見 §1.4） |
| 版本 | v1.0（2026-10-09） |
| 實作者 | Codex（`codex/g20k-read-only-guard`） |
| Flyway | **零 migration。`V15` 仍然空著**，下一份需要 migration 的規格自 `V15` 起算 |
| 新依賴 | **零**（ArchUnit 1.4.1 已在 `coffee-app` 的 test scope，其餘用 JDK 與 spring-jdbc 既有類別） |
| 生產程式碼 | **零變更**（兩個階段都只新增測試與測試輔助類別） |
| 前端 | **零變更** |

---

## 1. 背景與目標

### 1.1 `POST /api/orders/preview` 的「不寫入」目前只是一句話

G20h 建立了試算端點。它存在的前提是「試算不可以有副作用」—— 顧客在購物車加加減減，不該消耗優惠碼額度、不該佔庫存、不該留下痕跡。

現在守住這件事的有三道，三道都有洞：

| 現有防線 | 位置 | 洞 |
| --- | --- | --- |
| `@Transactional(readOnly = true)` | `OrderService.java:237` | **只是意圖宣告。** CI 跑的是 H2，它忽略這個標記；主線也沒有 `setEnforceReadOnly(true)`。G20h §13.4 自己寫明了「不是資料庫級的強制」 |
| `OrderPreviewTest` 驗收 7 的列數與 `redeemed_count` 斷言 | `OrderPreviewTest.java:263,268` | **只驗得了「當下列得出來的那幾個寫入」。** 它數 `orders`／`order_items` 的列數、查 `discounts.redeemed_count`。明天在試算路徑上新增第四種寫入，這組斷言一個都不會紅 |
| Code review | —— | 人工，且審查的人要自己記得「這條路徑是唯讀的」 |

G20j 的審查（PR #80）把第二道的邊界再確認了一次：驗收 7 擋得住今天，擋不住日後新增的那一行。

### 1.2 這不是假想風險，下一份規格就會踩到

試算路徑上的 `price()`（`OrderService.java:194`）會呼叫：

```
preview → price(redeem=false) → catalog.sellable / catalog.resolveOptions
                              → promotions.apply(branchId, lines, now)
                              → discounts.quote(code, branchId, afterItems, now)
```

兩個已經登記的後續缺口會直接改動其中兩個呼叫端：

- **G20 §13.8「促銷使用次數上限」** —— 做法幾乎一定是在 `PromotionService.apply`（`PromotionService.java:205`）裡加一行計數。那一行會讓**顧客每次在購物車按加減，就消耗一次促銷額度**。症狀跟 G20h §5.1 擋下的那個一模一樣（`DiscountService.apply` 的 `redeemed_count+1`），只是換成另一張表
- **G20b 多規則疊加與單位消耗模型** —— 名字裡就有「消耗」

G20h 當時是靠人讀出 `DiscountService.apply:134` 會寫入，才在 S1 把 `quote` 與 `apply` 分家。那次是運氣好，不是制度。

### 1.3 為什麼靜態分析單獨做不到

試算與建立訂單**共用同一組方法，用執行期旗標分流**：

| 方法 | 旗標 | 試算 | 建立訂單 |
| --- | --- | --- | --- |
| `OrderService.price(..., boolean redeem)`（`:194`） | `redeem` | `false` → `discounts.quote`（`:220`） | `true` → `discounts.apply`（`:219`） |
| `DiscountService.resolve(..., boolean lock)`（`:144`） | `lock` | `false` → `select * from discounts where code=?` | `true` → 同句 **+ `for update`** |

兩個分支在**同一個方法體**裡（`:218-220` 的三元運算、`:150` 的字串串接）。所以從 `preview` 出發的呼叫圖**必然**會走到 `discounts.apply` → `db.update`，任何方法層級的靜態規則在這裡只有兩種結局：誤報，或是加一條例外把那個邊忽略掉 —— 而那正是最該看住的邊。

**結論：試算路徑的紅線只有執行期守得住**（它走的是真的那一個分支）。靜態規則要用在它真正有效的地方：**整個模組都不該寫入**的場合。

### 1.4 為什麼這一輪排這一項

2026-10-09 這一輪的規格庫存是 0（G20j 的實作已隨 [PR #80](https://github.com/choka1227/coffee_GPT6/pull/80) 於 2026-10-09 合併）。逐項檢視所有未開工缺口後（完整對照表寫進 [`../GAP-ANALYSIS.md`](../GAP-ANALYSIS.md)「為什麼 2026-10-09 這一輪挑 G20k」），G20k 是唯一同時滿足下列條件的一項：

- 不需要 PO 決策（不碰 repo 設定、帳號憑證、不可逆操作、`main`）
- 不需要真實營業資料（排除 G17、G28）
- 不需要真實促銷方案（排除 G20b、G20d）
- 不需要先有身分模型（排除 G21、G16）
- **不需要新依賴**（本規格推翻了 G20h §13.4「要 Testcontainers」的前提，見 §13.1 —— 這是它本來被擋在 P2 的唯一理由）
- 沒有任何現存設計決策拒絕過它（排除 G20f：G20c §13.2 的拒絕理由至今沒有被推翻，G20h §14 也明寫沒有推翻）
- **有新鮮證據**：PR #80 的審查重新確認了驗收 7 的邊界；而 §1.2 的兩個後續缺口會正面踩到它

### 1.5 目標

1. 讓「報表模組只讀不寫」從 `AGENTS.md` 的散文變成**建置期會紅的檢查**
2. 讓「試算不寫入」從「列舉今天的寫入」變成**執行期攔截任何寫入語句**，包含日後新增的
3. 兩者都要有**反向對照**：證明紅線在真的有寫入時會紅。沒有反向對照的紅線跟沒有紅線一樣（G23 §13.2 的教訓：mock 掉被測對象就等於沒測）
4. 生產程式碼零變更。本規格不改任何執行時行為

---

## 2. 範圍

### 2.1 在範圍內

- `ReadOnlyPathsTest`（新檔，S1）：ArchUnit 規則，守 `com.coffee.reporting..`
- `WriteDetectingDataSource` + `PreviewWriteGuardTest`（新檔，S2）：執行期寫入偵測器與它的斷言
- 兩個階段各自的反向對照（刻意會寫入的 fixture）

### 2.2 不在範圍內（逐項有理由）

| 不做 | 理由 |
| --- | --- |
| **Testcontainers 或任何新依賴** | §13.1 |
| **`setEnforceReadOnly(true)`、`readOnlyMode` 等生產設定** | §13.2。那是部署決策，登記為 G20m |
| **從 `preview` 出發的靜態呼叫圖規則** | §1.3 結構上做不到。改由 S2 在執行期覆蓋 |
| **重構 `OrderService.price` 的 `redeem` 旗標（或 `DiscountService.resolve` 的 `lock` 旗標）** | §13.4。那會動到金額寫入路徑，風險與收益不成比例 |
| **把規則加進 `ModuleBoundariesTest`** | §13.5 |
| **任何生產程式碼變更（一行 Java 都不改）** | 驗收 14 會驗 |
| **任何 Flyway migration** | 驗收 15 會驗 |
| **前端變更** | 驗收 16 會驗 |
| **覆蓋率門檻** | G22 §13.7 已裁決不設，理由未變 |
| **改 `verify.yml` 或 `pom.xml`** | 不需要。`./mvnw verify` 已經跑所有測試；不新增依賴就不必動 `pom.xml`（驗收 14） |
| **H2 與 PostgreSQL 的行為差異** | 本規格的兩道紅線都不依賴資料庫方言：S1 是靜態分析，S2 攔的是**送出去的 SQL 字串**，攔截點在 JDBC 之上 |

---

## 3. 涉及模組與邊界

| 模組 | 變更 |
| --- | --- |
| `coffee-app`（test sources 只有這裡有） | **只新增測試與測試輔助類別**：`com.coffee.app.ReadOnlyPathsTest`、`com.coffee.app.PreviewWriteGuardTest`、`com.coffee.app.readonly.WriteDetectingDataSource`、`com.coffee.app.readonly.ReadOnlyWalker`、`com.coffee.app.readonly.fixture.*` |
| 其餘所有模組 | **零變更** |

模組邊界不受影響：測試一律放在 `coffee-app`（`AGENTS.md`「測試」一節），而 `coffee-app` 是組裝層，依賴全部模組是允許的。**不要**把測試放進 `coffee-reporting` 或 `coffee-orders` —— 那會讓業務模組的 test scope 長出對其他模組的依賴。

---

## 4. DB schema 與 migration

**零。** 不新增、不修改任何 Flyway migration 檔。`V15` 在本規格之後仍然空著。

S2 的偵測器在 H2 的記憶體資料庫上運作（與 `OrderPreviewTest` 同樣的 `spring.datasource.url=jdbc:h2:mem:...;MODE=PostgreSQL`），不需要任何 schema 變更。

---

## 5. 技術設計

### 5.1 S1：`ReadOnlyWalker` —— 可重用的呼叫圖走訪器

放在 `com.coffee.app.readonly.ReadOnlyWalker`（test source）。它是一個純函式類別，不依賴 Spring。

**輸入：** 一組 ArchUnit `JavaClasses`、一組進入點方法、一個「什麼叫寫入」的判斷。
**輸出：** 違規清單，每一筆包含「從哪個進入點出發」、「經過哪條呼叫鏈」、「在哪裡寫入」。

```java
public final class ReadOnlyWalker {
  // JdbcTemplate 上允許出現在唯讀路徑的方法名（允許清單，預設拒絕）
  static final Set<String> READ_METHODS =
      Set.of("query", "queryForList", "queryForObject", "queryForMap", "queryForStream",
             "queryForRowSet");

  public static List<String> violations(JavaClasses classes, Collection<JavaMethod> entryPoints);
}
```

**演算法（BFS，cycle-safe）：**

1. `queue` 放入所有進入點；`visited` 記已走過的方法（用 `JavaMethod.getFullName()` 當鍵）
2. 取出一個方法 `m`，對 `m.getMethodCallsFromSelf()` 的每一個呼叫目標 `t`：
   - `t` 的 owner 是 `org.springframework.jdbc.core.JdbcTemplate` 或 `NamedParameterJdbcTemplate`：
     - `t` 的方法名**不在** `READ_METHODS` → **記一筆違規**（附呼叫鏈）
     - 在清單裡 → 不往下走（Spring 的內部不是我們要走的範圍）
   - `t` 的 owner 是 `javax.sql.DataSource`、`java.sql.Connection`、`java.sql.Statement`、`java.sql.PreparedStatement`、`java.sql.CallableStatement` → **記一筆違規**（繞過 JdbcTemplate 的後門）
   - `t` 的 owner 在 `com.coffee..` 之內：
     - owner 是 interface → 找出 `classes` 裡所有 `implement(owner)` 的類別，把**同名同參數型別**的方法各自加入 `queue`（這是 §1.3 說的「解析介面呼叫」；找不到實作就跳過）
     - owner 是具體類別 → 把 `t` 加入 `queue`
   - 其餘 owner（JDK、Spring 其他部分、Jackson…）→ 不往下走
3. `queue` 空了就結束。回傳違規清單

**為什麼 `java.sql.ResultSet` 不在禁止清單裡：** `RowMapper` 的 lambda 參數就是 `ResultSet`（`ReportService.java:42` 等處），禁了它等於禁掉所有讀取。`ResultSet` 自己沒有寫入資料庫的方法會被用到（`updateRow` 等 updatable ResultSet API 本 repo 從未使用），不值得為它加例外。

### 5.2 S1：規則本體

`ReadOnlyPathsTest` 三條測試，全部用 `ImportOption.Predefined.DO_NOT_INCLUDE_TESTS` 匯入 `com.coffee`（**fixture 不可以影響生產規則**）：

**(a) `reportingModuleOnlyReadsThroughJdbcTemplate`**

```java
var classes = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages("com.coffee");
var entryPoints = classes.stream()
    .filter(c -> c.getPackageName().startsWith("com.coffee.reporting"))
    .flatMap(c -> c.getMethods().stream())
    .toList();
assertThat(ReadOnlyWalker.violations(classes, entryPoints)).isEmpty();
```

進入點取 `com.coffee.reporting..` 的**全部方法**（不只 public）。今天的 `ReportService` 只呼叫 `db.query`／`queryForList`／`queryForObject`（4 處 + 3 處 + 1 處），所以這條今天是綠的。

**(b) `reportingModuleDoesNotTouchRawJdbc`**

ArchUnit 原生語法即可，不必走訪器：

```java
noClasses().that().resideInAPackage("com.coffee.reporting..")
    .should().dependOnClassesThat()
    .haveNameMatching(
        "javax\\.sql\\.DataSource|java\\.sql\\.(Connection|Statement|PreparedStatement|CallableStatement)")
    .check(classes);
```

`haveNameMatching` 比對的是**完整類別名稱**（`JavaClass.getName()`）。若 ArchUnit 1.4.1 的 `ClassesThat` 在這個位置用的是別的名字，改用 `haveFullyQualifiedName(...)` 逐一 `or` 起來，或直接把這條也交給 §5.1 的走訪器（它本來就會把這五個型別記成違規）—— **三種寫法都可以，不要為了湊這個 API 去改規則的語意**。

**(c) `theWalkerFindsWritesThatAreThreeHopsAwayAndBehindAnInterface`** —— **反向對照**，見 §11.1。

### 5.3 S2：`WriteDetectingDataSource`

放在 `com.coffee.app.readonly.WriteDetectingDataSource`（test source）。它 `extends org.springframework.jdbc.datasource.DelegatingDataSource`（spring-jdbc 既有類別，不是新依賴），覆寫 `getConnection()`，用 `java.lang.reflect.Proxy` 包住回傳的 `Connection`：

```
Connection 代理攔截：
  prepareStatement(String, ...)  → 分類 SQL；回傳 Statement 代理
  prepareCall(String, ...)       → 分類 SQL；回傳 Statement 代理
  createStatement(...)           → 回傳 Statement 代理
  nativeSQL(String)              → 分類 SQL
  其餘方法 → 直接委派

Statement 代理攔截：
  execute / executeQuery / executeUpdate / executeLargeUpdate / addBatch
    有 String 參數的多載 → 分類該字串
  其餘方法 → 直接委派
```

**「分類」= 記錄**，不是丟例外。偵測器只負責記錄；斷言由測試做。理由：丟例外會讓被測程式走進它自己的錯誤處理，看到的症狀是 500 而不是「哪一句寫入」，而且 `@Transactional` 可能把它吞成 rollback-only。

**武裝（arming）：** 偵測器有一個 `static ThreadLocal<List<String>>`。

```java
public static void arm();                 // 清空並開始記錄（同一條執行緒）
public static List<String> disarm();      // 停止記錄並回傳這段期間的寫入語句
public static <T> Captured<T> around(Supplier<T> call);  // arm → 呼叫 → disarm，保證成對
```

沒有武裝時代理只委派、不記錄。**這是必要的**：Flyway migration、`InitialData` 的 seed、Hikari 的連線初始化都在 context 啟動時寫入／設定 session，若無條件記錄，每個測試一開始就有一堆雜訊。

**武裝只對當前執行緒有效。** 試算與建立訂單都在呼叫端的執行緒上同步執行（`OrderService` 沒有非同步），所以 `ThreadLocal` 夠用。若日後出現非同步寫入，這道紅線會看不到它 —— 記在 §14 的 G20m。

**SQL 分類（禁止清單，與 S1 的允許清單方向相反，理由見 §13.3）：**

```
1. 去掉前後空白；反覆去掉開頭的 /* ... */ 與 -- 到行尾的註解；去掉開頭的左括號
2. 取第一個語彙（以空白或括號切），轉小寫
3. 第一個語彙屬於 {insert, update, delete, merge, truncate, alter, create, drop, grant, revoke, replace}
     → 記為寫入
4. 或者：整句（轉小寫、把連續空白縮成一個）含有 " for update" 或 " for no key update" 或 " for share"
     → 記為寫入（取行鎖的 select 不是唯讀操作，理由見 §13.6）
5. 其餘（select / with / show / explain / values / set / commit / rollback / SELECT 1 之類的
   連線檢查）→ 不記錄
```

**為什麼第 4 步不會誤報試算：** `DiscountService.resolve`（`:144`）的 `for update` 是靠 `lock` 參數串接上去的（`:150`），試算走 `quote` → `lock=false` → **送出去的字串裡沒有 `for update`**。偵測器攔的是字串，所以它看到的是真相，不是程式碼長相。這正是 §1.3 要的那個差別。

### 5.4 S2：把偵測器裝上去

`PreviewWriteGuardTest` 用 `@SpringBootTest`，照 `OrderPreviewTest` 的樣子給每個測試類別一個獨立的 H2：

```java
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:preview-write-guard-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("dev")
@Import(PreviewWriteGuardTest.Wrapping.class)
class PreviewWriteGuardTest {
  @TestConfiguration
  static class Wrapping {
    @Bean
    static BeanPostProcessor wrapDataSource() {
      return new BeanPostProcessor() {
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
          return bean instanceof DataSource ds && !(ds instanceof WriteDetectingDataSource)
              ? new WriteDetectingDataSource(ds) : bean;
        }
      };
    }
  }
}
```

**`BeanPostProcessor` 必須是 `static @Bean` 方法**，否則 Spring 會警告「BeanPostProcessor 提早實例化了它所在的設定類別」，而且包裝可能發生在 `DataSource` 建好之後 —— 症狀是偵測器一句都沒攔到，測試假綠。驗收 10 的反向對照就是為了抓這個。

**不需要 `@DirtiesContext`。** 偵測器的狀態是 `ThreadLocal`，`around()` 保證成對解除；本測試類別不改菜單、不改優惠碼設定（建立訂單那條會寫入訂單列，但不影響其他測試類別，因為 H2 名稱帶 `${random.uuid}`，每個類別一份）。

**不要降低 `PREVIEW_LIMIT`、不要動 `RateLimiter`。** 本測試類別的試算次數遠低於 60 次／60 秒。

---

## 6. API

**沒有新端點，沒有任何既有端點的變化。** 本規格不改生產程式碼。

### 6.1 錯誤碼

不新增、不修改。測試失敗訊息是給工程師看的，不受「錯誤訊息一律繁體中文」約束（那條規範管的是終端使用者看得到的 `Problem` 訊息）。但**違規訊息要寫得能直接動手**：

```
唯讀路徑出現寫入：com.coffee.reporting.internal.ReportService.report(...)
  → com.coffee.reporting.internal.ReportService.rollUp(...)
  → org.springframework.jdbc.core.JdbcTemplate.update(...)
```

呼叫鏈是必要的。只說「reporting 模組有寫入」會讓人逐檔找。

---

## 7. 權限與資料範圍

**不新增端點，不新增權限，`Identity.PERMISSIONS` 一字不改。** 因此本規格**沒有越權測試**（`AGENTS.md`「測試」一節要求的越權測試是針對新端點；這裡沒有新端點）。

S2 的測試要用到既有帳號：`customer`（`ORDER_CREATE`、`SELF`）與 `cashier`（`POS_ORDER`、`BRANCH`）。照 `OrderPreviewTest` 的用法取 `identity.find("customer")`，**不要自訂權限組合**（G23 §13.9 的教訓）。

---

## 8. 金額規則

本規格不計算任何金額、不改計價路徑。

但它守的正是金額規則的一條：**「試算不得有副作用」**。S2 的反向對照（驗收 10）會證明建立訂單**確實**寫入 —— 那是刻意的：兩條路徑的差別必須看得見，否則無法分辨「偵測器沒抓到」與「真的沒寫入」。

---

## 9. 施工階段

兩個階段**互相獨立**，先後可換（建議照編號，S1 較小且不需要 Spring context）。任一階段單獨合併都有價值，都不會破壞任何既有行為（純新增測試）。

### S1 —— 靜態規則與走訪器（規模：小）

**動到的檔案（全部新增）：**

```
backend/coffee-app/src/test/java/com/coffee/app/ReadOnlyPathsTest.java
backend/coffee-app/src/test/java/com/coffee/app/readonly/ReadOnlyWalker.java
backend/coffee-app/src/test/java/com/coffee/app/readonly/fixture/DirectWriter.java
backend/coffee-app/src/test/java/com/coffee/app/readonly/fixture/IndirectWriter.java
backend/coffee-app/src/test/java/com/coffee/app/readonly/fixture/WriteBehindInterface.java
backend/coffee-app/src/test/java/com/coffee/app/readonly/fixture/CleanReader.java
```

**驗收子集：** 驗收 1–5、11、14–17
**預估：** 約 180 行，全部是測試與測試輔助。沒有 Spring context，跑得快。

### S2 —— 執行期寫入偵測器（規模：中）

**動到的檔案（全部新增）：**

```
backend/coffee-app/src/test/java/com/coffee/app/readonly/WriteDetectingDataSource.java
backend/coffee-app/src/test/java/com/coffee/app/PreviewWriteGuardTest.java
```

**驗收子集：** 驗收 6–10、12–17
**預估：** 約 240 行。`Connection`／`Statement` 的動態代理是本階段唯一有技巧的部分（§5.3）。

### 階段切分的理由

S1 是靜態分析、S2 是執行期攔截，兩者**沒有共用程式碼**（`ReadOnlyWalker` 只有 S1 用，`WriteDetectingDataSource` 只有 S2 用），也沒有共用檔案。所以這一刀切得乾淨：中斷在 S1 之後，主線上多一條真實有效的紅線；中斷在 S2 之後也一樣。

**真的跑不完時那一刀切在哪：** S2 的兩個檔案之間不要切 —— 只有 `WriteDetectingDataSource` 沒有任何測試用它，等於沒有紅線。若 S2 做到一半，就只推 S1 並維持 draft，`WriteDetectingDataSource` 留在工作區不要推（或推一個「編譯得過、沒有呼叫端」的中間狀態並在 PR 描述標明，比照 `AGENTS.md` 的做法）。

---

## 10. 驗收條件

逐條可勾選。

### S1

- [ ] **1.** `ReadOnlyPathsTest` 的三條測試全綠，且**不需要 Spring context**（整個類別沒有 `@SpringBootTest`）
- [ ] **2.** `reportingModuleOnlyReadsThroughJdbcTemplate` 的進入點是 `com.coffee.reporting..` 的**全部方法**（不是只有 public），走訪器回傳空清單
- [ ] **3.** `reportingModuleDoesNotTouchRawJdbc` 禁止 `com.coffee.reporting..` 依賴 `javax.sql.DataSource` 與 `java.sql.{Connection,Statement,PreparedStatement,CallableStatement}`；**`java.sql.ResultSet` 不在禁止清單裡**（否則 `RowMapper` 全掛）
- [ ] **4.** 匯入生產類別時用 `ImportOption.Predefined.DO_NOT_INCLUDE_TESTS`；fixture 不影響 (a)(b) 兩條規則（把 fixture 的 `db.update` 改掉也不會讓 (a)(b) 變色）
- [ ] **5.** 違規訊息包含**完整呼叫鏈**（進入點 → … → 寫入點），不是只有模組名稱
- [ ] **11.** 走訪器對 `JdbcTemplate` 用**允許清單**（`READ_METHODS` 之外的方法名一律違規）。測試要釘住這件事：把一個 fixture 改成呼叫 `db.execute(...)` 也會被抓到，不只 `db.update(...)`

### S2

- [ ] **6.** `orders.preview(...)` 期間偵測器記錄到的寫入語句為**空**：(a) 不帶優惠碼、(b) 帶一個有效優惠碼、(c) 命中品項促銷 —— 三個案例各一條測試
- [ ] **7.** `reports.report(...)` 期間記錄到的寫入語句為**空**（執行期也覆蓋報表，與 S1 的靜態規則互補）
- [ ] **8.** 偵測器未武裝時不記錄任何東西（context 啟動、Flyway、seed 都不會進清單）
- [ ] **9.** `for update` 的偵測有測試釘住：透過注入的 `JdbcTemplate` 跑一句 `select ... for update`，武裝期間**必須**被記錄
- [ ] **10.** **反向對照：** `orders.create(...)` 期間記錄到的寫入語句**非空**，且至少有一句以 `insert` 開頭並含 `orders`。這一條是用來證明偵測器真的裝上去了 —— 它紅了就代表包裝沒生效、整組 S2 的綠燈都不算數
- [ ] **12.** `PreviewWriteGuardTest` 不呼叫無參數的 `now()`（G27 的防線會擋，但不要寫出來讓它擋）
- [ ] **13.** 不修改 `RateLimiter` 的額度常數、不修改 `OrderPreviewTest`

### 全階段

- [ ] **14.** **生產程式碼零變更**：`git diff --numstat origin/feature/init-project..HEAD` 在 `src/main/` 底下零命中；`pom.xml` 零命中（不新增依賴）
- [ ] **15.** **零 migration**：`backend/coffee-app/src/main/resources/db/migration/` 零命中。`V15` 仍然空著
- [ ] **16.** **前端零變更**：`frontend/` 零命中
- [ ] **17.** CI 綠（`verify`）。`backend/mvnw`、`scripts/build.sh`、`start-demo.sh` 三個都是 `100755`

---

## 11. 測試要求

### 11.1 S1 的反向對照（`theWalkerFindsWritesThatAreThreeHopsAwayAndBehindAnInterface`）

**這一條是 S1 的核心，不是附加題。** 一個永遠回傳空清單的走訪器會讓 (a)(b) 兩條永遠綠。

Fixture（test source，`com.coffee.app.readonly.fixture`）：

| 類別 | 內容 | 期望 |
| --- | --- | --- |
| `DirectWriter` | 一個方法直接 `jdbc.update("update x set y=1")` | 違規，鏈長 1 |
| `IndirectWriter` | `entry()` → `middle()` → `tail()`，只有 `tail()` 寫入 | 違規，鏈長 3 |
| `WriteBehindInterface` | 宣告 interface `Sink { void write(); }` 與實作 `SinkImpl implements Sink`，`entry()` 只呼叫 `Sink.write()`（介面型別） | 違規 —— **這一條是在驗 §5.1 第 2 步的介面解析**。少了它，走訪器對整個 repo 的實際結構（跨模組一律只呼叫 `api` 的 interface）就是瞎的 |
| `CleanReader` | 只呼叫 `jdbc.query(...)` 與 `jdbc.queryForObject(...)` | **不**違規 |

測試用**另一個** `ClassFileImporter`（這次不排除測試類別）匯入 `com.coffee.app.readonly.fixture`，進入點取四個 fixture 的 `entry()`／對應方法，斷言：

```java
assertThat(violations).hasSize(3);                       // 不是 isNotEmpty()
assertThat(violations).anySatisfy(v -> assertThat(v).contains("DirectWriter"));
assertThat(violations).anySatisfy(v -> assertThat(v).contains("IndirectWriter").contains("tail"));
assertThat(violations).anySatisfy(v -> assertThat(v).contains("SinkImpl"));
assertThat(violations).noneSatisfy(v -> assertThat(v).contains("CleanReader"));
```

**`hasSize(3)` 而不是 `isNotEmpty()`**：後者會讓「走訪器把所有方法都當成違規」也變成綠的。

### 11.2 S2 的案例清單

| 測試 | 內容 |
| --- | --- |
| `previewWithoutDiscountCodeIssuesNoWrites` | 驗收 6(a) |
| `previewWithDiscountCodeIssuesNoWrites` | 驗收 6(b)。**這是最重要的一條** —— 它是 `DiscountService.apply` 的 `redeemed_count+1`（`:134`）唯一的執行期紅線 |
| `previewWithItemPromotionIssuesNoWrites` | 驗收 6(c)。守 §1.2 的 G20 §13.8 風險 |
| `monthlyReportIssuesNoWrites` | 驗收 7 |
| `nothingIsRecordedWhileTheDetectorIsNotArmed` | 驗收 8 |
| `selectForUpdateIsRecordedAsAWrite` | 驗收 9 |
| `creatingAnOrderIsRecordedSoTheDetectorIsProvenToWork` | 驗收 10（反向對照） |

### 11.3 不要做的事

- **不要斷言寫入語句的完整字串。** 斷言「以 `insert` 開頭且含 `orders`」就夠。釘死整句會在任何人調整 SQL 排版時無故變紅
- **不要用 `isNotEmpty()` 當 S1 反向對照的斷言**（§11.1）
- **不要在偵測器裡丟例外**（§5.3）
- **不要把偵測器掛進共用的測試設定**（例如改 `application-dev.yml` 或加一個全域 `@TestConfiguration`）。它只在 `PreviewWriteGuardTest` 生效。掛成全域會讓每一個 `@SpringBootTest` 都多包一層代理，CI 變慢且難除錯
- **不要為了讓紅線綠而放寬紅線。** 若 S2 在試算路徑上抓到真的寫入，那是**發現了缺陷**：寫進 PR 描述與 `docs/reports/`，照 `AGENTS.md`「規格書有錯或不完整時」的做法繼續做，不要把那句 SQL 加進例外清單
- **不要改既有測試。** 本規格是純新增

---

## 12. 與其他工作的並行注意

- **目前（2026-10-09）repo 沒有其他 open PR。** 本規格的 PR 是唯一一支
- **檔案交集：零。** 兩個階段動的全部是新檔案。不碰 `ModuleBoundariesTest`（§13.5）、不碰 `OrderPreviewTest`（驗收 13）
- **Flyway：零 migration。** 不可能撞號。`V15` 留給下一份需要 migration 的規格
- **與 §1.2 的後續缺口（G20 §13.8、G20b）的關係是互補不是衝突**：那兩份一旦開工，S2 的驗收 6(b)(c) 就是它們的紅線。若實作那兩份時這兩條紅了，**先看是不是真的把寫入加進了試算路徑**，不要調整紅線

---

## 13. 設計決策

每一項都附理由與推翻它的代價。

### 13.1 不用 Testcontainers —— **改用「靜態規則 + 執行期攔截」**

**決定：** 不引入 Testcontainers 或任何新依賴。G20h §13.4 把這個缺口登記成「要新依賴（Testcontainers 或等價物）」，本規格推翻那個前提。

**理由（四點）：**

1. **本 repo 的實作端跑不動它。** G20j 的施工報告寫得很清楚：Codex 的環境連 Maven Central 的 DNS 都解析不了，整份 backend 驗證是靠遠端 CI 補的。一個需要拉 Docker image 才能跑的測試，實作端**連一次都無法在本機執行**，只能靠推上去看 CI —— 除錯迴圈變成每次好幾分鐘，而且看不到完整輸出
2. **它會把網路依賴放進必經的 `verify` 檢查。** `verify` 是分支保護的必要檢查。拉 image 失敗（registry 限流、網路抖動）就是主線上**每一支 PR 都紅**，而紅的原因跟 PR 的內容無關。這個成本由所有後續工作分攤
3. **`setEnforceReadOnly(true)` 回答的問題比我們問的小。** 它只證明「資料庫拒絕了這個寫入」，不會說是哪一行發出的。S1 給呼叫鏈、S2 給 SQL 語句，兩者都直接指向要改的那一行
4. **攔截點選在 JDBC 之上，方言差異就不重要了。** S2 看的是送出去的 SQL 字串。H2 與 PostgreSQL 在「`insert` 是不是寫入」這件事上沒有分歧

**推翻的代價：** 本規格擋不住「SQL 看起來是讀取、實際上有副作用」的情況 —— 例如呼叫一個會寫入的 stored procedure、或 `select nextval(...)`。本 repo 兩者都沒有（沒有任何 procedure，主鍵用 `Ids.next()` 在應用層產生）。真的需要資料庫級強制時，那是**部署決策**（生產環境給報表一個只有 `SELECT` 權限的 DB 角色），登記為 G20m —— 那個做法比 Testcontainers 更接近「真的強制」，而且不需要在 CI 裡跑 Docker。

### 13.2 不加 `setEnforceReadOnly(true)` 等生產設定 —— **生產程式碼零變更**

**決定：** 兩個階段都不改一行生產程式碼。

**理由：** `setEnforceReadOnly(true)` 會讓 `@Transactional(readOnly = true)` 的交易在 PostgreSQL 上發 `SET TRANSACTION READ ONLY`。那是**執行時行為變更**，影響所有標了 `readOnly` 的交易，而本 repo 沒有可部署的環境可以驗（G07 §11.11 登記過）。純測試的變更沒有這個風險：它只會在 CI 上紅，不會在營業時紅。

**推翻的代價：** 唯讀意圖在生產上仍然只是意圖。做 G20m 時一併處理。

### 13.3 S1 用允許清單、S2 用禁止清單 —— **兩邊方向相反是刻意的**

**決定：** S1 對 `JdbcTemplate` 的方法名用允許清單（不在清單裡就是違規）；S2 對 SQL 的第一個語彙用禁止清單（在清單裡才是寫入）。

**理由：** 兩邊面對的集合性質不同。`JdbcTemplate` 的方法是**封閉可列舉**的 API，允許清單能自動擋住日後新增的寫入方法（也擋住 `execute`，那是最容易被忽略的一個）。S2 看到的 SQL 則包含**連線池與驅動的雜訊**（`SELECT 1` 之類的連線檢查、H2 的 session 設定），允許清單在那裡只會製造誤報，而誤報會讓人去放寬紅線 —— 比沒有紅線更糟。

**推翻的代價：** S2 的禁止清單漏了某個寫入關鍵字就會漏抓。清單寫在 §5.3，新增 DDL／DML 動詞時要同步。驗收 9、10 是它的紅線。

### 13.4 不重構 `OrderService.price` 的 `redeem` 旗標 —— **不動金額路徑**

**決定：** 不把 `price(..., boolean redeem)` 拆成「呼叫端先解析優惠碼、再把結果傳進純計價方法」，也不動 `DiscountService.resolve(..., boolean lock)`。

**理由：** 那個重構會讓 S1 的靜態呼叫圖也能覆蓋試算路徑（§1.3 的根因就是這兩個旗標）。但它**動到的是金額寫入路徑**，而本規格原本的收益是「加一道測試紅線」。用改金額路徑的風險去換靜態分析的優雅，比例不對 —— 而且 S2 已經在執行期覆蓋了同一件事，收益是重複的。

**推翻的代價：** 試算路徑的紅線只存在於執行期，依賴 S2 的測試案例涵蓋到真實分支。若日後試算路徑長出一個 S2 沒有涵蓋的分支（例如「只在某種促銷型態下才走的那一段」），那一段就沒有紅線。緩解方式是 §11.2 的案例表要隨試算路徑成長 —— 這比重構金額路徑便宜。

### 13.5 不把規則加進 `ModuleBoundariesTest` —— **新開一個測試類別**

**決定：** 新增 `ReadOnlyPathsTest`，`ModuleBoundariesTest` 一字不改。

**理由：** `ModuleBoundariesTest` 現在只有一個測試方法，承載「無循環依賴 + 不跨模組引用 internal」兩條架構規則。它紅的時候，訊息的意思很明確。把「唯讀路徑」塞進去會讓同一個方法有三種紅法，而且 `AGENTS.md` 明確點名 `ModuleBoundariesTest` 驗的是哪兩條 —— 動它就要連規範一起改。

**推翻的代價：** 架構類測試變成兩個檔案。那不是代價，那是分類。

### 13.6 `select ... for update` 視為寫入 —— **取鎖不是唯讀**

**決定：** S2 把含 `for update`／`for no key update`／`for share` 的 select 記為寫入。

**理由：** 行鎖會阻擋別人。一個「唯讀」的試算端點在熱門商品的優惠碼上取行鎖，顧客在購物車按加減就能讓結帳排隊 —— 那是沒有寫入任何資料的阻斷。`AGENTS.md` 把 `select ... for update` 列在「訂單狀態更新與收款」那條規則裡，正是因為它是收款路徑的工具，不是查詢的工具。

**推翻的代價：** 若日後真的需要在唯讀路徑取共享鎖（想不出場景），要改 §5.3 第 4 步並寫明理由。

### 13.7 偵測器只記錄、不丟例外 —— **斷言留給測試**

**決定：** 見 §5.3。

**理由：** 丟例外會被被測程式的錯誤處理或 `@Transactional` 的 rollback 吃掉，最後看到的是 500 或 `UnexpectedRollbackException`，而不是「哪一句 SQL」。記錄 + 事後斷言拿到的是清單，訊息直接可用。

**推翻的代價：** 寫入**已經發生**才被發現（在記憶體資料庫上，所以無害）。若日後要把偵測器用在非測試情境（想不出場景），再改成攔截式。

### 13.8 不設覆蓋率門檻、不改 `verify.yml` —— **沿用既有裁決**

**決定：** G22 §13.7 已裁決不設覆蓋率門檻，理由未變。`verify.yml` 不動（`./mvnw verify` 已經會跑新測試）。

**推翻的代價：** 無新增代價。

---

## 14. 登記給後續的缺口

| 編號 | 內容 | 來源 |
| --- | --- | --- |
| **G20m** | **生產環境的資料庫級唯讀強制**。三件事綁在一起：(1) 給報表／試算路徑一個只有 `SELECT` 權限的 PostgreSQL 角色（這才是真的「資料庫強制」，而且不需要在 CI 裡跑 Docker）；(2) `setEnforceReadOnly(true)` 與 pgjdbc 的 `readOnlyMode`（§13.2）；(3) 非同步寫入的偵測（本規格的 `ThreadLocal` 武裝看不到別的執行緒，§5.3）。**前兩項是部署決策，要有可部署環境才驗得了**（G07 §11.11 登記過沒有這種環境） | 本規格 §13.1、§13.2、§5.3 |
| G20l | 跨實例限流與限流的觀測性（要新依賴與基礎設施變更，與 G05 同一個前提） | G20j §13.2、§13.4 |
| G20b | 多規則疊加與單位消耗模型（**開工前提仍是要有真實促銷方案**）。開工時 S2 的驗收 6(c) 是它的紅線 | G20 §13.2 |
| G20d | 選項層促銷（加料免費、第二份加料半價） | G20 §2.2 |
| G20f | 訂單層優惠碼折抵分攤到品項（分攤演算法在 G20c 附錄 A）。**本規格沒有推翻 G20c §13.2 的拒絕理由** | G20c §13.2 |
| G21 | 會員價與員工價 | G07 §11.2 |

**這些都不計入規格庫存**，登記的目的是讓下一輪不用重新推導。

編號說明：`G20l` 由 G20j §14 占用，`G20m` 由本規格占用（上表第一列），所以 **`G20n`** 是 `G20` 系列下一個未使用號。主序列的下一個未使用號仍是 `G29`。

---

## 15. 給 Codex 的施工提醒

1. **`BeanPostProcessor` 要宣告成 `static @Bean` 方法**（§5.4）。不是 static 的話包裝可能發生在 `DataSource` 建好之後，症狀是偵測器一句都沒攔到、`previewIssuesNoWrites` 假綠 —— 驗收 10 的反向對照就是為了抓它。**先寫驗收 10，看它紅，再寫其餘的 S2 測試**（跟 G20j S3「測試先行」同樣的順序）
2. **`java.sql.ResultSet` 不可以列進 S1 的禁止清單**（驗收 3）。`RowMapper` 的 lambda 參數就是它
3. **S1 的反向對照要用 `hasSize(3)`，不要用 `isNotEmpty()`**（§11.1）
4. **走訪器要能解析介面呼叫**（§5.1 第 2 步）。本 repo 跨模組一律只呼叫 `api` 的 interface，少了這一步，走訪器對真實結構是瞎的 —— `WriteBehindInterface` fixture 就是驗這件事
5. **走訪器要有 `visited` 集合。** `com.coffee` 裡有互相呼叫的方法，沒有它會無限迴圈（症狀是測試掛住不結束）
6. **匯入生產類別時一定要 `DO_NOT_INCLUDE_TESTS`**（驗收 4）。漏了，fixture 的 `db.update` 會讓 (a) 直接紅，而紅的原因跟生產程式碼無關
7. **偵測器不要丟例外**（§13.7）
8. **不要把偵測器掛成全域**（§11.3）
9. **不要為了讓紅線綠而放寬紅線。** 若 S2 真的在試算路徑抓到寫入，那是發現缺陷：寫進 PR 描述與 `docs/reports/`，繼續做（§11.3 最後一點）
10. **不要改任何生產程式碼、不要動 `pom.xml`、不要加 migration、不要碰 `frontend/`**（驗收 14–16）。下一份需要 migration 的規格自 `V15` 起算
11. **不要改 `OrderPreviewTest`、`ModuleBoundariesTest`、`RateLimiter` 的常數**（驗收 13、§13.5）
12. **不要呼叫無參數的 `now()`**（驗收 12）。`TimeZoneGuardTest` 會擋，但不要寫出來讓它擋
13. **每階段做完就推。** S1 單獨合併就有價值，不要整份做完才推（`AGENTS.md`「施工階段與中斷續作」）
14. **推之前確認執行位元**：`git ls-files -s backend/mvnw scripts/build.sh start-demo.sh` 三個都要 `100755`
15. **規格有錯或做不到就講出來**（設計摘要、PR 描述、`docs/reports/`），寫明你採用了哪個做法與為什麼，**然後繼續做**。不要停下來等回覆

---

## 16. 版本紀錄

| 日期 | 版本 | 變更 |
| --- | --- | --- |
| 2026-10-09 | v1.0 | 初版。依 G20h §13.4／§14 與 G20j §14 的 G20k 登記產出。**與登記時的設想最大的差異是不引入 Testcontainers**（§13.1），改成「靜態規則守整個模組 + 執行期攔截守分支路徑」，因此生產程式碼零變更、零新依賴。§1.3 說明為什麼試算路徑**結構上**無法用靜態規則覆蓋（`price` 的 `redeem` 旗標與 `resolve` 的 `lock` 旗標都是執行期分流），這是本規格切成兩個互補階段的根本理由 |
