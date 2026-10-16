package com.coffee.app;

import static org.assertj.core.api.Assertions.*;
import com.coffee.app.ecpay.FakeEinvoiceServer;
import com.coffee.app.ecpay.FakeEinvoiceServer.Scenario;
import com.coffee.orders.api.Orders.InvoiceCandidate;
import com.coffee.orders.api.Orders.InvoiceRequest;
import com.coffee.payments.internal.EcpayInvoiceClient;
import com.coffee.payments.internal.EcpayInvoiceClient.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class EinvoiceClientTest {
  private static final String KEY = "0123456789abcdef", IV = "fedcba9876543210";

  static EcpayInvoiceClient client(String url) {
    return new EcpayInvoiceClient(new ObjectMapper(), true, "stage", "test-merchant", KEY, IV,
        url, true, 1000, 10000, 20, 120, 10, 900000);
  }

  static InvoiceCandidate order(String type) {
    return new InvoiceCandidate("CF000000000000000001", "taipei", 126, 1000,
        new InvoiceRequest(type, "private@example.test", "/ABC1234", "1234567"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"CLOUD", "MOBILE", "DONATE"})
  void issueAndQueryUseEncryptedEnvelopeAndDiscountedOrderTotal(String type, CapturedOutput output)
      throws Exception {
    try (var server = new FakeEinvoiceServer(KEY, IV)) {
      var client = client(server.baseUrl());
      var result = client.issue(order(type), 1792100000L);
      assertThat(result).isInstanceOf(Ok.class);
      var request = server.received().get(0);
      assertThat(request.path()).isEqualTo("/B2CInvoice/Issue");
      assertThat(request.timestampSec()).isEqualTo(1792100000L);
      assertThat(request.outer().path("MerchantID").asText()).isEqualTo("test-merchant");
      assertThat(request.outer().path("RqHeader").path("Revision").asText()).isEqualTo("3.0.0");
      var data = request.data();
      assertThat(data.path("MerchantID").asText()).isEqualTo("test-merchant");
      assertThat(data.path("RelateNumber").asText()).isEqualTo(order(type).orderId());
      assertThat(data.path("CustomerEmail").asText()).isEqualTo("private@example.test");
      assertThat(data.path("SalesAmount").asInt()).isEqualTo(126);
      assertThat(data.path("Print").asText()).isEqualTo("0");
      assertThat(data.path("TaxType").asText()).isEqualTo("1");
      assertThat(data.path("vat").asText()).isEqualTo("1");
      assertThat(data.path("InvType").asText()).isEqualTo("07");
      assertThat(data.path("CarrierType").asText())
          .isEqualTo(type.equals("CLOUD") ? "1" : type.equals("MOBILE") ? "3" : "");
      assertThat(data.path("CarrierNum").asText()).isEqualTo(type.equals("MOBILE") ? "/ABC1234" : "");
      assertThat(data.path("Donation").asText()).isEqualTo(type.equals("DONATE") ? "1" : "0");
      assertThat(data.path("LoveCode").asText()).isEqualTo(type.equals("DONATE") ? "1234567" : "");
      assertThat(data.path("Items").size()).isEqualTo(1);
      var item = data.path("Items").get(0);
      assertThat(item.path("ItemSeq").asInt()).isEqualTo(1);
      assertThat(item.path("ItemName").asText()).isEqualTo("餐飲費用");
      assertThat(item.path("ItemCount").asInt()).isEqualTo(1);
      assertThat(item.path("ItemWord").asText()).isEqualTo("式");
      assertThat(item.path("ItemTaxType").asText()).isEqualTo("1");
      assertThat(item.path("ItemPrice").asInt()).isEqualTo(126);
      assertThat(item.path("ItemAmount").asInt()).isEqualTo(126);
      var queried = (Ok) client.getIssueByRelateNumber(order(type).orderId());
      assertThat(queried.data().get("IIS_Number")).isEqualTo(((Ok) result).data().get("InvoiceNo"));
      assertThat(queried.data().get("IIS_Sales_Amount")).isEqualTo(126);
      assertThat(queried.data()).containsKeys("IIS_Create_Date", "IIS_Random_Number",
          "IIS_Invalid_Status", "IIS_Remain_Allowance_Amt");
      assertThat(server.received().get(1).data().size()).isEqualTo(2);
      assertThat(server.received().get(1).path()).isEqualTo("/B2CInvoice/GetIssue");
      assertThat(server.invoiceCount()).isEqualTo(1);
    }
    assertClean(output);
  }

  @ParameterizedTest
  @EnumSource(value = Scenario.class, names = {"TIMEOUT", "HTTP_500", "TRANS_CODE_ERROR",
      "BAD_CIPHER", "BAD_JSON", "BAD_DATA_JSON", "REDIRECT_TO_PRODUCTION"})
  void failuresAreUnknownAndRedirectsAreNotFollowed(Scenario scenario, CapturedOutput output)
      throws Exception {
    try (var server = new FakeEinvoiceServer(KEY, IV)) {
      var client = client(server.baseUrl());
      server.respond("Issue", scenario);
      server.respond("GetIssue", scenario);
      assertThat(client.issue(order("CLOUD"), 1792100000L)).isInstanceOf(Unknown.class);
      var result = client.getIssueByRelateNumber(order("CLOUD").orderId());
      assertThat(result).isInstanceOf(Unknown.class);
      if (scenario == Scenario.REDIRECT_TO_PRODUCTION)
        assertThat(((Unknown) result).reason()).isEqualTo("HTTP_302");
      assertThat(server.received()).hasSize(2);
    }
    assertClean(output);
  }

  @Test
  void rejectionDuplicateMissingQueryAndConnectionFailureAreDistinct(CapturedOutput output)
      throws Exception {
    try (var server = new FakeEinvoiceServer(KEY, IV)) {
      var client = client(server.baseUrl());
      assertThat(client.getIssueByRelateNumber("missing")).isEqualTo(new Rejected(1600003, ""));
      server.respond("Issue", Scenario.REJECTED);
      assertThat(client.issue(order("CLOUD"), 1000)).isEqualTo(new Rejected(4000001, "參數錯誤"));
      server.respond("Issue", Scenario.DUPLICATE);
      assertThat(((Rejected) client.issue(order("CLOUD"), 1000)).rtnCode()).isEqualTo(10000009);
      assertThat(client.getIssueByRelateNumber(order("CLOUD").orderId())).isInstanceOf(Ok.class);
    }
    int closedPort;
    try (var socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    }
    assertThat(client("http://127.0.0.1:" + closedPort).issue(order("CLOUD"), 1000))
        .isEqualTo(new Unknown("CONNECT"));
    assertClean(output);
  }

  private void assertClean(CapturedOutput output) {
    assertThat(output.getAll()).doesNotContain(KEY, IV, "private@example.test", "/ABC1234", "1234567");
    assertThat(output.getAll().lines().noneMatch(line -> line.startsWith("\tat "))).isTrue();
  }
}
