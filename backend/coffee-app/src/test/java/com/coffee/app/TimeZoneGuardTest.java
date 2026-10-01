package com.coffee.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
      noParameterlessNow(type).check(classes);
    }
  }

  @Test
  void guardRejectsParameterlessLocalDateNow(@TempDir Path tempDir) throws IOException {
    Path source = tempDir.resolve("src/g27/fixture/UnsafeNowFixture.java");
    Path classes = tempDir.resolve("classes");
    Files.createDirectories(source.getParent());
    Files.createDirectories(classes);
    String unsafeCall = "LocalDate." + "now();";
    Files.writeString(
        source,
        """
        package g27.fixture;

        import java.time.LocalDate;

        final class UnsafeNowFixture {
          void unsafe() {
            %s
          }
        }
        """
            .formatted(unsafeCall));

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "A JDK compiler is required to verify the guard");
    assertEquals(
        0,
        compiler.run(null, null, null, "-d", classes.toString(), source.toString()),
        "The negative fixture must compile");

    JavaClasses fixtureClasses = new ClassFileImporter().importPath(classes);
    assertThrows(
        AssertionError.class,
        () -> noParameterlessNow(LocalDate.class).check(fixtureClasses),
        "The guard must reject LocalDate." + "now() without arguments");
  }

  private static ArchRule noParameterlessNow(Class<?> type) {
    return noClasses()
        .should()
        .callMethod(type, "now")
        .because(type.getSimpleName() + ".now() uses the runner default time zone");
  }
}
