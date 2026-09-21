<script setup lang="ts">
import { ref, computed, onMounted, watch } from "vue";
import { useRouter } from "vue-router";
import {
  Search,
  Plus,
  Minus,
  ShoppingBag,
  ArrowRight,
  MapPin,
  Check,
  Trash2,
  Coffee,
  SlidersHorizontal,
} from "lucide-vue-next";
import { useAuth } from "../identity/store";
import { api, send } from "../../shared/api";
import type {
  Product,
  Branch,
  BranchHours,
  BranchHoursResponse,
  CartLine,
  Order,
} from "../../shared/types";
import { minuteTime, money } from "../../shared/format";
import { notify } from "../../shared/notice";
import Modal from "../../shared/Modal.vue";
import { openEcpay } from "../payments/ecpay";
const auth = useAuth(),
  router = useRouter(),
  products = ref<Product[]>([]),
  branches = ref<Branch[]>([]),
  branchId = ref(auth.user?.branchId || ""),
  loading = ref(true),
  error = ref(""),
  query = ref(""),
  category = ref("全部餐點"),
  cart = ref<CartLine[]>([]),
  fulfillment = ref("TAKEAWAY"),
  payment = ref("CASH"),
  note = ref(""),
  tendered = ref<number | undefined>(),
  busy = ref(false),
  mobileCart = ref(false),
  selected = ref<Product | null>(null),
  selectedOptionIds = ref<string[]>([]),
  quantity = ref(1),
  receipt = ref<Order | null>(null),
  branchHours = ref<BranchHours[]>([]),
  config = ref({ enabled: false, environment: "stage" });
let retryBody = "",
  retryKey = "";
