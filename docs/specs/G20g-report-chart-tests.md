# G20g — 報表圖表層的測試

| 項目 | 內容 |
| --- | --- |
| 缺口編號 | G20g |
| 版本 | v1.1（2026-10-05） |
| 登記來源 | [`G20c-report-net-revenue.md`](G20c-report-net-revenue.md) §13.6、§14 |
| 分支 | `codex/g20g-report-chart-tests` |
| Flyway | **零 migration**（`V15` 仍然空著，本規格一個 SQL 檔都不加） |
| 後端 | **零變更**（一行 Java 都不改） |
| 前端生產程式碼 | 只有一處一行的顯示條件修正（§5.5），其餘全是測試與測試輔助 |
| 開工前提 | **已滿足**，見 §12 |

---

## 1. 背景與目標

### 1.1 現況

報表頁 `frontend/src/modules/reporting/ReportsView.vue` 有三張 ECharts 圖，option 全部由 `computed` 產生：

| 圖 | `computed` | 資料來源 | `<Chart>` 位置 |
| --- | --- | --- | --- |
| 本月每日營業額折線圖 | `trend`（`:67`） | `report.daily[].day` / `.revenue` | `:291` |
| 餐點分類商品淨營收圓環圖 | `categoryChart`（`:100`） | `report.categoriesNet` | `:365` |
| 24 小時成交訂單分布長條圖 | `hourly`（`:124`） | `report.hourly[].hour` / `.orders` | `:379` |

`frontend/src/shared/Chart.vue` 是全專案唯一的圖表封裝，被這三處使用（`grep -rn '<Chart' frontend/src` 只有這三個命中）。

G22／G23 已經把前端測試基礎設施建起來了：`vitest.config.ts` 有 `node` 與 `dom` 兩個 project，`npm test` 進了 CI 的 `verify`，`src/shared/testing/harness.ts` 提供 `stubApi`／`mountView`。目前有 6 支 `*.dom.test.ts`。

**G20c（PR #69）是第一次有測試碰到圖表**，而它碰的方式暴露了這個缺口：

1. **run #497 紅燈** —— DOM 測試直接掛載真的 `Chart.vue`，`echarts/core` 的 `init()` 在 jsdom 裡要拿 canvas 2d context，jsdom 沒有，測試整支炸掉
2. 修法是在測試檔頂端 `vi.mock("../../shared/Chart.vue", ...)` 換成一個只收 props 的 stub（`ReportsView.dom.test.ts:15-21`）。這個決定是對的（G20c §13.6：不為了好測去動 `Chart.vue` 的封裝），**但它把 `Chart.vue` 整個排除在測試之外**
3. 於是只有一張圖的 option 被斷言過一次（`ReportsView.dom.test.ts:112` 斷言 `categoryChart` 的 `series[0].data`），另外兩張圖、以及 `Chart.vue` 自己的行為，一條測試都沒有

### 1.2 要擋的缺陷（具體的，都看得到）

**缺陷 1 —— 兩張圖的 option 沒有任何防線，而它們印的是財務數字。**

`trend` 的 `series[0].data` 是 `report.daily.map(d => d.revenue)`，`hourly` 的是 `report.hourly.map(h => h.orders)`。把 `.revenue` 寫成 `.orders`、把兩個 `map` 的來源互換、或是哪天後端欄位改名而前端跟著漂移，`vue-tsc` 擋不住（`daily[]` 的 `revenue` 與 `orders` 都是 `number`，型別相同），`npm test` 全綠，CI 全綠。使用者看到的是一張**數字錯誤但畫得很漂亮**的圖。G22 升排時寫的那句「任何純行為缺陷在 CI 上都是綠的」，在圖表這一層目前仍然成立。

**缺陷 2 —— `Chart.vue` 的行為零覆蓋。**

`vi.mock` 把它換掉之後，下列每一條都沒有測試在看：

- `onMounted` 把 `props.option` 傳進 `setOption`，並補上 `aria: { enabled: true, description: props.description || props.label }`
- `watch(() => props.option, v => chart?.setOption(v, true), { deep: true })` —— **第二個引數 `true` 是 `notMerge`**。拿掉它，切換月份時舊的 series 會與新的合併，圖上會留著上個月的資料點
- `onBeforeUnmount` 的 `observer?.disconnect()` 與 `chart?.dispose()` —— 漏掉就是切頁面累積 ECharts 實例的記憶體洩漏
- `role="img"` 與 `:aria-label="description || label"`

這一條不是假想。`setOption` 的 `notMerge` 旗標與 `dispose` 都是「寫錯了畫面在單次操作下看起來正常」的那類缺陷，只有切換資料或反覆進出頁面才會顯現，正是測試比人眼可靠的地方。

**缺陷 3 —— 分類圖的顯示條件用錯了口徑（真的 bug，本規格修掉）。**

`ReportsView.vue:366`：

```vue
<Chart v-if="report.revenue" :option="categoryChart" label="餐點分類商品淨營收圓環圖" />
<div v-else class="empty-state compact">尚無銷售資料</div>
```

圖的資料源在 G20c 已經改成 `categoriesNet`（`:118`），**顯示條件卻還看 `revenue`**。`revenue = 0` 而 `netProductRevenue > 0` 這個組合在「優惠碼折抵把整單折到 0」時會出現 —— G20c 自己的後端測試 `D-1` 就是 `total=0` 而品項淨額 100。那時候明明有分類淨營收資料，圖會被整個藏起來，改顯示「尚無銷售資料」。

這一項 Claude 在 PR #69 的 review 裡登記為「不擋，登記備查」第 3 項，當時刻意沒有要求 Codex 推 commit（PR 已綠且 auto-merge 已啟用，為一行顯示條件換掉 head SHA 不划算）。**本規格把它連同能擋住它的測試一起收掉**，這也是為什麼它值得進規格而不是留在備查清單裡：缺陷 3 是缺陷 1 的實例，光修一行不寫測試，下一次還會發生。

**缺陷 4 —— `vi.mock` 的 stub 是複製貼上的，沒有單一來源。**

`ReportsView.dom.test.ts:15-21` 那個 7 行的 stub 區塊，下一支要測圖的測試檔只能照抄。抄第二份的時候兩份就會開始漂移（props 清單少一個、template 的 class 名不同），而 `findAllComponents(Chart)` 依賴 stub 的 `name` 與 props 形狀。這是 G22 §13 把 `stubApi`／`mountView` 收進 `harness.ts` 的同一個理由，圖表 stub 漏掉了。

