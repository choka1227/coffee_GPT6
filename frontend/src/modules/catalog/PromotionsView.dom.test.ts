import { flushPromises } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import PromotionsView from "./PromotionsView.vue";
import { branchFixture, globalActor, productFixture } from "../../shared/testing/fixtures";
import { labelByText, mountView, stubApi, type StubbedRequest } from "../../shared/testing/harness";
import type { PromotionRule } from "../../shared/types";

const itemRule: PromotionRule = {
  id: "PROMO-1",
  name: "拿鐵九折",
  kind: "ITEM_PERCENT",
  percent: 10,
  nth: 0,
  targetKind: "PRODUCT",
  productId: "P1",
  category: null,
  branchId: null,
  startsAt: null,
  endsAt: null,
  active: true,
};

async function mountPromotions(rules: PromotionRule[] = [itemRule]) {
  const { fetch, requests } = stubApi({
    "/api/promotions": (request: StubbedRequest) => request.method === "GET" ? rules : request.body,
    "/api/branches": [branchFixture()],
    "/api/menu": [productFixture()],
  });
  return { wrapper: await mountView(PromotionsView, { actor: globalActor(), fetch }), requests };
}

describe("PromotionsView", () => {
  it("渲染規則清單並依型態顯示或隱藏 N 欄位", async () => {
    const { wrapper } = await mountPromotions();
    expect(wrapper.text()).toContain("拿鐵九折");
    expect(wrapper.text()).toContain("每件折 10%");

    await wrapper.get("button.primary").trigger("click");
    expect(labelByText(wrapper, "N")).toBeNull();
    await labelByText(wrapper, "型態")!.get("select").setValue("NTH_PERCENT");
    expect(labelByText(wrapper, "N")).not.toBeNull();
    await labelByText(wrapper, "型態")!.get("select").setValue("ITEM_PERCENT");
    expect(labelByText(wrapper, "N")).toBeNull();
  });

  it("可停用既有規則並送回後端", async () => {
    const { wrapper, requests } = await mountPromotions();
    await wrapper.get("button.secondary").trigger("click");
    await labelByText(wrapper, "啟用")!.get("input").setValue(false);
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    const write = requests.find((request) => request.path === "/api/promotions" && request.method === "POST");
    expect(write).toBeDefined();
    expect((write!.body as PromotionRule).active).toBe(false);
  });
});
