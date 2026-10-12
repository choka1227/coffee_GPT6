import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({ api: vi.fn() }));

vi.mock("../../shared/api", () => ({ api: mocks.api }));

import { openEcpay } from "./ecpay";

describe("openEcpay", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("以 stage 網址建立並送出含完整欄位的表單", async () => {
    mocks.api.mockResolvedValue({
      action: "https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5",
      fields: { MerchantID: "3002607", TotalAmount: "120", CheckMacValue: "signed" },
    });
    const submit = vi.spyOn(HTMLFormElement.prototype, "submit").mockImplementation(() => {});

    await openEcpay("order-1");

    const form = document.querySelector("form") as HTMLFormElement;
    expect(form.method).toBe("post");
    expect(form.action).toBe("https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5");
    expect(
      Object.fromEntries(
        [...form.querySelectorAll("input")].map((input) => [input.name, input.value]),
      ),
    ).toEqual({ MerchantID: "3002607", TotalAmount: "120", CheckMacValue: "signed" });
    expect(submit).toHaveBeenCalledOnce();
  });

  it.each([
    "https://payment.ecpay.com.tw/Cashier/AioCheckOut/V5",
    "https://evil.example/Cashier/AioCheckOut/V5",
  ])("拒絕非 stage 付款網址 %s", async (action) => {
    mocks.api.mockResolvedValue({ action, fields: { MerchantID: "3002607" } });
    const submit = vi.spyOn(HTMLFormElement.prototype, "submit").mockImplementation(() => {});

    await expect(openEcpay("order-1")).rejects.toThrow("付款網址不正確");

    expect(document.querySelector("form")).toBeNull();
    expect(submit).not.toHaveBeenCalled();
  });
});
