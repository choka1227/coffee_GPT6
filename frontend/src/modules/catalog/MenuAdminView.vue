<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { Plus, Search, Pencil, Settings2 } from "lucide-vue-next";
import { api, send } from "../../shared/api";
import type { Branch, BranchAvailability, OptionGroup, OptionItem, Product } from "../../shared/types";
import { money } from "../../shared/format";
import { notify } from "../../shared/notice";
import Modal from "../../shared/Modal.vue";
const products = ref<Product[]>([]),
  editing = ref<Product | null>(null),
  query = ref(""),
  category = ref("全部分類"),
  loading = ref(true),
  error = ref(""),
  saving = ref(false),
  optionGroups = ref<OptionGroup[]>([]),
  editingGroup = ref<OptionGroup | null>(null),
  editingItem = ref<OptionItem | null>(null),
  bindingProduct = ref<Product | null>(null),
  selectedGroupIds = ref<string[]>([]),
  branches = ref<Branch[]>([]),
  supplyBranchId = ref(""),
  branchAvailability = ref<BranchAvailability[]>([]);
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
    [products.value, optionGroups.value, branches.value] = await Promise.all([
      api<Product[]>("/menu?manage=true"),
      api<OptionGroup[]>("/menu/options"),
      api<Branch[]>("/branches"),
    ]);
    if (!supplyBranchId.value) supplyBranchId.value = branches.value[0]?.id || "";
    await loadAvailability();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
