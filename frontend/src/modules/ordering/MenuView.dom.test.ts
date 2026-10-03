import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import MenuView from "./MenuView.vue";
import {
  branchFixture,
  branchStaffActor,
  customerActor,
  orderFixture,
  productFixture,
} from "../../shared/testing/fixtures";
import {
  checkoutButton,
  labelByText,
  mountView,
  rowByLabel,
  stubApi,
  type StubbedRequest,
} from "../../shared/testing/harness";
import type { Actor, Branch, Product } from "../../shared/types";

// 這些測試證明的是「畫面有沒有出現」，不是授權。真正的授權一律在後端；
// 前端把實收欄位藏起來不等於顧客不能送實收金額，那條防線由後端越權測試守。

interface MenuScenario {
  wrapper: VueWrapper;
  requests: StubbedRequest[];
}

async function mountMenu(
  actor: Actor,
  branches: Branch[] = [branchFixture()],
  product: Product = productFixture(),
): Promise<MenuScenario> {
  const { fetch, requests } = stubApi({
    "/api/branches": branches,
    "/api/payments/config": { enabled: true, environment: "stage" },
    "/api/menu": () => [product],
    "/api/branches/B1/hours": {
      branchId: "B1",
      openNow: true,
      hours: [],
      lastOrderMinutes: 0,
      orderableNow: true,
      minutesUntilLastOrder: null,
    },
    "/api/branches/B1/hour-overrides": {
      branchId: "B1",
      from: 20260930,
      to: 20260930,
      overrides: [],
    },
    "/api/branches/B2/hours": {
      branchId: "B2",
      openNow: false,
      hours: [],
      lastOrderMinutes: 0,
      orderableNow: false,
      minutesUntilLastOrder: null,
    },
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
    "/api/menu/stock": (request: StubbedRequest) => {
      if (request.method === "GET") {
        return [
          {
            branchId: "B1",
            productId: product.id,
            productName: product.name,
            onDate: 20261003,
            quantity: product.remaining,
            remaining: product.remaining,
            updatedAt: null,
            updatedBy: null,
          },
        ];
      }
      const quantity = (request.body as { quantity: number | null }).quantity;
      product.remaining = quantity;
      return {
        branchId: "B1",
        productId: product.id,
        productName: product.name,
        onDate: 20261003,
        quantity,
        remaining: quantity,
        updatedAt: 1,
        updatedBy: actor.id,
      };
    },
    "/api/orders": orderFixture(),
    "/api/orders/O1/cash": orderFixture({
      status: "PAID",
      paidAt: 2,
      tendered: 150,
      changeAmount: 24,
    }),
  });
  return { wrapper: await mountView(MenuView, { actor, fetch }), requests };
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
      branchFixture({
        id: "B2",
        name: "高雄門市",
        openNow: false,
        orderableNow: false,
      }),
    ];
    const { wrapper } = await mountMenu(customerActor(), branches);
    await addProduct(wrapper);
    await wrapper.get('select[aria-label="選擇取餐分店"]').setValue("B2");
    await flushPromises();
    await flushPromises();

    expect(checkoutButton(wrapper).attributes("disabled")).toBeDefined();
  });

  it("顧客切換到仍營業但已停止接單的分店後不能結帳", async () => {
    const branches = [
      branchFixture(),
      branchFixture({
        id: "B2",
        name: "高雄門市",
        openNow: true,
        orderableNow: false,
      }),
    ];
    const { wrapper } = await mountMenu(customerActor(), branches);
    await addProduct(wrapper);
    await wrapper.get('select[aria-label="選擇取餐分店"]').setValue("B2");
    await flushPromises();
    await flushPromises();

    expect(checkoutButton(wrapper).attributes("disabled")).toBeDefined();
    expect(wrapper.text()).toContain("已停止接單");
    expect(wrapper.text()).not.toContain("明日");
  });

  it("顧客在最後點餐前 20 分鐘看得到即將停止提示且仍可結帳", async () => {
    const { wrapper } = await mountMenu(customerActor(), [
      branchFixture({ minutesUntilLastOrder: 20 }),
    ]);
    await addProduct(wrapper);

    expect(wrapper.text()).toContain("即將停止接單");
    expect(wrapper.text()).toContain("距離最後點餐還有 20 分鐘");
    expect(checkoutButton(wrapper).attributes("disabled")).toBeUndefined();
  });

  it("顧客距離最後點餐 45 分鐘時不顯示即將停止提示", async () => {
    const { wrapper } = await mountMenu(customerActor(), [
      branchFixture({ minutesUntilLastOrder: 45 }),
    ]);

    expect(wrapper.text()).not.toContain("即將停止接單");
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

  it("POS 商品有剩餘量時顯示剩餘徽章", async () => {
    const { wrapper } = await mountMenu(
      branchStaffActor(),
      [branchFixture()],
      productFixture({ remaining: 2 }),
    );

    expect(wrapper.get(".remaining-badge").text()).toBe("剩 2 份");
  });

  it("手動售完且仍有剩餘量時只顯示今日售完", async () => {
    const { wrapper } = await mountMenu(
      branchStaffActor(),
      [branchFixture()],
      productFixture({ availability: "SOLD_OUT", remaining: 5 }),
    );

    expect(wrapper.get(".sold-out").text()).toBe("今日售完");
    expect(wrapper.find(".remaining-badge").exists()).toBe(false);
  });

  it("不限量商品不顯示剩餘徽章", async () => {
    const { wrapper } = await mountMenu(branchStaffActor());

    expect(wrapper.find(".remaining-badge").exists()).toBe(false);
  });

  it("門市人員可設定與解除今日備量並由重載菜單更新畫面", async () => {
    const { wrapper, requests } = await mountMenu(branchStaffActor());
    const stockButton = wrapper.findAll("button").find((button) => button.text() === "設定備量");
    expect(stockButton).toBeDefined();
    await stockButton!.trigger("click");
    await flushPromises();

    await labelByText(wrapper, "今日備量")!.get("input").setValue("5");
    await wrapper.get("form.stock-form").trigger("submit");
    await flushPromises();
    await flushPromises();

    expect(
      requests.find(
        (request) => request.path === "/api/menu/stock" && request.method === "POST",
      )?.body,
    ).toEqual({ branchId: "B1", productId: "P1", quantity: 5 });
    expect(wrapper.get(".remaining-badge").text()).toBe("剩 5 份");

    await wrapper.findAll("button").find((button) => button.text() === "設定備量")!.trigger("click");
    await flushPromises();
    await wrapper.findAll("button").find((button) => button.text() === "解除限量")!.trigger("click");
    await flushPromises();
    await flushPromises();

    const writes = requests.filter(
      (request) => request.path === "/api/menu/stock" && request.method === "POST",
    );
    expect(writes.at(-1)?.body).toEqual({ branchId: "B1", productId: "P1", quantity: null });
    expect(wrapper.find(".remaining-badge").exists()).toBe(false);
  });

  it("顧客模式不顯示設定備量按鈕", async () => {
    const { wrapper } = await mountMenu(customerActor());

    expect(wrapper.findAll("button").some((button) => button.text() === "設定備量")).toBe(false);
  });
});

