<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { Coffee, ArrowRight, Eye, EyeOff } from "lucide-vue-next";
import { useAuth } from "./store";
const username = ref(""),
  password = ref(""),
  busy = ref(false),
  error = ref(""),
  show = ref(false),
  auth = useAuth(),
  router = useRouter();
const demo =
  import.meta.env.DEV || import.meta.env.VITE_SHOW_DEMO_ACCOUNTS === "true";
async function login() {
  busy.value = true;
  error.value = "";
  try {
    await auth.login(username.value, password.value);
    router.push(auth.can("REPORT_ALL") ? "/reports" : "/");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
function example(role: string) {
  username.value = role + "@coffee.local";
  password.value = "CoffeeDemo!2026";
}
</script>
<template>
  <div class="login-page">
    <section class="login-story">
      <div class="brand">
        <Coffee :size="32" /><span
          >MORNING POUR<small>COFFEE & DAILY</small></span
        >
      </div>
      <div class="login-story-copy">
        <p class="eyebrow">BREWED FOR YOUR EVERYDAY</p>
        <h1>讓日常，<br />慢一杯咖啡。</h1>
        <p>在熟悉的香氣裡，<br />找到屬於你的片刻。</p>
      </div>
      <span class="photo-caption">A little coffee. A better day.</span>
    </section>
    <section class="login-form-wrap">
      <div class="login-form">
        <span class="eyebrow">WELCOME BACK</span>
        <h2>很高興，再見到你。</h2>
        <p class="muted">登入帳號，開始今天的咖啡時光。</p>
        <form @submit.prevent="login" class="form-stack">
          <label
            >帳號<input
              v-model="username"
              autocomplete="username"
              placeholder="請輸入帳號或電子郵件"
              required
              maxlength="100" /></label
          ><label
            >密碼
            <div class="password-input">
              <input
                v-model="password"
                :type="show ? 'text' : 'password'"
                autocomplete="current-password"
                placeholder="請輸入密碼"
                required
              /><button
                type="button"
                class="icon-btn"
                :aria-label="show ? '隱藏密碼' : '顯示密碼'"
                @click="show = !show"
              >
                <EyeOff v-if="show" :size="18" /><Eye v-else :size="18" />
              </button></div
          ></label>
          <p v-if="error" class="error" role="alert">{{ error }}</p>
          <button class="btn primary login-submit" :disabled="busy">
            {{ busy ? "登入中…" : "登入" }}<ArrowRight :size="19" />
          </button>
        </form>
        <div v-if="demo" class="demo-accounts">
          <small>開發示範帳號 · 不含真實交易</small>
          <div>
            <button
              v-for="(label, role) in {
                customer: '客人',
                cashier: '收銀員',
                manager: '店長',
                hq: '總部',
              }"
              :key="role"
              @click="example(role)"
            >
              {{ label }}
            </button>
          </div>
          <small>密碼：CoffeeDemo!2026（可由 DEMO_PASSWORD 覆寫）</small>
        </div>
        <p class="login-help">需要帳號協助？請洽門市或總部管理人員。</p>
      </div>
      <div class="login-copyright">
        © {{ new Date().getFullYear() }} MORNING POUR
      </div>
    </section>
  </div>
</template>
