package com.coffee.audit.internal;

import com.coffee.audit.api.Audit;
import com.coffee.shared.Actor;
import com.coffee.shared.Ids;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AuditService implements Audit {
  private final AuditWriter writer;

  public AuditService(AuditWriter writer) {
    this.writer = writer;
  }

  @Override
  public void record(
      Actor actor, String action, String targetId, String branchId, String summary) {
    Entry entry =
        new Entry(
            Ids.next(),
            actor == null ? "system" : actor.id(),
            truncate(actor == null ? "系統" : actor.name(), 80),
            Objects.requireNonNull(action, "action"),
            truncate(Objects.requireNonNull(targetId, "targetId"), 80),
            branchId,
            truncate(summary, 200),
            System.currentTimeMillis());
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              writeQuietly(entry);
            }
          });
    } else {
      writeQuietly(entry);
    }
  }

  @Override
  public Page search(Actor actor, Query query) {
    throw new UnsupportedOperationException("Audit search is introduced in G11 S2");
  }

  private void writeQuietly(Entry entry) {
    try {
      writer.write(entry);
    } catch (RuntimeException ignored) {
      // Auditing is best effort and must never change an already committed business result.
    }
  }

  private static String truncate(String value, int limit) {
    if (value == null) return "";
    return value.length() <= limit ? value : value.substring(0, limit);
  }
}
