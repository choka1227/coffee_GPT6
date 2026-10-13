import { describe, expect, it } from "vitest";
import OrdersView from "./OrdersView.vue";
import { branchStaffActor, orderFixture } from "../../shared/testing/fixtures";
import { mountView, stubApi } from "../../shared/testing/harness";

async function mountOrders(discountAmount: number) {
  const order = orderFixture({
    itemDiscountAmount: discountAmount,
    itemPromotion: discountAmount > 0 ? {
      promotionId: "PROMO-1",
      name: "拿鐵九折",
      kind: "ITEM_PERCENT",
      percent: 10,
      nth: 0,
      discountedUnits: 1,
      discountAmount,
    } : null,
    items: [{ ...orderFixture().items[0], discountAmount }],
  });
  const { fetch } = stubApi({
    "/api/orders": { items: [order], nextCursor: null },
    "/api/payments/config": { enabled: true, environment: "stage", expire: { enabled: false, checkoutWindowMinutes: 10, afterMinutes: 40 } },
  });
  const wrapper = await mountView(OrdersView, { actor: branchStaffActor(), fetch });
  await wrapper.get("button.order-id").trigger("click");
  return wrapper;
}

describe("OrdersView 品項促銷折抵", () => {
  it("品項折抵大於零時顯示折抵列", async () => {
    const wrapper = await mountOrders(14);
    expect(wrapper.get(".receipt").text()).toContain("促銷折抵");
    expect(wrapper.get(".receipt").text()).toContain("14");
  });

  it("品項折抵為零時不顯示折抵列", async () => {
    const wrapper = await mountOrders(0);
    expect(wrapper.get(".receipt").text()).not.toContain("促銷折抵");
  });
});

describe("OrdersView 線上付款逾時提示", () => {
  async function mountEcpay(expireEnabled: boolean) {
    const order = orderFixture({ paymentMethod: "ECPAY", status: "PENDING_PAYMENT" });
    const { fetch } = stubApi({
      "/api/orders": { items: [order], nextCursor: null },
      "/api/payments/config": {
        enabled: true,
        environment: "stage",
        expire: { enabled: expireEnabled, checkoutWindowMinutes: 10, afterMinutes: 40 },
      },
    });
    const wrapper = await mountView(OrdersView, { actor: branchStaffActor(), fetch });
    await wrapper.get("button.order-id").trigger("click");
    return wrapper;
  }

  it("啟用逾時作廢時顯示付款窗口與作廢門檻", async () => {
    expect((await mountEcpay(true)).text()).toContain("10 分鐘內開啟付款");
    expect((await mountEcpay(true)).text()).toContain("40 分鐘仍未付款將自動作廢");
  });

  it("停用逾時作廢時保留既有提示", async () => {
    expect((await mountEcpay(false)).text()).toContain("若已完成付款，請稍候重新整理，等待金流通知。");
  });
});
