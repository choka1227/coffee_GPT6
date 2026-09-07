<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  Coffee,
  ShoppingBag,
  ClipboardList,
  ChartNoAxesCombined,
  Store,
  UsersRound,
  ShieldCheck,
  BookOpen,
  LogOut,
  Menu,
  X,
  KeyRound,
} from "lucide-vue-next";
import { useAuth } from "./modules/identity/store";
import { notice, notify } from "./shared/notice";
import { roleNames } from "./shared/format";
import { send } from "./shared/api";
import Modal from "./shared/Modal.vue";
const auth = useAuth(),
  route = useRoute(),
  router = useRouter(),
  mobile = ref(false),
  changePassword = ref(false),
  oldPassword = ref(""),
  newPassword = ref(""),
  saving = ref(false);
const links = computed(() =>
  [
    {
      path: "/",
      label: auth.customer ? "開始點餐" : "櫃台點餐",
      icon: Coffee,
      show: auth.can("ORDER_CREATE"),
    },
    {
      path: "/orders",
      label: auth.customer ? "我的訂單" : "門市訂單",
      icon: ClipboardList,
      show: auth.customer || auth.can("ORDER_MANAGE"),
    },
    {
      path: "/reports",
      label: auth.user?.scope === "GLOBAL" ? "營運總覽" : "業績報表",
      icon: ChartNoAxesCombined,
      show: auth.can("REPORT_STORE") || auth.can("REPORT_ALL"),
    },
    {
      path: "/branches",
      label: "分店管理",
      icon: Store,
      show: auth.can("BRANCH_MANAGE"),
    },
    {
      path: "/menu",
      label: "菜單管理",
      icon: BookOpen,
      show: auth.can("MENU_MANAGE"),
    },
    {
      path: "/accounts",
      label: "帳號管理",
      icon: UsersRound,
      show: auth.can("ACCOUNT_MANAGE"),
    },
    {
      path: "/roles",
      label: "角色與權限",
      icon: ShieldCheck,
      show: auth.can("ROLE_MANAGE"),
    },
  ].filter((l) => l.show),
);
async function logout() {
  try {
    await auth.logout();
    mobile.value = false;
    router.push("/login");
  } catch (e) {
    notify((e as Error).message);
  }
}
async function password() {
  saving.value = true;
  try {
    await send("/auth/password", {
      oldPassword: oldPassword.value,
      newPassword: newPassword.value,
    });
    changePassword.value = false;
    oldPassword.value = "";
    newPassword.value = "";
    notify("密碼已更新");
  } catch (e) {
    notify((e as Error).message);
  } finally {
    saving.value = false;
  }
}
</script>
<template>
  <div v-if="route.path === '/login' || !auth.user"><RouterView /></div>
  <div
    v-else
    :class="['shell', auth.customer ? 'customer-shell' : 'staff-shell']"
  >
    <aside v-if="!auth.customer" class="sidebar" :class="{ open: mobile }">
      <RouterLink to="/" class="brand"
        ><span class="brand-mark"><Coffee :size="26" /></span
        ><span>MORNING<br />POUR<small>COFFEE & DAILY</small></span></RouterLink
      ><button
        class="icon-btn mobile-close"
        aria-label="關閉選單"
        @click="mobile = false"
      >
        <X />
      </button>
      <div class="sidebar-label">WORKSPACE / 營運空間</div>
      <nav>
        <RouterLink
          v-for="link in links"
          :key="link.path"
          :to="link.path"
          :class="{ active: route.path === link.path }"
          @click="mobile = false"
          ><component :is="link.icon" :size="19" />{{ link.label }}</RouterLink
        >
      </nav>
      <div class="sidebar-bottom">
        <span class="avatar">{{ auth.user.name.slice(0, 1) }}</span>
        <div>
          <b>{{ auth.user.name }}</b
          ><small>{{ roleNames[auth.user.role] || auth.user.role }}</small>
        </div>
        <button class="icon-btn" aria-label="登出" @click="logout">
          <LogOut :size="18" />
        </button>
      </div>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <template v-if="auth.customer"
          ><RouterLink to="/" class="brand customer-brand"
            ><Coffee :size="27" /><span
              >MORNING POUR<small>COFFEE & DAILY</small></span
            ></RouterLink
          >
          <nav class="customer-nav">
            <RouterLink to="/" :class="{ active: route.path === '/' }"
              >咖啡與餐點</RouterLink
            ><RouterLink
              to="/orders"
              :class="{ active: route.path === '/orders' }"
              ><ShoppingBag :size="16" />我的訂單</RouterLink
            >
          </nav></template
        ><template v-else
          ><button
            class="icon-btn mobile-toggle"
            aria-label="開啟選單"
            @click="mobile = !mobile"
          >
            <Menu /></button
          ><span class="breadcrumb"
            >MORNING POUR <span>/</span>
            {{ links.find((l) => l.path === route.path)?.label }}</span
          ></template
        >
        <div class="account-tools">
          <span class="account-name">{{
            auth.customer
              ? auth.user.name
              : auth.user.scope === "GLOBAL"
                ? "總部營運中心"
                : "門市工作空間"
          }}</span
          ><button
            class="icon-btn"
            aria-label="修改密碼"
            title="修改密碼"
            @click="changePassword = true"
          >
            <KeyRound :size="18" /></button
          ><button
            v-if="auth.customer"
            class="icon-btn"
            aria-label="登出"
            @click="logout"
          >
            <LogOut :size="18" />
          </button>
        </div>
      </header>
      <main><RouterView :key="auth.user.id" /></main>
      <footer class="site-footer">
        MORNING POUR <span>一杯好咖啡，從每個細節開始。</span
        ><span>© {{ new Date().getFullYear() }}</span>
      </footer>
    </div>
  </div>
  <div v-if="notice" class="toast" role="status">
    {{ notice }}<button aria-label="關閉通知" @click="notice = ''">×</button>
  </div>
  <Modal v-if="changePassword" title="修改密碼" @close="changePassword = false"
    ><form class="form-stack" @submit.prevent="password">
      <label
        >目前密碼<input
          v-model="oldPassword"
          type="password"
          autocomplete="current-password"
          required /></label
      ><label
        >新密碼<input
          v-model="newPassword"
          type="password"
          autocomplete="new-password"
          minlength="12"
          required
          placeholder="至少 12 字元" /></label
      ><button class="btn primary" :disabled="saving">
        {{ saving ? "儲存中…" : "更新密碼" }}
      </button>
    </form></Modal
  >
</template>
