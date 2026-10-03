package com.coffee.app;

import static org.assertj.core.api.Assertions.*;

import com.coffee.catalog.api.Catalog;
import com.coffee.identity.api.Identity;
import com.coffee.orders.api.Orders;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:stock-ordering;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("dev")
class BranchProductStockOrderingTest {
  @Autowired Catalog catalog;
  @Autowired Orders orders;
  @Autowired Identity identity;
  @Autowired JdbcTemplate db;

  Actor cashier;
  Actor headquarters;

  @BeforeEach
  void reset() {
    db.update("delete from branch_product_stock_reservation");
    db.update("delete from branch_product_stock");
    db.update("delete from branch_products");
    cashier = identity.find("cashier");
    headquarters = identity.find("hq");
  }

  @AfterEach
  void clean() {
    db.update("delete from branch_product_stock_reservation");
    db.update("delete from branch_product_stock");
    db.update("delete from branch_products");
  }

  @Test
  void mergesDuplicateLinesAndIdempotentReplayDoesNotDeductTwice() {
    catalog.setStock(cashier, "taipei", "latte", 5);
    String key = UUID.randomUUID().toString();
    var request = createRequest(line("latte", 1), line("latte", 2));

    var first = orders.create(cashier, request, key);
    var replay = orders.create(cashier, request, key);

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(remaining("latte", today())).isEqualTo(2);
    assertThat(reserved(first.id(), "latte")).isEqualTo(3);
    assertThat(reservationCount(first.id())).isEqualTo(1);
  }

  @Test
  void limitedOrderDeductsAndCancellationRestoresTheReservedQuantity() {
    catalog.setStock(cashier, "taipei", "latte", 3);

    var order = orders.create(
        cashier, createRequest(line("latte", 2)), UUID.randomUUID().toString());
    assertThat(remaining("latte", today())).isEqualTo(1);
    assertThat(reserved(order.id(), "latte")).isEqualTo(2);

    orders.transition(cashier, order.id(), "CANCELLED");
    assertThat(remaining("latte", today())).isEqualTo(3);
    assertThat(reservationCount(order.id())).isZero();
  }

