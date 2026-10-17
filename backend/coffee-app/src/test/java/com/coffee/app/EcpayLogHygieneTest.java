package com.coffee.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.coffee.orders.api.Orders;
import com.coffee.payments.api.TradeQuery;
import com.coffee.payments.internal.EcpayTradeQuery;
import com.coffee.payments.internal.ReconciliationService;
import com.coffee.shared.Problem;
import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(OutputCaptureExtension.class)
class EcpayLogHygieneTest {
  @Test
  void realTransportFailureMessageDoesNotExposeSecrets() throws Exception {
    int closedPort;
    try (var socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    var query =
        new EcpayTradeQuery(
            "3002607",
            "test-key-g01b",
            "test-iv-g01b",
            "stage",
            "http://127.0.0.1:" + closedPort + "/query",
            100);

    assertThatThrownBy(() -> query.query("ORDER000000000000001"))
        .isInstanceOf(Problem.class)
        .hasMessageNotContaining("test-key-g01b")
        .hasMessageNotContaining("test-iv-g01b");
  }

  @Test
  void scheduledLogsZeroCandidateSummaryWithoutSecrets(CapturedOutput output) {
    Orders orders = mock(Orders.class);
    when(orders.reconciliationCandidates(isNull(), anyLong(), anyLong(), eq(50), eq(0)))
        .thenReturn(List.of());

    service(orders).scheduled();

    assertThat(output)
        .contains(
            "綠界排程對帳完成：候選 0 筆，CONFIRMED 0、STILL_UNPAID 0、SIMULATED 0、AMOUNT_MISMATCH 0、QUERY_FAILED 0、略過 0")
        .doesNotContain("test-key-g01b", "test-iv-g01b");
  }

  @Test
  void scheduledLogsFailureClassWithoutMessageOrStackTrace(CapturedOutput output) {
    Orders orders = mock(Orders.class);
    var candidate = order("ORDER000000000000001", "PAID");
    when(orders.reconciliationCandidates(isNull(), anyLong(), anyLong(), eq(50), eq(0)))
        .thenReturn(List.of(candidate));
    when(orders.paymentSnapshot(candidate.id()))
        .thenThrow(new IllegalStateException("test-key-g01b test-iv-g01b"));

    service(orders).scheduled();

    assertThat(output)
        .contains("綠界排程對帳單筆失敗：訂單 ORDER000000000000001，IllegalStateException")
        .contains("候選 1 筆")
        .contains("略過 1")
        .doesNotContain("test-key-g01b", "test-iv-g01b", "\tat ");
  }

  private static ReconciliationService service(Orders orders) {
    return new ReconciliationService(
        orders,
        mock(TradeQuery.class),
        mock(JdbcTemplate.class),
        mock(PlatformTransactionManager.class),
        true,
        true,
        "3002607",
        "test-key-g01b",
        "test-iv-g01b",
        15,
        7,
        50);
  }

  private static Orders.Order order(String id, String status) {
    return new Orders.Order(
        id,
        "branch-a",
        "分店 A",
        "customer",
        status,
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
        List.of(), null, null, null, null);
  }
}
