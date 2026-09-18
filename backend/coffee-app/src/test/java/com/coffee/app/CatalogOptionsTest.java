package com.coffee.app;

import static org.assertj.core.api.Assertions.*;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import com.coffee.app.bootstrap.InitialData;
import com.coffee.catalog.api.Catalog;
import com.coffee.catalog.internal.CatalogService;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CatalogOptionsTest {
  private JdbcTemplate db;
  private CatalogService catalog;
  private Actor headquarters;

  @BeforeEach
  void setUp() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:catalog-options-"
                + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "sa",
            "");
    Flyway.configure().dataSource(source).load().migrate();
    db = new JdbcTemplate(source);
    new InitialData(db, true, "bootstrap", "TestPassword!2026", "TestPassword!2026")
        .run(new DefaultApplicationArguments());
    catalog = new CatalogService(db);
    headquarters =
        new Actor("hq", "hq", "總部", "ADMIN", "GLOBAL", null, Set.of("MENU_MANAGE"));
  }

  @Test
  void menuMasksCostsButManagementAndResolutionKeepRealCosts() {
    db.update("update option_items set price_delta=20,cost_delta=12 where id='temp-hot'");

    var customer = new Actor("c", "c", "顧客", "CUSTOMER", "SELF", null, Set.of());
    var publicLatte =
        catalog.list(customer, false).stream()
            .filter(product -> product.id().equals("latte"))
            .findFirst()
            .orElseThrow();
    assertThat(publicLatte.cost()).isZero();
    assertThat(publicLatte.optionGroups())
        .flatExtracting(Catalog.OptionGroup::items)
        .allMatch(item -> item.costDelta() == 0);

    var managedLatte =
        catalog.list(headquarters, true).stream()
            .filter(product -> product.id().equals("latte"))
            .findFirst()
            .orElseThrow();
    assertThat(managedLatte.cost()).isPositive();
    assertThat(managedLatte.optionGroups())
        .flatExtracting(Catalog.OptionGroup::items)
        .anyMatch(item -> item.costDelta() == 12);

    assertThat(catalog.resolveOptions("latte", List.of("temp-hot", "sugar-none")))
        .anyMatch(option -> option.priceDelta() == 20 && option.costDelta() == 12);
  }

  @Test
  void resolutionEnforcesRequestAndSelectionRules() {
    assertProblem("選項數量超過上限", () -> catalog.resolveOptions("latte", null));
    assertProblem(
        "選項數量超過上限",
        () -> catalog.resolveOptions("latte", Collections.nCopies(21, "temp-hot")));
    assertProblem(
        "同一個選項不能重複選擇",
        () -> catalog.resolveOptions("latte", List.of("temp-hot", "temp-hot")));
    assertProblem(
        "請選擇「甜度」", () -> catalog.resolveOptions("latte", List.of("temp-hot")));
    assertProblem(
        "「溫度」只能選擇一項",
        () -> catalog.resolveOptions("latte", List.of("temp-hot", "temp-none", "sugar-none")));

    db.update("update option_items set active=false where id='temp-hot'");
    assertProblem(
        "選項已停用，請重新整理菜單",
        () -> catalog.resolveOptions("latte", List.of("temp-hot", "sugar-none")));
    db.update("update option_items set active=true where id='temp-hot'");

    db.update("insert into option_groups values('milk','鮮乳','SINGLE',0,1,true,3)");
    db.update("insert into option_items values('oat','milk','燕麥奶',20,12,true,1)");
    assertProblem(
        "此商品不提供所選的選項",
        () -> catalog.resolveOptions("latte", List.of("temp-hot", "sugar-none", "oat")));

    db.update("update option_groups set active=false where id='temperature'");
    assertProblem(
        "選項已停用，請重新整理菜單",
        () -> catalog.resolveOptions("latte", List.of("temp-hot", "sugar-none")));
  }

  @Test
  void managementRequiresGlobalPermissionAndValidatesWrites() {
    var branchManager =
        new Actor("m", "m", "店長", "MANAGER", "BRANCH", "taipei", Set.of("MENU_MANAGE"));
    assertThatThrownBy(() -> catalog.optionGroups(branchManager))
        .isInstanceOfSatisfying(
            Problem.class,
            problem -> {
              assertThat(problem.status).isEqualTo(403);
              assertThat(problem).hasMessage("菜單管理限總部範圍");
            });

    var extras =
        catalog.saveOptionGroup(
            headquarters,
            new Catalog.OptionGroup(null, "加料", "MULTI", 0, 2, true, 3, List.of()));
    var pearls =
        catalog.saveOptionItem(
            headquarters,
            new Catalog.OptionItem(null, extras.id(), "珍珠", 15, 5, true, 1));
    catalog.bindProductOptions(
        headquarters, "latte", List.of("temperature", "sugar", extras.id()));
    assertThat(catalog.optionGroups(headquarters))
        .filteredOn(group -> group.id().equals(extras.id()))
        .flatExtracting(Catalog.OptionGroup::items)
        .extracting(Catalog.OptionItem::id)
        .containsExactly(pearls.id());

    assertProblem(
        "加價金額需為 0 至 10000 元",
        () ->
            catalog.saveOptionItem(
                headquarters,
                new Catalog.OptionItem(null, extras.id(), "錯誤", -1, 0, true, 2)));
    assertProblem(
        "選擇數量下限不能大於上限",
        () ->
            catalog.saveOptionGroup(
                headquarters,
                new Catalog.OptionGroup(null, "錯誤", "MULTI", 2, 1, true, 9, List.of())));
    assertThatThrownBy(
            () ->
                catalog.saveOptionGroup(
                    headquarters,
                    new Catalog.OptionGroup(
                        extras.id(), "加料", "MULTI", 0, 2, false, 3, List.of())))
        .isInstanceOfSatisfying(
            Problem.class,
            problem -> {
              assertThat(problem.status).isEqualTo(409);
              assertThat(problem).hasMessage("此選項群組仍有商品使用，請先解除綁定");
            });
  }

  private void assertProblem(String message, ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOf(Problem.class)
        .hasMessage(message);
  }
}