### 1.3 目標

1. 三張圖的 option 都有測試釘住**資料來源與形狀**，不是釘樣式
2. `Chart.vue` 自己有測試，覆蓋 `setOption` 的引數、option 變更時的 `notMerge`、卸載時的 `dispose`／`disconnect`、以及 aria
3. 圖表 stub 收進 `harness.ts`，只有一份
4. 修掉缺陷 3，並有測試擋住它回歸
5. **不碰後端、不碰 `Chart.vue` 的封裝介面（props 不增不減）、不引入新依賴**

### 1.4 為什麼現在排這一項

工作順序（`docs/GAP-ANALYSIS.md`）第 21 項 G20c 已於 2026-10-05 隨 PR #69 合併，第 22 項 G20a 已有規格書（v1.0，待實作），第 23 項是金流 —— **PO 已整批延後**。所以工作順序到這裡用完了，必須從 P2 升排一項。逐項排除：

| 項目 | 為什麼不是它 |
| --- | --- |
| **G20f** 優惠碼折抵分攤到品項 | **刻意不選，理由見 §13.1。** G20c §13.2 拒絕它的三個理由到今天一條都沒有鬆動，沒有新資訊就推翻自己昨天的決策，是為了有產出而找事做 |
| G20b 多規則疊加 | 明文登記「開工前提是要有真實促銷方案」，否則疊加優先序一定是猜的 |
| G20d 選項層促銷 | 同上，且依賴 G20b 的疊加模型 |
| G20h 購物車試算端點 | 它是 G20a 提示功能的「真實金額」升級版。**G20a 自己還沒實作**，先寫 G20h 等於跑在實作端前面兩步 |
| G16 顧客自助註冊 | 涉及產品與法遵決策（濫用防護、驗證信、個資），**不在 PO 授權給 Claude 的範圍** |
| G05 Session 集中化 | 要引入 Redis，違反 `AGENTS.md`「不引入新依賴」的預設，需要 PO 決定 |
| G17 / G21 / G28 | 全部明文登記「要先有真實資料」 |
| G18 區域定價 | 同時動到金額重算、成本快照與報表毛利，規模大且沒有任何營運證據說三家分店需要不同售價 |
| G12 外送與硬體印單 | 依賴外部選型 |

**G20g 是唯一「沒有外部依賴、不需要產品決策、不需要真實資料、不引入新依賴、且有新證據」的一項。** 新證據就是 PR #69 的 run #497：這不是推測出來的缺口，是上一輪實作真的撞上去、而且用「把被測對象 mock 掉」繞過的那道牆。

**誠實交代：** 升排理由裡沒有營運端回饋（本 repo 至今沒有真實營業資料，G07 §11.11 已登記）。它靠的是「其餘都被擋住，而這一項剛好有新鮮證據」。規模也小 —— 這是刻意的，見 §9。

---

## 2. 範圍

### 2.1 在範圍內

- `frontend/src/shared/testing/harness.ts` 新增圖表 stub 的單一來源
- `frontend/src/modules/reporting/ReportsView.dom.test.ts` 補 `trend` 與 `hourly` 的 option 斷言，以及缺陷 3 的回歸測試
- 新增 `frontend/src/shared/Chart.dom.test.ts`，測 `Chart.vue` 自己
- `frontend/src/modules/reporting/ReportsView.vue:366` 一行顯示條件修正（缺陷 3）

### 2.2 不在範圍內（逐項有理由）

| 不做 | 理由 |
| --- | --- |
| **改 `Chart.vue` 的 props 或對外介面** | G20c §13.6 的決策維持：封裝不為了好測而變形。本規格用 `vi.mock("echarts/core")` 從依賴那一側切進去，不需要動 `Chart.vue`（§5.4） |
| **在 jsdom 裡跑真的 ECharts** | run #497 已經證明會炸。裝 `canvas` 套件能繞過，但那是引入一個原生編譯依賴（`node-canvas` 要 Cairo）換「圖真的畫出來了」這個我們根本不斷言的東西。§13.2 |
| **視覺回歸測試（螢幕截圖比對）** | 要 Playwright／headless browser，是新依賴與新 CI 階段，且截圖比對在無頭環境的字型差異下很脆。另立 **G20i** 登記（§14） |
| **斷言圖表樣式**（顏色、圓角、`radius`、`grid` 邊距） | 樣式改動是正常的設計迭代，釘住它只會製造每次調版都要改測試的摩擦，而且擋不到任何財務缺陷。只釘資料與必要的語意（§11.4） |
| **其他頁面的圖** | 目前只有 `ReportsView.vue` 用 `<Chart>`，沒有別的 |
| **後端任何變更** | 本規格不需要。後端的報表數字在 G20c 已經有完整覆蓋 |
| **把 `v-if` 的空狀態判斷抽成共用 helper** | 三張圖的空狀態條件各不相同（§5.5），抽象化現在只有一個使用者，是過早抽象 |

---

## 3. 涉及模組與邊界

純前端，且只動 `shared` 與 `modules/reporting`：

```
frontend/src/shared/Chart.vue                      ← 不改（被測對象）
frontend/src/shared/Chart.dom.test.ts              ← 新增
frontend/src/shared/testing/harness.ts             ← 新增 chartStub 匯出
frontend/src/modules/reporting/ReportsView.vue     ← 改 1 行（:366）
frontend/src/modules/reporting/ReportsView.dom.test.ts ← 補測試
```

- `frontend/src/modules/` 的模組名稱對應後端模組，`reporting` 已存在，不新增模組
- 測試輔助一律放 `src/shared/testing/`，與 G22 建立的慣例一致
- **後端模組邊界不受影響**（零後端變更，`ModuleBoundariesTest` 不會動到）

---

## 4. DB schema 與 migration

**無。** 本規格零 migration，`db/migration/` 一個檔都不加（`V15` 保留給 G20f 或後續缺口）。

驗收 11 會驗這件事。

---

## 5. 技術設計

### 5.1 圖表 stub 收進 `harness.ts`

在 `frontend/src/shared/testing/harness.ts` 新增一個匯出：

