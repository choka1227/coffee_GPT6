import { describe, expect, it } from "vitest";
import { flushPromises } from "@vue/test-utils";
import ReconciliationView from "./ReconciliationView.vue";
import { globalActor } from "../../shared/testing/fixtures";
import { mountView, stubApi } from "../../shared/testing/harness";

const anomaly = {
  id: "anomaly-1",
  orderId: "E1234567890123456789",
  branchId: "taipei",
  orderTotal: 280,
  tradeAmount: 280,
  providerTradeNo: "trade-1",
  source: "CALLBACK",
  detectedAt: 1760000000000,
};

describe("ReconciliationView 作廢後付款異常", () => {
  it("沒有未結案異常時不顯示異常區塊", async () => {
    const { fetch } = stubApi({
      "/api/payments/reconciliation/pending": { items: [], truncated: false },
      "/api/payments/anomalies": { items: [], truncated: false },
    });
    const wrapper = await mountView(ReconciliationView, { actor: globalActor(), fetch });
    expect(wrapper.text()).not.toContain("作廢後收到付款，需人工退款");
    expect(wrapper.text()).toContain("查單確認未付款或模擬付款");
  });

  it("顯示異常並以指定 URL、method 與 body 結案", async () => {
    let open = true;
    const { fetch, requests } = stubApi({
      "/api/payments/reconciliation/pending": { items: [], truncated: false },
      "/api/payments/anomalies": () => ({ items: open ? [anomaly] : [], truncated: false }),
      "/api/payments/anomalies/anomaly-1/resolve": () => { open = false; return anomaly; },
    });
    const wrapper = await mountView(ReconciliationView, { actor: globalActor(), fetch });
    expect(wrapper.text()).toContain("作廢後收到付款，需人工退款");
    expect(wrapper.text()).toContain("訂單金額：$280");
    expect(wrapper.text()).toContain("綠界金額：$280");
    await wrapper.get("input[maxlength='200']").setValue("已於綠界後台退款");
    await wrapper.findAll("button").find((button) => button.text() === "已處理")!.trigger("click");
    await flushPromises();
    expect(requests).toContainEqual(expect.objectContaining({
      path: "/api/payments/anomalies/anomaly-1/resolve",
      method: "POST",
      body: { note: "已於綠界後台退款" },
    }));
    expect(wrapper.text()).not.toContain("作廢後收到付款，需人工退款");
  });
});

describe("發票作廢折讓失敗", () => {
  it("顯示訂單發票方式原因及200筆提示，POST重試後刷新", async () => {
    let failed = true;
    const { fetch, requests } = stubApi({
      "/api/payments/reconciliation/pending": { items: [], truncated: false },
      "/api/payments/anomalies": { items: [], truncated: false },
      "/api/payments/invoices/failed": { items: [], truncated: false },
      "/api/payments/invoices/reversals/failed": () => ({ items: failed ? [{ orderId: "O1", invoiceNo: "AB12345678", kind: "ALLOWANCE", lastError: "查不到發票" }] : [], truncated: failed }),
      "/api/payments/invoices/order/O1/reversal/retry": () => { failed = false; return {}; },
    });
    const wrapper = await mountView(ReconciliationView, { actor: globalActor(), fetch });
    const block = wrapper.get(".reversal-failures");
    expect(block.text()).toContain("發票作廢／折讓失敗");
    expect(block.text()).toContain("O1"); expect(block.text()).toContain("AB12345678");
    expect(block.text()).toContain("方式：折讓"); expect(block.text()).toContain("查不到發票");
    expect(block.text()).toContain("前 200 筆");
    await block.get("button").trigger("click"); await flushPromises();
    expect(requests).toContainEqual(expect.objectContaining({ path: "/api/payments/invoices/order/O1/reversal/retry", method: "POST" }));
    expect(requests.find(r => r.path.endsWith("/reversal/retry"))!.headers.get("X-CSRF-TOKEN")).toBe("test-token");
    expect(wrapper.find(".reversal-failures").exists()).toBe(false);
  });
});
