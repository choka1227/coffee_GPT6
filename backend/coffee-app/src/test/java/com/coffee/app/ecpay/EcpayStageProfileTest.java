package com.coffee.app.ecpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:ecpay-stage-profile;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "ecpay.reconcile.interval-ms=86400000",
      "ecpay.query-url=http://127.0.0.1:1/Cashier/QueryTradeInfo/V5"
    })
@ActiveProfiles({"dev", "ecpay-stage"})
class EcpayStageProfileTest {
  private static final FakeEcpayServer FAKE =
      FakeEcpayServer.start("3002607", "pwFHCqoQZGmho4w6", "EkRm7iFT261dpevs");

  @DynamicPropertySource
  static void localQueryOnly(DynamicPropertyRegistry registry) {
    registry.add("ecpay.query-url", FAKE::queryUrl);
  }

  @Autowired Environment environment;

  @AfterAll
  static void closeServer() {
    FAKE.close();
  }

  @Test
  void profileEnablesOnlyStagePaymentAndReconciliation() throws Exception {
    assertThat(environment.getProperty("ecpay.enabled", Boolean.class)).isTrue();
    assertThat(environment.getProperty("ecpay.environment")).isEqualTo("stage");
    assertThat(environment.getProperty("ecpay.merchant-id")).isEqualTo("3002607");
    assertThat(environment.getProperty("ecpay.reconcile.enabled", Boolean.class)).isTrue();
    assertThat(environment.getProperty("ecpay.reconcile.min-age-minutes", Integer.class))
        .isEqualTo(3);
    assertThat(environment.getProperty("ecpay.reconcile.interval-ms", Long.class))
        .isEqualTo(86_400_000L);

    String profile;
    try (InputStream input =
        EcpayStageProfileTest.class.getResourceAsStream("/application-ecpay-stage.yml")) {
      assertThat(input).isNotNull();
      profile = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(profile)
        .contains("interval-ms: ${ECPAY_RECONCILE_INTERVAL_MS:120000}")
        .contains("綠界官方公開測試特店，不是正式憑證")
        .contains("merchant-id: ${ECPAY_MERCHANT_ID:3002607}")
        .contains("hash-key: ${ECPAY_HASH_KEY:pwFHCqoQZGmho4w6}")
        .contains("hash-iv: ${ECPAY_HASH_IV:EkRm7iFT261dpevs}");
  }
}
