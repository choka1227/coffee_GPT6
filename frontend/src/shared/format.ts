export const money = (n: number) =>
  new Intl.NumberFormat("zh-TW", {
    style: "currency",
    currency: "TWD",
    maximumFractionDigits: 0,
  }).format(n);
export const number = (n: number) => new Intl.NumberFormat("zh-TW").format(n);
export const dateTime = (n: number) =>
  new Intl.DateTimeFormat("zh-TW", {
    timeZone: "Asia/Taipei",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(n);
export const currentMonth = () =>
  new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Asia/Taipei",
    year: "numeric",
    month: "2-digit",
  }).format(new Date());
export const statuses: Record<string, string> = {
  PENDING_PAYMENT: "待付款",
  PAID: "已付款",
  PREPARING: "製作中",
  READY: "可取餐",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
};
export const permissions: Record<string, string> = {
  ORDER_CREATE: "點餐",
  POS_ORDER: "櫃台收銀",
  ORDER_MANAGE: "門市訂單",
  REPORT_STORE: "單店業績",
  REPORT_ALL: "跨店業績",
  BRANCH_MANAGE: "分店管理",
  ACCOUNT_MANAGE: "帳號管理",
  ROLE_MANAGE: "角色與功能配置",
  MENU_MANAGE: "菜單管理",
};
export const roleNames: Record<string, string> = {
  CUSTOMER: "客人",
  CASHIER: "收銀員",
  MANAGER: "店長",
  HQ: "總部人員",
};
export function csv(name: string, rows: (string | number)[][]) {
  const body =
    "\uFEFF" +
    rows
      .map((row) =>
        row
          .map(
            (v) =>
              '"' +
              String(
                typeof v === "string" && /^[=+@\-\t\r]/.test(v) ? "'" + v : v,
              ).replaceAll('"', '""') +
              '"',
          )
          .join(","),
      )
      .join("\r\n");
  const url = URL.createObjectURL(
    new Blob([body], { type: "text/csv;charset=utf-8" }),
  );
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}
