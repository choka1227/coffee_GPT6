package com.coffee.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffee.catalog.api.Promotions.Line;
import com.coffee.catalog.api.Promotions.Rule;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItemPromotionCalculationTest {
  @Test
  void itemPercentRoundsDownForEveryUnit() {
    var applied = PromotionService.evaluate(item("a", 10, "PRODUCT", "latte"),
        List.of(line("latte", "飲品", 55, 3)));

    assertThat(applied.discountAmount()).isEqualTo(15);
    assertThat(applied.discountedUnits()).isEqualTo(3);
    assertThat(applied.lineDiscounts()).containsExactly(15);
  }

  @Test
  void itemPercentMatchesDifferentProductsInOneCategory() {
    var applied = PromotionService.evaluate(item("a", 20, "CATEGORY", "蛋糕"), List.of(
        line("cake-a", "蛋糕", 55, 2),
        line("cake-b", "蛋糕", 81, 1),
        line("coffee", "飲品", 100, 1)));

    assertThat(applied.discountAmount()).isEqualTo(38);
    assertThat(applied.lineDiscounts()).containsExactly(22, 16, 0);
  }

  @Test
  void nthPercentDiscountsQuantityDividedByNth() {
    var applied = PromotionService.evaluate(nth("a", 2, 100, "PRODUCT", "latte"),
        List.of(line("latte", "飲品", 60, 4)));

    assertThat(applied.discountedUnits()).isEqualTo(2);
    assertThat(applied.discountAmount()).isEqualTo(120);
    assertThat(applied.lineDiscounts()).containsExactly(120);
  }

  @Test
  void nthPercentUsesIntegerDivisionForSevenUnits() {
    var applied = PromotionService.evaluate(nth("a", 3, 50, "PRODUCT", "latte"),
        List.of(line("latte", "飲品", 40, 7)));

    assertThat(applied.discountedUnits()).isEqualTo(2);
    assertThat(applied.discountAmount()).isEqualTo(40);
  }

  @Test
  void nthPercentReturnsNullWhenQuantityIsInsufficient() {
    var rule = nth("a", 2, 100, "PRODUCT", "latte");
    var lines = List.of(line("latte", "飲品", 60, 1));

    assertThat(PromotionService.evaluate(rule, lines)).isNull();
    assertThat(PromotionService.best(List.of(rule), lines)).isNull();
  }

  @Test
  void nthPercentUsesCheapestUnitAndEarlierLineForEqualPrices() {
    var applied = PromotionService.evaluate(nth("a", 2, 100, "CATEGORY", "飲品"), List.of(
        line("expensive", "飲品", 100, 1),
        line("cheap-first", "飲品", 60, 1),
        line("cheap-second", "飲品", 60, 2)));

    assertThat(applied.discountedUnits()).isEqualTo(2);
    assertThat(applied.discountAmount()).isEqualTo(120);
    assertThat(applied.lineDiscounts()).containsExactly(0, 60, 60);
  }

  @Test
  void unrelatedLinesDoNotMatch() {
    var lines = List.of(line("tea", "飲品", 60, 2));

    assertThat(PromotionService.evaluate(item("a", 10, "PRODUCT", "latte"), lines)).isNull();
    assertThat(PromotionService.best(
        List.of(item("a", 10, "PRODUCT", "latte")), lines)).isNull();
  }

  @Test
  void bestUsesLargestDiscountThenLexicographicallySmallestId() {
    var lines = List.of(line("latte", "飲品", 100, 2));
    var smaller = item("a", 20, "PRODUCT", "latte");
    var equalButLargerId = item("b", 20, "PRODUCT", "latte");
    var lowerDiscount = item("0", 10, "PRODUCT", "latte");

    assertThat(PromotionService.best(
        List.of(equalButLargerId, smaller, lowerDiscount), lines).promotionId()).isEqualTo("a");
    assertThat(PromotionService.best(
        List.of(lowerDiscount, smaller), lines).promotionId()).isEqualTo("a");
  }

  @Test
  void fullNthDiscountAlwaysLeavesAtLeastOneDollar() {
    var lines = List.of(line("latte", "飲品", 1, 2));
    var applied = PromotionService.best(
        List.of(nth("a", 2, 100, "PRODUCT", "latte")), lines);
    int subtotal = Math.multiplyExact(lines.get(0).unitPrice(), lines.get(0).quantity());

    assertThat(Math.subtractExact(subtotal, applied.discountAmount())).isGreaterThanOrEqualTo(1);
  }

  private Rule item(String id, int percent, String targetKind, String targetId) {
    return rule(id, "ITEM_PERCENT", percent, 0, targetKind, targetId);
  }

  private Rule nth(
      String id, int nth, int percent, String targetKind, String targetId) {
    return rule(id, "NTH_PERCENT", percent, nth, targetKind, targetId);
  }

  private Rule rule(
      String id, String kind, int percent, int nth, String targetKind, String targetId) {
    return new Rule(
        id,
        "測試促銷",
        kind,
        percent,
        nth,
        targetKind,
        "PRODUCT".equals(targetKind) ? targetId : null,
        "CATEGORY".equals(targetKind) ? targetId : null,
        null,
        null,
        null,
        true);
  }

  private Line line(String productId, String category, int unitPrice, int quantity) {
    return new Line(productId, category, unitPrice, quantity);
  }
}
