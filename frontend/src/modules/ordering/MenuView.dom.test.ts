import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import MenuView from "./MenuView.vue";
import {
  branchFixture,
  branchStaffActor,
  customerActor,
  productFixture,
} from "../../shared/testing/fixtures";
import {
  checkoutButton,
  labelByText,
  mountView,
  rowByLabel,
  stubApi,
} from "../../shared/testing/harness";
import type { Actor, Branch, Product } from "../../shared/types";

// 這些測試證明的是「畫面有沒有出現」，不是授權。真正的授權一律在後端；
// 前端把實收欄位藏起來不等於顧客不能送實收金額，那條防線由後端越權測試守。

interface MenuScenario {
  wrapper: VueWrapper;
}

async function mountMenu(
  actor: Actor,
  branches: Branch[] = [branchFixture()],
): Promise<MenuScenario> {
  const product = productFixture();
  const { fetch } = stubApi({
    "/api/branches": branches,
    "/api/payments/config": { enabled: true, environment: "stage" },
    "/api/menu": () => [product],
    "/api/branches/B1/hours": { branchId: "B1", openNow: true, hours: [] },
    "/api/branches/B1/hour-overrides": {
      branchId: "B1",
      from: 20260930,
      to: 20260930,
      overrides: [],
    },
    "/api/branches/B2/hours": { branchId: "B2", openNow: false, hours: [] },
    "/api/branches/B2/hour-overrides": {
      branchId: "B2",
      from: 20260930,
      to: 20260930,
      overrides: [],
    },
    "/api/menu/availability": () => {
      product.availability = "SOLD_OUT";
      return {};
    },
  });
  return { wrapper: await mountView(MenuView, { actor, fetch }) };
}

async function addProduct(wrapper: VueWrapper): Promise<void> {
  await wrapper.get(".product-card").trigger("click");
  const addButton = wrapper
    .findAll("button")
    .find((button) => button.text().includes("加入點餐單"));
  expect(addButton).toBeDefined();
  await addButton!.trigger("click");
  await flushPromises();
}

describe("MenuView 可見性", () => {
  it("門市人員以現金結帳時看得到實收與找零並可確認收款", async () => {
    const { wrapper } = await mountMenu(branchStaffActor());
    await addProduct(wrapper);

    expect(labelByText(wrapper, "實收金額")).not.toBeNull();
    expect(rowByLabel(wrapper, "應找零")).not.toBeNull();
    expect(checkoutButton(wrapper).attributes("disabled")).toBeUndefined();
    expect(checkoutButton(wrapper).text()).toContain("確認收款");
  });

  it("顧客點餐時看不到門市實收與找零欄位", async () => {
    const { wrapper } = await mountMenu(customerActor());
    await addProduct(wrapper);

    expect(labelByText(wrapper, "實收金額")).toBeNull();
    expect(rowByLabel(wrapper, "應找零")).toBeNull();
    expect(checkoutButton(wrapper).text()).toContain("確認點餐");
  });

  it("門市人員輸入優惠碼後改為先建立訂單計算應收", async () => {
    const { wrapper } = await mountMenu(branchStaffActor());
    await addProduct(wrapper);
    await labelByText(wrapper, "優惠碼")!.get("input").setValue("WELCOME");

    expect(labelByText(wrapper, "實收金額")).toBeNull();
    expect(rowByLabel(wrapper, "應找零")).toBeNull();
    expect(checkoutButton(wrapper).text()).toContain("建立訂單並計算應收");
  });

  it("門市人員選擇綠界時看不到實收並前往付款", async () => {
    const { wrapper } = await mountMenu(branchStaffActor());
    await addProduct(wrapper);
    await labelByText(wrapper, "付款方式")!.get("select").setValue("ECPAY");

    expect(labelByText(wrapper, "實收金額")).toBeNull();
    expect(checkoutButton(wrapper).text()).toContain("前往付款");
  });

  it("顧客切換到打烊分店後不能送出點餐", async () => {
    const branches = [
      branchFixture(),
      branchFixture({ id: "B2", name: "高雄門市", openNow: false }),
    ];
    const { wrapper } = await mountMenu(customerActor(), branches);
    await addProduct(wrapper);
    await wrapper.get('select[aria-label="選擇取餐分店"]').setValue("B2");
    await flushPromises();
    await flushPromises();

    expect(checkoutButton(wrapper).attributes("disabled")).toBeDefined();
  });

  it("購物車商品被標記售完後不能結帳並顯示錯誤", async () => {
    const { wrapper } = await mountMenu(branchStaffActor());
    await addProduct(wrapper);
    await wrapper.get("button.availability-action").trigger("click");
    await flushPromises();
    await flushPromises();

    expect(checkoutButton(wrapper).attributes("disabled")).toBeDefined();
    expect(wrapper.find(".error-state").exists()).toBe(true);
  });
});
