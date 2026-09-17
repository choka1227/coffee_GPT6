package com.coffee.payments.internal;

import com.coffee.payments.api.Reconciliation;
import com.coffee.shared.Actor;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments/reconciliation")
class ReconciliationController {
  private final Reconciliation service;

  ReconciliationController(Reconciliation service) {
    this.service = service;
  }

  @GetMapping("/pending")
  Map<String, ?> pending(@RequestAttribute Actor actor) {
    return Map.of("items", service.pending(actor));
  }

  @GetMapping("/{id}")
  Map<String, ?> history(@RequestAttribute Actor actor, @PathVariable String id) {
    return Map.of("orderId", id, "attempts", service.history(actor, id));
  }

  @PostMapping("/{id}")
  Reconciliation.Result reconcile(@RequestAttribute Actor actor, @PathVariable String id) {
    return service.reconcile(actor, id);
  }
}
