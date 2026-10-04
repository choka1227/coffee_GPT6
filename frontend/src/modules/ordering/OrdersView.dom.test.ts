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
