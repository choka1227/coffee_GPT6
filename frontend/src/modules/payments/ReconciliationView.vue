<script setup lang="ts">
import { onMounted, ref } from "vue";
import { RefreshCw, ShieldCheck } from "lucide-vue-next";
import { api } from "../../shared/api";
import { money } from "../../shared/format";
import type { PaymentAnomaly, ReconciliationPending, ReconciliationAttempt, Invoice, InvoicePage, InvoiceReversal, InvoiceReversalPage } from "../../shared/types";

const items = ref<ReconciliationPending[]>([]);
const truncated = ref(false);
const attempts = ref<ReconciliationAttempt[]>([]);
const selected = ref("");
const busy = ref(false);
const message = ref("");
const anomalyItems = ref<PaymentAnomaly[]>([]);
const anomalyTruncated = ref(false);
const invoiceItems = ref<Invoice[]>([]);
const invoiceTruncated = ref(false);
const reversalItems = ref<InvoiceReversal[]>([]);
const reversalTruncated = ref(false);
async function loadReversals() {
  try {
    const page = await api<InvoiceReversalPage>("/payments/invoices/reversals/failed");
    reversalItems.value = page.items; reversalTruncated.value = page.truncated;
  } catch (e) { message.value = (e as Error).message; }
}
async function retryReversal(id: string) {
  if (busy.value) return;
  busy.value = true;
  try {
    await api(`/payments/invoices/order/${encodeURIComponent(id)}/reversal/retry`, { method: "POST" });
    await loadReversals(); message.value = "已排入作廢／折讓重試，等待排程確認。";
  } catch (e) { message.value = (e as Error).message; }
  finally { busy.value = false; }
}
async function loadInvoices() {
  try {
    const page = await api<InvoicePage>("/payments/invoices/failed");
    invoiceItems.value = page.items;
    invoiceTruncated.value = page.truncated;
  } catch (e) { message.value = (e as Error).message; }
}
async function retryInvoice(id: string | null) {
  if (!id || busy.value) return;
  busy.value = true;
  try {
    await api(`/payments/invoices/order/${encodeURIComponent(id)}/retry`, { method: "POST" });
    await loadInvoices();
    await loadReversals();
    message.value = "已排入發票重試，等待排程確認。";
  } catch (e) { message.value = (e as Error).message; }
  finally { busy.value = false; }
}
const resolutionNotes = ref<Record<string, string>>({});
const labels: Record<string, string> = {
  CONFIRMED: "已確認付款", STILL_UNPAID: "尚未付款", AMOUNT_MISMATCH: "金額不符",
  SIMULATED: "模擬付款", QUERY_FAILED: "查核失敗",
};
const date = (value: number | null) => value == null ? "尚未查核" : new Date(value).toLocaleString("zh-TW", { timeZone: "Asia/Taipei" });
async function load() {
  busy.value = true;
  try {
    const [result, anomalyPage] = await Promise.all([
      api<{ items: ReconciliationPending[]; truncated: boolean }>("/payments/reconciliation/pending"),
      api<{ items: PaymentAnomaly[]; truncated: boolean }>("/payments/anomalies"),
    ]);
    items.value = result.items;
    truncated.value = result.truncated;
    anomalyItems.value = anomalyPage.items;
    anomalyTruncated.value = anomalyPage.truncated;
    await loadInvoices();
    await loadReversals();
  }
  catch (e) { message.value = (e as Error).message; }
  finally { busy.value = false; }
}
async function resolveAnomaly(id: string) {
  busy.value = true;
  try {
    await api(`/payments/anomalies/${id}/resolve`, {
      method: "POST",
      body: JSON.stringify({ note: resolutionNotes.value[id] || "" }),
    });
    message.value = "異常已標記為處理完成。";
    await load();
  } catch (e) { message.value = (e as Error).message; }
  finally { busy.value = false; }
}
async function history(id: string) {
  selected.value = id;
  attempts.value = [];
  try { attempts.value = (await api<{ attempts: ReconciliationAttempt[] }>(`/payments/reconciliation/${id}`)).attempts; }
  catch (e) { message.value = (e as Error).message; }
}
async function reconcile(id: string) {
  busy.value = true;
  try {
    const result = await api<{ detail: string }>(`/payments/reconciliation/${id}`, { method: "POST" });
    message.value = result.detail;
    await history(id);
    await load();
  } catch (e) { message.value = (e as Error).message; }
  finally { busy.value = false; }
}
onMounted(load);
</script>

