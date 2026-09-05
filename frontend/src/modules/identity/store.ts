import { defineStore } from "pinia";
import { ref, computed } from "vue";
import { api, send, refreshCsrf, ApiError } from "../../shared/api";
import type { Actor } from "../../shared/types";
export const useAuth = defineStore("auth", () => {
  const user = ref<Actor | null>(null),
    loaded = ref(false);
  const customer = computed(() => user.value?.scope === "SELF");
  const can = (p: string) => !!user.value?.permissions.includes(p);
  async function init() {
    if (loaded.value) return;
    await refreshCsrf();
    try {
      user.value = await api<Actor>("/auth/me");
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 401)) throw e;
    }
    loaded.value = true;
  }
  async function login(username: string, password: string) {
    user.value = await send<Actor>("/auth/login", { username, password });
    await refreshCsrf();
    loaded.value = true;
  }
  async function logout() {
    await send("/auth/logout", {});
    user.value = null;
    await refreshCsrf();
  }
  return { user, loaded, customer, can, init, login, logout };
});