describe("MenuView 兩段式現金收款", () => {
  it("由後端計算折扣應收並只送出允許的訂單與收款欄位", async () => {
    const { wrapper, requests } = await mountMenu(branchStaffActor());
    await addProduct(wrapper);
    await labelByText(wrapper, "優惠碼")!.get("input").setValue("WELCOME");

    await checkoutButton(wrapper).trigger("click");
    await flushPromises();

    const orderRequest = requests.find(
      (request) => request.path === "/api/orders" && request.method === "POST",
    );
    expect(orderRequest).toBeDefined();
    expect(Object.keys(orderRequest!.body as object).sort()).toEqual(
      [
        "branchId",
        "discountCode",
        "fulfillment",
        "items",
        "note",
        "paymentMethod",
      ].sort(),
    );
    const items = (orderRequest!.body as { items: object[] }).items;
    expect(items).toHaveLength(1);
    expect(Object.keys(items[0]).sort()).toEqual(
      ["optionIds", "productId", "quantity"].sort(),
    );

    expect(rowByLabel(wrapper, "小計")!.text()).toContain("140");
    expect(rowByLabel(wrapper, "優惠折抵")!.text()).toContain("14");
    expect(rowByLabel(wrapper, "應收")!.text()).toContain("126");
    const tendered = labelByText(wrapper, "實收金額");
    expect(tendered).not.toBeNull();
    expect(labelByText(wrapper, "優惠碼")!.get("input").attributes("disabled")).toBeDefined();
    expect(labelByText(wrapper, "付款方式")!.get("select").attributes("disabled")).toBeDefined();
    expect(checkoutButton(wrapper).text()).toContain("確認收款");

    await tendered!.get("input").setValue("150");
    expect(rowByLabel(wrapper, "應找零")!.text()).toContain("24");
    await checkoutButton(wrapper).trigger("click");
    await flushPromises();

    const cashRequest = requests.find(
      (request) => request.path === "/api/orders/O1/cash" && request.method === "POST",
    );
    expect(cashRequest).toBeDefined();
    expect(Object.keys(cashRequest!.body as object)).toEqual(["tendered"]);
    expect(cashRequest!.body).toEqual({ tendered: 150 });
  });
});
