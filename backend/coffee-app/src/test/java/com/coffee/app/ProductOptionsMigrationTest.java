package com.coffee.app;

import static org.assertj.core.api.Assertions.*;

import com.coffee.app.bootstrap.InitialData;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ProductOptionsMigrationTest {
  private DriverManagerDataSource database() {
    return new DriverManagerDataSource(
        "jdbc:h2:mem:options-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sa",
        "");
  }

  private void seed(JdbcTemplate db, boolean demo) {
    new InitialData(db, demo, "bootstrap", "TestPassword!2026", "TestPassword!2026")
        .run(new DefaultApplicationArguments());
  }

  @Test
  void freshDemoPreservesHistoryAndDoesNotRestoreRemovedBindingsOnRestart() {
    var source = database();
    Flyway.configure().dataSource(source).load().migrate();
    var db = new JdbcTemplate(source);
    seed(db, true);
    assertThat(db.queryForObject("select count(*) from product_option_groups", Integer.class))
        .isEqualTo(12);
    assertThat(
            db.queryForObject(
                "select count(*) from product_option_groups g join products p on p.id=g.product_id"
                    + " where p.category='手作烘焙'",
                Integer.class))
        .isZero();
    assertThat(db.queryForObject("select count(*) from order_items", Integer.class)).isPositive();
    assertThat(
            db.queryForObject(
                "select count(*) from order_items where options_price<>0"
                    + " or options_cost<>0 or temperature is null or sugar is null",
                Integer.class))
        .isZero();
    assertThat(db.queryForObject("select count(*) from order_item_options", Integer.class))
        .isZero();
    var prices = db.queryForList("select id,price from products order by id");
    var history = db.queryForList("select * from order_items order by id");
    seed(db, true);
    assertThat(db.queryForObject("select count(*) from product_option_groups", Integer.class))
        .isEqualTo(12);
    assertThat(db.queryForList("select id,price from products order by id")).isEqualTo(prices);
    assertThat(db.queryForList("select * from order_items order by id")).isEqualTo(history);
    db.update("delete from product_option_groups where product_id='latte'");
    seed(db, true);
    assertThat(
            db.queryForObject(
                "select count(*) from product_option_groups where product_id='latte'",
                Integer.class))
        .isZero();
  }

  @Test
  void productionBootstrapDoesNotCreateDemoBindings() {
    var source = database();
    Flyway.configure().dataSource(source).load().migrate();
    var db = new JdbcTemplate(source);
    seed(db, false);
    seed(db, false);
    assertThat(db.queryForObject("select count(*) from accounts", Integer.class)).isEqualTo(1);
    assertThat(db.queryForObject("select count(*) from products", Integer.class)).isZero();
    assertThat(db.queryForObject("select count(*) from product_option_groups", Integer.class))
        .isZero();
  }

  @Test
  void upgradeBindsExistingDrinksWithoutChangingPricesAndEnforcesConstraints() {
    var source = database();
    Flyway.configure().dataSource(source).target("2").load().migrate();
    var db = new JdbcTemplate(source);
    db.update("insert into products values('drink','飲品','','咖啡',140,40,'','',true,1)");
    db.update("insert into products values('bakery','烘焙','','手作烘焙',90,30,'','',true,2)");
    var prices = db.queryForList("select id,price from products order by id");
    var flyway = Flyway.configure().dataSource(source).load();
    flyway.migrate();
    flyway.migrate();
    assertThat(db.queryForList("select id,price from products order by id")).isEqualTo(prices);
    assertThat(
            db.queryForList(
                "select group_id from product_option_groups where product_id='drink'",
                String.class))
        .containsExactlyInAnyOrder("temperature", "sugar");
    assertThat(
            db.queryForObject(
                "select count(*) from product_option_groups where product_id='bakery'",
                Integer.class))
        .isZero();
    assertThat(db.queryForObject("select count(*) from option_groups", Integer.class)).isEqualTo(2);
    assertThat(
            db.queryForObject(
                "select count(*) from option_items where price_delta=0", Integer.class))
        .isEqualTo(8);
    assertThatThrownBy(
            () -> db.update("update option_items set price_delta=-1 where id='temp-hot'"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> db.update("update option_groups set min_select=2,max_select=1 where id='sugar'"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    db.update("insert into option_groups values('extras','加料','MULTI',0,3,true,99)");
    assertThat(db.queryForObject("select count(*) from order_item_options", Integer.class))
        .isZero();
  }
}
