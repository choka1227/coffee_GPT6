package com.coffee.payments.api;

import com.coffee.shared.Actor;
import java.util.List;
import java.util.Optional;

public interface Invoices {
  record Invoice(String orderId, String branchId, int amount, String status,
                 String invoiceNo, String invoiceDate, String randomNumber,
                 int attempts, String lastError, long updatedAt,
                 String reversalKind, String reversalStatus, String allowanceNo, String reversalLastError) {}
  record InvoicePage(List<Invoice> items, boolean truncated) {}
  record Reversal(String orderId, String branchId, String invoiceNo, int amount, String kind,
                  String status, String allowanceNo, int attempts, String lastError, long updatedAt) {}
  record ReversalPage(List<Reversal> items, boolean truncated) {}
  Optional<Invoice> forOrder(Actor actor, String orderId);
  InvoicePage failed(Actor actor, String branchId);
  Invoice retry(Actor actor, String orderId);
  ReversalPage failedReversals(Actor actor, String branchId);
  Reversal retryReversal(Actor actor, String orderId);
}
