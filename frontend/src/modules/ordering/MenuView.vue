<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
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
import type { Product, Branch, Line, Order } from "../../shared/types";
import { money } from "../../shared/format";
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
  cart = ref<Line[]>([]),
  fulfillment = ref("TAKEAWAY"),
  payment = ref("CASH"),
  note = ref(""),
  tendered = ref<number | undefined>(),
  busy = ref(false),
  mobileCart = ref(false),
  selected = ref<Product | null>(null),
  temperature = ref("熱"),
  sugar = ref("無糖"),
  quantity = ref(1),
  receipt = ref<Order | null>(null),
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
  cart.value.reduce((s, l) => s + l.unitPrice * l.quantity, 0),
);
const count = computed(() => cart.value.reduce((s, l) => s + l.quantity, 0));
const branch = computed(() =>
  branches.value.find((b) => b.id === branchId.value),
);
const permittedBranches = computed(() =>
  auth.customer || auth.user?.scope === "GLOBAL"
    ? branches.value
    : branches.value.filter((b) => b.id === auth.user?.branchId),
);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [p, b, c] = await Promise.all([
      api<Product[]>("/menu"),
      api<Branch[]>("/branches"),
      api<{ enabled: boolean; environment: string }>("/payments/config"),
    ]);
    products.value = p;
    branches.value = b;
    config.value = c;
    if (!branchId.value) branchId.value = b[0]?.id || "";
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
onMounted(load);
function choose(p: Product) {
  if (busy.value) return;
  selected.value = p;
  temperature.value = p.category === "手作烘焙" ? "不適用" : "熱";
  sugar.value = p.category === "手作烘焙" ? "不適用" : "無糖";
  quantity.value = 1;
}
function add() {
  const p = selected.value;
  if (!p?.id) return;
  const existing = cart.value.find(
    (l) =>
      l.productId === p.id &&
      l.temperature === temperature.value &&
      l.sugar === sugar.value,
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
      temperature: temperature.value,
      sugar: sugar.value,
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
      items: cart.value.map(({ productId, quantity, temperature, sugar }) => ({
        productId,
        quantity,
        temperature,
        sugar,
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
              {{ b.name }}
            </option>
          </select>
        </div>
      </div>
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
        <button
          v-for="p in visible"
          :key="p.id!"
          class="product-card"
          @click="choose(p)"
          :disabled="busy"
        >
          <div class="product-photo">
            <img
              :src="'/images/' + p.image + '.jpg'"
              :alt="p.category + '示意照片'"
              loading="lazy"
            /><span v-if="p.badge" class="product-badge">{{ p.badge }}</span>
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
          </div>
        </button>
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
            :key="l.productId + l.temperature + l.sugar"
            class="cart-item"
          >
            <div class="cart-item-title">
              <b>{{ l.name }}</b
              ><strong>{{ money(l.unitPrice * l.quantity) }}</strong>
            </div>
            <small>{{
              l.temperature === "不適用"
                ? "現烤烘焙"
                : l.temperature + " / " + l.sugar
            }}</small>
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
      </div>
      <button
        class="btn primary checkout"
        :disabled="!cart.length || busy || !branchId"
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
        <template v-if="selected.category !== '手作烘焙'"
          ><label
            >溫度<select v-model="temperature">
              <option v-for="t in ['熱', '正常冰', '少冰', '去冰']" :key="t">
                {{ t }}
              </option>
            </select></label
          ><label
            >甜度<select v-model="sugar">
              <option v-for="s in ['無糖', '微糖', '半糖', '正常糖']" :key="s">
                {{ s }}
              </option>
            </select></label
          ></template
        ><label
          >數量<input
            v-model.number="quantity"
            type="number"
            min="1"
            max="50"
            step="1" /></label
        ><button
          class="btn primary"
          :disabled="
            !Number.isInteger(quantity) || quantity < 1 || quantity > 50
          "
          @click="add"
        >
          加入點餐單 <span>{{ money(selected.price * quantity) }}</span>
        </button>
      </div></Modal
    ><Modal v-if="receipt" title="收款完成" @close="receipt = null"
      ><div class="receipt">
        <div class="success-icon"><Check :size="30" /></div>
        <h2>謝謝光臨！</h2>
        <p class="muted">{{ receipt.branchName }} · {{ receipt.id }}</p>
        <div
          v-for="l in receipt.items"
          :key="l.productId + l.temperature + l.sugar"
          class="receipt-row"
        >
          <span>{{ l.name }} × {{ l.quantity }}</span
          ><b>{{ money(l.unitPrice * l.quantity) }}</b>
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
