package com.coffee.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class TimeZoneGuardTest {
  @Test
  void dateAndTimeTypesRequireAnExplicitZoneOrClock() {
    JavaClasses classes = new ClassFileImporter().importPackages("com.coffee");

    for (Class<?> type :
        new Class<?>[] {
          YearMonth.class,
          LocalDate.class,
          LocalDateTime.class,
          ZonedDateTime.class,
          LocalTime.class
        }) {
      noClasses()
          .should()
          .callMethod(type, "now")
          .because(type.getSimpleName() + ".now() uses the runner default time zone")
          .check(classes);
    }
  }
}
