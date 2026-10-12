package com.coffee.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.coffee.orders.api.Orders;
import com.coffee.payments.internal.EcpayService;
import com.coffee.payments.internal.EcpayTradeQuery;
import com.coffee.shared.Actor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(OutputCaptureExtension.class)
class EcpayStageGuardTest {
  private static final String STAGE_CHECKOUT =
      "https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5";
  private static final String STAGE_QUERY =
      "https://payment-stage.ecpay.com.tw/Cashier/QueryTradeInfo/V5";

  @ParameterizedTest
  @ValueSource(strings = {"production", "Stage", "STAGE", "", "prod"})
  void environmentMustBeExactStageEvenWhenPaymentIsDisabled(String environment) {
    assertThatThrownBy(
            () ->
                new EcpayService(
                    mock(Orders.class),
                    mock(JdbcTemplate.class),
                    false,
                    "",
                    "test-key-g01b",
                    "test-iv-g01b",
                    "http://localhost",
                    environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("本系統只允許串接綠界測試環境（stage）")
        .hasMessageNotContaining("test-key-g01b")
        .hasMessageNotContaining("test-iv-g01b");

    assertThatThrownBy(
            () ->
                new EcpayTradeQuery(
                    "", "test-key-g01b", "test-iv-g01b", environment, STAGE_QUERY, 1000))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("本系統只允許串接綠界測試環境（stage）")
        .hasMessageNotContaining("test-key-g01b")
        .hasMessageNotContaining("test-iv-g01b");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        STAGE_QUERY,
        "http://127.0.0.1:8081/Cashier/QueryTradeInfo/V5",
        "http://localhost:54321/query"
      })
  void acceptsOnlyStageOrExplicitLoopbackQueryUrls(String queryUrl) {
    assertThatCode(() -> new EcpayTradeQuery("", "key", "iv", "stage", queryUrl, 1000))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://payment.ecpay.com.tw/Cashier/QueryTradeInfo/V5",
        "http://payment-stage.ecpay.com.tw/Cashier/QueryTradeInfo/V5",
        "https://127.0.0.1:8081/query",
        "http://127.0.0.1.evil.com:8081/query",
        "http://user@127.0.0.1:8081/query",
        "http://127.0.0.1/query",
        "http://localhost:8081/query?q=1",
        "http://localhost:8081/query#fragment",
        "not a uri"
      })
  void rejectsEveryOtherQueryUrlWithoutLeakingValues(String queryUrl) {
    assertThatThrownBy(
            () ->
                new EcpayTradeQuery(
                    "merchant", "test-key-g01b", "test-iv-g01b", "stage", queryUrl, 1000))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("綠界查單網址只允許測試環境或本機測試伺服器")
        .hasMessageNotContaining("test-key-g01b")
        .hasMessageNotContaining("test-iv-g01b");
  }

  @Test
  void springContextRejectsProductionEvenWhenPaymentIsDisabled() {
    contextRunner("ecpay.environment=production", "ecpay.enabled=false")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage("本系統只允許串接綠界測試環境（stage）");
            });
  }

  @Test
  void springContextRejectsUnsafeQueryUrl() {
    contextRunner(
            "ecpay.environment=stage",
            "ecpay.enabled=false",
            "ecpay.query-url=https://payment.ecpay.com.tw/Cashier/QueryTradeInfo/V5")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage("綠界查單網址只允許測試環境或本機測試伺服器");
            });
  }

  @Test
  void checkoutAlwaysUsesStageAndHttpPublicUrlOnlyWarns(CapturedOutput output) {
    Orders orders = mock(Orders.class);
    when(orders.payable(any(), eq("ORDER000000000000001"))).thenReturn(order());
    var service =
        new EcpayService(
            orders,
            mock(JdbcTemplate.class),
            true,
            "3002607",
            "test-key-g01b",
            "test-iv-g01b",
            "http://localhost:8080",
            "stage");

    Actor customer =
        new Actor("customer", "customer", "顧客", "CUSTOMER", "SELF", null, Set.of());
    assertThat(service.checkout(customer, "ORDER000000000000001"))
        .containsEntry("action", STAGE_CHECKOUT);
    assertThat(output).contains("綠界付款通知（ReturnURL）需要可從外網連線的 HTTPS 網址");
    assertThat(output).doesNotContain("test-key-g01b", "test-iv-g01b");
  }

  @Test
  void defaultsStayCredentialFreeAndMainSourcesContainNoProductionHost() throws IOException {
    Path root = repositoryRoot();
    String application;
    try (InputStream resource =
        EcpayStageGuardTest.class.getResourceAsStream("/application.yml")) {
      assertThat(resource).isNotNull();
      application = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    assertThat(application)
        .contains("merchant-id: ${ECPAY_MERCHANT_ID:}")
        .contains("hash-key: ${ECPAY_HASH_KEY:}")
        .contains("hash-iv: ${ECPAY_HASH_IV:}");

    try (Stream<Path> files =
        Stream.concat(
            Files.walk(root.resolve("backend")).filter(p -> p.toString().contains("/src/main/")),
            Files.walk(root.resolve("frontend/src")))) {
      var offenders =
          files
              .filter(Files::isRegularFile)
              .filter(p -> !p.getFileName().toString().endsWith(".test.ts"))
              .filter(
                  p -> {
                    try {
                      return Files.readString(p).contains("payment.ecpay.com.tw");
                    } catch (IOException e) {
                      throw new RuntimeException(e);
                    }
                  })
              .map(root::relativize)
              .toList();
      assertThat(offenders).isEmpty();
    }
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null && !Files.isDirectory(current.resolve("frontend")))
      current = current.getParent();
    if (current == null) throw new IllegalStateException("找不到 repository root");
    return current;
  }

  private static ApplicationContextRunner contextRunner(String... properties) {
    return new ApplicationContextRunner()
        .withBean(Orders.class, () -> mock(Orders.class))
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withBean(EcpayService.class)
        .withBean(EcpayTradeQuery.class)
        .withPropertyValues(properties);
  }

  private static Orders.Order order() {
    return new Orders.Order(
        "ORDER000000000000001",
        "branch-a",
        "分店 A",
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
        1_700_000_000_000L,
        null,
        null,
        null,
        List.of());
  }
}
