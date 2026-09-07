package com.coffee.app.security;

import com.coffee.identity.api.Identity;
import com.coffee.shared.*;
import jakarta.servlet.http.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
  private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

  private record Attempts(int count, long until) {}
  ;

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
    long now = System.currentTimeMillis();
    attempts.entrySet().removeIf(e -> e.getValue().until < now);
    var current = attempts.get(key);
    if (current != null && current.count >= 20) throw new Problem(429, "登入嘗試過多，請 15 分鐘後再試");
    if (attempts.size() > 10000) throw new Problem(429, "系統忙碌，請稍後再試");
    attempts.compute(
        key,
        (k, v) -> new Attempts(v == null ? 1 : v.count + 1, v == null ? now + 900000 : v.until));
    Actor a = identity.authenticate(input.username(), input.password());
    attempts.remove(key);
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
