import { describe, expect, it } from "vitest";
import { activePromotionFixture } from "../../shared/testing/fixtures";
import {
  discountLabel,
  promotionCartHint,
  promotionProgress,
  promotionText,
} from "./promotions";

describe("promotion hints", () => {
  it.each([
    [100, "免費"],
    [50, "5 折"],
    [10, "9 折"],
    [15, "85 折"],
    [33, "67 折"],
    [1, "99 折"],
  ])("把折抵 %i%% 顯示為 %s", (percent, label) => {
    expect(discountLabel(percent)).toBe(label);
  });

  it("顯示單件折扣規則", () => {
    expect(
      promotionText(
        activePromotionFixture({
          name: "拿鐵九折",
          kind: "ITEM_PERCENT",
          percent: 10,
          nth: 1,
        }),
      ),
    ).toBe("拿鐵九折：每件 9 折");
  });

  it("顯示第 N 件折扣規則", () => {
    expect(
      promotionText(
        activePromotionFixture({
          name: "第二杯半價",
          kind: "NTH_PERCENT",
          percent: 50,
          nth: 2,
        }),
      ),
    ).toBe("第二杯半價：第 2 件 5 折");
  });

  it.each([
    [2, 0, 0, 2],
    [2, 1, 0, 1],
    [2, 2, 1, 2],
    [2, 3, 1, 1],
    [2, 4, 2, 2],
    [3, 2, 0, 1],
    [3, 3, 1, 3],
  ])(
    "nth=%i、件數=%i 時已折 %i 件且距下一輪 %i 件",
    (nth, matchedUnits, discountedUnits, unitsToNext) => {
      expect(
        promotionProgress({
          rule: activePromotionFixture({ nth }),
          matchedUnits,
        }),
      ).toEqual({ discountedUnits, unitsToNext });
    },
  );

  it("單件規則的命中件數就是折扣件數", () => {
    expect(
      promotionProgress({
        rule: activePromotionFixture({ kind: "ITEM_PERCENT", nth: 1 }),
        matchedUnits: 3,
      }),
    ).toEqual({ discountedUnits: 3, unitsToNext: 0 });
  });

  it("未達門檻時提示再加件數", () => {
    expect(
      promotionCartHint({
        rule: activePromotionFixture(),
        matchedUnits: 1,
      }),
    ).toBe("再加 1 件可享「第二杯半價」第 2 件 5 折");
  });

  it("已達門檻時提示已折與下一輪件數", () => {
    expect(
      promotionCartHint({
        rule: activePromotionFixture(),
        matchedUnits: 2,
      }),
    ).toBe("已符合「第二杯半價」：已折 1 件，再加 2 件可再折 1 件");
  });

  it("單件規則提示已符合", () => {
    expect(
      promotionCartHint({
        rule: activePromotionFixture({
          name: "拿鐵九折",
          kind: "ITEM_PERCENT",
          percent: 10,
          nth: 1,
        }),
        matchedUnits: 1,
      }),
    ).toBe("已符合「拿鐵九折」：每件 9 折");
  });
});
