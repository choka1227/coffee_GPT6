package com.coffee.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.coffee.orders.api.Orders;
import com.coffee.payments.api.TradeQuery;
import com.coffee.payments.internal.EcpayService;
import com.coffee.payments.internal.ReconciliationService;
import com.coffee.shared.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class PaymentExpiryConfigTest {
  private static Stream<Object[]> invalidSettings() {
    return Stream.of(
        new Object[] {false, 20L, 60L, 30L, 300000L, "啟用逾時作廢前必須先啟用金流對帳"},
        new Object[] {true, 4L, 60L, 30L, 300000L, "逾時作廢設定不正確"},
        new Object[] {true, 1441L, 2000L, 30L, 300000L, "逾時作廢設定不正確"},
        new Object[] {true, 20L, 49L, 30L, 300000L, "逾時作廢設定不正確"},
        new Object[] {true, 20L, 15L, 30L, 300000L, "逾時作廢設定不正確"},
        new Object[] {true, 20L, 10081L, 30L, 300000L, "逾時作廢設定不正確"},
        new Object[] {true, 20L, 60L, 4L, 300000L, "逾時作廢設定不正確"},
        new Object[] {true, 20L, 60L, 1441L, 300000L, "逾時作廢設定不正確"},
        new Object[] {true, 20L, 60L, 30L, 59999L, "逾時作廢設定不正確"},
        new Object[] {true, 20L, 60L, 30L, 86400001L, "逾時作廢設定不正確"});
  }

  @ParameterizedTest
  @MethodSource("invalidSettings")
  void invalidEnabledConfigurationFailsWithoutLeakingValues(
      boolean reconcileEnabled,
      long checkoutMinutes,
      long afterMinutes,
      long recheckMinutes,
      long intervalMs,
      String message) {
    assertThatThrownBy(
            () ->
                service(
                    reconcileEnabled,
                    true,
                    checkoutMinutes,
                    afterMinutes,
                    recheckMinutes,
                    intervalMs))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(message)
        .hasMessageNotContaining(Long.toString(checkoutMinutes))
        .hasMessageNotContaining(Long.toString(afterMinutes));
  }

  @Test
  void disabledExpiryIgnoresExpiryValues() {
    assertThatCode(() -> service(false, false, -1, -2, -3, -4)).doesNotThrowAnyException();
  }

  @Test
  void checkoutWindowAppliesOnlyWhenEnabled() {
    Orders orders = mock(Orders.class);
    when(orders.payable(any(), anyString())).thenReturn(order(System.currentTimeMillis() - 21 * 60_000));
    var enabled = ecpay(orders, true, 20);
    assertThatThrownBy(() -> enabled.checkout(customer(), "ORDER000000000000001"))
        .isInstanceOf(Problem.class)
        .hasMessage("此訂單已超過線上付款期限，請重新下單");

    when(orders.payable(any(), anyString())).thenReturn(order(System.currentTimeMillis() - 19 * 60_000));
    assertThat(enabled.checkout(customer(), "ORDER000000000000001")).containsKey("fields");
    when(orders.payable(any(), anyString())).thenReturn(order(1));
    assertThat(ecpay(orders, false, 20).checkout(customer(), "ORDER000000000000001"))
        .containsKey("fields");
  }

  @Test
  void defaultConfigurationKeepsExpiryDisabled() throws Exception {
    try (InputStream input = getClass().getResourceAsStream("/application.yml")) {
      assertThat(input).isNotNull();
      assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
          .contains("enabled: ${ECPAY_EXPIRE_ENABLED:false}");
    }
  }

  private ReconciliationService service(
      boolean reconcileEnabled,
      boolean expireEnabled,
      long checkoutMinutes,
      long afterMinutes,
      long recheckMinutes,
      long intervalMs) {
    return new ReconciliationService(
        mock(Orders.class),
        mock(TradeQuery.class),
        mock(JdbcTemplate.class),
        mock(PlatformTransactionManager.class),
        reconcileEnabled,
        true,
        "merchant",
        "key",
        "iv",
        15,
        7,
        50,
        expireEnabled,
        checkoutMinutes,
        afterMinutes,
        recheckMinutes,
        intervalMs);
  }

  private EcpayService ecpay(Orders orders, boolean expireEnabled, long checkoutMinutes) {
    return new EcpayService(
        orders,
        mock(JdbcTemplate.class),
        true,
        "3002607",
        "key",
        "iv",
        "https://coffee.example.test",
        "stage",
        expireEnabled,
        checkoutMinutes);
  }

  private Actor customer() {
    return new Actor("customer", "customer", "顧客", "CUSTOMER", "SELF", null, Set.of());
  }

  private Orders.Order order(long createdAt) {
    return new Orders.Order(
        "ORDER000000000000001",
        "taipei",
        "台北店",
        "customer",
        "PENDING_PAYMENT",
        "TAKEAWAY",
        "ECPAY",
        120,
        120,
        0,
        0,
        null,
        null,
        "",
        createdAt,
        null,
        null,
        null,
        List.of(), null);
  }
}
