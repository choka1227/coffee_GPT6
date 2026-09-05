package com.coffee.app.security;

import com.coffee.identity.api.Identity;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

@Configuration
public class SecurityConfiguration {
  @Bean
  CookieCsrfTokenRepository csrfRepository() {
    return CookieCsrfTokenRepository.withHttpOnlyFalse();
  }

  @Bean
  HttpSessionSecurityContextRepository contextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, Identity identity,
      CookieCsrfTokenRepository csrfRepository,
      HttpSessionSecurityContextRepository contextRepository) throws Exception {
    return http.csrf(
            c ->
                c.csrfTokenRepository(csrfRepository)
                    .ignoringRequestMatchers("/api/payments/ecpay/callback"))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .securityContext(s -> s.securityContextRepository(contextRepository).requireExplicitSave(true))
        .requestCache(c -> c.disable())
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/auth/csrf",
                        "/api/auth/login",
                        "/api/payments/ecpay/callback",
                        "/actuator/health")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (q, r, x) -> {
                          r.setStatus(401);
                          r.setContentType("application/json;charset=UTF-8");
                          r.getWriter().write("{\"message\":\"請先登入\"}");
                        })
                    .accessDeniedHandler(
                        (q, r, x) -> {
                          r.setStatus(403);
                          r.setContentType("application/json;charset=UTF-8");
                          r.getWriter().write("{\"message\":\"操作未獲授權或安全憑證已過期，請重新整理\"}");
                        }))
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c ->
                        c.policyDirectives(
                            "default-src 'self'; script-src 'self'; style-src 'self'"
                                + " 'unsafe-inline'; img-src 'self' data:; font-src 'self';"
                                + " connect-src 'self'; form-action 'self'"
                                + " https://payment-stage.ecpay.com.tw"
                                + " https://payment.ecpay.com.tw; frame-ancestors 'none';"
                                + " object-src 'none'; base-uri 'self'")))
        .addFilterBefore(new SessionActorFilter(identity), AnonymousAuthenticationFilter.class)
        .build();
  }
}
