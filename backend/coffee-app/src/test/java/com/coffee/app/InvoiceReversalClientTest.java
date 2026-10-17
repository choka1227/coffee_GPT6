package com.coffee.app;

import static org.assertj.core.api.Assertions.*;

import com.coffee.app.ecpay.FakeEinvoiceServer;
import com.coffee.app.ecpay.FakeEinvoiceServer.Scenario;
import com.coffee.orders.api.Orders.InvoiceCandidate;
import com.coffee.orders.api.Orders.InvoiceRequest;
import com.coffee.payments.internal.EcpayInvoiceClient;
import com.coffee.payments.internal.EcpayInvoiceClient.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class InvoiceReversalClientTest {
  private static final String KEY = "0123456789abcdef", IV = "fedcba9876543210";
  private static final String EMAIL = "reversal-private@example.test", ID = "CF000000000000000002";
  private static final String DATE = "2026-10-16 12:00:00";
  private static final long TS = 1792100000L;

  private EcpayInvoiceClient client(String url) {
    return new EcpayInvoiceClient(true, "stage", "reversal-merchant", KEY, IV,
        url, true, 1000, 10000, 20, 120, 10, 900000);
  }

  private String seed(EcpayInvoiceClient client) {
    var order = new InvoiceCandidate(ID, "taipei", 126, 1000,
        new InvoiceRequest("CLOUD", EMAIL, null, null), null);
    return (String) ((Ok) client.issue(order, TS)).data().get("InvoiceNo");
  }

  private CallResult reverse(EcpayInvoiceClient client, String operation, String invoiceNo) {
    return operation.equals("Invalid") ? client.invalid(invoiceNo, DATE, "訂單退款", TS)
        : client.allowance(invoiceNo, DATE, 126, EMAIL, TS);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Invalid", "Allowance"})
  void encryptedRequestsMatchSpecAndQueryReflectsProviderState(String operation, CapturedOutput output)
      throws Exception {
    try (var server = new FakeEinvoiceServer(KEY, IV)) {
      var client = client(server.baseUrl());
      String invoiceNo = seed(client);
      AtomicInteger hooks = new AtomicInteger();
      server.hook(operation, ignored -> hooks.incrementAndGet());
      var result = reverse(client, operation, invoiceNo);
      assertThat(result).isInstanceOf(Ok.class);
      assertThat(hooks.get()).isEqualTo(1);
      var request = server.received().get(1);
      assertThat(request.path()).isEqualTo("/B2CInvoice/" + operation);
      assertThat(request.timestampSec()).isEqualTo(TS);
      assertThat(request.outer().path("Data").asText()).doesNotContain(EMAIL, invoiceNo, "訂單退款");
      assertThat(request.outer().path("MerchantID").asText()).isEqualTo("reversal-merchant");
      assertThat(request.outer().path("RqHeader").path("Revision").asText()).isEqualTo("3.0.0");
      JsonNode data = request.data();
      assertThat(data.path("MerchantID").asText()).isEqualTo("reversal-merchant");
      assertThat(data.path("InvoiceNo").asText()).isEqualTo(invoiceNo);
      assertThat(data.path("InvoiceDate").asText()).isEqualTo("2026-10-16");
      if (operation.equals("Invalid")) {
        assertThat(data.size()).isEqualTo(4);
        assertThat(data.path("Reason").asText()).isEqualTo("訂單退款");
      } else {
        assertThat(data.size()).isEqualTo(7);
        assertThat(data.path("AllowanceNotify").asText()).isEqualTo("E");
        assertThat(data.path("NotifyMail").asText()).isEqualTo(EMAIL);
        assertThat(data.path("AllowanceAmount").asInt()).isEqualTo(126);
        assertThat(data.path("Items").size()).isEqualTo(1);
        var item = data.path("Items").get(0);
        assertThat(item.size()).isEqualTo(7);
        assertThat(item.path("ItemSeq").asInt()).isEqualTo(1);
        assertThat(item.path("ItemName").asText()).isEqualTo("餐飲費用");
        assertThat(item.path("ItemCount").asInt()).isEqualTo(1);
        assertThat(item.path("ItemWord").asText()).isEqualTo("式");
        assertThat(item.path("ItemTaxType").asText()).isEqualTo("1");
        assertThat(item.path("ItemPrice").asInt()).isEqualTo(126);
        assertThat(item.path("ItemAmount").asInt()).isEqualTo(126);
        assertThat(((Ok) result).data().get("IA_Allow_No")).isInstanceOf(String.class);
      }
      var queried = (Ok) client.getIssueByRelateNumber(ID);
      assertThat(queried.data().get("IIS_Invalid_Status")).isEqualTo(operation.equals("Invalid") ? "1" : "0");
      assertThat(queried.data().get("IIS_Remain_Allowance_Amt")).isEqualTo(operation.equals("Invalid") ? 126 : 0);
      assertThat(reverse(client, operation, invoiceNo)).isInstanceOf(Rejected.class);
      assertThat(server.invoiceCount()).isEqualTo(1);
    }
    clean(output);
  }

  @Test void nullEmailOmitsNotificationMailAndUsesSameFullAmount(CapturedOutput output) throws Exception {
    try (var server = new FakeEinvoiceServer(KEY, IV)) {
      var client = client(server.baseUrl()); String invoiceNo = seed(client);
      assertThat(client.allowance(invoiceNo, "2026-10-16", 126, null, TS)).isInstanceOf(Ok.class);
      var data = server.received().get(1).data();
      assertThat(data.path("AllowanceNotify").asText()).isEqualTo("N");
      assertThat(data.has("NotifyMail")).isFalse();
      assertThat(data.path("AllowanceAmount").asInt()).isEqualTo(126);
    }
    clean(output);
  }

  @ParameterizedTest
  @EnumSource(value = Scenario.class, names = {"TIMEOUT", "HTTP_500", "BAD_CIPHER",
      "BAD_JSON", "BAD_DATA_JSON", "TRANS_CODE_ERROR", "REDIRECT_TO_PRODUCTION"})
  void bothOperationsReturnUnknownOnUncertainTransportOrEnvelope(Scenario scenario, CapturedOutput output)
      throws Exception {
    try (var server = new FakeEinvoiceServer(KEY, IV)) {
      var client = client(server.baseUrl()); String invoiceNo = seed(client);
      for (String operation : List.of("Invalid", "Allowance")) {
        server.respond(operation, scenario);
        var result = reverse(client, operation, invoiceNo);
        assertThat(result).isInstanceOf(Unknown.class);
        if (scenario == Scenario.REDIRECT_TO_PRODUCTION)
          assertThat(((Unknown) result).reason()).isEqualTo("HTTP_302");
      }
      assertThat(server.received()).hasSize(3);
      assertThat(((Ok) client.getIssueByRelateNumber(ID)).data().get("IIS_Invalid_Status")).isEqualTo("0");
    }
    clean(output);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Invalid", "Allowance"})
  void successThenTimeoutIsVisibleToQueryWithoutResending(String operation, CapturedOutput output) throws Exception {
    try (var server = new FakeEinvoiceServer(KEY, IV)) {
      var client = client(server.baseUrl()); String invoiceNo = seed(client);
      server.respond(operation, Scenario.SUCCEED_THEN_TIMEOUT);
      assertThat(reverse(client, operation, invoiceNo)).isEqualTo(new Unknown("TIMEOUT"));
      var query = (Ok) client.getIssueByRelateNumber(ID);
      assertThat(query.data().get(operation.equals("Invalid") ? "IIS_Invalid_Status" : "IIS_Remain_Allowance_Amt"))
          .isEqualTo(operation.equals("Invalid") ? "1" : 0);
      assertThat(server.received().stream().filter(r -> r.path().endsWith("/" + operation))).hasSize(1);
    }
    clean(output);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Invalid", "Allowance"})
  void delayedEffectCanBeAppliedOrDroppedWithoutChangingLegacyIssueScenarios(String operation) throws Exception {
    for (boolean apply : List.of(true, false)) {
      try (var server = new FakeEinvoiceServer(KEY, IV)) {
        var client = client(server.baseUrl()); String invoiceNo = seed(client);
        server.respond(operation, Scenario.DELAYED_EFFECT);
        assertThat(reverse(client, operation, invoiceNo)).isInstanceOf(Unknown.class);
        var before = (Ok) client.getIssueByRelateNumber(ID);
        assertThat(before.data().get("IIS_Invalid_Status")).isEqualTo("0");
        assertThat(before.data().get("IIS_Remain_Allowance_Amt")).isEqualTo(126);
        if (apply) server.applyDelayed(); else server.dropDelayed();
        var after = (Ok) client.getIssueByRelateNumber(ID);
        assertThat(after.data().get("IIS_Invalid_Status"))
            .isEqualTo(apply && operation.equals("Invalid") ? "1" : "0");
        assertThat(after.data().get("IIS_Remain_Allowance_Amt"))
            .isEqualTo(apply && operation.equals("Allowance") ? 0 : 126);
      }
    }
  }

  @Test void voidDeadlineRefusalAndSensitiveEchoAreSafe(CapturedOutput output) throws Exception {
    try (var server = new FakeEinvoiceServer(KEY, IV)) {
      var client = client(server.baseUrl()); String invoiceNo = seed(client);
      server.respond("Invalid", Scenario.VOID_PAST_DEADLINE);
      assertThat(client.invalid(invoiceNo, DATE, "訂單退款", TS)).isEqualTo(new Rejected(5070450, "已超過作廢期限"));
      for (String operation : List.of("Invalid", "Allowance")) {
        server.respond(operation, Scenario.ECHO_SENSITIVE);
        var result = (Rejected) reverse(client, operation, invoiceNo);
        assertThat(result.rtnMsg()).doesNotContain(KEY, IV, EMAIL, "reversal-merchant", "\n", "\t", "訂單退款");
        assertThat(result.rtnMsg().length()).isLessThanOrEqualTo(100);
      }
    }
    clean(output);
  }

  @Test void disabledInvalidInputAndConnectionFailuresDoNotLeakOrSendUnexpectedRequests(CapturedOutput output)
      throws Exception {
    try (var server = new FakeEinvoiceServer(KEY, IV)) {
      var client = client(server.baseUrl());
      assertThat(client.invalid(null, DATE, "訂單退款", TS)).isInstanceOf(Unknown.class);
      assertThat(client.invalid("AB12345678", "2026-02-30", "訂單退款", TS)).isInstanceOf(Unknown.class);
      assertThat(client.allowance("AB12345678", DATE, 0, EMAIL, TS)).isInstanceOf(Unknown.class);
      var disabled = new EcpayInvoiceClient(false, "stage", "", "", "", server.baseUrl(), false,
          1000, 10000, 20, 120, 10, 900000);
      assertThat(disabled.invalid(null, null, null, TS)).isEqualTo(new Unknown("DISABLED"));
      assertThat(disabled.allowance(null, null, 0, null, TS)).isEqualTo(new Unknown("DISABLED"));
      assertThat(server.received()).isEmpty();
    }
    int closed;
    try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { closed = socket.getLocalPort(); }
    for (String operation : List.of("Invalid", "Allowance"))
      assertThat(reverse(client("http://127.0.0.1:" + closed), operation, "AB12345678"))
          .isEqualTo(new Unknown("CONNECT"));
    clean(output);
  }

  private void clean(CapturedOutput output) {
    assertThat(output.getAll()).doesNotContain(KEY, IV, EMAIL, "\tat ");
  }
}
