import { describe, expect, it } from "vitest";
import { flushPromises } from "@vue/test-utils";
import OrdersView from "./OrdersView.vue";
import { branchStaffActor, customerActor, orderFixture } from "../../shared/testing/fixtures";
import { mountView, stubApi } from "../../shared/testing/harness";
import type { Actor, Invoice, Order } from "../../shared/types";

function manager(): Actor {
  return { ...branchStaffActor(), role: "MANAGER", permissions: [...branchStaffActor().permissions, "ORDER_REFUND"] };
}
async function setup(overrides: Partial<Order> = {}, inv: Partial<Invoice> = {}, actor = manager()) {
  let order = orderFixture({ status: "PAID", paidAt: 1, ...overrides });
  const { fetch, requests } = stubApi({
    "/api/orders": () => ({ items: [order], nextCursor: null }),
    "/api/payments/config": { enabled: true, invoice: { enabled: true }, expire: { enabled: false } },
    "/api/payments/invoices/order/O1": { status: "ISSUED", invoiceNo: "AB12345678", reversalKind: null, reversalStatus: null, ...inv },
    "/api/orders/O1/refund": () => {
      order = { ...order, refundedAt: 1000, refundedBy: "manager", refundReason: "做錯餐點" }; return order;
    },
  });
  const wrapper = await mountView(OrdersView, { actor, fetch });
  await wrapper.get("button.order-id").trigger("click"); await flushPromises();
  return { wrapper, requests };
}
describe("退款畫面", () => {
  it("已退款顯示標籤、時間、原因、執行人，沒有推進或退款按鈕", async () => {
    const { wrapper } = await setup({ refundedAt: 1000, refundedBy: "manager", refundReason: "做錯餐點" });
    expect(wrapper.findAll(".status").every(s => s.text() === "已退款")).toBe(true);
    expect(wrapper.get(".refund-info").text()).toContain("退款時間：");
    expect(wrapper.get(".refund-info").text()).toContain("退款原因：做錯餐點");
    expect(wrapper.get(".refund-info").text()).toContain("執行人：manager");
    expect(wrapper.text()).not.toContain("開始製作");
    expect(wrapper.find(".refund-button").exists()).toBe(false);
  });
  it("收銀及顧客不顯示退款入口", async () => {
    for (const actor of [branchStaffActor(), customerActor()]) {
      const { wrapper } = await setup({}, {}, actor);
      expect(wrapper.find(".refund-button").exists()).toBe(false);
      wrapper.unmount();
    }
  });
  it("現金提示退款額，本文只有原因與紀錄，不含任何金額，成功刷新明細", async () => {
    const { wrapper, requests } = await setup();
    await wrapper.get(".refund-button").trigger("click");
    expect(wrapper.get(".refund-form").text()).toContain("請從錢櫃退還 $126 給顧客");
    expect(wrapper.get(".refund-form").findAll("input")).toHaveLength(1);
    expect(wrapper.get(".refund-form button").attributes("disabled")).toBeDefined();
    await wrapper.get(".refund-form input").setValue("  做錯餐點  ");
    await wrapper.get(".refund-form").trigger("submit"); await flushPromises();
    const post = requests.find(r => r.path === "/api/orders/O1/refund");
    expect(post).toMatchObject({ method: "POST", body: { reason: "做錯餐點", providerNote: null } });
    expect(Object.keys(post!.body as object).sort()).toEqual(["providerNote", "reason"]);
    expect(post!.headers.get("X-CSRF-TOKEN")).toBe("test-token");
    expect(wrapper.find(".refund-form").exists()).toBe(false);
    expect(wrapper.get(".receipt").text()).toContain("已退款");
  });
  it("線上必須填人工退刷紀錄，空白或超過200字不會送出", async () => {
    const { wrapper, requests } = await setup({ paymentMethod: "ECPAY" });
    await wrapper.get(".refund-button").trigger("click");
    expect(wrapper.get(".refund-form").text()).toContain("請先到綠界廠商後台完成退刷，再回來登記。系統不會自動退刷");
    const inputs = wrapper.get(".refund-form").findAll("input");
    await inputs[0].setValue("做錯餐點"); await inputs[1].setValue("   ");
    await wrapper.get(".refund-form").trigger("submit");
    expect(requests.some(r => r.path.endsWith("/refund"))).toBe(false);
    await inputs[1].setValue("字".repeat(201));
    expect(wrapper.get(".refund-form button").attributes("disabled")).toBeDefined();
    await inputs[1].setValue("後台已退刷");
    await wrapper.get(".refund-form").trigger("submit"); await flushPromises();
    expect(requests).toContainEqual(expect.objectContaining({ path: "/api/orders/O1/refund", method: "POST", body: { reason: "做錯餐點", providerNote: "後台已退刷" } }));
  });
  it.each([
    [{ reversalKind: "VOID", reversalStatus: "DONE" }, "發票已作廢"],
    [{ reversalKind: "ALLOWANCE", reversalStatus: "DONE", allowanceNo: "AL123" }, "發票已折讓（折讓單號 AL123）"],
    [{ reversalKind: null, reversalStatus: "PENDING" }, "發票作廢處理中"],
    [{ reversalKind: "VOID", reversalStatus: "FAILED", reversalLastError: "請人工確認" }, "發票作廢／折讓失敗：請人工確認"],
  ] as const)("發票作廢／折讓狀態與單號", async (invoice, text) => {
    const { wrapper } = await setup({}, invoice);
    expect(wrapper.get(".reversal-status").text()).toBe(text);
  });
  it("NONE不顯示作廢，SKIPPED顯示未開立；顧客不顯示失敗原因", async () => {
    const { wrapper } = await setup({}, { status: "SKIPPED", reversalKind: "NONE", reversalStatus: "DONE" });
    expect(wrapper.find(".reversal-status").exists()).toBe(false);
    expect(wrapper.get(".invoice-status").text()).toBe("未開立"); wrapper.unmount();
    const customer = await setup({}, { reversalStatus: "FAILED", reversalLastError: "員工處理原因" }, customerActor());
    expect(customer.wrapper.text()).not.toContain("員工處理原因");
  });
});
