import { describe, expect, it } from "vitest";
import { activePromotionFixture } from "../../shared/testing/fixtures";
import { discountLabel, promotionText } from "./promotions";

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
});
