import type { InvoiceRequest } from "../../shared/types";

export interface InvoiceInput { type?: string; email?: string; carrier?: string; loveCode?: string }
export function validateInvoice(input: InvoiceInput | null | undefined): string | null {
  if (!input) return "請選擇發票開立方式";
  if (!["CLOUD", "MOBILE", "DONATE"].includes(input.type || "")) return "發票開立方式不正確";
  const email = input.email?.trim() || "";
  if (!email || email.length > 100 || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email))
    return "請輸入正確的發票通知 email";
  if (input.type === "MOBILE" && !/^\/[0-9A-Z.+-]{7}$/.test(input.carrier?.trim().toUpperCase() || ""))
    return "手機條碼格式不正確";
  if (input.type === "DONATE" && !/^[0-9]{3,7}$/.test(input.loveCode?.trim() || ""))
    return "捐贈碼格式不正確";
  return null;
}
export function toInvoiceRequest(form: InvoiceInput): InvoiceRequest {
  return {
    type: form.type as InvoiceRequest["type"], email: form.email?.trim() || "",
    carrier: form.type === "MOBILE" ? form.carrier?.trim().toUpperCase() : undefined,
    loveCode: form.type === "DONATE" ? form.loveCode?.trim() : undefined,
  };
}
