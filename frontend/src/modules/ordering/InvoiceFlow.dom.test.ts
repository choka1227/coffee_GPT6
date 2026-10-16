import { flushPromises } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import MenuView from "./MenuView.vue";
import OrdersView from "./OrdersView.vue";
import ReconciliationView from "../payments/ReconciliationView.vue";
import { branchFixture, branchStaffActor, customerActor, globalActor, orderFixture, productFixture } from "../../shared/testing/fixtures";
import { checkoutButton, labelByText, mountView, stubApi } from "../../shared/testing/harness";
import type { Actor } from "../../shared/types";

beforeEach(() => localStorage.clear());
afterEach(() => vi.restoreAllMocks());

async function menu(enabled: boolean, actor: Actor = customerActor()) {
  const stub = stubApi({
    "/api/branches": [branchFixture()], "/api/menu": [productFixture()],
    "/api/promotions/active": [],
    "/api/payments/config": { enabled: false, environment: "stage", invoice: { enabled } },
    "/api/branches/B1/hours": { hours: [], openNow: true, orderableNow: true, lastOrderMinutes: 0, minutesUntilLastOrder: null },
    "/api/branches/B1/hour-overrides": { overrides: [] },
    "/api/orders": orderFixture(),
    "/api/orders/O1/cash": orderFixture({ paidAt: 2, status: "PAID" }),
    "/api/orders/preview": { subtotal: 140, total: 140, itemDiscountAmount: 0, codeDiscountAmount: 0, discountAmount: 0, items: [] },
  });
  const wrapper = await mountView(MenuView, { actor, fetch: stub.fetch });
  await wrapper.get(".product-card").trigger("click");
  await wrapper.findAll("button").find(b => b.text().includes("加入點餐單"))!.trigger("click");
  await flushPromises();
  return { ...stub, wrapper };
}

describe("發票結帳表單", () => {
  it("停用時沒有欄位或 payload，啟用時驗證失敗不送單", async () => {
    const disabled = await menu(false);
    expect(disabled.wrapper.find(".invoice-fields").exists()).toBe(false);
    await checkoutButton(disabled.wrapper).trigger("click"); await flushPromises();
    const order = disabled.requests.find(r => r.path === "/api/orders" && r.method === "POST")!;
    expect(order.body).not.toHaveProperty("invoice"); disabled.wrapper.unmount();
    const enabled = await menu(true);
    expect(enabled.wrapper.get(".invoice-fields").text()).toContain("發票通知 email");
    await checkoutButton(enabled.wrapper).trigger("click"); await flushPromises();
    expect(enabled.requests.filter(r => r.path === "/api/orders" && r.method === "POST")).toHaveLength(0);
  });

  it.each(["CLOUD", "MOBILE", "DONATE"])("%s 切換欄位並只送相關資料", async type => {
    const { wrapper, requests } = await menu(true);
    await labelByText(wrapper, "發票開立方式")!.get("select").setValue(type);
    await labelByText(wrapper, "發票通知 email")!.get("input").setValue(" user@example.test ");
    expect(!!labelByText(wrapper, "手機條碼")).toBe(type === "MOBILE");
    expect(!!labelByText(wrapper, "捐贈碼")).toBe(type === "DONATE");
    if (type === "MOBILE") {
      await labelByText(wrapper, "手機條碼")!.get("input").setValue("/abc1234");
      expect((labelByText(wrapper, "手機條碼")!.get("input").element as HTMLInputElement).value).toBe("/ABC1234");
    }
    if (type === "DONATE") await labelByText(wrapper, "捐贈碼")!.get("input").setValue("123");
    await checkoutButton(wrapper).trigger("click"); await flushPromises();
    const body = requests.find(r => r.path === "/api/orders" && r.method === "POST")!.body as { invoice: unknown };
    expect(body.invoice).toEqual({ type, email: "user@example.test",
      ...(type === "MOBILE" ? { carrier: "/ABC1234" } : {}), ...(type === "DONATE" ? { loveCode: "123" } : {}) });
    expect(JSON.parse(localStorage.getItem("coffee.invoice.last")!).email).toBe("user@example.test");
  });

  it("顧客預填，POS不讀取也不覆寫記憶且成功後清除表單", async () => {
    const stored = JSON.stringify({ email: "saved@example.test", carrier: "/ABC1234" });
    localStorage.setItem("coffee.invoice.last", stored);
    const customer = await menu(true);
    expect((labelByText(customer.wrapper, "發票通知 email")!.get("input").element as HTMLInputElement).value).toBe("saved@example.test");
    customer.wrapper.unmount();
    const pos = await menu(true, branchStaffActor());
    expect((labelByText(pos.wrapper, "發票通知 email")!.get("input").element as HTMLInputElement).value).toBe("");
    await labelByText(pos.wrapper, "發票通知 email")!.get("input").setValue("walkin@example.test");
    await checkoutButton(pos.wrapper).trigger("click"); await flushPromises();
    expect(localStorage.getItem("coffee.invoice.last")).toBe(stored);
    await labelByText(pos.wrapper, "實收金額")!.get("input").setValue("150");
    await checkoutButton(pos.wrapper).trigger("click"); await flushPromises();
    expect((labelByText(pos.wrapper, "發票通知 email")!.get("input").element as HTMLInputElement).value).toBe("");
  });

  it("localStorage無法使用時仍可下單", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("blocked"); });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("blocked"); });
    const { wrapper, requests } = await menu(true);
    await labelByText(wrapper, "發票通知 email")!.get("input").setValue("user@example.test");
    await checkoutButton(wrapper).trigger("click"); await flushPromises();
    expect(requests.filter(r => r.path === "/api/orders" && r.method === "POST")).toHaveLength(1);
  });
});

