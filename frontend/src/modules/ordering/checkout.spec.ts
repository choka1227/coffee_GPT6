import { describe, expect, it } from "vitest";
import {
  amountDue,
  changeAmount,
  checkoutBlock,
  checkoutPath,
  effectiveTendered,
  nextIdempotency,
  validateTendered,
} from "./checkout";

describe("checkoutPath", () => {
  it.each([
    [true, "CASH", "", "CUSTOMER_PENDING"],
    [false, "ECPAY", "X10", "ECPAY"],
    [false, "CASH", "  ", "POS_CASH_TWO_STAGE"],
    [false, "CASH", " X10 ", "POS_CASH_TWO_STAGE"],
  ])("判斷四條結帳路徑", (isCustomer, paymentMethod, discountCode, expected) => {
    expect(checkoutPath({ isCustomer, paymentMethod, discountCode })).toBe(expected);
  });
});

describe("amountDue", () => {
  it("優先採用後端應收", () => {
    expect(amountDue({ path: "POS_CASH_SINGLE", cartTotal: 140, pendingOrderTotal: 126 })).toBe(126);
  });
  it("單段式無後端應收時採購物車小計", () => {
    expect(amountDue({ path: "POS_CASH_SINGLE", cartTotal: 140, pendingOrderTotal: null })).toBe(140);
  });
  it("兩段式無後端應收時拒絕猜測", () => {
    expect(() => amountDue({ path: "POS_CASH_TWO_STAGE", cartTotal: 140, pendingOrderTotal: null })).toThrow();
  });
});

describe("validateTendered", () => {
  const invalid = [undefined, 100.5, 99, 1000001];
  it.each(invalid)("拒絕不合法實收 %s", (tendered) => {
    expect(validateTendered({ tendered, amountDue: 100 })).toEqual({
      ok: false,
      message: "請輸入足夠的實收金額",
    });
  });
  it.each([100, 101])("接受足額整數 %s", (tendered) => {
    expect(validateTendered({ tendered, amountDue: 100 })).toEqual({ ok: true, tendered });
  });
});

describe("effectiveTendered", () => {
  it("單段式未輸入時帶入應收", () => {
    expect(effectiveTendered({ path: "POS_CASH_SINGLE", tendered: undefined, amountDue: 140 })).toBe(140);
  });
  it("兩段式未輸入時保持未輸入", () => {
    expect(effectiveTendered({ path: "POS_CASH_TWO_STAGE", tendered: undefined, amountDue: 126 })).toBeUndefined();
  });
  it.each(["POS_CASH_SINGLE", "POS_CASH_TWO_STAGE"] as const)("%s 保留已輸入值", (path) => {
    expect(effectiveTendered({ path, tendered: 150, amountDue: 140 })).toBe(150);
  });
});

describe("changeAmount", () => {
  it.each([
    [undefined, 0],
    [126, 0],
    [130, 4],
    [100, 0],
  ])("實收 %s 時找零為 %s", (tendered, expected) => {
    expect(changeAmount({ tendered, amountDue: 126 })).toBe(expected);
  });
});

describe("checkoutBlock", () => {
  const valid = { cartLineCount: 1, branchId: "B1", customerClosed: false, unavailableCount: 0 };
  it("空車靜默阻擋且優先", () => {
    expect(checkoutBlock({ ...valid, cartLineCount: 0, branchId: "", customerClosed: true, unavailableCount: 1 })).toEqual({ blocked: true, message: "" });
  });
  it("無分店優先於未營業與售罄", () => {
    expect(checkoutBlock({ ...valid, branchId: "", customerClosed: true, unavailableCount: 1 })).toEqual({ blocked: true, message: "請先選擇分店" });
  });
  it("未營業優先於售罄", () => {
    expect(checkoutBlock({ ...valid, customerClosed: true, unavailableCount: 1 })).toEqual({ blocked: true, message: "分店目前未營業，請選擇其他分店或於營業時間再下單" });
  });
  it("售罄時阻擋", () => {
    expect(checkoutBlock({ ...valid, unavailableCount: 1 })).toEqual({ blocked: true, message: "點餐單中有商品已售完或本店未供應，請先移除" });
  });
  it("條件均通過時放行", () => expect(checkoutBlock(valid)).toEqual({ blocked: false }));
});

