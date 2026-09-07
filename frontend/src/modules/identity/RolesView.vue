<script setup lang="ts">
import { ref, onMounted, computed } from "vue";
import { Plus, ShieldCheck, Pencil, Check, LockKeyhole } from "lucide-vue-next";
import { api, send } from "../../shared/api";
import type { Role } from "../../shared/types";
import { permissions } from "../../shared/format";
import { notify } from "../../shared/notice";
import Modal from "../../shared/Modal.vue";
const roles = ref<Role[]>([]),
  allPermissions = ref<string[]>([]),
  editing = ref<Role | null>(null),
  isNew = ref(false),
  loading = ref(true),
  error = ref(""),
  saving = ref(false);
const scopeNames: Record<string, string> = {
  SELF: "僅本人訂單",
  BRANCH: "所屬分店",
  GLOBAL: "所有分店",
};
const globalOnly = [
  "ACCOUNT_MANAGE",
  "ROLE_MANAGE",
  "BRANCH_MANAGE",
  "MENU_MANAGE",
  "REPORT_ALL",
];
const allowed = computed(() =>
  allPermissions.value.filter((p) =>
    editing.value?.scope === "SELF"
      ? p === "ORDER_CREATE"
      : editing.value?.scope === "BRANCH"
        ? !globalOnly.includes(p)
        : p !== "REPORT_STORE",
  ),
);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const data = await api<{ roles: Role[]; permissions: string[] }>(
      "/admin/roles",
    );
    roles.value = data.roles;
    allPermissions.value = data.permissions;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
onMounted(load);
function add() {
  isNew.value = true;
  editing.value = { code: "", name: "", scope: "BRANCH", permissions: [] };
}
function edit(r: Role) {
  isNew.value = false;
  editing.value = { ...r, permissions: [...r.permissions] };
}
function scopeChange() {
  if (editing.value)
    editing.value.permissions = editing.value.permissions.filter((p) =>
      allowed.value.includes(p),
    );
}
async function save() {
  saving.value = true;
  try {
    await send("/admin/roles", editing.value);
    editing.value = null;
    notify("角色與功能配置已儲存");
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
        <span class="eyebrow">THE RIGHT ACCESS, FOR EVERY ROLE</span>
        <h1>清楚分工，安心協作。</h1>
        <p class="muted">角色與功能配置 · 定義能做什麼，以及能看到哪些分店。</p>
      </div>
      <button class="btn primary" @click="add">
        <Plus :size="18" />新增角色
      </button>
    </div>
    <div v-if="loading" class="loading-state">讀取角色中…</div>
    <div v-else-if="error" class="error-state">
      {{ error }}<button class="btn secondary" @click="load">重試</button>
    </div>
    <template v-else
      ><div class="roles-grid">
        <article v-for="r in roles" :key="r.code" class="role-card">
          <div>
            <ShieldCheck :size="25" /><span class="tiny-tag">{{ r.code }}</span>
          </div>
          <h2>{{ r.name }}</h2>
          <p>{{ scopeNames[r.scope] }} · {{ r.permissions.length }} 項功能</p>
          <button v-if="r.code !== 'HQ'" class="btn secondary" @click="edit(r)">
            <Pencil :size="16" />配置功能</button
          ><span v-else class="locked-role"
            ><LockKeyhole :size="15" />保留完整管理權限</span
          >
        </article>
      </div>
      <section class="panel">
        <div class="panel-heading">
          <div>
            <h2>功能權限矩陣</h2>
            <p>畫面與 API 同時依角色授權</p>
          </div>
        </div>
        <div class="table-wrap">
          <table class="permission-matrix">
            <thead>
              <tr>
                <th>功能</th>
                <th v-for="r in roles" :key="r.code">{{ r.name }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in allPermissions" :key="p">
                <td>{{ permissions[p] || p }}</td>
                <td v-for="r in roles" :key="r.code">
                  <Check
                    v-if="r.permissions.includes(p)"
                    :size="18"
                    aria-label="已授權"
                  /><span v-else aria-label="未授權">—</span>
                </td>
              </tr>
              <tr>
                <td>資料範圍</td>
                <td v-for="r in roles" :key="r.code">
                  {{ scopeNames[r.scope] }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section></template
    >
  </div>
  <Modal
    v-if="editing"
    :title="isNew ? '新增角色' : '配置角色功能'"
    @close="!saving && (editing = null)"
    ><form class="form-stack" @submit.prevent="save">
      <label
        >角色代碼<input
          v-model="editing.code"
          pattern="[A-Z][A-Z0-9_]{1,39}"
          :disabled="!isNew"
          required
          placeholder="例如：AREA_MANAGER" /></label
      ><label
        >角色名稱<input v-model="editing.name" maxlength="60" required /></label
      ><label
        >資料範圍<select
          v-model="editing.scope"
          :disabled="!isNew"
          @change="scopeChange"
        >
          <option value="SELF">僅本人訂單（客人）</option>
          <option value="BRANCH">所屬分店（門市人員）</option>
          <option value="GLOBAL">所有分店（總部人員）</option>
        </select></label
      >
      <fieldset>
        <legend>開放功能</legend>
        <label v-for="p in allowed" :key="p" class="checkbox-label"
          ><input v-model="editing.permissions" type="checkbox" :value="p" />{{
            permissions[p] || p
          }}</label
        >
      </fieldset>
      <p class="form-hint">
        門市點餐需同時勾選櫃台收銀與門市訂單。既有角色的資料範圍不可變更，需要不同範圍時請新增角色。
      </p>
      <button class="btn primary" :disabled="saving">
        {{ saving ? "儲存中…" : "儲存角色" }}
      </button>
    </form></Modal
  >
</template>