describe("發票狀態與重試", () => {
  it.each([
    ["ISSUED", false, "發票號碼 AB12345678（隨機碼 1234）"],
    ["PENDING", true, "發票開立中"], ["FAILED", true, "發票開立中"],
    ["FAILED", false, "發票開立失敗：拒絕原因"], ["NONE", true, ""],
  ])("訂單明細 %s / customer=%s", async (status, customer, text) => {
    const stub = stubApi({
      "/api/orders": { items: [orderFixture({ paidAt: 2, status: "PAID" })], nextCursor: null },
      "/api/payments/config": { invoice: { enabled: true } },
      "/api/payments/invoices/order/O1": { status, invoiceNo: "AB12345678", randomNumber: "1234", lastError: "拒絕原因" },
    });
    const wrapper = await mountView(OrdersView, { actor: customer ? customerActor() : branchStaffActor(), fetch: stub.fetch });
    await wrapper.get("button.order-id").trigger("click"); await flushPromises();
    if (text) expect(wrapper.get(".invoice-status").text()).toBe(text);
    else expect(wrapper.find(".invoice-status").exists()).toBe(false);
    if (customer) expect(wrapper.text()).not.toContain("拒絕原因");
  });

  it.each([true, false])("對帳頁失敗清單存在=%s，重試使用POST且更新清單", async exists => {
    let failed = exists;
    const stub = stubApi({
      "/api/payments/reconciliation/pending": { items: [], truncated: false },
      "/api/payments/anomalies": { items: [], truncated: false },
      "/api/payments/invoices/failed": () => ({ items: failed ? [{ orderId: "O1", amount: 140, lastError: "拒絕原因", updatedAt: 1 }] : [], truncated: false }),
      "/api/payments/invoices/order/O1/retry": () => { failed = false; return {}; },
    });
    const wrapper = await mountView(ReconciliationView, { actor: globalActor(), fetch: stub.fetch });
    expect(wrapper.find(".invoice-failures").exists()).toBe(exists);
    if (exists) {
      expect(wrapper.get(".invoice-failures").text()).toContain("$140");
      await wrapper.get(".invoice-failures button").trigger("click"); await flushPromises();
      expect(stub.requests).toContainEqual(expect.objectContaining({ path: "/api/payments/invoices/order/O1/retry", method: "POST" }));
      expect(wrapper.find(".invoice-failures").exists()).toBe(false);
    }
  });
});