  @Test
  void soldOutAndInsufficientStockRejectTheWholeOrder() {
    catalog.setStock(cashier, "taipei", "americano", 2);
    catalog.setStock(cashier, "taipei", "latte", 0);

    int ordersBefore = orderCount();
    assertThatThrownBy(
            () -> orders.create(
                cashier,
                createRequest(line("americano", 1), line("latte", 1)),
                UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(
            Problem.class,
            problem -> assertThat(problem).hasMessage("本店今日已售完此商品，請調整餐點"));
    assertThat(remaining("americano", today())).isEqualTo(2);
    assertThat(orderCount()).isEqualTo(ordersBefore);
    assertThat(db.queryForObject(
        "select count(*) from branch_product_stock_reservation", Integer.class)).isZero();

    catalog.setStock(cashier, "taipei", "latte", 3);
    assertThatThrownBy(
            () -> orders.create(
                cashier, createRequest(line("latte", 5)), UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(
            Problem.class,
            problem -> assertThat(problem).hasMessage("本店今日此商品僅剩 3 份，請調整數量"));
    assertThat(remaining("latte", today())).isEqualTo(3);

    catalog.setStock(cashier, "taipei", "latte", 3);
    assertThatThrownBy(
            () -> orders.create(
                cashier,
                createRequest(line("latte", 2), line("latte", 2)),
                UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(
            Problem.class,
            problem -> assertThat(problem).hasMessage("本店今日此商品僅剩 3 份，請調整數量"));
    assertThat(remaining("latte", today())).isEqualTo(3);
    assertThat(orderCount()).isEqualTo(ordersBefore);
  }

  @Test
  void unlimitedProductsCreateNoReservationAndCancellationCannotInventStock() {
    var order = orders.create(
        cashier, createRequest(line("croissant", 2)), UUID.randomUUID().toString());

    assertThat(reservationCount(order.id())).isZero();
    catalog.setStock(cashier, "taipei", "croissant", 5);
    var limited = orders.create(
        cashier, createRequest(line("croissant", 2)), UUID.randomUUID().toString());
    assertThat(remaining("croissant", today())).isEqualTo(3);
    orders.transition(cashier, order.id(), "CANCELLED");
    assertThat(remaining("croissant", today())).isEqualTo(3);
    assertThat(reservationCount(order.id())).isZero();
    assertThat(reservationCount(limited.id())).isEqualTo(1);
  }

  @Test
  void clearingAndRecreatingALimitDiscardsOldReservations() {
    catalog.setStock(cashier, "taipei", "latte", 5);
    var old = orders.create(
        cashier, createRequest(line("latte", 2)), UUID.randomUUID().toString());
    assertThat(remaining("latte", today())).isEqualTo(3);

    catalog.setStock(cashier, "taipei", "latte", null);
    catalog.setStock(cashier, "taipei", "latte", 5);
    assertThat(reservationCount(old.id())).isZero();
    orders.transition(cashier, old.id(), "CANCELLED");

    assertThat(remaining("latte", today())).isEqualTo(5);
  }

  @Test
  void cancellationRestoresFromReservationOnlyOnceAndCapsAtCurrentQuantity() {
    catalog.setStock(cashier, "taipei", "latte", 5);
    var order = orders.create(
        cashier, createRequest(line("latte", 3)), UUID.randomUUID().toString());
    catalog.setStock(cashier, "taipei", "latte", 1);

    orders.transition(cashier, order.id(), "CANCELLED");
    catalog.releaseStock("taipei", order.id());

    assertThat(remaining("latte", today())).isEqualTo(1);
    assertThat(reservationCount(order.id())).isZero();
  }

  @Test
  void cancellationUsesReservationDateAndMissingStockRowIsNotRecreated() {
    catalog.setStock(cashier, "taipei", "latte", 2);
    var oldDate = date(-1);
    var order = orders.create(
        cashier, createRequest(line("latte", 1)), UUID.randomUUID().toString());
    db.update("delete from branch_product_stock where branch_id='taipei' and product_id='latte'");
    db.update(
        "update branch_product_stock_reservation set on_date=? where order_id=?",
        oldDate,
        order.id());
    insertStock("latte", today(), 7, 4);
    insertStock("latte", oldDate, 4, 1);

    orders.transition(cashier, order.id(), "CANCELLED");

    assertThat(remaining("latte", oldDate)).isEqualTo(2);
    assertThat(remaining("latte", today())).isEqualTo(4);
    assertThat(reservationCount(order.id())).isZero();

    catalog.setStock(cashier, "taipei", "latte", null);
    catalog.setStock(cashier, "taipei", "latte", 2);
    var missing = orders.create(
        cashier, createRequest(line("latte", 1)), UUID.randomUUID().toString());
    db.update("delete from branch_product_stock where branch_id='taipei' and product_id='latte'");
    orders.transition(cashier, missing.id(), "CANCELLED");
    assertThat(stockCount("latte", today())).isZero();
    assertThat(reservationCount(missing.id())).isZero();
  }

  @Test
  void concurrentLastItemHasExactlyOneWinner() throws Exception {
    catalog.setStock(cashier, "taipei", "latte", 1);
    var start = new CountDownLatch(1);
    var successes = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> futures = List.of(
          executor.submit(() -> reserveAfter(start, "race-a", successes, List.of(stock("latte", 1)))),
          executor.submit(() -> reserveAfter(start, "race-b", successes, List.of(stock("latte", 1)))));
      start.countDown();
      for (var future : futures) future.get(5, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    assertThat(successes).hasValue(1);
    assertThat(remaining("latte", today())).isZero();
    assertThat(db.queryForObject(
        "select count(*) from branch_product_stock_reservation", Integer.class)).isEqualTo(1);
  }

  @Test
  void opposingLineOrderCompletesWithoutDeadlock() throws Exception {
    catalog.setStock(cashier, "taipei", "americano", 2);
    catalog.setStock(cashier, "taipei", "latte", 2);
    var start = new CountDownLatch(1);
    var successes = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> futures = List.of(
          executor.submit(() -> reserveAfter(
              start, "pair-a", successes, List.of(stock("latte", 1), stock("americano", 1)))),
          executor.submit(() -> reserveAfter(
              start, "pair-b", successes, List.of(stock("americano", 1), stock("latte", 1)))));
      start.countDown();
      for (var future : futures) future.get(5, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    assertThat(successes).hasValue(2);
    assertThat(remaining("americano", today())).isZero();
    assertThat(remaining("latte", today())).isZero();
  }

  @Test
  void menuShowsRemainingAndAutomaticallyMarksExhaustedStockSoldOut() {
    catalog.setStock(cashier, "taipei", "latte", 2);

    assertThat(menuProduct(cashier, "latte"))
        .satisfies(
            product -> {
              assertThat(product.availability()).isEqualTo("AVAILABLE");
              assertThat(product.remaining()).isEqualTo(2);
            });

    orders.create(cashier, createRequest(line("latte", 2)), UUID.randomUUID().toString());

    assertThat(menuProduct(cashier, "latte"))
        .satisfies(
            product -> {
              assertThat(product.availability()).isEqualTo("SOLD_OUT");
              assertThat(product.remaining()).isZero();
            });
    assertThatThrownBy(() -> catalog.sellable("taipei", "latte"))
        .isInstanceOfSatisfying(
            Problem.class,
            problem -> assertThat(problem).hasMessage("本店今日已售完此商品，請調整餐點"));
  }

  @Test
  void menuKeepsUnlimitedAndManagementViewsStockNeutral() {
    assertThat(menuProduct(cashier, "croissant"))
        .satisfies(
            product -> {
              assertThat(product.availability()).isEqualTo("AVAILABLE");
              assertThat(product.remaining()).isNull();
            });
    catalog.setStock(cashier, "taipei", "latte", 2);

    assertThat(catalog.list(headquarters, true, null))
        .allSatisfy(product -> assertThat(product.remaining()).isNull());
  }

  @Test
  void unlistedStillTakesPriorityAndDoesNotLeakRemaining() {
    catalog.setStock(cashier, "taipei", "latte", 0);
    catalog.setAvailability(headquarters, "taipei", "latte", "UNLISTED");

    assertThat(catalog.list(cashier, false, "taipei"))
        .noneMatch(product -> product.id().equals("latte"));
    assertThatThrownBy(() -> catalog.sellable("taipei", "latte"))
        .isInstanceOfSatisfying(
            Problem.class, problem -> assertThat(problem).hasMessage("本店未供應此商品"));
  }

  private void reserveAfter(
      CountDownLatch start, String orderId, AtomicInteger successes, List<Catalog.StockLine> lines) {
    try {
      start.await(5, TimeUnit.SECONDS);
      catalog.reserveStock("taipei", orderId, lines);
      successes.incrementAndGet();
    } catch (Problem expected) {
      assertThat(expected.status).isEqualTo(400);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private Orders.Create createRequest(Orders.LineInput... lines) {
    return new Orders.Create("taipei", "TAKEAWAY", "CASH", "庫存測試", null, List.of(lines));
  }

  private Orders.LineInput line(String productId, int quantity) {
    return new Orders.LineInput(
        productId,
        quantity,
        productId.equals("croissant") ? List.of() : List.of("temp-hot", "sugar-none"));
  }

  private Catalog.StockLine stock(String productId, int quantity) {
    return new Catalog.StockLine(productId, quantity);
  }

  private Catalog.Product menuProduct(Actor actor, String productId) {
    return catalog.list(actor, false, "taipei").stream()
        .filter(product -> product.id().equals(productId))
        .findFirst()
        .orElseThrow();
  }

  private int remaining(String productId, int onDate) {
    return db.queryForObject(
        "select remaining from branch_product_stock"
            + " where branch_id='taipei' and product_id=? and on_date=?",
        Integer.class,
        productId,
        onDate);
  }

  private int stockCount(String productId, int onDate) {
    return db.queryForObject(
        "select count(*) from branch_product_stock"
            + " where branch_id='taipei' and product_id=? and on_date=?",
        Integer.class,
        productId,
        onDate);
  }

  private int reserved(String orderId, String productId) {
    return db.queryForObject(
        "select quantity from branch_product_stock_reservation"
            + " where order_id=? and product_id=?",
        Integer.class,
        orderId,
        productId);
  }

  private int reservationCount(String orderId) {
    return db.queryForObject(
        "select count(*) from branch_product_stock_reservation where order_id=?",
        Integer.class,
        orderId);
  }

  private int orderCount() {
    return db.queryForObject("select count(*) from orders", Integer.class);
  }

  private void insertStock(String productId, int onDate, int quantity, int remaining) {
    db.update(
        "insert into branch_product_stock"
            + "(branch_id,product_id,on_date,quantity,remaining,updated_at,updated_by)"
            + " values('taipei',?,?,?,?,?,?)",
        productId,
        onDate,
        quantity,
        remaining,
        1L,
        cashier.id());
  }

  private int today() {
    return date(0);
  }

  private int date(int days) {
    return Integer.parseInt(
        LocalDate.now(ZoneId.of("Asia/Taipei"))
            .plusDays(days)
            .format(DateTimeFormatter.BASIC_ISO_DATE));
  }
}
