package com.coffee.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ModuleBoundariesTest {
  @Test
  void businessModulesHaveNoCyclesAndOnlyConsumePublicContracts() {
    var classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.coffee");
    slices().matching("com.coffee.(*)..").should().beFreeOfCycles().check(classes);
    for (String module :
        new String[] {"identity", "branches", "catalog", "orders", "payments", "reporting"})
      noClasses()
          .that()
          .resideOutsideOfPackages("com.coffee." + module + "..", "com.coffee.app..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.coffee." + module + ".internal..")
          .check(classes);
  }
}
