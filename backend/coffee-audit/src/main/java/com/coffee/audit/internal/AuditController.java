package com.coffee.audit.internal;

import com.coffee.audit.api.Audit;
import com.coffee.shared.Actor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
class AuditController {
  private final Audit audit;

  AuditController(Audit audit) {
    this.audit = audit;
  }

  @GetMapping
  Audit.Page search(
      @RequestAttribute Actor actor,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String actorId,
      @RequestParam(required = false) String branchId,
      @RequestParam(required = false) Long from,
      @RequestParam(required = false) Long to,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "50") int limit) {
    return audit.search(actor, new Audit.Query(action, actorId, branchId, from, to, cursor, limit));
  }
}
