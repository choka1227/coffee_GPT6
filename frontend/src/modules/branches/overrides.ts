import type { BranchDayOverride } from "../../shared/types";
import { minuteTime } from "../../shared/format";

export function sortOverrides(list: BranchDayOverride[]): BranchDayOverride[] {
  return list
    .map((value, index) => ({ value, index }))
    .sort((a, b) => a.value.onDate - b.value.onDate || a.index - b.index)
    .map(({ value }) => value);
}

export function overrideSummary(override: BranchDayOverride): string {
  if (override.closed)
    return override.note ? `公休 · ${override.note}` : "公休";
  return override.hours
    .map(
      (period) =>
        `${minuteTime(period.openMinute)}–${minuteTime(period.closeMinute)}`,
    )
    .join("、");
}

export function formatOnDate(onDate: number): string {
  const value = String(onDate).padStart(8, "0");
  const year = Number(value.slice(0, 4));
  const month = Number(value.slice(4, 6));
  const day = Number(value.slice(6, 8));
  const weekdays = ["日", "一", "二", "三", "四", "五", "六"];
  const weekday = new Date(Date.UTC(year, month - 1, day)).getUTCDay();
  return `${String(month).padStart(2, "0")}/${String(day).padStart(2, "0")}（${weekdays[weekday]}）`;
}
