package com.coffee.orders.internal;

import com.coffee.orders.api.CashSessions;
import com.coffee.shared.Actor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cash-sessions")
class CashSessionController {
  private final CashSessions sessions;

  CashSessionController(CashSessions sessions) {
    this.sessions = sessions;
  }

  @PostMapping
  CashSessions.Session open(
      @RequestAttribute Actor actor, @RequestBody CashSessions.Open request) {
    return sessions.open(actor, request);
  }

  @GetMapping("/current")
  CashSessions.Session current(
      @RequestAttribute Actor actor, @RequestParam(required = false) String branchId) {
    return sessions.current(actor, branchId);
  }

  @GetMapping("/{id}")
  CashSessions.Session get(@RequestAttribute Actor actor, @PathVariable String id) {
    return sessions.get(actor, id);
  }

  @GetMapping
  CashSessions.Page search(
      @RequestAttribute Actor actor,
      @RequestParam(required = false) String branchId,
      @RequestParam(required = false) Long from,
      @RequestParam(required = false) Long to,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "50") int limit) {
    return sessions.search(actor, new CashSessions.Query(branchId, from, to, cursor, limit));
  }

  @PostMapping("/{id}/close")
  CashSessions.Session close(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestBody CashSessions.Close request) {
    return sessions.close(actor, id, request);
  }
}
