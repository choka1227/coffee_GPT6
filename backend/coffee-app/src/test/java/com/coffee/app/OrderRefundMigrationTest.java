package com.coffee.app;

import static org.assertj.core.api.Assertions.*;

import com.coffee.app.bootstrap.InitialData;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class OrderRefundMigrationTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void upgradeFromV16PreservesOrdersAndGrantsExactlyTwoRoles(boolean alreadyGranted) {
    var source = new DriverManagerDataSource("jdbc:h2:mem:refund-upgrade-" + UUID.randomUUID()
        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    Flyway.configure().dataSource(source).target("16").load().migrate();
    var db = new JdbcTemplate(source);
    new InitialData(db, true, "bootstrap", "TestPassword!2026", "TestPassword!2026")
        .run(new DefaultApplicationArguments());
    if (!alreadyGranted) db.update("delete from role_permissions where permission='ORDER_REFUND'");
    var before = db.queryForList("select id,status,total,paid_at from orders order by id");
    Flyway.configure().dataSource(source).load().migrate();
    assertThat(db.queryForList("select id,status,total,paid_at from orders order by id")).isEqualTo(before);
    assertThat(db.queryForObject("select count(*) from orders where refunded_at is not null", Integer.class)).isZero();
    assertThat(db.queryForList("select role_code from role_permissions where permission='ORDER_REFUND'"
        + " order by role_code", String.class)).containsExactly("HQ", "MANAGER");
    assertThat(db.queryForObject("select count(*) from invoice_reversals", Integer.class)).isZero();
  }
}
