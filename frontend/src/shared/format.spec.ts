import { describe, expect, it } from "vitest";

import { csvBody, minuteTime } from "./format";

describe("csvBody", () => {
  it("保留 BOM、雙引號逸出與 CRLF 列分隔", () => {
    expect(
      csvBody([
        ["名稱", '咖啡"豆'],
        ["拿鐵", "大杯"],
      ]),
    ).toBe('\uFEFF"名稱","咖啡""豆"\r\n"拿鐵","大杯"');
  });

  it.each(["=1+1", "+SUM(A1)", "@command", "-10", "\tformula"])(
    "阻擋公式注入前綴 %s",
    (value) => {
      expect(csvBody([[value]])).toBe(`\uFEFF"'${value}"`);
    },
  );

  it("保留逗號與換行內容並防護 carriage return 前綴", () => {
    expect(csvBody([["a,b", "line\nbreak", "\rformula"]])).toBe(
      '\uFEFF"a,b","line\nbreak","\'\rformula"',
    );
  });

  it("數字型別不套用公式注入前綴", () => {
    expect(csvBody([[-10, 42]])).toBe('\uFEFF"-10","42"');
  });

  it("空列集合只回傳 BOM", () => {
    expect(csvBody([])).toBe("\uFEFF");
  });
});

describe("minuteTime", () => {
  it.each([
    [0, "00:00"],
    [59, "00:59"],
    [60, "01:00"],
    [1439, "23:59"],
    [1440, "24:00"],
  ])("將第 %i 分鐘格式化為 %s", (value, expected) => {
    expect(minuteTime(value)).toBe(expected);
  });
});
