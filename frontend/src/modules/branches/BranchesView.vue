<script setup lang="ts">
import { ref, onMounted } from "vue";
import {
  Plus,
  MapPin,
  Phone,
  Store,
  Pencil,
  Target,
  Clock,
  Trash2,
} from "lucide-vue-next";
import { api, send } from "../../shared/api";
import type {
  Branch,
  BranchHours,
  BranchHoursResponse,
} from "../../shared/types";
import { minuteTime, money } from "../../shared/format";
import { notify } from "../../shared/notice";
import Modal from "../../shared/Modal.vue";
const branches = ref<Branch[]>([]),
  editing = ref<Branch | null>(null),
  loading = ref(true),
  error = ref(""),
  saving = ref(false),
  hoursBranch = ref<Branch | null>(null),
  hours = ref<BranchHours[]>([]),
  hoursLoading = ref(false),
  hoursSaving = ref(false);
const dayNames = [
  "",
  "星期一",
  "星期二",
  "星期三",
  "星期四",
  "星期五",
  "星期六",
  "星期日",
];
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
function parseMinute(value: string, close = false) {
  if (close && value === "24:00") return 1440;
  const match = /^(\d{2}):(\d{2})$/.exec(value);
  if (!match) throw new Error(close ? "結束時間不正確" : "開始時間不正確");
  const hour = Number(match[1]),
    minute = Number(match[2]);
  if (hour > 23 || minute > 59)
    throw new Error(close ? "結束時間不正確" : "開始時間不正確");
  return hour * 60 + minute;
}
async function editHours(branch: Branch) {
  hoursBranch.value = branch;
  hoursLoading.value = true;
  try {
    const result = await api<BranchHoursResponse>(
      `/branches/${branch.id}/hours`,
    );
    hours.value = result.hours.map((period) => ({ ...period }));
  } catch (e) {
    notify((e as Error).message);
    hoursBranch.value = null;
  } finally {
    hoursLoading.value = false;
  }
}
function dayHours(day: number) {
  return hours.value.filter((period) => period.dayOfWeek === day);
}
function addHours(day: number) {
  hours.value.push({ dayOfWeek: day, openMinute: 540, closeMinute: 1260 });
}
function removeHours(period: BranchHours) {
  hours.value = hours.value.filter((candidate) => candidate !== period);
}
async function saveHours() {
  if (!hoursBranch.value?.id) return;
  hoursSaving.value = true;
  try {
    const result = await send<BranchHoursResponse>(
      `/branches/${hoursBranch.value.id}/hours`,
      { hours: hours.value },
      "PUT",
    );
    hours.value = result.hours;
    notify("營業時間已儲存");
    hoursBranch.value = null;
  } catch (e) {
    notify((e as Error).message);
  } finally {
    hoursSaving.value = false;
  }
}
function updateTime(
  period: BranchHours,
  field: "openMinute" | "closeMinute",
  event: Event,
) {
  try {
    period[field] = parseMinute(
      (event.target as HTMLInputElement).value,
      field === "closeMinute",
    );
  } catch (e) {
    notify((e as Error).message);
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
          <button class="btn secondary" @click="editHours(b)">
            <Clock :size="16" />營業時間
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
  <Modal
    v-if="hoursBranch"
    :title="`${hoursBranch.name}・營業時間`"
    wide
    @close="!hoursSaving && (hoursBranch = null)"
  >
    <div v-if="hoursLoading" class="loading-state">讀取營業時間中…</div>
    <form v-else class="hours-form" @submit.prevent="saveHours">
      <p v-if="hours.length === 0" class="hours-empty">
        目前未設定營業時間，視為 24 小時營業。
      </p>
      <div v-for="day in 7" :key="day" class="hours-day">
        <div class="hours-day-title">
          <strong>{{ dayNames[day] }}</strong>
          <button class="btn secondary" type="button" @click="addHours(day)">
            <Plus :size="15" />新增時段
          </button>
        </div>
        <p v-if="dayHours(day).length === 0" class="muted">本日未設定時段</p>
        <div
          v-for="(period, index) in dayHours(day)"
          :key="`${period.dayOfWeek}-${period.openMinute}-${period.closeMinute}-${index}`"
          class="hours-row"
        >
          <input
            type="text"
            inputmode="numeric"
            pattern="[0-2][0-9]:[0-5][0-9]"
            :value="minuteTime(period.openMinute)"
            aria-label="開始時間"
            @change="updateTime(period, 'openMinute', $event)"
          />
          <span>至</span>
          <input
            type="text"
            inputmode="numeric"
            pattern="(?:[0-2][0-9]):[0-5][0-9]"
            :value="minuteTime(period.closeMinute)"
            aria-label="結束時間"
            @change="updateTime(period, 'closeMinute', $event)"
          />
          <button
            class="icon-btn"
            type="button"
            aria-label="刪除時段"
            @click="removeHours(period)"
          >
            <Trash2 :size="17" />
          </button>
        </div>
      </div>
      <p class="form-hint">結束時間早於或等於開始時間時，代表營業至隔日。</p>
      <button class="btn primary" :disabled="hoursSaving">
        {{ hoursSaving ? "儲存中…" : "儲存營業時間" }}
      </button>
    </form>
  </Modal>
</template>

<style scoped>
.branch-card-body > .btn + .btn {
  margin-left: 0.5rem;
}
.hours-form {
  display: grid;
  gap: 1rem;
}
.hours-empty {
  margin: 0;
  padding: 0.8rem 1rem;
  border-radius: 0.6rem;
  background: #fff7df;
  color: #6a4a00;
}
.hours-day {
  border-bottom: 1px solid var(--border, #dedbd3);
  padding-bottom: 0.8rem;
}
.hours-day-title,
.hours-row {
  display: flex;
  align-items: center;
  gap: 0.65rem;
}
.hours-day-title {
  justify-content: space-between;
}
.hours-row {
  margin-top: 0.6rem;
}
.hours-row input {
  min-width: 8rem;
}
.hours-day .muted {
  margin: 0.55rem 0 0;
}
</style>
