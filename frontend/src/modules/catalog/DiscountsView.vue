<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Plus, Pencil, Tags } from "lucide-vue-next";
import { api, send } from "../../shared/api";
import { money } from "../../shared/format";
import { notify } from "../../shared/notice";
import type { Branch, DiscountRule } from "../../shared/types";
import Modal from "../../shared/Modal.vue";

const rules = ref<DiscountRule[]>([]), branches = ref<Branch[]>([]);
const editing = ref<DiscountRule | null>(null), loading = ref(true), saving = ref(false);

async function load() {
  loading.value = true;
  try {
    [rules.value, branches.value] = await Promise.all([
      api<DiscountRule[]>("/discounts"), api<Branch[]>("/branches"),
    ]);
  } catch (error) { notify((error as Error).message); }
  finally { loading.value = false; }
}
function add() {
  editing.value = { id: null, code: "", name: "", kind: "PERCENT", percent: 10,
    amount: 0, minSubtotal: 0, branchId: null, startsAt: null, endsAt: null,
    maxRedemptions: null, redeemedCount: 0, active: true };
}
function edit(rule: DiscountRule) { editing.value = { ...rule }; }
function selectKind() {
  if (!editing.value) return;
  if (editing.value.kind === "PERCENT") editing.value.amount = 0;
  else editing.value.percent = 0;
}
async function save() {
  if (!editing.value) return;
  saving.value = true;
  try {
    await send("/discounts", editing.value);
    editing.value = null;
    notify("優惠碼已儲存");
    await load();
  } catch (error) { notify((error as Error).message); }
  finally { saving.value = false; }
}
function value(rule: DiscountRule) {
  return rule.kind === "PERCENT" ? `${rule.percent}%` : money(rule.amount);
}
function usage(rule: DiscountRule) {
  return `${rule.redeemedCount} / ${rule.maxRedemptions ?? "不限"}`;
}
function localTime(value: number | null) {
  if (value == null) return "";
  const date = new Date(value - new Date(value).getTimezoneOffset() * 60_000);
  return date.toISOString().slice(0, 16);
}
function setTime(field: "startsAt" | "endsAt", event: Event) {
  if (!editing.value) return;
  const value = (event.target as HTMLInputElement).value;
  editing.value[field] = value ? new Date(value).getTime() : null;
}
function setMaximum(event: Event) {
  if (!editing.value) return;
  const value = (event.target as HTMLInputElement).value;
  editing.value.maxRedemptions = value ? Number(value) : null;
}
onMounted(load);
</script>

<template>
  <div class="page-pad">
    <div class="page-heading"><div><span class="eyebrow">PROMOTIONS</span>
      <h1>優惠碼管理</h1><p class="muted">優惠碼由後端計算折抵，並記錄已用次數。</p></div>
      <button class="btn primary" @click="add"><Plus :size="16" />新增優惠碼</button>
    </div>
    <div v-if="loading" class="loading-state">讀取中…</div>
    <div v-else class="card-grid">
      <article v-for="rule in rules" :key="rule.id" class="admin-card">
        <div><Tags :size="18" /><strong>{{ rule.code }}</strong>
          <span class="badge">{{ rule.active ? "啟用" : "停用" }}</span></div>
        <h3>{{ rule.name }}</h3>
        <p>{{ value(rule) }} · 最低消費 {{ money(rule.minSubtotal) }}</p>
        <p class="muted">已用／上限：{{ usage(rule) }}</p>
        <button class="btn secondary" @click="edit(rule)"><Pencil :size="15" />編輯</button>
      </article>
    </div>
    <Modal v-if="editing" title="優惠碼" @close="!saving && (editing = null)">
      <form class="form-grid" @submit.prevent="save">
        <label>代碼<input v-model.trim="editing.code" maxlength="20" required /></label>
        <label>名稱<input v-model.trim="editing.name" maxlength="40" required /></label>
        <label>類型<select v-model="editing.kind" @change="selectKind">
          <option value="PERCENT">百分比</option><option value="AMOUNT">定額</option>
        </select></label>
        <label v-if="editing.kind === 'PERCENT'">折扣百分比<input v-model.number="editing.percent" type="number" min="1" max="90" /></label>
        <label v-else>折抵金額<input v-model.number="editing.amount" type="number" min="1" max="1000000" /></label>
        <label>最低消費<input v-model.number="editing.minSubtotal" type="number" min="0" max="1000000" /></label>
        <label>適用分店<select v-model="editing.branchId"><option :value="null">全鏈</option>
          <option v-for="branch in branches" :key="branch.id!" :value="branch.id">{{ branch.name }}</option>
        </select></label>
        <label>使用上限（空白為不限）<input :value="editing.maxRedemptions ?? ''" type="number" min="1" @input="setMaximum" /></label>
        <label>開始時間<input type="datetime-local" :value="localTime(editing.startsAt)" @change="setTime('startsAt', $event)" /></label>
        <label>結束時間<input type="datetime-local" :value="localTime(editing.endsAt)" @change="setTime('endsAt', $event)" /></label>
        <label><input v-model="editing.active" type="checkbox" /> 啟用</label>
        <button class="btn primary" :disabled="saving">{{ saving ? "儲存中…" : "儲存" }}</button>
      </form>
    </Modal>
  </div>
</template>
