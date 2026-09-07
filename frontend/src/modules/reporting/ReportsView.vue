<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import {
  Download,
  CalendarDays,
  TrendingUp,
  Receipt,
  ShoppingBag,
  Wallet,
  ArrowUpRight,
  Store,
  RefreshCw,
} from "lucide-vue-next";
import type { EChartsOption } from "echarts";
import Chart from "../../shared/Chart.vue";
import { api } from "../../shared/api";
import { money, number, currentMonth, csv } from "../../shared/format";
import type { Report, Branch } from "../../shared/types";
import { useAuth } from "../identity/store";
const auth = useAuth(),
  month = ref(currentMonth()),
  branchId = ref(""),
  branches = ref<Branch[]>([]),
  report = ref<Report | null>(null),
  loading = ref(false),
  error = ref("");
let request = 0;
const global = computed(() => auth.user?.scope === "GLOBAL");
const palette = ["#274d3e", "#c87e4c", "#9db4a1", "#e1bd86"];
const tooltip = {
  trigger: "axis" as const,
  backgroundColor: "#fff",
  borderColor: "#e3e7e2",
  textStyle: { color: "#273c32" },
};
async function load() {
  const id = ++request;
  loading.value = true;
  error.value = "";
  try {
    const r = await api<Report>(
      "/reports?month=" +
        month.value +
        (branchId.value
          ? "&branchId=" + encodeURIComponent(branchId.value)
          : ""),
    );
    if (id === request) report.value = r;
  } catch (e) {
    if (id === request) {
      error.value = (e as Error).message;
      report.value = null;
    }
  } finally {
    if (id === request) loading.value = false;
  }
}
onMounted(async () => {
  await load();
  if (global.value)
    try {
      branches.value = await api<Branch[]>(
        "/branches?manage=" + auth.can("BRANCH_MANAGE"),
      );
    } catch {}
});
const trend = computed<EChartsOption>(() => ({
  color: palette,
  tooltip,
  grid: { left: 48, right: 18, top: 22, bottom: 30 },
  xAxis: {
    type: "category",
    data: report.value?.daily.map((d) => d.day),
    boundaryGap: false,
    axisLine: { lineStyle: { color: "#e6e9e4" } },
    axisTick: { show: false },
    axisLabel: { color: "#7f8982" },
  },
  yAxis: {
    type: "value",
    splitLine: { lineStyle: { color: "#eff1ed", type: "dashed" } },
    axisLabel: {
      color: "#7f8982",
      formatter: (n: number) => (n >= 1000 ? n / 1000 + "k" : String(n)),
    },
  },
  series: [
    {
      name: "營業額",
      type: "line",
      smooth: 0.3,
      symbol: "circle",
      symbolSize: 5,
      data: report.value?.daily.map((d) => d.revenue),
      lineStyle: { width: 3 },
      areaStyle: { opacity: 0.09 },
    },
  ],
}));
const categoryChart = computed<EChartsOption>(() => ({
  color: palette,
  tooltip: { trigger: "item", formatter: "{b}<br/>{c} 元 · {d}%" },
  legend: {
    bottom: 0,
    icon: "circle",
    itemWidth: 8,
    textStyle: { color: "#69776e" },
  },
  series: [
    {
      name: "分類營業額",
      type: "pie",
      radius: ["52%", "73%"],
      center: ["50%", "43%"],
      avoidLabelOverlap: true,
      label: { show: false },
      itemStyle: { borderColor: "#fff", borderWidth: 5, borderRadius: 5 },
      data: Object.entries(report.value?.categories || {}).map(
        ([name, value]) => ({ name, value }),
      ),
    },
  ],
}));
const hourly = computed<EChartsOption>(() => ({
  color: ["#93ad9b"],
  tooltip,
  grid: { left: 34, right: 15, top: 14, bottom: 30 },
  xAxis: {
    type: "category",
    data: report.value?.hourly.map((h) => h.hour),
    axisLine: { lineStyle: { color: "#e6e9e4" } },
    axisTick: { show: false },
    axisLabel: { color: "#7f8982", interval: 3 },
  },
  yAxis: {
    type: "value",
    minInterval: 1,
    splitLine: { lineStyle: { color: "#eff1ed", type: "dashed" } },
  },
  series: [
    {
      name: "訂單數",
      type: "bar",
      barMaxWidth: 20,
      itemStyle: { borderRadius: [4, 4, 0, 0] },
      data: report.value?.hourly.map((h) => h.orders),
    },
  ],
}));
const todayMax = computed(() =>
  Math.max(1, ...(report.value?.topToday.map((p) => p.quantity) || [])),
);
function exportReport() {
  const r = report.value;
  if (!r) return;
  csv("coffee-report-" + r.month + ".csv", [
    ["月份", r.month],
    ["營業額", r.revenue],
    ["訂單數", r.orders],
    ["平均客單價", r.averageOrder],
    ["商品毛利（未扣營運費用）", r.grossProfit],
    [],
    ["日期", "營業額", "訂單數"],
    ...r.daily.map((d) => [r.month + "-" + d.day, d.revenue, d.orders]),
    [],
    ["門市", "營業額", "訂單數", "目標", "達成率 %"],
    ...r.branches.map((b) => [
      b.name,
      b.revenue,
      b.orders,
      b.target,
      b.achievement,
    ]),
    [],
    ["商品", "銷售數量", "銷售金額"],
    ...r.products.map((p) => [p.name, p.quantity, p.revenue]),
  ]);
}
</script>
<template>
  <div class="page-pad reports-page">
    <div class="page-heading">
      <div>
        <span class="eyebrow">A CLEARER VIEW OF YOUR BUSINESS</span>
        <h1>{{ global ? "每家分店，都在這裡。" : "今天的努力，看得見。" }}</h1>
        <p class="muted">
          {{
            global
              ? "營運總覽 · 掌握各店表現，找到下一個成長機會。"
              : "門市業績 · 從每一杯咖啡，累積更好的經營。"
          }}
        </p>
      </div>
      <button
        class="btn secondary"
        :disabled="!report || loading"
        @click="exportReport"
      >
        <Download :size="17" />匯出報表
      </button>
    </div>
    <div class="report-controls">
      <div class="report-title">
        <span class="live-dot"></span
        >{{ global ? "跨店營運概況" : "門市營運概況" }}
      </div>
      <div>
        <label v-if="global" class="inline-select"
          ><Store :size="17" /><select
            v-model="branchId"
            aria-label="報表門市"
            @change="load"
          >
            <option value="">所有分店</option>
            <option v-for="b in branches" :key="b.id!" :value="b.id">
              {{ b.name }}
            </option>
          </select></label
        ><label class="inline-select"
          ><CalendarDays :size="17" /><input
            v-model="month"
            type="month"
            min="2020-01"
            max="2100-12"
            aria-label="報表月份"
            @change="load" /></label
        ><button
          class="icon-btn"
          aria-label="更新報表"
          :disabled="loading"
          @click="load"
        >
          <RefreshCw :size="18" />
        </button>
      </div>
    </div>
    <div v-if="error" class="error-state" role="alert">
      {{ error }}<button class="btn secondary" @click="load">重試</button>
    </div>
    <div v-else-if="loading" class="loading-state">正在彙整已付款訂單…</div>
    <template v-else-if="report"
      ><div class="kpi-grid">
        <article class="kpi featured">
          <div><span>本月營業額</span><Wallet :size="19" /></div>
          <h2>{{ money(report.revenue) }}</h2>
          <small>已確認付款 · {{ month }}</small>
        </article>
        <article class="kpi">
          <div><span>成交訂單</span><Receipt :size="19" /></div>
          <h2>{{ number(report.orders) }}<small>筆</small></h2>
          <small>銷售 {{ number(report.quantity) }} 件餐點</small>
        </article>
        <article class="kpi">
          <div><span>平均客單價</span><ShoppingBag :size="19" /></div>
          <h2>{{ money(report.averageOrder) }}</h2>
          <small>營業額 ÷ 已付款訂單數</small>
        </article>
        <article class="kpi">
          <div><span>商品毛利率</span><TrendingUp :size="19" /></div>
          <h2>{{ report.grossMargin }}<small>%</small></h2>
          <small>商品毛利 {{ money(report.grossProfit) }} · 未扣營運費用</small>
        </article>
      </div>
      <div class="report-main-grid">
        <section class="panel trend-panel">
          <div class="panel-heading">
            <div>
              <h2>每日營業額趨勢</h2>
              <p>{{ month }} · 台灣時間</p>
            </div>
            <span class="legend"><i></i>營業額</span>
          </div>
          <Chart
            :option="trend"
            label="本月每日營業額折線圖"
            :description="
              '本月營業額 ' +
              money(report.revenue) +
              '。每天的數值可由匯出報表查看。'
            "
          />
          <div class="chart-summary">
            <span
              >本月共 <b>{{ number(report.orders) }}</b> 筆成交</span
            ><span
              >外帶
              <b
                >{{
                  report.orders
                    ? Math.round((report.takeawayOrders / report.orders) * 100)
                    : 0
                }}%</b
              >
              · 內用
              <b
                >{{
                  report.orders
                    ? 100 -
                      Math.round((report.takeawayOrders / report.orders) * 100)
                    : 0
                }}%</b
              ></span
            >
          </div>
        </section>
        <section class="panel top-panel">
          <div class="panel-heading">
            <div>
              <h2>今日人氣 TOP 5</h2>
              <p>{{ report.today }} · 依銷售份數排序</p>
            </div>
            <span class="tiny-tag">BEST SELLERS</span>
          </div>
          <div v-if="!report.topToday.length" class="empty-state compact">
            今天還沒有已付款的餐點。
          </div>
          <div
            v-for="(p, i) in report.topToday"
            :key="p.id"
            class="ranking-item"
          >
            <span class="rank" :class="{ first: i === 0 }">{{
              String(i + 1).padStart(2, "0")
            }}</span>
            <div>
              <div class="ranking-label">
                <b>{{ p.name }}</b
                ><span>{{ p.quantity }} 份</span>
              </div>
              <div class="bar-track">
                <span
                  :style="{ width: (p.quantity / todayMax) * 100 + '%' }"
                ></span>
              </div>
            </div>
          </div>
        </section>
      </div>
      <div class="report-secondary-grid">
        <section class="panel">
          <div class="panel-heading">
            <div>
              <h2>餐點分類佔比</h2>
              <p>依所選月份銷售金額計算</p>
            </div>
          </div>
          <Chart
            v-if="report.revenue"
            :option="categoryChart"
            label="餐點分類營業額圓環圖"
          />
          <div v-else class="empty-state compact">尚無銷售資料</div>
        </section>
        <section class="panel">
          <div class="panel-heading">
            <div>
              <h2>熱門消費時段</h2>
              <p>所選月份每小時成交訂單數</p>
            </div>
          </div>
          <Chart :option="hourly" label="24 小時成交訂單分布長條圖" />
        </section>
        <section class="panel payment-panel">
          <div class="panel-heading">
            <div>
              <h2>付款方式</h2>
              <p>已付款訂單</p>
            </div>
          </div>
          <div class="payment-split">
            <strong
              >{{
                report.orders
                  ? Math.round((report.onlineOrders / report.orders) * 100)
                  : 0
              }}<small>%</small></strong
            ><span>線上付款佔比</span>
          </div>
          <div class="payment-progress">
            <span
              :style="{
                width:
                  (report.orders
                    ? (report.onlineOrders / report.orders) * 100
                    : 0) + '%',
              }"
            ></span>
          </div>
          <div class="payment-label">
            <span><i class="online"></i>綠界信用卡</span
            ><b>{{ number(report.onlineOrders) }} 筆</b>
          </div>
          <div class="payment-label">
            <span><i class="cash"></i>現金</span
            ><b>{{ number(report.cashOrders) }} 筆</b>
          </div>
        </section>
      </div>
      <section class="panel branch-performance">
        <div class="panel-heading">
          <div>
            <h2>{{ global ? "分店績效比較" : "本店目標達成" }}</h2>
            <p>每月營收目標與實際成交表現</p>
          </div>
          <span class="muted">{{ report.branches.length }} 家門市</span>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>分店</th>
                <th>營業額</th>
                <th>成交訂單</th>
                <th>平均客單價</th>
                <th>月營收目標</th>
                <th>目標達成率</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="b in report.branches" :key="b.id">
                <td>
                  <b>{{ b.name }}</b>
                </td>
                <td class="numeric">
                  <b>{{ money(b.revenue) }}</b>
                </td>
                <td>{{ number(b.orders) }} 筆</td>
                <td>
                  {{ money(b.orders ? Math.round(b.revenue / b.orders) : 0) }}
                </td>
                <td>{{ money(b.target) }}</td>
                <td>
                  <div class="target-cell">
                    <div class="bar-track">
                      <span
                        :style="{ width: Math.min(b.achievement, 100) + '%' }"
                      ></span>
                    </div>
                    <b>{{ b.target ? b.achievement + "%" : "未設定" }}</b>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
      <section class="panel">
        <div class="panel-heading">
          <div>
            <h2>本月商品銷售明細</h2>
            <p>依歷史成交價格計算，菜單改價不影響既有業績</p>
          </div>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>商品</th>
                <th>分類</th>
                <th>銷售份數</th>
                <th>銷售金額</th>
                <th>商品毛利</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="p in report.products"
                :key="p.id + p.name + p.category"
              >
                <td>
                  <b>{{ p.name }}</b>
                </td>
                <td>{{ p.category }}</td>
                <td>{{ number(p.quantity) }}</td>
                <td>{{ money(p.revenue) }}</td>
                <td>{{ money(p.revenue - p.cost) }}</td>
              </tr>
              <tr v-if="!report.products.length">
                <td colspan="5">此月份尚無已付款餐點。</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
      <p class="report-note">
        統計以付款確認時間（Asia/Taipei）歸屬月份；排除未付款與取消訂單。商品毛利僅扣除成交時商品成本，不含人事、租金、稅費及金流手續費。
      </p></template
    >
  </div>
</template>