onMounted(load);
async function loadAvailability() {
  branchAvailability.value = supplyBranchId.value
    ? await api<BranchAvailability[]>(`/menu/availability?branchId=${encodeURIComponent(supplyBranchId.value)}`)
    : [];
}
async function setSupply(item: BranchAvailability, availability: "AVAILABLE" | "UNLISTED") {
  saving.value = true;
  try {
    await send("/menu/availability", {
      branchId: supplyBranchId.value,
      productId: item.productId,
      availability,
    });
    notify(availability === "UNLISTED" ? "已設為本店不供應" : "已恢復本店供應");
    await loadAvailability();
  } catch (e) {
    notify((e as Error).message);
  } finally {
    saving.value = false;
  }
}
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
    availability: "AVAILABLE",
    optionGroups: [],
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
function addGroup() {
  editingGroup.value = {
    id: null,
    name: "",
    selection: "SINGLE",
    minSelect: 0,
    maxSelect: 1,
    active: true,
    sortOrder: optionGroups.value.length + 1,
    items: [],
  };
}
function addItem(group: OptionGroup) {
  editingItem.value = {
    id: null,
    groupId: group.id!,
    name: "",
    priceDelta: 0,
    costDelta: 0,
    active: true,
    sortOrder: group.items.length + 1,
  };
}
async function saveGroup() {
  saving.value = true;
  try {
    await send("/menu/options/groups", editingGroup.value);
    editingGroup.value = null;
    notify("選項群組已儲存");
    await load();
  } catch (e) {
    notify((e as Error).message);
  } finally {
    saving.value = false;
  }
}
async function saveItem() {
  saving.value = true;
  try {
    await send("/menu/options/items", editingItem.value);
    editingItem.value = null;
    notify("選項項目已儲存");
    await load();
  } catch (e) {
    notify((e as Error).message);
  } finally {
    saving.value = false;
  }
}
function openBinding(product: Product) {
  bindingProduct.value = product;
  selectedGroupIds.value = product.optionGroups.map((group) => group.id!);
}
async function saveBinding() {
  saving.value = true;
  try {
    await send(`/menu/${bindingProduct.value!.id}/options`, {
      groupIds: selectedGroupIds.value,
    });
    bindingProduct.value = null;
    notify("商品選項已更新");
    await load();
  } catch (e) {
    notify((e as Error).message);
  } finally {
    saving.value = false;
  }
}
function usageCount(groupId: string | null) {
  return products.value.filter((product) =>
    product.optionGroups.some((group) => group.id === groupId),
  ).length;
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
        <div>
          <h2>分店供應設定</h2>
          <p class="muted">設定特定分店是否供應商品；每日售完由門市在點餐畫面操作。</p>
        </div>
        <select v-model="supplyBranchId" aria-label="供應設定分店" @change="loadAvailability">
          <option v-for="branch in branches" :key="branch.id!" :value="branch.id">{{ branch.name }}</option>
        </select>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>商品</th><th>目前狀態</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in branchAvailability" :key="item.productId">
              <td><b>{{ item.productName }}</b></td>
              <td>{{ item.availability === "UNLISTED" ? "本店不供應" : item.availability === "SOLD_OUT" ? "今日售完" : "供應中" }}</td>
              <td>
                <button
                  class="btn secondary"
                  :disabled="saving"
                  @click="setSupply(item, item.availability === 'UNLISTED' ? 'AVAILABLE' : 'UNLISTED')"
                >{{ item.availability === "UNLISTED" ? "恢復供應" : "設為不供應" }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
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
                  :aria-label="'設定' + p.name + '選項'"
                  @click="openBinding(p)"
                >
                  <Settings2 :size="18" />
                </button>
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
    <section class="panel">
      <div class="table-toolbar">
        <div>
          <h2>商品選項</h2>
          <p class="muted">管理全鏈共用的選項、加價與成本。</p>
        </div>
        <button class="btn primary" @click="addGroup">
          <Plus :size="18" />新增群組
        </button>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>群組</th>
              <th>選擇規則</th>
              <th>商品使用數</th>
              <th>項目</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="group in optionGroups" :key="group.id!">
              <td>
                <b>{{ group.name }}</b>
                <small>{{ group.active ? "啟用" : "停用" }}</small>
              </td>
              <td>
                {{ group.selection === "SINGLE" ? "單選" : "複選" }}，
                {{ group.minSelect }}–{{ group.maxSelect }} 項
              </td>
              <td>{{ usageCount(group.id) }} 個商品使用</td>
              <td>
                <span v-for="item in group.items" :key="item.id!" class="status">
                  {{ item.name }}（+{{ money(item.priceDelta) }}）
                </span>
              </td>
              <td>
                <button class="icon-btn" :aria-label="'編輯' + group.name" @click="editingGroup = { ...group }">
                  <Pencil :size="18" />
                </button>
                <button class="icon-btn" :aria-label="'新增' + group.name + '項目'" @click="addItem(group)">
                  <Plus :size="18" />
                </button>
                <button
                  v-for="item in group.items"
                  :key="'edit-' + item.id"
                  class="btn secondary"
                  @click="editingItem = { ...item }"
                >
                  編輯 {{ item.name }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
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
  <Modal v-if="editingGroup" title="編輯選項群組" @close="!saving && (editingGroup = null)">
    <form class="form-stack" @submit.prevent="saveGroup">
      <label>群組名稱<input v-model="editingGroup.name" required maxlength="40" /></label>
      <div class="form-grid">
        <label>選擇方式<select v-model="editingGroup.selection"><option value="SINGLE">單選</option><option value="MULTI">複選</option></select></label>
        <label>最少選擇<input v-model.number="editingGroup.minSelect" type="number" min="0" required /></label>
        <label>最多選擇<input v-model.number="editingGroup.maxSelect" type="number" min="1" required /></label>
        <label>排序<input v-model.number="editingGroup.sortOrder" type="number" required /></label>
      </div>
      <label class="checkbox-label"><input v-model="editingGroup.active" type="checkbox" />啟用群組</label>
      <p v-if="editingGroup.id" class="muted">{{ usageCount(editingGroup.id) }} 個商品使用；停用前需先解除全部綁定。</p>
      <button class="btn primary" :disabled="saving">{{ saving ? "儲存中…" : "儲存群組" }}</button>
    </form>
  </Modal>
  <Modal v-if="editingItem" title="編輯選項項目" @close="!saving && (editingItem = null)">
    <form class="form-stack" @submit.prevent="saveItem">
      <label>項目名稱<input v-model="editingItem.name" required maxlength="40" /></label>
      <div class="form-grid">
        <label>加價（元）<input v-model.number="editingItem.priceDelta" type="number" min="0" max="10000" required /></label>
        <label>成本增量（元）<input v-model.number="editingItem.costDelta" type="number" min="0" max="10000" required /></label>
        <label>排序<input v-model.number="editingItem.sortOrder" type="number" required /></label>
      </div>
      <label class="checkbox-label"><input v-model="editingItem.active" type="checkbox" />啟用項目</label>
      <button class="btn primary" :disabled="saving">{{ saving ? "儲存中…" : "儲存項目" }}</button>
    </form>
  </Modal>
  <Modal v-if="bindingProduct" :title="'設定「' + bindingProduct.name + '」選項'" @close="!saving && (bindingProduct = null)">
    <form class="form-stack" @submit.prevent="saveBinding">
      <label v-for="group in optionGroups" :key="group.id!" class="checkbox-label">
        <input v-model="selectedGroupIds" type="checkbox" :value="group.id" />
        {{ group.name }}（{{ usageCount(group.id) }} 個商品使用）
      </label>
      <button class="btn primary" :disabled="saving">{{ saving ? "儲存中…" : "儲存商品選項" }}</button>
    </form>
  </Modal>
</template>
