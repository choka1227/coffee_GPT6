package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffee.branches.api.Branches.Hours;
import com.coffee.branches.internal.BranchService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class BranchHoursTest {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

  private long taipei(int day, int hour, int minute, int second, int nano) {
    return LocalDateTime.of(2026, 9, day, hour, minute, second, nano)
        .atZone(TAIPEI)
        .toInstant()
        .toEpochMilli();
  }

  @Test
  void noScheduleMeansAlwaysOpen() {
    assertThat(BranchService.isOpenAt(List.of(), taipei(21, 3, 0, 0, 0))).isTrue();
  }

  @Test
  void regularWindowsAreLeftClosedAndRightOpen() {
    var hours = List.of(new Hours(1, 540, 1260));

    assertThat(BranchService.isOpenAt(hours, taipei(21, 8, 59, 0, 0))).isFalse();
    assertThat(BranchService.isOpenAt(hours, taipei(21, 9, 0, 0, 0))).isTrue();
    assertThat(BranchService.isOpenAt(hours, taipei(21, 20, 59, 59, 999_000_000))).isTrue();
    assertThat(BranchService.isOpenAt(hours, taipei(21, 21, 0, 0, 0))).isFalse();
    assertThat(BranchService.isOpenAt(hours, taipei(22, 12, 0, 0, 0))).isFalse();
  }

  @Test
  void splitAndAdjacentWindowsUseOneSharedBoundary() {
    var split = List.of(new Hours(1, 660, 840), new Hours(1, 1020, 1260));
    assertThat(BranchService.isOpenAt(split, taipei(21, 15, 0, 0, 0))).isFalse();
    assertThat(BranchService.isOpenAt(split, taipei(21, 13, 59, 0, 0))).isTrue();
    assertThat(BranchService.isOpenAt(split, taipei(21, 17, 0, 0, 0))).isTrue();

    var adjacent = List.of(new Hours(1, 540, 840), new Hours(1, 840, 1260));
    assertThat(BranchService.isOpenAt(adjacent, taipei(21, 14, 0, 0, 0))).isTrue();
  }

  @Test
  void overnightWindowsCoverBothDaysAndWrapSundayToMonday() {
    var saturday = List.of(new Hours(6, 1320, 120));
    assertThat(BranchService.isOpenAt(saturday, taipei(26, 23, 0, 0, 0))).isTrue();
    assertThat(BranchService.isOpenAt(saturday, taipei(27, 1, 0, 0, 0))).isTrue();
    assertThat(BranchService.isOpenAt(saturday, taipei(27, 2, 0, 0, 0))).isFalse();
    assertThat(BranchService.isOpenAt(saturday, taipei(27, 12, 0, 0, 0))).isFalse();

    var sunday = List.of(new Hours(7, 1320, 120));
    assertThat(BranchService.isOpenAt(sunday, taipei(28, 1, 0, 0, 0))).isTrue();
  }

  @Test
  void midnightAt1440DoesNotSpillIntoNextDay() {
    var hours = List.of(new Hours(1, 540, 1440));
    assertThat(BranchService.isOpenAt(hours, taipei(21, 23, 59, 0, 0))).isTrue();
    assertThat(BranchService.isOpenAt(hours, taipei(22, 0, 0, 0, 0))).isFalse();
  }

  @Test
  void fixedEpochIsConvertedUsingTaipeiInsteadOfSystemDefaultZone() {
    long sundayAt2300Utc = 1789945200000L;
    assertThat(BranchService.isOpenAt(List.of(new Hours(1, 420, 480)), sundayAt2300Utc))
        .isTrue();
  }
}