```ts
import type { Component } from "vue";

/**
 * `Chart.vue` 的測試替身。
 *
 * 真的 `Chart.vue` 會呼叫 echarts 的 `init()`，而 `init()` 在 jsdom 取不到
 * canvas 2d context（PR #69 的 run #497 就是這樣紅的）。要斷言「傳給圖的
 * option 對不對」並不需要真的畫出來 —— 用這個替身把 option 原封不動留在
 * props 上，測試再用 `findAllComponents` 讀回來即可。
 *
 * `Chart.vue` 自己的行為由 `src/shared/Chart.dom.test.ts` 覆蓋，走的是
 * mock `echarts/core` 的路線，不經過這個替身。
 */
export const chartStub: Component = {
  name: "Chart",
  props: ["option", "label", "description"],
  template:
    '<div class="chart-stub" role="img" :aria-label="description || label"></div>',
};
```

`ReportsView.dom.test.ts` 頂端的 `vi.mock` 改成引用它。**唯一正確的寫法是 async factory 配 dynamic import：**

```ts
vi.mock("../../shared/Chart.vue", async () => ({
  default: (await import("../../shared/testing/harness")).chartStub,
}));
```

**不要寫成靜態 import 版本**（`import { chartStub } from "..."` 配 `vi.mock(..., () => ({ default: chartStub }))`）。`vi.mock` 會被 Vitest 提到檔案最上面，factory 裡**不能**依賴檔案頂層 `import` 建立的 binding：factory 的執行時機是「`ReportsView` 解析到 `Chart.vue` 的那一刻」，**那個時機早於本測試檔自己的 import binding 完成初始化**，於是 `chartStub` 還在 TDZ，拿到的是 `Cannot access 'chartStub' before initialization` 或 undefined。

這件事取決於模組解析次序，所以它**會不會炸是環境相依的** —— 這正是不能把靜態版本當預設、把 async 版本當「炸了再換」的備案的理由：一個時好時壞的寫法比一個穩定壞掉的寫法更難處理。async factory 的 dynamic import 在 factory **執行時**才解析 `harness.ts`，此時它必定已初始化完成，不存在次序問題。

> 這條與 §5.4 的 `vi.hoisted` 是同一個根因的兩種解法：**`vi.mock` 的 factory 不能看見它下面的任何東西。** 需要的值來自別的模組就用 async dynamic import（本節），需要的值是本檔自己造的 mock 函式就用 `vi.hoisted`（§5.4）。

**保留現有 class 名 `chart-stub`。** `ReportsView.dom.test.ts` 現有的三條測試用 `findAllComponents(Chart)` 找元件、不靠 class，但沿用同一個 class 名可以讓既有測試一個字都不用改（驗收 1）。

### 5.2 `trend` 的 option 斷言

要釘住的是**資料來源對應**，兩個方向都要：

```ts
it("每日營業額折線圖的 x 軸是日期、series 是當日營業額", async () => {
  const wrapper = await mountReports(
    reportFixture({
      daily: [
        { day: "01", revenue: 1000, orders: 7 },
        { day: "02", revenue: 0, orders: 0 },
        { day: "03", revenue: 250, orders: 2 },
      ],
    }),
  );
  const option = chartOption(wrapper, "本月每日營業額折線圖");

  expect(option.xAxis.data).toEqual(["01", "02", "03"]);
  expect(option.series[0].data).toEqual([1000, 0, 250]);
  expect(option.series[0].type).toBe("line");
});
```

**fixture 的三筆資料是刻意設計的：** `revenue` 與 `orders` 在每一筆都不相等（1000≠7、250≠2），所以「把 `.revenue` 寫成 `.orders`」一定會讓斷言紅。`day: "02"` 那筆 `revenue: 0` 用來確認零值是被保留成 `0` 而不是被過濾掉或變成 `null` —— 折線圖中間斷一格與畫到 0 是不同的意思。

`label` 的字串（`"本月每日營業額折線圖"`，`ReportsView.vue:293`）要照現況填，實作時以原始碼為準，不要照抄本規格的字面（若不符，改測試對齊程式碼，**不要**改程式碼的 label 去對齊規格）。

### 5.3 `hourly` 的 option 斷言

```ts
it("時段分布圖的 x 軸是 24 個小時、series 是訂單數", async () => {
  const hourlyData = Array.from({ length: 24 }, (_, h) => ({
    hour: `${String(h).padStart(2, "0")}:00`,
    orders: h === 9 ? 5 : h === 18 ? 3 : 0,
  }));
  const wrapper = await mountReports(reportFixture({ hourly: hourlyData }));
  const option = chartOption(wrapper, "24 小時成交訂單分布長條圖");

  expect(option.xAxis.data).toHaveLength(24);
  expect(option.xAxis.data[0]).toBe("00:00");
  expect(option.xAxis.data[23]).toBe("23:00");
  expect(option.series[0].data[9]).toBe(5);
  expect(option.series[0].data[18]).toBe(3);
  expect(option.series[0].type).toBe("bar");
});
```

**為什麼要斷言 `[0]` 與 `[23]` 的字面值：** 後端 `ReportService` 產生 `hourly` 用的是 `((paid_at+28800000)/3600000)%24` 的台北時算術（G09 §5.2），而「圖上第一格是哪個小時」是這條算術唯一在前端看得見的地方。釘住頭尾可以擋住「前端自己又把小時重排一次」這類漂移。

### 5.4 `Chart.vue` 自己的測試（新檔 `src/shared/Chart.dom.test.ts`）

**手法：mock `echarts/core`，不 mock `Chart.vue`。** 這樣 `Chart.vue` 的 `onMounted`／`watch`／`onBeforeUnmount` 真的會跑，只有「真的畫到 canvas」那一步被換掉。

下面這段是**檔案骨架（看結構用）**，mock 宣告的正確寫法在本節末尾的 `vi.hoisted` 那段 —— 以末尾那段為準：

```ts
import { mount } from "@vue/test-utils";
import { describe, expect, it, vi, beforeEach } from "vitest";

const setOption = vi.fn();
const resize = vi.fn();
const dispose = vi.fn();
const init = vi.fn(() => ({ setOption, resize, dispose }));

vi.mock("echarts/core", () => ({
  init,
  use: vi.fn(),
}));
vi.mock("echarts/charts", () => ({
  LineChart: {}, BarChart: {}, PieChart: {},
}));
vi.mock("echarts/components", () => ({
  GridComponent: {}, TooltipComponent: {}, LegendComponent: {}, AriaComponent: {},
}));
vi.mock("echarts/renderers", () => ({ CanvasRenderer: {} }));

const disconnect = vi.fn();
class ResizeObserverStub {
  observe = vi.fn();
  disconnect = disconnect;
}

import Chart from "./Chart.vue";

describe("Chart.vue", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    globalThis.ResizeObserver = ResizeObserverStub as never;
  });

  it("掛載時把 option 連同 aria 說明傳給 setOption", () => { /* ... */ });
  it("option 變更時以 notMerge 重設，不與舊 series 合併", async () => { /* ... */ });
  it("卸載時釋放 observer 與 chart 實例", () => { /* ... */ });
  it("容器有 role=img 與可讀的 aria-label", () => { /* ... */ });
});
```

