package com.coffee.app.security;

import com.coffee.identity.api.Identity;
import com.coffee.shared.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final Identity identity;
  private final HttpSessionSecurityContextRepository contextRepository;
  private final CookieCsrfTokenRepository csrfRepository;
  private final RateLimiter logins =
      new RateLimiter("登入嘗試過多，請 15 分鐘後再試", 20, 900_000L, 10_000);

  record Login(String username, String password) {}

  record Password(String oldPassword, String newPassword) {}

  public AuthController(Identity i, HttpSessionSecurityContextRepository contextRepository,
      CookieCsrfTokenRepository csrfRepository) {
    identity = i;
    this.contextRepository = contextRepository;
    this.csrfRepository = csrfRepository;
  }

  @GetMapping("/csrf")
  Map<String, String> csrf(CsrfToken csrf) {
    return Map.of("token", csrf.getToken(), "headerName", csrf.getHeaderName());
  }

  @PostMapping("/login")
  Actor login(@RequestBody Login input, HttpServletRequest req, HttpServletResponse response) {
    Problem.check(
        input.username() != null
            && input.username().length() <= 100
            && input.password() != null
            && input.password().length() <= 100,
        "帳號或密碼格式不正確");
    String key = req.getRemoteAddr();
    logins.hit(key, System.currentTimeMillis());
    Actor a = identity.authenticate(input.username(), input.password());
    logins.clear(key);
    req.getSession(true);
    req.changeSessionId();
    req.getSession().setAttribute("ACCOUNT_ID", a.id());
    req.getSession().setAttribute("ACCOUNT_VERSION", identity.sessionVersion(a.id()));
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new UsernamePasswordAuthenticationToken(a, null,
        a.permissions().stream().map(SimpleGrantedAuthority::new).toList()));
    SecurityContextHolder.setContext(context);
    contextRepository.saveContext(context, req, response);
    csrfRepository.saveToken(null, req, response);
    return a;
  }

  @GetMapping("/me")
  Actor me(@RequestAttribute Actor actor) {
    return actor;
  }

  @PostMapping("/logout")
  void logout(HttpServletRequest req, HttpServletResponse response) {
    var s = req.getSession(false);
    if (s != null) s.invalidate();
    SecurityContextHolder.clearContext();
    csrfRepository.saveToken(null, req, response);
  }

  @PostMapping("/password")
  void password(@RequestAttribute Actor actor, @RequestBody Password p, HttpServletRequest req) {
    identity.password(actor, p.oldPassword(), p.newPassword());
    req.changeSessionId();
    req.getSession().setAttribute("ACCOUNT_VERSION", identity.sessionVersion(actor.id()));
  }
}