describe("nextIdempotency", () => {
  it("相同 request body 沿用原 key 且不產生新 key", () => {
    let calls = 0;
    const previous = { body: '{"branchId":"B1"}', key: "same-key" };
    expect(
      nextIdempotency(previous, previous.body, () => {
        calls += 1;
        return "new-key";
      }),
    ).toBe(previous);
    expect(calls).toBe(0);
  });

  it("request body 改變時只產生一次新 key", () => {
    let calls = 0;
    expect(
      nextIdempotency({ body: "old", key: "old-key" }, "new", () => {
        calls += 1;
        return "new-key";
      }),
    ).toEqual({ body: "new", key: "new-key" });
    expect(calls).toBe(1);
  });
});

describe("G07 驗收 21b／22／23 自動化替代", () => {
  it("21b 使用後端折後應收並正確找零", () => {
    const due = amountDue({
      path: "POS_CASH_TWO_STAGE",
      cartTotal: 140,
      pendingOrderTotal: 126,
    });
    expect(due).toBe(126);
    expect(changeAmount({ tendered: 130, amountDue: due })).toBe(4);
  });

  it("22 帶碼兩段式在後端應收產生前不得拿小計充當應收", () => {
    const path = checkoutPath({
      isCustomer: false,
      paymentMethod: "CASH",
      discountCode: "X10",
    });
    expect(path).toBe("POS_CASH_TWO_STAGE");
    expect(() => amountDue({ path, cartTotal: 140, pendingOrderTotal: null })).toThrow();
  });

  it("23 POS 現金無論有無優惠碼都維持兩段式與未輸入", () => {
    const withoutCode = checkoutPath({
      isCustomer: false,
      paymentMethod: "CASH",
      discountCode: "",
    });
    expect(withoutCode).toBe("POS_CASH_TWO_STAGE");
    expect(() =>
      amountDue({ path: withoutCode, cartTotal: 140, pendingOrderTotal: null }),
    ).toThrow();

    const twoStageTendered = effectiveTendered({
      path: withoutCode,
      tendered: undefined,
      amountDue: 126,
    });
    expect(twoStageTendered).toBeUndefined();
    expect(validateTendered({ tendered: twoStageTendered, amountDue: 126 })).toEqual({
      ok: false,
      message: "請輸入足夠的實收金額",
    });
  });
});

it("純函式模組不依賴 Vue、API 或 identity store", () => {
  const source = import.meta.glob("./checkout.ts", {
    query: "?raw",
    import: "default",
    eager: true,
  })["./checkout.ts"] as string;
  expect(source).not.toMatch(/from ["']vue["']/);
  expect(source).not.toContain("shared/api");
  expect(source).not.toContain("identity/store");
});

it("建立訂單 request body 不含任何金額欄位", () => {
  const source = import.meta.glob("./MenuView.vue", {
    query: "?raw",
    import: "default",
    eager: true,
  })["./MenuView.vue"] as string;
  const body = source.match(/const body = \{([\s\S]*?)\n    \};/)?.[1];
  expect(body).toBeDefined();
  expect(body).toContain("branchId: branchId.value");
  expect(body).toContain("fulfillment: fulfillment.value");
  expect(body).toContain("paymentMethod: payment.value");
  expect(body).toContain("note: note.value");
  expect(body).toContain("discountCode: discountCode.value");
  expect(body).toContain("items: cart.value.map(({ productId, quantity, optionIds })");
  expect(body).toContain("productId,");
  expect(body).toContain("quantity,");
  expect(body).toContain("optionIds,");
  expect(body).not.toMatch(/\b(total|subtotal|discountAmount|tendered|changeAmount|unitPrice|lineTotal|optionsPrice)\b/);
});