const categories = ["全部餐點", "經典咖啡", "風味特調", "茶與其他", "手作烘焙"];
const visible = computed(() =>
  products.value.filter(
    (p) =>
      (category.value === "全部餐點" || p.category === category.value) &&
      (p.name + p.subtitle).includes(query.value),
  ),
);
const total = computed(() =>
  cart.value.reduce((s, l) => s + (l.unitPrice + l.optionsPrice) * l.quantity, 0),
);
const count = computed(() => cart.value.reduce((s, l) => s + l.quantity, 0));
const selectedOptionsPrice = computed(() =>
  (selected.value?.optionGroups || []).flatMap((g) => g.items)
    .filter((i) => selectedOptionIds.value.includes(i.id!))
    .reduce((sum, i) => sum + i.priceDelta, 0),
);
const optionsValid = computed(() =>
  (selected.value?.optionGroups || []).every((g) => {
    const count = g.items.filter((i) => selectedOptionIds.value.includes(i.id!)).length;
    return count >= g.minSelect && count <= g.maxSelect && (g.selection !== "SINGLE" || count <= 1);
  }),
);
const branch = computed(() =>
  branches.value.find((b) => b.id === branchId.value),
);
const customerClosed = computed(
  () => auth.customer && !!branch.value && !branch.value.openNow,
);
const dayNames = ["", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"];
const schedule = computed(() =>
  dayNames.slice(1).map((name, index) => {
    const periods = branchHours.value.filter((period) => period.dayOfWeek === index + 1);
    return {
      name,
      text: periods.length
        ? periods
            .map(
              (period) =>
                `${minuteTime(period.openMinute)}–${
                  period.closeMinute <= period.openMinute ? "隔日 " : ""
                }${minuteTime(period.closeMinute)}`,
            )
            .join("、")
        : branchHours.value.length
          ? "未營業"
          : "24 小時營業",
    };
  }),
);
const permittedBranches = computed(() =>
  auth.customer || auth.user?.scope === "GLOBAL"
    ? branches.value
    : branches.value.filter((b) => b.id === auth.user?.branchId),
);
const unavailableCart = computed(() => {
  const menu = new Map(products.value.map((p) => [p.id, p]));
  return cart.value.filter((line) => menu.get(line.productId)?.availability !== "AVAILABLE");
});
let menuReady = false;
async function loadBranchHours(showError = true) {
  if (!branchId.value) {
    branchHours.value = [];
    return;
  }
  try {
    const result = await api<BranchHoursResponse>(`/branches/${branchId.value}/hours`);
    branchHours.value = result.hours;
  } catch (e) {
    branchHours.value = [];
    if (showError) error.value = (e as Error).message;
    else notify((e as Error).message);
  }
}
async function loadMenu(showError = true) {
  if (!branchId.value) {
    products.value = [];
    return;
  }
  products.value = [];
  try {
    products.value = await api<Product[]>(`/menu?branchId=${encodeURIComponent(branchId.value)}`);
    if (unavailableCart.value.length)
      notify("切換分店後，點餐單中有商品已售完或未供應，請先移除");
  } catch (e) {
    if (showError) error.value = (e as Error).message;
    else notify((e as Error).message);
  }
}
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [b, c] = await Promise.all([
      api<Branch[]>("/branches"),
      api<{ enabled: boolean; environment: string }>("/payments/config"),
    ]);
    branches.value = b;
    config.value = c;
    if (!b.some((branch) => branch.id === branchId.value)) branchId.value = b[0]?.id || "";
    await Promise.all([loadMenu(), loadBranchHours()]);
    menuReady = true;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
onMounted(load);
watch(branchId, async () => {
  if (!menuReady) return;
  error.value = "";
  loading.value = true;
  await Promise.all([loadMenu(false), loadBranchHours(false)]);
  loading.value = false;
});
function choose(p: Product) {
  if (busy.value || customerClosed.value || p.availability === "SOLD_OUT") return;
  selected.value = p;
  selectedOptionIds.value = p.optionGroups
    .filter((g) => g.minSelect > 0)
    .flatMap((g) => g.items.slice(0, g.minSelect).map((i) => i.id!));
  quantity.value = 1;
}
function toggleOption(groupId: string | null, itemId: string | null, single: boolean) {
  if (!itemId) return;
  const group = selected.value?.optionGroups.find((g) => g.id === groupId);
  if (single && group) {
    const ids = new Set(group.items.map((i) => i.id));
    selectedOptionIds.value = selectedOptionIds.value.filter((id) => !ids.has(id));
  }
  if (!selectedOptionIds.value.includes(itemId)) selectedOptionIds.value.push(itemId);
  else if (!single) selectedOptionIds.value = selectedOptionIds.value.filter((id) => id !== itemId);
}
function add() {
  if (customerClosed.value) {
    notify("分店目前未營業，請選擇其他分店或於營業時間再下單");
    return;
  }
  const p = selected.value;
  if (!p?.id) return;
  const existing = cart.value.find(
    (l) =>
      l.productId === p.id &&
      [...l.optionIds].sort().join(",") === [...selectedOptionIds.value].sort().join(","),
  );
  if (existing) {
    if (existing.quantity + quantity.value > 50) {
      notify("單品數量最多 50 份");
      return;
    }
    existing.quantity += quantity.value;
  } else
    cart.value.push({
      productId: p.id,
      name: p.name,
      category: p.category,
      unitPrice: p.price,
      quantity: quantity.value,
      optionIds: [...selectedOptionIds.value].sort(),
      optionsPrice: p.optionGroups.flatMap((g) => g.items)
        .filter((i) => selectedOptionIds.value.includes(i.id!))
        .reduce((sum, i) => sum + i.priceDelta, 0),
      lineTotal: 0,
      options: p.optionGroups.flatMap((g) => g.items
        .filter((i) => selectedOptionIds.value.includes(i.id!))
        .map((i) => ({ groupName: g.name, optionName: i.name, priceDelta: i.priceDelta }))),
    });
  selected.value = null;
  notify("已加入 " + p.name);
}
function adjust(i: number, n: number) {
  const l = cart.value[i];
  l.quantity += n;
  if (l.quantity === 0) cart.value.splice(i, 1);
  if (l.quantity > 50) l.quantity = 50;
}
async function checkout() {
  if (busy.value || !cart.value.length) return;
  if (!branchId.value) {
    notify("請先選擇分店");
    return;
  }
  if (customerClosed.value) {
    notify("分店目前未營業，請選擇其他分店或於營業時間再下單");
    return;
  }
  if (unavailableCart.value.length) {
    notify("點餐單中有商品已售完或本店未供應，請先移除");
    return;
  }
  const cashAtPos = !auth.customer && payment.value === "CASH";
  const cash = tendered.value ?? total.value;
  if (
    cashAtPos &&
    (!Number.isInteger(cash) || cash < total.value || cash > 1000000)
  ) {
    notify("請輸入足夠的實收金額");
    return;
  }
  busy.value = true;
  let order: Order | undefined;
  try {
    const body = {
      branchId: branchId.value,
      fulfillment: fulfillment.value,
      paymentMethod: payment.value,
      note: note.value,
      items: cart.value.map(({ productId, quantity, optionIds }) => ({
        productId,
        quantity,
        optionIds,
      })),
    };
    const serialized = JSON.stringify(body);
    if (serialized !== retryBody) {
      retryBody = serialized;
      retryKey = crypto.randomUUID();
    }
    order = await send<Order>("/orders", body, "POST", {
      "Idempotency-Key": retryKey,
    });
    cart.value = [];
    note.value = "";
    retryBody = "";
    retryKey = "";
    if (payment.value === "ECPAY") {
      await openEcpay(order.id);
      return;
    }
    if (cashAtPos) {
      receipt.value = await send<Order>("/orders/" + order.id + "/cash", {
        tendered: cash,
      });
      tendered.value = undefined;
      mobileCart.value = false;
    } else {
      notify("訂單已送出，請至櫃台付款");
      router.push("/orders");
    }
  } catch (e) {
    notify((e as Error).message);
    if (order) router.push("/orders");
  } finally {
    busy.value = false;
  }
}
async function toggleAvailability(p: Product) {
  if (!p.id || !branchId.value || busy.value) return;
  busy.value = true;
  try {
    await send("/menu/availability", {
      branchId: branchId.value,
      productId: p.id,
      availability: p.availability === "SOLD_OUT" ? "AVAILABLE" : "SOLD_OUT",
    });
    notify(p.availability === "SOLD_OUT" ? "已恢復供應" : "已標記今日售完");
    await loadMenu(false);
  } catch (e) {
    notify((e as Error).message);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div v-if="!auth.can('ORDER_CREATE')" class="empty-state">
    <Coffee :size="40" />
    <h2>請從選單開啟工作功能</h2>
    <p>此帳號尚未配置點餐權限。</p>
  </div>
  <div v-else class="order-page" :class="{ 'pos-page': !auth.customer }">
    <section class="menu-surface">
      <div class="page-heading">
        <div>
          <span class="eyebrow">{{
            auth.customer ? "GOOD COFFEE, GOOD DAY" : "POINT OF SALE"
          }}</span>
          <h1>{{ auth.customer ? "今天，想喝點什麼？" : "櫃台點餐" }}</h1>
          <p class="muted">
            {{
              auth.customer
                ? "新鮮現做，為你保留每一口的美好。"
                : "為每位客人，準備一杯剛剛好的咖啡。"
            }}
          </p>
        </div>
        <div class="branch-picker">
          <MapPin :size="17" /><select
            v-model="branchId"
            aria-label="選擇取餐分店"
            :disabled="busy || permittedBranches.length <= 1"
          >
            <option v-for="b in permittedBranches" :key="b.id!" :value="b.id">
              {{ b.name }}{{ auth.customer ? (b.openNow ? " · 營業中" : " · 已打烊") : "" }}
            </option>
          </select>
        </div>
      </div>
      <section v-if="auth.customer && branch" class="hours-status" :class="{ closed: customerClosed }">
        <div>
          <strong>{{ customerClosed ? "目前已打烊" : "目前營業中" }}</strong>
          <span>{{ branch.name }}營業時間</span>
        </div>
        <dl>
          <template v-for="day in schedule" :key="day.name">
            <dt>{{ day.name }}</dt><dd>{{ day.text }}</dd>
          </template>
        </dl>
        <p v-if="customerClosed">目前仍可瀏覽菜單；請選擇營業中的分店或於營業時間再下單。</p>
      </section>
      <div v-if="auth.customer" class="coffee-banner">
        <div>
          <span class="eyebrow">THE HOUSE FAVORITE</span>
          <h2>一杯經典，<br />恰好的日常。</h2>
          <button class="text-link" @click="category = '經典咖啡'">
            探索經典咖啡 <ArrowRight :size="16" />
          </button>
        </div>
        <img src="/images/latte.jpg" alt="木桌上的拿鐵咖啡與細緻拉花" />
      </div>
      <div class="menu-toolbar">
        <div class="category-tabs" role="tablist" aria-label="餐點分類">
          <button
            v-for="c in categories"
            :key="c"
            role="tab"
            :aria-selected="c === category"
            :class="{ active: c === category }"
            @click="category = c"
          >
            {{ c }}
          </button>
        </div>
        <label class="search-field"
          ><Search :size="18" /><input
            v-model="query"
            aria-label="搜尋餐點"
            placeholder="找一杯喜歡的咖啡"
        /></label>
      </div>
      <div class="section-line">
        <h2>{{ category }}</h2>
        <span>{{ visible.length }} 項餐點</span>
      </div>
      <div v-if="error" class="error-state" role="alert">
        {{ error }}<button class="btn secondary" @click="load">重新載入</button>
      </div>
      <div v-else-if="loading" class="loading-state">正在準備菜單…</div>
      <div v-else-if="!visible.length" class="empty-state">
        <Search :size="35" />
        <h3>沒有找到符合的餐點</h3>
        <button
          class="text-link"
          @click="
            query = '';
            category = '全部餐點';
          "
        >
          查看全部菜單
        </button>
      </div>
      <div v-else class="product-grid">
        <article
          v-for="p in visible"
          :key="p.id!"
          class="product-card"
          :class="{ unavailable: p.availability === 'SOLD_OUT' }"
          role="button"
          tabindex="0"
          :aria-disabled="busy || customerClosed || p.availability === 'SOLD_OUT'"
          @click="choose(p)"
          @keydown.enter="choose(p)"
          @keydown.space.prevent="choose(p)"
        >
          <div class="product-photo">
            <img
              :src="'/images/' + p.image + '.jpg'"
              :alt="p.category + '示意照片'"
              loading="lazy"
            /><span v-if="p.availability === 'SOLD_OUT'" class="product-badge sold-out">今日售完</span
            ><span v-else-if="p.badge" class="product-badge">{{ p.badge }}</span>
          </div>
          <div class="product-copy">
            <h3>{{ p.name }}</h3>
            <p>{{ p.subtitle }}</p>
            <div>
              <strong>{{ money(p.price) }}</strong
              ><span class="add-product" aria-hidden="true"
                ><Plus :size="19"
              /></span>
            </div>
            <button
              v-if="auth.can('MENU_AVAILABILITY')"
              class="availability-action"
              type="button"
              @click.stop="toggleAvailability(p)"
            >{{ p.availability === "SOLD_OUT" ? "恢復供應" : "標記售完" }}</button>
          </div>
        </article>
      </div>
      <p class="menu-footnote">
        商品照片為分類示意，以門市實際餐點為準。飲品可選擇溫度與甜度。
      </p>
    </section>
    <aside class="cart" :class="{ expanded: mobileCart }" :inert="busy">
      <header>
        <div>
          <ShoppingBag :size="22" />
          <h2>{{ auth.customer ? "你的點餐單" : "本次訂單" }}</h2>
          <span class="count-badge">{{ count }}</span>
        </div>
        <button
          v-if="cart.length"
          class="icon-btn"
          aria-label="清空點餐單"
          @click="cart = []"
        >
          <Trash2 :size="17" />
        </button>
      </header>
      <div class="cart-inner">
        <div class="segmented">
          <button
            :class="{ active: fulfillment === 'TAKEAWAY' }"
            @click="fulfillment = 'TAKEAWAY'"
          >
            外帶</button
          ><button
            :class="{ active: fulfillment === 'DINE_IN' }"
            @click="fulfillment = 'DINE_IN'"
          >
            內用
          </button>
        </div>
        <div class="pickup-info">
          <MapPin :size="15" /><span
            >{{ branch?.name || "請選擇分店"
            }}<small>{{
              fulfillment === "TAKEAWAY" ? "門市自取" : "門市內用"
            }}</small></span
          >
        </div>
        <div v-if="!cart.length" class="cart-empty">
          <Coffee :size="42" :stroke-width="1.1" />
          <h3>好咖啡，等你選。</h3>
          <p>點選喜歡的餐點<br />開始今天的咖啡時光。</p>
        </div>
        <div v-else class="cart-items">
          <article
            v-for="(l, i) in cart"
            :key="l.productId + l.optionIds.join(',')"
            class="cart-item"
          >
            <div class="cart-item-title">
              <b>{{ l.name }}</b
              ><strong>{{ money((l.unitPrice + l.optionsPrice) * l.quantity) }}</strong>
            </div>
            <small>{{ l.options.length ? l.options.map((o) => o.optionName).join(" / ") : "無選項" }}</small>
            <div class="quantity-control">
              <button :aria-label="'減少' + l.name" @click="adjust(i, -1)">
                <Minus :size="13" /></button
              ><span>{{ l.quantity }}</span
              ><button :aria-label="'增加' + l.name" @click="adjust(i, 1)">
                <Plus :size="13" />
              </button>
            </div>
          </article>
        </div>
        <label class="cart-note"
          >訂單備註 <span>選填</span
          ><textarea
            v-model="note"
            maxlength="200"
            placeholder="有什麼想讓我們知道的？"
            rows="2"
          ></textarea></label
        ><label class="payment-choice"
          >付款方式<select v-model="payment">
            <option value="CASH">
              {{ auth.customer ? "櫃台付款" : "現金收銀" }}
            </option>
            <option value="ECPAY" :disabled="!config.enabled">
              {{
                config.enabled
                  ? config.environment === "stage"
                    ? "綠界信用卡（測試環境）"
                    : "信用卡 · 綠界安全付款"
                  : "信用卡（尚未開放）"
              }}
            </option>
          </select></label
        ><label v-if="!auth.customer && payment === 'CASH'"
          >實收金額<input
            v-model.number="tendered"
            type="number"
            min="0"
            max="1000000"
            step="1"
            :placeholder="String(total)"
        /></label>
        <div class="cart-total">
          <span>總計 <small>含稅</small></span
          ><strong>{{ money(total) }}</strong>
        </div>
        <div v-if="!auth.customer && payment === 'CASH'" class="change-row">
          <span>應找零</span
          ><b>{{ money(Math.max(0, (tendered ?? total) - total)) }}</b>
        </div>
        <p v-if="unavailableCart.length" class="error-state">
          點餐單中有 {{ unavailableCart.map((line) => line.name).join("、") }} 已售完或未供應，請先移除。
        </p>
      </div>
      <button
        class="btn primary checkout"
        :disabled="!cart.length || busy || !branchId || customerClosed || !!unavailableCart.length"
        @click="checkout"
      >
        {{
          busy
            ? "訂單處理中…"
            : auth.customer
              ? "確認點餐"
              : payment === "CASH"
                ? "確認收款"
                : "前往付款"
        }}<ArrowRight :size="18" /></button
      ><small class="cart-caption">{{
        payment === "CASH"
          ? auth.customer
            ? "送出後請至櫃台完成付款"
            : "確認收款後，訂單即列入業績報表"
          : "安全付款由綠界科技提供"
      }}</small>
    </aside>
    <button
      class="mobile-cart-bar btn primary"
      @click="mobileCart = !mobileCart"
    >
      <ShoppingBag :size="19" />{{ mobileCart ? "繼續選餐" : "查看點餐單" }} ·
      {{ count }} 項 <strong>{{ money(total) }}</strong></button
    ><Modal v-if="selected" :title="selected.name" @close="selected = null"
      ><div class="product-detail">
        <img
          :src="'/images/' + selected.image + '.jpg'"
          :alt="selected.category + '示意照片'"
        />
        <p class="muted">{{ selected.subtitle }}</p>
        <fieldset v-for="g in selected.optionGroups" :key="g.id!">
          <legend>{{ g.name }} <small>選擇 {{ g.minSelect }}–{{ g.maxSelect }} 項</small></legend>
          <label v-for="item in g.items" :key="item.id!">
            <input
              :type="g.selection === 'SINGLE' ? 'radio' : 'checkbox'"
              :name="'option-' + g.id"
              :checked="selectedOptionIds.includes(item.id!)"
              @change="toggleOption(g.id, item.id, g.selection === 'SINGLE')"
            />{{ item.name }}<span v-if="item.priceDelta"> +{{ money(item.priceDelta) }}</span>
          </label>
        </fieldset>
        <label
          >數量<input
            v-model.number="quantity"
            type="number"
            min="1"
            max="50"
            step="1" /></label
        ><button
          class="btn primary"
          :disabled="
            customerClosed || !Number.isInteger(quantity) || quantity < 1 || quantity > 50 || !optionsValid
          "
          @click="add"
        >
          加入點餐單 <span>{{ money((selected.price + selectedOptionsPrice) * quantity) }}</span>
        </button>
      </div></Modal
    ><Modal v-if="receipt" title="收款完成" @close="receipt = null"
      ><div class="receipt">
        <div class="success-icon"><Check :size="30" /></div>
        <h2>謝謝光臨！</h2>
        <p class="muted">{{ receipt.branchName }} · {{ receipt.id }}</p>
        <div
          v-for="(l, i) in receipt.items"
          :key="i"
          class="receipt-row"
        >
          <span>{{ l.name }} × {{ l.quantity
            }}<small>{{
              l.options.length
                ? l.options.map((o) => o.optionName).join(" / ")
                : [l.temperature, l.sugar].filter(Boolean).join(" / ") || "無選項"
            }}</small></span
          ><b>{{ money(l.lineTotal) }}</b>
        </div>
        <div class="receipt-row">
          <span>合計</span><b>{{ money(receipt.total) }}</b>
        </div>
        <div class="receipt-row">
          <span>實收</span><b>{{ money(receipt.tendered || 0) }}</b>
        </div>
        <div class="receipt-row">
          <span>找零</span
          ><strong>{{ money(receipt.changeAmount || 0) }}</strong>
        </div>
        <button class="btn primary" @click="receipt = null">下一位客人</button
        ><small>此為點餐收據，非統一發票。</small>
      </div></Modal
    >
  </div>
</template>
