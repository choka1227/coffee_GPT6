package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffee.catalog.api.Discounts;
import com.coffee.catalog.api.Discounts.Rule;
import com.coffee.identity.api.Identity;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import java.util.Set;
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
      "spring.datasource.url=jdbc:h2:mem:discount-admin;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("dev")
@AutoConfigureMockMvc
class DiscountAdminTest {
  @Autowired Discounts discounts;
  @Autowired Identity identity;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;

  Actor hq;

  @BeforeEach
  void reset() {
    db.update("delete from order_discounts");
    db.update("delete from discounts");
    hq = identity.find("hq");
  }

  @Test
  void headquartersCanSaveListAndUpdateWithoutReplacingUsage() {
    var saved = discounts.save(hq, valid(null, " save-10 "));
    discounts.apply("SAVE-10", "taipei", 500, System.currentTimeMillis());
    var updated = discounts.save(hq, new Rule(
        saved.id(), saved.code(), "新名稱", saved.kind(), saved.percent(), saved.amount(),
        saved.minSubtotal(), saved.branchId(), saved.startsAt(), saved.endsAt(),
        saved.maxRedemptions(), 999, saved.active()));

    assertThat(updated.redeemedCount()).isEqualTo(1);
    assertThat(discounts.list(hq)).extracting(Rule::code).containsExactly("SAVE-10");
    assertThat(db.queryForObject(
        "select count(*) from audit_log where action='DISCOUNT_SAVE' and target_id=?",
        Integer.class, saved.id())).isEqualTo(2);
  }

  @Test
  void globalPermissionAndCsrfAreRequired() throws Exception {
    var branch = new Actor("m", "m", "店長", "MANAGER", "BRANCH", "taipei", Set.of("MENU_MANAGE"));
    assertProblem(403, "只有總部可以維護優惠碼", () -> discounts.list(branch));
    assertProblem(403, "沒有此功能的操作權限", () -> discounts.list(identity.find("customer")));

    mvc.perform(get("/api/discounts")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/discounts").session(session("hq"))
        .contentType(MediaType.APPLICATION_JSON).content(json())).andExpect(status().isForbidden());
  }

  @Test
  void validatesEveryRuleFieldAndConflictStatus() {
    assertProblem(400, "優惠碼格式不正確", () -> discounts.save(hq, valid(null, "x")));
    assertProblem(400, "優惠碼名稱需為 1–40 字", () -> discounts.save(hq, withName(" ")));
    assertProblem(400, "折扣類型不正確", () -> discounts.save(hq, withKind("OTHER", 0, 0)));
    assertProblem(400, "折扣百分比需為 1–90", () -> discounts.save(hq, withKind("PERCENT", 0, 0)));
    assertProblem(400, "百分比折扣不可設定定額金額", () -> discounts.save(hq, withKind("PERCENT", 10, 1)));
    assertProblem(400, "折抵金額需為 1 元以上", () -> discounts.save(hq, withKind("AMOUNT", 0, 0)));
    assertProblem(400, "定額折扣不可設定百分比", () -> discounts.save(hq, withKind("AMOUNT", 1, 10)));
    assertProblem(400, "最低消費金額不正確", () -> discounts.save(hq, withMinimum(-1)));
    assertProblem(400, "優惠期間的起訖時間不正確", () -> discounts.save(hq, withPeriod(2L, 1L)));
    assertProblem(400, "使用次數上限需大於 0", () -> discounts.save(hq, withMaximum(0)));
    assertProblem(404, "找不到分店", () -> discounts.save(hq, withBranch("missing")));
    discounts.save(hq, valid(null, "DUPL"));
    assertProblem(409, "優惠碼已存在", () -> discounts.save(hq, valid(null, "dupl")));
  }

  private Rule valid(String id, String code) {
    return new Rule(id, code, "測試優惠", "PERCENT", 10, 0, 0, null, null, null, null, 0, true);
  }
  private Rule withName(String name) { var r=valid(null,"NAME"); return new Rule(r.id(),r.code(),name,r.kind(),r.percent(),r.amount(),r.minSubtotal(),r.branchId(),r.startsAt(),r.endsAt(),r.maxRedemptions(),0,true); }
  private Rule withKind(String kind,int percent,int amount) { var r=valid(null,"KIND"); return new Rule(null,r.code(),r.name(),kind,percent,amount,0,null,null,null,null,0,true); }
  private Rule withMinimum(int value) { var r=valid(null,"MINI"); return new Rule(null,r.code(),r.name(),r.kind(),r.percent(),0,value,null,null,null,null,0,true); }
  private Rule withPeriod(Long start,Long end) { var r=valid(null,"TIME"); return new Rule(null,r.code(),r.name(),r.kind(),r.percent(),0,0,null,start,end,null,0,true); }
  private Rule withMaximum(Integer value) { var r=valid(null,"MAXI"); return new Rule(null,r.code(),r.name(),r.kind(),r.percent(),0,0,null,null,null,value,0,true); }
  private Rule withBranch(String value) { var r=valid(null,"BRAN"); return new Rule(null,r.code(),r.name(),r.kind(),r.percent(),0,0,value,null,null,null,0,true); }
  private MockHttpSession session(String id) { var s=new MockHttpSession(); s.setAttribute("ACCOUNT_ID",id); s.setAttribute("ACCOUNT_VERSION",identity.sessionVersion(id)); return s; }
  private String json() { return "{\"code\":\"HTTP\",\"name\":\"HTTP\",\"kind\":\"PERCENT\",\"percent\":10,\"amount\":0,\"minSubtotal\":0,\"active\":true}"; }
  private static void assertProblem(int status,String message,org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call).isInstanceOfSatisfying(Problem.class, p -> { assertThat(p.status).isEqualTo(status); assertThat(p).hasMessage(message); });
  }
}
