package com.coffee.app;

import static org.assertj.core.api.Assertions.*;

import com.coffee.catalog.api.Catalog;
import com.coffee.identity.api.Identity;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:availability-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("dev")
class BranchMenuAvailabilityTest {
  @Autowired Catalog catalog;
  @Autowired Identity identity;
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager transactionManager;

  Actor hq;
  Actor cashier;
  Actor customer;

  @BeforeEach
  void reset() {
    db.update("delete from branch_products");
    db.update("delete from audit_log where action like 'MENU_AVAILABILITY_%'");
    hq = identity.find("hq");
    cashier = identity.find("cashier");
    customer = identity.find("customer");
  }

  @Test
  void permissionsScopeValidationAndAuditAreEnforced() {
    assertProblem(403, "只能存取所屬分店資料", () -> catalog.availability(cashier, "banqiao"));
    assertProblem(
        403,
        "只能存取所屬分店資料",
        () -> catalog.setAvailability(cashier, "banqiao", "latte", "SOLD_OUT"));
    assertProblem(
        403,
        "沒有此功能的操作權限",
        () -> catalog.setAvailability(customer, "taipei", "latte", "SOLD_OUT"));

    catalog.setAvailability(cashier, "taipei", "latte", "SOLD_OUT");
    assertThat(current("taipei", "latte")).isEqualTo("SOLD_OUT");
    assertThat(catalog.availability(cashier, "taipei"))
        .filteredOn(value -> value.productId().equals("latte"))
        .singleElement()
        .extracting(Catalog.BranchAvailability::availability)
        .isEqualTo("SOLD_OUT");

    assertProblem(
        403,
        "分店供應品項限總部設定",
        () -> catalog.setAvailability(cashier, "taipei", "croissant", "UNLISTED"));
    catalog.setAvailability(hq, "taipei", "croissant", "UNLISTED");
    assertProblem(
        403,
        "分店供應品項限總部設定",
        () -> catalog.setAvailability(cashier, "taipei", "croissant", "AVAILABLE"));
    catalog.setAvailability(hq, "taipei", "croissant", "AVAILABLE");

    for (String invalid : new String[] {"", "sold_out"}) {
      assertProblem(
          400,
          "供應狀態不正確",
          () -> catalog.setAvailability(hq, "taipei", "latte", invalid));
    }
    assertProblem(
        400,
        "供應狀態不正確",
        () -> catalog.setAvailability(hq, "taipei", "latte", null));
    assertProblem(
        404,
        "找不到商品",
        () -> catalog.setAvailability(hq, "taipei", "missing", "AVAILABLE"));

    assertThat(
            db.queryForObject(
                "select count(*) from audit_log where action like 'MENU_AVAILABILITY_%'",
                Integer.class))
        .isEqualTo(3);
    assertThat(
            db.queryForList(
                "select action from audit_log where target_id='taipei:croissant'",
                String.class))
        .containsExactlyInAnyOrder(
            "MENU_AVAILABILITY_UNLISTED", "MENU_AVAILABILITY_AVAILABLE");
  }

  @Test
  void expiredSoldOutReadsAsAvailableAndUuidTargetFitsAuditSchema() {
    int yesterday =
        Integer.parseInt(
            LocalDate.now(ZoneId.of("Asia/Taipei"))
                .minusDays(1)
                .format(DateTimeFormatter.BASIC_ISO_DATE));
    db.update(
        "insert into branch_products values(?,?,?,?,?,?)",
        "taipei",
        "latte",
        "SOLD_OUT",
        yesterday,
        1L,
        "cashier");
    assertThat(catalog.availability(cashier, "taipei"))
        .filteredOn(value -> value.productId().equals("latte"))
        .singleElement()
        .extracting(Catalog.BranchAvailability::availability)
        .isEqualTo("AVAILABLE");

    String branchId = UUID.randomUUID().toString();
    String productId = UUID.randomUUID().toString();
    db.update(
        "insert into branches values(?,?,?,?,?,?)",
        branchId,
        "長識別碼門市",
        "台北市",
        "02-00000000",
        true,
        1);
    db.update(
        "insert into products values(?,?,?,?,?,?,?,?,?,?)",
        productId,
        "長識別碼商品",
        "測試",
        "經典咖啡",
        100,
        10,
        "latte",
        "",
        true,
        99);
    catalog.setAvailability(hq, branchId, productId, "UNLISTED");
    assertThat(
            db.queryForObject(
                "select target_id from audit_log where target_id=?",
                String.class,
                branchId + ":" + productId))
        .hasSize(73);
  }

  @Test
  void productLockSerializesHeadquartersUnlistedAgainstStoreChanges() throws Exception {
    assertConcurrentUnlistedWins(false);
    assertConcurrentUnlistedWins(true);
  }

  private void assertConcurrentUnlistedWins(boolean existingOverride) throws Exception {
    db.update("delete from branch_products where branch_id='taipei' and product_id='latte'");
    if (existingOverride) catalog.setAvailability(hq, "taipei", "latte", "AVAILABLE");
    var hqWritten = new CountDownLatch(1);
    var storeAttempting = new CountDownLatch(1);
    var transaction = new TransactionTemplate(transactionManager);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var headquarters =
          executor.submit(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        catalog.setAvailability(hq, "taipei", "latte", "UNLISTED");
                        hqWritten.countDown();
                        await(storeAttempting);
                      }));
      var store =
          executor.submit(
              () -> {
                await(hqWritten);
                storeAttempting.countDown();
                try {
                  transaction.executeWithoutResult(
                      status ->
                          catalog.setAvailability(cashier, "taipei", "latte", "SOLD_OUT"));
                  return null;
                } catch (Throwable failure) {
                  return failure;
                }
              });
      headquarters.get(5, TimeUnit.SECONDS);
      Throwable failure = store.get(5, TimeUnit.SECONDS);
      assertThat(failure).isNotNull();
      assertThat(rootCause(failure))
          .isInstanceOfSatisfying(
              Problem.class,
              problem -> {
                assertThat(problem.status).isEqualTo(403);
                assertThat(problem).hasMessage("分店供應品項限總部設定");
              });
    } finally {
      executor.shutdownNow();
    }
    assertThat(current("taipei", "latte")).isEqualTo("UNLISTED");
  }

  private String current(String branchId, String productId) {
    return db.queryForObject(
        "select availability from branch_products where branch_id=? and product_id=?",
        String.class,
        branchId,
        productId);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static Throwable rootCause(Throwable failure) {
    Throwable result = failure;
    while (result.getCause() != null) result = result.getCause();
    return result;
  }

  private static void assertProblem(
      int status, String message, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            Problem.class,
            problem -> {
              assertThat(problem.status).isEqualTo(status);
              assertThat(problem).hasMessage(message);
            });
  }
}
