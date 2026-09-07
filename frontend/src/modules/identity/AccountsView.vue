<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { Plus, Search, Pencil, UsersRound } from "lucide-vue-next";
import { api, send } from "../../shared/api";
import type { Account, Role, Branch } from "../../shared/types";
import { notify } from "../../shared/notice";
import Modal from "../../shared/Modal.vue";
const accounts = ref<Account[]>([]),
  roles = ref<Role[]>([]),
  branches = ref<Branch[]>([]),
  editing = ref<Account | null>(null),
  query = ref(""),
  filterRole = ref(""),
  loading = ref(true),
  error = ref(""),
  saving = ref(false);
const visible = computed(() =>
  accounts.value.filter(
    (a) =>
      (a.username + a.name).includes(query.value) &&
      (!filterRole.value || a.role === filterRole.value),
  ),
);
const chosenRole = computed(() =>
  roles.value.find((r) => r.code === editing.value?.role),
);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [a, r, b] = await Promise.all([
      api<Account[]>("/admin/accounts"),
      api<{ roles: Role[] }>("/admin/roles"),
      api<Branch[]>("/branches"),
    ]);
    accounts.value = a;
    roles.value = r.roles;
    branches.value = b;
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
    username: "",
    name: "",
    role: "CUSTOMER",
    branchId: null,
    active: true,
    password: "",
  };
}
async function save() {
  saving.value = true;
  try {
    await send("/admin/accounts", editing.value);
    editing.value = null;
    notify("帳號已儲存");
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
        <span class="eyebrow">PEOPLE BEHIND EVERY CUP</span>
        <h1>讓對的人，做好每件事。</h1>
        <p class="muted">帳號管理 · 配置人員角色與分店歸屬。</p>
      </div>
      <button class="btn primary" @click="add">
        <Plus :size="18" />新增帳號
      </button>
    </div>
    <div class="mini-summary">
      <span
        ><b>{{ accounts.length }}</b> 個帳號</span
      ><span
        ><b>{{ accounts.filter((a) => a.active).length }}</b> 個啟用中</span
      >
    </div>
    <section class="panel">
      <div class="table-toolbar">
        <label class="search-field"
          ><Search :size="18" /><input
            v-model="query"
            placeholder="搜尋姓名或帳號"
            aria-label="搜尋帳號" /></label
        ><select v-model="filterRole" aria-label="篩選角色">
          <option value="">所有角色</option>
          <option v-for="r in roles" :key="r.code" :value="r.code">
            {{ r.name }}
          </option>
        </select>
      </div>
      <div v-if="loading" class="loading-state">讀取帳號中…</div>
      <div v-else-if="error" class="error-state">
        {{ error }}<button class="btn secondary" @click="load">重試</button>
      </div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>姓名 / 帳號</th>
              <th>角色</th>
              <th>分店歸屬</th>
              <th>狀態</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in visible" :key="a.id!">
              <td>
                <div class="user-cell">
                  <span class="avatar">{{ a.name.slice(0, 1) }}</span>
                  <div>
                    <b>{{ a.name }}</b
                    ><small>{{ a.username }}</small>
                  </div>
                </div>
              </td>
              <td>
                <span class="role-tag">{{
                  roles.find((r) => r.code === a.role)?.name || a.role
                }}</span>
              </td>
              <td>
                {{
                  a.branchId
                    ? branches.find((b) => b.id === a.branchId)?.name ||
                      a.branchId
                    : "不限定分店"
                }}
              </td>
              <td>
                <span class="status" :class="a.active ? 'paid' : 'cancelled'">{{
                  a.active ? "啟用" : "停用"
                }}</span>
              </td>
              <td>
                <button
                  class="icon-btn"
                  :aria-label="'編輯' + a.name"
                  @click="editing = { ...a, password: '' }"
                >
                  <Pencil :size="18" />
                </button>
              </td>
            </tr>
            <tr v-if="!visible.length">
              <td colspan="5" class="empty-cell">沒有符合的帳號。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="table-foot">
        帳號停用及權限變更會在下一次操作生效。系統保留至少一個啟用的總部管理帳號。
      </div>
    </section>
  </div>
  <Modal
    v-if="editing"
    :title="editing.id ? '編輯帳號' : '新增帳號'"
    @close="!saving && (editing = null)"
    ><form class="form-stack" @submit.prevent="save">
      <label>姓名<input v-model="editing.name" required maxlength="80" /></label
      ><label
        >登入帳號<input
          v-model="editing.username"
          required
          minlength="3"
          maxlength="100"
          autocomplete="off" /></label
      ><label
        >角色<select v-model="editing.role" required>
          <option v-for="r in roles" :key="r.code" :value="r.code">
            {{ r.name }}
          </option>
        </select></label
      ><label v-if="chosenRole?.scope === 'BRANCH'"
        >所屬分店<select v-model="editing.branchId" required>
          <option :value="null" disabled>請選擇分店</option>
          <option v-for="b in branches" :key="b.id!" :value="b.id">
            {{ b.name }}
          </option>
        </select></label
      ><label
        >{{ editing.id ? "重設密碼（留白則不變）" : "初始密碼"
        }}<input
          v-model="editing.password"
          type="password"
          minlength="12"
          maxlength="72"
          :required="!editing.id"
          autocomplete="new-password"
          placeholder="至少 12 字元" /></label
      ><label class="checkbox-label"
        ><input v-model="editing.active" type="checkbox" />啟用帳號</label
      ><button class="btn primary" :disabled="saving">
        {{ saving ? "儲存中…" : "儲存帳號" }}
      </button>
    </form></Modal
  >
</template>
