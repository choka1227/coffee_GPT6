package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffee.app.readonly.WriteDetectingDataSource;
import com.coffee.identity.api.Identity;
import com.coffee.orders.api.Orders;
import com.coffee.reporting.api.Reports;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:preview-write-guard-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("dev")
@Import(PreviewWriteGuardTest.Wrapping.class)
class PreviewWriteGuardTest {
  @Autowired Orders orders;
  @Autowired Reports reports;
  @Autowired Identity identity;
  @Autowired JdbcTemplate db;

  @Test
  void previewWithoutDiscountCodeIssuesNoWrites() {
    assertThat(writes(() -> orders.preview(identity.find("customer"), preview(null)))).isEmpty();
  }

  @Test
  void previewWithDiscountCodeIssuesNoWrites() {
    insertDiscount("GUARD-10");

    assertThat(writes(() -> orders.preview(identity.find("customer"), preview("GUARD-10"))))
        .isEmpty();
  }

  @Test
  void previewWithItemPromotionIssuesNoWrites() {
    insertPromotion();

    assertThat(writes(() -> orders.preview(identity.find("customer"), preview(null)))).isEmpty();
  }

  @Test
  void monthlyReportIssuesNoWrites() {
    String month = YearMonth.now(ZoneId.of("Asia/Taipei")).toString();

    assertThat(writes(() -> reports.report(identity.find("manager"), month, "taipei"))).isEmpty();
  }

  @Test
  void nothingIsRecordedWhileTheDetectorIsNotArmed() {
    db.update("update discounts set redeemed_count=redeemed_count where 1=0");

    assertThat(WriteDetectingDataSource.disarm()).isEmpty();
  }

  @Test
  void selectForUpdateIsRecordedAsAWrite() {
    var captured = WriteDetectingDataSource.around(
        () -> db.queryForList("select id from branches for update"));

    assertThat(captured.writes()).singleElement()
        .satisfies(sql -> assertThat(sql).containsIgnoringCase("for update"));
  }

  @Test
  void creatingAnOrderIsRecordedSoTheDetectorIsProvenToWork() {
    var captured = WriteDetectingDataSource.around(() -> orders.create(
        identity.find("cashier"),
        new Orders.Create(
            "taipei", "TAKEAWAY", "CASH", "", null, List.of(line())),
        UUID.randomUUID().toString()));

    assertThat(captured.writes()).anySatisfy(sql -> assertThat(sql.trim())
        .startsWithIgnoringCase("insert")
        .containsIgnoringCase("orders"));
  }

  @Test
  void writableCteIsClassifiedAsAWrite() {
    assertThat(List.of(
        "with t as (select 1) update discounts set redeemed_count = 0",
        "with t as (select 1) insert into orders(id) values ('x')",
        "with t as (select 1) delete from orders",
        "with x as (update discounts set redeemed_count = redeemed_count + 1 returning id) select * from x",
        "/* c */ (with t as (select 1) update discounts set redeemed_count = 0)"))
        .allSatisfy(sql -> assertThat(WriteDetectingDataSource.isWrite(sql)).isTrue());
  }

  @Test
  void readOnlyCteIsNotClassifiedAsAWrite() {
    assertThat(List.of(
        "with t as (select id from orders) select * from t",
        "with t as (select 'update' as s) select * from t",
        "with t as (select 1 as \"delete\") select * from t",
        "with t as (select 1) /* insert */ select * from t",
        "select 1"))
        .allSatisfy(sql -> assertThat(WriteDetectingDataSource.isWrite(sql)).isFalse());
  }

  private List<String> writes(java.util.function.Supplier<?> call) {
    return WriteDetectingDataSource.around(call).writes();
  }

  private Orders.PreviewRequest preview(String discountCode) {
    return new Orders.PreviewRequest("taipei", discountCode, List.of(line()));
  }

  private Orders.LineInput line() {
    return new Orders.LineInput("latte", 2, List.of("temp-hot", "sugar-none"));
  }

  private void insertDiscount(String code) {
    db.update(
        "insert into discounts(id,code,name,kind,percent,amount,min_subtotal,branch_id,starts_at,"
            + "ends_at,max_redemptions,redeemed_count,active,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(), code, code, "PERCENT", 10, 0, 0, null, null, null,
        null, 0, true, 1_000L, 1_000L);
  }

  private void insertPromotion() {
    db.update(
        "insert into item_promotions(id,name,kind,percent,nth,target_kind,product_id,category,"
            + "branch_id,starts_at,ends_at,active,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        "guard-bogo", "買一送一", "NTH_PERCENT", 100, 2, "PRODUCT", "latte", null, null,
        null, null, true, 1_000L, 1_000L);
  }

  @TestConfiguration
  static class Wrapping {
    @Bean
    static BeanPostProcessor wrapDataSource() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) throws BeansException {
          return bean instanceof DataSource dataSource
                  && !(dataSource instanceof WriteDetectingDataSource)
              ? new WriteDetectingDataSource(dataSource)
              : bean;
        }
      };
    }
  }
}
