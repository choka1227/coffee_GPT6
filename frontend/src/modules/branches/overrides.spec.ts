import { describe, expect, it } from "vitest";
import type { BranchDayOverride } from "../../shared/types";
import { formatOnDate, overrideSummary, sortOverrides } from "./overrides";

const override = (
  onDate: number,
  closed = true,
  note = "",
  hours: BranchDayOverride["hours"] = [],
): BranchDayOverride => ({ onDate, dayOfWeek: 1, closed, note, hours });

describe("sortOverrides", () => {
  it("依日期升冪且相同日期維持原順序", () => {
    const first = override(20261010, true, "first");
    const second = override(20261010, true, "second");
    expect(sortOverrides([first, override(20260101), second])).toEqual([
      override(20260101),
      first,
      second,
    ]);
  });
});

describe("overrideSummary", () => {
  it("顯示無備註與有備註的公休", () => {
    expect(overrideSummary(override(20261010))).toBe("公休");
    expect(overrideSummary(override(20261010, true, "國慶日"))).toBe(
      "公休 · 國慶日",
    );
  });

  it("顯示單段與多段時段", () => {
    expect(
      overrideSummary(
        override(20261010, false, "", [
          { dayOfWeek: 6, openMinute: 540, closeMinute: 1020 },
        ]),
      ),
    ).toBe("09:00–17:00");
    expect(
      overrideSummary(
        override(20261010, false, "", [
          { dayOfWeek: 6, openMinute: 540, closeMinute: 720 },
          { dayOfWeek: 6, openMinute: 780, closeMinute: 1020 },
        ]),
      ),
    ).toBe("09:00–12:00、13:00–17:00");
  });
});

describe("formatOnDate", () => {
  it.each([
    [20260101, "01/01（四）"],
    [20260131, "01/31（六）"],
    [20261231, "12/31（四）"],
    [20270101, "01/01（五）"],
  ])("格式化 %s", (onDate, expected) => {
    expect(formatOnDate(onDate)).toBe(expected);
  });
});

it("純函式模組不依賴 Vue、API 或 identity store", () => {
  const source = import.meta.glob("./overrides.ts", {
    query: "?raw",
    import: "default",
    eager: true,
  })["./overrides.ts"] as string;
  expect(source).not.toMatch(/from ["']vue["']/);
  expect(source).not.toContain("shared/api");
  expect(source).not.toContain("identity/store");
});

it("例外備註只以 Vue 文字插值顯示", () => {
  const sources = import.meta.glob(["./BranchesView.vue", "../ordering/MenuView.vue"], {
    query: "?raw",
    import: "default",
    eager: true,
  });
  expect(sources["./BranchesView.vue"]).not.toContain("v-html");
  expect(sources["../ordering/MenuView.vue"]).not.toContain("v-html");
});

it("總部 UI 接上例外日新增、清單與刪除，顧客端接上公休訊息", () => {
  const sources = import.meta.glob(["./BranchesView.vue", "../ordering/MenuView.vue"], {
    query: "?raw",
    import: "default",
    eager: true,
  });
  const branches = sources["./BranchesView.vue"] as string;
  const menu = sources["../ordering/MenuView.vue"] as string;
  expect(branches).toContain("/hour-overrides/${onDate}");
  expect(branches).toContain('"PUT"');
  expect(branches).toContain('"DELETE"');
  expect(branches).toContain("overrideSummary(value)");
  expect(menu).toContain("customerClosedMessage");
  expect(menu).toContain("分店今日公休");
});
