import { describe, expect, it } from "vitest";
import { canRefund } from "./refund";

describe("canRefund", () => {
  it.each(["PAID", "PREPARING", "READY", "COMPLETED"])("允許有權限且已付款的%s", status => {
    expect(canRefund({ status, paidAt: 0, refundedAt: null }, ["ORDER_REFUND"])).toBe(true);
  });
  it.each([
    [{ status: "PAID", paidAt: 1, refundedAt: null }, []],
    [{ status: "PENDING_PAYMENT", paidAt: null, refundedAt: null }, ["ORDER_REFUND"]],
    [{ status: "PAID", paidAt: 1, refundedAt: 0 }, ["ORDER_REFUND"]],
    [{ status: "CANCELLED", paidAt: 1, refundedAt: null }, ["ORDER_REFUND"]],
  ] as const)("拒絕無權限、未付款、已退款或已取消", (order, permissions) => {
    expect(canRefund(order, permissions)).toBe(false);
  });
});
