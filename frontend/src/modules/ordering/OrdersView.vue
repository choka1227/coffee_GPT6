<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRoute } from "vue-router";
import {
  RefreshCw,
  Search,
  ClipboardList,
  ArrowRight,
  Check,
} from "lucide-vue-next";
import { api, send } from "../../shared/api";
import type { Order } from "../../shared/types";
import { useAuth } from "../identity/store";
import { money, dateTime, statuses } from "../../shared/format";
import { notify } from "../../shared/notice";
import Modal from "../../shared/Modal.vue";
import { openEcpay } from "../payments/ecpay";
const auth = useAuth(),
  route = useRoute(),
  orders = ref<Order[]>([]),
  loading = ref(false),
  error = ref(""),
  query = ref(""),
  status = ref("ALL"),
  selected = ref<Order | null>(null),
  cashOrder = ref<Order | null>(null),
  tendered = ref(0),
  busy = ref(false);
const visible = computed(() =>
  orders.value.filter(
    (o) =>
      (status.value === "ALL" || o.status === status.value) &&
      (!query.value ||
        (o.id + o.items.map((l) => l.name).join())
          .toLowerCase()
          .includes(query.value.toLowerCase())),
  ),
);
const nextStatus: Record<string, string> = {
  PAID: "PREPARING",
  PREPARING: "READY",
  READY: "COMPLETED",
};
async function load() {
  loading.value = true;
  error.value = "";
  try {
    orders.value = await api<Order[]>("/orders");
    if (route.query.order) {
      const wanted = String(route.query.order);
      const found = orders.value.find((o) => o.id === wanted);
      selected.value =
        found || (await api<Order>("/orders/" + encodeURIComponent(wanted)));
    }
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
onMounted(load);
async function transition(o: Order, next: string) {
  busy.value = true;
  try {
    await send("/orders/" + o.id + "/status", { status: next }, "PATCH");
    notify(next === "CANCELLED" ? "訂單已取消" : "訂單狀態已更新");
    selected.value = null;
    await load();
  } catch (e) {
    notify((e as Error).message);
  } finally {
    busy.value = false;
  }
}
async function cash() {
  if (!cashOrder.value) return;
  busy.value = true;
  try {
    const o = await send<Order>("/orders/" + cashOrder.value.id + "/cash", {
      tendered: tendered.value,
    });
    notify("已收款，找零 " + money(o.changeAmount || 0));
    cashOrder.value = null;
    selected.value = o;
    await load();
  } catch (e) {
    notify((e as Error).message);
  } finally {
    busy.value = false;
  }
}
async function pay(o: Order) {
  busy.value = true;
  try {
    await openEcpay(o.id);
  } catch (e) {
    notify((e as Error).message);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="page-pad">
    <div class="page-heading">
      <div>
        <span class="eyebrow">{{
          auth.customer ? "YOUR COFFEE MOMENTS" : "ORDER MANAGEMENT"
        }}</span>
        <h1>{{ auth.customer ? "我的訂單" : "門市訂單" }}</h1>
        <p class="muted">
          {{
            auth.customer
              ? "查看付款與餐點準備進度。"
              : "掌握每一筆訂單，讓出餐有條不紊。"
          }}
        </p>
      </div>
      <button class="btn secondary" :disabled="loading" @click="load">
        <RefreshCw :size="17" />重新整理
      </button>
    </div>
    <div class="panel">
      <div class="table-toolbar">
        <div class="filter-tabs">
          <button
            v-for="(label, key) in {
              ALL: '全部',
              PENDING_PAYMENT: '待付款',
              PAID: '已付款',
              PREPARING: '製作中',
              READY: '可取餐',
              COMPLETED: '已完成',
              CANCELLED: '已取消',
            }"
            :key="key"
            :class="{ active: status === key }"
            @click="status = key"
          >
            {{ label }}
          </button>
        </div>
        <label class="search-field"
          ><Search :size="17" /><input
            v-model="query"
            placeholder="搜尋編號或餐點"
            aria-label="搜尋訂單"
        /></label>
      </div>
      <div v-if="error" class="error-state" role="alert">{{ error }}</div>
      <div v-else-if="loading" class="loading-state">讀取訂單中…</div>
      <div v-else-if="!visible.length" class="empty-state">
        <ClipboardList :size="40" />
        <h3>目前沒有符合的訂單</h3>
        <RouterLink to="/" class="text-link"
          >前往點餐 <ArrowRight :size="16"
        /></RouterLink>
      </div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>訂單 / 時間</th>
              <th>餐點</th>
              <th>門市 / 取餐</th>
              <th>金額</th>
              <th>狀態</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="o in visible" :key="o.id">
              <td>
                <button class="order-id" @click="selected = o">
                  #{{ o.id.slice(-8) }}</button
                ><small>{{ dateTime(o.createdAt) }}</small>
              </td>
              <td>
                <b>{{ o.items[0]?.name }}</b
                ><small
                  >共 {{ o.items.reduce((s, l) => s + l.quantity, 0) }} 件 ·
                  {{
                    o.paymentMethod === "CASH" ? "現金" : "綠界信用卡"
                  }}</small
                >
              </td>
              <td>
                {{ o.branchName
                }}<small>{{
                  o.fulfillment === "DINE_IN" ? "內用" : "外帶"
                }}</small>
              </td>
              <td class="numeric">
                <b>{{ money(o.total) }}</b>
              </td>
              <td>
                <span class="status" :class="o.status.toLowerCase()">{{
                  statuses[o.status]
                }}</span>
              </td>
              <td>
                <div class="row-actions">
                  <button
                    v-if="
                      o.status === 'PENDING_PAYMENT' &&
                      o.paymentMethod === 'ECPAY'
                    "
                    class="btn small primary"
                    :disabled="busy"
                    @click="pay(o)"
                  >
                    付款</button
                  ><button
                    v-else-if="
                      o.status === 'PENDING_PAYMENT' && auth.can('POS_ORDER')
                    "
                    class="btn small primary"
                    @click="
                      cashOrder = o;
                      tendered = o.total;
                    "
                  >
                    收款</button
                  ><button
                    v-if="!auth.customer && nextStatus[o.status]"
                    class="btn small secondary"
                    :disabled="busy"
                    @click="transition(o, nextStatus[o.status])"
                  >
                    {{
                      {
                        PAID: "開始製作",
                        PREPARING: "通知取餐",
                        READY: "完成取餐",
                      }[o.status]
                    }}</button
                  ><button
                    class="icon-btn"
                    aria-label="查看訂單詳情"
                    @click="selected = o"
                  >
                    <ArrowRight :size="17" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="table-foot">
        顯示最近 100 筆訂單中的 {{ visible.length }} 筆 · 付款狀態以後端確認為準
      </div>
    </div>
  </div>
  <Modal v-if="selected" title="訂單明細" @close="selected = null"
    ><div class="receipt">
      <span class="status" :class="selected.status.toLowerCase()">{{
        statuses[selected.status]
      }}</span>
      <h3>#{{ selected.id }}</h3>
      <p class="muted">
        {{ selected.branchName }} · {{ dateTime(selected.createdAt) }}
      </p>
      <div v-for="(l, i) in selected.items" :key="i" class="receipt-row">
        <span
          >{{ l.name }} × {{ l.quantity
          }}<small>{{ l.temperature }} / {{ l.sugar }}</small></span
        ><b>{{ money(l.unitPrice * l.quantity) }}</b>
      </div>
      <div class="receipt-row">
        <b>合計</b><strong>{{ money(selected.total) }}</strong>
      </div>
      <p v-if="selected.note">備註：{{ selected.note }}</p>
      <p v-if="selected.paidAt" class="muted">
        付款確認：{{ dateTime(selected.paidAt) }}
      </p>
      <p v-else-if="selected.status === 'PENDING_PAYMENT'" class="muted">
        {{
          selected.paymentMethod === "CASH"
            ? "請至櫃台付款，付款後門市將開始準備。"
            : "若已完成付款，請稍候重新整理，等待金流通知。"
        }}
      </p>
      <button
        v-if="
          selected.status === 'PENDING_PAYMENT' &&
          selected.paymentMethod === 'CASH'
        "
        class="btn danger"
        :disabled="busy"
        @click="transition(selected, 'CANCELLED')"
      >
        取消未付款訂單
      </button>
    </div></Modal
  ><Modal v-if="cashOrder" title="現金收款" @close="cashOrder = null"
    ><form class="form-stack" @submit.prevent="cash">
      <div class="payment-amount">
        <span>應收金額</span><strong>{{ money(cashOrder.total) }}</strong>
      </div>
      <label
        >實收金額<input
          v-model.number="tendered"
          type="number"
          :min="cashOrder.total"
          max="1000000"
          step="1"
          required
          autofocus
      /></label>
      <div class="receipt-row">
        <span>應找零</span
        ><strong>{{ money(Math.max(0, tendered - cashOrder.total)) }}</strong>
      </div>
      <button
        class="btn primary"
        :disabled="busy || tendered < cashOrder.total"
      >
        {{ busy ? "確認中…" : "確認已收到現金" }}<Check :size="18" />
      </button></form
  ></Modal>
</template>
