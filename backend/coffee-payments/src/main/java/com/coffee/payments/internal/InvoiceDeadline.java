package com.coffee.payments.internal;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/** G03: leave one full day before the provider's statutory void deadline. */
public final class InvoiceDeadline {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

  private InvoiceDeadline() {}

  public static boolean canVoid(String invoiceDate, Instant now) {
    if (invoiceDate == null || invoiceDate.length() < 10 || now == null) return false;
    try {
      LocalDate date = LocalDate.parse(invoiceDate.substring(0, 10));
      int month = date.getMonthValue();
      Instant cutoff = YearMonth.of(date.getYear(), month % 2 == 0 ? month : month + 1)
          .plusMonths(1).atDay(13).atStartOfDay(TAIPEI).toInstant();
      return !now.atZone(TAIPEI).toLocalDate().isBefore(date) && now.isBefore(cutoff);
    } catch (DateTimeException invalidDate) {
      // A malformed/future provider date must not authorize a void operation.
      return false;
    }
  }
}
