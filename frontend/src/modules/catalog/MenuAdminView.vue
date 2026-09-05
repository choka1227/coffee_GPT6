<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { Plus, Search, Pencil, BookOpen } from "lucide-vue-next";
import { api, send } from "../../shared/api";
import type { Product } from "../../shared/types";
import { money } from "../../shared/format";
import { notify } from "../../shared/notice";
import Modal from "../../shared/Modal.vue";
const products = ref<Product[]>([]),
  editing = ref<Product | null>(null),
  query = ref(""),
  category = ref("全部分類"),
  loading = ref(true),
  error = ref(""),
  saving = ref(false);
const categories = ["經典咖啡", "風味特調", "茶與其他", "手作烘焙"];
const visible = computed(() =>
  products.value.filter(
    (p) =>
      p.name.includes(query.value) &&
      (category.value === "全部分類" || p.category === category.value),
  ),
);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    products.value = await api<Product[]>("/menu?manage=true");
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
    subtitle: "",
    category: "經典咖啡",
    price: 140,
    cost: 45,
    image: "latte",
    badge: "",
    active: true,
  };
}
async function save() {
  saving.value = true;
  try {
    await send("/menu", editing.value);
    editing.value = null;
    notify("菜單已更新，各店即時套用");
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
        <span class="eyebrow">CRAFT YOUR MENU</span>
        <h1>好味道，從菜單開始。</h1>
        <p class="muted">總部統一管理商品，價格與上下架狀態同步各分店。</p>
      </div>
      <button class="btn primary" @click="add">
        <Plus :size="18" />新增商品
      </button>
    </div>
    <div class="mini-summary">
      <span
        ><b>{{ products.length }}</b> 項商品</span
      ><span
        ><b>{{ products.filter((p) => p.active).length }}</b> 項供應中</span
      ><span
        ><b>{{ products.filter((p) => !p.active).length }}</b> 項已下架</span
      >
    </div>
    <section class="panel">
      <div class="table-toolbar">
        <label class="search-field"
          ><Search :size="18" /><input
            v-model="query"
            placeholder="搜尋商品名稱"
            aria-label="搜尋商品" /></label
        ><select v-model="category" aria-label="商品分類">
          <option>全部分類</option>
          <option v-for="c in categories" :key="c">{{ c }}</option>
        </select>
      </div>
      <div v-if="loading" class="loading-state">讀取菜單中…</div>
      <div v-else-if="error" class="error-state">
        {{ error }}<button class="btn secondary" @click="load">重試</button>
      </div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>商品</th>
              <th>分類</th>
              <th>售價</th>
              <th>成本</th>
              <th>商品毛利率</th>
              <th>供應狀態</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in visible" :key="p.id!">
              <td>
                <div class="product-table-cell">
                  <img
                    :src="'/images/' + p.image + '.jpg'"
                    :alt="p.category + '示意照片'"
                  />
                  <div>
                    <b>{{ p.name }}</b
                    ><small>{{ p.subtitle }}</small>
                  </div>
                </div>
              </td>
              <td>{{ p.category }}</td>
              <td class="numeric">
                <b>{{ money(p.price) }}</b>
              </td>
              <td>{{ money(p.cost) }}</td>
              <td>{{ Math.round(((p.price - p.cost) / p.price) * 100) }}%</td>
              <td>
                <span class="status" :class="p.active ? 'paid' : 'cancelled'">{{
                  p.active ? "供應中" : "已下架"
                }}</span>
              </td>
              <td>
                <button
                  class="icon-btn"
                  :aria-label="'編輯' + p.name"
                  @click="editing = { ...p }"
                >
                  <Pencil :size="18" />
                </button>
              </td>
            </tr>
            <tr v-if="!visible.length">
              <td colspan="7" class="empty-cell">目前沒有符合的商品。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="table-foot">
        價格以新台幣整數計價。修改價格與成本只影響之後建立的訂單。
      </div>
    </section>
  </div>
  <Modal
    v-if="editing"
    :title="editing.id ? '編輯商品' : '新增商品'"
    @close="!saving && (editing = null)"
    ><form class="form-stack" @submit.prevent="save">
      <label
        >商品名稱<input v-model="editing.name" required maxlength="80" /></label
      ><label
        >商品說明<textarea
          v-model="editing.subtitle"
          maxlength="200"
          rows="2"
        ></textarea>
      </label>
      <div class="form-grid">
        <label
          >分類<select v-model="editing.category">
            <option v-for="c in categories" :key="c">{{ c }}</option>
          </select></label
        ><label
          >圖片<select v-model="editing.image">
            <option value="latte">咖啡分類示意</option>
            <option value="pastry">烘焙分類示意</option>
          </select></label
        ><label
          >售價（元）<input
            v-model.number="editing.price"
            type="number"
            min="1"
            max="100000"
            step="1"
            required /></label
        ><label
          >單品成本（元）<input
            v-model.number="editing.cost"
            type="number"
            min="0"
            max="100000"
            step="1"
            required
        /></label>
      </div>
      <label
        >商品標籤<input
          v-model="editing.badge"
          maxlength="20"
          placeholder="例如：人氣首選" /></label
      ><label class="checkbox-label"
        ><input v-model="editing.active" type="checkbox" />上架供應</label
      ><button class="btn primary" :disabled="saving">
        {{ saving ? "儲存中…" : "儲存商品" }}
      </button>
    </form></Modal
  >
</template>
