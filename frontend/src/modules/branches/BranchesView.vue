<script setup lang="ts">
import { ref, onMounted } from "vue";
import { Plus, MapPin, Phone, Store, Pencil, Target } from "lucide-vue-next";
import { api, send } from "../../shared/api";
import type { Branch } from "../../shared/types";
import { money } from "../../shared/format";
import { notify } from "../../shared/notice";
import Modal from "../../shared/Modal.vue";
const branches = ref<Branch[]>([]),
  editing = ref<Branch | null>(null),
  loading = ref(true),
  error = ref(""),
  saving = ref(false);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    branches.value = await api<Branch[]>("/branches?manage=true");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
onMounted(load);
function add() {
  editing.value = {
    id: null,
    name: "",
    address: "",
    phone: "",
    active: true,
    monthlyTarget: 300000,
  };
}
async function save() {
  saving.value = true;
  try {
    await send("/branches", editing.value);
    editing.value = null;
    notify("分店資料已儲存");
    await load();
  } catch (e) {
    notify((e as Error).message);
  } finally {
    saving.value = false;
  }
}
</script>
<template>
  <div class="page-pad">
    <div class="page-heading">
      <div>
        <span class="eyebrow">OUR NEIGHBORHOOD COFFEE SHOPS</span>
        <h1>每個街角，都有好咖啡。</h1>
        <p class="muted">分店管理 · 管理營業狀態、聯絡資訊與每月目標。</p>
      </div>
      <button class="btn primary" @click="add">
        <Plus :size="18" />新增分店
      </button>
    </div>
    <div class="mini-summary">
      <span
        ><b>{{ branches.length }}</b> 家分店</span
      ><span
        ><b>{{ branches.filter((b) => b.active).length }}</b> 家營業中</span
      ><span
        >總月營收目標
        <b>{{
          money(
            branches
              .filter((b) => b.active)
              .reduce((s, b) => s + b.monthlyTarget, 0),
          )
        }}</b></span
      >
    </div>
    <div v-if="loading" class="loading-state">讀取分店中…</div>
    <div v-else-if="error" class="error-state">
      {{ error }}<button class="btn secondary" @click="load">重試</button>
    </div>
    <div v-else-if="!branches.length" class="empty-state">
      <Store :size="40" />
      <h3>建立第一家分店</h3>
      <p>設定分店後，就能配置門市人員與開始點餐。</p>
      <button class="btn primary" @click="add">新增分店</button>
    </div>
    <div v-else class="branch-grid">
      <article v-for="(b, i) in branches" :key="b.id!" class="branch-card">
        <div class="branch-card-top">
          <span class="branch-number">{{ String(i + 1).padStart(2, "0") }}</span
          ><Store :size="42" :stroke-width="1" /><span
            class="status"
            :class="b.active ? 'paid' : 'cancelled'"
            >{{ b.active ? "營業中" : "暫停營業" }}</span
          >
        </div>
        <div class="branch-card-body">
          <h2>{{ b.name }}</h2>
          <p><MapPin :size="17" />{{ b.address || "尚未填寫地址" }}</p>
          <p><Phone :size="17" />{{ b.phone || "尚未填寫電話" }}</p>
          <div class="branch-target">
            <span><Target :size="17" />每月營收目標</span
            ><strong>{{ money(b.monthlyTarget) }}</strong>
          </div>
          <button class="btn secondary" @click="editing = { ...b }">
            <Pencil :size="16" />編輯分店
          </button>
        </div>
      </article>
    </div>
  </div>
  <Modal
    v-if="editing"
    :title="editing.id ? '編輯分店' : '新增分店'"
    @close="!saving && (editing = null)"
    ><form class="form-stack" @submit.prevent="save">
      <label
        >分店名稱<input
          v-model="editing.name"
          required
          maxlength="80"
          placeholder="例如：台北・中山店" /></label
      ><label
        >地址<input v-model="editing.address" maxlength="200" required /></label
      ><label
        >電話<input v-model="editing.phone" maxlength="30" type="tel" /></label
      ><label
        >每月營收目標（元）<input
          v-model.number="editing.monthlyTarget"
          type="number"
          min="0"
          max="2000000000"
          step="1"
          required /></label
      ><label class="checkbox-label"
        ><input v-model="editing.active" type="checkbox" />開放營業與點餐</label
      >
      <p class="form-hint">暫停營業後不接受新訂單，既有訂單與業績仍保留。</p>
      <button class="btn primary" :disabled="saving">
        {{ saving ? "儲存中…" : "儲存分店" }}
      </button>
    </form></Modal
  >
</template>
