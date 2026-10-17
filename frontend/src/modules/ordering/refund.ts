import type { Order } from "../../shared/types";

export function canRefund(order: Pick<Order, "status" | "paidAt" | "refundedAt">, permissions: readonly string[]): boolean {
  return permissions.includes("ORDER_REFUND") && order.paidAt != null
    && order.refundedAt == null && order.status !== "CANCELLED";
}
