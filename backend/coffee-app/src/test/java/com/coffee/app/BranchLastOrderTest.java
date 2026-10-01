package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffee.branches.api.Branches.DayOverride;
import com.coffee.branches.api.Branches.Hours;
import com.coffee.branches.internal.BranchService;
import com.coffee.branches.internal.BranchService.OpenWindow;
import com.coffee.branches.internal.BranchService.Resolver;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class BranchLastOrderTest {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
  private static final LocalDate MONDAY = LocalDate.of(2026, 9, 21);

  record ScheduleCase(String name, Resolver resolver, IntPredicate expectedOpen) {
    @Override
    public String toString() {
      return name;
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("schedules")
  void windowAtMatchesThePreviousRulesForEveryMinute(ScheduleCase schedule) {
    for (int minute = 0; minute < 1440; minute++) {
      boolean open =
          !(BranchService.windowAt(schedule.resolver(), at(MONDAY, minute))
              instanceof OpenWindow.NotOpen);
      assertThat(open).as("%s at minute %s", schedule.name(), minute)
          .isEqualTo(schedule.expectedOpen().test(minute));
    }
  }

  @Test
  void overnightWindowReportsMinutesUntilTheNextDayClose() {
    var window = BranchService.windowAt(weekly(List.of(new Hours(1, 1320, 120))), at(MONDAY, 1380));

    assertThat(window).isEqualTo(new OpenWindow.ClosesIn(180));
  }

  private static Stream<ScheduleCase> schedules() {
    var overridePeriods =
        Map.of(
            MONDAY,
            new DayOverride(
                20260921, false, "臨時調整", List.of(new Hours(1, 600, 700))));
    var overrideClosed =
        Map.of(MONDAY, new DayOverride(20260921, true, "公休", List.of()));
    return Stream.of(
        new ScheduleCase(
            "weekly single period",
            weekly(List.of(new Hours(1, 540, 1260))),
            minute -> minute >= 540 && minute < 1260),
        new ScheduleCase(
            "weekly split periods",
            weekly(List.of(new Hours(1, 540, 720), new Hours(1, 780, 1080))),
            minute -> (minute >= 540 && minute < 720) || (minute >= 780 && minute < 1080)),
        new ScheduleCase(
            "weekly overnight period",
            weekly(List.of(new Hours(7, 1320, 120), new Hours(1, 1320, 120))),
            minute -> minute < 120 || minute >= 1320),
        new ScheduleCase(
            "override periods",
            resolver(List.of(new Hours(1, 540, 1260)), overridePeriods),
            minute -> minute >= 600 && minute < 700),
        new ScheduleCase(
            "override closed",
            resolver(List.of(new Hours(1, 540, 1260)), overrideClosed),
            minute -> false),
        new ScheduleCase("no weekly periods", weekly(List.of()), minute -> true));
  }

  private static Resolver weekly(List<Hours> schedule) {
    return resolver(schedule, Map.of());
  }

  private static Resolver resolver(
      List<Hours> weekly, Map<LocalDate, DayOverride> overrides) {
    return date ->
        BranchService.resolveDay(
            date,
            weekly.isEmpty(),
            weekly.stream()
                .filter(period -> period.dayOfWeek() == date.getDayOfWeek().getValue())
                .toList(),
            overrides.get(date));
  }

  private static long at(LocalDate date, int minute) {
    return date.atStartOfDay(TAIPEI).plusMinutes(minute).toInstant().toEpochMilli();
  }
}
