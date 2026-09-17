export interface Actor {
  id: string;
  username: string;
  name: string;
  role: string;
  scope: "SELF" | "BRANCH" | "GLOBAL";
  branchId: string | null;
  permissions: string[];
}
export interface Branch {
  id: string | null;
  name: string;
  address: string;
  phone: string;
  active: boolean;
  monthlyTarget: number;
}
export interface Product {
  id: string | null;
  name: string;
  subtitle: string;
  category: string;
  price: number;
  cost: number;
  image: string;
  badge: string;
  active: boolean;
}
export interface Line {
  productId: string;
  name: string;
  category: string;
  unitPrice: number;
  quantity: number;
  temperature: string;
  sugar: string;
}
export interface Order {
  id: string;
  branchId: string;
  branchName: string;
  accountId: string;
  status: string;
  fulfillment: string;
  paymentMethod: string;
  total: number;
  note: string;
  createdAt: number;
  paidAt: number | null;
  tendered: number | null;
  changeAmount: number | null;
  items: Line[];
}
export interface Role {
  code: string;
  name: string;
  scope: string;
  permissions: string[];
}
export interface Account {
  id: string | null;
  username: string;
  name: string;
  role: string;
  branchId: string | null;
  active: boolean;
  password?: string;
}
export interface Report {
  month: string;
  today: string;
  revenue: number;
  orders: number;
  averageOrder: number;
  quantity: number;
  grossProfit: number;
  grossMargin: number;
  daily: { day: string; revenue: number; orders: number }[];
  products: {
    id: string;
    name: string;
    category: string;
    quantity: number;
    revenue: number;
    cost: number;
  }[];
  topToday: { id: string; name: string; quantity: number; revenue: number }[];
  branches: {
    id: string;
    name: string;
    revenue: number;
    orders: number;
    target: number;
    achievement: number;
  }[];
  categories: Record<string, number>;
  hourly: { hour: string; orders: number }[];
  cashOrders: number;
  onlineOrders: number;
  takeawayOrders: number;
}
export interface ReconciliationPending {
  orderId: string;
  branchId: string;
  branchName: string;
  total: number;
  createdAt: number;
  lastOutcome: string | null;
  lastQueriedAt: number | null;
  attempts: number;
}
export interface ReconciliationAttempt {
  outcome: string;
  triggerSource: string;
  tradeStatus: string;
  tradeAmount: number | null;
  detail: string;
  queriedAt: number;
}
