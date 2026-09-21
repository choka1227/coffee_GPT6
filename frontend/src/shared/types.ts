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
  openNow: boolean;
}
export interface BranchHours {
  dayOfWeek: number;
  openMinute: number;
  closeMinute: number;
}
export interface BranchHoursResponse {
  branchId: string;
  openNow: boolean;
  hours: BranchHours[];
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
  availability: "AVAILABLE" | "SOLD_OUT";
  optionGroups: OptionGroup[];
}
export interface BranchAvailability {
  branchId: string;
  productId: string;
  productName: string;
  availability: "AVAILABLE" | "SOLD_OUT" | "UNLISTED";
  updatedAt: number | null;
  updatedBy: string | null;
}
export interface OptionItem {
  id: string | null;
  groupId: string;
  name: string;
  priceDelta: number;
  costDelta: number;
  active: boolean;
  sortOrder: number;
}
export interface OptionGroup {
  id: string | null;
  name: string;
  selection: "SINGLE" | "MULTI";
  minSelect: number;
  maxSelect: number;
  active: boolean;
  sortOrder: number;
  items: OptionItem[];
}
export interface Line {
  productId: string;
  name: string;
  category: string;
  unitPrice: number;
  quantity: number;
  temperature: string | null;
  sugar: string | null;
  optionsPrice: number;
  lineTotal: number;
  options: OrderOption[];
}
export interface CartLine extends Omit<Line, "temperature" | "sugar"> {
  optionIds: string[];
}
export interface OrderOption {
  groupName: string;
  optionName: string;
  priceDelta: number;
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
export type OrderPage = {
  items: Order[];
  nextCursor: string | null;
};
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
export interface AuditEntry {
  id: string;
  actorId: string;
  actorName: string;
  action: string;
  targetId: string;
  branchId: string | null;
  summary: string;
  createdAt: number;
}
export interface AuditPage {
  items: AuditEntry[];
  nextCursor: string | null;
}
export interface CashSession {
  id: string;
  branchId: string;
  status: "OPEN" | "CLOSED";
  openingFloat: number;
  cashRevenue: number;
  orderCount: number;
  expectedAmount: number;
  countedAmount: number | null;
  variance: number | null;
  openedBy: string;
  openedByName: string;
  openedAt: number;
  closedBy: string | null;
  closedByName: string | null;
  closedAt: number | null;
  note: string;
}
export interface CashSessionPage {
  items: CashSession[];
  nextCursor: string | null;
  unassignedCashRevenue: number;
  unassignedOrderCount: number;
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
