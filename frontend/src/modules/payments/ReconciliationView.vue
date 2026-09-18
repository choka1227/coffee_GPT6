<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "../../shared/api";
import { money } from "../../shared/format";
import type { ReconciliationPending, ReconciliationAttempt } from "../../shared/types";

const items = ref<ReconciliationPending[]>([]);
const truncated = ref(false);
const attempts = ref<ReconciliationAttempt[]>([]);
const selected = ref("");
const busy = ref(false);
const message = ref("");
const labels: Record<string, string> = {
  CONFIRMED: "已確認付款", STILL_UNPAID: "尚未付款", AMOUNT_MISMATCH: "金額不符",
  SIMULATED: "模擬付款", QUERY_FAILED: "查核失敗",
};
const date = (value: number | null) => value == null ? "尚未查核" : new Date(value).toLocaleString("zh-TW", { timeZone: "Asia/Taipei" });
async function load() {
  busy.value = true;
  try {
    const result = await api<{ items: ReconciliationPending[]; truncated: boolean }>("/payments/reconciliation/pending");
    items.value = result.items;
    truncated.value = result.truncated;
  }
  catch (e) { message.value = (e as Error).message; }
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
  <section>
    <h1>金流對帳</h1>
    <p>查核逾靜默期的線上付款訂單；金額不符或查核失敗不會入帳，也不會自動取消訂單。</p>
    <button :disabled="busy" @click="load">重新整理</button>
    <p v-if="truncated">僅顯示前 200 筆。</p>
    <p role="status">{{ message }}</p>
    <p v-if="!busy && !items.length">目前沒有待查核訂單。</p>
    <div style="overflow-x:auto">
      <table>
        <thead><tr><th>訂單</th><th>分店</th><th>金額</th><th>建立時間</th><th>最近查核</th><th>次數</th><th>操作</th></tr></thead>
        <tbody><tr v-for="item in items" :key="item.orderId">
          <td>{{ item.orderId }}</td><td>{{ item.branchName }}</td><td>{{ money(item.total) }}</td>
          <td>{{ date(item.createdAt) }}</td>
          <td>{{ item.lastOutcome ? labels[item.lastOutcome] || item.lastOutcome : "尚未查核" }}<br>{{ date(item.lastQueriedAt) }}</td>
          <td>{{ item.attempts }}</td><td>
            <button :disabled="busy" @click="reconcile(item.orderId)">立即查核</button>
            <button :disabled="busy" @click="history(item.orderId)">歷程</button>
          </td>
        </tr></tbody>
      </table>
    </div>
    <section v-if="selected">
      <h2>{{ selected }} 查核歷程（最近 50 筆）</h2>
      <p v-if="!attempts.length">尚無紀錄。</p>
      <ul><li v-for="(attempt, index) in attempts" :key="index">
        {{ date(attempt.queriedAt) }} · {{ attempt.triggerSource === "MANUAL" ? "人工" : "排程" }} ·
        {{ labels[attempt.outcome] || attempt.outcome }}：{{ attempt.detail }}
      </li></ul>
    </section>
  </section>
</template>
