import { describe, expect, it } from "vitest";
import { toInvoiceRequest, validateInvoice } from "./invoice";

describe("發票驗證與正規化", () => {
  it.each<[Parameters<typeof validateInvoice>[0], string]>([
    [null, "請選擇發票開立方式"],
    [{ type: "PAPER", email: "a@b.co" }, "發票開立方式不正確"],
    ...[undefined, "", " ", "bad", "a b@c.co", "a@@b.co", "x".repeat(96) + "@a.co"].map((email): [Parameters<typeof validateInvoice>[0], string] => [{ type: "CLOUD", email }, "請輸入正確的發票通知 email"]),
    ...[undefined, "", "/ABC123", "/ABC12345", "/ABC_123"].map((carrier): [Parameters<typeof validateInvoice>[0], string] => [{ type: "MOBILE", email: "a@b.co", carrier }, "手機條碼格式不正確"]),
    ...[undefined, "", "12", "12345678", "12a"].map((loveCode): [Parameters<typeof validateInvoice>[0], string] => [{ type: "DONATE", email: "a@b.co", loveCode }, "捐贈碼格式不正確"]),
  ])("錯誤訊息與後端一致 %#", (input, message) => {
    expect(validateInvoice(input as Parameters<typeof validateInvoice>[0])).toBe(message);
  });
  it.each([
    { type: "CLOUD", email: "a@b.co" },
    { type: "CLOUD", email: "x".repeat(95) + "@a.co" },
    { type: "MOBILE", email: "a@b.co", carrier: " /abc.+-1 " },
    { type: "DONATE", email: "a@b.co", loveCode: " 123 " },
    { type: "DONATE", email: "a@b.co", loveCode: "1234567" },
  ])("有效輸入 %#", input => expect(validateInvoice(input)).toBeNull());
  it.each(["CLOUD", "MOBILE", "DONATE"])("%s 清除無關欄位", type => {
    const value = toInvoiceRequest({ type, email: " a@b.co ", carrier: " /abc1234 ", loveCode: " 123 " });
    expect(value).toEqual({ type, email: "a@b.co", carrier: type === "MOBILE" ? "/ABC1234" : undefined, loveCode: type === "DONATE" ? "123" : undefined });
  });
});
