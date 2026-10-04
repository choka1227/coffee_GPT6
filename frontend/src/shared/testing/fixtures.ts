import type { Actor, Branch, Order, Product } from "../types";

const ALL_PERMISSIONS = [
  "ORDER_CREATE",
  "PAYMENT_RECONCILE",
  "POS_ORDER",
  "ORDER_MANAGE",
  "REPORT_STORE",
  "REPORT_ALL",
  "BRANCH_MANAGE",
  "BRANCH_HOURS_OVERRIDE",
  "ACCOUNT_MANAGE",
  "ROLE_MANAGE",
  "MENU_MANAGE",
  "MENU_AVAILABILITY",
  "AUDIT_VIEW",
  "CASH_SESSION",
];

export function customerActor(): Actor {
  return {
    id: "customer",
    username: "customer@coffee.local",
    name: "測試顧客",
    role: "CUSTOMER",
    scope: "SELF",
    branchId: null,
    permissions: ["ORDER_CREATE"],
  };
}

export function branchStaffActor(): Actor {
  return {
    id: "cashier",
    username: "cashier@coffee.local",
    name: "測試收銀員",
    role: "CASHIER",
    scope: "BRANCH",
    branchId: "B1",
    permissions: [
      "ORDER_CREATE",
      "POS_ORDER",
      "ORDER_MANAGE",
      "MENU_AVAILABILITY",
      "CASH_SESSION",
    ],
  };
}

export function globalActor(): Actor {
  return {
    id: "hq",
    username: "hq@coffee.local",
    name: "測試總部人員",
    role: "HQ",
    scope: "GLOBAL",
    branchId: null,
    permissions: [...ALL_PERMISSIONS],
  };
}

export function branchFixture(overrides: Partial<Branch> = {}): Branch {
  return {
    id: "B1",
    name: "台北門市",
    address: "台北市測試路 1 號",
    phone: "02-0000-0000",
    active: true,
    monthlyTarget: 300000,
    openNow: true,
    orderableNow: true,
    minutesUntilLastOrder: null,
    ...overrides,
  };
}

export function productFixture(overrides: Partial<Product> = {}): Product {
  return {
    id: "P1",
    name: "經典拿鐵",
    subtitle: "濃縮咖啡與鮮奶",
    category: "經典咖啡",
    price: 140,
    cost: 55,
    image: "latte",
    badge: "",
    active: true,
    availability: "AVAILABLE",
    remaining: null,
    optionGroups: [],
    ...overrides,
  };
}

export function orderFixture(overrides: Partial<Order> = {}): Order {
  return {
    id: "O1",
    branchId: "B1",
    branchName: "台北門市",
    accountId: "cashier",
    status: "PENDING_PAYMENT",
    fulfillment: "TAKEAWAY",
    paymentMethod: "CASH",
    total: 126,
    subtotal: 140,
    discountAmount: 14,
    itemDiscountAmount: 0,
    discount: {
      code: "WELCOME",
      name: "新客優惠",
      kind: "PERCENT",
      percent: 10,
      amount: 0,
      discountAmount: 14,
    },
    itemPromotion: null,
    note: "",
    createdAt: 1,
    paidAt: null,
    tendered: null,
    changeAmount: null,
    items: [
      {
        productId: "P1",
        name: "經典拿鐵",
        category: "經典咖啡",
        unitPrice: 140,
        quantity: 1,
        temperature: null,
        sugar: null,
        optionsPrice: 0,
        lineTotal: 140,
        discountAmount: 0,
        options: [],
      },
    ],
    ...overrides,
  };
}
