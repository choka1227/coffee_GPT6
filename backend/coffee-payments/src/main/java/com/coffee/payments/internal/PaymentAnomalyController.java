package com.coffee.payments.internal;

import com.coffee.payments.api.Reconciliation;
import com.coffee.shared.Actor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments/anomalies")
class PaymentAnomalyController {
  private final Reconciliation service;

  PaymentAnomalyController(Reconciliation service) {
    this.service = service;
  }

  record Resolution(String note) {}

  @GetMapping
  Reconciliation.AnomalyPage anomalies(@RequestAttribute Actor actor) {
    return service.anomalies(actor);
  }

  @PostMapping("/{id}/resolve")
  Reconciliation.Anomaly resolve(
      @RequestAttribute Actor actor, @PathVariable String id, @RequestBody Resolution request) {
    return service.resolveAnomaly(actor, id, request.note());
  }
}
