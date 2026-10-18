import { describe, expect, it } from "vitest";
import CashSessionsView from "./CashSessionsView.vue";
import { branchStaffActor } from "../../shared/testing/fixtures";
import { mountView, stubApi } from "../../shared/testing/harness";
import type { CashSession } from "../../shared/types";

describe("現金班別退款", () => {
  it("目前班別及歷史顯示退款，應有金額直接使用後端", async () => {
    const session: CashSession = { id: "S1", branchId: "B1", status: "OPEN", openingFloat: 1000,
      cashRevenue: 300, orderCount: 3, refundAmount: 100, refundOrders: 1, expectedAmount: 1200,
      countedAmount: null, variance: null, openedBy: "staff", openedByName: "店長", openedAt: 1,
      closedBy: null, closedByName: null, closedAt: null, note: "" };
    const { fetch } = stubApi({
      "/api/cash-sessions/current": session,
      "/api/cash-sessions": { items: [session], nextCursor: null, unassignedCashRevenue: 0, unassignedOrderCount: 0 },
    });
    const wrapper = await mountView(CashSessionsView, { actor: branchStaffActor(), fetch });
    expect(wrapper.get(".session-grid").text()).toContain("現金退款 −$100（1 筆）");
    expect(wrapper.get("tbody").text()).toContain("現金退款 −$100（1 筆）");
    expect(wrapper.get(".primary-metric").text()).toContain("已扣除本班現金退款");
    expect(wrapper.get(".primary-metric").text()).toContain("$1,200");
  });
});
