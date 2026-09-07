import { api } from "../../shared/api";
export async function openEcpay(orderId: string) {
  const p = await api<{ action: string; fields: Record<string, string> }>(
    "/payments/ecpay/" + encodeURIComponent(orderId),
    { method: "POST" },
  );
  if (
    ![
      "https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5",
      "https://payment.ecpay.com.tw/Cashier/AioCheckOut/V5",
    ].includes(p.action)
  )
    throw new Error("付款網址不正確");
  const form = document.createElement("form");
  form.method = "POST";
  form.action = p.action;
  for (const [name, value] of Object.entries(p.fields)) {
    const input = document.createElement("input");
    input.type = "hidden";
    input.name = name;
    input.value = value;
    form.append(input);
  }
  document.body.append(form);
  form.submit();
}
