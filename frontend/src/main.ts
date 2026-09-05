import { createApp } from "vue";
import { createPinia } from "pinia";
import { createRouter, createWebHistory } from "vue-router";
import App from "./App.vue";
import { useAuth } from "./modules/identity/store";
import { notify } from "./shared/notice";
import "./style.css";
const routes = [
  {
    path: "/login",
    component: () => import("./modules/identity/LoginView.vue"),
  },
  { path: "/", component: () => import("./modules/ordering/MenuView.vue") },
  {
    path: "/orders",
    component: () => import("./modules/ordering/OrdersView.vue"),
  },
  {
    path: "/reports",
    component: () => import("./modules/reporting/ReportsView.vue"),
    meta: { permissions: ["REPORT_STORE", "REPORT_ALL"] },
  },
  {
    path: "/branches",
    component: () => import("./modules/branches/BranchesView.vue"),
    meta: { permissions: ["BRANCH_MANAGE"] },
  },
  {
    path: "/menu",
    component: () => import("./modules/catalog/MenuAdminView.vue"),
    meta: { permissions: ["MENU_MANAGE"] },
  },
  {
    path: "/accounts",
    component: () => import("./modules/identity/AccountsView.vue"),
    meta: { permissions: ["ACCOUNT_MANAGE"] },
  },
  {
    path: "/roles",
    component: () => import("./modules/identity/RolesView.vue"),
    meta: { permissions: ["ROLE_MANAGE"] },
  },
  { path: "/:pathMatch(.*)*", redirect: "/" },
];
const app = createApp(App);
app.use(createPinia());
const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
});
router.beforeEach(async (to) => {
  const auth = useAuth();
  try {
    await auth.init();
  } catch (e) {
    notify((e as Error).message);
    return to.path === "/login" ? true : "/login";
  }
  if (!auth.user) return to.path === "/login" ? true : "/login";
  if (to.path === "/login") return "/";
  const ps = to.meta.permissions as string[] | undefined;
  if (ps && !ps.some(auth.can)) {
    notify("你的帳號沒有這個功能的權限");
    return "/";
  }
  return true;
});
window.addEventListener("session-expired", () => {
  useAuth().user = null;
  notify("登入已逾時，請重新登入");
  router.push("/login");
});
app.use(router);
app.mount("#app");
