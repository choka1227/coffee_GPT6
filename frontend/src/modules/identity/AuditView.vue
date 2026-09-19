<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Search, ChevronRight } from "lucide-vue-next";
import { api } from "../../shared/api";
import { dateTime } from "../../shared/format";
import type { AuditEntry, AuditPage } from "../../shared/types";

const items = ref<AuditEntry[]>([]);
const action = ref("");
const actorId = ref("");
const branchId = ref("");
const nextCursor = ref<string | null>(null);
const loading = ref(false);
const error = ref("");

async function load(append = false) {
  loading.value = true;
  error.value = "";
  try {
    const query = new URLSearchParams({ limit: "50" });
    if (action.value.trim()) query.set("action", action.value.trim());
    if (actorId.value.trim()) query.set("actorId", actorId.value.trim());
    if (branchId.value.trim()) query.set("branchId", branchId.value.trim());
    if (append && nextCursor.value) query.set("cursor", nextCursor.value);
    const page = await api<AuditPage>(`/audit?${query}`);
    items.value = append ? [...items.value, ...page.items] : page.items;
    nextCursor.value = page.nextCursor;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}

onMounted(() => load());
</script>

<template>
  <div class="page-pad">
    <div class="page-heading">
      <div>
        <span class="eyebrow">WHO DID WHAT, AND WHEN</span>
        <h1>稽核軌跡</h1>
        <p class="muted">依權限範圍查詢重要營運操作，店長僅能查看所屬分店。</p>
      </div>
    </div>
    <section class="panel">
      <form class="filter-bar" @submit.prevent="load(false)">
        <label>動作<input v-model="action" placeholder="例如 ORDER_CASH" /></label>
        <label>操作者 ID<input v-model="actorId" placeholder="帳號 ID" /></label>
        <label>分店 ID<input v-model="branchId" placeholder="總部可選填" /></label>
        <button class="btn primary" :disabled="loading"><Search :size="17" />查詢</button>
      </form>
    </section>
    <div v-if="error" class="error-state">{{ error }}</div>
    <div v-else-if="loading && !items.length" class="loading-state">讀取稽核紀錄中…</div>
    <section v-else class="panel">
      <div class="table-wrap">
        <table>
          <thead><tr><th>時間</th><th>操作者</th><th>動作</th><th>分店</th><th>內容</th></tr></thead>
          <tbody>
            <tr v-for="entry in items" :key="entry.id">
              <td>{{ dateTime(entry.createdAt) }}</td>
              <td>{{ entry.actorName }}<small class="muted">{{ entry.actorId }}</small></td>
              <td><span class="tiny-tag">{{ entry.action }}</span></td>
              <td>{{ entry.branchId || "總部" }}</td>
              <td>{{ entry.summary }}</td>
            </tr>
            <tr v-if="!items.length"><td colspan="5" class="empty-state">沒有符合條件的稽核紀錄</td></tr>
          </tbody>
        </table>
      </div>
      <button v-if="nextCursor" class="btn secondary" :disabled="loading" @click="load(true)">
        載入更多<ChevronRight :size="16" />
      </button>
    </section>
  </div>
</template>

<style scoped>
.filter-bar { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)) auto; gap: 14px; align-items: end; }
label { display: grid; gap: 6px; }
td small { display: block; margin-top: 3px; }
.panel > .btn { margin-top: 18px; }
@media (max-width: 800px) { .filter-bar { grid-template-columns: 1fr; } }
</style>
