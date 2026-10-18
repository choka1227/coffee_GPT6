package com.coffee.payments.internal;

import com.coffee.payments.api.Invoices;
import com.coffee.shared.Actor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments/invoices")
class InvoiceController {
  private final Invoices service;
  InvoiceController(Invoices service) { this.service = service; }

  @GetMapping("/order/{orderId}")
  Object forOrder(@RequestAttribute Actor actor, @PathVariable String orderId) {
    return service.forOrder(actor, orderId).<Object>map(value -> value).orElseGet(() -> {
      Map<String, Object> none = new LinkedHashMap<>();
      for (String field : new String[] {"orderId", "branchId", "amount", "invoiceNo", "invoiceDate",
          "randomNumber", "attempts", "lastError", "updatedAt", "reversalKind", "reversalStatus", "allowanceNo", "reversalLastError"}) none.put(field, null);
      none.put("status", "NONE");
      return Collections.unmodifiableMap(none);
    });
  }

  @GetMapping("/failed")
  Invoices.InvoicePage failed(@RequestAttribute Actor actor,
      @RequestParam(required = false) String branchId) { return service.failed(actor, branchId); }

  @PostMapping("/order/{orderId}/retry")
  Invoices.Invoice retry(@RequestAttribute Actor actor, @PathVariable String orderId) {
    return service.retry(actor, orderId);
  }

  @GetMapping("/reversals/failed")
  Invoices.ReversalPage failedReversals(@RequestAttribute Actor actor,
      @RequestParam(required = false) String branchId) { return service.failedReversals(actor, branchId); }

  @PostMapping("/order/{orderId}/reversal/retry")
  Invoices.Reversal retryReversal(@RequestAttribute Actor actor, @PathVariable String orderId) {
    return service.retryReversal(actor, orderId);
  }
}
