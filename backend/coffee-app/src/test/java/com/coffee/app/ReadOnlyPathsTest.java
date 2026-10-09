package com.coffee.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.coffee.app.readonly.ReadOnlyWalker;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadOnlyPathsTest {
  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.coffee");

  @Test
  void reportingModuleOnlyReadsThroughJdbcTemplate() {
    var entryPoints =
        PRODUCTION_CLASSES.stream()
            .filter(type -> type.getPackageName().startsWith("com.coffee.reporting"))
            .flatMap(type -> type.getMethods().stream())
            .toList();

    assertThat(ReadOnlyWalker.violations(PRODUCTION_CLASSES, entryPoints)).isEmpty();
  }

  @Test
  void reportingModuleDoesNotTouchRawJdbc() {
    noClasses()
        .that()
        .resideInAPackage("com.coffee.reporting..")
        .should()
        .dependOnClassesThat()
        .haveNameMatching(
            "javax\\.sql\\.DataSource|java\\.sql\\.(Connection|Statement|PreparedStatement|CallableStatement)")
        .check(PRODUCTION_CLASSES);
  }

  @Test
  void theWalkerFindsWritesThatAreThreeHopsAwayAndBehindAnInterface() {
    var fixtureClasses = new ClassFileImporter().importPackages("com.coffee.app.readonly.fixture");
    List<JavaMethod> entryPoints =
        fixtureClasses.stream()
            .flatMap(type -> type.getMethods().stream())
            .filter(method -> method.getName().equals("entry"))
            .toList();

    var violations = ReadOnlyWalker.violations(fixtureClasses, entryPoints);

    assertThat(violations).hasSize(3);
    assertThat(violations).anySatisfy(violation -> assertThat(violation).contains("DirectWriter"));
    assertThat(violations)
        .anySatisfy(violation -> assertThat(violation).contains("IndirectWriter").contains("tail"));
    assertThat(violations).anySatisfy(violation -> assertThat(violation).contains("SinkImpl"));
    assertThat(violations).noneSatisfy(violation -> assertThat(violation).contains("CleanReader"));
  }
}
