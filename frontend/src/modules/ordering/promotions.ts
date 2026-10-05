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

export function promotionProgress(input: {
  rule: ActivePromotion;
  matchedUnits: number;
}): { discountedUnits: number; unitsToNext: number } {
  if (input.rule.kind === "ITEM_PERCENT")
    return { discountedUnits: input.matchedUnits, unitsToNext: 0 };
  const nth = input.rule.nth;
  const discountedUnits = Math.floor(input.matchedUnits / nth);
  const unitsToNext = nth - (input.matchedUnits % nth);
  return { discountedUnits, unitsToNext };
}

export function promotionCartHint(input: {
  rule: ActivePromotion;
  matchedUnits: number;
}): string {
  const { discountedUnits, unitsToNext } = promotionProgress(input);
  if (input.rule.kind === "ITEM_PERCENT")
    return `已符合「${input.rule.name}」：每件 ${discountLabel(input.rule.percent)}`;
  if (discountedUnits === 0)
    return `再加 ${unitsToNext} 件可享「${input.rule.name}」第 ${input.rule.nth} 件 ${discountLabel(input.rule.percent)}`;
  return `已符合「${input.rule.name}」：已折 ${discountedUnits} 件，再加 ${unitsToNext} 件可再折 1 件`;
}
