package com.coffee.orders.internal;

import static org.assertj.core.api.Assertions.*;

import com.coffee.orders.api.Orders.InvoiceRequest;
import com.coffee.shared.Problem;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Pure validation tests: no Spring context, database or network. */
class InvoiceRequestValidationTest {
  @Test
  void missingInvoiceIsAllowedOnlyWhenNotRequired() {
    assertThat(OrderService.normalizeInvoice(null, false)).isNull();
    assertThatThrownBy(() -> OrderService.normalizeInvoice(null, true))
        .isInstanceOfSatisfying(Problem.class, p -> {
          assertThat(p.status).isEqualTo(400);
          assertThat(p.getMessage()).isEqualTo("請選擇發票開立方式");
        });
  }

  static Stream<Arguments> valid() {
    return Stream.of(
        Arguments.of(new InvoiceRequest("CLOUD", " a@b.co ", "discard", "discard"),
            new InvoiceRequest("CLOUD", "a@b.co", null, null)),
        Arguments.of(new InvoiceRequest("MOBILE", "a@b.co", " /ab1.+-2 ", "discard"),
            new InvoiceRequest("MOBILE", "a@b.co", "/AB1.+-2", null)),
        Arguments.of(new InvoiceRequest("DONATE", "a@b.co", "discard", " 001 "),
            new InvoiceRequest("DONATE", "a@b.co", null, "001")));
  }

  @ParameterizedTest
  @MethodSource("valid")
  void normalizesOnlyRelevantFields(InvoiceRequest input, InvoiceRequest expected) {
    assertThat(OrderService.normalizeInvoice(input, true)).isEqualTo(expected);
    assertThat(OrderService.normalizeInvoice(input, false)).isEqualTo(expected);
  }

  static Stream<InvoiceRequest> invalid() {
    return Stream.of(
        new InvoiceRequest("cloud", "a@b.co", null, null),
        new InvoiceRequest("CLOUD", "a@b.co\nextra", null, null),
        new InvoiceRequest("MOBILE", "a@b.co", "ABC12345", null),
        new InvoiceRequest("DONATE", "a@b.co", null, "１２３"));
  }

  @ParameterizedTest
  @MethodSource("invalid")
  void disabledRequirementDoesNotDisableValidationOfSuppliedInvoice(InvoiceRequest input) {
    assertThatThrownBy(() -> OrderService.normalizeInvoice(input, false))
        .isInstanceOfSatisfying(Problem.class, p -> assertThat(p.status).isEqualTo(400));
  }
}