四條測試各自要斷言的重點：

**(a) 掛載** —— `init` 被呼叫一次，`setOption` 收到的物件同時含傳入的 option 欄位與 `aria: { enabled: true, description: <label> }`。沒給 `description` 時 `aria.description` 要等於 `label`；給了就等於 `description`（兩種都測，這是 `props.description || props.label` 那條短路的兩個分支）。

**這兩個分支在生產程式碼裡都真的有使用者**，不是為了覆蓋率湊的：`trend` 那張圖傳了 `:description`（`ReportsView.vue:294-296`，一段含 `money(report.revenue)` 的字串），而 `hourly`（`:379`）只傳 `label`。所以短路寫反會同時弄壞一張圖的無障礙說明。

**(b) option 變更** —— `await wrapper.setProps({ option: next })`，然後斷言**最後一次** `setOption` 的呼叫是 `(next, true)`。
**第二個引數 `true` 一定要斷言** —— 它就是 `notMerge`，是這條測試存在的唯一理由（缺陷 2）。只斷言「`setOption` 有被再呼叫一次」擋不到任何東西。

**(c) 卸載** —— `wrapper.unmount()` 之後 `disconnect` 與 `dispose` 各被呼叫一次。

**(d) aria** —— 根元素 `.chart` 有 `role="img"`，`aria-label` 等於 `description || label`。
**注意**：`Chart.vue` 的 template 目前寫的是 `:aria-label="description || label"`，直接用了 `description`／`label` 這兩個名字（`<script setup>` 會把 props 展開到模板作用域），這與 `onMounted` 裡的 `props.description || props.label` 是同一條邏輯的兩份寫法。測試把兩邊都釘住即可，**不要**為了去重而改 `Chart.vue`（§2.2）。

**`ResizeObserver` 在 jsdom 不存在**，所以要自己塞一個 stub。`beforeEach` 裡設 `globalThis.ResizeObserver`，不要改 `setup.dom.ts` —— 那個檔是所有 dom 測試共用的，為一支測試加全域污染不划算；若實作端發現第二支測試也需要它，那時再搬進 `setup.dom.ts` 才有理由。

**`vi.mock` 的 factory 會被提到檔案最上面，所以它不能用到下面才初始化的 `const`。** 上面那段示意碼把 `const setOption = vi.fn()` 寫在 `vi.mock` 之前只是為了好讀，**實際寫起來要用 `vi.hoisted`**，否則 factory 執行時那些 `const` 還在 TDZ，會收到 `Cannot access 'init' before initialization`：

```ts
const mocks = vi.hoisted(() => ({
  setOption: vi.fn(),
  resize: vi.fn(),
  dispose: vi.fn(),
  disconnect: vi.fn(),
  init: vi.fn(),
}));

vi.mock("echarts/core", () => ({
  init: mocks.init,
  use: vi.fn(),
}));
```

然後在 `beforeEach` 裡接起 `init` 的回傳值（**每條測試都重設，理由見下方**）：

```ts
beforeEach(() => {
  vi.clearAllMocks();
  mocks.init.mockReturnValue({
    setOption: mocks.setOption,
    resize: mocks.resize,
    dispose: mocks.dispose,
  });
  globalThis.ResizeObserver = class {
    observe = vi.fn();
    disconnect = mocks.disconnect;
  } as never;
});
```

**`vi.clearAllMocks()` 不會清掉 `mockReturnValue`。** 它對每個 mock 呼叫 `.mockClear()`，只清呼叫歷史（`mock.calls`、`mock.instances`、`mock.results`），**實作與回傳值原封不動**。會重設實作的是 `vi.resetAllMocks()`／`.mockReset()`。

所以上面那段的順序**不是為了修復 `clearAllMocks` 造成的破壞**，而是為了**測試隔離**：把 `mockReturnValue` 設在 `beforeEach` 裡，每條測試都拿到一個全新的 chart 物件，前一條測試對它做過什麼都不會滲進來。這個寫法本身是對的，照抄即可。

