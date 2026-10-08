package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:login-limit-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "server.servlet.session.cookie.secure=false"
    })
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LoginRateLimitTest {
  @LocalServerPort int port;
  final ObjectMapper json = new ObjectMapper();

  @Test
  void twentyFailuresReturnUnauthorizedAndTheNextAttemptIsRateLimited() throws Exception {
    var browser = new BrowserSession();
    browser.refreshCsrf();

    for (int i = 0; i < 20; i++) {
      var response = browser.login("customer@coffee.local", "wrong");
      assertThat(response.statusCode()).isEqualTo(401);
      assertThat(message(response)).isEqualTo("帳號或密碼錯誤");
    }

    var blocked = browser.login("customer@coffee.local", "wrong");
    assertThat(blocked.statusCode()).isEqualTo(429);
    assertThat(message(blocked)).isEqualTo("登入嘗試過多，請 15 分鐘後再試");
  }

  @Test
  void successfulLoginClearsPreviousFailures() throws Exception {
    var browser = new BrowserSession();
    browser.refreshCsrf();
    for (int i = 0; i < 10; i++) {
      assertThat(browser.login("customer@coffee.local", "wrong").statusCode()).isEqualTo(401);
    }

    assertThat(browser.login("customer@coffee.local", "CoffeeDemo!2026").statusCode())
        .isEqualTo(200);
    browser.refreshCsrf();

    for (int i = 0; i < 20; i++) {
      assertThat(browser.login("customer@coffee.local", "wrong").statusCode()).isEqualTo(401);
    }
    assertThat(browser.login("customer@coffee.local", "wrong").statusCode()).isEqualTo(429);
  }

  private String message(HttpResponse<String> response) throws Exception {
    return json.readTree(response.body()).get("message").asText();
  }

  class BrowserSession {
    final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String token;
    String header;

    void refreshCsrf() throws Exception {
      var response = request("GET", "/api/auth/csrf", null);
      assertThat(response.statusCode()).isEqualTo(200);
      JsonNode csrf = json.readTree(response.body());
      token = csrf.get("token").asText();
      header = csrf.get("headerName").asText();
    }

    HttpResponse<String> login(String username, String password) throws Exception {
      return request(
          "POST",
          "/api/auth/login",
          json.writeValueAsString(Map.of("username", username, "password", password)));
    }

    HttpResponse<String> request(String method, String path, String body) throws Exception {
      var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
      if (body != null) builder.header("Content-Type", "application/json");
      if (!method.equals("GET") && token != null) builder.header(header, token);
      return client.send(
          builder
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
