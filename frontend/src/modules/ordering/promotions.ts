import type { ActivePromotion } from "../../shared/types";

export function discountLabel(percent: number): string {
  if (percent >= 100) return "免費";
  const remaining = 100 - percent;
  return remaining % 10 === 0 ? `${remaining / 10} 折` : `${remaining} 折`;
}

export function promotionText(rule: ActivePromotion): string {
  return rule.kind === "ITEM_PERCENT"
    ? `${rule.name}：每件 ${discountLabel(rule.percent)}`
    : `${rule.name}：第 ${rule.nth} 件 ${discountLabel(rule.percent)}`;
}
