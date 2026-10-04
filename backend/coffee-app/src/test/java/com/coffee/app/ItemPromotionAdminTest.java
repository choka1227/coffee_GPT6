package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffee.catalog.api.Promotions;
import com.coffee.catalog.api.Promotions.Rule;
import com.coffee.identity.api.Identity;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:item-promotion-admin;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("dev")
@AutoConfigureMockMvc
class ItemPromotionAdminTest {
  @Autowired Promotions promotions;
  @Autowired Identity identity;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;

  Actor hq;

  @BeforeEach
  void reset() {
    db.update("delete from order_item_promotions");
    db.update("delete from item_promotions");
    hq = identity.find("hq");
  }

  @Test
  void headquartersCanCreateListAndUpdateBothKinds() {
    var item = promotions.save(hq, item(null, 18));
    var nth = promotions.save(hq, nth(null, 2, 100));
    var updated = promotions.save(hq, new Rule(
        item.id(), " 更新九折 ", "ITEM_PERCENT", 10, 99, "CATEGORY", null,
        "手作烘焙", "taipei", 10L, 20L, false));

    assertThat(updated.name()).isEqualTo("更新九折");
    assertThat(updated.nth()).isZero();
    assertThat(updated.productId()).isNull();
    assertThat(promotions.list(hq))
        .extracting(Rule::id)
        .containsExactlyInAnyOrder(item.id(), nth.id());
    assertThat(db.queryForObject(
        "select count(*) from audit_log where action='PROMOTION_SAVE'", Integer.class))
        .isEqualTo(3);
    assertThat(db.queryForObject(
        "select count(*) from audit_log where action='PROMOTION_SAVE' and branch_id is null",
        Integer.class)).isEqualTo(3);
  }

  @Test
  void httpEndpointsRequireHeadquartersAuthenticationAndCsrf() throws Exception {
    mvc.perform(post("/api/promotions").session(session("hq")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("NTH_PERCENT"))
        .andExpect(jsonPath("$.nth").value(2));
    mvc.perform(get("/api/promotions").session(session("hq")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("HTTP 買一送一"));

    mvc.perform(get("/api/promotions")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/promotions").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json()))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/promotions").session(session("hq"))
            .contentType(MediaType.APPLICATION_JSON).content(json()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/promotions").session(session("manager")))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/promotions").session(session("manager")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/promotions").session(session("customer")))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/promotions").session(session("customer")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json()))
        .andExpect(status().isForbidden());
  }

  @Test
  void validatesEverySpecifiedField() {
    assertProblem(400, "請提供品項促銷", () -> promotions.save(hq, null));
    assertProblem(400, "促銷名稱需為 1–40 字", () -> promotions.save(hq, withName(" ")));
    assertProblem(400, "促銷型態不正確", () -> promotions.save(hq, withKind("OTHER", 10, 0)));
    assertProblem(400, "每件折扣的百分比需為 1–90", () -> promotions.save(hq, withKind("ITEM_PERCENT", 91, 0)));
    assertProblem(400, "第 N 件折扣的百分比需為 1–100", () -> promotions.save(hq, withKind("NTH_PERCENT", 0, 2)));
    assertProblem(400, "第 N 件折扣的 N 需大於或等於 2", () -> promotions.save(hq, withKind("NTH_PERCENT", 50, 1)));
    assertProblem(400, "促銷目標不正確", () -> promotions.save(hq, withTarget("OTHER", null, null)));
    assertProblem(404, "找不到指定的商品", () -> promotions.save(hq, withTarget("PRODUCT", "missing", null)));
    assertProblem(400, "促銷分類需為 1–40 字", () -> promotions.save(hq, withTarget("CATEGORY", null, " ")));
    assertProblem(404, "找不到指定的分店", () -> promotions.save(hq, withBranch("missing")));
    assertProblem(400, "促銷結束時間不能早於開始時間", () -> promotions.save(hq, withPeriod(2L, 1L)));
    assertProblem(404, "找不到品項促銷", () -> promotions.save(hq, item("missing", 10)));
  }

  @Test
  void migrationAddsSnapshotTablesAndDefaultedOrderColumns() {
    assertThat(db.queryForObject("select count(*) from item_promotions", Integer.class)).isZero();
    assertThat(db.queryForObject("select count(*) from order_item_promotions", Integer.class)).isZero();
    assertThat(db.queryForObject(
        "select count(*) from information_schema.columns where table_name='orders' and column_name='item_discount_amount'",
        Integer.class)).isEqualTo(1);
    assertThat(db.queryForObject(
        "select count(*) from information_schema.columns where table_name='order_items' and column_name='discount_amount'",
        Integer.class)).isEqualTo(1);
  }

  private Rule item(String id, int percent) {
    return new Rule(id, "拿鐵折扣", "ITEM_PERCENT", percent, 0, "PRODUCT", "latte", null,
        null, null, null, true);
  }

  private Rule nth(String id, int nth, int percent) {
    return new Rule(id, "買一送一", "NTH_PERCENT", percent, nth, "PRODUCT", "latte", null,
        null, null, null, true);
  }

  private Rule withName(String name) {
    var r = item(null, 10);
    return new Rule(null, name, r.kind(), r.percent(), r.nth(), r.targetKind(), r.productId(),
        r.category(), r.branchId(), r.startsAt(), r.endsAt(), r.active());
  }

  private Rule withKind(String kind, int percent, int nth) {
    var r = item(null, 10);
    return new Rule(null, r.name(), kind, percent, nth, r.targetKind(), r.productId(), null,
        null, null, null, true);
  }

  private Rule withTarget(String kind, String productId, String category) {
    var r = item(null, 10);
    return new Rule(null, r.name(), r.kind(), r.percent(), r.nth(), kind, productId, category,
        null, null, null, true);
  }

  private Rule withBranch(String branchId) {
    var r = item(null, 10);
    return new Rule(null, r.name(), r.kind(), r.percent(), r.nth(), r.targetKind(), r.productId(),
        null, branchId, null, null, true);
  }

  private Rule withPeriod(Long startsAt, Long endsAt) {
    var r = item(null, 10);
    return new Rule(null, r.name(), r.kind(), r.percent(), r.nth(), r.targetKind(), r.productId(),
        null, null, startsAt, endsAt, true);
  }

  private MockHttpSession session(String id) {
    var session = new MockHttpSession();
    session.setAttribute("ACCOUNT_ID", id);
    session.setAttribute("ACCOUNT_VERSION", identity.sessionVersion(id));
    return session;
  }

  private String json() {
    return "{\"name\":\"HTTP 買一送一\",\"kind\":\"NTH_PERCENT\",\"percent\":100,"
        + "\"nth\":2,\"targetKind\":\"PRODUCT\",\"productId\":\"latte\",\"active\":true}";
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
