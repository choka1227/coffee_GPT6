package com.coffee.app.security;

import com.coffee.identity.api.Identity;
import com.coffee.shared.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionActorFilter extends OncePerRequestFilter {
  private final Identity identity;

  public SessionActorFilter(Identity identity) {
    this.identity = identity;
  }

  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    var session = req.getSession(false);
    if (session != null && session.getAttribute("ACCOUNT_ID") instanceof String id) {
      try {
        Actor actor = identity.find(id);
        if (!java.util.Objects.equals(
            session.getAttribute("ACCOUNT_VERSION"), identity.sessionVersion(id)))
          throw new Problem(401, "密碼已變更，請重新登入");
        req.setAttribute("actor", actor);
        var authentication =
            new UsernamePasswordAuthenticationToken(
                actor,
                null,
                actor.permissions().stream().map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(authentication);
      } catch (Problem e) {
        session.invalidate();
        SecurityContextHolder.clearContext();
      }
    }
    chain.doFilter(req, res);
  }
}