> **這一段在 v1.0 把原因寫反了**（說 `clearAllMocks` 會清掉 `mockReturnValue`），由 Codex 在 [PR #70](https://github.com/choka1227/coffee_GPT6/pull/70) 的審查指出。保留這個註記是因為**錯的因果會讓人在真的遇到問題時找錯方向**：如果有人照 v1.0 的說法相信「回傳值被 clear 掉了」，下次 mock 行為異常時他會去檢查 `clearAllMocks` 的位置，而不是去檢查 `vi.hoisted` 或 factory 的執行時機 —— 那兩個才是這個檔案真正會出事的地方。
>
> **真正會「默默收到 0 次呼叫」的失敗模式仍然存在，但成因是別的**：`Chart.vue` 裡全是 `chart?.setOption(...)` 的選擇性串接，所以只要 `init` 因為任何原因回傳 `undefined`（例如 `vi.hoisted` 沒用、factory 拿到還在 TDZ 的 `const`），**不會丟錯，只會讓斷言收到 0 次呼叫**。驗收 8 的 `notMerge` 斷言能擋住它，但看到「0 次呼叫」時要往 §5.4 開頭的 hoisting 去查，不是往 `clearAllMocks` 查。

**`vi.mock("echarts/core")` 必須連 `use` 一起提供。** `Chart.vue` 在模組層就呼叫 `use([...])`（`:13`），mock 漏掉 `use` 會在 import 時就 `TypeError`。同理四個 `echarts/*` 子路徑都要 mock，否則真的 echarts 模組會被載入 —— 載入本身不會炸（炸的是 `init`），但沒必要讓它進來。

### 5.5 缺陷 3 的修正（唯一一處生產程式碼變更）

`frontend/src/modules/reporting/ReportsView.vue:366`：

```diff
           <Chart
-            v-if="report.revenue"
+            v-if="report.netProductRevenue"
             :option="categoryChart"
             label="餐點分類商品淨營收圓環圖"
           />
```

**只改這一行。** 三張圖的現況與處置：

| 圖 | 現在的顯示條件 | 要不要改 |
| --- | --- | --- |
| `trend`（`:291`） | **沒有 `v-if`**，一律顯示 | **不改** —— `daily` 後端固定回傳當月每一天（沒有交易的日子是 `revenue: 0`），永遠非空，不需要空狀態 |
| `categoryChart`（`:365`） | `v-if="report.revenue"` | **改成 `report.netProductRevenue`** —— 資料源是 `categoriesNet`，口徑要一致 |
| `hourly`（`:379`） | **沒有 `v-if`**，一律顯示 | **不改** —— `hourly` 後端固定回傳 24 筆，永遠非空；且畫的是訂單數，與金額無關 |

也就是說**整個報表頁只有分類圓餅圖有空狀態分支**，而它的條件看錯了欄位。另外兩張圖「沒有條件」是正確的設計，不是遺漏 —— 後端 `ReportService` 把 `daily` 補滿當月天數、`hourly` 補滿 24 小時（G09 的設計），所以它們不會有「沒資料」的狀態。

**不要**順手替另外兩張圖加上 `v-if`，那會製造一個永遠不會成立的分支，而且永遠不會被測試覆蓋。

實作時以原始碼為準（本規格依主線 `fea0ef4` 描述）。若發現現況與上表不符，**照原始碼走並在 PR 描述說明差異**，不要為了對齊規格而改出錯的行為。

對應的回歸測試：

```ts
it("營業額為 0 但仍有商品淨營收時，分類圖照樣顯示", async () => {
  const wrapper = await mountReports(
    reportFixture({
      revenue: 0,
      netProductRevenue: 252,
      categoriesNet: { 經典咖啡: 252 },
    }),
  );
  const panel = panelByTitle(wrapper, "餐點分類淨營收佔比");

  expect(panel.find(".chart-stub").exists()).toBe(true);
  expect(panel.text()).not.toContain("尚無銷售資料");
});
```

**這組 fixture 就是「優惠碼把整單折到 0」**：`revenue = 0`（= `sum(o.total)`）而品項淨額 252，`codeDiscount = 252`。G20c 後端測試的 `D-1` 是同一個情境，所以這不是編出來的邊界值。

再補一條反向的，確認真的沒資料時空狀態還在（否則上面那條用 `v-if="true"` 也會過）：

```ts
it("完全沒有商品淨營收時顯示空狀態而不是空圖", async () => {
  const wrapper = await mountReports(
    reportFixture({ revenue: 0, netProductRevenue: 0, categoriesNet: {} }),
  );
  const panel = panelByTitle(wrapper, "餐點分類淨營收佔比");

  expect(panel.find(".chart-stub").exists()).toBe(false);
  expect(panel.text()).toContain("尚無銷售資料");
});
```

**兩個方向都要。** 這是 G20c 驗收 3a 學到的同一件事：只驗一個方向等於只擋一半。

### 5.6 共用的 `chartOption` 讀取輔助

三條 option 測試都要「依 label 找到圖、把 option 讀出來、斷言型別」。放在 `ReportsView.dom.test.ts` 的檔內私有函式即可（只有這一支測試檔用得到，不進 `harness.ts`）：

```ts
type ChartOption = {
  xAxis: { data: unknown[] };
  series: { type: string; data: unknown[]; name?: string }[];
};

function chartOption(wrapper: VueWrapper, label: string): ChartOption {
  const chart = wrapper
    .findAllComponents(Chart)
    .find((component) => component.props("label") === label);
  expect(chart, `找不到 label 為「${label}」的圖`).toBeDefined();
  return chart!.props("option") as ChartOption;
}
```

既有的第 103-112 行那條 `categoryChart` 測試可以順手改用它（圓餅圖沒有 `xAxis`，所以 `ChartOption` 的 `xAxis` 在那條用不到 —— 若 TypeScript 抱怨，把 `xAxis` 標成選用 `xAxis?:`）。**這是整理，不是必要項**，驗收不要求。

---

## 6. API

**無變更。** 本規格不新增、不修改任何端點，請求與回應一個位元都不變。

---

## 7. 權限與資料範圍

**無變更。** 本規格不新增端點，不碰任何權限常數，不改任何資料範圍判斷。

報表頁的既有授權（`REPORT_ALL` / `REPORT_STORE`，店長只看本店）由後端 `ReportService` 把關，G20c 的 `branchScopeCannotSeeOrRequestAnotherBranch` 已覆蓋。**前端測試不是授權防線**（`AGENTS.md`：前端導航只是體驗，真正的授權一律在後端），本規格不假裝它是。

因此**本規格沒有越權測試**。這不是遺漏 —— 沒有新端點就沒有可越的權。

---

## 8. 金額規則

本規格**不產生任何金額計算**，也不改變任何既有計算。

要注意的只有一條：**測試斷言金額時用整數字面值，不要用算式或浮點數**。`reportFixture` 的金額欄位全是 `number`，而後端是 TWD 整數元；測試寫 `expect(...).toEqual([1000, 0, 250])` 這種字面值，不要寫 `expect(...).toEqual([a - b, ...])` —— 算式會跟著實作一起錯。

圖表層**完全不做金額運算**，只把後端給的數字搬進 `series.data`。驗收 9 會確認這一點：三張圖的 `series[].data` 必須是 `report` 欄位的直接 `map`，不含任何加減乘除。若實作時發現某張圖需要在前端算（例如百分比），**停下來寫進 PR 描述** —— 那會是計價邏輯外洩到前端，與 G20a §13.1 拒絕的是同一件事。

---

## 9. 施工階段

本規格**只有一個階段**。

### 階段切不開的理由（明講，依 `AGENTS.md`「施工階段與中斷續作」）

規模本身就是一次執行做得完的量級：

| 檔案 | 變更 | 估計行數 |
| --- | --- | --- |
| `harness.ts` | 新增 `chartStub` | +18 |
| `ReportsView.dom.test.ts` | 改 `vi.mock` 引用、加 4 條測試、加 `chartOption` | +90 |
| `Chart.dom.test.ts` | 新檔，4 條測試 + mock 設定 | +110 |
| `ReportsView.vue` | 1 行 | +1 −1 |

合計約 220 行，**全部是測試與測試輔助，沒有任何需要設計決策的實作**。切成兩個階段反而會製造問題：`chartStub` 進了 `harness.ts` 而沒有人用它，`npm run build` 的 `vue-tsc --noEmit` 不會抱怨未使用的匯出，但那是一個「半成品進主線」的狀態，違反 G20c 以來一貫的「獨立可交付」要求。

**如果額度真的不夠**，可切的那一刀在這裡（但**不要主動切**，只在跑不完時用）：

- **先推**：`harness.ts` 的 `chartStub` + `ReportsView.dom.test.ts` 的三條 option／`v-if` 測試 + `ReportsView.vue` 的一行修正。這一組自己就完整：CI 綠、可獨立合併、能單獨驗收驗收 1–7 與 9–12
- **後推**：`Chart.dom.test.ts`（驗收 8）

這一刀之所以乾淨，是因為兩組的被測對象完全不同（一組測 option 的產生、一組測 `Chart.vue` 的消費），彼此不共用任何新程式碼。若走這條路，PR 維持 **draft** 並在描述維護進度檢查表，照 `AGENTS.md` 的格式。

---

## 10. 驗收條件

可逐條勾選。

- [ ] 1. `ReportsView.dom.test.ts` 既有的三條測試**一個字元都不改**（除了頂端 `vi.mock` 改為引用 `chartStub`）且全綠
- [ ] 2. `harness.ts` 匯出 `chartStub`，`ReportsView.dom.test.ts` 的 `vi.mock` 以 **async factory 配 dynamic import** 引用它（`async () => ({ default: (await import(".../harness")).chartStub })`，**不是**靠檔案頂層的靜態 `import` binding —— 理由見 §5.1），測試檔內**沒有**自己的 stub 定義
- [ ] 3. `trend` 的 option 有測試斷言 `xAxis.data` 等於 `daily[].day`、`series[0].data` 等於 `daily[].revenue`、`series[0].type` 為 `"line"`，且 fixture 的 `revenue` 與 `orders` 在每一筆都不相等
- [ ] 4. `trend` 的測試覆蓋 `revenue: 0` 的那一天，並斷言它在 `series[0].data` 裡是 `0`（不是被濾掉、不是 `null`）
- [ ] 5. `hourly` 的 option 有測試斷言 `xAxis.data` 長度為 24、`[0]` 為 `"00:00"`、`[23]` 為 `"23:00"`、`series[0].data` 在指定小時的值正確、`series[0].type` 為 `"bar"`
- [ ] 6. `ReportsView.vue` 分類圖的 `v-if` 改為看 `report.netProductRevenue`
- [ ] 7. 缺陷 3 有**兩個方向**的回歸測試：`revenue=0` 且 `netProductRevenue>0` 時圖要在、空狀態文字不出現；`netProductRevenue=0` 時圖不在、空狀態文字要出現
- [ ] 8. 新增 `src/shared/Chart.dom.test.ts`，以 mock `echarts/core`（**不** mock `Chart.vue`）的方式覆蓋四件事：(a) 掛載時 `setOption` 收到 option 與 `aria.description`，且 `description` 有值／無值兩個分支都測；(b) option 變更時最後一次 `setOption` 的呼叫引數為 `(新 option, true)` —— **`true` 必須被斷言**；(c) 卸載時 `disconnect` 與 `dispose` 各一次；(d) 根元素 `role="img"` 且 `aria-label` 正確
- [ ] 9. 三張圖的 `series[].data` 都是 `report` 欄位的直接 `map`，**不含任何算式**（審查時以原始碼為準）
- [ ] 10. **後端零變更** —— `git diff` 在 `backend/` 下沒有任何檔案
- [ ] 11. **零 migration** —— `backend/*/src/main/resources/db/migration/` 沒有新增檔案
- [ ] 12. `Chart.vue` 的 props 不增不減（`option` / `label` / `description`），template 的對外結構（`role`、`aria-label`、`class="chart"`）不變
- [ ] 13. 沒有新增任何 npm 依賴 —— `frontend/package.json` 的 `dependencies` 與 `devDependencies` 一字不改
- [ ] 14. `cd frontend && npm ci && npm run build` 與 `npm test` 全綠；`cd backend && ./mvnw -B -ntp verify` 全綠（後端沒動，但要確認沒有誤傷）
- [ ] 15. 執行位元：`backend/mvnw`、`scripts/build.sh`、`start-demo.sh` 皆 `100755`

---

## 11. 測試要求

### 11.1 測試矩陣

| # | 情境 | 檔案 | 對應驗收 |
| --- | --- | --- | --- |
| (a) | `trend` 的 x 軸與 series 對應正確（含零值那一天） | `ReportsView.dom.test.ts` | 3、4 |
| (b) | `hourly` 的 24 格與訂單數對應正確（含頭尾字面值） | `ReportsView.dom.test.ts` | 5 |
| (c) | `categoryChart` 用 `categoriesNet`（**既有**，不要改） | `ReportsView.dom.test.ts` | 1 |
| (d) | `revenue=0` 且 `netProductRevenue>0` → 分類圖顯示 | `ReportsView.dom.test.ts` | 7 |
| (e) | `netProductRevenue=0` → 分類圖不顯示、空狀態出現 | `ReportsView.dom.test.ts` | 7 |
| (f) | `Chart.vue` 掛載傳 option + aria（`description` 兩分支） | `Chart.dom.test.ts` | 8(a) |
| (g) | `Chart.vue` option 變更用 `notMerge=true` | `Chart.dom.test.ts` | 8(b) |
| (h) | `Chart.vue` 卸載釋放資源 | `Chart.dom.test.ts` | 8(c) |
| (i) | `Chart.vue` 的 aria 屬性 | `Chart.dom.test.ts` | 8(d) |

### 11.2 越權測試

**本規格沒有，理由見 §7**（零新端點）。這一行刻意寫出來，省得審查時以為漏了。

### 11.3 不要做的事

- **不要**為了讓 `Chart.vue` 好測而改它的 props、拆出 adapter、或把 `init` 改成可注入。G20c §13.6 的決策維持，而 §5.4 的 mock 手法已經不需要這些
- **不要**裝 `canvas`／`node-canvas` 或任何新依賴（驗收 13）
- **不要**斷言顏色、`radius`、`center`、`grid` 邊距、`itemStyle` 這類樣式。改版會紅，而紅了不代表有缺陷
- **不要**用快照測試（`toMatchSnapshot`）。整個 option 物件進快照，任何調版都會讓它紅，而人會養成「反正就 `-u` 更新」的習慣，等於沒有防線
- **不要**把 `ResizeObserver` 的 stub 加進 `setup.dom.ts`（§5.4 的理由）
- **不要**在 `ReportsView.dom.test.ts` 裡 mock `echarts/core` —— 那支測試走 `chartStub` 路線，兩套 mock 混用只會讓後來的人搞不清哪條路生效

---

## 12. 開工前提與並行注意

**開工前提已滿足，沒有任何外部閘門：**

- G22（前端測試基礎設施）已合併 —— `vitest`、`jsdom`、`@vue/test-utils` 都在 `devDependencies` 裡，`npm test` 已進 CI
- G23（前端元件層測試）已合併 —— `harness.ts` 的 `stubApi`／`mountView` 可直接用
- **G20c 已於 2026-10-05 隨 [PR #69](https://github.com/choka1227/coffee_GPT6/pull/69) 合併**（主線合併提交 `fea0ef4`）。本規格依賴它帶進來的 `categoriesNet`、`netProductRevenue`、`reportFixture` 以及那個 `vi.mock` stub 的先例，三者都已在主線上

**與 G20a 的並行注意：**

G20a（工作順序第 22 項）動的是 `modules/ordering/MenuView.vue` 與購物車，**與本規格在檔案層級零重疊**。

**更正（v1.1）：** v1.0 寫「唯一的交會點是 `src/shared/testing/harness.ts`」，**那是錯的**，由 Codex 在 [PR #70](https://github.com/choka1227/coffee_GPT6/pull/70) 的審查指出。G20a 要加的那條 `"/api/promotions/active"` 預設路由是加在 `MenuView.dom.test.ts` 自己的 `mountMenu` 裡（G20a §11.3），而 G20a §11.4 **明文禁止**修改 `harness.ts` 的 `stubApi`。G20a 的實作（[PR #72](https://github.com/choka1227/coffee_GPT6/pull/72)）已證實這一點：它動的 11 個檔案裡**沒有 `harness.ts`**。

所以兩份規格在 `harness.ts` 上**不會相撞** —— 本規格是這一輪唯一新增 `chartStub` 匯出的一方：

- 本規格只**新增**一個具名匯出 `chartStub`，不改 `stubApi`／`mountView` 的任何一行
- G20a 不碰 `harness.ts`

**先合併誰：** 誰先綠誰先合併。兩邊在檔案層級沒有交集，**不需要任何合併順序協調**；本規格刻意不依賴 G20a 的任何產出，反向也成立。

> 這類「推測出來的交集」比漏寫交集更糟：它會讓實作端為了一個不存在的衝突去預留處理，或在解衝突時誤動對方的檔案。**並行注意只寫查證過的交集**，查不到就寫「無」。

---

## 13. 設計決策

每一項都附理由與推翻它的代價。**本節沒有「待 PO 決定」**，設計決策已整批授權給 Claude（`AGENTS.md`「設計決策的歸屬」）。

### 13.1 本輪不做 G20f（優惠碼折抵分攤到品項）—— **維持 G20c §13.2 的決定**

**決定：** G20f 繼續擱置，本輪產出 G20g。

**理由：** G20c §13.2 拒絕分攤的三個理由，從 2026-10-04 到今天**一條都沒有鬆動**：

1. 分攤規則本質上武斷（同一杯拿鐵在不同訂單裡會有不同淨營收），而本 repo 至今沒有真實營業資料可以判斷哪種攤法符合營運直覺
2. 缺陷 1–4 不需要分攤就修完了 —— 那正是 PR #69 已經做到的事
3. 成本差一個數量級：分攤要動 `OrderService.create` 的**金額寫入路徑**、加 `order_items.code_discount_amount`（V15）、處理歷史回填，還要重新證明「每列折抵不超過該列毛額」在餘數分配後仍成立

這一輪唯一的新資訊是「G20c 合併了」，而 G20c 的合併**不構成推翻理由** —— 它本來就是在「不分攤」的前提下設計的，合併只是確認那個前提可行。

**推翻的代價（給下一輪）：** 很低，而且比昨天更低。附錄 A 的演算法還在 G20c 裡，照著做即可。真正該等的是**第一批真實促銷資料**：屆時看實際用了哪幾種優惠碼（整單定額？滿額折扣？），再決定比例攤是不是對的攤法。**在那之前，寫 G20f 等於把一個武斷決定寫成施工級規格，然後讓 Codex 花一次完整執行去實作它。** 這比空跑一輪糟。

**這一條記在這裡而不是只記在摘要裡**，是因為下一輪醒來的人（可能是另一個實例）會看到「工作順序用完了、G20f 的演算法都寫好了」而覺得它是顯然的下一步。它不是，理由在這裡。

### 13.2 用 mock `echarts/core` 測 `Chart.vue`，不在 jsdom 跑真的 ECharts —— **mock 依賴**

**決定：** `Chart.dom.test.ts` mock `echarts/core` 的 `init`，讓 `Chart.vue` 的生命週期真的執行。

**理由：** 三個選項比較過：

| 選項 | 成本 | 擋到什麼 |
| --- | --- | --- |
| 裝 `canvas` 套件跑真 ECharts | 新增原生編譯依賴（要 Cairo），CI 安裝時間變長，違反「不引入新依賴」 | 「圖真的畫出來了」—— 而我們不斷言畫面，所以**擋到的東西接近零** |
| mock 整個 `Chart.vue`（現況） | 零 | 零（被測對象被換掉了） |
| **mock `echarts/core`** | 約 15 行 mock 設定 | `Chart.vue` 的全部自有邏輯：`setOption` 的引數、`notMerge`、`dispose`、aria |

第三個選項是唯一「成本低且真的擋到東西」的。它測的是**我們寫的程式碼**（`Chart.vue` 的 40 行），不是 ECharts（那是別人測過的函式庫）。

**推翻的代價：** 若日後真的需要驗「圖畫出來長什麼樣」，那是視覺回歸測試，要的是 Playwright + 截圖比對，與本規格的手法沒有重疊，不會浪費這裡的工。已登記為 G20i（§14）。

### 13.3 只釘資料與語意，不釘樣式 —— **不測樣式**

**決定：** 斷言 `series[].data`、`series[].type`、`xAxis.data`、`aria`，不斷言顏色、圓角、邊距。

**理由：** 測試的價值等於「紅的時候真的有問題」的比率。樣式斷言的紅燈幾乎都是設計調整造成的假警報，而假警報會訓練出「紅了就更新測試」的習慣，那會連真警報一起吃掉。資料對應則相反：它紅的時候一定有問題，因為沒有人會「刻意」把 `revenue` 換成 `orders`。

**推翻的代價：** 低。哪天真的有「圖的配色必須符合品牌規範」這種需求，那是另一類測試（design token 的一致性），加上去不必回頭改本規格的任何一條。

### 13.4 缺陷 3 的修正放進本規格，而不是留在備查清單 —— **收進來**

**決定：** `ReportsView.vue:366` 的一行修正納入本規格範圍。

**理由：** Claude 在 PR #69 的 review 裡把它列為「不擋，登記備查」，當時的判斷是正確的 —— PR 已綠、auto-merge 已啟用，為一行顯示條件換掉 head SHA 會作廢 review 並多燒一次 CI，不划算。但「不值得為它單獨推 commit」不等於「不該修」。

本規格剛好是**同一個檔案、同一類缺陷、本來就要推 commit** 的時機，而且缺陷 3 正是缺陷 1 的實例：一個沒有測試看著的圖表顯示邏輯漂掉了。只修一行不寫測試，下一次還會發生；只寫測試不修一行，測試得照著錯的行為寫。兩件事一起做才完整。

**推翻的代價：** 若實作端發現那一行其實不該改（例如 `revenue` 真的是正確口徑），**照自己的判斷不要改，寫進 PR 描述說明理由**，驗收 6、7 跟著作廢。規格寫錯的代價由下一輪修補，不要為了對齊規格而改出錯的行為。

### 13.5 `chartStub` 放 `harness.ts`，`chartOption` 放測試檔內 —— **按使用者數量決定**

**決定：** stub 進共用的 `harness.ts`，讀取輔助留在 `ReportsView.dom.test.ts` 裡。

**理由：** `chartStub` 會有第二個使用者（任何未來要測圖的測試檔都得 mock `Chart.vue`），`chartOption` 不會（它綁定 `ReportsView` 的 label 慣例）。抽象化的門檻是「第二個使用者出現」，不是「看起來可以共用」。

**推翻的代價：** 零。真的出現第二個使用者時搬過去就好，`harness.ts` 本來就是那個家。

### 13.6 只有一個施工階段 —— **不切階段**

**決定：** 單階段交付，但在 §9 寫明額度不足時唯一那一刀切在哪。

**理由：** 約 220 行、全是測試、零設計決策，一次執行做得完。切兩階段會製造「`chartStub` 進了主線但沒人用」的半成品狀態。

**推翻的代價：** 零 —— §9 已經把那一刀的位置與兩邊各自的驗收子集寫好了，實作端真的跑不完就照那裡切，不需要回來問。

---

## 14. 登記給後續的缺口

| 編號 | 內容 | 來源 |
| --- | --- | --- |
| **G20i** | 視覺回歸測試（Playwright + 截圖比對）。**開工前提是先決定要不要引入 headless browser 這個新依賴與新 CI 階段**，那是 PO 的範圍（`AGENTS.md` 禁止事項第 3 條的例外要有 PO 可見的理由） | 本規格 §2.2、§13.2 |
| G20f | 訂單層優惠碼折抵分攤到品項（演算法見 G20c 附錄 A）。**開工前提是要有第一批真實促銷資料**，理由見本規格 §13.1 | G20c §13.2 |
| G20b | 多規則疊加與單位消耗模型 | G20 §13.2 |
| G20d | 選項層促銷 | G20 §2.2 |
| G20h | 後端購物車試算端點 `POST /api/orders/preview`。**開工前提是 G20a 先實作完** | G20a §13.1 |

這些**都不計入規格庫存**，登記的目的是讓下一輪不用重新推導。

編號說明：`G20g` 由 G20c §13.6 占用（即本規格），`G20h` 由 G20a §13.1 占用，`G20i` 由 G20a §14 占用（促銷提示的樣式，2026-10-05 登記），所以 **`G20j`** 是 `G20` 系列下一個未使用號。`G28` 已由 G08 §14 占用，主序列的下一個未使用號是 `G29`。

---

## 15. 版本紀錄

| 日期 | 版本 | 變更 |
| --- | --- | --- |
| 2026-10-05 | v1.1 | 依 Codex 在 [PR #70](https://github.com/choka1227/coffee_GPT6/pull/70) 的 `REQUEST_CHANGES` 修正三處**規格準確性**問題（三項查證後皆成立，全數採納）：(1) §5.1 原把「在 `vi.mock` factory 裡直接用靜態 import 的 `chartStub`」描述為安全、只把 async dynamic import 當作炸了之後的備案 —— 實際上 factory 的執行時機早於本檔 import binding 初始化，會不會炸取決於模組解析次序，**時好時壞比穩定壞掉更難處理**，故改為直接定 async factory 為唯一寫法，並同步收緊驗收 2；(2) §5.4 原稱 `vi.clearAllMocks()` 會清掉 `mockReturnValue` —— **錯的**，`clearAllMocks` 只清呼叫歷史，重設實作的是 `resetAllMocks`。`beforeEach` 裡重設回傳值的寫法本身正確（理由是測試隔離），但錯的因果會讓人在真的遇到「0 次呼叫」時去查 `clearAllMocks` 而不是查 hoisting，故改寫原因並指向真正的失敗模式;(3) §12 原稱與 G20a 的交會點是 `harness.ts` —— **不存在**，G20a §11.3 要改的是 `MenuView.dom.test.ts` 的 `mountMenu`、§11.4 明文禁止動 `harness.ts`，其實作 PR #72 的 11 個檔案裡也確實沒有 `harness.ts`，故刪除該交集並改為「不需要合併順序協調」 |
| 2026-10-05 | v1.0 | 初版。依 G20c §13.6／§14 的 G20g 登記產出。缺陷 3（分類圖 `v-if` 口徑不一致）為 Claude 審查 [PR #69](https://github.com/choka1227/coffee_GPT6/pull/69) 時發現、當時列為「不擋，登記備查」第 3 項，依 §13.4 的理由併入本規格。§13.1 記錄「本輪不做 G20f」的理由，避免下一輪把它當成顯然的下一步 |
