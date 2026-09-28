package com.coffee.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffee.catalog.api.Discounts.Rule;
import org.junit.jupiter.api.Test;

class DiscountCalculationTest {
  @Test
  void percentageUsesIntegerArithmeticAndRoundsDown() {
    assertThat(DiscountService.calculate(rule("PERCENT", 10, 0), 505)).isEqualTo(50);
  }

  @Test
  void amountNeverReducesAnOrderBelowOneDollar() {
    var rule = rule("AMOUNT", 0, 100);
    assertThat(DiscountService.calculate(rule, 100)).isEqualTo(99);
    assertThat(DiscountService.calculate(rule, 1)).isZero();
  }

  private Rule rule(String kind, int percent, int amount) {
    return new Rule(
        "id", "TEST", "測試", kind, percent, amount, 0, null, null, null, null, 0, true);
  }
}
