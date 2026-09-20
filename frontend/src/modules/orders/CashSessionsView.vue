<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ChevronRight, RefreshCw, WalletCards } from "lucide-vue-next";
import { api, send } from "../../shared/api";
import { dateTime, money } from "../../shared/format";
import { notify } from "../../shared/notice";
import type { Branch, CashSession, CashSessionPage } from "../../shared/types";
import { useAuth } from "../identity/store";

const auth = useAuth();
const branches = ref<Branch[]>([]);
const branchId = ref(auth.user?.branchId || "");
const current = ref<CashSession | null>(null);
const history = ref<CashSession[]>([]);
const nextCursor = ref<string | null>(null);
const unassignedCashRevenue = ref(0);
const unassignedOrderCount = ref(0);
const openingFloat = ref(0);
const countedAmount = ref(0);
const note = ref("");
const loading = ref(false);
const busy = ref(false);
const error = ref("");

const branchQuery = computed(() =>
  branchId.value ? `branchId=${encodeURIComponent(branchId.value)}` : "",
);
const varianceLabel = (value: number) =>
  value < 0 ? `短少 ${money(Math.abs(value))}` : `溢收 ${money(value)}`;

async function load(append = false) {
  if (!branchId.value) return;
  loading.value = true;
  error.value = "";
  try {
    const cursor = append && nextCursor.value
      ? `&cursor=${encodeURIComponent(nextCursor.value)}`
      : "";
    const [openSession, page] = await Promise.all([
      api<CashSession | null>(`/cash-sessions/current?${branchQuery.value}`),
      api<CashSessionPage>(`/cash-sessions?${branchQuery.value}&limit=20${cursor}`),
    ]);
    current.value = openSession;
    history.value = append ? [...history.value, ...page.items] : page.items;
    nextCursor.value = page.nextCursor;
    unassignedCashRevenue.value = page.unassignedCashRevenue;
    unassignedOrderCount.value = page.unassignedOrderCount;
    if (openSession) countedAmount.value = openSession.expectedAmount;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}

async function openSession() {
  busy.value = true;
  try {
    await send<CashSession>("/cash-sessions", {
      branchId: branchId.value,
      openingFloat: openingFloat.value,
      note: note.value,
    });
    note.value = "";
    notify("現金班別已開啟");
    await load();
  } catch (e) {
    notify((e as Error).message);
  } finally {
    busy.value = false;
  }
}

async function closeSession() {
  if (!current.value) return;
  busy.value = true;
  try {
    const closed = await send<CashSession>(
      `/cash-sessions/${encodeURIComponent(current.value.id)}/close`,
      { countedAmount: countedAmount.value, note: note.value },
    );
    notify(`交班完成，${varianceLabel(closed.variance || 0)}`);
    note.value = "";
    await load();
  } catch (e) {
    notify((e as Error).message);
  } finally {
    busy.value = false;
  }
}

onMounted(async () => {
  if (auth.user?.scope === "GLOBAL") {
    branches.value = await api<Branch[]>("/branches");
    if (!branchId.value) branchId.value = branches.value[0]?.id || "";
  }
  await load();
});
watch(branchId, () => load());
</script>

<template>
  <div class="page-pad">
    <div class="page-heading">
      <div><span class="eyebrow">CASH CONTROL</span><h1>現金班別</h1><p class="muted">開班、交班與短溢紀錄都由系統依收款資料計算。</p></div>
      <button class="btn secondary" :disabled="loading || !branchId" @click="load()"><RefreshCw :size="17" />重新整理</button>
    </div>
    <section v-if="auth.user?.scope === 'GLOBAL'" class="panel branch-picker">
      <label>分店<select v-model="branchId"><option v-for="branch in branches" :key="branch.id || ''" :value="branch.id || ''">{{ branch.name }}</option></select></label>
    </section>
    <div v-if="error" class="error-state">{{ error }}</div>
    <div v-else-if="loading && !history.length" class="loading-state">讀取班別資料中…</div>
    <template v-else-if="branchId">
      <section class="panel session-card">
        <div v-if="current" class="session-grid">
          <div><span class="eyebrow">OPEN SESSION</span><h2>目前班別</h2><p>{{ current.openedByName }} · {{ dateTime(current.openedAt) }}</p></div>
          <div class="metric"><small>準備金</small><b>{{ money(current.openingFloat) }}</b></div>
          <div class="metric"><small>現金收入（{{ current.orderCount }} 筆）</small><b>{{ money(current.cashRevenue) }}</b></div>
          <div class="metric primary-metric"><small>應有金額</small><b>{{ money(current.expectedAmount) }}</b></div>
          <form class="session-form" @submit.prevent="closeSession">
            <label>實點金額<input v-model.number="countedAmount" type="number" min="0" max="10000000" required /></label>
            <label>備註<input v-model="note" maxlength="200" /></label>
            <p class="variance" :class="{ zero: countedAmount === current.expectedAmount }">{{ varianceLabel(countedAmount - current.expectedAmount) }}</p>
            <button class="btn primary" :disabled="busy">{{ busy ? "交班中…" : "確認交班" }}</button>
          </form>
        </div>
        <form v-else class="open-form" @submit.prevent="openSession">
          <div><WalletCards :size="36" /><h2>尚未開班</h2><p class="muted">輸入抽屜準備金後開始記錄本班現金。</p></div>
          <label>準備金<input v-model.number="openingFloat" type="number" min="0" max="1000000" required /></label>
          <label>備註<input v-model="note" maxlength="200" /></label>
          <button class="btn primary" :disabled="busy">{{ busy ? "開班中…" : "開始班別" }}</button>
        </form>
      </section>
      <section v-if="unassignedOrderCount" class="panel warning-card"><b>未歸班現金</b><span>{{ unassignedOrderCount }} 筆，共 {{ money(unassignedCashRevenue) }}</span><small>這些收款發生時沒有開啟中的班別，已獨立列出供核對。</small></section>
      <section class="panel">
        <h2>班別歷史</h2>
        <div class="table-wrap"><table><thead><tr><th>開班時間</th><th>操作人員</th><th>現金收入</th><th>應有／實點</th><th>短溢</th></tr></thead><tbody>
          <tr v-for="item in history" :key="item.id"><td>{{ dateTime(item.openedAt) }}<small>{{ item.status === 'OPEN' ? '進行中' : `交班 ${dateTime(item.closedAt!)}` }}</small></td><td>{{ item.openedByName }}<small v-if="item.closedByName">交班：{{ item.closedByName }}</small></td><td>{{ money(item.cashRevenue) }}<small>{{ item.orderCount }} 筆</small></td><td>{{ money(item.expectedAmount) }}<small v-if="item.countedAmount !== null">實點 {{ money(item.countedAmount) }}</small></td><td><span v-if="item.variance !== null" class="variance">{{ varianceLabel(item.variance) }}</span><span v-else>—</span></td></tr>
          <tr v-if="!history.length"><td colspan="5" class="empty-state">尚無班別紀錄</td></tr>
        </tbody></table></div>
        <button v-if="nextCursor" class="btn secondary" :disabled="loading" @click="load(true)">載入更多<ChevronRight :size="16" /></button>
      </section>
    </template>
  </div>
</template>

<style scoped>
.branch-picker { margin-bottom: 18px; max-width: 420px; }
.branch-picker label, .session-form label, .open-form label { display: grid; gap: 6px; }
.session-grid { display: grid; grid-template-columns: 1.4fr repeat(3, 1fr); gap: 20px; align-items: center; }
.metric { display: grid; gap: 6px; }
.metric b { font-size: 1.35rem; }
.primary-metric { color: var(--accent); }
.session-form { grid-column: 1 / -1; display: grid; grid-template-columns: 1fr 1fr auto auto; gap: 14px; align-items: end; }
.open-form { display: grid; grid-template-columns: 1.4fr 1fr 1fr auto; gap: 18px; align-items: end; }
.variance { color: #b42318; font-weight: 700; }
.variance.zero { color: var(--muted); }
.warning-card { display: grid; grid-template-columns: auto auto 1fr; gap: 16px; margin-top: 18px; border-color: #f4b740; }
td small { display: block; margin-top: 4px; color: var(--muted); }
.panel + .panel { margin-top: 18px; }
.panel > .btn { margin-top: 18px; }
@media (max-width: 900px) { .session-grid, .open-form, .session-form { grid-template-columns: 1fr; } .session-form { grid-column: auto; } .warning-card { grid-template-columns: 1fr; } }
</style>
