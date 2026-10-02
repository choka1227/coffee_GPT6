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
  const hours = override.hours
    .map(
      (period) =>
        `${minuteTime(period.openMinute)}–${minuteTime(period.closeMinute)}`,
    )
    .join("、");
  const schedule = hours || "沿用每週時段";
  return override.lastOrderMinutes === null
    ? schedule
    : `${schedule} · 最後點餐提前 ${override.lastOrderMinutes} 分鐘`;
}

function dateParts(onDate: number): [number, number, number] {
  const value = String(onDate).padStart(8, "0");
  return [
    Number(value.slice(0, 4)),
    Number(value.slice(4, 6)),
    Number(value.slice(6, 8)),
  ];
}

export function inputDate(onDate: number): string {
  const [year, month, day] = dateParts(onDate);
  return `${year}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

export function addDays(onDate: number, days: number): number {
  const [year, month, day] = dateParts(onDate);
  const value = new Date(Date.UTC(year, month - 1, day + days));
  return (
    value.getUTCFullYear() * 10000 +
    (value.getUTCMonth() + 1) * 100 +
    value.getUTCDate()
  );
}

export function dayWindowLimits(todayOnDate: number): {
  min: string;
  max: string;
} {
  return { min: inputDate(todayOnDate), max: inputDate(addDays(todayOnDate, 14)) };
}

export function isWithinDayWindow(onDate: number, todayOnDate: number): boolean {
  return onDate >= todayOnDate && onDate <= addDays(todayOnDate, 14);
}

export function formatOnDate(onDate: number): string {
  const [year, month, day] = dateParts(onDate);
  const weekdays = ["日", "一", "二", "三", "四", "五", "六"];
  const weekday = new Date(Date.UTC(year, month - 1, day)).getUTCDay();
  return `${String(month).padStart(2, "0")}/${String(day).padStart(2, "0")}（${weekdays[weekday]}）`;
}
