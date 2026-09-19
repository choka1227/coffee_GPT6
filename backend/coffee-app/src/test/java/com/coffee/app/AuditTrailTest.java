package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffee.audit.api.Audit;
import com.coffee.branches.api.Branches;
import com.coffee.catalog.api.Catalog;
import com.coffee.identity.api.Identity;
import com.coffee.orders.api.Orders;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:audit-trail;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("dev")
class AuditTrailTest {
  @Autowired Audit audit;
  @Autowired Identity identity;
  @Autowired Orders orders;
  @Autowired Catalog catalog;
  @Autowired Branches branches;
  @Autowired JdbcTemplate db;

  @Test
  void freshRolesAndPermissionCatalogIncludeAuditView() {
    assertThat(Identity.PERMISSIONS).contains("AUDIT_VIEW");
    assertThat(
            db.queryForList(
                "select role_code from role_permissions where permission='AUDIT_VIEW'"
                    + " order by role_code",
                String.class))
        .containsExactly("HQ", "MANAGER");
  }

  @Test
  void businessWritePointsProduceScopedAuditEntriesAfterCommit() {
    Actor hq = identity.find("hq");
    Actor cashier = identity.find("cashier");
    String suffix = UUID.randomUUID().toString().substring(0, 8);

    String branchId =
        branches
            .save(
                hq,
                new Branches.Branch(
                    null, "稽核門市 " + suffix, "台北市", "02-12345678", true, 10000))
            .id();
    String productId =
        catalog
            .save(
                hq,
                new Catalog.Product(
                    null,
                    "稽核商品 " + suffix,
                    "測試",
                    "經典咖啡",
                    100,
                    30,
                    "latte",
                    "",
                    true,
                    "AVAILABLE",
                    List.of()))
            .id();
    Orders.Order created =
        orders.create(
            cashier,
            new Orders.Create(
                "taipei",
                "TAKEAWAY",
                "CASH",
                "",
                List.of(new Orders.LineInput("latte", 1, List.of()))),
            UUID.randomUUID().toString());
    orders.cash(cashier, created.id(), 200);
    orders.transition(cashier, created.id(), "PREPARING");

    assertThat(row("BRANCH_SAVE", branchId)).containsEntry("branch_id", branchId);
    assertThat(row("PRODUCT_SAVE", productId)).containsEntry("branch_id", null);
    assertThat(row("ORDER_CASH", created.id())).containsEntry("branch_id", "taipei");
    assertThat(row("ORDER_TRANSITION", created.id())).containsEntry("branch_id", "taipei");
  }

  @Test
  void cursorPaginationClampsLimitAndReturnsEveryRowExactlyOnce() {
    Actor hq = identity.find("hq");
    String action = "PAGE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    Set<String> expected = new HashSet<>();
    for (int i = 0; i < 205; i++) {
      String id = "audit-page-" + i;
      expected.add(id);
      audit.record(hq, action, id, i % 2 == 0 ? "taipei" : null, "分頁測試 " + i);
    }

    Audit.Page first = audit.search(hq, new Audit.Query(action, null, null, null, null, null, 500));
    assertThat(first.items()).hasSize(200);
    assertThat(first.nextCursor()).isNotNull();
    Audit.Page second =
        audit.search(
            hq, new Audit.Query(action, null, null, null, null, first.nextCursor(), 500));
    assertThat(second.items()).hasSize(5);
    assertThat(second.nextCursor()).isNull();

    Set<String> actual = new HashSet<>();
    first.items().forEach(e -> actual.add(e.targetId()));
    second.items().forEach(e -> actual.add(e.targetId()));
    assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  void branchScopeCannotEscapeOrSeeHeadquartersRows() {
    String action = "SCOPE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    Actor hq = identity.find("hq");
    Actor manager = identity.find("manager");
    audit.record(hq, action, "own", "taipei", "本店");
    audit.record(hq, action, "other", "banqiao", "別店");
    audit.record(hq, action, "headquarters", null, "總部");

    Audit.Page page =
        audit.search(manager, new Audit.Query(action, null, null, null, null, null, 50));
    assertThat(page.items()).isNotEmpty().allMatch(e -> "taipei".equals(e.branchId()));
    assertThatThrownBy(
            () ->
                audit.search(
                    manager, new Audit.Query(action, null, "banqiao", null, null, null, 50)))
        .isInstanceOfSatisfying(Problem.class, p -> assertThat(p.status).isEqualTo(403));

    Actor customer = identity.find("customer");
    Actor noPermission =
        new Actor("none", "none", "無權限", "CUSTOM", "GLOBAL", null, Set.of());
    assertThatThrownBy(
            () -> audit.search(customer, new Audit.Query(null, null, null, null, null, null, 50)))
        .isInstanceOfSatisfying(Problem.class, p -> assertThat(p.status).isEqualTo(403));
    assertThatThrownBy(
            () ->
                audit.search(
                    noPermission, new Audit.Query(null, null, null, null, null, null, 50)))
        .isInstanceOfSatisfying(Problem.class, p -> assertThat(p.status).isEqualTo(403));
  }

  private java.util.Map<String, Object> row(String action, String targetId) {
    return db.queryForMap(
        "select branch_id,summary from audit_log where action=? and target_id=?"
            + " order by created_at desc limit 1",
        action,
        targetId);
  }
}