<template>
  <div class="page-pad reconciliation-page">
    <div class="page-heading">
      <div>
        <span class="eyebrow">PAYMENT RECONCILIATION</span>
        <h1>金流對帳</h1>
        <p class="muted">查核逾靜默期的線上付款訂單；只有查單確認未付款或模擬付款時，才會依設定逾時作廢。</p>
      </div>
      <button class="btn secondary" :disabled="busy" @click="load"><RefreshCw :size="17" />重新整理</button>
    </div>
    <p v-if="message" class="reconciliation-notice" role="status">{{ message }}</p>
    <section v-if="reversalItems.length" class="panel reversal-failures">
      <div class="section-heading"><h2>發票作廢／折讓失敗</h2><span class="status pending_payment">{{ reversalItems.length }} 筆待處理</span></div>
      <p v-if="reversalTruncated" class="table-foot">僅顯示前 200 筆作廢／折讓。</p>
      <div class="card-grid panel-body">
      <article v-for="reversal in reversalItems" :key="reversal.orderId" class="admin-card">
        <p>訂單編號：{{ reversal.orderId }}</p><p>發票號碼：{{ reversal.invoiceNo || '尚未確認' }}</p>
        <p>方式：{{ reversal.kind === 'VOID' ? '作廢' : reversal.kind === 'ALLOWANCE' ? '折讓' : '待確認' }}</p>
        <p>失敗原因：{{ reversal.lastError }}</p>
        <button class="btn secondary" :disabled="busy" @click="retryReversal(reversal.orderId)">重試</button>
      </article>
      </div>
    </section>
    <section v-if="invoiceItems.length" class="panel invoice-failures">
      <div class="section-heading"><h2>發票開立失敗</h2><span class="status pending_payment">{{ invoiceItems.length }} 筆待處理</span></div>
      <p v-if="invoiceTruncated" class="table-foot">僅顯示前 200 筆發票。</p>
      <div class="card-grid panel-body">
      <article v-for="invoice in invoiceItems" :key="invoice.orderId || ''" class="admin-card">
        <p>訂單編號：{{ invoice.orderId }}</p><p>金額：{{ money(invoice.amount || 0) }}</p>
        <p>失敗原因：{{ invoice.lastError }}</p><p>最後更新時間：{{ date(invoice.updatedAt) }}</p>
        <button class="btn secondary" :disabled="busy" @click="retryInvoice(invoice.orderId)">重試</button>
      </article>
      </div>
    </section>
    <section v-if="anomalyItems.length" class="panel">
      <div class="section-heading"><h2>作廢後收到付款，需人工退款</h2><span class="status pending_payment">{{ anomalyItems.length }} 筆待處理</span></div>
      <p v-if="anomalyTruncated" class="table-foot">僅顯示前 200 筆異常。</p>
      <div class="card-grid panel-body">
      <article v-for="anomaly in anomalyItems" :key="anomaly.id" class="admin-card">
        <p>訂單編號：{{ anomaly.orderId }}</p>
        <p>訂單金額：{{ money(anomaly.orderTotal) }}</p>
        <p>綠界金額：{{ anomaly.tradeAmount == null ? "未提供" : money(anomaly.tradeAmount) }}</p>
        <p>來源：{{ anomaly.source === "CALLBACK" ? "付款通知" : "作廢後複查" }}</p>
        <p>偵測時間：{{ date(anomaly.detectedAt) }}</p>
        <label>處理說明<input v-model="resolutionNotes[anomaly.id]" maxlength="200" /></label>
        <button class="btn primary" :disabled="busy" @click="resolveAnomaly(anomaly.id)">已處理</button>
      </article>
      </div>
    </section>
    <section class="panel">
      <div class="section-heading"><h2>待查核訂單</h2><span class="status">{{ items.length }} 筆</span></div>
      <div v-if="busy && !items.length" class="loading-state">讀取待查核訂單中…</div>
      <div v-else-if="!items.length" class="empty-state">
        <ShieldCheck :size="32" /><h3>目前沒有待查核訂單。</h3><p>可使用重新整理，查看最新付款狀態。</p>
      </div>
      <div v-else class="table-wrap">
      <table>
        <thead><tr><th>訂單</th><th>分店</th><th>金額</th><th>建立時間</th><th>最近查核</th><th>次數</th><th>操作</th></tr></thead>
        <tbody><tr v-for="item in items" :key="item.orderId">
          <td><b>{{ item.orderId }}</b></td><td>{{ item.branchName }}</td><td class="numeric">{{ money(item.total) }}</td>
          <td>{{ date(item.createdAt) }}</td>
          <td>{{ item.lastOutcome ? labels[item.lastOutcome] || item.lastOutcome : "尚未查核" }}<br>{{ date(item.lastQueriedAt) }}</td>
          <td>{{ item.attempts }}</td><td>
            <div class="row-actions">
            <button class="btn primary small" :disabled="busy" @click="reconcile(item.orderId)">立即查核</button>
            <button class="btn secondary small" :disabled="busy" @click="history(item.orderId)">歷程</button>
            </div>
          </td>
        </tr></tbody>
      </table>
      </div>
      <p v-if="truncated" class="table-foot">僅顯示前 200 筆。</p>
    </section>
    <section v-if="selected" class="panel">
      <div class="section-heading"><div><h2>查核歷程</h2><p class="muted">{{ selected }} · 最近 50 筆</p></div></div>
      <p v-if="!attempts.length" class="empty-state compact">尚無紀錄。</p>
      <ul v-else class="reconciliation-history"><li v-for="(attempt, index) in attempts" :key="index">
        {{ date(attempt.queriedAt) }} · {{ attempt.triggerSource === "MANUAL" ? "人工" : "排程" }} ·
        {{ labels[attempt.outcome] || attempt.outcome }}：{{ attempt.detail }}
      </li></ul>
    </section>
  </div>
</template>

<style scoped>
.reconciliation-notice {
  padding: 16px 20px;
  margin-bottom: var(--section-gap);
  background: var(--surface-soft);
  border: 1px solid var(--line);
  border-left: 3px solid var(--green);
  border-radius: 8px;
  overflow-wrap: anywhere;
}
.reconciliation-history {
  margin: 0;
  padding: 0 var(--panel-padding);
  list-style: none;
}
.reconciliation-history li {
  padding: 16px 0;
  border-bottom: 1px solid var(--line);
  overflow-wrap: anywhere;
}
.reconciliation-history li:last-child {
  border-bottom: 0;
}
.section-heading p {
  overflow-wrap: anywhere;
}
</style>
