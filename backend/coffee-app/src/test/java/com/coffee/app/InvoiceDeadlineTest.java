package com.coffee.app;

import static org.assertj.core.api.Assertions.*;

import com.coffee.payments.internal.InvoiceDeadline;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class InvoiceDeadlineTest {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
  void allBimonthlyPeriodsAcceptTwelfthButNeverThirteenth(int month) {
    String date = LocalDate.of(2026, month, 1).toString();
    var nextMonth = YearMonth.of(2026, month % 2 == 0 ? month : month + 1).plusMonths(1);
    Instant cutoff = nextMonth.atDay(13).atStartOfDay(TAIPEI).toInstant();
    assertThat(InvoiceDeadline.canVoid(date, cutoff.minusSeconds(1))).isTrue();
    assertThat(InvoiceDeadline.canVoid(date + " 12:30:45", cutoff.minusNanos(1))).isTrue();
    assertThat(InvoiceDeadline.canVoid(date, cutoff)).isFalse();
    assertThat(InvoiceDeadline.canVoid(date, cutoff.plusSeconds(86399))).isFalse();
  }

  @Test void leapDayAndCrossYearUseTaipeiRatherThanUtcCalendar() {
    assertThat(InvoiceDeadline.canVoid("2024-02-29", Instant.parse("2024-03-12T15:59:59.999Z"))).isTrue();
    assertThat(InvoiceDeadline.canVoid("2024-02-29", Instant.parse("2024-03-12T16:00:00Z"))).isFalse();
    for (String date : new String[] {"2026-11-01", "2026-12-31"}) {
      assertThat(InvoiceDeadline.canVoid(date, Instant.parse("2027-01-12T15:59:59Z"))).isTrue();
      assertThat(InvoiceDeadline.canVoid(date, Instant.parse("2027-01-12T16:00:00Z"))).isFalse();
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"2026", "2026-02-30", "2026-2-01", "not-a-date", "2025-02-29", "2026-13-01"})
  void malformedDateNeverAuthorizesVoid(String date) {
    assertThat(InvoiceDeadline.canVoid(date, Instant.parse("2026-03-01T00:00:00Z"))).isFalse();
  }

  @Test void missingClockAndFutureInvoiceNeverAuthorizeVoid() {
    assertThat(InvoiceDeadline.canVoid("2026-10-01", null)).isFalse();
    assertThat(InvoiceDeadline.canVoid("2026-10-01", Instant.parse("2026-09-30T15:59:59Z"))).isFalse();
    assertThat(InvoiceDeadline.canVoid("2026-10-01", Instant.parse("2026-09-30T16:00:00Z"))).isTrue();
  }
}
