package com.coffee.app;

import static org.assertj.core.api.Assertions.*;
import com.coffee.payments.internal.EcpayInvoiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EinvoiceStageGuardTest {
  private ApplicationContextRunner runner(String... properties) {
    return new ApplicationContextRunner().withUserConfiguration(EcpayInvoiceClient.class)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withPropertyValues("ecpay.invoice.enabled=true", "ecpay.invoice.environment=stage",
            "ecpay.invoice.merchant-id=test-merchant", "ecpay.invoice.hash-key=0123456789abcdef",
            "ecpay.invoice.hash-iv=fedcba9876543210", "orders.invoice-required=true")
        .withPropertyValues(properties);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "ecpay.invoice.environment=production", "ecpay.invoice.environment=Stage",
      "ecpay.invoice.environment=", "ecpay.invoice.merchant-id=",
      "ecpay.invoice.hash-key=", "ecpay.invoice.hash-key=short",
      "ecpay.invoice.hash-iv=", "ecpay.invoice.hash-iv=short", "orders.invoice-required=false",
      "ecpay.invoice.timeout-ms=999", "ecpay.invoice.timeout-ms=30001",
      "ecpay.invoice.interval-ms=9999", "ecpay.invoice.batch-size=0", "ecpay.invoice.batch-size=101",
      "ecpay.invoice.lag-seconds=29", "ecpay.invoice.lag-seconds=3601",
      "ecpay.invoice.max-attempts=0", "ecpay.invoice.max-attempts=101",
      "ecpay.invoice.claim-lease-ms=89999", "ecpay.invoice.claim-lease-ms=3600001",
      "ecpay.invoice.base-url=https://einvoice.ecpay.com.tw",
      "ecpay.invoice.base-url=http://einvoice-stage.ecpay.com.tw",
      "ecpay.invoice.base-url=https://127.0.0.1:8888",
      "ecpay.invoice.base-url=http://localhost", "ecpay.invoice.base-url=http://localhost:0",
      "ecpay.invoice.base-url=http://localhost:65536", "ecpay.invoice.base-url=http://localhost:8888/",
      "ecpay.invoice.base-url=http://localhost:8888/path",
      "ecpay.invoice.base-url=http://user@localhost:8888",
      "ecpay.invoice.base-url=http://localhost:8888?q=1", "ecpay.invoice.base-url=http://localhost:8888#x",
      "ecpay.invoice.base-url=http://localhost.evil:8888", "ecpay.invoice.base-url=invalid"
  })
  void invalidConfigurationFailsContextWithoutEchoingValues(String property) {
    runner(property).run(context -> {
      assertThat(context).hasFailed();
      Throwable failure = context.getStartupFailure();
      while (failure.getCause() != null) failure = failure.getCause();
      assertThat(failure).isInstanceOf(IllegalStateException.class);
      assertThat(failure.getMessage()).doesNotContain("0123456789abcdef", "fedcba9876543210",
          "test-merchant", "http", "production", "localhost");
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://einvoice-stage.ecpay.com.tw", "http://127.0.0.1:12345", "http://localhost:54321"})
  void exactStageAndExplicitLoopbackAreAcceptedWithoutConnecting(String url) {
    runner("ecpay.invoice.base-url=" + url).run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void numericBoundsAreInclusive() {
    runner("ecpay.invoice.timeout-ms=1000", "ecpay.invoice.interval-ms=10000",
        "ecpay.invoice.batch-size=1", "ecpay.invoice.lag-seconds=30",
        "ecpay.invoice.max-attempts=1", "ecpay.invoice.claim-lease-ms=63000")
        .run(context -> assertThat(context).hasNotFailed());
    runner("ecpay.invoice.timeout-ms=30000", "ecpay.invoice.batch-size=100",
        "ecpay.invoice.lag-seconds=3600", "ecpay.invoice.max-attempts=100",
        "ecpay.invoice.claim-lease-ms=3600000")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void disabledClientAcceptsInvalidSettingsAndCannotSend() {
    runner("ecpay.invoice.enabled=false", "ecpay.invoice.environment=production",
        "ecpay.invoice.base-url=not a uri", "ecpay.invoice.hash-key=", "ecpay.invoice.hash-iv=",
        "ecpay.invoice.timeout-ms=-1", "ecpay.invoice.claim-lease-ms=-1")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(EcpayInvoiceClient.class).getIssueByRelateNumber("anything"))
              .isEqualTo(new EcpayInvoiceClient.Unknown("DISABLED"));
        });
  }
}
