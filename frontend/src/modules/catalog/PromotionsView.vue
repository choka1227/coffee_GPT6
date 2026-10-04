<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Pencil, Plus, Tags } from "lucide-vue-next";
import { api, send } from "../../shared/api";
import { notify } from "../../shared/notice";
import type { Branch, Product, PromotionRule } from "../../shared/types";
import Modal from "../../shared/Modal.vue";

const rules = ref<PromotionRule[]>([]);
const branches = ref<Branch[]>([]);
const products = ref<Product[]>([]);
const editing = ref<PromotionRule | null>(null);
const loading = ref(true);
const saving = ref(false);
const categories = computed(() =>
  [...new Set(products.value.map((product) => product.category))].sort(),
);

async function load() {
  loading.value = true;
  try {
    [rules.value, branches.value, products.value] = await Promise.all([
      api<PromotionRule[]>("/promotions"),
      api<Branch[]>("/branches"),
      api<Product[]>("/menu?manage=true"),
    ]);
  } catch (error) {
    notify((error as Error).message);
  } finally {
    loading.value = false;
  }
}

function add() {
  editing.value = {
    id: null,
    name: "",
    kind: "ITEM_PERCENT",
    percent: 10,
    nth: 0,
    targetKind: "PRODUCT",
    productId: products.value[0]?.id ?? null,
    category: null,
    branchId: null,
    startsAt: null,
    endsAt: null,
    active: true,
  };
}

function edit(rule: PromotionRule) {
  editing.value = { ...rule };
}

function selectKind() {
  if (!editing.value) return;
  editing.value.nth = editing.value.kind === "ITEM_PERCENT" ? 0 : Math.max(2, editing.value.nth || 2);
}

function selectTarget() {
  if (!editing.value) return;
  if (editing.value.targetKind === "PRODUCT") {
    editing.value.productId ??= products.value[0]?.id ?? null;
    editing.value.category = null;
  } else {
    editing.value.productId = null;
    editing.value.category ??= categories.value[0] ?? null;
  }
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

async function save() {
  if (!editing.value) return;
  saving.value = true;
  try {
    await send("/promotions", editing.value);
    editing.value = null;
    notify("品項促銷已儲存");
    await load();
  } catch (error) {
    notify((error as Error).message);
  } finally {
    saving.value = false;
  }
}

function kindLabel(rule: PromotionRule) {
  return rule.kind === "ITEM_PERCENT"
    ? `每件折 ${rule.percent}%`
    : `每第 ${rule.nth} 件折 ${rule.percent}%`;
}

function targetLabel(rule: PromotionRule) {
  if (rule.targetKind === "CATEGORY") return rule.category;
  return products.value.find((product) => product.id === rule.productId)?.name ?? rule.productId;
}

onMounted(load);
</script>

<template>
  <div class="page-pad">
    <div class="page-heading">
      <div>
        <span class="eyebrow">ITEM PROMOTIONS</span>
        <h1>品項促銷管理</h1>
        <p class="muted">設定每件折扣或第 N 件折扣，訂單將由後端自動擇優套用。</p>
      </div>
      <button class="btn primary" @click="add"><Plus :size="16" />新增促銷</button>
    </div>
    <div v-if="loading" class="loading-state">讀取中…</div>
    <div v-else class="card-grid">
      <article v-for="rule in rules" :key="rule.id!" class="admin-card">
        <div><Tags :size="18" /><strong>{{ targetLabel(rule) }}</strong>
          <span class="badge">{{ rule.active ? "啟用" : "停用" }}</span></div>
        <h3>{{ rule.name }}</h3>
        <p>{{ kindLabel(rule) }}</p>
        <p class="muted">{{ rule.branchId ? branches.find((b) => b.id === rule.branchId)?.name : "全鏈適用" }}</p>
        <button class="btn secondary" @click="edit(rule)"><Pencil :size="15" />編輯</button>
      </article>
    </div>
    <Modal v-if="editing" title="品項促銷" @close="!saving && (editing = null)">
      <form class="form-grid" @submit.prevent="save">
        <label>名稱<input v-model.trim="editing.name" maxlength="40" required /></label>
        <label>型態<select v-model="editing.kind" @change="selectKind">
          <option value="ITEM_PERCENT">每件折扣</option>
          <option value="NTH_PERCENT">第 N 件折扣</option>
        </select></label>
        <label>折扣百分比<input v-model.number="editing.percent" type="number" min="1"
          :max="editing.kind === 'ITEM_PERCENT' ? 90 : 100" required /></label>
        <label v-if="editing.kind === 'NTH_PERCENT'">N<input v-model.number="editing.nth"
          type="number" min="2" required /></label>
        <label>目標<select v-model="editing.targetKind" @change="selectTarget">
          <option value="PRODUCT">商品</option><option value="CATEGORY">分類</option>
        </select></label>
        <label v-if="editing.targetKind === 'PRODUCT'">商品<select v-model="editing.productId" required>
          <option v-for="product in products" :key="product.id!" :value="product.id">{{ product.name }}</option>
        </select></label>
        <label v-else>分類<select v-model="editing.category" required>
          <option v-for="category in categories" :key="category" :value="category">{{ category }}</option>
        </select></label>
        <label>適用分店<select v-model="editing.branchId"><option :value="null">全鏈</option>
          <option v-for="branch in branches" :key="branch.id!" :value="branch.id">{{ branch.name }}</option>
        </select></label>
        <label>開始時間<input type="datetime-local" :value="localTime(editing.startsAt)"
          @change="setTime('startsAt', $event)" /></label>
        <label>結束時間<input type="datetime-local" :value="localTime(editing.endsAt)"
          @change="setTime('endsAt', $event)" /></label>
        <label><input v-model="editing.active" type="checkbox" /> 啟用</label>
        <button class="btn primary" :disabled="saving">{{ saving ? "儲存中…" : "儲存" }}</button>
      </form>
    </Modal>
  </div>
</template>
