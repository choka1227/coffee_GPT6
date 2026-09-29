export type CheckoutPath =
  | "CUSTOMER_PENDING"
  | "ECPAY"
  | "POS_CASH_SINGLE"
  | "POS_CASH_TWO_STAGE";

export function checkoutPath(input: {
  isCustomer: boolean;
  paymentMethod: string;
  discountCode: string;
}): CheckoutPath {
  if (input.paymentMethod === "ECPAY") return "ECPAY";
  const cashAtPos = !input.isCustomer && input.paymentMethod === "CASH";
  if (cashAtPos && input.discountCode.trim().length > 0) return "POS_CASH_TWO_STAGE";
  if (cashAtPos) return "POS_CASH_SINGLE";
  return "CUSTOMER_PENDING";
}

export function validateTendered(input: {
  tendered: number | undefined;
  amountDue: number;
}): { ok: true; tendered: number } | { ok: false; message: string } {
  if (
    input.tendered === undefined ||
    !Number.isInteger(input.tendered) ||
    input.tendered < input.amountDue ||
    input.tendered > 1000000
  ) {
    return { ok: false, message: "請輸入足夠的實收金額" };
  }
  return { ok: true, tendered: input.tendered };
}

export function effectiveTendered(input: {
  path: CheckoutPath;
  tendered: number | undefined;
  amountDue: number;
}): number | undefined {
  return input.path === "POS_CASH_SINGLE" && input.tendered === undefined
    ? input.amountDue
    : input.tendered;
}

export function amountDue(input: {
  path: CheckoutPath;
  cartTotal: number;
  pendingOrderTotal: number | null;
}): number {
  if (input.pendingOrderTotal !== null) return input.pendingOrderTotal;
  if (input.path === "POS_CASH_SINGLE") return input.cartTotal;
  throw new Error("尚無後端計算的應收金額");
}

export function changeAmount(input: {
  tendered: number | undefined;
  amountDue: number;
}): number {
  return Math.max(0, (input.tendered ?? input.amountDue) - input.amountDue);
}

export type CheckoutBlock =
  | { blocked: false }
  | { blocked: true; message: string };

export function checkoutBlock(input: {
  cartLineCount: number;
  branchId: string;
  customerClosed: boolean;
  unavailableCount: number;
}): CheckoutBlock {
  if (input.cartLineCount === 0) return { blocked: true, message: "" };
  if (!input.branchId) return { blocked: true, message: "請先選擇分店" };
  if (input.customerClosed)
    return {
      blocked: true,
      message: "分店目前未營業，請選擇其他分店或於營業時間再下單",
    };
  if (input.unavailableCount > 0)
    return {
      blocked: true,
      message: "點餐單中有商品已售完或本店未供應，請先移除",
    };
  return { blocked: false };
}

export function nextIdempotency(
  previous: { body: string; key: string },
  serializedBody: string,
  newKey: () => string,
): { body: string; key: string } {
  return serializedBody === previous.body
    ? previous
    : { body: serializedBody, key: newKey() };
}
