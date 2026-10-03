<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Clock, Pencil, Plus, Trash2 } from "lucide-vue-next";
import { useAuth } from "../identity/store";
import { api, send } from "../../shared/api";
import { minuteTime } from "../../shared/format";
import { notify } from "../../shared/notice";
import type {
  BranchDayOverride,
  BranchDayOverridesResponse,
  BranchHours,
  BranchHoursResponse,
} from "../../shared/types";
import {
  dayWindowLimits,
  formatOnDate,
  inputDate,
  isWithinDayWindow,
  overrideSummary,
  sortOverrides,
} from "./overrides";

type OverrideMode = "closed" | "weekly" | "custom";

const auth = useAuth();
const loading = ref(true);
const saving = ref(false);
const error = ref("");
const hours = ref<BranchHoursResponse | null>(null);
const overrides = ref<BranchDayOverride[]>([]);
const draft = ref<BranchDayOverride | null>(null);
const overrideDate = ref("");
const mode = ref<OverrideMode>("closed");
const lastOrderEnabled = ref(false);
const lastOrderMinutes = ref(0);
const dayNames = ["", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"];

function dateValue(value: string): number {
  return Number(value.replaceAll("-", ""));
}

function taipeiToday(): number {
  const value = new Date(Date.now() + 8 * 60 * 60 * 1000);
  return (
    value.getUTCFullYear() * 10000 +
    (value.getUTCMonth() + 1) * 100 +
    value.getUTCDate()
  );
}

const todayOnDate = taipeiToday();
const limits = dayWindowLimits(todayOnDate);

async function load() {
  const branchId = auth.user?.branchId;
  if (!branchId) {
    error.value = "帳號未綁定分店，請聯絡總部管理員";
    loading.value = false;
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    const [hoursResult, overridesResult] = await Promise.all([
      api<BranchHoursResponse>(`/branches/${branchId}/hours`),
      api<BranchDayOverridesResponse>(`/branches/${branchId}/hour-overrides`),
    ]);
    hours.value = hoursResult;
    overrides.value = sortOverrides(
      overridesResult.overrides.filter((value) =>
        isWithinDayWindow(value.onDate, todayOnDate),
      ),
    );
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}

onMounted(load);

function weeklyHours(day: number): BranchHours[] {
  return hours.value?.hours.filter((period) => period.dayOfWeek === day) ?? [];
}

function newOverride() {
  overrideDate.value = limits.min;
  mode.value = "closed";
  lastOrderEnabled.value = false;
  lastOrderMinutes.value = hours.value?.lastOrderMinutes ?? 0;
  draft.value = {
    onDate: dateValue(overrideDate.value),
    dayOfWeek: 1,
    closed: true,
    note: "",
    hours: [],
    lastOrderMinutes: null,
  };
}

function editOverride(value: BranchDayOverride) {
  draft.value = { ...value, hours: value.hours.map((period) => ({ ...period })) };
  overrideDate.value = inputDate(value.onDate);
  mode.value = value.closed ? "closed" : value.hours.length ? "custom" : "weekly";
  lastOrderEnabled.value = value.lastOrderMinutes !== null;
  lastOrderMinutes.value = value.lastOrderMinutes ?? hours.value?.lastOrderMinutes ?? 0;
}

function setMode(value: OverrideMode) {
  if (!draft.value) return;
  mode.value = value;
  if (value === "closed") {
    draft.value.closed = true;
    draft.value.hours = [];
    lastOrderEnabled.value = false;
  } else if (value === "weekly") {
    draft.value.closed = false;
    draft.value.hours = [];
    lastOrderEnabled.value = true;
  } else {
    draft.value.closed = false;
    if (!draft.value.hours.length)
      draft.value.hours = [{ dayOfWeek: 1, openMinute: 540, closeMinute: 1260 }];
  }
}

function addHours() {
  if (!draft.value || draft.value.hours.length >= 4) return;
  draft.value.hours.push({ dayOfWeek: 1, openMinute: 540, closeMinute: 1260 });
}

function removeHours(period: BranchHours) {
  if (!draft.value) return;
  draft.value.hours = draft.value.hours.filter((candidate) => candidate !== period);
}

function updateTime(
  period: BranchHours,
  field: "openMinute" | "closeMinute",
  event: Event,
) {
  const raw = (event.target as HTMLInputElement).value;
  if (field === "closeMinute" && raw === "24:00") {
    period[field] = 1440;
    return;
  }
  const match = /^(\d{2}):(\d{2})$/.exec(raw);
  if (!match || Number(match[1]) > 23 || Number(match[2]) > 59) {
    notify(field === "closeMinute" ? "結束時間不正確" : "開始時間不正確");
    return;
  }
  period[field] = Number(match[1]) * 60 + Number(match[2]);
}

async function saveOverride() {
  const branchId = auth.user?.branchId;
  if (!branchId || !draft.value || !overrideDate.value) return;
  saving.value = true;
  try {
    const onDate = dateValue(overrideDate.value);
    const saved = await send<BranchDayOverride>(
      `/branches/${branchId}/hour-overrides/${onDate}`,
      {
        closed: mode.value === "closed",
        note: draft.value.note,
        hours: mode.value === "custom" ? draft.value.hours : [],
        lastOrderMinutes:
          mode.value === "closed" || !lastOrderEnabled.value
            ? null
            : lastOrderMinutes.value,
      },
      "PUT",
    );
    overrides.value = sortOverrides([
      ...overrides.value.filter((value) => value.onDate !== saved.onDate),
      saved,
    ]);
    draft.value = null;
    notify("本店營業設定已儲存");
  } catch (e) {
    notify((e as Error).message);
  } finally {
    saving.value = false;
  }
}

async function deleteOverride(value: BranchDayOverride) {
  const branchId = auth.user?.branchId;
  if (!branchId) return;
  saving.value = true;
  try {
    await send(
      `/branches/${branchId}/hour-overrides/${value.onDate}`,
      undefined,
      "DELETE",
    );
    overrides.value = overrides.value.filter(
      (candidate) => candidate.onDate !== value.onDate,
    );
    if (draft.value?.onDate === value.onDate) draft.value = null;
    notify("本店營業設定已刪除");
  } catch (e) {
    notify((e as Error).message);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <div class="page-pad branch-day-page">
    <div class="page-heading">
      <div>
        <span class="eyebrow">BRANCH DAILY OPERATIONS</span>
        <h1>本店營業設定</h1>
        <p class="muted">設定今日起 14 天內的公休、特殊時段或本日最後點餐。</p>
      </div>
      <button v-if="!loading && !error" class="btn primary" @click="newOverride">
        <Plus :size="18" />新增單日設定
      </button>
    </div>

    <div v-if="loading" class="loading-state">讀取本店營業設定中…</div>
    <div v-else-if="error" class="error-state">
      {{ error }}<button class="btn secondary" @click="load">重試</button>
    </div>
    <template v-else-if="hours">
      <section class="day-card weekly-card">
        <div>
          <Clock :size="24" />
          <div>
            <h2>每週營業時間</h2>
            <p class="muted">由總部維護；本店預設最後點餐為打烊前 {{ hours.lastOrderMinutes }} 分鐘。</p>
          </div>
        </div>
        <div class="weekly-grid">
          <div v-for="day in 7" :key="day">
            <strong>{{ dayNames[day] }}</strong>
            <span v-if="weeklyHours(day).length === 0">未設定時段</span>
            <span v-for="(period, index) in weeklyHours(day)" :key="index">
              {{ minuteTime(period.openMinute) }}–{{ minuteTime(period.closeMinute) }}
            </span>
          </div>
        </div>
      </section>

      <section class="day-card">
        <h2>已設定日期</h2>
        <p v-if="overrides.length === 0" class="muted">目前沒有單日設定。</p>
        <div v-for="value in overrides" :key="value.onDate" class="override-row">
          <div>
            <strong>{{ formatOnDate(value.onDate) }}</strong>
            <span>{{ overrideSummary(value) }}</span>
          </div>
          <div class="override-actions">
            <button class="btn secondary" type="button" @click="editOverride(value)">
              <Pencil :size="15" />編輯
            </button>
            <button
              class="icon-btn"
              type="button"
              aria-label="刪除單日設定"
              :disabled="saving"
              @click="deleteOverride(value)"
            >
              <Trash2 :size="17" />
            </button>
          </div>
        </div>
      </section>

      <form v-if="draft" class="day-card override-editor" @submit.prevent="saveOverride">
        <h2>單日設定</h2>
        <label>日期<input v-model="overrideDate" type="date" :min="limits.min" :max="limits.max" required /></label>
        <label>備註<input v-model="draft.note" maxlength="40" placeholder="例如：臨時調整" /></label>
        <div class="override-kind" role="group" aria-label="單日設定類型">
          <label class="checkbox-label"><input type="radio" :checked="mode === 'closed'" @change="setMode('closed')" />整天公休</label>
          <label class="checkbox-label"><input type="radio" :checked="mode === 'weekly'" @change="setMode('weekly')" />沿用每週時段</label>
          <label class="checkbox-label"><input type="radio" :checked="mode === 'custom'" @change="setMode('custom')" />自訂時段</label>
        </div>

        <template v-if="mode !== 'closed'">
          <label v-if="mode === 'custom'" class="checkbox-label">
            <input v-model="lastOrderEnabled" type="checkbox" />覆寫本日最後點餐
          </label>
          <label v-if="lastOrderEnabled">
            本日最後點餐（打烊前分鐘）
            <input v-model.number="lastOrderMinutes" type="number" min="0" max="120" step="5" required />
          </label>
        </template>

        <template v-if="mode === 'custom'">
          <div v-for="(period, index) in draft.hours" :key="index" class="hours-row">
            <input type="text" inputmode="numeric" :value="minuteTime(period.openMinute)" aria-label="單日開始時間" @change="updateTime(period, 'openMinute', $event)" />
            <span>至</span>
            <input type="text" inputmode="numeric" :value="minuteTime(period.closeMinute)" aria-label="單日結束時間" @change="updateTime(period, 'closeMinute', $event)" />
            <button class="icon-btn" type="button" aria-label="刪除單日時段" @click="removeHours(period)"><Trash2 :size="17" /></button>
          </div>
          <button class="btn secondary" type="button" :disabled="draft.hours.length >= 4" @click="addHours"><Plus :size="15" />新增時段</button>
        </template>

        <div class="override-actions">
          <button class="btn primary" :disabled="saving">{{ saving ? "儲存中…" : "儲存單日設定" }}</button>
          <button class="btn secondary" type="button" @click="draft = null">取消</button>
        </div>
      </form>
    </template>
  </div>
</template>

<style scoped>
.branch-day-page {
  display: grid;
  gap: 1rem;
}
.day-card {
  display: grid;
  gap: 0.8rem;
  padding: 1rem;
  border: 1px solid var(--border, #dedbd3);
  border-radius: 0.8rem;
  background: var(--surface, #fff);
}
.day-card h2,
.day-card p {
  margin: 0;
}
.weekly-card > div:first-child,
.override-row,
.override-actions,
.override-kind,
.hours-row {
  display: flex;
  align-items: center;
  gap: 0.65rem;
}
.weekly-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
  gap: 0.65rem;
}
.weekly-grid > div,
.override-row > div:first-child {
  display: grid;
  gap: 0.2rem;
}
.weekly-grid > div {
  padding: 0.65rem;
  border-radius: 0.5rem;
  background: var(--surface-soft, #f7f5ef);
}
.override-row {
  justify-content: space-between;
  padding: 0.7rem;
  border: 1px solid var(--border, #dedbd3);
  border-radius: 0.6rem;
}
.override-editor {
  max-width: 48rem;
}
.hours-row input {
  min-width: 8rem;
}
@media (max-width: 640px) {
  .override-row,
  .override-kind {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
