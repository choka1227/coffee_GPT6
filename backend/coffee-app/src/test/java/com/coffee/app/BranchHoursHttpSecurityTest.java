package com.coffee.app;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffee.identity.api.Identity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:branch-hours-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("dev")
@AutoConfigureMockMvc
class BranchHoursHttpSecurityTest {
  @Autowired MockMvc mvc;
  @Autowired Identity identity;

  @Test
  void readingRequiresLoginButAnyAuthenticatedCustomerMayRead() throws Exception {
    mvc.perform(get("/api/branches/banqiao/hours")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/branches/banqiao/hours").session(session("customer")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.branchId").value("banqiao"));
  }

  @Test
  void putUsesExistingCsrfProtectionAndHeadquartersAuthorization() throws Exception {
    String body =
        "{\"hours\":[{\"dayOfWeek\":1,\"openMinute\":540,\"closeMinute\":1260}]}";
    var hq = session("hq");
    mvc.perform(
            put("/api/branches/taipei/hours")
                .session(hq)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/api/branches/taipei/hours")
                .session(hq)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hours[0].openMinute").value(540));
    mvc.perform(
            put("/api/branches/taipei/hours")
                .session(session("customer"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
  }

  private MockHttpSession session(String accountId) {
    var session = new MockHttpSession();
    session.setAttribute("ACCOUNT_ID", accountId);
    session.setAttribute("ACCOUNT_VERSION", identity.sessionVersion(accountId));
    return session;
  }
}
